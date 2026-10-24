package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;

/** Policies with real-world delays, tested on a {@code ManualClock} without waiting for them. */
public interface TestkitClient {

    @Retry(maxAttempts = 4, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    String fetch(String key);

    @Retry(maxAttempts = 5, delay = "1s", jitter = Jitter.FULL)
    String jittered(String key);

    @CircuitBreaker(name = "inventory", windowSize = 2, minimumCalls = 2, waitInOpen = "30s", halfOpenCalls = 1)
    String stock(String sku);

    @Timeout("2s")
    String slow(String key);

    @Retry(maxAttempts = 3, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    CompletableFuture<String> fetchAsync(String key);

    @Timeout("2s")
    CompletableFuture<String> slowAsync(String key);
}
