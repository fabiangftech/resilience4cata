package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import reactor.core.publisher.Flux;

/** Every policy that a {@code Flux} method can use, with real timers, for {@link FluxBlockHoundTest}. */
public interface FluxBlockHoundClient {

    @Retry(name = "fbh-stack", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "fbh-stack", windowSize = 100, minimumCalls = 100, failureRateThreshold = 100,
            slowCallDuration = "1s")
    @RateLimiter(name = "fbh-stack", permits = 1_000_000, period = "1s")
    @AdaptiveLimit(name = "fbh-stack", initialLimit = 64, minLimit = 8, maxLimit = 256, window = "10ms")
    @Timeout("20ms")
    @Bulkhead(name = "fbh-stack", maxConcurrent = 16, maxWait = "1s")
    @Fallback(method = "recover")
    Flux<String> stack(int i);

    @RateLimiter(name = "fbh-limited", permits = 50, period = "10ms", maxWait = "1s")
    Flux<String> limited(int i);

    default Flux<String> recover(int i, Throwable failure) {
        return Flux.just("recovered", "twice");
    }
}
