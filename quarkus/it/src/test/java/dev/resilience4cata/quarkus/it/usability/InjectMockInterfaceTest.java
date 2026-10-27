package dev.resilience4cata.quarkus.it.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.resilience4cata.quarkus.guide.Checkout;
import dev.resilience4cata.quarkus.guide.PaymentsGateway;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * The usual Quarkus test idiom: {@code @InjectMock} on the interface a bean implements. Without resilience4cata the
 * interface resolves to the {@code @ApplicationScoped} {@code CardPayments}, which Quarkus can mock; with the
 * extension it resolves to the wrapper, a {@code @Singleton}, which {@code @InjectMock} rejects.
 */
@QuarkusTest
// On the class: @InjectMock fails when the test instance is created, before any method condition is evaluated.
@Disabled("BUG-QK-8: @InjectMock on an annotated interface fails: its wrapper bean is @Singleton, not normal-scoped")
class InjectMockInterfaceTest {

    @InjectMock
    PaymentsGateway payments;

    @Inject
    Checkout checkout;

    @Test
    void theInterfaceCanBeMocked() {
        when(payments.charge("o-1", 1999)).thenReturn("mocked");
        assertThat(checkout.pay("o-1")).isEqualTo("mocked");
    }
}
