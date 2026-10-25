package dev.resilience4cata.examples.docs.ratelimiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.RateLimiterHandle;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/policies/rate-limiter.md}. */
class RateLimiterPageTest {

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    private final AtomicInteger calls = new AtomicInteger();

    private final GeocodingApi geocodingBackend = new GeocodingApi() {
        @Override
        public String geocode(String address) {
            calls.incrementAndGet();
            return "geo " + address;
        }

        @Override
        public String reverse(double latitude, double longitude) {
            calls.incrementAndGet();
            return "address";
        }

        @Override
        public CompletableFuture<String> geocodeAsync(String address) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("geo " + address);
        }
    };

    @Test
    void testingOnAManualClock() {
        // tag::rate-limiter-testing[]
        SearchApi search = Resilient.of(SearchApi.class, query -> "results for " + query, registry);

        for (int i = 0; i < 10; i++) {
            search.search("q" + i);                      // a full burst of 10 is available at once
        }
        assertThatThrownBy(() -> search.search("one too many"))
                .isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofMillis(100));           // permits come back one every 1s / 10
        assertThat(search.search("next")).isEqualTo("results for next");
        assertThatThrownBy(() -> search.search("again")).isInstanceOf(RateLimitExceededException.class);
        // end::rate-limiter-testing[]
    }

    @Test
    void aRejectedCallNeverRuns() {
        AtomicInteger runs = new AtomicInteger();
        SearchApi search = Resilient.of(SearchApi.class, query -> "r" + runs.incrementAndGet(), registry);
        for (int i = 0; i < 15; i++) {
            try {
                search.search("q");
            } catch (RateLimitExceededException expected) {
                assertThat(expected.instanceId())
                        .isEqualTo("dev.resilience4cata.examples.docs.ratelimiter.SearchApi#search(java.lang.String)");
            }
        }
        assertThat(runs).hasValue(10);
    }

    @Test
    void permitsBuildUpToOneBurstOnly() {
        SearchApi search = Resilient.of(SearchApi.class, query -> "r", registry);
        search.search("warm-up"); // spends one permit
        clock.advance(Duration.ofMinutes(10));
        int passed = 0;
        for (int i = 0; i < 30; i++) {
            try {
                search.search("q");
                passed++;
            } catch (RateLimitExceededException expected) {
                // counted below
            }
        }
        assertThat(passed).isEqualTo(10);
    }

    @Test
    void callersWaitUpToMaxWaitAndShareTheLimiterByName() {
        GeocodingApi geocoding = Resilient.of(GeocodingApi.class, geocodingBackend, registry);
        for (int i = 0; i < 5; i++) {
            geocoding.geocode("a" + i);
        }
        // the sixth permit is due in 200 ms (1s / 5): within maxWait, so the caller sleeps until then
        assertThat(geocoding.reverse(1, 2)).isEqualTo("address");
        assertThat(clock.elapsed()).isEqualTo(Duration.ofMillis(200));
        assertThat(calls).hasValue(6);

        RateLimiterHandle limiter = registry.rateLimiter("geocoding");
        assertThat(limiter.availablePermits()).isZero();
        assertThat(limiter.config().maxWait()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void aWaitLongerThanMaxWaitIsRejectedAtOnce() {
        ResilienceRegistry other = ResilienceTesting.registry(clock)
                .rateLimiterInstance("geocoding", r -> r.maxWait(Duration.ofMillis(100))).build();
        GeocodingApi geocoding = Resilient.of(GeocodingApi.class, geocodingBackend, other);
        for (int i = 0; i < 5; i++) {
            geocoding.geocode("a" + i);
        }
        assertThatThrownBy(() -> geocoding.geocode("late")).isInstanceOf(RateLimitExceededException.class);
        assertThat(clock.elapsed()).isZero();
    }

    @Test
    void handle() {
        GeocodingApi geocoding = Resilient.of(GeocodingApi.class, geocodingBackend, registry);
        geocoding.geocode("first");
        // tag::rate-limiter-handle[]
        RateLimiterHandle limiter = registry.rateLimiter("geocoding");
        int now = limiter.availablePermits();            // calls that would pass right now without waiting
        RateLimiterSnapshot snapshot = limiter.snapshot(); // instance, availablePermits, permits
        // end::rate-limiter-handle[]
        assertThat(now).isEqualTo(4);
        assertThat(snapshot.permits()).isEqualTo(5);
        assertThat(snapshot.availablePermits()).isEqualTo(4);
    }

    @Test
    void asyncWaitsAreScheduled() {
        GeocodingApi geocoding = Resilient.of(GeocodingApi.class, geocodingBackend, registry);
        for (int i = 0; i < 5; i++) {
            assertThat(geocoding.geocodeAsync("a" + i)).isCompleted();
        }
        CompletableFuture<String> waiting = geocoding.geocodeAsync("sixth");
        assertThat(waiting).isNotDone();
        clock.advance(Duration.ofMillis(200));
        assertThat(waiting).isCompletedWithValue("geo sixth");
        geocoding.geocodeAsync("x");
        geocoding.geocodeAsync("y");
        CompletableFuture<String> rejected = geocoding.geocodeAsync("z"); // 600 ms away > 500 ms
        assertThat(rejected).isCompletedExceptionally();
        assertThatThrownBy(rejected::join).hasCauseInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void pipeline() {
        SearchApi backend = query -> "results for " + query;
        // tag::rate-limiter-pipeline[]
        Pipeline<String> search = Pipeline.builder(registry, "search", String.class)
                .rateLimiter(rl -> rl.permits(10).period(Duration.ofSeconds(1)))
                .build();

        String results = search.execute(() -> backend.search("resilience"));
        // end::rate-limiter-pipeline[]
        assertThat(results).isEqualTo("results for resilience");
        assertThat(registry.rateLimiter("search").availablePermits()).isEqualTo(9);
    }
}
