package dev.resilience4cata.examples;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Idempotent reads from replicated backends, hedged against slow replicas. */
public interface HedgeClient {

    @Hedge(delay = "50ms", maxAttempts = 3, budgetPercent = 0)
    @CircuitBreaker(name = "replicas", windowSize = 10, minimumCalls = 10)
    @Timeout("200ms")
    @Fallback(Cached.class)
    CompletableFuture<String> price(String sku);

    @Hedge(name = "reads", delay = "20ms")
    CompletionStage<String> read(String key);

    /** Answers from a cache, saying how many attempts were started. */
    final class Cached implements FallbackHandler<CompletableFuture<String>> {
        @Override
        public CompletableFuture<String> apply(FallbackContext context) {
            return CompletableFuture.completedFuture("cached after " + context.attempts() + ": "
                    + context.failure().getClass().getSimpleName());
        }
    }
}
