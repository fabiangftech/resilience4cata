package dev.resilience4cata.spring.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.Runners;
import dev.resilience4cata.spring.usability.fixtures.LazyPayments;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.SpringFactoriesLoader;

/** {@code @Lazy} protected beans and the startup check of {@code instances.<id>} keys. */
class LazyBeansTest {

    private final ApplicationContextRunner runner = Runners.runner().withUserConfiguration(LazyPayments.class)
            .withPropertyValues("resilience4cata.instances.lazy-payments.circuit-breaker.minimum-calls=7");

    @Test
    void aLazyBeansInstanceKeyFailsTheStartupByDefault() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("R4C-CFG-013")
                    .hasMessageContaining("lazy-payments");
        });
    }

    @Test
    void theStartupFailureReportForALazyBeansKeyPointsAtVerifyInstances() {
        runner.run(context -> {
            // what Spring Boot prints: the failure analysis of the registered analyzers
            FailureAnalysis analysis = analyzers().stream().map(a -> a.analyze(context.getStartupFailure()))
                    .filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(analysis.getDescription()).contains("R4C-CFG-013", "lazy-payments");
            assertThat(analysis.getAction()).contains("@Lazy", "resilience4cata.spring.verify-instances=warn");
        });
    }

    private static List<FailureAnalyzer> analyzers() {
        return SpringFactoriesLoader.forDefaultResourceLocation().load(FailureAnalyzer.class,
                SpringFactoriesLoader.ArgumentResolver.of(BeanFactory.class, null).and(Environment.class, null),
                SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> {
                    // Spring Boot's own analyzers need a bean factory; resilience4cata's accept none
                }));
    }

    @Test
    void withVerifyInstancesWarnTheLazyBeanGetsItsInstanceKeyWhenCreated() {
        runner.withPropertyValues("resilience4cata.spring.verify-instances=warn").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LazyPayments.class).pay("1")).isEqualTo("paid 1");
            assertThat(context.getBean(ResilienceRegistry.class).circuitBreaker("lazy-payments").config().minimumCalls())
                    .isEqualTo(7);
        });
    }
}
