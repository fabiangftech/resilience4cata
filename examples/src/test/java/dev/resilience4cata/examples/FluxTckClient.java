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

/** Generated {@code Flux} methods checked against the Reactive Streams TCK by {@link FluxPublisherTckTest}. */
public interface FluxTckClient {

    @Retry(name = "ftck-stack", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "ftck-stack", windowSize = 1000, minimumCalls = 1000, slowCallDuration = "5s")
    @AdaptiveLimit(name = "ftck-stack", initialLimit = 1000, minLimit = 1000, maxLimit = 1000)
    @Timeout("5s")
    @Bulkhead(name = "ftck-stack", maxConcurrent = 1000)
    Flux<String> stack(long elements, boolean fail, boolean async);

    /** The delegate always fails before its first element: the elements come from the fallback's stream. */
    @Retry(name = "ftck-fallback", maxAttempts = 2, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Timeout("5s")
    @Fallback(method = "replacement")
    Flux<String> recovered(long elements, boolean fail, boolean async);

    default Flux<String> replacement(long elements, boolean fail, boolean async) {
        return FluxPublisherTckTest.Backend.emit(elements, fail, async);
    }
}
