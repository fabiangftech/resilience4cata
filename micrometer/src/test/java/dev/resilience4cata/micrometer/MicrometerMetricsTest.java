package dev.resilience4cata.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.event.HedgeEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class MicrometerMetricsTest {

    private final ManualClock clock = new ManualClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MicrometerMetrics metrics = MicrometerMetrics.create(meters);
    private final ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();

    @Test
    void retriesAndGiveUpsAreCountedByInstanceAndReason() {
        metrics.bindTo(registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "payments", String.class)
                .retry(r -> r.maxAttempts(3).abortOn(List.of(IllegalStateException.class)))
                .build();

        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new IOException("down");
        })).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new IllegalStateException("bad request");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(counters()).isEqualTo(Map.of(
                "r4c.retry.retries{name=payments}", 2.0,
                "r4c.retry.gave.up{name=payments,reason=max_attempts}", 1.0,
                "r4c.retry.gave.up{name=payments,reason=not_retryable}", 1.0));
    }

    @Test
    void breakerTransitionsRejectionsAndStateGauges() throws Exception {
        metrics.bindTo(registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "inventory", String.class)
                .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2).failureRateThreshold(50))
                .build();
        CircuitBreakerHandle breaker = registry.circuitBreaker("inventory");

        assertThat(gauges("r4c.circuitbreaker.state")).isEqualTo(Map.of(
                "r4c.circuitbreaker.state{name=inventory,state=closed}", 1.0,
                "r4c.circuitbreaker.state{name=inventory,state=open}", 0.0,
                "r4c.circuitbreaker.state{name=inventory,state=half_open}", 0.0,
                "r4c.circuitbreaker.state{name=inventory,state=forced_open}", 0.0,
                "r4c.circuitbreaker.state{name=inventory,state=disabled}", 0.0));
        assertThat(gauge("r4c.circuitbreaker.failure.rate", "inventory").value()).isNaN();
        assertThat(gauge("r4c.circuitbreaker.failure.rate", "inventory").getId().getBaseUnit()).isEqualTo("percent");
        assertThat(gauge("r4c.circuitbreaker.slow.call.rate", "inventory").getId().getBaseUnit()).isEqualTo("percent");

        assertThat(pipeline.execute(() -> "ok")).isEqualTo("ok");
        assertThat(gauge("r4c.circuitbreaker.failure.rate", "inventory").value()).as("one call < minimumCalls").isNaN();
        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new IOException("down");
        })).isInstanceOf(IOException.class);
        assertThat(breaker.state().name()).isEqualTo("OPEN");
        assertThat(gauge("r4c.circuitbreaker.state", "inventory", "state", "open").value()).isEqualTo(1.0);
        assertThat(gauge("r4c.circuitbreaker.state", "inventory", "state", "closed").value()).isEqualTo(0.0);
        assertThat(gauge("r4c.circuitbreaker.failure.rate", "inventory").value()).as("no window while open").isNaN();
        assertThat(gauge("r4c.circuitbreaker.slow.call.rate", "inventory").value()).as("no slow-call detection").isNaN();

        assertThatThrownBy(() -> pipeline.execute(() -> "ok")).isInstanceOf(CallNotPermittedException.class);
        breaker.forceOpen();

        assertThat(counters()).isEqualTo(Map.of(
                "r4c.circuitbreaker.transitions{from=closed,name=inventory,to=open}", 1.0,
                "r4c.circuitbreaker.transitions{from=open,name=inventory,to=forced_open}", 1.0,
                "r4c.calls.rejected{kind=circuitbreaker,name=inventory}", 1.0));
        assertThat(gauge("r4c.circuitbreaker.state", "inventory", "state", "forced_open").value()).isEqualTo(1.0);
    }

    @Test
    void theFailureRateIsReportedOnceTheWindowHoldsMinimumCalls() throws Exception {
        metrics.bindTo(registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "search", String.class)
                .circuitBreaker(cb -> cb.windowSize(4).minimumCalls(4).failureRateThreshold(100)
                        .slowCallDuration(Duration.ofSeconds(1)).slowCallRateThreshold(100))
                .build();
        for (int i = 0; i < 3; i++) {
            pipeline.execute(() -> "ok");
        }
        assertThat(gauge("r4c.circuitbreaker.failure.rate", "search").value()).isNaN();
        assertThat(gauge("r4c.circuitbreaker.slow.call.rate", "search").value()).isNaN();

        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new IOException("down");
        })).isInstanceOf(IOException.class);

        assertThat(gauge("r4c.circuitbreaker.failure.rate", "search").value()).isEqualTo(25.0);
        assertThat(gauge("r4c.circuitbreaker.slow.call.rate", "search").value()).isEqualTo(0.0);
    }

    @Test
    void adaptiveLimitGaugesRejectionsAndLimitChanges() throws Exception {
        metrics.bindTo(registry);
        Pipeline<String> limited = Pipeline.builder(registry, "orders", String.class)
                .adaptiveLimit(a -> a.algorithm(dev.resilience4cata.AdaptiveLimitConfig.Algorithm.AIMD).initialLimit(1)
                        .minWindowSamples(1))
                .build();

        assertThat(gauge("r4c.adaptivelimit.limit", "orders").value()).isEqualTo(1.0);
        String inside = limited.execute(() -> {
            assertThat(gauge("r4c.adaptivelimit.concurrent.calls", "orders").value()).isEqualTo(1.0);
            assertThatThrownBy(() -> limited.execute(() -> "nested"))
                    .isInstanceOf(dev.resilience4cata.AdaptiveLimitFullException.class);
            clock.advance(Duration.ofSeconds(1)); // the window ends when this call does: AIMD adds one
            return "done";
        });
        assertThat(inside).isEqualTo("done");
        assertThat(gauge("r4c.adaptivelimit.concurrent.calls", "orders").value()).isEqualTo(0.0);
        assertThat(gauge("r4c.adaptivelimit.limit", "orders").value()).isEqualTo(2.0);
        assertThat(counters()).isEqualTo(Map.of(
                "r4c.calls.rejected{kind=adaptivelimit,name=orders}", 1.0,
                "r4c.adaptivelimit.limit.changes{name=orders}", 1.0));
    }

    @Test
    void bulkheadAndRateLimiterGaugesAndRejections() throws Exception {
        metrics.bindTo(registry);
        Pipeline<String> bulkheaded = Pipeline.builder(registry, "reports", String.class)
                .bulkhead(b -> b.maxConcurrent(1))
                .build();
        Pipeline<String> limited = Pipeline.builder(registry, "quotes", String.class)
                .rateLimiter(r -> r.permits(2).period(Duration.ofSeconds(1)))
                .build();

        assertThat(gauge("r4c.bulkhead.max.concurrent", "reports").value()).isEqualTo(1.0);
        assertThat(gauge("r4c.bulkhead.concurrent.calls", "reports").value()).isEqualTo(0.0);
        assertThat(gauge("r4c.bulkhead.waiting.calls", "reports").value()).isEqualTo(0.0);
        String inside = bulkheaded.execute(() -> {
            assertThat(gauge("r4c.bulkhead.concurrent.calls", "reports").value()).isEqualTo(1.0);
            assertThatThrownBy(() -> bulkheaded.execute(() -> "nested")).isInstanceOf(BulkheadFullException.class);
            return "done";
        });
        assertThat(inside).isEqualTo("done");
        assertThat(gauge("r4c.bulkhead.concurrent.calls", "reports").value()).isEqualTo(0.0);

        assertThat(gauge("r4c.ratelimiter.available.permits", "quotes").value()).isEqualTo(2.0);
        limited.execute(() -> "a");
        assertThat(gauge("r4c.ratelimiter.available.permits", "quotes").value()).isEqualTo(1.0);
        limited.execute(() -> "b");
        assertThatThrownBy(() -> limited.execute(() -> "c")).isInstanceOf(RateLimitExceededException.class);
        assertThat(gauge("r4c.ratelimiter.available.permits", "quotes").value()).isEqualTo(0.0);
        clock.advance(Duration.ofSeconds(1));
        assertThat(gauge("r4c.ratelimiter.available.permits", "quotes").value()).isEqualTo(2.0);

        assertThat(counters()).isEqualTo(Map.of(
                "r4c.calls.rejected{kind=bulkhead,name=reports}", 1.0,
                "r4c.calls.rejected{kind=ratelimiter,name=quotes}", 1.0));
    }

    @Test
    void hedgesAreCountedByInstanceAndReason() {
        Pipeline<String> quotes = Pipeline.builder(registry, "quotes", String.class)
                .hedge(h -> h.delay(Duration.ofMillis(10)).maxAttempts(3).budgetPercent(0))
                .build();
        Pipeline<String> strict = Pipeline.builder(registry, "strict", String.class)
                .hedge(h -> h.abortOn(List.of(IllegalStateException.class)).budgetPercent(0))
                .build();
        Pipeline<String> rare = Pipeline.builder(registry, "rare", String.class)
                .hedge(h -> h.budgetMinHedgesPerSecond(0))
                .build();
        CompletableFuture<String> slow = new CompletableFuture<>();
        int[] calls = {0};

        CompletableFuture<String> won = quotes.executeAsync(() -> calls[0]++ == 0 ? slow : CompletableFuture.completedFuture("fast"));
        clock.advance(Duration.ofMillis(10));
        CompletableFuture<String> allFailed = quotes.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down")));
        CompletableFuture<String> aborted = strict.executeAsync(() -> CompletableFuture.failedFuture(new IllegalStateException("bad")));
        CompletableFuture<String> noBudget = rare.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down")));
        clock.advance(Duration.ZERO); // a failure starts the next attempt off the completing thread: here, at the next advance

        assertThat(won).isCompletedWithValue("fast");
        assertThat(allFailed).isCompletedExceptionally();
        assertThat(aborted).isCompletedExceptionally();
        assertThat(noBudget).isCompletedExceptionally();
        assertThat(counters()).isEqualTo(Map.of(
                "r4c.hedge.hedged{name=quotes,reason=delay}", 1.0,
                "r4c.hedge.hedged{name=quotes,reason=failure}", 2.0,
                "r4c.hedge.won{name=quotes}", 1.0,
                "r4c.hedge.failed{name=quotes}", 1.0,
                "r4c.hedge.skipped{name=strict,reason=aborted}", 1.0,
                "r4c.hedge.failed{name=strict}", 1.0,
                "r4c.hedge.skipped{name=rare,reason=budget_exhausted}", 1.0,
                "r4c.hedge.failed{name=rare}", 1.0));
    }

    @Test
    void timeoutsAndFallbacksAreCounted() throws Exception {
        Pipeline<String> pipeline = Pipeline.builder(registry, "catalog", String.class)
                .timeout(t -> t.timeout(Duration.ofSeconds(1)))
                .build();
        Pipeline<String> fallback = Pipeline.builder(registry, "prices", String.class)
                .fallback(failure -> "cached")
                .build();

        assertThatThrownBy(() -> pipeline.execute(() -> {
            clock.sleep(Duration.ofSeconds(2));
            return "late";
        })).isInstanceOf(CallTimeoutException.class);
        assertThat(fallback.execute(() -> {
            throw new UncheckedIOException(new IOException("down"));
        })).isEqualTo("cached");

        assertThat(counters()).isEqualTo(Map.of(
                "r4c.timeout.timed.out{name=catalog}", 1.0,
                "r4c.fallback.applied{name=prices}", 1.0));
    }

    @Test
    void instancesCreatedAfterBindingGetTheirGauges() {
        metrics.bindTo(registry); // nothing exists yet
        assertThat(meters.getMeters()).isEmpty();

        Pipeline.builder(registry, "late", String.class).circuitBreaker().bulkhead().rateLimiter().build();

        assertThat(meterNames()).containsExactlyInAnyOrder(
                "r4c.circuitbreaker.state", "r4c.circuitbreaker.state", "r4c.circuitbreaker.state",
                "r4c.circuitbreaker.state", "r4c.circuitbreaker.state",
                "r4c.circuitbreaker.failure.rate", "r4c.circuitbreaker.slow.call.rate",
                "r4c.bulkhead.concurrent.calls", "r4c.bulkhead.waiting.calls", "r4c.bulkhead.max.concurrent",
                "r4c.ratelimiter.available.permits");
        assertThat(metrics.boundForTest()).containsExactlyInAnyOrder("circuitbreaker late", "bulkhead late", "ratelimiter late");
    }

    @Test
    void instancesCreatedBeforeBindingGetTheirGaugesAndBindingTwiceChangesNothing() {
        Pipeline.builder(registry, "early", String.class).circuitBreaker().build();
        assertThat(meters.getMeters()).isEmpty();

        metrics.bindTo(registry);
        metrics.bindTo(registry);

        assertThat(meterNames()).hasSize(7);
    }

    @Test
    void severalMeterRegistriesAndSeveralResilienceRegistries() {
        SimpleMeterRegistry other = new SimpleMeterRegistry();
        MicrometerMetrics otherMetrics = MicrometerMetrics.create(other);
        ResilienceRegistry shared = otherMetrics.register(metrics.register(ResilienceRegistry.builder())).build();
        ResilienceRegistry second = metrics.register(ResilienceRegistry.builder()).build();
        metrics.bindTo(shared);
        metrics.bindTo(second);
        otherMetrics.bindTo(shared);

        Pipeline<String> a = Pipeline.builder(shared, "a", String.class).circuitBreaker().retry(r -> r.maxAttempts(2)).build();
        Pipeline.builder(second, "b", String.class).circuitBreaker().build();
        assertThatThrownBy(() -> a.execute(() -> {
            throw new IOException("down");
        })).isInstanceOf(IOException.class);

        assertThat(meters.find("r4c.circuitbreaker.state").tag("name", "a").gauges()).hasSize(5);
        assertThat(meters.find("r4c.circuitbreaker.state").tag("name", "b").gauges()).hasSize(5);
        assertThat(other.find("r4c.circuitbreaker.state").tag("name", "a").gauges()).hasSize(5);
        assertThat(other.find("r4c.circuitbreaker.state").tag("name", "b").gauges()).isEmpty();
        assertThat(meters.get("r4c.retry.retries").tag("name", "a").counter().count()).isEqualTo(1.0);
        assertThat(other.get("r4c.retry.retries").tag("name", "a").counter().count()).isEqualTo(1.0);
    }

    @Test
    void cachedCountersAreReusedAndCountEventsWithoutAllocating() {
        EventCounters counters = new EventCounters(meters);
        RetryEvent.Retrying retrying = new RetryEvent.Retrying("hot", 0, 1, Duration.ZERO, null);
        RetryEvent.GaveUp gaveUp = new RetryEvent.GaveUp("hot", 0, 3, RetryEvent.Reason.MAX_ATTEMPTS, null);
        HedgeEvent.Hedged hedged = new HedgeEvent.Hedged("hot", 0, 2, HedgeEvent.Reason.DELAY);
        HedgeEvent.Skipped skipped = new HedgeEvent.Skipped("hot", 0, 2, HedgeEvent.SkipReason.BUDGET_EXHAUSTED);
        counters.onEvent(retrying);
        counters.onEvent(gaveUp);
        counters.onEvent(hedged);
        counters.onEvent(skipped);
        List<Meter> registered = List.copyOf(meters.getMeters());
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < 20_000; i++) {
            counters.onEvent(retrying);
            counters.onEvent(gaveUp);
            counters.onEvent(hedged);
            counters.onEvent(skipped);
        }

        // Measured in rounds until the JIT is done (a slow CI machine may not be after the warm-up); a real
        // allocation shows in every round.
        long allocated = Long.MAX_VALUE;
        int rounds = 0;
        while (rounds < 20 && allocated >= 1024) {
            rounds++;
            long before = threads.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 100_000; i++) {
                counters.onEvent(retrying);
                counters.onEvent(gaveUp);
                counters.onEvent(hedged);
                counters.onEvent(skipped);
            }
            allocated = Math.min(allocated, threads.getThreadAllocatedBytes(thread) - before);
        }

        assertThat(allocated).as("bytes allocated by 400k cached events (the measurement may allocate a little)")
                .isLessThan(1024);
        assertThat(meters.getMeters()).containsExactlyInAnyOrderElementsOf(registered);
        assertThat(meters.get("r4c.retry.retries").counter().count()).isEqualTo(20_001 + 100_000.0 * rounds);
    }

    @Test
    void closeRemovesEveryMeterAndStopsCountingAndDiscovering() {
        metrics.bindTo(registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "closing", String.class)
                .retry(r -> r.maxAttempts(2)).circuitBreaker().build();
        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new IOException("down");
        })).isInstanceOf(IOException.class);
        assertThat(meters.getMeters()).isNotEmpty();

        metrics.close();
        metrics.close();
        assertThat(meters.getMeters()).isEmpty();

        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw new IOException("down");
        })).isInstanceOf(IOException.class);
        Pipeline.builder(registry, "after", String.class).circuitBreaker().build();
        metrics.bindTo(registry);
        assertThat(meters.getMeters()).isEmpty();
        assertThat(metrics.counterCacheForTest()).isEmpty();
    }

    // java:S2925: gives the real garbage collector time to clear the weak reference
    @SuppressWarnings("java:S2925")
    @Test
    void meterRegistriesDoNotKeepTheResilienceRegistryAlive() throws Exception {
        MeterRegistry longLived = new SimpleMeterRegistry();
        MicrometerMetrics kept = MicrometerMetrics.create(longLived);
        WeakReference<ResilienceRegistry> reference = bindAndDrop(kept);

        for (int i = 0; i < 50 && reference.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }

        assertThat(reference.get()).as("the registry is collected").isNull();
        assertThat(longLived.get("r4c.circuitbreaker.state").tag("state", "closed").gauge().value()).isNaN();
    }

    private static WeakReference<ResilienceRegistry> bindAndDrop(MicrometerMetrics kept) {
        ResilienceRegistry dropped = kept.register(ResilienceRegistry.builder()).build();
        kept.bindTo(dropped);
        Pipeline.builder(dropped, "dropped", String.class).circuitBreaker().build();
        return new WeakReference<>(dropped);
    }

    @Test
    void concurrentEventsAndInstanceCreationLoseNothing() throws Exception {
        SimpleMeterRegistry concurrentMeters = new SimpleMeterRegistry();
        MicrometerMetrics concurrent = MicrometerMetrics.create(concurrentMeters);
        ResilienceRegistry shared = concurrent.register(ResilienceRegistry.builder()
                .retryDefaults(r -> r.delay(Duration.ZERO))).build();
        concurrent.bindTo(shared);
        int threads = 8;
        int calls = 2_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                done.add(executor.submit(() -> {
                    start.await();
                    Pipeline<String> own = Pipeline.builder(shared, "own-" + thread, String.class).circuitBreaker().build();
                    Pipeline<String> common = Pipeline.builder(shared, "common", String.class)
                            .retry(r -> r.maxAttempts(2)).build();
                    for (int i = 0; i < calls; i++) {
                        try {
                            common.execute(() -> {
                                throw new IOException("down");
                            });
                        } catch (IOException expected) {
                            // counted
                        }
                    }
                    return own.id();
                }));
            }
            start.countDown();
            for (Future<?> future : done) {
                future.get();
            }
        }

        assertThat(concurrentMeters.get("r4c.retry.retries").tag("name", "common").counter().count())
                .isEqualTo(threads * calls);
        assertThat(concurrentMeters.get("r4c.retry.gave.up").tag("reason", "max_attempts").counter().count())
                .isEqualTo(threads * calls);
        for (int t = 0; t < threads; t++) {
            assertThat(concurrentMeters.find("r4c.circuitbreaker.state").tag("name", "own-" + t).gauges()).hasSize(5);
        }
    }

    // ---- helpers

    private Map<String, Double> counters() {
        Map<String, Double> result = new TreeMap<>();
        for (Meter meter : meters.getMeters()) {
            if (meter instanceof Counter counter) {
                result.put(key(meter), counter.count());
            }
        }
        return result;
    }

    private Map<String, Double> gauges(String name) {
        Map<String, Double> result = new TreeMap<>();
        for (Gauge gauge : meters.find(name).gauges()) {
            result.put(key(gauge), gauge.value());
        }
        return result;
    }

    private Gauge gauge(String name, String instance, String... tags) {
        return meters.get(name).tag("name", instance).tags(tags).gauge();
    }

    private List<String> meterNames() {
        return meters.getMeters().stream().map(m -> m.getId().getName()).toList();
    }

    private static String key(Meter meter) {
        StringBuilder key = new StringBuilder(meter.getId().getName()).append('{');
        List<Tag> tags = meter.getId().getTags(); // sorted by key
        for (int i = 0; i < tags.size(); i++) {
            key.append(i == 0 ? "" : ",").append(tags.get(i).getKey()).append('=').append(tags.get(i).getValue());
        }
        return key.append('}').toString();
    }
}
