package dev.resilience4cata.quarkus.it.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.resilience4cata.quarkus.guide.CardPayments;
import dev.resilience4cata.quarkus.guide.Checkout;
import dev.resilience4cata.quarkus.guide.Order;
import dev.resilience4cata.quarkus.guide.OrderRepository;
import dev.resilience4cata.quarkus.guide.OrderService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@code @InjectSpy}: a spy of an annotated class is a copy of the generated subclass, so it keeps its policies
 * (calls on {@code this} included) and can be verified; a spy of the bean behind an annotated interface is called
 * through the wrapper, once per attempt.
 */
@QuarkusTest
class InjectSpyTest {

    @InjectSpy
    OrderService orders;

    @InjectSpy
    CardPayments card;

    @Inject
    OrderRepository repository;

    @Inject
    Checkout checkout;

    @Test
    void aSpiedAnnotatedClassKeepsItsPoliciesAndCanBeVerified() {
        repository.failNext(2);
        int before = repository.loads();

        assertThat(orders.find("1")).isEqualTo(new Order("1"));
        assertThat(repository.loads() - before).as("retried through the spy").isEqualTo(3);
        verify(orders, times(1)).find("1");

        repository.failNext(1);
        assertThat(orders.findAgain("2")).isEqualTo(new Order("2"));
        assertThat(repository.loads() - before).as("a call on this is retried too").isEqualTo(5);
    }

    @Test
    void aSpiedDelegateIsCalledOncePerAttempt() {
        doThrow(new IllegalStateException("down")).doCallRealMethod().when(card).charge("o-3", 1999);

        assertThat(checkout.pay("o-3")).isEqualTo("receipt-o-3");
        verify(card, times(2)).charge("o-3", 1999);
    }
}
