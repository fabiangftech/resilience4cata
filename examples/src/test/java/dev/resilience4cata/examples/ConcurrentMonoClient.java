package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import reactor.core.publisher.Mono;

/** {@code Mono} methods under real concurrency and real timers, for {@link MonoConcurrencyTest}. */
public interface ConcurrentMonoClient {

    /** At most {@link #MAX_CONCURRENT} calls at once inside the bulkhead. */
    int MAX_CONCURRENT = 8;

    @Retry(name = "cc-stack", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "cc-stack", windowSize = 1000, minimumCalls = 1000, failureRateThreshold = 100)
    @AdaptiveLimit(name = "cc-stack", initialLimit = 64, minLimit = 16, maxLimit = 128, window = "10ms")
    @Timeout("15ms")
    @Bulkhead(name = "cc-stack", maxConcurrent = MAX_CONCURRENT, maxWait = "500ms")
    @Fallback(method = "recover")
    Mono<String> stack(int i);

    @Hedge(name = "cc-hedge", maxAttempts = 3, delay = "2ms", budgetPercent = 100)
    @Timeout("40ms")
    @Bulkhead(name = "cc-hedge", maxConcurrent = MAX_CONCURRENT * 3)
    Mono<String> hedged(int i);

    default Mono<String> recover(int i, Throwable failure) {
        return Mono.just("recovered:" + i);
    }
}
