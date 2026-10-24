package dev.resilience4cata.examples;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Async methods that return a stage with a wildcard: the policies work with its upper bound. */
public interface WildcardClient {

    /** Retried, then a fallback that returns a value of the upper bound's subtype. */
    @Retry(maxAttempts = 3, delay = "1ms")
    @Fallback(method = "zero")
    CompletionStage<? extends Number> count(String id);

    default Integer zero(String id, Throwable e) {
        return 0;
    }

    /** {@code ?} means {@code Object}. */
    @CircuitBreaker
    @Timeout("5s")
    CompletableFuture<?> anything(String id);

    /** A handler that returns a stage of a subtype. */
    @Retry(maxAttempts = 2, delay = "1ms")
    @Fallback(Cached.class)
    CompletionStage<? extends CharSequence> name(String id);

    /** Generic and wildcard together; the fallback returns the same wildcard stage. */
    @Retry(maxAttempts = 2, delay = "1ms")
    @Fallback(method = "items")
    <T> CompletableFuture<? extends List<T>> items(Class<T> type);

    default <T> CompletionStage<? extends List<T>> items(Class<T> type, Exception e) {
        return CompletableFuture.completedFuture(List.of());
    }

    final class Cached implements FallbackHandler<CompletionStage<String>> {
        @Override
        public CompletionStage<String> apply(FallbackContext context) {
            return CompletableFuture.completedFuture("cached " + context.argument(0, String.class)
                    + " after " + context.attempts());
        }
    }
}
