package dev.resilience4cata.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DurationsTest {

    @Test
    void parsesEveryUnit() {
        assertThat(Durations.parseNanos("0")).isZero();
        assertThat(Durations.parseNanos("7ns")).isEqualTo(7);
        assertThat(Durations.parseNanos("7us")).isEqualTo(7_000);
        assertThat(Durations.parse("250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(Durations.parse("2s")).isEqualTo(Duration.ofSeconds(2));
        assertThat(Durations.parse("3m")).isEqualTo(Duration.ofMinutes(3));
        assertThat(Durations.parse("4h")).isEqualTo(Duration.ofHours(4));
        assertThat(Durations.parse("5d")).isEqualTo(Duration.ofDays(5));
        assertThat(Durations.parse("0s")).isEqualTo(Duration.ZERO);
    }

    @Test
    void parsesIso8601() {
        assertThat(Durations.parse("PT2S")).isEqualTo(Duration.ofSeconds(2));
        assertThat(Durations.parse("pt0.5s")).isEqualTo(Duration.ofMillis(500));
        assertThat(Durations.parse("P1DT1H")).isEqualTo(Duration.ofHours(25));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2", "2 s", "2S", "-1s", "1.5s", "s", "2sec", "ms250"})
    void rejectsMalformed(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Durations.parseNanos(text))
                .withMessage("expected " + Durations.GRAMMAR);
    }

    @Test
    void rejectsNegativeAndInvalidIso() {
        assertThatIllegalArgumentException().isThrownBy(() -> Durations.parseNanos("PT-1S"))
                .withMessage("must not be negative");
        assertThatIllegalArgumentException().isThrownBy(() -> Durations.parseNanos("PTXS"))
                .withMessage("invalid ISO-8601 duration");
    }

    @Test
    void rejectsOverflow() {
        assertThatIllegalArgumentException().isThrownBy(() -> Durations.parseNanos("999999999999d"))
                .withMessage("too large");
        assertThatIllegalArgumentException().isThrownBy(() -> Durations.parseNanos("99999999999999999999ns"))
                .withMessage("too large");
        assertThatIllegalArgumentException().isThrownBy(() -> Durations.parseNanos("PT999999999999999H"))
                .withMessage("invalid ISO-8601 duration");
    }
}
