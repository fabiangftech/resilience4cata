package dev.resilience4cata.quarkus.guide;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

// tag::quarkus-class[]
@ApplicationScoped
public class OrderService {

    @Inject
    OrderRepository orders;

    @Retry(maxAttempts = 3, delay = "100ms")
    @CircuitBreaker(name = "orders")
    public Order find(String id) {
        return orders.load(id);
    }

    public Order findAgain(String id) {
        return find(id); // a call on this: still retried
    }
}
// end::quarkus-class[]
