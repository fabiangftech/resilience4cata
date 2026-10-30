package dev.resilience4cata.quarkus.deployment.load;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An annotated class: ArC creates its generated subclass (field injection: the client proxy needs a no-arg constructor). One policy per method. */
@ApplicationScoped
public class InventoryService {

    private static final ExecutorService ASYNC = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    Warehouse warehouse;

    @Bulkhead(name = "load-bulkhead", maxConcurrent = 5)
    public int reserve(String sku) {
        return warehouse.work(sku);
    }

    @Bulkhead(name = "load-queue", maxConcurrent = 5, maxWait = "10s")
    public int queued(String sku) {
        return warehouse.work(sku);
    }

    @Timeout("10s")
    @Bulkhead(name = "load-async", maxConcurrent = 5, maxWait = "10s")
    public CompletableFuture<Integer> later(String sku) {
        return CompletableFuture.supplyAsync(() -> warehouse.work(sku), ASYNC);
    }

    @Retry(name = "load-stock", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    @CircuitBreaker(name = "load-stock", windowSize = 20, minimumCalls = 10, failureRateThreshold = 50,
            waitInOpen = "300ms", halfOpenCalls = 2)
    public int stock(String sku) {
        return warehouse.work(sku);
    }

    @RateLimiter(name = "load-limiter", permits = 50, period = "1h")
    public int quote(String sku) {
        return warehouse.work(sku);
    }

    @Timeout("100ms")
    public int slow(String sku) {
        return warehouse.work(sku);
    }
}
