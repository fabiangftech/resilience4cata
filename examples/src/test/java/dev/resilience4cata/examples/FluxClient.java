package dev.resilience4cata.examples;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.List;
import reactor.core.publisher.Flux;

/** Methods that return {@code Flux}, one per policy and a few stacks, for {@link FluxEndToEndTest}. */
public interface FluxClient {

    @Retry(name = "flux-retry", maxAttempts = 3, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    Flux<String> retried(String id);

    @CircuitBreaker(name = "flux-breaker", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1s")
    Flux<String> guarded(String id);

    @CircuitBreaker(name = "flux-slow", windowSize = 2, minimumCalls = 2, slowCallDuration = "1s",
            slowCallRateThreshold = 100, failureRateThreshold = 100, waitInOpen = "1s")
    Flux<String> slow(String id);

    @Timeout("500ms")
    Flux<String> timed(String id);

    @Bulkhead(name = "flux-bulkhead", maxConcurrent = 1)
    Flux<String> bulkheaded(String id);

    @Bulkhead(name = "flux-bulkhead-wait", maxConcurrent = 1, maxWait = "10s")
    Flux<String> bulkheadedWithWait(String id);

    @RateLimiter(name = "flux-limiter", permits = 1, period = "1s")
    Flux<String> limited(String id);

    @AdaptiveLimit(name = "flux-adaptive", initialLimit = 1, minLimit = 1, maxLimit = 1)
    Flux<String> adaptive(String id);

    @Retry(name = "flux-fallback", maxAttempts = 2, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Fallback(method = "recover")
    Flux<String> recovered(String id);

    default Flux<String> recover(String id, Throwable failure) {
        return Flux.deferContextual(context -> Flux.just("recovered " + id, "from " + failure.getMessage(),
                "for " + context.getOrDefault("user", "nobody")));
    }

    @Timeout("200ms")
    @Fallback(method = "placeholder")
    Flux<String> recoveredWithAValue(String id);

    default String placeholder(String id) {
        return "placeholder " + id;
    }

    @Fallback(Cached.class)
    @Timeout("200ms")
    Flux<String> handled(String id);

    /** The fallback of {@link #handled}: one value, not a {@code Flux}. */
    final class Cached implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "cached " + context.argument(0, String.class) + " after " + context.attempts();
        }
    }

    @Retry(name = "flux-stack", maxAttempts = 3, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "flux-stack", windowSize = 10, minimumCalls = 10)
    @Timeout("300ms")
    @Bulkhead(name = "flux-stack", maxConcurrent = 5)
    @Fallback(method = "recover")
    Flux<String> stack(String id);

    @Retry(name = "flux-wildcard", maxAttempts = 2, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    Flux<? extends CharSequence> wildcard(String id);

    @Retry(name = "flux-generic", maxAttempts = 2, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    <T> Flux<List<T>> generic(Class<T> type);

    /** Not annotated: delegated as it is. */
    Flux<String> plain(String id);
}
