package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.RateLimiter;

/** Rate limiters alone, shared by name, waiting for a permit, and behind a circuit breaker. */
public interface QuoteClient {

    @RateLimiter(permits = 3, period = "1h")
    String quote(String symbol);

    @RateLimiter(name = "market-api", permits = 2, period = "1h")
    String history(String symbol);

    @RateLimiter(name = "market-api", permits = 2, period = "1h")
    String news(String symbol);

    @RateLimiter(permits = 1, period = "100ms", maxWait = "5s")
    String paced(String symbol);

    @CircuitBreaker
    @RateLimiter(permits = 1, period = "1h")
    String guarded(String symbol);
}
