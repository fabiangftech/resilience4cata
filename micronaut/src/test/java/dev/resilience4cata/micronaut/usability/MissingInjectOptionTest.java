package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A generated subclass compiled without {@code -Ar4c.inject=micronaut} that Micronaut creates anyway, because its
 * mirrored constructor repeats {@code @Inject}: it lacks the class's scope and qualifiers, so its creation fails with
 * R4C-MN-004 naming the option, rather than quietly changing the bean's scope.
 */
class MissingInjectOptionTest {

    @Test
    void aSubclassCompiledWithoutTheOptionIsReported() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "missing-option"))) {
            assertThatThrownBy(() -> context.getBean(Unmarked.class))
                    .hasMessageContaining("[R4C-MN-004]").hasMessageContaining("-Ar4c.inject=micronaut");
        }
    }
}
