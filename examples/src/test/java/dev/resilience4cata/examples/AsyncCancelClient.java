package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Async methods whose callers cancel them, for {@link AsyncCancellationEndToEndTest}. */
public interface AsyncCancelClient {

    @Bulkhead(name = "cancel-bulkhead", maxConcurrent = 1, maxWait = "10s")
    CompletableFuture<String> held(String id);

    @Retry(name = "cancel-retry", maxAttempts = 5, delay = "1s")
    @Fallback(method = "recover")
    CompletableFuture<String> retried(String id);

    @CircuitBreaker(name = "cancel-breaker", windowSize = 1, minimumCalls = 1)
    CompletionStage<String> guarded(String id);

    @RateLimiter(name = "cancel-limiter", permits = 1, period = "1s", maxWait = "5s")
    CompletableFuture<String> limited(String id);

    @Fallback(method = "recover")
    @Retry(name = "cancel-stack", maxAttempts = 3, delay = "1s")
    @CircuitBreaker(name = "cancel-stack", windowSize = 1, minimumCalls = 1)
    @Timeout("5s")
    @Bulkhead(name = "cancel-stack", maxConcurrent = 1)
    CompletableFuture<String> stack(String id);

    default CompletableFuture<String> recover(String id, Throwable failure) {
        return CompletableFuture.completedFuture("fallback " + id);
    }
}
