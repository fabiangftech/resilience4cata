package dev.resilience4cata.config;

import dev.resilience4cata.generated.Policies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetryConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The retry budget's keys: {@code budgetPercent}, {@code budgetMinRetriesPerSecond}, {@code budgetWindow}. */
class RetryBudgetConfigTest {

    private static RetryConfig retry(ResilienceRegistry registry, String instanceId, String name, String config) {
        return Policies.retry(registry, instanceId, name, config, null, false).config();
    }

    @Test
    void defaultsNamedConfigsAndInstancesAllSetTheBudget() {
        ResilienceRegistry registry = ResilienceConfig.builder().text("test.properties", """
                defaults.retry.budgetPercent=10
                configs.external.retry.budgetMinRetriesPerSecond=3
                configs.external.retry.budgetWindow=30s
                instances.payments.retry.budgetPercent=25.5
                """).build().toRegistry();

        RetryConfig plain = retry(registry, "com.acme.Api#get()", "", "");
        assertThat(plain.budgetPercent()).isEqualTo(10);
        assertThat(plain.hasBudget()).isTrue();
        RetryConfig external = retry(registry, "com.acme.Api#put()", "", "external");
        assertThat(external.budgetPercent()).isEqualTo(10);
        assertThat(external.budgetMinRetriesPerSecond()).isEqualTo(3);
        assertThat(external.budgetWindow()).isEqualTo(Duration.ofSeconds(30));
        assertThat(retry(registry, "com.acme.Payments#pay()", "payments", "").budgetPercent()).isEqualTo(25.5);
    }

    @Test
    void environmentVariablesSetTheBudget() {
        ResilienceRegistry registry = ResilienceConfig.builder().environment(Map.of(
                "R4C_DEFAULTS_RETRY_BUDGETPERCENT", "20",
                "R4C_DEFAULTS_RETRY_BUDGETMINRETRIESPERSECOND", "5",
                "R4C_DEFAULTS_RETRY_BUDGETWINDOW", "1m")).build().toRegistry();

        assertThat(registry.retryConfig("default")).satisfies(c -> {
            assertThat(c.budgetPercent()).isEqualTo(20);
            assertThat(c.budgetMinRetriesPerSecond()).isEqualTo(5);
            assertThat(c.budgetWindow()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void invalidBudgetsAreReportedWithTheirKeys() {
        ResilienceConfig.Builder window = ResilienceConfig.builder().text("test.properties", """
                defaults.retry.budgetWindow=100ms
                """);
        assertThatThrownBy(window::build)
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("[R4C-CFG-006] invalid retry defaults: budgetWindow must be between 1s and 1h")
                .hasMessageContaining("'defaults.retry.budgetWindow' (test.properties, line 1)");
        ResilienceConfig.Builder percent = ResilienceConfig.builder().text("test.properties", """
                instances.payments.retry.budgetPercent=-1
                """);
        assertThatThrownBy(percent::build)
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("[R4C-CFG-006] invalid retry instance 'payments': budgetPercent must be a finite number >= 0")
                .hasMessageContaining("'instances.payments.retry.budgetPercent' (test.properties, line 1)");
    }
}
