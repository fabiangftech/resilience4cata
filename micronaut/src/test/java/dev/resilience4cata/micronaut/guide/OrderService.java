package dev.resilience4cata.micronaut.guide;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Requires(env = "guide")
// tag::micronaut-class[]
@Singleton
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    @Retry(maxAttempts = 3, delay = "100ms")
    @CircuitBreaker(name = "orders")
    public Order find(String id) {
        return orders.load(id);
    }

    public Order findAgain(String id) {
        return find(id); // a call on this: still retried
    }
}
// end::micronaut-class[]
