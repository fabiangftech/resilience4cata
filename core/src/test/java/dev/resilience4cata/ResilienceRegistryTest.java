package dev.resilience4cata;

import dev.resilience4cata.generated.Policies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.resilience4cata.generated.RetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ResilienceRegistryTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .retryDefaults(r -> r.maxAttempts(4))
            .retry("external", r -> r.delay(Duration.ofSeconds(1)))
            .build();

    @Test
    void namedConfigsStartFromTheDefaults() {
        RetryConfig external = registry.retryConfig("external");

        assertThat(external.maxAttempts()).isEqualTo(4);
        assertThat(external.delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(registry.retryConfig("")).isEqualTo(registry.retryConfig("default"));
    }

    @Test
    void unknownConfigNamesTheKnownOnes() {
        assertThatIllegalArgumentException().isThrownBy(() -> registry.retryConfig("extrenal"))
                .withMessageStartingWith("[R4C-REG-004] Unknown retry config 'extrenal'")
                .withMessageContaining("Did you mean 'external'?")
                .withMessageContaining("Known retry configs: [external]");
    }

    @Test
    void unknownConfigFarFromEveryNameSuggestsNothing() {
        assertThatIllegalArgumentException().isThrownBy(() -> registry.retryConfig("payments"))
                .withMessageNotContaining("Did you mean");
    }

    @Test
    void unknownInstanceSuggestsTheClosestId() {
        Policies.circuitBreaker(registry, "A#m()", "payments", "", null, false);

        assertThatIllegalArgumentException().isThrownBy(() -> registry.circuitBreaker("paymnets"))
                .withMessageStartingWith("[R4C-REG-009] No circuit breaker 'paymnets'")
                .withMessageContaining("Did you mean 'payments'?")
                .withMessageContaining("Known: [payments]");
    }

    @Test
    void overridesApplyOnTopOfTheNamedConfig() {
        RetryPolicy policy = Policies.retry(registry, "A#m()", "", "external", r -> r.maxAttempts(7), false);

        assertThat(policy.config().maxAttempts()).isEqualTo(7);
        assertThat(policy.config().delay()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void sameNameAndConfigShareOneInstance() {
        RetryPolicy first = Policies.retry(registry, "A#m()", "shared", "external", null, false);
        RetryPolicy second = Policies.retry(registry, "B#n()", "shared", "external", null, false);

        assertThat(first).isSameAs(second);
    }

    @Test
    void sameNameWithDifferentConfigFails() {
        Policies.retry(registry, "A#m()", "shared", "external", null, false);

        assertThatIllegalStateException()
                .isThrownBy(() -> Policies.retry(registry, "B#n()", "shared", "external", r -> r.maxAttempts(9), false))
                .withMessageStartingWith("[R4C-REG-008] Retry instance 'shared'");
    }

    @Test
    void invalidNamedConfigFailsAtBuild() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder().retry("bad", r -> r.maxAttempts(0)).build())
                .withMessageStartingWith("[R4C-REG-002] Invalid retry config 'bad': maxAttempts must be at least 1,"
                        + " was 0. It starts from the retry defaults")
                .withCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidDefaultsNameTheDefaults() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder().bulkheadDefaults(b -> b.maxConcurrent(0)).build())
                .withMessageStartingWith("[R4C-REG-002] Invalid bulkhead defaults: maxConcurrent must be at least 1")
                .withMessageContaining("bulkheadDefaults(...)");
    }

    @Test
    void invalidPipelineOrAnnotationValuesNameTheInstance() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Policies.timeout(registry, "A#m()", "", "", t -> t.timeout(Duration.ZERO), false))
                .withMessageStartingWith("[R4C-REG-003] Invalid timeout config for instance 'A#m()': timeout must be"
                        + " positive")
                .withMessageContaining("the values set in its annotation or pipeline step;");
    }

    @Test
    void configValueErrorsHaveACode() {
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimiterConfig.builder().permits(0).build())
                .withMessage("[R4C-REG-001] Invalid rateLimiter config: permits must be at least 1, was 0");
        assertThat(ErrorCode.detail(new IllegalArgumentException("[R4C-REG-001] Invalid retry config: x")))
                .isEqualTo("x");
        assertThat(ErrorCode.detail(new IllegalArgumentException("plain"))).isEqualTo("plain");
    }

    @Test
    void defaultIsReserved() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder().retry("default", r -> { }))
                .withMessageStartingWith("[R4C-REG-006] ResilienceRegistry.Builder.retry(\"default\", ...)");
    }
}
