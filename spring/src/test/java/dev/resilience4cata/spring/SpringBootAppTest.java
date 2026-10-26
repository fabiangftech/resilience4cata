package dev.resilience4cata.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.fixtures.InventoryClient;
import dev.resilience4cata.spring.fixtures.Money;
import dev.resilience4cata.spring.fixtures.PaymentsGateway;
import dev.resilience4cata.spring.fixtures.PricingService;
import dev.resilience4cata.spring.fixtures.PricingService$Resilient;
import dev.resilience4cata.testkit.ManualClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/**
 * A full Spring Boot application: component scanning of the fixtures package, auto-configuration from
 * {@code META-INF/spring/...AutoConfiguration.imports} (nothing listed by hand), Boot's metrics, health and
 * actuator auto-configurations on the class path.
 */
@SpringBootTest(classes = SpringBootAppTest.App.class, properties = {
        "resilience4cata.instances.payments.circuit-breaker.minimum-calls=10",
        "management.endpoints.web.exposure.include=health,resilience4cata"})
class SpringBootAppTest {

    @Autowired
    InventoryClient inventory;

    @Autowired
    PricingService pricing;

    @Autowired
    PaymentsGateway payments;

    @Autowired
    ResilienceRegistry registry;

    @Autowired
    ResilienceEndpoint endpoint;

    @Autowired
    ResilienceHealthIndicator health;

    @Test
    void scannedBeansAreProtected() {
        assertThat(inventory.getClass().getName()).endsWith("InventoryClient$Resilient");
        assertThat(pricing).isInstanceOf(PricingService$Resilient.class);
        assertThat(pricing.currency()).isEqualTo("EUR");
        assertThat(payments.getClass().getName()).endsWith("PaymentsGateway$Resilient");

        pricing.failures.set(2);
        assertThat(pricing.price("a")).isEqualTo(105);
        assertThat(pricing.attempts).hasValue(3);
    }

    @Test
    void propertiesActuatorAndHealthAreWired() {
        assertThat(registry.circuitBreaker("payments").config().minimumCalls()).isEqualTo(10);
        assertThat(endpoint.snapshot().circuitBreakers()).extracting(s -> s.instance()).contains("payments");
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
    }

    @SpringBootApplication(scanBasePackageClasses = InventoryClient.class)
    static class App {

        @Bean
        ManualClock clock() {
            return new ManualClock();
        }

        @Bean
        Money euro() {
            return new Money("EUR");
        }

        @Bean
        Money dollar() {
            return new Money("USD");
        }
    }
}
