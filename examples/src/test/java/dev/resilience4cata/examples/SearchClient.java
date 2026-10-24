package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

/** Timeouts alone and combined with a retry and a circuit breaker. */
public interface SearchClient {

    @Timeout("50ms")
    String search(String query);

    @Retry(maxAttempts = 3, delay = "0", jitter = Jitter.NONE)
    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 100, waitInOpen = "1h")
    @Timeout("50ms")
    String protectedSearch(String query);

    @Timeout(config = "slow")
    String report();
}
