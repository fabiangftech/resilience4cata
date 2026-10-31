package dev.resilience4cata.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.RetrySnapshot;
import dev.resilience4cata.TimeoutSnapshot;
import dev.resilience4cata.config.ConfigException;
import dev.resilience4cata.config.ResilienceConfig;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.spring.fixtures.InventoryClient;
import dev.resilience4cata.spring.fixtures.InventoryClientImpl;
import dev.resilience4cata.spring.fixtures.PaymentsGateway;
import dev.resilience4cata.spring.fixtures.ShippingService;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** The registry built from Spring's Environment, customizers, listeners and the user's own beans. */
class ConfigurationTest {

    private static final String TRACK = "dev.resilience4cata.spring.fixtures.ShippingService#track(java.lang.String)";

    private final ApplicationContextRunner runner = Runners.runner();

    @Test
    void yamlKeysAreKebabCaseWithBracketedInstanceIdsAndLists() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.name=resilience-test")
                .withUserConfiguration(ShippingService.class).run(context -> {
                    ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
                    assertThat(registry.retryConfig("").delay()).isEqualTo(Duration.ofMillis(10));
                    assertThat(registry.retryConfig("").retryOn())
                            .containsExactly(IOException.class, TimeoutException.class);
                    assertThat(registry.circuitBreakerConfig("external").minimumCalls()).isEqualTo(50);
                    assertThat(retry(registry, "shipping").config().maxAttempts()).isEqualTo(5);
                    assertThat(timeout(registry, TRACK).config().timeout()).isEqualTo(Duration.ofSeconds(4));
                });
    }

    @Test
    void propertiesKeysAcceptBracketsListIndexesAndPlaceholders() {
        runner.withPropertyValues("attempts=6", "resilience4cata.defaults.retry.max-attempts=${attempts}",
                "resilience4cata.defaults.retry.abort-on[0]=java.lang.IllegalArgumentException",
                "resilience4cata.defaults.retry.abort-on[1]=java.lang.UnsupportedOperationException",
                "resilience4cata.instances[" + TRACK + "].timeout.value=3s")
                .withUserConfiguration(ShippingService.class).run(context -> {
                    ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
                    assertThat(registry.retryConfig("").maxAttempts()).isEqualTo(6);
                    assertThat(registry.retryConfig("").abortOn())
                            .containsExactly(IllegalArgumentException.class, UnsupportedOperationException.class);
                    assertThat(timeout(registry, TRACK).config().timeout()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    /** As in the other integrations and in the config module: the short prefix works too; r4c.spring.* is skipped. */
    @Test
    void theShortR4cPrefixIsReadAsWell() {
        runner.withSystemProperties("r4c.defaults.retry.max-attempts=7", "r4c.spring.anything=ignored")
                .withPropertyValues("r4c.configs.external.circuit-breaker.minimum-calls=40")
                .withUserConfiguration(ShippingService.class).run(context -> {
                    ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
                    assertThat(registry.retryConfig("").maxAttempts()).isEqualTo(7);
                    assertThat(registry.circuitBreakerConfig("external").minimumCalls()).isEqualTo(40);
                });
    }

    @Test
    void springPrecedenceHoldsTestPropertiesOverSystemPropertiesOverVariablesOverFiles() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getEnvironment().getPropertySources().addBefore(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource("testEnvironment", Map.of(
                                "R4C_DEFAULTS_RETRY_DELAY", "20ms", "R4C_DEFAULTS_RETRY_MAXATTEMPTS", "8",
                                "RESILIENCE4CATA_DEFAULTS_TIMEOUT_VALUE", "7s", "RESILIENCE4CATA_SPRING_WRAP", "false"))))
                .withSystemProperties("resilience4cata.defaults.retry.max-attempts=2",
                        "resilience4cata.defaults.timeout.value=5s")
                .withPropertyValues("spring.config.name=resilience-test", "resilience4cata.defaults.timeout.value=9s")
                .withUserConfiguration(InventoryClientImpl.class, ShippingService.class).run(context -> {
                    ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
                    assertThat(registry.retryConfig("").delay()).as("variable over application.yml")
                            .isEqualTo(Duration.ofMillis(20));
                    assertThat(registry.retryConfig("").maxAttempts()).as("system property over variable")
                            .isEqualTo(2);
                    assertThat(registry.timeoutConfig("").timeout()).as("test property over system property")
                            .isEqualTo(Duration.ofSeconds(9));
                    assertThat(context.getBean(InventoryClient.class)).as("RESILIENCE4CATA_SPRING_* is Spring's")
                            .isExactlyInstanceOf(InventoryClientImpl.class);
                });
    }

    @Test
    void theClasspathResourceRanksBelowEverySpringSource() {
        runner.run(context -> assertThat(context.getBean(ResilienceRegistry.class).bulkheadConfig("").maxConcurrent())
                .isEqualTo(12));
        runner.withPropertyValues("resilience4cata.defaults.bulkhead.max-concurrent=3").run(context ->
                assertThat(context.getBean(ResilienceRegistry.class).bulkheadConfig("").maxConcurrent()).isEqualTo(3));
    }

    @Test
    void adaptiveLimitKeysBindInKebabCase() {
        runner.withPropertyValues("resilience4cata.defaults.adaptive-limit.algorithm=aimd",
                "resilience4cata.configs.backend.adaptive-limit.max-limit=40").run(context -> {
                    ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
                    assertThat(registry.adaptiveLimitConfig("").algorithm())
                            .isEqualTo(dev.resilience4cata.AdaptiveLimitConfig.Algorithm.AIMD);
                    assertThat(registry.adaptiveLimitConfig("backend").maxLimit()).isEqualTo(40);
                });
    }

    @Test
    void anInvalidConfigurationFailsTheStartupWithFileAndLine() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.name=broken-test").run(context -> {
                    assertThat(context).hasFailed();
                    ConfigException failure = cause(context.getStartupFailure(), ConfigException.class);
                    assertThat(failure.problems()).hasSize(2)
                            .anySatisfy(p -> assertThat(p).startsWith("[R4C-CFG-001]")
                                    .contains("'resilience4cata.defaults.retry.max-atempts'"
                                            + " (class path resource [broken-test.yml] - 4:20)")
                                    .contains("maxAttempts"))
                            .anySatisfy(p -> assertThat(p).startsWith("[R4C-CFG-002]")
                                    .contains("class path resource [broken-test.yml] - 5:14"));
                });
    }

    @Test
    void anOverrideOfAnUnknownInstanceFailsTheStartupUnlessToldToWarn() {
        ApplicationContextRunner unknown = runner.withUserConfiguration(ShippingService.class)
                .withPropertyValues("resilience4cata.instances.shiping.retry.max-attempts=2");
        unknown.run(context -> assertThat(cause(context.getStartupFailure(), ConfigException.class).problems())
                .singleElement().asString().startsWith("[R4C-CFG-013]").contains("Did you mean 'shipping'?"));
        unknown.withPropertyValues("resilience4cata.spring.verify-instances=warn")
                .run(context -> assertThat(context).hasNotFailed());
        unknown.withPropertyValues("spring.main.lazy-initialization=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void customizersRunInOrderAndThePropertiesWin() {
        runner.withUserConfiguration(Customizers.class)
                .withPropertyValues("resilience4cata.configs.external.retry.delay=5ms").run(context -> {
                    ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
                    assertThat(registry.retryConfig("external").maxAttempts()).as("the later customizer").isEqualTo(4);
                    assertThat(registry.retryConfig("external").delay()).as("the property").isEqualTo(Duration.ofMillis(5));
                });
    }

    @Test
    void listenerBeansGetTheEventsOfTheirGenericType() {
        runner.withUserConfiguration(Listeners.class, InventoryClientImpl.class).run(context -> {
            InventoryClient inventory = context.getBean(InventoryClient.class);
            Listeners listeners = context.getBean(Listeners.class);
            try {
                failAlways(inventory);
            } catch (IllegalStateException expected) {
                // gave up
            }
            assertThat(listeners.retries).as("a lambda @Bean EventListener<RetryEvent.Retrying>").hasSize(2)
                    .allMatch(RetryEvent.Retrying.class::isInstance);
            assertThat(listeners.gaveUp).as("a class implementing EventListener<RetryEvent.GaveUp>").hasSize(1);
            assertThat(listeners.all).as("a raw listener gets everything").hasSize(3);
            assertThat(listeners.transitions).isEmpty();
        });
    }

    @Test
    void anApplicationRegistryReplacesTheAutoConfiguredOne() {
        ResilienceRegistry own = ResilienceRegistry.builder().build();
        runner.withBean(ResilienceRegistry.class, () -> own).withUserConfiguration(PaymentsGateway.class).run(context -> {
            assertThat(context.getBean(ResilienceRegistry.class)).isSameAs(own);
            assertThat(context.getBean(ResilienceConfig.class)).as("still available to build it").isNotNull();
            assertThat(own.snapshot().circuitBreakers()).extracting(s -> s.id()).containsExactly("payments");
        });
    }

    private static void failAlways(InventoryClient inventory) throws ReflectiveOperationException {
        java.lang.reflect.Field delegate = inventory.getClass().getDeclaredField("delegate");
        delegate.setAccessible(true);
        ((InventoryClientImpl) delegate.get(inventory)).failures.set(100);
        inventory.stock("a");
    }

    static RetrySnapshot retry(ResilienceRegistry registry, String id) {
        return registry.snapshot().retries().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    static TimeoutSnapshot timeout(ResilienceRegistry registry, String id) {
        return registry.snapshot().timeouts().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    static <T extends Throwable> T cause(Throwable failure, Class<T> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        throw new AssertionError("no " + type.getName() + " in " + failure, failure);
    }

    @Configuration(proxyBeanMethods = false)
    static class Customizers {

        @Bean
        @Order(2)
        ResilienceRegistryCustomizer later() {
            return builder -> builder.retryOverlay("external", retry -> retry.maxAttempts(4));
        }

        @Bean
        @Order(1)
        ResilienceRegistryCustomizer earlier() {
            return builder -> builder.retry("external", retry -> retry.maxAttempts(2).delay(Duration.ofSeconds(1)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Listeners {

        final List<ResilienceEvent> retries = new CopyOnWriteArrayList<>();
        final List<ResilienceEvent> gaveUp = new CopyOnWriteArrayList<>();
        final List<ResilienceEvent> all = new CopyOnWriteArrayList<>();
        final List<ResilienceEvent> transitions = new CopyOnWriteArrayList<>();

        @Bean
        EventListener<RetryEvent.Retrying> retrying() {
            return retries::add;
        }

        @Bean
        GaveUpListener gaveUpListener() {
            return new GaveUpListener(gaveUp);
        }

        @Bean
        @SuppressWarnings("rawtypes")
        EventListener everything() {
            return all::add;
        }

        @Bean
        EventListener<CircuitBreakerEvent.StateTransition> transitions() {
            return transitions::add;
        }
    }

    static final class GaveUpListener implements EventListener<RetryEvent.GaveUp> {

        private final List<ResilienceEvent> events;

        GaveUpListener(List<ResilienceEvent> events) {
            this.events = events;
        }

        @Override
        public void onEvent(RetryEvent.GaveUp event) {
            events.add(event);
        }
    }
}
