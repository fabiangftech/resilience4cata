package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Several {@code @ResilientBean} implementations of one annotated interface, with qualifiers and {@code @Primary}. */
class QualifiedInterfaceBeansTest {

    @Test
    void everyImplementationIsWrappedAndKeepsItsQualifier() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "qualifiers"))) {
            RatesConsumer consumer = context.getBean(RatesConsumer.class);
            Failures failures = context.getBean(Failures.class);

            assertThat(consumer.all).hasSize(2).allSatisfy(r -> assertThat(r.getClass().getSimpleName())
                    .isEqualTo("Rates$Resilient"));

            failures.failNext(2);
            assertThat(consumer.primary.rate("USD")).as("@Primary picks EcbRates").isEqualTo(2);
            assertThat(failures.calls()).isEqualTo(3);

            failures.failNext(2);
            assertThat(consumer.fed.rate("USD")).as("@Named(\"fed\")").isEqualTo(4);
            assertThat(failures.calls()).isEqualTo(6);
        }
    }

    @Test
    void anUnannotatedDefaultMethodRunsOnTheImplementationSoItsCallsAreNotProtected() {
        // Undocumented: the wrapper delegates inverse() to the bean, whose this.rate() is a self-invocation.
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "qualifiers"))) {
            Rates rates = context.getBean(Rates.class);
            context.getBean(Failures.class).failNext(1);
            assertThatThrownBy(() -> rates.inverse("USD")).isInstanceOf(IllegalStateException.class);
            assertThat(rates.inverse("USD")).isEqualTo(0.5);
        }
    }
}
