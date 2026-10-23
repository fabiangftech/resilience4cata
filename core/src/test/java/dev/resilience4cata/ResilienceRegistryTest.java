package dev.resilience4cata;

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
                .withMessageContaining("Unknown retry config 'extrenal'")
                .withMessageContaining("[external]");
    }

    @Test
    void overridesApplyOnTopOfTheNamedConfig() {
        RetryPolicy policy = registry.retryPolicy("A#m()", "", "external", r -> r.maxAttempts(7));

        assertThat(policy.config().maxAttempts()).isEqualTo(7);
        assertThat(policy.config().delay()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void sameNameAndConfigShareOneInstance() {
        RetryPolicy first = registry.retryPolicy("A#m()", "shared", "external", null);
        RetryPolicy second = registry.retryPolicy("B#n()", "shared", "external", null);

        assertThat(first).isSameAs(second);
    }

    @Test
    void sameNameWithDifferentConfigFails() {
        registry.retryPolicy("A#m()", "shared", "external", null);

        assertThatIllegalStateException()
                .isThrownBy(() -> registry.retryPolicy("B#n()", "shared", "external", r -> r.maxAttempts(9)))
                .withMessageContaining("'shared'");
    }

    @Test
    void invalidNamedConfigFailsAtBuild() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder().retry("bad", r -> r.maxAttempts(0)).build())
                .withMessageContaining("Invalid retry config 'bad'");
    }

    @Test
    void defaultIsReserved() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder().retry("default", r -> { }));
    }
}
