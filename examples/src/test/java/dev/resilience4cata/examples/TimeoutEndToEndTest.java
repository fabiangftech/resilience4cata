package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TimeoutEndToEndTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .timeout("slow", t -> t.timeout(Duration.ofSeconds(30)))
            .build();

    private final AtomicInteger calls = new AtomicInteger();
    private volatile long sleepMillis = 10_000;

    private final SearchClient backend = new SearchClient() {
        @Override
        public String search(String query) {
            return slow(query);
        }

        @Override
        public String protectedSearch(String query) {
            return slow(query);
        }

        @Override
        public String report() {
            return slow("report");
        }

        // java:S2925: the call must really block so that the timeout interrupts it
        @SuppressWarnings("java:S2925")
        private String slow(String query) {
            calls.incrementAndGet();
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                throw new IllegalStateException("search interrupted", e);
            }
            return "results for " + query;
        }
    };

    private final SearchClient client = Resilient.of(SearchClient.class, backend, registry);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void aSlowCallTimesOutAndTheThreadIsNotLeftInterrupted() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.search("java"))
                .isInstanceOf(CallTimeoutException.class)
                .hasMessageContaining("SearchClient#search(java.lang.String)")
                .hasRootCauseInstanceOf(InterruptedException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(50), Duration.ofSeconds(2));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aFastCallIsNotAffected() {
        // On a manual clock the 50 ms timeout fires only when the clock moves, so a slow machine cannot make this
        // fast call late (with the real clock, a busy CI runner occasionally did).
        sleepMillis = 0;
        ResilienceRegistry manual = ResilienceTesting.registry(new ManualClock())
                .timeout("slow", t -> t.timeout(Duration.ofSeconds(30)))
                .build();
        SearchClient onManualClock = Resilient.of(SearchClient.class, backend, manual);
        assertThat(onManualClock.search("java")).isEqualTo("results for java");
    }

    @Test
    void timeoutsAreRetriedAndOpenTheBreaker() {
        assertThatThrownBy(() -> client.protectedSearch("java")).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).as("two timeouts open the breaker; the third attempt is rejected").hasValue(2);
    }

    @Test
    void worksOnVirtualThreads() throws InterruptedException {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                client.search("java");
            } catch (Throwable e) {
                outcome.set(e);
            }
        }).join();
        assertThat(outcome.get()).isInstanceOf(CallTimeoutException.class);
    }

    @Test
    void namedConfigsWork() {
        sleepMillis = 100; // longer than 50 ms, shorter than the "slow" config's 30 s
        assertThat(client.report()).isEqualTo("results for report");
    }
}
