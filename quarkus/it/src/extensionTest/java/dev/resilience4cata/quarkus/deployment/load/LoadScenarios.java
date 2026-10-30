package dev.resilience4cata.quarkus.deployment.load;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Quarkus application (Quarkus REST), its protected beans called by hundreds of concurrent HTTP requests on the
 * system clock; {@link LoadTest} runs the endpoints on worker threads, {@link VirtualThreadsLoadTest} on virtual
 * threads. What must hold: the bulkhead never lets more calls in than its limit and frees every slot, a bulkhead with
 * a wait hands its slots on, the rate limiter lets exactly its permits through, the breaker opens under failures,
 * stops the calls, and recovers, timeouts interrupt the blocked calls and answer at once, concurrent retries do not
 * interfere, and the metrics and the registry agree with what the clients saw.
 */
abstract class LoadScenarios {

    /** The application of a subclass: the protected beans, and {@code resources} for the endpoints. */
    static QuarkusExtensionTest application(Class<?>... resources) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(Warehouse.class, InventoryService.class, ShippingClient.class,
                        WarehouseShippingClient.class, LoadResource.class, LoadExceptionMappers.class).addClasses(resources)
                        .addClass("dev.resilience4cata.quarkus.deployment.load.InventoryService$Resilient")
                        .addClass("dev.resilience4cata.quarkus.deployment.load.ShippingClient$Resilient"))
                .overrideConfigKey("quarkus.thread-pool.max-threads", "400");
    }

    /** The path of the resource whose endpoints the scenarios call. */
    abstract String resource();

    private static ExecutorService clients;
    private static HttpClient http;

    @TestHTTPResource("/")
    URI root;

    @Inject
    Warehouse warehouse;

    @Inject
    ResilienceRegistry registry;

    @Inject
    MeterRegistry meters;

    @BeforeAll
    static void client() {
        clients = Executors.newVirtualThreadPerTaskExecutor();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(clients)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void close() {
        http.close();
        clients.close();
    }

    @BeforeEach
    void reset() {
        warehouse.reset();
    }

    private String url(String path) {
        return root.resolve(resource() + path).toString();
    }

    /** Sends {@code requests} requests at once (each waits for the others to be ready) and counts the statuses. */
    private Map<Integer, Integer> fire(int requests, IntFunction<String> path) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> responses = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url(path.apply(i)))).timeout(Duration.ofSeconds(30)).build();
            responses.add(clients.submit(() -> {
                ready.countDown();
                go.await();
                return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).as("every client ready").isTrue();
        go.countDown();
        Map<Integer, Integer> statuses = new TreeMap<>();
        for (Future<Integer> response : responses) {
            statuses.merge(response.get(60, TimeUnit.SECONDS), 1, Integer::sum);
        }
        return statuses;
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private double rejected(String kind, String instance) {
        var counter = meters.find("r4c.calls.rejected").tags("kind", kind, "instance", instance).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theBulkheadNeverLetsMoreThanItsLimitInAndFreesEverySlot() throws Exception {
        warehouse.latency(50);
        Map<Integer, Integer> statuses = fire(200, i -> "/reserve/sku-" + i);

        assertThat(statuses.keySet()).as(statuses.toString()).containsOnly(200, 503);
        assertThat(statuses.get(200)).as("some got in").isGreaterThanOrEqualTo(5);
        assertThat(warehouse.maxInside()).as("calls inside the bulkhead at once").isLessThanOrEqualTo(5);
        assertThat(warehouse.calls()).as("every admitted call reached the warehouse, no rejected one did")
                .isEqualTo(statuses.get(200));
        assertThat(registry.bulkhead("load-bulkhead").concurrentCalls()).as("every slot freed").isZero();
        assertThat(rejected("bulkhead", "load-bulkhead")).as("the rejection metric counts the 503s")
                .isEqualTo(statuses.getOrDefault(503, 0).doubleValue());
    }

    @Test
    void aBulkheadWithMaxWaitHandsItsSlotsOnAndLetsEveryCallInInTurn() throws Exception {
        warehouse.latency(20);
        Map<Integer, Integer> statuses = fire(100, i -> "/queued/sku-" + i);

        assertThat(statuses).as("100 calls of 20 ms through 5 slots: about 400 ms, well within the wait")
                .containsOnlyKeys(200).containsEntry(200, 100);
        assertThat(warehouse.maxInside()).isLessThanOrEqualTo(5);
        assertThat(warehouse.calls()).isEqualTo(100);
        assertThat(registry.bulkhead("load-queue").concurrentCalls()).isZero();
        assertThat(registry.bulkhead("load-queue").waitingCalls()).isZero();
    }

    @Test
    void anAsyncBulkheadWithMaxWaitQueuesWithoutBlockingAndHandsItsSlotsOn() throws Exception {
        warehouse.latency(20);
        Map<Integer, Integer> statuses = fire(100, i -> "/later/sku-" + i);

        assertThat(statuses).containsOnlyKeys(200).containsEntry(200, 100);
        assertThat(warehouse.maxInside()).as("async calls inside the bulkhead at once").isLessThanOrEqualTo(5);
        assertThat(warehouse.calls()).isEqualTo(100);
        awaitUntil(() -> registry.bulkhead("load-async").concurrentCalls() == 0);
        assertThat(registry.bulkhead("load-async").waitingCalls()).isZero();
    }

    @Test
    void theRateLimiterLetsExactlyItsPermitsThrough() throws Exception {
        Map<Integer, Integer> statuses = fire(200, i -> "/quote/sku-" + i);

        assertThat(statuses).as("50 permits for the hour").containsEntry(200, 50).containsEntry(429, 150).hasSize(2);
        assertThat(warehouse.calls()).isEqualTo(50);
        assertThat(rejected("ratelimiter", "load-limiter")).isEqualTo(150);
    }

    @Test
    void theBreakerOpensUnderFailuresStopsTheCallsAndRecovers() throws Exception {
        registry.circuitBreaker("load-stock").reset();
        warehouse.failing(true);
        Map<Integer, Integer> failing = fire(100, i -> "/stock/sku-" + i);
        assertThat(failing.keySet()).as(failing.toString()).containsAnyOf(502, 503).isSubsetOf(502, 503);
        assertThat(registry.circuitBreaker("load-stock").state()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(warehouse.calls()).as("at most three attempts per request").isLessThanOrEqualTo(300);
        assertThat(warehouse.inside()).as("every attempt left").isEqualTo(0);

        int callsWhileOpen = warehouse.calls();
        Map<Integer, Integer> open = fire(100, i -> "/stock/sku-" + i);
        assertThat(open).as("rejected while open, not retried").containsOnlyKeys(503);
        assertThat(warehouse.calls()).as("nothing reached the warehouse").isEqualTo(callsWhileOpen);

        warehouse.failing(false);
        awaitUntil(() -> {
            try {
                // one probe at a time until the half-open breaker has seen enough successes to close
                fire(1, i -> "/stock/probe");
                return registry.circuitBreaker("load-stock").state() == CircuitBreakerState.CLOSED;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Map<Integer, Integer> recovered = fire(100, i -> "/stock/sku-" + i);
        assertThat(recovered).as("closed again").containsOnlyKeys(200);
        assertThat(registry.circuitBreaker("load-stock").state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(registry.snapshot().circuitBreakers()).anySatisfy(b -> assertThat(b.instance()).isEqualTo("load-stock"));
    }

    @Test
    void timeoutsInterruptTheBlockedCallsAndAnswerAtOnce() throws Exception {
        warehouse.latency(5_000);
        long started = System.nanoTime();
        Map<Integer, Integer> statuses = fire(50, i -> "/slow/sku-" + i);
        long took = System.nanoTime() - started;

        assertThat(statuses).containsOnlyKeys(504).containsEntry(504, 50);
        assertThat(Duration.ofNanos(took)).as("well before the warehouse's five seconds").isLessThan(Duration.ofSeconds(4));
        awaitUntil(() -> warehouse.interrupted() == 50);
        assertThat(warehouse.inside()).as("every blocked call let go").isEqualTo(0);

        warehouse.latency(0);
        assertThat(fire(50, i -> "/slow/sku-" + i)).as("the request threads are fine afterwards").containsOnlyKeys(200);
    }

    @Test
    void concurrentRetriesOfAWrappedInterfaceDoNotInterfere() throws Exception {
        warehouse.failFirstAttempt();
        Map<Integer, Integer> statuses = fire(200, i -> "/ship/order-" + i);

        assertThat(statuses).containsOnlyKeys(200).containsEntry(200, 200);
        assertThat(warehouse.calls()).as("exactly two attempts per request").isEqualTo(400);
        assertThat(warehouse.attemptsPerId()).allSatisfy(attempts -> assertThat(attempts).hasValue(2));
        assertThat(meters.find("r4c.retry.retries").tags("instance", "load-shipping").counter().count()).isEqualTo(200);
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline).as("waited too long").isNegative();
            Thread.sleep(20);
        }
    }
}
