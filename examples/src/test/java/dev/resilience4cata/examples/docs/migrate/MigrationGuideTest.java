package dev.resilience4cata.examples.docs.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The code of {@code docs/en/migrate/from-resilience4j.md}. */
class MigrationGuideTest {

    @Test
    void theMigratedClientRetriesThenFallsBack() throws IOException {
        ManualClock clock = new ManualClock();
        AtomicInteger calls = new AtomicInteger();
        // tag::migrate-r4c-use[]
        ResilienceRegistry registry = ResilienceTesting.registry(clock).build(); // ResilienceRegistry.builder() in production
        StockClient stock = Resilient.of(StockClient.class, sku -> {
            calls.incrementAndGet();
            throw new IOException("inventory unreachable");
        }, registry);

        int units = stock.stock("SKU-1"); // three attempts 500 ms apart, then the fallback
        // end::migrate-r4c-use[]

        assertThat(units).isEqualTo(-1);
        assertThat(calls).hasValue(3);
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(1));
        assertThat(registry.circuitBreaker("inventory").state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void thePropertiesOverrideTheAnnotations() {
        ResilienceConfig config = ResilienceConfig.builder().classpath("docs/migrate/inventory.properties").build();
        ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
        Resilient.of(StockClient.class, sku -> 1, registry);
        config.verifyInstances(registry);

        CircuitBreakerConfig breaker = registry.circuitBreaker("inventory").config();
        assertThat(breaker.windowSize()).isEqualTo(50);
        assertThat(breaker.waitInOpen()).isEqualTo(Duration.ofSeconds(10));
        assertThat(breaker.failureRateThreshold()).isEqualTo(50f); // the annotation beats defaults.*
        assertThat(breaker.minimumCalls()).isEqualTo(10);
        assertThat(registry.snapshot().retries()).singleElement()
                .satisfies(r -> assertThat(r.config().maxAttempts()).isEqualTo(5));
    }
}
