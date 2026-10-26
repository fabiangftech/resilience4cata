package dev.resilience4cata.spring.guide;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.testkit.ManualClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Runs the guide's code in a Spring Boot test, with the guide's {@code guide.yml}. */
@SpringBootTest(properties = "spring.config.name=guide")
class SpringGuideTest {

    // tag::spring-test[]
    @TestConfiguration
    static class TestClock {

        @Bean
        ManualClock clock() {
            return new ManualClock(); // retry pauses, waits and timeouts follow it instead of the system clock
        }
    }

    @Autowired
    OrderService orders;

    @Autowired
    ManualClock clock;

    @Autowired
    OrderRepository repository; // fails the first loads it is told to

    @Test
    void retriesWithoutSleeping() {
        repository.failures.set(2);

        assertThat(orders.find("42")).isEqualTo(new Order("42"));
        assertThat(clock.elapsed()).isPositive(); // the two pauses, taken on the manual clock
    }
    // end::spring-test[]

    @Autowired
    StockReport report;

    @Autowired
    ResilienceRegistry registry;

    @Autowired
    Alerts alerts;

    @Test
    void callsOnThisAreRetried() {
        repository.failures.set(1);
        assertThat(orders.findAgain("7")).isEqualTo(new Order("7"));
        assertThat(repository.loads).hasValue(2);
    }

    @Test
    void theInterfaceBeanIsWrapped() {
        assertThat(report.line("sku")).isEqualTo("sku: 42");
        assertThat(registry.snapshot().timeouts()).extracting(s -> s.config().timeout())
                .containsExactly(Duration.ofSeconds(3)); // the instances key of guide.yml
    }

    @Test
    void theYamlAndTheCustomizerConfigureTheRegistry() {
        assertThat(registry.retryConfig("").delay()).isEqualTo(Duration.ofMillis(50));
        assertThat(registry.circuitBreakerConfig("").minimumCalls()).isEqualTo(20);
        assertThat(registry.retryConfig("slow").maxAttempts()).as("the customizer").isEqualTo(5);
        assertThat(registry.retryConfig("slow").delay()).as("the YAML wins").isEqualTo(Duration.ofMillis(500));
        assertThat(registry.circuitBreaker("orders").config().failureRateThreshold()).isEqualTo(25f);
    }

    @Test
    void theListenerGetsBreakerTransitions() {
        registry.circuitBreaker("orders").forceOpen();
        assertThat(alerts.sent).containsExactly("orders is now FORCED_OPEN");
        registry.circuitBreaker("orders").reset();
    }
}
