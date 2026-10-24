package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;

/** One policy per method, to observe their events and snapshots end to end. */
public interface EventClient {

    @Retry(name = "orders", maxAttempts = 3, delay = "1ms")
    @Fallback(method = "cached")
    String order(String id) throws IOException;

    default String cached(String id, IOException e) {
        return "cached " + id;
    }

    @CircuitBreaker(name = "payments", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    String pay(String id) throws IOException;

    @Timeout("50ms")
    String slow(String id) throws InterruptedException;

    @Bulkhead(name = "reports", maxConcurrent = 1)
    String report(String id) throws InterruptedException;

    @RateLimiter(name = "search", permits = 1, period = "1h")
    String search(String query);
}
