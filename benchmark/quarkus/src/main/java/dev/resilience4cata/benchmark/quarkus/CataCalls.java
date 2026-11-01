package dev.resilience4cata.benchmark.quarkus;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;
import jakarta.inject.Singleton;

/**
 * resilience4cata on an annotated class: Quarkus creates its generated subclass as the bean. The settings match
 * {@link FaultToleranceCalls} and {@link R4jCalls}.
 */
@Singleton
public class CataCalls {

    @Retry(maxAttempts = 3, delay = "0ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    public String retry() {
        return "ok";
    }

    @Retry(maxAttempts = 3, delay = "0ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    public String retryOnce(Flip flip) {
        flip.attempt();
        return "ok";
    }

    @CircuitBreaker(windowSize = 100, minimumCalls = 100, failureRateThreshold = 50, waitInOpen = "60s")
    public String circuitBreaker() {
        return "ok";
    }

    @CircuitBreaker(name = "open", windowSize = 100, minimumCalls = 100, failureRateThreshold = 50, waitInOpen = "60s")
    public String open() {
        return "ok";
    }

    @Bulkhead(maxConcurrent = 1000, maxWait = "0ms")
    public String bulkhead() {
        return "ok";
    }

    @RateLimiter(permits = 1_000_000_000, period = "1s", maxWait = "0ms")
    public String rateLimiter() {
        return "ok";
    }

    @Timeout("1s")
    public String timeout() {
        return "ok";
    }

    @Timeout("1s")
    public CompletableFuture<String> timeoutAsync() {
        return PlainCalls.DONE;
    }

    @Retry(maxAttempts = 3, delay = "0ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(windowSize = 100, minimumCalls = 100, failureRateThreshold = 50, waitInOpen = "60s")
    @RateLimiter(permits = 1_000_000_000, period = "1s", maxWait = "0ms")
    @Bulkhead(maxConcurrent = 1000, maxWait = "0ms")
    public String stack() {
        return "ok";
    }
}
