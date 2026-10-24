package dev.resilience4cata.benchmark;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous service, one method per policy plus a stack of four, for {@link AsyncBenchmark}. Every
 * method but {@link #timeoutPending} returns an already completed future.
 */
public interface AsyncService {

    @CircuitBreaker
    CompletableFuture<String> circuitBreaker();

    @Retry(maxAttempts = 3)
    CompletableFuture<String> retry();

    @Timeout("1s")
    CompletableFuture<String> timeout();

    /** Returns {@code stage}, which the caller completes afterwards: the timeout cannot skip its timer. */
    @Timeout("1s")
    CompletableFuture<String> timeoutPending(CompletableFuture<String> stage);

    @Bulkhead(maxConcurrent = 1000)
    CompletableFuture<String> bulkhead();

    @RateLimiter(permits = 1_000_000_000, period = "1s")
    CompletableFuture<String> rateLimiter();

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    @Timeout("1s")
    @Bulkhead(maxConcurrent = 1000)
    CompletableFuture<String> stack();

    /** Returns one already completed future. */
    final class Impl implements AsyncService {
        private final CompletableFuture<String> done = CompletableFuture.completedFuture("ok");

        @Override
        public CompletableFuture<String> circuitBreaker() {
            return done;
        }

        @Override
        public CompletableFuture<String> retry() {
            return done;
        }

        @Override
        public CompletableFuture<String> timeout() {
            return done;
        }

        @Override
        public CompletableFuture<String> timeoutPending(CompletableFuture<String> stage) {
            return stage;
        }

        @Override
        public CompletableFuture<String> bulkhead() {
            return done;
        }

        @Override
        public CompletableFuture<String> rateLimiter() {
            return done;
        }

        @Override
        public CompletableFuture<String> stack() {
            return done;
        }
    }
}
