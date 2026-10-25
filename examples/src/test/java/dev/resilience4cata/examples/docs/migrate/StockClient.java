package dev.resilience4cata.examples.docs.migrate;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;

// tag::migrate-r4c-interface[]
public interface StockClient {

    @Fallback(method = "unknownStock")
    @Retry(name = "inventory", maxAttempts = 3, delay = "500ms", backoff = Backoff.FIXED, jitter = Jitter.NONE,
            retryOn = IOException.class)
    @CircuitBreaker(name = "inventory", failureRateThreshold = 50, windowSize = 20, minimumCalls = 10,
            waitInOpen = "30s", halfOpenCalls = 3)
    @Timeout("2s")
    int stock(String sku) throws IOException;

    default int unknownStock(String sku, Exception failure) {
        return -1;
    }
}
// end::migrate-r4c-interface[]
