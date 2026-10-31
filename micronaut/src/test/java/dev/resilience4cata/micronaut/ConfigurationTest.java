package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.TimeoutSnapshot;
import dev.resilience4cata.config.ConfigException;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.micronaut.fixtures.Payments;
import dev.resilience4cata.micronaut.fixtures.Shipping;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code resilience4cata.*} in Micronaut's configuration reaches the registry, in Micronaut's precedence. */
class ConfigurationTest {

    @Test
    void yamlConfiguresTheRegistry() {
        try (ApplicationContext context = ApplicationContext.run("config")) {
            context.getBean(Payments.class).charge(1);
            context.getBean(Shipping.class).track("p1");
            ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);

            RetrySnapshot payments = only(registry.snapshot().retries(), "payments");
            assertThat(payments.config().maxAttempts()).as("instances.payments, over the annotation's 3").isEqualTo(5);
            assertThat(payments.config().retryOn()).as("a YAML list").containsExactly(IOException.class, IllegalStateException.class);
            TimeoutSnapshot track = registry.snapshot().timeouts().stream()
                    .filter(t -> t.id().endsWith("Shipping#track(java.lang.String)")).findFirst().orElseThrow();
            assertThat(track.config().timeout()).as("a quoted derived instance id").isEqualTo(Duration.ofSeconds(4));
            assertThat(context.getBean(ResilienceConfig.class).configNames()).containsExactly("external");
        }
    }

    @Test
    void contextPropertiesWinOverYamlAndTheIntegrationSwitchesAreNotPolicyKeys() {
        try (ApplicationContext context = ApplicationContext.run(
                Map.of("resilience4cata.instances.payments.retry.max-attempts", "7"), "config")) {
            context.getBean(Payments.class).charge(1);
            RetrySnapshot payments = only(context.getBean(ResilienceRegistry.class).snapshot().retries(), "payments");
            assertThat(payments.config().maxAttempts()).isEqualTo(7);
        }
    }

    @Test
    void adaptiveLimitKeysBind() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "resilience4cata.defaults.adaptive-limit.max-limit", "30",
                "resilience4cata.configs.backend.adaptive-limit.window", "2s"), "config")) {
            ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
            assertThat(registry.adaptiveLimitConfig("").maxLimit()).isEqualTo(30);
            assertThat(registry.adaptiveLimitConfig("backend").window()).isEqualTo(Duration.ofSeconds(2));
        }
    }

    @Test
    void environmentVariablesUseTheConfigGrammarAtTheirMicronautPosition() {
        try (ApplicationContext context = ApplicationContext.run(
                Map.of("resilience4cata.instances.payments.retry.delay", "30ms"), "config")) {
            Environment environment = context.getEnvironment();
            ResilienceConfig config = MicronautEnvironmentConfig.read(environment, Map.of(
                    "R4C_INSTANCES_payments_RETRY_MAX_ATTEMPTS", "9", "R4C_INSTANCES_payments_RETRY_DELAY", "50ms",
                    "R4C_MICRONAUT_VERIFY_INSTANCES", "fail", "PATH", "/bin"), getClass().getClassLoader());
            ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
            Resilient.of(Payments.class, cents -> cents, registry).charge(1);
            RetrySnapshot payments = only(registry.snapshot().retries(), "payments");
            assertThat(payments.config().maxAttempts()).as("the variable, over application-config.yml").isEqualTo(9);
            assertThat(payments.config().delay()).as("the context property, over the variable")
                    .isEqualTo(Duration.ofMillis(30));
        }
    }

    @Test
    void anInvalidKeyStopsTheStartupNamingTheFile() {
        assertThatThrownBy(() -> ApplicationContext.run("broken"))
                .satisfies(e -> assertThat(rootConfigException(e).getMessage())
                        .contains("R4C-CFG-001", "'resilience4cata.defaults.retry.max-atempts' (application-broken.yml)",
                                "Did you mean", "R4C-CFG-002", "soon"));
    }

    @Test
    void verifyInstancesFailStopsTheStartupForAnUnknownInstance() {
        assertThatThrownBy(() -> ApplicationContext.run(Map.of(
                "resilience4cata.instances.nobody.retry.max-attempts", "2",
                "resilience4cata.micronaut.verify-instances", "fail")))
                .satisfies(e -> assertThat(rootConfigException(e).getMessage()).contains("R4C-CFG-013", "nobody"));
    }

    @Test
    void verifyInstancesFailsByDefaultWhenSingletonsAreEager() {
        assertThatThrownBy(() -> ApplicationContext.builder()
                .properties(Map.of("resilience4cata.instances.nobody.retry.max-attempts", "2"))
                .eagerInitSingletons(true).start())
                .satisfies(e -> assertThat(rootConfigException(e).getMessage()).contains("R4C-CFG-013", "nobody"));
        try (ApplicationContext context = ApplicationContext.builder()
                .properties(Map.of("resilience4cata.instances.payments.retry.max-attempts", "2"))
                .eagerInitSingletons(true).start()) {
            assertThat(context.isRunning()).as("eager singletons created the payments instance").isTrue();
        }
    }

    @Test
    void verifyInstancesWarnsByDefault() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "resilience4cata.instances.nobody.retry.max-attempts", "2"))) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    private static ConfigException rootConfigException(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof ConfigException config) {
                return config;
            }
        }
        throw new AssertionError("no ConfigException in the causes of " + thrown, thrown);
    }

    private static RetrySnapshot only(List<RetrySnapshot> retries, String instance) {
        return retries.stream().filter(r -> r.id().equals(instance)).findFirst().orElseThrow();
    }
}
