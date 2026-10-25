package dev.resilience4cata.examples;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Fallbacks whose sibling methods do not take every failure that {@code applyOn} lets through: a
 * {@code FallbackEvent.Applied} must be published only when a fallback really runs.
 */
public interface FallbackEventClient {

    /** Every failure passes {@code applyOn}, but the only sibling takes an {@code IOException}. */
    @Fallback(method = "cached")
    String load(String id, Throwable failure) throws IOException;

    default String cached(String id, Throwable failure, IOException e) {
        return "cached " + id;
    }

    /** Overloads for two unrelated exception types. */
    @Fallback(method = "recover")
    String overloaded(String id, Throwable failure) throws IOException;

    default String recover(String id, Throwable failure, FileNotFoundException e) {
        return "missing " + id;
    }

    default String recover(String id, Throwable failure, IllegalStateException e) {
        return "state " + id;
    }

    /** The fallback fused into the retry's give-up branch. */
    @Retry(maxAttempts = 2, delay = "0")
    @Fallback(method = "cached")
    String retried(String id, Throwable failure) throws IOException;

    /** A sibling that takes everything: always runs. */
    @Fallback(method = "anything")
    String total(String id, Throwable failure) throws IOException;

    default String anything(String id, Throwable failure, Throwable e) {
        return "anything " + id;
    }

    /** A handler class: always runs once {@code applyOn}/{@code skipOn} accept the failure. */
    @Fallback(value = Fixed.class, skipOn = IllegalArgumentException.class)
    String handled(String id, Throwable failure) throws IOException;

    @Fallback(method = "cachedAsync")
    CompletableFuture<String> loadAsync(String id, Throwable failure);

    default String cachedAsync(String id, Throwable failure, IOException e) {
        return "cached " + id;
    }

    @Retry(maxAttempts = 2, delay = "0")
    @Fallback(method = "cachedAsync")
    CompletableFuture<String> retriedAsync(String id, Throwable failure);

    @Fallback(value = Fixed.class, skipOn = IllegalArgumentException.class)
    CompletableFuture<String> handledAsync(String id, Throwable failure);

    /** Returns a fixed value. */
    final class Fixed implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "fixed";
        }
    }
}
