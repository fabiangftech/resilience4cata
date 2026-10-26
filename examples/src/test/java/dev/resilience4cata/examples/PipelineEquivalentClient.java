package dev.resilience4cata.examples;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.LimitAlgorithm;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * The annotated twin of the pipelines in {@code PipelineEquivalenceTest}: same configuration, so the
 * generated code and the pipeline must behave the same.
 */
public interface PipelineEquivalentClient {

    @Retry(maxAttempts = 3, delay = "0", jitter = Jitter.NONE)
    @CircuitBreaker(windowSize = 4, minimumCalls = 4, failureRateThreshold = 50, waitInOpen = "1h")
    String guarded(String order) throws IOException;

    @Retry(maxAttempts = 3, delay = "0", jitter = Jitter.NONE, classifier = Busy.class)
    @CircuitBreaker(windowSize = 4, minimumCalls = 4, failureRateThreshold = 50, waitInOpen = "1h",
            classifier = Busy.class)
    String classified(String order) throws IOException;

    @RateLimiter(permits = 2, period = "1h")
    String limited(String order) throws IOException;

    @AdaptiveLimit(algorithm = LimitAlgorithm.AIMD, initialLimit = 10, dropOn = IOException.class)
    String adaptive(String order) throws IOException;

    @Fallback(Pending.class)
    @Retry(maxAttempts = 3, delay = "0", jitter = Jitter.NONE)
    @CircuitBreaker(windowSize = 10, minimumCalls = 10, waitInOpen = "1h")
    @RateLimiter(permits = 1000)
    @Timeout("1s")
    @Bulkhead(maxConcurrent = 10)
    String everything(String order) throws IOException;

    @Fallback(Pending.class)
    @Retry(maxAttempts = 3, delay = "0", jitter = Jitter.NONE)
    @CircuitBreaker(windowSize = 10, minimumCalls = 10, waitInOpen = "1h")
    @RateLimiter(permits = 1000)
    @Timeout("1s")
    @Bulkhead(maxConcurrent = 10)
    CompletableFuture<String> everythingAsync(String order);

    /** A {@code "busy"} answer is a failure; an {@link IllegalStateException} is ignored; other exceptions fail. */
    final class Busy implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            if (failure instanceof IllegalStateException) {
                return Outcome.IGNORE;
            }
            if (failure != null || result.startsWith("busy")) {
                return Outcome.FAILURE;
            }
            return Outcome.SUCCESS;
        }
    }

    /** Answers with the failure and the number of attempts. */
    final class Pending implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "pending after " + context.attempts() + ": " + context.failure().getClass().getSimpleName();
        }
    }
}
