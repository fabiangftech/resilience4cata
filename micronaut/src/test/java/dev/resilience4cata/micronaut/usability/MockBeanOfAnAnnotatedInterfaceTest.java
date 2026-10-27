package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * {@code @MockBean} of a declarative client that is itself annotated {@code @ResilientBean} (the only place the
 * marker can go on a {@code @Client} interface): the mock inherits the marker from its return type, and
 * {@code @MockBean} is {@code @Refreshable}, so the startup check reports R4C-MN-003.
 */
@MicronautTest
@Property(name = "test.usability", value = "http")
@Disabled("BUG-MN-6: @MockBean of a @ResilientBean @Client interface fails the startup with R4C-MN-003")
class MockBeanOfAnAnnotatedInterfaceTest {

    @Inject
    GreetingClient greetings;

    @MockBean(GreetingClient.class)
    GreetingClient stubGreetings() {
        return () -> "stub";
    }

    @Test
    void theMockIsInjectedAsItIs() {
        assertThat(greetings.greeting()).isEqualTo("stub");
    }
}
