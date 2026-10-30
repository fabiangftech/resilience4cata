package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import reactor.core.publisher.Flux;

/** {@code Flux} methods under real concurrency and real timers, for {@link FluxConcurrencyTest}. */
public interface ConcurrentFluxClient {

    /** At most {@link #MAX_CONCURRENT} streams at once inside the bulkhead. */
    int MAX_CONCURRENT = 8;

    @Retry(name = "cf-stack", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "cf-stack", windowSize = 1000, minimumCalls = 1000, failureRateThreshold = 100,
            slowCallDuration = "10ms")
    @AdaptiveLimit(name = "cf-stack", initialLimit = 64, minLimit = 16, maxLimit = 128, window = "10ms")
    @Timeout("15ms")
    @Bulkhead(name = "cf-stack", maxConcurrent = MAX_CONCURRENT, maxWait = "500ms")
    @Fallback(method = "recover")
    Flux<String> stack(int i);

    default Flux<String> recover(int i, Throwable failure) {
        return Flux.just(i + ":r:0", i + ":r:1");
    }
}
