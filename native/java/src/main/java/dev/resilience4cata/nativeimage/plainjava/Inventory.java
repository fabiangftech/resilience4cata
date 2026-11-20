package dev.resilience4cata.nativeimage.plainjava;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * A wrapped interface. {@code Resilient.of} loads {@code Inventory$Resilient} by name, which works in a native image
 * only through the reachability metadata the processor writes next to it.
 */
public interface Inventory {

    @Retry(maxAttempts = 3, delay = "1ms")
    @Fallback(method = "unknown")
    int stock(String sku);

    default int unknown(String sku, Exception e) {
        return -1;
    }

    /** Four attempts and {@code retryOn} from {@code resilience4cata.properties}. */
    @Retry(config = "inventory", delay = "1ms")
    int reserve(String sku);

    @CircuitBreaker(name = "warehouse", windowSize = 4, minimumCalls = 4, failureRateThreshold = 50, waitInOpen = "1h")
    int count(String sku);

    @Timeout("100ms")
    String slow(long millis) throws InterruptedException;

    @Timeout("100ms")
    CompletableFuture<String> later(CompletableFuture<String> answer);

    @Hedge(delay = "20ms", maxAttempts = 2)
    CompletableFuture<String> hedged(String sku);

    @Bulkhead(maxConcurrent = 1)
    String alone(CountDownLatch inside, CountDownLatch release) throws InterruptedException;

    @RateLimiter(permits = 2, period = "1h")
    String limited();
}
