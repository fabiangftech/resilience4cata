package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.config.ConfigException;
import dev.resilience4cata.config.ResilienceConfig;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * External configuration retuning annotated methods without recompiling them: per-instance
 * overrides ({@code instances.<id>.*}) beat the annotation values, and a named config defined both in
 * code and externally takes the external values. See {@code config-e2e/resilience4cata.properties}.
 */
class ConfigEndToEndTest {

    private static final String RESOURCE = "config-e2e/resilience4cata.properties";

    private static ResilienceRegistry registry(String... profiles) {
        return ResilienceConfig.builder().classpath(RESOURCE).profiles(profiles).build()
                .applyTo(ResilienceRegistry.builder()
                        .retry("external", r -> r.maxAttempts(9).delay(Duration.ofMillis(1))))
                .build();
    }

    /** Each method fails {@code failures} times, then succeeds. */
    private static final class Fake implements ConfigTunedClient {
        final Flaky flaky;

        Fake(int failures) {
            flaky = new Flaky(failures);
        }

        @Override
        public String quote(String sku) {
            flaky.call();
            return "quote " + sku;
        }

        @Override
        public String order(int id) {
            flaky.call();
            return "order " + id;
        }

        @Override
        public String ship(int id) {
            flaky.call();
            return "ship " + id;
        }

        @Override
        public String untouched() {
            flaky.call();
            return "untouched";
        }
    }

    @Test
    void instanceOverrideRaisesTheAnnotatedMaxAttempts() {
        Fake fake = new Fake(4);
        ConfigTunedClient client = Resilient.of(ConfigTunedClient.class, fake, registry());

        assertThat(client.quote("sku-1")).isEqualTo("quote sku-1"); // @Retry(maxAttempts = 2) alone would fail
        assertThat(fake.flaky.calls()).isEqualTo(5);
    }

    @Test
    void methodsWithoutOverridesKeepTheirAnnotation() {
        Fake fake = new Fake(10);
        ConfigTunedClient client = Resilient.of(ConfigTunedClient.class, fake, registry());

        assertThatIllegalStateException().isThrownBy(client::untouched);
        assertThat(fake.flaky.calls()).isEqualTo(3);
    }

    @Test
    void externalValuesOfANamedConfigWinOverTheCode() {
        Fake fake = new Fake(10);
        ResilienceRegistry registry = registry();
        ConfigTunedClient client = Resilient.of(ConfigTunedClient.class, fake, registry);

        assertThatIllegalStateException().isThrownBy(() -> client.order(1));
        assertThat(fake.flaky.calls()).isEqualTo(4); // 9 in code, 4 externally
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(4);
    }

    @Test
    void instanceOverrideRetunesANamedCircuitBreaker() {
        Fake fake = new Fake(10);
        ResilienceRegistry registry = registry();
        ConfigTunedClient client = Resilient.of(ConfigTunedClient.class, fake, registry);

        assertThatIllegalStateException().isThrownBy(() -> client.ship(1));
        assertThatIllegalStateException().isThrownBy(() -> client.ship(2));
        assertThatThrownBy(() -> client.ship(3)).isInstanceOf(CallNotPermittedException.class);
        assertThat(registry.circuitBreaker("config-shipping").config().minimumCalls()).isEqualTo(2);
    }

    @Test
    void aProfileCanDisableTheRetryOfOneMethod() {
        Fake fake = new Fake(1);
        ConfigTunedClient client = Resilient.of(ConfigTunedClient.class, fake, registry("no-retry"));

        assertThatIllegalStateException().isThrownBy(() -> client.quote("sku-1"));
        assertThat(fake.flaky.calls()).isEqualTo(1);
    }

    @Test
    void verifyInstancesAfterWrappingCatchesTypos() {
        ResilienceConfig config = ResilienceConfig.builder().classpath(RESOURCE).build();
        ResilienceRegistry registry = config.toRegistry();
        Resilient.of(ConfigTunedClient.class, new Fake(0), registry);
        assertThatCode(() -> config.verifyInstances(registry)).doesNotThrowAnyException();

        Properties typo = new Properties();
        typo.setProperty("r4c.configs.external.retry.maxAttempts", "2");
        typo.setProperty("r4c.instances.\"dev.resilience4cata.examples.ConfigTunedClient#quote(String)\".retry"
                + ".maxAttempts", "5");
        ResilienceConfig wrong = ResilienceConfig.builder().systemProperties(typo).build();
        ResilienceRegistry other = wrong.toRegistry();
        Resilient.of(ConfigTunedClient.class, new Fake(0), other);
        assertThatThrownBy(() -> wrong.verifyInstances(other)).isInstanceOf(ConfigException.class)
                .hasMessageContaining("[R4C-CFG-013]")
                .hasMessageContaining("Known retry instances: [config-orders,"
                        + " dev.resilience4cata.examples.ConfigTunedClient#quote(java.lang.String),"
                        + " dev.resilience4cata.examples.ConfigTunedClient#untouched()]");
    }
}
