package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An {@code @ResilientBean} whose class is annotated too. */
class BothStylesTest {

    @Test
    void theInterfaceRetryRunsOnceNotOncePerStyle() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "both-styles"))) {
            Inventory inventory = context.getBean(Inventory.class);
            Failures failures = context.getBean(Failures.class);
            failures.failNext(100);
            assertThatThrownBy(() -> inventory.count("a")).isInstanceOf(IllegalStateException.class);
            assertThat(failures.calls()).as("@Retry(maxAttempts = 3) of the interface").isEqualTo(3);
        }
    }
}
