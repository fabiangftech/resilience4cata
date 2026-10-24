package dev.resilience4cata.examples;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Generic methods with fallbacks: sibling methods with the same type parameters, sync and async. */
public interface GenericClient {

    /** Retried, then the fallback for {@code IOException}, fused into the retry layer. */
    @Retry(maxAttempts = 3, delay = "1ms")
    @Fallback(method = "load")
    <T> T load(Class<T> type, String id) throws IOException;

    /** Type parameters are matched by position, so they may have other names. */
    default <U> U load(Class<U> type, String id, IOException e) {
        return type.cast(type == String.class ? "cached " + id : null);
    }

    /** A static fallback with a recursive bound, behind a timeout. */
    @Timeout("5s")
    @Fallback(method = "smallest")
    <T extends Comparable<T>> T largest(List<T> values);

    static <T extends Comparable<T>> T smallest(List<T> values, IllegalStateException e) {
        return values.stream().min(Comparable::compareTo).orElseThrow();
    }

    /** A handler works when the result type does not depend on the type parameters. */
    @Fallback(Unknown.class)
    <T> String describe(T value);

    @Retry(maxAttempts = 2, delay = "1ms")
    @Fallback(method = "fetch")
    <T> CompletionStage<T> fetch(Class<T> type, String id);

    default <T> CompletionStage<T> fetch(Class<T> type, String id, Throwable e) {
        return CompletableFuture.completedFuture(type.cast(type == Integer.class ? -1 : null));
    }

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown " + context.argument(0, Object.class);
        }
    }
}
