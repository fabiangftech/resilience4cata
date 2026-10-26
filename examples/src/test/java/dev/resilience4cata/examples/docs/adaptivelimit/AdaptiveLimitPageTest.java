package dev.resilience4cata.examples.docs.adaptivelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.AdaptiveLimitHandle;
import dev.resilience4cata.AdaptiveLimitSnapshot;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.LimitExceededException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/policies/adaptive-limit.md}, on generated code. */
class AdaptiveLimitPageTest {

    private static final String SEARCH = "dev.resilience4cata.examples.docs.adaptivelimit.SearchClient#search(java.lang.String)";

    private final ManualClock clock = new ManualClock();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final OrderClient backend = new OrderClient() {
        @Override
        public String place(String order) {
            return "placed " + order;
        }

        @Override
        public String cancel(String order) {
            if (order.startsWith("slow")) {
                throw new CallTimeoutException("downstream", Duration.ofSeconds(2), null);
            }
            return "cancelled " + order;
        }

        @Override
        public CompletableFuture<String> placeAsync(String order) {
            return pending.computeIfAbsent(order, o -> new CompletableFuture<>());
        }
    };

    @Test
    void theMinimalExampleStartsAtTheDefaults() {
        SearchClient search = Resilient.of(SearchClient.class, query -> "results for " + query, registry);
        assertThat(search.search("java")).isEqualTo("results for java");

        AdaptiveLimitHandle limit = registry.adaptiveLimit(SEARCH);
        assertThat(limit.config()).isEqualTo(AdaptiveLimitConfig.DEFAULTS);
        assertThat(limit.limit()).isEqualTo(20);
        assertThat(limit.inFlight()).isZero();
    }

    @Test
    void aFullLimitRejectsAtOnceWithoutCallingTheMethod() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ResilienceRegistry real = ResilienceRegistry.builder().adaptiveLimitInstance(SEARCH, a -> a.initialLimit(1)).build();
        int[] calls = {0};
        SearchClient search = Resilient.of(SearchClient.class, query -> {
            calls[0]++;
            if (query.equals("slow")) {
                inside.countDown();
                await(release);
            }
            return "results for " + query;
        }, real);
        Thread holder = Thread.ofVirtual().start(() -> search.search("slow"));
        await(inside);

        assertThatThrownBy(() -> search.search("fast")).isInstanceOf(LimitExceededException.class)
                .hasMessage("Adaptive limit '" + SEARCH + "' is full")
                .satisfies(e -> assertThat(((LimitExceededException) e).instanceId()).isEqualTo(SEARCH));
        assertThat(calls[0]).as("the method was not called").isEqualTo(1);

        release.countDown();
        holder.join(5_000);
        assertThat(search.search("fast")).isEqualTo("results for fast");
    }

    @Test
    void theTunedExampleSetsItsBoundsAndATimeoutInsideIsADrop() {
        ResilienceRegistry timed = ResilienceTesting.registry(clock).adaptiveLimitDefaults(a -> a.minWindowSamples(1)).build();
        OrderClient orders = Resilient.of(OrderClient.class, backend, timed);
        assertThat(orders.place("o-1")).isEqualTo("placed o-1");
        AdaptiveLimitConfig tuned = timed.adaptiveLimit("orders").config();
        assertThat(tuned.initialLimit()).isEqualTo(10);
        assertThat(tuned.maxLimit()).isEqualTo(100);
        assertThat(tuned.window()).isEqualTo(Duration.ofMillis(500));

        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(() -> orders.cancel("slow-1")).isInstanceOf(CallTimeoutException.class);
        assertThat(timed.adaptiveLimit("orders-aimd").limit()).as("AIMD backs off: 10 * 0.9").isEqualTo(9);
    }

    @Test
    void anAnnotatedClassIsLimitedToo() {
        InventoryService inventory = Resilient.create(InventoryService.class, registry);
        assertThat(inventory.stock("sku-1")).isEqualTo(7);
        AdaptiveLimitHandle limit = registry.adaptiveLimit(
                "dev.resilience4cata.examples.docs.adaptivelimit.InventoryService#stock(java.lang.String)");
        assertThat(limit.config().maxLimit()).isEqualTo(50);
    }

    @Test
    void asyncCallsHoldTheirSlotUntilTheirStageCompletes() {
        OrderClient orders = Resilient.of(OrderClient.class, backend, registry);
        CompletableFuture<String> a = orders.placeAsync("a");
        orders.placeAsync("b");
        assertThat(orders.placeAsync("c")).failsWithin(Duration.ZERO).withThrowableThat()
                .withCauseInstanceOf(LimitExceededException.class);
        pending.get("a").complete("placed a");
        assertThat(a).isCompletedWithValue("placed a");
        assertThat(registry.adaptiveLimit("orders-async").inFlight()).isEqualTo(1);

        a.cancel(true); // already complete: nothing to cancel
        CompletableFuture<String> d = orders.placeAsync("d");
        d.cancel(true);
        assertThat(pending.get("d")).as("cancelling reaches the stage").isCancelled();
        assertThat(registry.adaptiveLimit("orders-async").inFlight()).as("and frees its slot").isEqualTo(1);
    }

    @Test
    void namedConfigsCarryTheAlgorithmsTuning() {
        // tag::adaptive-limit-registry[]
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .adaptiveLimit("backend", a -> a.algorithm(AdaptiveLimitConfig.Algorithm.AIMD)
                        .backoffRatio(0.8)
                        .minWindowSamples(20))
                .build();
        // end::adaptive-limit-registry[]
        assertThat(registry.adaptiveLimitConfig("backend").backoffRatio()).isEqualTo(0.8);
    }

    @Test
    void externalConfiguration() {
        ResilienceConfig config = ResilienceConfig.builder().classpath("docs/adaptivelimit/adaptive-limit.properties").build();
        ResilienceRegistry configured = config.toRegistry();
        assertThat(configured.adaptiveLimitConfig("").maxLimit()).isEqualTo(100);
        AdaptiveLimitConfig backend = configured.adaptiveLimitConfig("backend");
        assertThat(backend.algorithm()).isEqualTo(AdaptiveLimitConfig.Algorithm.AIMD);
        assertThat(backend.backoffRatio()).isEqualTo(0.8);
        assertThat(backend.minWindowSamples()).isEqualTo(20);
        Resilient.of(OrderClient.class, backend(), configured);
        assertThat(configured.adaptiveLimit("orders").config().rttTolerance()).isEqualTo(2.0);
    }

    private OrderClient backend() {
        return backend;
    }

    @Test
    void handle() {
        Resilient.of(OrderClient.class, backend, registry);
        // tag::adaptive-limit-handle[]
        AdaptiveLimitHandle limit = registry.adaptiveLimit("orders");
        int current = limit.limit();                   // how many calls may run at once now
        int running = limit.inFlight();                // calls inside right now
        AdaptiveLimitSnapshot snapshot = limit.snapshot(); // limit, inFlight, bounds, windows, latencies
        // end::adaptive-limit-handle[]
        assertThat(current).isEqualTo(10);
        assertThat(running).isZero();
        assertThat(snapshot.windows()).isZero();
        assertThat(snapshot.availableSlots()).isEqualTo(10);
        assertThat(registry.snapshot().adaptiveLimits()).contains(snapshot);
    }

    // java:S1117: the documentation snippet below calls its client "backend", like the other pages
    @SuppressWarnings("java:S1117")
    @Test
    void pipeline() {
        SearchClient backend = query -> "results for " + query;
        // tag::adaptive-limit-pipeline[]
        Pipeline<String> search = Pipeline.builder(registry, "search", String.class)
                .adaptiveLimit(a -> a.maxLimit(100))
                .timeout(t -> t.timeout(Duration.ofSeconds(2)))
                .build();

        String results = search.execute(() -> backend.search("java"));
        // end::adaptive-limit-pipeline[]
        assertThat(results).isEqualTo("results for java");
        assertThat(registry.adaptiveLimit("search").config().maxLimit()).isEqualTo(100);
    }

    // java:S1117: the snippet builds its own registry, as a test would
    @SuppressWarnings("java:S1117")
    @Test
    void testingOnAManualClock() {
        // tag::adaptive-limit-testing[]
        ManualClock clock = new ManualClock();
        ResilienceRegistry registry = ResilienceTesting.registry(clock)
                .adaptiveLimitDefaults(a -> a.minWindowSamples(1))   // end a window after a single call
                .build();
        OrderClient orders = Resilient.of(OrderClient.class, backend, registry);
        AdaptiveLimitHandle limit = registry.adaptiveLimit("orders-async");

        CompletableFuture<String> a = orders.placeAsync("a");          // pending: holds a slot
        CompletableFuture<String> b = orders.placeAsync("b");          // pending: holds the other
        assertThat(orders.placeAsync("c")).failsWithin(Duration.ZERO)  // full: rejected at once
                .withThrowableThat().withCauseInstanceOf(LimitExceededException.class);

        clock.advance(Duration.ofSeconds(1));                          // the calls took 1 s; the window is over
        pending.get("a").complete("placed a");                         // ends the window
        pending.get("b").complete("placed b");
        assertThat(limit.snapshot().windows()).isEqualTo(1);
        assertThat(limit.snapshot().windowLatency()).isEqualTo(Duration.ofSeconds(1));
        // end::adaptive-limit-testing[]
        assertThat(a).isCompletedWithValue("placed a");
        assertThat(b).isCompletedWithValue("placed b");
        assertThat(clock.pendingTasks()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out in real time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
