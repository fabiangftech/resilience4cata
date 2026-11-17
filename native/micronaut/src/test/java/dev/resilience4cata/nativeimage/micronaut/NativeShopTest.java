package dev.resilience4cata.nativeimage.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

/**
 * The shop over HTTP, on the JVM ({@code test}) and inside a GraalVM native image ({@code nativeTest}): the generated
 * classes are the beans, their policies and the YAML configuration apply, and the management endpoints (health,
 * metrics, resilience4cata) answer.
 */
@MicronautTest
class NativeShopTest {

    @Inject
    EmbeddedServer server;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void closeClient() {
        http.close();
    }

    @Test
    void theBeansAreTheGeneratedClasses() throws Exception {
        assertThat(get("/shop/beans").body()).isEqualTo(
                "dev.resilience4cata.nativeimage.micronaut.Inventory$Resilient"
                        + " dev.resilience4cata.nativeimage.micronaut.Prices$Resilient");
    }

    @Test
    void anAnnotatedClassRetriesWithItsConfigurationFromYaml() throws Exception {
        get("/warehouse/calls");
        post("/warehouse/failures/3"); // the annotation's 3 attempts would give up; application.yml allows 4
        HttpResponse<String> response = get("/stock/abc");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("3");
        assertThat(get("/warehouse/calls").body()).isEqualTo("4");
    }

    @Test
    void aWrappedInterfaceFallsBackAfterItsRetries() throws Exception {
        get("/warehouse/calls");
        post("/warehouse/failures/5");
        assertThat(get("/price/abc").body()).isEqualTo("abc: list price");
        assertThat(get("/warehouse/calls").body()).isEqualTo("2");
        post("/warehouse/failures/0");
        assertThat(get("/price/abc").body()).isEqualTo("abc: 30");
    }

    @Test
    void managementReportsHealthMetricsAndTheRegistry() throws Exception {
        post("/warehouse/failures/1");
        get("/stock/abc"); // one retry, so the counter exists

        HttpResponse<String> health = get("/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("resilience4cata").contains("\"status\":\"UP\"");
        HttpResponse<String> retries = get("/metrics/r4c.retry.retries");
        assertThat(retries.statusCode()).isEqualTo(200);
        assertThat(retries.body()).contains("\"name\":\"r4c.retry.retries\"").contains("inventory");
        HttpResponse<String> registry = get("/resilience4cata/inventory");
        assertThat(registry.statusCode()).isEqualTo(200);
        assertThat(registry.body()).contains("\"inventory\"").contains("maxAttempts");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(server.getURI().resolve(path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void post(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(server.getURI().resolve(path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
