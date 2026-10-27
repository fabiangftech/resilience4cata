package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@code @MockBean} of a declarative client that is itself annotated {@code @ResilientBean} (the only place the
 * marker can go on a {@code @Client} interface): the mock inherits the marker from its return type, and
 * {@code @MockBean} is {@code @Refreshable}, which R4C-MN-003 would reject; a mock is injected as it is, not wrapped.
 */
@MicronautTest
@Property(name = "test.usability", value = "http")
class MockBeanOfAnAnnotatedInterfaceTest {

    @Inject
    GreetingClient greetings;

    @MockBean(GreetingClient.class)
    GreetingClient stubGreetings() {
        return () -> "stub";
    }

    @Test
    void theMockIsInjectedAsItIs() {
        assertThat(greetings.getClass().getSimpleName()).doesNotEndWith("$Resilient");
        assertThat(greetings.greeting()).isEqualTo("stub");
    }
}
