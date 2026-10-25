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

    @Test
    void theBudgetIsOffByDefaultAndOnWithAPercentOrAReserve() {
        assertThat(RetryConfig.DEFAULTS.hasBudget()).isFalse();
        assertThat(RetryConfig.DEFAULTS.budgetWindow()).isEqualTo(Duration.ofSeconds(10));
        assertThat(RetryConfig.builder().budgetPercent(20).build().hasBudget()).isTrue();
        assertThat(RetryConfig.builder().budgetMinRetriesPerSecond(1).build().hasBudget()).isTrue();
        assertThat(RetryConfig.builder().budgetPercent(20).build().toBuilder().budgetPercent(0).build().hasBudget()).isFalse();
    }

    @Test
    void theConstructorOf020HasNoBudget() {
        RetryConfig d = RetryConfig.DEFAULTS;
        assertThat(new RetryConfig(d.enabled(), d.maxAttempts(), d.delay(), d.backoff(), d.multiplier(), d.maxDelay(),
                d.jitter(), d.maxDuration(), d.retryOn(), d.abortOn())).isEqualTo(d);
    }

    @Test
    void rejectsInvalidBudgets() {
        for (double percent : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> RetryConfig.builder().budgetPercent(percent).build())
                    .withMessageStartingWith("[R4C-REG-001]").withMessageContaining("budgetPercent");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> RetryConfig.builder().budgetMinRetriesPerSecond(-1).build())
                .withMessageStartingWith("[R4C-REG-001]").withMessageContaining("budgetMinRetriesPerSecond");
        for (Duration window : new Duration[] {Duration.ofMillis(999), Duration.ofHours(1).plusNanos(1)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> RetryConfig.builder().budgetWindow(window).build())
                    .withMessageStartingWith("[R4C-REG-001]").withMessageContaining("budgetWindow");
        }
        assertThat(RetryConfig.builder().budgetWindow(Duration.ofSeconds(1)).build().budgetWindow()).isEqualTo(Duration.ofSeconds(1));
        assertThat(RetryConfig.builder().budgetWindow(Duration.ofHours(1)).build().budgetWindow()).isEqualTo(Duration.ofHours(1));
    }
}
