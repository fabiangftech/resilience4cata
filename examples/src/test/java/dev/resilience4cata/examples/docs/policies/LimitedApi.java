package dev.resilience4cata.examples.docs.policies;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

/** Interactions between policies that the overview page states. */
public interface LimitedApi {

    @CircuitBreaker(name = "limited", windowSize = 2, minimumCalls = 2)
    @RateLimiter(permits = 1, period = "1h")
    String limited(String id);

    @Retry(maxAttempts = 3, delay = "1s")
    @RateLimiter(permits = 1, period = "1s")
    String retriedRejections(String id);

    @Retry(maxAttempts = 3, delay = "0")
    @Timeout("1s")
    String timedPerAttempt(String id);

    @Retry(maxAttempts = 3, delay = "5s")
    @Bulkhead(name = "slots", maxConcurrent = 1)
    String slotDuringBackoff(String id);
}
