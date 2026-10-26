package dev.resilience4cata.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.spring.Runners;
import dev.resilience4cata.spring.broken.TwoFacedClient;
import dev.resilience4cata.spring.fixtures.InventoryClientImpl;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.SpringFactoriesLoader;

/** The failure analyses that Spring Boot prints when the startup fails. */
class FailureAnalyzersTest {

    private final ApplicationContextRunner runner = Runners.runner();

    @Test
    void theAnalyzersAreRegistered() {
        List<FailureAnalyzer> analyzers = SpringFactoriesLoader.forDefaultResourceLocation().load(FailureAnalyzer.class,
                SpringFactoriesLoader.ArgumentResolver.of(org.springframework.beans.factory.BeanFactory.class, null)
                        .and(org.springframework.core.env.Environment.class, null),
                SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> {
                    // Spring Boot's own analyzers need a bean factory; ours accept none
                }));
        assertThat(analyzers).hasAtLeastOneElementOfType(ConfigFailureAnalyzer.class)
                .hasAtLeastOneElementOfType(ResilienceFailureAnalyzer.class)
                .hasAtLeastOneElementOfType(WrappedBeanFailureAnalyzer.class);
    }

    @Test
    void anInvalidConfigurationListsEveryProblemWithItsOrigin() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.name=broken-test").run(context -> {
                    FailureAnalysis analysis = new ConfigFailureAnalyzer().analyze(context.getStartupFailure());
                    assertThat(analysis).isNotNull();
                    assertThat(analysis.getDescription()).startsWith("Invalid resilience4cata configuration (2 problems):")
                            .contains("\n    [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts'"
                                    + " (class path resource [broken-test.yml] - 4:20)");
                    assertThat(analysis.getAction()).contains("https://resilience4cata.dev/#/en/errors");
                });
    }

    @Test
    void anR4cSprErrorSplitsIntoDescriptionAndAction() {
        runner.withBean(TwoFacedClient.class, TwoFacedClient::new).run(context -> {
            FailureAnalysis analysis = new ResilienceFailureAnalyzer().analyze(context.getStartupFailure());
            assertThat(analysis).isNotNull();
            assertThat(analysis.getDescription()).startsWith("[R4C-SPR-003] Bean 'twoFacedClient'")
                    .doesNotContain("Annotate the class");
            assertThat(analysis.getAction()).startsWith("Annotate the class instead of its interfaces");
        });
    }

    @Test
    void injectingTheImplementationOfAWrappedBeanIsExplainedWhicheverIsCreatedFirst() {
        for (ApplicationContextRunner order : List.of(
                runner.withUserConfiguration(InventoryClientImpl.class, NeedsImplementation.class),
                runner.withUserConfiguration(NeedsImplementation.class, InventoryClientImpl.class))) {
            AtomicReference<ConfigurableApplicationContext> source = new AtomicReference<>();
            order.withInitializer(source::set).run(context -> {
                assertThat(context).hasFailed();
                FailureAnalysis analysis = analyzer(source.get()).analyze(context.getStartupFailure());
                assertThat(analysis).isNotNull();
                assertThat(analysis.getDescription()).contains("'inventoryClientImpl'",
                        "InventoryClient$Resilient", "requires dev.resilience4cata.spring.fixtures.InventoryClientImpl");
                assertThat(analysis.getAction()).startsWith("Inject dev.resilience4cata.spring.fixtures.InventoryClient instead");
            });
        }
    }

    @Test
    void otherFailuresAreLeftToSpringBoot() {
        AtomicReference<ConfigurableApplicationContext> source = new AtomicReference<>();
        runner.withUserConfiguration(NeedsImplementation.class).withInitializer(source::set).run(context ->
                assertThat(analyzer(source.get()).analyze(context.getStartupFailure())).isNull());
        runner.withUserConfiguration(InventoryClientImpl.class, NeedsImplementation.class).withInitializer(source::set)
                .withPropertyValues("resilience4cata.spring.exclude=inventoryClientImpl").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(new ResilienceFailureAnalyzer().analyze(new IllegalStateException("other"))).isNull();
                });
    }

    private static WrappedBeanFailureAnalyzer analyzer(ConfigurableApplicationContext context) {
        return new WrappedBeanFailureAnalyzer(context.getBeanFactory(), context.getEnvironment());
    }

    @Configuration(proxyBeanMethods = false)
    static class NeedsImplementation {

        @Bean
        String consumer(InventoryClientImpl inventory) {
            return "uses " + inventory;
        }
    }
}
