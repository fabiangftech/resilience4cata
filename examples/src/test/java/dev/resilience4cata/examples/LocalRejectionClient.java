package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.RateLimiter;

/** A breaker around this application's own limits: their rejections must not open it. */
interface LocalRejectionClient {

    @CircuitBreaker(name = "local-bulkhead", windowSize = 4, minimumCalls = 4, failureRateThreshold = 50)
    @Bulkhead(name = "local-bulkhead", maxConcurrent = 1)
    String fetch(String id);

    @CircuitBreaker(name = "local-rate", windowSize = 4, minimumCalls = 4, failureRateThreshold = 50,
            recordOn = Exception.class)
    @RateLimiter(name = "local-rate", permits = 1, period = "1h")
    String quote(String id);
}
