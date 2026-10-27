package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Asynchronous methods of an annotated class: a retry with a pause, and a timeout with a fallback. */
@Singleton
@Requires(property = "test.usability", value = "async")
public class AsyncQuotes {

    private final Failures failures;
    final CompletableFuture<String> never = new CompletableFuture<>();

    public AsyncQuotes(Failures failures) {
        this.failures = failures;
    }

    @Retry(maxAttempts = 3, delay = "1s")
    public CompletableFuture<String> quote(String sku) {
        try {
            failures.call();
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.completedFuture(sku + "=1");
    }

    @Timeout("2s")
    @Fallback(method = "cached")
    public CompletionStage<String> slow(String sku) {
        return never;
    }

    CompletionStage<String> cached(String sku) {
        return CompletableFuture.completedFuture(sku + "=cached");
    }
}
