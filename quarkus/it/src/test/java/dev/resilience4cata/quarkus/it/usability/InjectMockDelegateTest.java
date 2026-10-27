package dev.resilience4cata.quarkus.it.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.resilience4cata.quarkus.guide.CardPayments;
import dev.resilience4cata.quarkus.guide.Checkout;
import dev.resilience4cata.quarkus.guide.Order;
import dev.resilience4cata.quarkus.guide.OrderService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@code @InjectMock} on the bean behind an annotated interface: the wrapper holds the bean's client proxy, so the mock
 * is called through the policies (here, retried). {@code @InjectMock} on an annotated class mocks the generated
 * subclass itself, so its policies are bypassed, as with any mock.
 */
@QuarkusTest
class InjectMockDelegateTest {

    @InjectMock
    CardPayments card;

    @InjectMock
    OrderService orders;

    @Inject
    Checkout checkout;

    @Test
    void theMockedDelegateIsCalledThroughThePolicies() {
        when(card.charge("o-2", 1999)).thenThrow(new IllegalStateException("down")).thenReturn("mocked");
        assertThat(checkout.pay("o-2")).isEqualTo("mocked");
        verify(card, times(2)).charge("o-2", 1999);
    }

    @Test
    void anAnnotatedClassCanBeMocked() {
        when(orders.find("1")).thenReturn(new Order("mocked"));
        assertThat(orders.find("1")).isEqualTo(new Order("mocked"));
    }
}
