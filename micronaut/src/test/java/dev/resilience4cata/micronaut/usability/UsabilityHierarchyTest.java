package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Two annotated classes of one hierarchy, each a bean of its own. */
class UsabilityHierarchyTest {

    @Test
    void anAnnotatedSubclassIsStillItsOwnBean() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "hierarchy"))) {
            assertThat(context.getBeansOfType(Orders.class)).extracting(o -> o.getClass().getSimpleName())
                    .containsExactlyInAnyOrder("Orders$Resilient", "PriorityOrders$Resilient");
            Orders priority = context.getBean(Orders.class, Qualifiers.byName("priority"));
            assertThat(priority.getClass().getSimpleName()).isEqualTo("PriorityOrders$Resilient");
            Failures failures = context.getBean(Failures.class);
            failures.failNext(3);
            assertThat(priority.find("1")).isEqualTo("priority order 1");
            assertThat(failures.calls()).as("maxAttempts = 4 of the subclass").isEqualTo(4);
        }
    }

    @Test
    void theBaseClassIsStillProtected() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "hierarchy"))) {
            Orders orders = context.getBean(Orders.class);
            assertThat(orders.getClass().getSimpleName()).isEqualTo("Orders$Resilient");
            context.getBean(Failures.class).failNext(2);
            assertThat(orders.find("1")).isEqualTo("order 1");
        }
    }
}
