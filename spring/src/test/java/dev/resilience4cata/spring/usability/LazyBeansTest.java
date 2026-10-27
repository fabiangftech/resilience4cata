package dev.resilience4cata.spring.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.Runners;
import dev.resilience4cata.spring.usability.fixtures.LazyPayments;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

    @Disabled("BUG-SPR-6: the R4C-CFG-013 startup failure says nothing of lazy beans or verify-instances=warn")
    @Test
    void theStartupFailureForALazyBeansKeyPointsAtVerifyInstances() {
        runner.run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("resilience4cata.spring.verify-instances"));
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
