package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;

/** Three methods sharing one retry budget (same {@code name}, same attributes), sync, async and with a fallback. */
public interface BudgetedClient {

    @Retry(name = "backend", maxAttempts = 3, delay = "10ms", jitter = Jitter.NONE,
            budgetPercent = 10, budgetMinRetriesPerSecond = 1, budgetWindow = "5s")
    String get(String id);

    @Retry(name = "backend", maxAttempts = 3, delay = "10ms", jitter = Jitter.NONE,
            budgetPercent = 10, budgetMinRetriesPerSecond = 1, budgetWindow = "5s")
    CompletableFuture<String> getAsync(String id);

    @Fallback(method = "cached")
    @Retry(name = "backend", maxAttempts = 3, delay = "10ms", jitter = Jitter.NONE,
            budgetPercent = 10, budgetMinRetriesPerSecond = 1, budgetWindow = "5s")
    String getOrCached(String id);

    default String cached(String id, Exception e) {
        return "cached " + id;
    }
}
