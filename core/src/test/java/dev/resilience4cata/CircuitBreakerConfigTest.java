package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.resilience4cata.CircuitBreakerConfig.WindowType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CircuitBreakerConfigTest {

    @Test
    void aTimeWindowHasADurationAndACountWindowASize() {
        assertThat(CircuitBreakerConfig.DEFAULTS.windowSize()).isEqualTo(100);
        assertThat(CircuitBreakerConfig.DEFAULTS.windowDuration()).isEqualTo(Duration.ofSeconds(60));
        CircuitBreakerConfig time = CircuitBreakerConfig.builder().windowType(WindowType.TIME)
                .windowDuration(Duration.ofMinutes(2)).build();
        assertThat(time.windowDuration()).isEqualTo(Duration.ofSeconds(120));
        assertThat(time.toString()).contains("windowSize=100, windowDuration=PT2M,");
    }

    @Test
    void ratesAreDoubles() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder().failureRateThreshold(33.333333333)
                .slowCallRateThreshold(0.1).build();
        assertThat(config.failureRateThreshold()).isEqualTo(33.333333333);
        assertThat(config.slowCallRateThreshold()).isEqualTo(0.1);
        assertThat(config).isNotEqualTo(config.toBuilder().failureRateThreshold(33.333333334).build());
    }

    @Test
    void eachWindowTypeUsesOnlyItsOwnAttribute() {
        // Defaults are shared by count and time windows, so both may be set; each window uses its own.
        CircuitBreakerConfig both = CircuitBreakerConfig.builder().windowSize(20).windowDuration(Duration.ofSeconds(30))
                .build();
        CircuitBreakerConfig time = both.toBuilder().windowType(WindowType.TIME).build();
        assertThat(time.windowSize()).isEqualTo(20);
        assertThat(time.windowDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theDurationIsAWholeNumberOfSeconds() {
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(999),
                Duration.ofSeconds(-1), Duration.ofSeconds(Integer.MAX_VALUE + 1L)}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CircuitBreakerConfig.builder().windowType(WindowType.TIME)
                            .windowDuration(invalid).build())
                    .withMessageContaining("windowDuration must be a whole number of seconds, at least 1s, was");
        }
    }
}
