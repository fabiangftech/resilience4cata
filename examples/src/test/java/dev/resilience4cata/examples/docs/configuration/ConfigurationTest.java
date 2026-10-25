package dev.resilience4cata.examples.docs.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.TimeoutSnapshot;
import dev.resilience4cata.config.ConfigException;
import dev.resilience4cata.config.ResilienceConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The claims of docs/{en,es}/guides/configuration.md, against the test resource resilience4cata.properties. */
class ConfigurationTest {

    static final String CANCEL = "dev.resilience4cata.examples.docs.configuration.ShippingClient#cancel(java.lang.String)";
    /** Copied verbatim on the page, with the message it produces (no tag lines, so the line numbers match). */
    static final String BROKEN = "dev/resilience4cata/examples/docs/configuration/broken.properties";

    /** Answers at once. */
    static final class HttpShippingClient implements ShippingClient {
        @Override
        public String track(String parcel) {
            return "in transit";
        }

        @Override
        public String cancel(String parcel) {
            return "cancelled";
        }
    }

    static Duration timeoutOf(ResilienceRegistry registry, String id) {
        return registry.snapshot().timeouts().stream().filter(t -> t.instance().equals(id))
                .map(TimeoutSnapshot::config).findFirst().orElseThrow().timeout();
    }

    @Test
    void loadReadsTheFileAndAppliesIt() {
        // tag::configuration-load[]
        ResilienceConfig config = ResilienceConfig.load();   // resilience4cata.properties, R4C_* variables, -Dr4c.* properties
        ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
        ShippingClient shipping = Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
        config.verifyInstances(registry);   // fails if an instances.<id> key matches no instance
        // end::configuration-load[]

        assertThat(shipping.track("p-1")).isEqualTo("in transit");
        assertThat(registry.retryConfig("default").delay()).isEqualTo(Duration.ofMillis(50));
        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(4);
        assertThat(registry.retryConfig("batch").maxAttempts()).isEqualTo(4);   // inherited through extends
        assertThat(registry.timeoutConfig("batch").timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(registry.circuitBreakerConfig("batch").waitInOpen()).isEqualTo(Duration.ofSeconds(10));
        // the annotation (minimumCalls = 20) beats the named config (50); the instance key beats both
        assertThat(registry.circuitBreaker("shipping").config().minimumCalls()).isEqualTo(20);
        assertThat(registry.circuitBreaker("shipping").config().waitInOpen()).isEqualTo(Duration.ofSeconds(10));
        assertThat(registry.circuitBreaker("shipping").config().failureRateThreshold()).isEqualTo(30f);
        // @Timeout("1s") on cancel, but the instance key says 3s
        assertThat(timeoutOf(registry, CANCEL)).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.configNames()).contains("external", "batch");
    }

    @Test
    void sourcesCanBeListedOneByOne() {
        // tag::configuration-builder[]
        ResilienceConfig config = ResilienceConfig.builder()
                .optionalClasspath("resilience4cata.properties")
                .optionalFile(Path.of("/etc/shop/resilience4cata.properties"))   // skipped if missing
                .environment()                                                     // R4C_… variables
                .systemProperties()                                                // -Dr4c.… properties
                .build();
        // end::configuration-builder[]

        assertThat(config.applyTo(ResilienceRegistry.builder()).build().retryConfig("external").maxAttempts())
                .isEqualTo(4);
    }

    @Test
    void environmentBeatsFilesAndSystemPropertiesBeatEverything() {
        // tag::configuration-sources[]
        Properties system = new Properties();
        system.setProperty("r4c.configs.external.retry.maxAttempts", "7");   // the r4c. prefix is required here

        ResilienceConfig config = ResilienceConfig.builder()
                .classpath("resilience4cata.properties")                      // maxAttempts=4
                .environment(Map.of(
                        "R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS", "6",
                        "R4C_INSTANCES_SHIPPING_CIRCUIT_BREAKER_WAIT_IN_OPEN", "5s"))
                .systemProperties(system)
                .build();
        ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
        // registry.retryConfig("external").maxAttempts() == 7: system properties > environment > files
        // end::configuration-sources[]

        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(7);
        Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
        assertThat(registry.circuitBreaker("shipping").config().waitInOpen()).isEqualTo(Duration.ofSeconds(5));

        ResilienceConfig withoutSystem = ResilienceConfig.builder().classpath("resilience4cata.properties")
                .environment(Map.of("R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS", "6")).build();
        assertThat(withoutSystem.applyTo(ResilienceRegistry.builder()).build().retryConfig("external").maxAttempts())
                .isEqualTo(6);

        Properties unprefixed = new Properties();
        unprefixed.setProperty("configs.external.retry.maxAttempts", "9");
        ResilienceConfig ignored = ResilienceConfig.builder().classpath("resilience4cata.properties")
                .systemProperties(unprefixed).build();
        assertThat(ignored.applyTo(ResilienceRegistry.builder()).build().retryConfig("external").maxAttempts())
                .as("system properties without the prefix are ignored").isEqualTo(4);
    }

    @Test
    void aProfileOverridesTheKeysItRepeats() {
        // tag::configuration-profiles[]
        ResilienceConfig config = ResilienceConfig.builder()
                .classpath("resilience4cata.properties")
                .profiles("test")                         // or profiles.active=test, or R4C_PROFILES_ACTIVE=test
                .build();
        // config.activeProfiles() == [test]; configs.external.retry.maxAttempts is now 1
        // end::configuration-profiles[]

        assertThat(config.activeProfiles()).containsExactly("test");
        assertThat(config.applyTo(ResilienceRegistry.builder()).build().retryConfig("external").maxAttempts())
                .isEqualTo(1);

        ResilienceConfig fromEnvironment = ResilienceConfig.builder().classpath("resilience4cata.properties")
                .environment(Map.of("R4C_PROFILES_ACTIVE", "test")).build();
        assertThat(fromEnvironment.activeProfiles()).containsExactly("test");
    }

    @Test
    void environmentWordsMayBeRunTogetherAndHyphensBecomeUnderscores() {
        ResilienceConfig config = ResilienceConfig.builder()
                .environment(Map.of(
                        "R4C_INSTANCES_SHIPPING_CIRCUITBREAKER_WAITINOPEN", "4s",
                        "R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE", "10s",
                        "RESILIENCE4CATA_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS", "6"))
                .build();
        assertThat(config.configNames()).containsExactly("slow-external");
        assertThat(config.instanceIds()).containsExactlyInAnyOrder("shipping", "payments-api");
        assertThat(config.toRegistry().timeoutConfig("slow-external").timeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theLaterOfTwoActiveProfilesWins() {
        Properties file = new Properties();
        file.setProperty("profiles.a.defaults.retry.maxAttempts", "2");
        file.setProperty("profiles.b.defaults.retry.maxAttempts", "5");
        file.setProperty("profiles.active", "a,b");
        assertThat(ResilienceConfig.builder().properties(file).build().toRegistry().retryConfig("default").maxAttempts())
                .isEqualTo(5);
        assertThat(ResilienceConfig.builder().properties(file).profiles("b", "a").build().toRegistry()
                .retryConfig("default").maxAttempts()).isEqualTo(2);
    }

    @Test
    void aProfileKeyBeatsAKeyFromAStrongerSource() {
        Properties file = new Properties();
        file.setProperty("profiles.test.defaults.retry.maxAttempts", "2");
        ResilienceConfig config = ResilienceConfig.builder().properties(file)
                .environment(Map.of("R4C_DEFAULTS_RETRY_MAXATTEMPTS", "7")).profiles("test").build();
        assertThat(config.toRegistry().retryConfig("default").maxAttempts()).isEqualTo(2);
    }

    @Test
    void codeAndExternalConfigsWithTheSameNameMerge() {
        // tag::configuration-merge[]
        ResilienceRegistry registry = ResilienceConfig.load()
                .applyTo(ResilienceRegistry.builder()
                        .retry("external", r -> r.maxAttempts(9).maxDuration(Duration.ofSeconds(5))))
                .build();
        // registry.retryConfig("external"): maxAttempts 4 (the file wins), maxDuration 5s (only the code sets it)
        // end::configuration-merge[]

        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(4);
        assertThat(registry.retryConfig("external").maxDuration()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void programmaticCallsAfterApplyToWin() {
        ResilienceRegistry registry = ResilienceConfig.load()
                .applyTo(ResilienceRegistry.builder())
                .circuitBreakerInstance("shipping", cb -> cb.failureRateThreshold(40))
                .build();
        Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
        assertThat(registry.circuitBreaker("shipping").config().failureRateThreshold()).isEqualTo(40f);

        ResilienceRegistry replaced = ResilienceConfig.load()
                .applyTo(ResilienceRegistry.builder())
                .retryDefaults(r -> r.maxAttempts(5))
                .build();
        assertThat(replaced.retryConfig("default").maxAttempts()).isEqualTo(5);
        assertThat(replaced.retryConfig("default").delay()).as("the external defaults of retry are replaced")
                .isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        // tag::configuration-errors[]
        assertThatThrownBy(() -> ResilienceConfig.builder().classpath(BROKEN).build())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("[R4C-CFG-001]")
                .hasMessageContaining("Did you mean 'configs.external.circuit-breaker.wait-in-open'?")
                .hasMessageContaining("[R4C-CFG-002]")
                .hasMessageContaining("[R4C-CFG-007]");
        // end::configuration-errors[]
        try {
            ResilienceConfig.builder().classpath(BROKEN).build();
        } catch (ConfigException e) {
            assertThat(e.problems()).hasSize(3);
            assertThat(e.getMessage()).contains("broken.properties, line 1)", "broken.properties, line 2)",
                    "broken.properties, line 3)");
        }
    }

    @Test
    void describeListsEveryValueAndItsOrigin() {
        ResilienceConfig config = ResilienceConfig.load();
        String description = config.describe();
        assertThat(description).contains("active profiles: []",
                "configs.batch.retry.maxAttempts = 4  <- 'configs.external.retry.maxAttempts' (classpath:resilience4cata.properties,",
                "instances.shipping.circuitBreaker.failureRateThreshold = 30  <- 'instances.shipping.circuitBreaker.failureRateThreshold'");
    }

    @Test
    void verifyInstancesCatchesATypoInAnInstanceId() {
        Properties typo = new Properties();
        typo.setProperty("instances.shiping.circuitBreaker.failureRateThreshold", "30");
        ResilienceConfig config = ResilienceConfig.builder().properties("ops.properties", typo).build();
        ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()
                .retry("external", r -> r.maxAttempts(4))
                .circuitBreaker("external", cb -> cb.waitInOpen(Duration.ofSeconds(10)))).build();
        Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);

        assertThatThrownBy(() -> config.verifyInstances(registry)).isInstanceOf(ConfigException.class)
                .hasMessageContaining("[R4C-CFG-013]").hasMessageContaining("shipping");
    }
}
