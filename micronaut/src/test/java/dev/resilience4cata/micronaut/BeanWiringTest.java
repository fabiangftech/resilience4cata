package dev.resilience4cata.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.micronaut.fixtures.HttpPayments;
import dev.resilience4cata.micronaut.fixtures.Outage;
import dev.resilience4cata.micronaut.fixtures.Payments;
import dev.resilience4cata.testkit.ManualClock;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.NoSuchBeanException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** How beans are protected: {@code @ResilientBean} interface beans and annotated classes from a factory. */
class BeanWiringTest {

    @Test
    void aResilientBeanIsInjectedAsItsGeneratedWrapper() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Payments payments = context.getBean(Payments.class);
            assertThat(payments.getClass().getName()).isEqualTo(Payments.class.getName() + "$Resilient");
            assertThat(context.getBean(Payments.class)).isSameAs(payments);
            assertThatThrownBy(() -> context.getBean(HttpPayments.class)).isInstanceOf(NoSuchBeanException.class);
        }
    }

    @Test
    void theWrapperRetriesOnTheRegistryClock() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Payments payments = context.getBean(Payments.class);
            Outage outage = context.getBean(Outage.class);
            ManualClock clock = context.getBean(ManualClock.class);
            outage.failNext(2);
            assertThat(payments.charge(5)).isEqualTo(5);
            assertThat(outage.calls()).isEqualTo(3);
            assertThat(clock.elapsed()).as("two jittered pauses on the manual clock").isPositive()
                    .isLessThan(Duration.ofSeconds(1));
        }
    }
}
