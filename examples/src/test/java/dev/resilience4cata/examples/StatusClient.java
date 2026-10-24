package dev.resilience4cata.examples;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Asynchronous methods: every policy on its own, all of them together, and both kinds of fallback. */
public interface StatusClient {

    @Retry(maxAttempts = 3, delay = "10ms")
    CompletableFuture<String> status(String id) throws IOException;

    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    CompletionStage<String> ping(String id);

    @Timeout("100ms")
    CompletableFuture<String> slow(String id);

    @Bulkhead(maxConcurrent = 1)
    CompletableFuture<String> limited(String id);

    @RateLimiter(permits = 1, period = "200ms", maxWait = "1s")
    CompletableFuture<String> paced(String id);

    @Fallback(method = "recover")
    @Retry(maxAttempts = 2, delay = "0")
    CompletableFuture<String> withFallback(String id);

    default String recover(String id, IOException e) {
        return "fallback " + id + ": " + e.getMessage();
    }

    default CompletionStage<String> recover(String id, IllegalStateException e) {
        return CompletableFuture.supplyAsync(() -> "async fallback " + id);
    }

    @Fallback(Unknown.class)
    @Timeout("50ms")
    CompletionStage<Integer> count(String id);

    @Fallback(Unknown.class)
    @Retry(maxAttempts = 2, delay = "0")
    @CircuitBreaker
    @RateLimiter(permits = 1000)
    @Timeout("1s")
    @Bulkhead(maxConcurrent = 10)
    CompletableFuture<Integer> everything(String id) throws IOException;

    /** Answers -1 and records the attempts it was told about. */
    final class Unknown implements FallbackHandler<Integer> {
        static volatile int attempts;

        @Override
        public Integer apply(FallbackContext context) {
            attempts = context.attempts();
            return -1;
        }
    }
}
