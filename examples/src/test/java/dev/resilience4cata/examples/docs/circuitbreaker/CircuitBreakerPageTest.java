package dev.resilience4cata.examples.docs.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/policies/circuit-breaker.md}. */
class CircuitBreakerPageTest {

    private final ManualClock clock = new ManualClock();
    private final List<CircuitBreakerEvent.StateTransition> transitions = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(CircuitBreakerEvent.StateTransition.class, transitions::add)
            .build();
    private final AtomicBoolean down = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final PaymentGateway backend = orderId -> {
        calls.incrementAndGet();
        if (down.get()) {
            throw new IllegalStateException("gateway down");
        }
        return "receipt " + orderId;
    };

    @Test
    void testingOnAManualClock() {
        // tag::circuit-breaker-testing[]
        PaymentGateway payments = Resilient.of(PaymentGateway.class, backend, registry);
        CircuitBreakerHandle breaker = registry.circuitBreaker("payments");

        for (int i = 0; i < 5; i++) {
            payments.charge("ok-" + i);                       // 5 successes
        }
        down.set(true);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> payments.charge("ko")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);   // 10 calls, 50% failed

        assertThatThrownBy(() -> payments.charge("rejected")).isInstanceOf(CallNotPermittedException.class);

        down.set(false);
        ResilienceTesting.elapseWaitInOpen(breaker, clock);  // 30 s later, on the test's clock
        payments.charge("probe-1");                          // half-open: 2 probes
        payments.charge("probe-2");
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
        // end::circuit-breaker-testing[]

        assertThat(calls).hasValue(12); // the rejected call never reached the backend
        assertThat(transitions).extracting(CircuitBreakerEvent.StateTransition::to).containsExactly(
                CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED);
    }

    @Test
    void nothingIsEvaluatedBeforeMinimumCalls() {
        PaymentGateway payments = Resilient.of(PaymentGateway.class, backend, registry);
        down.set(true);
        for (int i = 0; i < 9; i++) {
            assertThatThrownBy(() -> payments.charge("ko")).isInstanceOf(IllegalStateException.class);
        }
        CircuitBreakerHandle breaker = registry.circuitBreaker("payments");
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(breaker.snapshot().failureRate()).isEqualTo(-1f);
        assertThat(breaker.snapshot().bufferedCalls()).isEqualTo(9);
        assertThatThrownBy(() -> payments.charge("ko")).isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void openMovesToHalfOpenOnTheNextCallNotOnATimer() {
        PaymentGateway payments = Resilient.of(PaymentGateway.class, backend, registry);
        CircuitBreakerHandle breaker = registry.circuitBreaker("payments");
        down.set(true);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> payments.charge("ko")).isInstanceOf(IllegalStateException.class);
        }
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
        // the first call after waitInOpen is a probe: half-open until both probes completed
        assertThatThrownBy(() -> payments.charge("probe")).isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.HALF_OPEN);
        // the second probe fails too: 100% >= 50%, open again
        assertThatThrownBy(() -> payments.charge("probe")).isInstanceOf(IllegalStateException.class);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void errorsCountAsSuccesses() {
        PaymentGateway failing = orderId -> {
            throw new AssertionError("an Error, not an Exception");
        };
        PaymentGateway payments = Resilient.of(PaymentGateway.class, failing, registry);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> payments.charge("x")).isInstanceOf(AssertionError.class);
        }
        CircuitBreakerSnapshot snapshot = registry.circuitBreaker("payments").snapshot();
        assertThat(snapshot.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(snapshot.failedCalls()).isZero();
        assertThat(snapshot.failureRate()).isZero();
    }

    @Test
    void handle() {
        PaymentGateway payments = Resilient.of(PaymentGateway.class, backend, registry);
        payments.charge("first");
        // tag::circuit-breaker-handle[]
        CircuitBreakerHandle breaker = registry.circuitBreaker("payments");
        CircuitBreakerSnapshot snapshot = breaker.snapshot();
        System.out.printf("%s is %s, %d calls, failure rate %.0f%%%n",
                snapshot.instance(), snapshot.state(), snapshot.bufferedCalls(), snapshot.failureRate());

        breaker.forceOpen();   // maintenance window: every call is rejected
        breaker.disable();     // or let every call through, recording nothing
        breaker.reset();       // back to CLOSED with an empty window
        // end::circuit-breaker-handle[]
        assertThat(snapshot.bufferedCalls()).isEqualTo(1);
        assertThat(snapshot.failureRate()).isEqualTo(-1f);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);

        breaker.forceOpen();
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.FORCED_OPEN);
        assertThatThrownBy(() -> payments.charge("x")).isInstanceOf(CallNotPermittedException.class);
        clock.advance(Duration.ofHours(1));
        assertThatThrownBy(() -> payments.charge("x")).isInstanceOf(CallNotPermittedException.class);

        breaker.disable();
        down.set(true);
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> payments.charge("ko")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.DISABLED);
        assertThat(breaker.snapshot().bufferedCalls()).isZero();

        assertThat(registry.circuitBreakers()).extracting(CircuitBreakerHandle::id).containsExactly("payments");
        assertThatThrownBy(() -> registry.circuitBreaker("paymnts"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("R4C-REG-009");
    }

    @Test
    void slowCallsAndIgnoredExceptions() {
        AtomicBoolean invalid = new AtomicBoolean();
        LedgerApi ledger = Resilient.of(LedgerApi.class, new LedgerApi() {
            @Override
            public long balance(String account) {
                if (invalid.get()) {
                    throw new IllegalArgumentException("bad account");
                }
                sleep(Duration.ofSeconds(3));
                return 42;
            }

            @Override
            public CompletableFuture<Long> balanceAsync(String account) {
                return CompletableFuture.failedFuture(new IllegalStateException("down"));
            }
        }, registry);
        String id = "dev.resilience4cata.examples.docs.circuitbreaker.LedgerApi#balance(java.lang.String)";

        invalid.set(true);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> ledger.balance("x")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(registry.circuitBreaker(id).snapshot().bufferedCalls()).isZero(); // ignored

        invalid.set(false);
        for (int i = 0; i < 4; i++) {
            assertThat(ledger.balance("a")).isEqualTo(42); // slow, but it returns
        }
        assertThat(registry.circuitBreaker(id).state()).isEqualTo(CircuitBreakerState.CLOSED);
        ledger.balance("a"); // fifth slow call in the last 60 s: 100% slow >= 80%
        assertThat(registry.circuitBreaker(id).state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    @Test
    void async() {
        LedgerApi ledger = Resilient.of(LedgerApi.class, new LedgerApi() {
            @Override
            public long balance(String account) {
                return 0;
            }

            @Override
            public CompletableFuture<Long> balanceAsync(String account) {
                return CompletableFuture.failedFuture(new IllegalStateException("down"));
            }
        }, registry);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> ledger.balanceAsync("a").join()).isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        CompletableFuture<Long> rejected = ledger.balanceAsync("a");
        assertThat(rejected).isCompletedExceptionally();
        assertThatThrownBy(rejected::join).hasCauseInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void pipeline() {
        // tag::circuit-breaker-pipeline[]
        Pipeline<String> charge = Pipeline.builder(registry, "payments", String.class)
                .circuitBreaker(cb -> cb.windowSize(10).minimumCalls(10).waitInOpen(Duration.ofSeconds(30)))
                .build();

        String receipt = charge.execute(() -> backend.charge("order-1"));
        // end::circuit-breaker-pipeline[]
        assertThat(receipt).isEqualTo("receipt order-1");
        assertThat(registry.circuitBreaker("payments").snapshot().bufferedCalls()).isEqualTo(1);
    }

    private void sleep(Duration duration) {
        try {
            clock.sleep(duration);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
