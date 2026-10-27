package dev.resilience4cata.quarkus.it.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.resilience4cata.quarkus.guide.Checkout;
import dev.resilience4cata.quarkus.guide.PaymentsGateway;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The usual Quarkus test idiom: {@code @InjectMock} on the interface a bean implements. The interface resolves to the
 * wrapper of the {@code @ApplicationScoped} {@code CardPayments}, which is {@code @ApplicationScoped} too, so Quarkus
 * can mock it: the mock replaces the wrapper, policies included.
 */
@QuarkusTest
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
