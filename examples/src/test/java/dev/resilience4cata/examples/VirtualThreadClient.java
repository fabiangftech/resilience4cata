package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

/** Synchronous methods that block, called from thousands of virtual threads by {@link VirtualThreadsEndToEndTest}. */
public interface VirtualThreadClient {

    @Timeout("100ms")
    String slow(String id);

    @Bulkhead(name = "vt-bulkhead", maxConcurrent = 8, maxWait = "30s")
    String queued(String id);

    @RateLimiter(name = "vt-limiter", permits = 500, period = "1s", maxWait = "30s")
    String limited(String id);

    @Retry(name = "vt-retry", maxAttempts = 3, delay = "20ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    String retried(String id);

    @Retry(name = "vt-stack", maxAttempts = 2, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "vt-stack", windowSize = 10000, minimumCalls = 10000) // records every outcome, never opens here
    @AdaptiveLimit(name = "vt-stack", initialLimit = 4000, minLimit = 4000, maxLimit = 4000)
    @Timeout("2s")
    @Bulkhead(name = "vt-stack", maxConcurrent = 16, maxWait = "30s")
    String stacked(String id);
}
