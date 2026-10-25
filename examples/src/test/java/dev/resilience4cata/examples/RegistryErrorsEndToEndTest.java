package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The registry's error codes as they reach users of generated code and pipelines (docs/en/errors.md, R4C-REG). */
class RegistryErrorsEndToEndTest {

    @Test
    void sharedNameWithTwoConfigurationsFailsWhenWrapping() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        SharedNameClient impl = new SharedNameClient() {
            @Override
            public String stock(String sku) {
                return sku;
            }

            @Override
            public String reserve(String sku) {
                return sku;
            }
        };

        assertThatIllegalStateException().isThrownBy(() -> Resilient.of(SharedNameClient.class, impl, registry))
                .withMessageStartingWith("[R4C-REG-008] Retry instance 'backend' is used with two different"
                        + " configurations");
    }

    @Test
    void instanceOverrideThatDoesNotFitFailsWhenThePipelineIsBuilt() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("payments", r -> r.delay(Duration.ofSeconds(30)))
                .build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Pipeline.builder(registry, "payments")
                        .retry(r -> r.maxDelay(Duration.ofSeconds(20))).build())
                .withMessageStartingWith("[R4C-REG-003] Invalid retry config for instance 'payments': maxDelay"
                        + " (PT20S) must not be shorter than delay (PT30S)")
                .withMessageContaining("retryInstance(\"payments\", ...), or instances.payments.retry.*");
    }

    @Test
    void unknownConfigSuggestsTheClosestName() {
        ResilienceRegistry registry = ResilienceRegistry.builder().retry("five", r -> r.maxAttempts(5)).build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Pipeline.builder(registry, "orders").retry("fiev").build())
                .withMessageStartingWith("[R4C-REG-004] Unknown retry config 'fiev'")
                .withMessageContaining("Did you mean 'five'?");
    }
}
