package com.example.consumer;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** An annotated interface, wrapped with {@code Resilient.of}. The retry's attempts come from resilience4cata.properties. */
public interface Inventory {

    @Retry(config = "inventory")
    @CircuitBreaker(name = "inventory")
    @Timeout("2s")
    @Fallback(method = "unknown")
    int stock(String sku) throws IOException;

    @Retry(maxAttempts = 2, delay = "1ms")
    @Timeout("2s")
    CompletableFuture<Integer> stockAsync(String sku);

    default int unknown(String sku, Exception failure) {
        return -1;
    }
}
