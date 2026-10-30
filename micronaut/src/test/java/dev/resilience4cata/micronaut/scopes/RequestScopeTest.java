package dev.resilience4cata.micronaut.scopes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A request-scoped annotated class of a Micronaut application on Netty, called over HTTP: one generated-subclass
 * instance per request, every attempt of a call on it, named policies shared by every request's instance. A
 * {@code @ResilientBean} in the request scope stops the startup (R4C-MN-003).
 */
class RequestScopeTest {

    private static EmbeddedServer server;
    private static HttpClient http;

    @BeforeAll
    static void start() {
        server = ApplicationContext.run(EmbeddedServer.class, Map.of("micronaut.server.port", "-1", "test.scopes", "web"));
        http = server.getApplicationContext().createBean(HttpClient.class, server.getURL());
    }

    @AfterAll
    static void stop() {
        http.close();
        server.close();
    }

    @Test
    void aRequestScopedAnnotatedClassRetriesOnTheRequestsOwnInstance() {
        String[] parts = get("/scopes/quotes/a?failures=2", 200).split(" \\| ");
        String[] results = parts[0].split(" ");
        String instance = results[0].substring(0, results[0].indexOf(':'));
        assertThat(results).containsExactly(instance + ":a", instance + ":a");
        assertThat(parts[1].split(" ")).as("two failed attempts, then the two calls, all on one instance")
                .containsExactly(instance, instance, instance, instance);

        assertThat(get("/scopes/quotes/a", 200)).as("a new request, a new instance").doesNotContain(instance + ":");
    }

    @Test
    void aRequestScopedAnnotatedClassThatKeepsFailingGivesUp() {
        assertThat(get("/scopes/quotes/a?failures=10", 502)).isEqualTo("down");
    }

    @Test
    void everyRequestsInstanceSharesTheNamedBreaker() {
        get("/scopes/guarded-quotes/a?failures=1", 502);
        get("/scopes/guarded-quotes/a?failures=1", 502);
        assertThat(get("/scopes/guarded-quotes/a", 503)).as("opened by two other requests' instances").isEqualTo("open");
    }

    @Test
    void aResilientBeanInTheRequestScopeStopsTheStartup() {
        assertThatThrownBy(() -> ApplicationContext.run(Map.of("test.scopes", "resilient-request")).close())
                .hasStackTraceContaining("[R4C-MN-003]").hasStackTraceContaining("RequestTrace");
    }

    private static String get(String path, int status) {
        BlockingHttpClient client = http.toBlocking();
        try {
            HttpResponse<String> response = client.exchange(HttpRequest.GET(path), String.class);
            assertThat(response.code()).as(path).isEqualTo(status);
            return response.body();
        } catch (HttpClientResponseException e) {
            assertThat(e.code()).as(path + ": " + e.getResponse().getBody(String.class).orElse("")).isEqualTo(status);
            return e.getResponse().getBody(String.class).orElse("");
        }
    }
}
