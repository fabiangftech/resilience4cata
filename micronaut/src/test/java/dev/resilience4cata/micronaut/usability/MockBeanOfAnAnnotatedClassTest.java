package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The usual Micronaut test idiom: replace a service with {@code @MockBean}. For an annotated class the mock is a
 * subclass of it (a Mockito mock, or a hand-written stub as here), which is what a test of a caller wants.
 */
@MicronautTest
@Property(name = "test.usability", value = "mock")
class MockBeanOfAnAnnotatedClassTest {

    @Inject
    PriceService prices;

    @MockBean(PriceService.class)
    PriceService stubPrices(Failures failures) {
        return new PriceService(failures) {
            @Override
            public int price(String sku) {
                return 7;
            }
        };
    }

    @Test
    void theMockReplacesTheAnnotatedClass() {
        assertThat(prices.price("sku")).isEqualTo(7);
    }
}
