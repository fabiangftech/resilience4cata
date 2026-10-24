package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An annotated class: the processor generates {@code ClassOrderService$Resilient extends ClassOrderService},
 * created with {@code Resilient.create(ClassOrderService.class, registry, ...)}.
 */
public class ClassOrderService implements ClassOrderApi {

    final AtomicInteger statusCalls = new AtomicInteger();
    final AtomicInteger quoteCalls = new AtomicInteger();
    final AtomicInteger priceCalls = new AtomicInteger();
    final AtomicInteger inventoryCalls = new AtomicInteger();
    final AtomicInteger trackCalls = new AtomicInteger();
    private final String region;
    private final int failures;

    /** {@code failures}: how many calls of {@code status}, {@code quote} and {@code track} fail first. */
    public ClassOrderService(String region, int failures) {
        this.region = region;
        this.failures = failures;
    }

    public ClassOrderService() {
        this("eu", 0);
    }

    @Override
    @Retry(maxAttempts = 4)
    public String status(String id) {
        if (statusCalls.incrementAndGet() <= failures) {
            throw new IllegalStateException("status unavailable");
        }
        return region + ":" + id;
    }

    @Override
    public String quote(String id) {
        if (quoteCalls.incrementAndGet() <= failures) {
            throw new IllegalStateException("quote unavailable");
        }
        return "quote " + id;
    }

    /** Not annotated, so not overridden; its call to {@link #price} still goes through price's policies. */
    public String summary(String id) {
        return "summary of " + this.price(id);
    }

    @Retry(maxAttempts = 3, delay = "1ms", jitter = Jitter.NONE)
    @Fallback(method = "cachedPrice")
    public String price(String id) {
        priceCalls.incrementAndGet();
        throw new IllegalStateException("pricing down");
    }

    protected String cachedPrice(String id, IllegalStateException e) {
        return "cached price of " + id + " (" + e.getMessage() + ")";
    }

    @CircuitBreaker(windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    String inventory(String id) {
        inventoryCalls.incrementAndGet();
        throw new IllegalStateException("inventory down");
    }

    @Timeout("50ms")
    public String slow(String id) {
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException e) {
            throw new IllegalStateException("interrupted", e);
        }
        return id;
    }

    @Retry(maxAttempts = 3, delay = "1ms", jitter = Jitter.NONE)
    @Fallback(method = "lostParcel")
    public CompletableFuture<String> track(String id) {
        int call = trackCalls.incrementAndGet();
        if (id.equals("lost")) {
            return CompletableFuture.failedFuture(new IllegalStateException("no such parcel"));
        }
        return call <= failures ? CompletableFuture.failedFuture(new IllegalStateException("tracking down"))
                : CompletableFuture.supplyAsync(() -> "parcel " + id);
    }

    protected String lostParcel(String id) {
        return "lost " + id;
    }

    @Timeout("50ms")
    public CompletionStage<String> never() {
        return new CompletableFuture<>();
    }
}
