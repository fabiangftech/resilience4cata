package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@code @MockBean} of an annotated interface whose implementations are marked {@code @ResilientBean}: the mock
 * replaces them and is injected as it is, without the policies.
 */
@MicronautTest
@Property(name = "test.usability", value = "qualifiers")
class MockBeanOfAResilientBeanTest {

    @Inject
    Rates rates;

    @MockBean(Rates.class)
    Rates stubRates() {
        return currency -> 9;
    }

    @Test
    void theMockIsInjectedUnwrapped() {
        assertThat(rates.rate("USD")).isEqualTo(9);
        assertThat(rates.getClass().getSimpleName()).doesNotContain("$Resilient");
    }
}
