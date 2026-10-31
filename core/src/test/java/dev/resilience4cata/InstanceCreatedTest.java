package dev.resilience4cata;

import dev.resilience4cata.generated.Policies;
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

        Policies.circuitBreaker(registry, "A#m()", "", "", null, false);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).circuitBreakers()).extracting(CircuitBreakerSnapshot::id).containsExactly("A#m()");
        assertThat(Policies.circuitBreaker(registry, "A#m()", "", "", null, false)).isNotNull(); // existing: no run
        assertThat(seen).hasSize(1);

        Policies.retry(registry, "A#m()", "", "", null, false);
        Policies.rateLimiter(registry, "A#m()", "", "", null, false);
        Policies.bulkhead(registry, "A#m()", "", "", null, false);
        Policies.timeout(registry, "A#m()", "", "", t -> t.timeout(Duration.ofSeconds(1)), false);
        Policies.timeout(registry, "A#m()", "", "", t -> t.timeout(Duration.ofSeconds(1)), false); // same id: no run
        Policies.adaptiveLimit(registry, "A#m()", "", "", null, false);
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
        Policies.circuitBreaker(registry, "A#m()", "", "", null, false);

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
            Policies.circuitBreaker(registry, "A#m()", "", "", null, false);
            runs.add(registry.circuitBreaker("A#m()").id());
        });
        thread.setUncaughtExceptionHandler((t, e) -> uncaught.add(e));
        thread.start();
        thread.join();

        assertThat(runs).containsExactly("second", "A#m()");
        assertThat(uncaught).singleElement().extracting(Throwable::getMessage).isEqualTo("boom");
    }
}
