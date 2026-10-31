package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.micrometer.MicrometerMetrics;
import dev.resilience4cata.micronaut.fixtures.Outage;
import dev.resilience4cata.micronaut.fixtures.Shipping;
import dev.resilience4cata.testkit.ManualClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.health.HealthStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Micrometer metrics, the health indicator and the management endpoint. */
class ObservabilityTest {

    @Test
    void metricsAreBoundWhenTheMicrometerModuleAndAMeterRegistryArePresent() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertThat(context.containsBean(MicrometerMetrics.class)).isTrue();
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            Shipping shipping = context.getBean(Shipping.class);
            assertThat(meters.find("r4c.circuitbreaker.state").tag("instance", "shipping-quotes").tag("state", "closed")
                    .gauge().value()).as("gauge bound when the instance is created").isEqualTo(1.0);

            failTwice(context);
            assertThat(meters.find("r4c.circuitbreaker.transitions").tag("instance", "shipping-quotes").tag("to", "open")
                    .counter().count()).as("counter fed by the registry's events").isEqualTo(1.0);
            assertThat(shipping).isNotNull();
        }
    }

    @Test
    void noMetricsWithoutAMeterRegistryOrWhenDisabled() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("micronaut.metrics.enabled", "false"))) {
            assertThat(context.containsBean(MeterRegistry.class)).isFalse();
            assertThat(context.containsBean(MicrometerMetrics.class)).isFalse();
            assertThat(context.getBean(ResilienceRegistry.class)).isNotNull();
        }
        try (ApplicationContext context = ApplicationContext.run(Map.of("resilience4cata.micronaut.metrics.enabled", "false"))) {
            assertThat(context.containsBean(MicrometerMetrics.class)).isFalse();
        }
    }

    @Test
    void healthStaysUpWithAnOpenBreakerByDefault() {
        try (ApplicationContext context = ApplicationContext.run()) {
            context.getBean(Shipping.class);
            context.getBean(ResilienceRegistry.class).circuitBreaker("shipping-quotes").forceOpen();
            ResilienceHealthIndicator indicator = context.getBean(ResilienceHealthIndicator.class);

            assertThat(indicator.health().getStatus()).isEqualTo(HealthStatus.UP);
            assertThat(indicator.health().getDetails().toString()).contains("shipping-quotes", "FORCED_OPEN");
        }
    }

    @Test
    void listedBreakersTurnTheHealthDownWhileOpenAndUnknownWhileHalfOpen() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "resilience4cata.micronaut.health.down-when-open", "shipping-quotes"))) {
            ResilienceHealthIndicator indicator = context.getBean(ResilienceHealthIndicator.class);
            context.getBean(Shipping.class);
            assertThat(indicator.health().getStatus()).isEqualTo(HealthStatus.UP);

            failTwice(context);
            assertThat(indicator.health().getStatus()).isEqualTo(HealthStatus.DOWN);

            context.getBean(ManualClock.class).advance(Duration.ofMinutes(1));
            context.getBean(Shipping.class).quote("probe"); // the first half-open probe
            assertThat(context.getBean(ResilienceRegistry.class).circuitBreaker("shipping-quotes").state())
                    .isEqualTo(CircuitBreakerState.HALF_OPEN);
            assertThat(indicator.health().getStatus()).isEqualTo(HealthStatus.UNKNOWN);
        }
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "resilience4cata.micronaut.health.down-when-open", "*"))) {
            context.getBean(Shipping.class);
            context.getBean(ResilienceRegistry.class).circuitBreaker("shipping-quotes").forceOpen();
            assertThat(context.getBean(ResilienceHealthIndicator.class).health().getStatus()).isEqualTo(HealthStatus.DOWN);
        }
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "resilience4cata.micronaut.health.enabled", "false"))) {
            assertThat(context.containsBean(ResilienceHealthIndicator.class)).isFalse();
        }
    }

    @Test
    void theEndpointAndTheHealthReportServeJson() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "micronaut.server.port", "-1",
                "endpoints.resilience4cata.sensitive", "false",
                "endpoints.health.details-visible", "ANONYMOUS"));
                HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            server.getApplicationContext().getBean(Shipping.class).track("p1");

            String all = client.toBlocking().retrieve("/resilience4cata");
            assertThat(all).contains("\"circuitBreakers\"", "\"id\":\"shipping-quotes\"", "\"state\":\"CLOSED\"",
                    "\"timeouts\"", "\"timeout\":\"PT2S\"");
            String one = client.toBlocking().retrieve("/resilience4cata/shipping-quotes");
            assertThat(one).contains("shipping-quotes").doesNotContain("Shipping#track");
            assertThatThrownBy(() -> client.toBlocking().retrieve("/resilience4cata/nobody"))
                    .isInstanceOf(HttpClientResponseException.class).hasMessageContaining("Not Found");

            String health = client.toBlocking().retrieve("/health");
            assertThat(health).contains("\"resilience4cata\"", "shipping-quotes");
        }
    }

    @Test
    void theEndpointIsSensitiveByDefault() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("micronaut.server.port", "-1"));
                HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertThatThrownBy(() -> client.toBlocking().retrieve("/resilience4cata"))
                    .isInstanceOf(HttpClientResponseException.class);
        }
    }

    /** Two failures of {@code quote}: the breaker (minimum 2 calls, 50%) opens. */
    private static void failTwice(ApplicationContext context) {
        Outage outage = context.getBean(Outage.class);
        Shipping shipping = context.getBean(Shipping.class);
        outage.failNext(2);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> shipping.quote("x")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(context.getBean(ResilienceRegistry.class).circuitBreaker("shipping-quotes").state())
                .isEqualTo(CircuitBreakerState.OPEN);
    }
}
