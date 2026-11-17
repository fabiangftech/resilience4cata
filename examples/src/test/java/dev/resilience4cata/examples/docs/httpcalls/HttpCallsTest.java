package dev.resilience4cata.examples.docs.httpcalls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.resilience4cata.CallRejectedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks what docs/{en,es}/guides/http-calls.md says, through a real HTTP server on localhost that answers with
 * the statuses each test queues (the last one repeats).
 */
class HttpCallsTest {

    /** Queued instead of a status: answer 200, but only after two seconds. */
    private static final int SLOW = -1;

    /** The retry's instance: {@code @Retry} has no name, so its id is derived from the method. */
    private static final String RETRY = "dev.resilience4cata.examples.docs.httpcalls.InventoryClient#stock(java.lang.String)";

    private final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int last = 200;
    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/stock/", exchange -> {
            requests.incrementAndGet();
            Integer next = statuses.poll();
            int status = next != null ? next : last;
            last = status;
            if (status == SLOW) {
                try {
                    // Never answers within the test: the 1 s @Timeout must give up first however late it fires. A
                    // 2 s answer arrived first once on a loaded macOS runner, where the watchdog wakes up late.
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                status = 200;
            }
            byte[] body = (status == 200 ? "7" : "error").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // a slow answer must not block the next request
        server.start();
        base = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // tag::http-calls-wiring[]
    static InventoryClient connect(URI base, ResilienceRegistry registry) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        return Resilient.of(InventoryClient.class, new HttpInventoryClient(http, base), registry);
    }
    // end::http-calls-wiring[]

    @Test
    void serverErrorsAreRetried() throws Exception {
        statuses.addAll(List.of(503, 503, 200));
        InventoryClient inventory = connect(base, withoutTimeBudget().build());

        assertThat(inventory.stock("sku-1")).isEqualTo(7);
        assertThat(requests).hasValue(3);
    }

    @Test
    void clientErrorsAreNeitherRetriedNorCountedByTheBreaker() {
        statuses.add(404);
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        InventoryClient inventory = connect(base, registry);

        assertThatThrownBy(() -> inventory.stock("sku-1")).isInstanceOf(InventoryClient.ClientError.class)
                .hasMessage("HTTP 404");
        assertThat(requests).hasValue(1);
        assertThat(registry.circuitBreaker("inventory").snapshot().bufferedCalls()).isZero();
    }

    @Test
    void anOpenBreakerStopsTheRetriesBeforeTheServiceIsCalledAgain() {
        statuses.add(500);
        ResilienceRegistry registry = withoutTimeBudget()
                .circuitBreakerInstance("inventory", b -> b.windowSize(2).minimumCalls(2))
                .build();
        InventoryClient inventory = connect(base, registry);

        assertThatThrownBy(() -> inventory.stock("sku-1")).isInstanceOf(CallRejectedException.class);
        assertThat(requests).as("the third attempt was rejected without a request").hasValue(2);
        assertThat(registry.circuitBreaker("inventory").state()).isEqualTo(CircuitBreakerState.OPEN);
    }

    /**
     * The retry without its 3 s {@code maxDuration}, for the tests that count attempts: on a busy two-core CI runner
     * the first requests of a cold JVM once took 3.5 s, the budget ran out and the third attempt never ran. The
     * budget itself is tested by {@link #aSlowAnswerIsInterruptedAndRetriedUntilTheAttemptsOrTheBudgetRunOut}.
     */
    private static ResilienceRegistry.Builder withoutTimeBudget() {
        return ResilienceRegistry.builder().retryInstance(RETRY, r -> r.maxDuration(Duration.ZERO));
    }

    @Test
    void aSlowAnswerIsInterruptedAndRetriedUntilTheAttemptsOrTheBudgetRunOut() {
        statuses.add(SLOW);
        InventoryClient inventory = connect(base, ResilienceRegistry.builder().build());

        assertThatThrownBy(() -> inventory.stock("sku-1")).isInstanceOf(CallTimeoutException.class);
        // Three attempts, or fewer once the 3 s budget ran out: a timeout that fires late (a starved watchdog) leaves
        // room for fewer of them, so only the bounds are checked here; the budget's arithmetic is RetryPolicy's tests'.
        assertThat(requests.get()).as("at most three attempts, within the 3 s budget").isBetween(1, 3);
    }
}
