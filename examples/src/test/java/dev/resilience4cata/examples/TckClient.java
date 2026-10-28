package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import reactor.core.publisher.Mono;

/** Generated {@code Mono} methods checked against the Reactive Streams TCK by {@link MonoPublisherTckTest}. */
public interface TckClient {

    @Retry(name = "tck-stack", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "tck-stack", windowSize = 1000, minimumCalls = 1000)
    @Timeout("5s")
    @Bulkhead(name = "tck-stack", maxConcurrent = 1000)
    Mono<String> stack(long elements, boolean fail, boolean delayed);

    @Hedge(name = "tck-hedge", maxAttempts = 2, delay = "1ms", budgetPercent = 100)
    @Timeout("5s")
    Mono<String> hedged(long elements, boolean fail, boolean delayed);
}
