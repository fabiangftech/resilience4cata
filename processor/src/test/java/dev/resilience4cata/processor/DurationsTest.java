package dev.resilience4cata.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void parsesEveryUnit() {
        assertThat(Durations.parseNanos("0")).isZero();
        assertThat(Durations.parseNanos("7ns")).isEqualTo(7);
        assertThat(Durations.parseNanos("3us")).isEqualTo(3_000);
        assertThat(Durations.parseNanos("250ms")).isEqualTo(Duration.ofMillis(250).toNanos());
        assertThat(Durations.parseNanos("2s")).isEqualTo(Duration.ofSeconds(2).toNanos());
        assertThat(Durations.parseNanos("1m")).isEqualTo(Duration.ofMinutes(1).toNanos());
        assertThat(Durations.parseNanos("1h")).isEqualTo(Duration.ofHours(1).toNanos());
        assertThat(Durations.parseNanos("1d")).isEqualTo(Duration.ofDays(1).toNanos());
        assertThat(Durations.parseNanos("PT1.5S")).isEqualTo(Duration.ofMillis(1500).toNanos());
    }

    @Test
    void rejectsInvalidText() {
        for (String text : new String[] {"", "2", "2 s", "-1s", "1.5s", "2sec", "PT-1S", "99999999999999999999d"}) {
            assertThatIllegalArgumentException().as(text).isThrownBy(() -> Durations.parseNanos(text));
        }
    }
}
