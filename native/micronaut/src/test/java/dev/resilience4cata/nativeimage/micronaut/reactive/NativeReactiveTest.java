package dev.resilience4cata.nativeimage.micronaut.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

/**
 * The {@code Mono} and {@code Flux} beans over HTTP, on the JVM ({@code test}) and inside a GraalVM native image
 * ({@code nativeTest}), on the system clock: the same scenarios as the Quarkus extension's {@code ReactiveSmokeIT}.
 */
@MicronautTest
class NativeReactiveTest {

    @Inject
    EmbeddedServer server;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void closeClient() {
        http.close();
    }

    @Test
    void theReactiveBeansAreTheGeneratedClasses() throws Exception {
        assertThat(get("/reactive/beans").body()).isEqualTo(
                "dev.resilience4cata.nativeimage.micronaut.reactive.QuoteService$Resilient"
                        + " dev.resilience4cata.nativeimage.micronaut.reactive.RatesClient$Resilient");
    }

    @Test
    void aMonoMethodOfAnAnnotatedClassIsRetried() throws Exception {
        HttpResponse<String> response = get("/reactive/quotes/a?failures=2");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("a=42");
        assertThat(calls()).isEqualTo(3);
    }

    @Test
    void aMonoMethodThatKeepsFailingGivesUpAfterItsAttempts() throws Exception {
        assertThat(get("/reactive/quotes/a?failures=10").statusCode()).isEqualTo(500);
        assertThat(calls()).isEqualTo(3);
    }

    @Test
    void aMonoThatNeverAnswersTimesOutIntoItsFallback() throws Exception {
        HttpResponse<String> response = get("/reactive/slow-quotes/a");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("a late: CallTimeoutException");
    }

    @Test
    void aFluxMethodIsRetriedBeforeItsFirstElementAndStreamsEveryTick() throws Exception {
        assertThat(events("/reactive/ticks/a?failures=2")).containsExactly("a tick 1", "a tick 2", "a tick 3");
        assertThat(calls()).isEqualTo(3);
    }

    @Test
    void aMonoMethodOfAWrappedInterfaceIsRetried() throws Exception {
        HttpResponse<String> response = get("/reactive/rates/eur?failures=2");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("eur=42");
        assertThat(calls()).isEqualTo(3);
    }

    @Test
    void aFluxMethodOfAWrappedInterfaceFallsBackToItsOwnStream() throws Exception {
        assertThat(events("/reactive/rate-ticks/eur?failures=10")).containsExactly("eur closed");
        assertThat(calls()).isEqualTo(2);
    }

    @Test
    void aFluxMethodOfAWrappedInterfaceStreamsEveryTick() throws Exception {
        assertThat(events("/reactive/rate-ticks/eur")).containsExactly("eur tick 1", "eur tick 2", "eur tick 3");
        assertThat(calls()).isEqualTo(1);
    }

    private List<String> events(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body().lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).strip()).toList();
    }

    private int calls() throws IOException, InterruptedException {
        return Integer.parseInt(get("/reactive/calls").body());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return server.getURI().resolve(path);
    }
}
