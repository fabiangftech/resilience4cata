package dev.resilience4cata.examples.docs.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/** The code of {@code docs/en/guides/observability.md}. */
class ObservabilityGuideTest {

    private final AtomicBoolean down = new AtomicBoolean(true);
    private final PaymentGateway delegate = order -> {
        if (down.get()) {
            throw new IllegalStateException("payment provider down");
        }
        return "charged " + order;
    };

    // java:S108: the empty default branch is part of a documentation snippet (the switch must be exhaustive)
    @SuppressWarnings("java:S108")
    @Test
    void listenersReceiveEventsByTypeAndByInstance() {
        List<String> log = new CopyOnWriteArrayList<>();
        // tag::observability-listen[]
        ResilienceRegistry registry = ResilienceRegistry.builder()
                // one record type, on every instance
                .listen(CircuitBreakerEvent.StateTransition.class,
                        t -> log.add(t.instance() + ": " + t.from() + " -> " + t.to()))
                // every retry event (Retrying and GaveUp), on one instance only
                .listen("payments", RetryEvent.class, e -> log.add(e.toString()))
                // everything: keep a default branch, new policies add event types
                .listen(ResilienceEvent.class, e -> {
                    switch (e) {
                        case RetryEvent.GaveUp g -> log.add("gave up after " + g.attempts() + ": " + g.reason());
                        default -> { }
                    }
                })
                .build();
        // end::observability-listen[]
        PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);

        for (int i = 0; i < 2; i++) { // 2 calls × 3 attempts: the 4th attempt opens the breaker
            assertThatThrownBy(() -> payments.charge("o-1")).isInstanceOf(RuntimeException.class);
        }

        assertThat(log).contains("payments: CLOSED -> OPEN", "gave up after 3: MAX_ATTEMPTS",
                        "gave up after 2: NOT_RETRYABLE") // the open breaker's rejection is not retried
                .anyMatch(line -> line.startsWith("Retrying[instance=payments, "));
    }

    @Test
    void theSnapshotDescribesEveryInstance() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
        down.set(false);
        payments.charge("o-1");
        List<String> lines = new CopyOnWriteArrayList<>();

        // tag::observability-snapshot[]
        ResilienceSnapshot snapshot = registry.snapshot(); // built when asked, never on the call path
        for (CircuitBreakerSnapshot b : snapshot.circuitBreakers()) {
            lines.add(b.instance() + " " + b.state() + " failureRate=" + b.failureRate()
                    + " buffered=" + b.bufferedCalls() + " failed=" + b.failedCalls() + " slow=" + b.slowCalls());
        }
        for (BulkheadSnapshot b : snapshot.bulkheads()) {
            lines.add(b.instance() + " in use " + b.concurrentCalls() + "/" + b.maxConcurrent()
                    + ", waiting " + b.waitingCalls() + ", free " + b.availableSlots());
        }
        for (RateLimiterSnapshot r : snapshot.rateLimiters()) {
            lines.add(r.instance() + " permits " + r.availablePermits() + "/" + r.permits());
        }
        // end::observability-snapshot[]

        assertThat(lines).containsExactly(
                "payments CLOSED failureRate=-1.0 buffered=1 failed=0 slow=0",
                "payments in use 0/10, waiting 0, free 10",
                "payments permits 99/100");
        assertThat(snapshot.retries()).singleElement().satisfies(r -> assertThat(r.config().maxAttempts()).isEqualTo(3));
    }

    @Test
    void theBuildingBlocksOfAMetricsBridge() {
        Map<String, Long> retries = new ConcurrentHashMap<>();
        Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();
        // tag::observability-diy[]
        ResilienceRegistry registry = ResilienceRegistry.builder()
                // counters: from events, on the thread that caused them
                .listen(RetryEvent.Retrying.class, e -> retries.merge(e.instance(), 1L, Long::sum))
                .build();
        // gauges: one per instance, read at collection time; new instances are announced once each
        registry.onInstanceCreated(() -> {
            for (CircuitBreakerHandle breaker : registry.circuitBreakers()) {
                gauges.putIfAbsent(breaker.id(), () -> breaker.state() == CircuitBreakerState.OPEN ? 1 : 0);
            }
        });
        // end::observability-diy[]
        PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
        assertThat(gauges.get("payments").getAsDouble()).isZero();

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> payments.charge("o-1")).isInstanceOf(RuntimeException.class);
        }

        assertThat(retries).containsEntry("payments", 3L);
        assertThat(gauges.get("payments").getAsDouble()).isEqualTo(1.0);
        assertThatThrownBy(() -> payments.charge("o-2")).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void theLoggerReportsTransitions() {
        RecordingLogger logger = new RecordingLogger();
        // tag::observability-logging-wiring[]
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .listen(CircuitBreakerEvent.StateTransition.class, new BreakerTransitionLogger(logger))
                .build();
        // end::observability-logging-wiring[]
        Resilient.of(PaymentGateway.class, delegate, registry);
        CircuitBreakerHandle breaker = registry.circuitBreaker("payments");

        breaker.forceOpen();
        breaker.reset();

        assertThat(logger.lines).containsExactly("WARNING Circuit breaker payments: CLOSED -> FORCED_OPEN",
                "INFO Circuit breaker payments: FORCED_OPEN -> CLOSED");
    }

    @Test
    void theHealthCheckIsDegradedWhileABreakerIsOpen() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Resilient.of(PaymentGateway.class, delegate, registry);
        assertThat(ResilienceHealth.of(registry)).isEqualTo(new ResilienceHealth(true, List.of()));

        registry.circuitBreaker("payments").forceOpen();

        // tag::observability-health-use[]
        ResilienceHealth health = ResilienceHealth.of(registry);
        if (!health.healthy()) {
            // report "degraded" with health.openBreakers() on your readiness endpoint
        }
        // end::observability-health-use[]
        assertThat(health).isEqualTo(new ResilienceHealth(false, List.of("payments")));
        assertThat(registry.circuitBreaker("payments").state()).isEqualTo(CircuitBreakerState.FORCED_OPEN);
    }

    /** A {@link System.Logger} that keeps what it is asked to log. */
    private static final class RecordingLogger implements System.Logger {
        final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            lines.add(level + " " + msg);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            lines.add(level + " " + MessageFormat.format(format, params));
        }
    }
}
