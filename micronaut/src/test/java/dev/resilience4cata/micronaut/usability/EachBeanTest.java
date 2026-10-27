package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Policies on one bean per configured entry: an annotated {@code @EachBean} of an {@code @EachProperty} class. */
class EachBeanTest {

    @Test
    void everyConfiguredBackendIsAProtectedBean() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "each-bean",
                "usability-backends.a.url", "http://a", "usability-backends.b.url", "http://b"))) {
            assertThat(context.getBeansOfType(Backend.class)).hasSize(2)
                    .allMatch(b -> b.getClass().getSimpleName().equals("Backend$Resilient"));

            Backend b = context.getBean(Backend.class, Qualifiers.byName("b"));
            b.failNext(2);
            assertThat(b.call("/orders")).isEqualTo("http://b/orders");
            assertThat(b.calls()).as("retried").isEqualTo(3);
        }
    }
}
