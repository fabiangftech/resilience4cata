package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryConfigTest {

    @Test
    void builderStartsFromDefaults() {
        assertThat(RetryConfig.builder().build()).isEqualTo(RetryConfig.DEFAULTS);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> RetryConfig.builder().maxAttempts(0).build())
                .withMessageContaining("maxAttempts");
        assertThatIllegalArgumentException().isThrownBy(() -> RetryConfig.builder().multiplier(0.5).build())
                .withMessageContaining("multiplier");
        assertThatIllegalArgumentException().isThrownBy(() -> RetryConfig.builder().delay(Duration.ofSeconds(-1)).build())
                .withMessageContaining("delay");
        assertThatIllegalArgumentException().isThrownBy(() -> RetryConfig.builder()
                        .delay(Duration.ofSeconds(5)).maxDelay(Duration.ofSeconds(1)).build())
                .withMessageContaining("maxDelay");
    }
}
