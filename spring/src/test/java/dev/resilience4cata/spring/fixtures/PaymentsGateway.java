package dev.resilience4cata.spring.fixtures;

import dev.resilience4cata.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

/** A circuit breaker for the health indicator, the endpoint and the metrics. */
@Component
public class PaymentsGateway {

    @CircuitBreaker(name = "payments", minimumCalls = 2, windowSize = 2)
    public String pay(String order) {
        if (order.isEmpty()) {
            throw new IllegalArgumentException("no order");
        }
        return "paid " + order;
    }
}
