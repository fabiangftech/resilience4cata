package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Every duration of a {@code *Config} must fit in a {@code long} of nanoseconds, which the policies convert it to.
 * A longer one is rejected by {@code build()} with R4C-REG-001, not by an {@code ArithmeticException} when the policy
 * is created.
 */
class ConfigDurationLimitsTest {

    private static final Duration LONGEST = Duration.ofNanos(Long.MAX_VALUE);
    private static final Duration TOO_LONG = LONGEST.plusNanos(1);

    /** Builds a config with the given value in one duration attribute; the key is the attribute's name. */
    private static final Map<String, Function<Duration, Object>> DURATIONS = Map.ofEntries(
            Map.entry("retry delay", d -> RetryConfig.builder().delay(d).maxDelay(d).build()),
            Map.entry("retry maxDelay", d -> RetryConfig.builder().maxDelay(d).build()),
            Map.entry("retry maxDuration", d -> RetryConfig.builder().maxDuration(d).build()),
            Map.entry("circuitBreaker slowCallDuration", d -> CircuitBreakerConfig.builder().slowCallDuration(d).build()),
            Map.entry("circuitBreaker waitInOpen", d -> CircuitBreakerConfig.builder().waitInOpen(d).build()),
            Map.entry("circuitBreaker maxWaitInHalfOpen", d -> CircuitBreakerConfig.builder().maxWaitInHalfOpen(d).build()),
            Map.entry("hedge delay", d -> HedgeConfig.builder().delay(d).build()),
            Map.entry("rateLimiter period", d -> RateLimiterConfig.builder().period(d).build()),
            Map.entry("rateLimiter maxWait", d -> RateLimiterConfig.builder().maxWait(d).build()),
            Map.entry("bulkhead maxWait", d -> BulkheadConfig.builder().maxWait(d).build()),
            Map.entry("timeout timeout", d -> TimeoutConfig.builder().timeout(d).build()));

    @Test
    void aDurationLongerThanTheLongestNanosecondCountIsRejectedWithItsName() {
        DURATIONS.forEach((label, config) -> {
            String attribute = label.substring(label.indexOf(' ') + 1);
            String kind = label.substring(0, label.indexOf(' '));
            assertThatIllegalArgumentException().as(label).isThrownBy(() -> config.apply(TOO_LONG))
                    .withMessageStartingWith("[R4C-REG-001] Invalid " + kind + " config: " + attribute
                            + " must be at most " + LONGEST + " (about 292 years), was " + TOO_LONG);
        });
    }

    @Test
    void theLongestNanosecondCountIsAccepted() {
        DURATIONS.forEach((label, config) -> assertThatCode(() -> config.apply(LONGEST)).as(label)
                .doesNotThrowAnyException());
    }

    @Test
    void aRegistryReportsTheTooLongDurationOfAPipelineStep() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Pipeline.builder(registry, "long", String.class)
                        .timeout(t -> t.timeout(Duration.ofDays(200_000))).build())
                .satisfies(e -> assertThat(e.getMessage()).contains("timeout must be at most", "was PT4800000H"));
    }
}
