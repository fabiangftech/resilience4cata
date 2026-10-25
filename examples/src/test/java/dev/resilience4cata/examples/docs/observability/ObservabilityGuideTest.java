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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The code of {@code docs/en/guides/observability.md}, including the Micrometer recipe. */
class ObservabilityGuideTest {

    private final AtomicBoolean down = new AtomicBoolean(true);
    private final PaymentGateway delegate = order -> {
        if (down.get()) {
            throw new IllegalStateException("payment provider down");
        }
        return "charged " + order;
    };

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
                // everything: the hierarchy is sealed, so a switch can be exhaustive
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
                "gave up after 2: NOT_RETRYABLE"); // the open breaker's rejection is not retried
        assertThat(log).anyMatch(line -> line.startsWith("Retrying[instance=payments, "));
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
    void theMicrometerRecipeMovesTheMeters() {
        // tag::observability-micrometer-wiring[]
        MeterRegistry meters = new SimpleMeterRegistry(); // in your app: the registry you already export
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .listen(ResilienceEvent.class, new ResilienceEventCounters(meters))
                .build();
        PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
        ResilienceGauges.bind(registry, meters); // after wrapping: the instances exist now
        // end::observability-micrometer-wiring[]

        assertThat(meters.get("r4c.circuitbreaker.state").tags("instance", "payments", "state", "closed").gauge().value())
                .isEqualTo(1.0);
        assertThat(meters.get("r4c.circuitbreaker.failure.rate").tag("instance", "payments").gauge().value()).isNaN();

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> payments.charge("o-1")).isInstanceOf(RuntimeException.class);
        }

        assertThat(meters.get("r4c.retry.retries").tag("instance", "payments").counter().count()).isEqualTo(3.0);
        assertThat(meters.get("r4c.retry.gave.up").tags("instance", "payments", "reason", "max_attempts").counter()
                .count()).isEqualTo(1.0);
        assertThat(meters.get("r4c.retry.gave.up").tags("instance", "payments", "reason", "not_retryable").counter()
                .count()).isEqualTo(1.0);
        assertThat(meters.get("r4c.circuitbreaker.transitions").tags("instance", "payments", "from", "closed", "to", "open")
                .counter().count()).isEqualTo(1.0);
        assertThat(meters.get("r4c.calls.rejected").tags("instance", "payments", "kind", "circuitbreaker").counter()
                .count()).isEqualTo(1.0);
        assertThat(meters.get("r4c.circuitbreaker.state").tags("instance", "payments", "state", "open").gauge().value())
                .isEqualTo(1.0);
        assertThat(meters.get("r4c.circuitbreaker.state").tags("instance", "payments", "state", "closed").gauge().value())
                .isZero();
        assertThat(meters.get("r4c.bulkhead.max.concurrent").tag("instance", "payments").gauge().value()).isEqualTo(10.0);
        assertThat(meters.get("r4c.bulkhead.concurrent.calls").tag("instance", "payments").gauge().value()).isZero();
        assertThat(meters.get("r4c.ratelimiter.available.permits").tag("instance", "payments").gauge().value())
                .isBetween(96.0, 100.0); // four attempts took a permit; one comes back every 10 ms
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
