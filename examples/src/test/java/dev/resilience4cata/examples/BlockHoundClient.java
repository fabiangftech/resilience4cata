package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import reactor.core.publisher.Mono;

/** Every policy on {@code Mono} methods, with real timers, for {@link MonoBlockHoundTest}. */
public interface BlockHoundClient {

    @Retry(name = "bh-stack", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "bh-stack", windowSize = 100, minimumCalls = 100, failureRateThreshold = 100,
            slowCallDuration = "1s")
    @RateLimiter(name = "bh-stack", permits = 1_000_000, period = "1s")
    @AdaptiveLimit(name = "bh-stack", initialLimit = 64, minLimit = 8, maxLimit = 256, window = "10ms")
    @Timeout("20ms")
    @Bulkhead(name = "bh-stack", maxConcurrent = 16, maxWait = "1s")
    @Fallback(method = "recover")
    Mono<String> stack(int i);

    @Hedge(name = "bh-hedge", maxAttempts = 3, delay = "2ms", budgetPercent = 100)
    @Timeout("50ms")
    Mono<String> hedged(int i);

    @RateLimiter(name = "bh-limited", permits = 50, period = "10ms", maxWait = "1s")
    Mono<String> limited(int i);

    default Mono<String> recover(int i, Throwable failure) {
        return Mono.just("recovered");
    }
}
