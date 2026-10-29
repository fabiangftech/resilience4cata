package dev.resilience4cata.examples;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.util.List;
import reactor.core.publisher.Mono;

/** Methods that return {@code Mono}, one per policy and a few stacks, for {@link MonoEndToEndTest}. */
public interface MonoClient {

    @Retry(name = "mono-retry", maxAttempts = 3, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    Mono<String> retried(String id);

    @Retry(name = "mono-retry-io", maxAttempts = 3, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE,
            retryOn = IOException.class)
    Mono<String> retriedOnIo(String id) throws IOException;

    @CircuitBreaker(name = "mono-breaker", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1s")
    Mono<String> guarded(String id);

    @Timeout("500ms")
    Mono<String> timed(String id);

    @Bulkhead(name = "mono-bulkhead", maxConcurrent = 1)
    Mono<String> bulkheaded(String id);

    @Bulkhead(name = "mono-bulkhead-wait", maxConcurrent = 1, maxWait = "1s")
    Mono<String> bulkheadedWithWait(String id);

    @RateLimiter(name = "mono-limiter", permits = 1, period = "1s")
    Mono<String> limited(String id);

    @RateLimiter(name = "mono-limiter-wait", permits = 1, period = "1s", maxWait = "2s")
    Mono<String> limitedWithWait(String id);

    @AdaptiveLimit(name = "mono-adaptive", initialLimit = 1, minLimit = 1, maxLimit = 1)
    Mono<String> adaptive(String id);

    @Hedge(name = "mono-hedge", maxAttempts = 2, delay = "50ms")
    Mono<String> hedged(String id);

    @Retry(name = "mono-fallback", maxAttempts = 2, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Fallback(method = "recover")
    Mono<String> recovered(String id);

    default Mono<String> recover(String id, Throwable failure) {
        return Mono.deferContextual(context -> Mono.just("recovered " + id + " from " + failure.getMessage()
                + " for " + context.getOrDefault("user", "nobody")));
    }

    @Fallback(Cached.class)
    @Timeout("200ms")
    Mono<String> handled(String id);

    /** The fallback of {@link #handled}: a value, not a {@code Mono}. */
    final class Cached implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "cached " + context.argument(0, String.class) + " after " + context.attempts();
        }
    }

    @Retry(name = "mono-classified", maxAttempts = 3, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE,
            classifier = Blank.class, onExhaustedResult = ExhaustedResult.THROW)
    Mono<String> classified(String id);

    /** Blank results and every failure are failures. */
    final class Blank implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure != null || result == null || result.isBlank() ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }

    @Retry(name = "mono-stack", maxAttempts = 3, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "mono-stack", windowSize = 10, minimumCalls = 10)
    @Timeout("300ms")
    @Bulkhead(name = "mono-stack", maxConcurrent = 5)
    @Fallback(method = "recover")
    Mono<String> stack(String id);

    @Retry(name = "mono-ordered", maxAttempts = 3, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @Timeout("250ms")
    @PolicyOrder({Layer.TIMEOUT, Layer.RETRY})
    Mono<String> oneTimeoutForAllAttempts(String id);

    @Retry(name = "mono-void", maxAttempts = 2, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    Mono<Void> send(String message);

    @Retry(name = "mono-wildcard", maxAttempts = 2, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    Mono<? extends CharSequence> wildcard(String id);

    @Retry(name = "mono-generic", maxAttempts = 2, delay = "100ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    <T> Mono<List<T>> generic(Class<T> type);

    /** Not annotated: delegated as it is. */
    Mono<String> plain(String id);
}
