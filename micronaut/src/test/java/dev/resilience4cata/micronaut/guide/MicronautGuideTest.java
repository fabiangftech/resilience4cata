package dev.resilience4cata.micronaut.guide;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.testkit.ManualClock;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Runs the code of the Micronaut guide, in the {@code guide} environment ({@code application-guide.yml}). */
@MicronautTest(environments = "guide")
class MicronautGuideTest {

    // tag::micronaut-test[]
    @Inject
    OrderService orders;

    @Inject
    ManualClock clock; // the @Factory above, in src/test

    @Inject
    OrderRepository repository; // fails the first loads it is told to

    @Test
    void retriesWithoutSleeping() {
        repository.failures.set(2);

        assertThat(orders.find("42")).isEqualTo(new Order("42"));
        assertThat(clock.elapsed()).isPositive(); // the two pauses, taken on the manual clock
    }
    // end::micronaut-test[]

    @Inject
    ResilienceRegistry registry;

    @Inject
    StockReport report;

    @Inject
    InvoiceClient invoices;

    @Inject
    Alerts alerts;

    @Test
    void anAnnotatedClassIsItsGeneratedSubclassAndSelfInvocationIsRetried() {
        assertThat(orders.getClass().getSimpleName()).isEqualTo("OrderService$Resilient");
        repository.failures.set(1);
        assertThat(orders.findAgain("7")).isEqualTo(new Order("7"));
    }

    @Test
    void anInterfaceBeanIsWrapped() {
        assertThat(report.line("sku")).isEqualTo("sku: 42");
        assertThat(registry.snapshot().timeouts()).anySatisfy(timeout -> {
            assertThat(timeout.instance()).isEqualTo(StockClient.class.getName() + "#available(java.lang.String)");
            assertThat(timeout.config().timeout()).as("instances.\"…\".timeout.value").isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    void aFactoryReturnsTheGeneratedSubclass() {
        assertThat(invoices.getClass().getSimpleName()).isEqualTo("InvoiceClient$Resilient");
        assertThat(invoices.fetch("1")).isEqualTo("https://invoices.example.com/invoices/1");
    }

    @Test
    void theYamlTheCustomizerAndTheListenerApply() {
        orders.find("1");
        assertThat(registry.snapshot().retries()).anySatisfy(retry -> {
            assertThat(retry.instance()).isEqualTo(OrderService.class.getName() + "#find(java.lang.String)");
            assertThat(retry.config().delay()).as("the annotation's 100ms over defaults.retry.delay")
                    .isEqualTo(Duration.ofMillis(100));
        });
        Pipeline.builder(registry, "slow-probe").retry("slow").build(); // an instance of the named config "slow"
        RetrySnapshot probe = registry.snapshot().retries().stream().filter(r -> r.instance().equals("slow-probe"))
                .findFirst().orElseThrow();
        assertThat(probe.config().maxAttempts()).as("the customizer's").isEqualTo(5);
        assertThat(probe.config().delay()).as("configs.slow.retry.delay wins over the customizer")
                .isEqualTo(Duration.ofMillis(500));

        registry.circuitBreaker("orders").forceOpen();
        assertThat(registry.circuitBreaker("orders").state()).isEqualTo(CircuitBreakerState.FORCED_OPEN);
        assertThat(alerts.sent).contains("orders is now FORCED_OPEN");
        registry.circuitBreaker("orders").reset();
    }
}
