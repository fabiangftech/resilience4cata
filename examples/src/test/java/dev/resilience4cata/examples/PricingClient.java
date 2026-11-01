package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.WindowType;

/** Circuit breakers alone and combined with a retry. */
public interface PricingClient {

    @CircuitBreaker(windowSize = 4, minimumCalls = 4, failureRateThreshold = 50, waitInOpen = "1h")
    int price(String sku);

    @Retry(maxAttempts = 10, delay = "0", jitter = Jitter.NONE)
    @CircuitBreaker(windowSize = 3, minimumCalls = 3, failureRateThreshold = 100, waitInOpen = "1h")
    int priceWithRetry(String sku);

    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "0", halfOpenCalls = 1)
    void refresh();

    @CircuitBreaker(name = "shared", config = "pricing")
    int first();

    @CircuitBreaker(name = "shared", config = "pricing")
    int second();

    @CircuitBreaker(windowType = WindowType.TIME, windowDuration = "60s", minimumCalls = 3, failureRateThreshold = 50, waitInOpen = "1h")
    int recent();
}
