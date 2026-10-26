package dev.resilience4cata.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.micrometer.MicrometerMetrics;
import dev.resilience4cata.spring.fixtures.PaymentsGateway;
import dev.resilience4cata.spring.fixtures.ShippingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** Micrometer metrics, the health indicator and the actuator endpoint. */
class ObservabilityTest {

    private final ApplicationContextRunner runner = Runners.runner().withUserConfiguration(PaymentsGateway.class);

    @Test
    void metricsAreBoundWhenTheMicrometerModuleAndAMeterRegistryArePresent() {
        runner.withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            assertThat(context).hasSingleBean(MicrometerMetrics.class);
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            assertThat(meters.find("r4c.circuitbreaker.state").tag("instance", "payments").tag("state", "closed")
                    .gauge().value()).as("gauge bound after startup").isEqualTo(1.0);

            failTwice(context.getBean(PaymentsGateway.class));
            assertThat(meters.find("r4c.circuitbreaker.transitions").tag("instance", "payments").tag("to", "open")
                    .counter().count()).as("counter fed by the registry's events").isEqualTo(1.0);
        });
    }

    @Test
    void noMetricsWithoutTheMicrometerModuleOrAMeterRegistryOrWhenDisabled() {
        runner.withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withClassLoader(new FilteredClassLoader(MicrometerMetrics.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MicrometerMetrics.class));
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MicrometerMetrics.class));
        runner.withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("resilience4cata.spring.metrics.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MicrometerMetrics.class));
    }

    @Test
    void healthStaysUpWithAnOpenBreakerByDefault() {
        runner.run(context -> {
            context.getBean(ResilienceRegistry.class).circuitBreaker("payments").forceOpen();
            Health health = context.getBean(ResilienceHealthIndicator.class).health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(breakerDetails(health)).containsEntry("state", CircuitBreakerState.FORCED_OPEN);
        });
    }

    @Test
    void listedBreakersTurnTheHealthDownWhileOpenAndUnknownWhileHalfOpen() {
        runner.withPropertyValues("resilience4cata.spring.health.down-when-open=payments").run(context -> {
            ResilienceHealthIndicator indicator = context.getBean(ResilienceHealthIndicator.class);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

            failTwice(context.getBean(PaymentsGateway.class));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

            context.getBean(dev.resilience4cata.testkit.ManualClock.class).advance(java.time.Duration.ofMinutes(5));
            context.getBean(PaymentsGateway.class).pay("probe"); // the first half-open probe
            assertThat(context.getBean(ResilienceRegistry.class).circuitBreaker("payments").state())
                    .isEqualTo(CircuitBreakerState.HALF_OPEN);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
        });
        runner.withPropertyValues("resilience4cata.spring.health.down-when-open=*").run(context -> {
            context.getBean(ResilienceRegistry.class).circuitBreaker("payments").forceOpen();
            assertThat(context.getBean(ResilienceHealthIndicator.class).health().getStatus()).isEqualTo(Status.DOWN);
        });
    }

    @Test
    void theHealthIndicatorCanBeTurnedOff() {
        runner.withPropertyValues("management.health.resilience4cata.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ResilienceHealthIndicator.class));
        runner.withClassLoader(new FilteredClassLoader(org.springframework.boot.health.contributor.HealthIndicator.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ResilienceHealthIndicator.class));
    }

    @Test
    void theEndpointReportsSnapshotsWhenExposed() {
        runner.withUserConfiguration(ShippingService.class)
                .withPropertyValues("management.endpoints.web.exposure.include=resilience4cata").run(context -> {
                    ResilienceEndpoint endpoint = context.getBean(ResilienceEndpoint.class);
                    ResilienceSnapshot all = endpoint.snapshot();
                    assertThat(all.circuitBreakers()).extracting(s -> s.instance()).containsExactly("payments");
                    assertThat(all.retries()).extracting(s -> s.instance()).containsExactly("shipping");

                    ResilienceSnapshot one = endpoint.instance("payments");
                    assertThat(one).isNotNull();
                    assertThat(one.circuitBreakers()).hasSize(1);
                    assertThat(one.retries()).isEmpty();
                    assertThat(endpoint.instance("nobody")).as("404").isNull();

                    dev.resilience4cata.ResilienceRegistry registry = context.getBean(dev.resilience4cata.ResilienceRegistry.class);
                    registry.adaptiveLimitPolicy("Orders#place()", "orders", "", null);
                    registry.hedgePolicy("Quotes#get()", "quotes", "", null);
                    assertThat(endpoint.instance("orders")).isNotNull().satisfies(o -> assertThat(o.adaptiveLimits()).hasSize(1));
                    assertThat(endpoint.instance("quotes")).isNotNull().satisfies(q -> assertThat(q.hedges()).hasSize(1));

                    String json = JsonMapper.builder().build().writeValueAsString(endpoint.snapshot());
                    assertThat(json).contains("\"instance\":\"payments\"", "\"state\":\"CLOSED\"", "\"maxAttempts\":3",
                            "\"adaptiveLimits\":[{\"instance\":\"orders\",\"algorithm\":\"GRADIENT2\",\"limit\":20");
                });
    }

    @Test
    void theEndpointIsAbsentUnlessExposed() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ResilienceEndpoint.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> breakerDetails(Health health) {
        Map<String, Object> breakers = (Map<String, Object>) health.getDetails().get("circuitBreakers");
        return (Map<String, Object>) breakers.get("payments");
    }

    private static void failTwice(PaymentsGateway payments) {
        for (int i = 0; i < 2; i++) {
            try {
                payments.pay("");
            } catch (IllegalArgumentException expected) {
                // recorded
            }
        }
    }
}
