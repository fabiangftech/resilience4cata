package dev.resilience4cata.examples;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import reactor.core.publisher.Mono;

/**
 * Pairs of twin methods, for {@link MonoEquivalenceEndToEndTest}: in each shape, {@code stage} and
 * {@code mono} carry the same annotations, so the generated code differs only in returning a stage or
 * a {@code Mono}. Named policies share their instance name; the rest differ only in the method name.
 */
public final class MonoTwins {

    private MonoTwins() {
    }

    /** What every shape declares; the shapes repeat the two methods with their annotations. */
    public interface Twin {
        CompletionStage<String> stage(int call);

        Mono<String> mono(int call);

        default String recover(int call, Throwable failure) {
            return "fallback:" + failure.getClass().getSimpleName();
        }

        default CompletionStage<String> recoverStage(int call, Throwable failure) {
            return CompletableFuture.completedFuture("stage fallback:" + failure.getClass().getSimpleName());
        }

        default Mono<String> recoverMono(int call, Throwable failure) {
            return Mono.just("stage fallback:" + failure.getClass().getSimpleName());
        }
    }

    /** Blank results and failures are failures. */
    public static final class Blank implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure != null || result == null || result.isBlank() ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }

    public interface RetryAndTimeout extends Twin {
        @Override
        @Retry(name = "a", maxAttempts = 3, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @Timeout("100ms")
        CompletionStage<String> stage(int call);

        @Override
        @Retry(name = "a", maxAttempts = 3, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @Timeout("100ms")
        Mono<String> mono(int call);
    }

    public interface RetryBreakerFallback extends Twin {
        @Override
        @Retry(name = "b", maxAttempts = 2, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @CircuitBreaker(name = "b", windowSize = 4, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "500ms")
        @Fallback(method = "recover")
        CompletionStage<String> stage(int call);

        @Override
        @Retry(name = "b", maxAttempts = 2, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @CircuitBreaker(name = "b", windowSize = 4, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "500ms")
        @Fallback(method = "recover")
        Mono<String> mono(int call);
    }

    public interface HedgeTimeoutBulkhead extends Twin {
        @Override
        @Hedge(name = "c", maxAttempts = 3, delay = "30ms", budgetPercent = 100)
        @Timeout("100ms")
        @Bulkhead(name = "c", maxConcurrent = 3)
        CompletionStage<String> stage(int call);

        @Override
        @Hedge(name = "c", maxAttempts = 3, delay = "30ms", budgetPercent = 100)
        @Timeout("100ms")
        @Bulkhead(name = "c", maxConcurrent = 3)
        Mono<String> mono(int call);
    }

    public interface FallbackTimeoutBulkhead extends Twin {
        @Override
        @Fallback(method = "recoverStage")
        @Timeout("100ms")
        @Bulkhead(name = "d", maxConcurrent = 1)
        CompletionStage<String> stage(int call);

        @Override
        @Fallback(method = "recoverMono")
        @Timeout("100ms")
        @Bulkhead(name = "d", maxConcurrent = 1)
        Mono<String> mono(int call);
    }

    public interface TimeoutAroundRetry extends Twin {
        @Override
        @Retry(name = "e", maxAttempts = 4, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @Timeout("120ms")
        @PolicyOrder({Layer.TIMEOUT, Layer.RETRY})
        CompletionStage<String> stage(int call);

        @Override
        @Retry(name = "e", maxAttempts = 4, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @Timeout("120ms")
        @PolicyOrder({Layer.TIMEOUT, Layer.RETRY})
        Mono<String> mono(int call);
    }

    public interface Classified extends Twin {
        @Override
        @Retry(name = "f", maxAttempts = 3, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE,
                classifier = Blank.class)
        @CircuitBreaker(name = "f", windowSize = 4, minimumCalls = 2, waitInOpen = "500ms", classifier = Blank.class)
        @Timeout("100ms")
        CompletionStage<String> stage(int call);

        @Override
        @Retry(name = "f", maxAttempts = 3, delay = "10ms", backoff = Backoff.FIXED, jitter = Jitter.NONE,
                classifier = Blank.class)
        @CircuitBreaker(name = "f", windowSize = 4, minimumCalls = 2, waitInOpen = "500ms", classifier = Blank.class)
        @Timeout("100ms")
        Mono<String> mono(int call);
    }

    public interface LimitedRetry extends Twin {
        @Override
        @Retry(name = "g", maxAttempts = 3, delay = "40ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @RateLimiter(name = "g", permits = 2, period = "100ms", maxWait = "60ms")
        @Timeout("100ms")
        CompletionStage<String> stage(int call);

        @Override
        @Retry(name = "g", maxAttempts = 3, delay = "40ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
        @RateLimiter(name = "g", permits = 2, period = "100ms", maxWait = "60ms")
        @Timeout("100ms")
        Mono<String> mono(int call);
    }

    public interface Adaptive extends Twin {
        @Override
        @AdaptiveLimit(name = "h", initialLimit = 2, minLimit = 1, maxLimit = 4, window = "50ms")
        @Timeout("100ms")
        @Fallback(method = "recover")
        CompletionStage<String> stage(int call);

        @Override
        @AdaptiveLimit(name = "h", initialLimit = 2, minLimit = 1, maxLimit = 4, window = "50ms")
        @Timeout("100ms")
        @Fallback(method = "recover")
        Mono<String> mono(int call);
    }
}
