package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ResilienceRegistry#onInstanceCreated}: the cold-path hook that metrics bridges use. */
class InstanceCreatedTest {

    @Test
    void runsOncePerNewInstanceAfterItIsVisibleInTheSnapshot() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        List<ResilienceSnapshot> seen = new ArrayList<>();
        registry.onInstanceCreated(() -> seen.add(registry.snapshot()));

        registry.circuitBreakerPolicy("A#m()", "", "", null);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).circuitBreakers()).extracting(CircuitBreakerSnapshot::instance).containsExactly("A#m()");
        assertThat(registry.circuitBreakerPolicy("A#m()", "", "", null)).isNotNull(); // existing: no run
        assertThat(seen).hasSize(1);

        registry.retryPolicy("A#m()", "", "", null);
        registry.rateLimiterPolicy("A#m()", "", "", null);
        registry.bulkheadPolicy("A#m()", "", "", null);
        registry.timeoutPolicy("A#m()", "", "", t -> t.timeout(Duration.ofSeconds(1)));
        registry.timeoutPolicy("A#m()", "", "", t -> t.timeout(Duration.ofSeconds(1))); // same id: no run
        registry.adaptiveLimitPolicy("A#m()", "", "", null);
        assertThat(seen).hasSize(6);
        assertThat(seen.get(5).adaptiveLimits()).hasSize(1);
        ResilienceSnapshot last = seen.get(4);
        assertThat(last.retries()).hasSize(1);
        assertThat(last.rateLimiters()).hasSize(1);
        assertThat(last.bulkheads()).hasSize(1);
        assertThat(last.timeouts()).hasSize(1);
    }

    @Test
    void pipelinesCreateInstancesToo() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        List<String> runs = new ArrayList<>();
        registry.onInstanceCreated(() -> runs.add("created"));

        Pipeline.builder(registry, "payments").circuitBreaker().bulkhead().build();

        assertThat(runs).hasSize(2);
    }

    @Test
    void closeStopsOnlyThatRegistration() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        List<String> runs = new ArrayList<>();
        Runnable action = () -> runs.add("x");
        ResilienceRegistry.Registration first = registry.onInstanceCreated(action);
        registry.onInstanceCreated(action);

        first.close();
        first.close();
        registry.circuitBreakerPolicy("A#m()", "", "", null);

        assertThat(runs).containsExactly("x");
    }

    @Test
    void aFailingActionDoesNotBreakTheCreationNorTheOtherActions() throws InterruptedException {
        List<Throwable> uncaught = new ArrayList<>();
        List<String> runs = new ArrayList<>();
        Thread thread = new Thread(() -> {
            ResilienceRegistry registry = ResilienceRegistry.builder().build();
            registry.onInstanceCreated(() -> {
                throw new IllegalStateException("boom");
            });
            registry.onInstanceCreated(() -> runs.add("second"));
            registry.circuitBreakerPolicy("A#m()", "", "", null);
            runs.add(registry.circuitBreaker("A#m()").id());
        });
        thread.setUncaughtExceptionHandler((t, e) -> uncaught.add(e));
        thread.start();
        thread.join();

        assertThat(runs).containsExactly("second", "A#m()");
        assertThat(uncaught).singleElement().extracting(Throwable::getMessage).isEqualTo("boom");
    }
}
