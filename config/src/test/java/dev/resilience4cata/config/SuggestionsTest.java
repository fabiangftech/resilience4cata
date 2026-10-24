package dev.resilience4cata.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestionsTest {

    @Test
    void closestIgnoresCaseAndSeparators() {
        List<String> names = List.of("waitInOpen", "windowSize", "windowType");
        assertThat(Suggestions.closest("wait-in-opne", names)).isEqualTo("waitInOpen");
        assertThat(Suggestions.closest("WINDOWSIZ", names)).isEqualTo("windowSize");
        assertThat(Suggestions.closest("completelyDifferent", names)).isNull();
    }

    @Test
    void distanceCountsTranspositionsAsOne() {
        assertThat(Suggestions.distance("ab", "ba")).isEqualTo(1);
        assertThat(Suggestions.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(Suggestions.distance("", "abc")).isEqualTo(3);
    }

    @Test
    void spellings() {
        assertThat(Suggestions.kebab("maxWaitInHalfOpen")).isEqualTo("max-wait-in-half-open");
        assertThat(Suggestions.environmentName("configs.slow-external.retry.maxAttempts"))
                .isEqualTo("R4C_CONFIGS_SLOW_EXTERNAL_RETRY_MAXATTEMPTS");
    }
}
