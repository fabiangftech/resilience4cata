package dev.resilience4cata.examples.docs.opentelemetry;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;

/** The service the OpenTelemetry page instruments: every policy shares the instance id {@code payments}. */
public interface PaymentGateway {

    @Retry(name = "payments", maxAttempts = 3, delay = "10ms")
    @CircuitBreaker(name = "payments", windowSize = 4, minimumCalls = 4)
    @RateLimiter(name = "payments", permits = 100, period = "1s")
    @Bulkhead(name = "payments", maxConcurrent = 10)
    String charge(String order);
}
