package dev.resilience4cata.examples.docs.circuitbreaker;

// tag::circuit-breaker-minimal[]
import dev.resilience4cata.annotation.CircuitBreaker;

public interface PaymentGateway {

    @CircuitBreaker(name = "payments", windowSize = 10, minimumCalls = 10, failureRateThreshold = 50,
            waitInOpen = "30s", halfOpenCalls = 2)
    String charge(String orderId);
}
// end::circuit-breaker-minimal[]
