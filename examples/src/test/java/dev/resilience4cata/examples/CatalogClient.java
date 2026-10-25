package dev.resilience4cata.examples;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Fallbacks: a typed handler, sibling methods picked by exception type, and both combined with other policies. */
public interface CatalogClient {

    @Fallback(value = CachedPrice.class, skipOn = IllegalArgumentException.class)
    @Retry(maxAttempts = 3, delay = "0")
    long price(String sku, int quantity) throws IOException;

    @Fallback(method = "describeFallback")
    String describe(String sku) throws IOException;

    default String describeFallback(String sku, FileNotFoundException e) {
        return "unknown product " + sku;
    }

    default String describeFallback(String sku, IOException e) {
        return "description unavailable: " + e.getMessage();
    }

    @Fallback(method = "stockFallback")
    @CircuitBreaker
    int stock(String sku);

    // java:S1172: a fallback mirrors the protected method's parameters, then takes the failure, which selects it by type
    @SuppressWarnings("java:S1172")
    static int stockFallback(String sku, CallNotPermittedException e) {
        return -1;
    }

    @Fallback(Failing.class)
    void refresh(String sku) throws IOException;

    /** Answers from a cache, and records what it saw. */
    final class CachedPrice implements FallbackHandler<Long> {
        static volatile FallbackContext last;

        @Override
        public Long apply(FallbackContext context) {
            last = context;
            return 100L * context.argument(1, int.class);
        }
    }

    /** A fallback that fails with a checked exception. */
    final class Failing implements FallbackHandler<Void> {
        @Override
        public Void apply(FallbackContext context) throws IOException {
            throw new IOException("fallback failed too", context.failure());
        }
    }
}
