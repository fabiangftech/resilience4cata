package dev.resilience4cata.quarkus.deployment.reactiveweb;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A Quarkus REST application whose resource returns the protected beans' {@code Mono} and {@code Flux} as they are
 * (streams as server-sent events), on the system clock. Retries, the fallback, exception mappers, a timeout that
 * cancels the backend, a client that disconnects freeing the bulkhead, and for streams: retried before the first tick,
 * not after it, an idle timeout between ticks, a disconnect cancelling them.
 */
class ReactiveWebTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(QuoteBackend.class, QuoteService.class, RatesClient.class,
                    BackendRatesClient.class, QuoteResource.class, QuoteExceptionMappers.class)
                    .addClass("dev.resilience4cata.quarkus.deployment.reactiveweb.QuoteService$Resilient")
                    .addClass("dev.resilience4cata.quarkus.deployment.reactiveweb.RatesClient$Resilient"));

    private static HttpClient http;

    @TestHTTPResource("/web/")
    URI root;

    @Inject
    QuoteBackend backend;

    @Inject
    ResilienceRegistry registry;

    @Inject
    QuoteService quotes;

    @Inject
    RatesClient rates;

    @BeforeAll
    static void start() {
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    @BeforeEach
    void reset() {
        backend.reset();
    }

    private HttpResponse<String> get(String path) {
        try {
            return http.send(HttpRequest.newBuilder(root.resolve(path.substring(1))).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The {@code data} of every server-sent event until the stream ends (normally or not). */
    private List<String> events(String path) throws Exception {
        HttpResponse<Stream<String>> response = http.send(HttpRequest.newBuilder(root.resolve(path.substring(1)))
                .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> data = new java.util.ArrayList<>();
        try (Stream<String> lines = response.body()) {
            lines.filter(line -> line.startsWith("data:")).map(line -> line.substring(5).strip()).forEach(data::add);
        } catch (UncheckedIOException e) {
            // the server closed the connection: a stream that failed after its first events
        }
        return data;
    }

    /** Sends a request on a plain socket and returns it open: closing it is a client that disconnects. */
    private Socket request(String path, String accept) throws IOException {
        URI uri = root;
        path = uri.getPath() + path.substring(1);
        Socket socket = new Socket(uri.getHost(), uri.getPort());
        OutputStream out = socket.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\nHost: " + uri.getHost() + "\r\nAccept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return socket;
    }

    @Test
    void theBeansAreTheGeneratedClasses() {
        assertThat(quotes.getClass().getSimpleName()).startsWith("QuoteService$Resilient");
        assertThat(rates.getClass().getSimpleName()).startsWith("RatesClient$Resilient");
    }

    @Test
    void aRequestIsRetried() {
        backend.failures.set(2);
        HttpResponse<String> response = get("/quotes/a");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("a=42");
        assertThat(backend.calls).hasValue(3);
    }

    @Test
    void whenTheRetriesGiveUpTheFailureMapsToAStatus() {
        backend.failures.set(10);
        assertThat(get("/quotes/a").statusCode()).isEqualTo(502);
        assertThat(backend.calls).hasValue(3);
    }

    @Test
    void theFallbackAnswersWhenTheRetriesGiveUp() {
        backend.failures.set(10);
        HttpResponse<String> response = get("/cached-quotes/b");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("b=40 (cached)");
        assertThat(backend.calls).hasValue(2);
    }

    @Test
    void aTimeoutCancelsTheBackendAndAnswers504() {
        backend.hang = true;
        assertThat(get("/slow-quotes/c").statusCode()).isEqualTo(504);
        awaitUntil(() -> backend.cancellations.get() == 1);
        // the slot is freed after the backend is cancelled, on the thread that cancelled it: wait for it too
        awaitUntil(() -> registry.bulkhead("web-slow-quotes").concurrentCalls() == 0);
    }

    @Test
    void aClientThatDisconnectsCancelsTheCallAndFreesTheBulkhead() throws Exception {
        backend.hang = true;
        Socket waiting = request("/slow-quotes/d", "text/plain");
        awaitUntil(() -> registry.bulkhead("web-slow-quotes").concurrentCalls() == 1);
        assertThat(get("/slow-quotes/e").statusCode()).as("the slot is taken").isEqualTo(503);
        waiting.close();
        awaitUntil(() -> registry.bulkhead("web-slow-quotes").concurrentCalls() == 0);
        assertThat(backend.cancellations).as("the server cancelled the backend's Mono").hasValue(1);
    }

    @Test
    void theInterfaceWrapperOpensItsBreaker() {
        registry.circuitBreaker("web-rates").reset();
        backend.failures.set(10);
        assertThat(get("/rates/eur").statusCode()).isEqualTo(502);
        assertThat(get("/rates/eur").statusCode()).isEqualTo(502);
        assertThat(registry.circuitBreaker("web-rates").state()).isEqualTo(CircuitBreakerState.OPEN);

        assertThat(get("/rates/eur").statusCode()).isEqualTo(503);
        assertThat(backend.calls).as("rejected without calling the backend").hasValue(2);
        registry.circuitBreaker("web-rates").reset();
    }

    @Test
    void aStreamIsRetriedBeforeItsFirstTick() throws Exception {
        backend.failures.set(2);
        assertThat(events("/ticks/a")).containsExactly("a tick 1", "a tick 2", "a tick 3");
        assertThat(backend.calls).hasValue(3);
        awaitUntil(() -> registry.bulkhead("web-ticks").concurrentCalls() == 0);
    }

    /**
     * Retried, then the failure reaches Quarkus REST, which has already sent the server-sent event stream's headers (200)
     * and ends the response by breaking the connection, without events.
     */
    @Test
    void aStreamThatFailsBeforeItsFirstTickIsRetriedThenBreaksTheResponse() throws Exception {
        backend.failures.set(10);
        assertThat(events("/ticks/a")).isEmpty();
        assertThat(backend.calls).hasValue(3);
    }

    @Test
    void aStreamThatBreaksAfterItsFirstTicksIsNotRetried() throws Exception {
        backend.breakAfterTwo = true;
        assertThat(events("/ticks/b")).containsExactly("b tick 1", "b tick 2");
        assertThat(backend.calls).as("committed by the first tick: no retry").hasValue(1);
    }

    @Test
    void aStreamThatStallsTimesOutBetweenTicksAndIsCancelled() throws Exception {
        backend.stall = true;
        assertThat(events("/ticks/c")).containsExactly("c tick 1");
        awaitUntil(() -> backend.cancellations.get() == 1);
        assertThat(backend.calls).hasValue(1);
        awaitUntil(() -> registry.bulkhead("web-ticks").concurrentCalls() == 0);
    }

    @Test
    void aClientThatDisconnectsFromAStreamCancelsItAndFreesTheBulkhead() throws Exception {
        Socket stream = request("/feed/d", "text/event-stream");
        awaitUntil(() -> registry.bulkhead("web-feed").concurrentCalls() == 1);
        assertThat(new String(stream.getInputStream().readNBytes(1), StandardCharsets.US_ASCII)).as("response started")
                .isNotEmpty();
        stream.close();
        awaitUntil(() -> registry.bulkhead("web-feed").concurrentCalls() == 0);
        assertThat(backend.cancellations).as("the server cancelled the stream").hasValue(1);
        assertThat(backend.calls).hasValue(1);
    }

    @Test
    void aClientThatDisconnectsBeforeTheFirstEventCancelsTheStreamToo() throws Exception {
        backend.hang = true;
        Socket stream = request("/ticks/d", "text/event-stream");
        awaitUntil(() -> registry.bulkhead("web-ticks").concurrentCalls() == 1);
        stream.close();
        awaitUntil(() -> registry.bulkhead("web-ticks").concurrentCalls() == 0);
        awaitUntil(() -> backend.cancellations.get() == 1);
        assertThat(backend.calls).as("cancelled before its timeout: no retry").hasValue(1);
    }

    @Test
    void theInterfaceWrapperStreamsAndFallsBackBeforeTheFirstTick() throws Exception {
        assertThat(events("/rate-ticks/eur")).containsExactly("eur tick 1", "eur tick 2", "eur tick 3");
        backend.failures.set(10);
        assertThat(events("/rate-ticks/eur")).containsExactly("eur closed");
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline).as("waited too long").isNegative();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
