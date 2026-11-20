package dev.resilience4cata.nativeimage.plainjava;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** The implementation behind {@link Inventory}: fails the next {@link #failures} calls, and counts every call. */
public class Warehouse implements Inventory {

    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    private void maybeFail() {
        calls.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new OutOfStockException("out of stock");
        }
    }

    @Override
    public int stock(String sku) {
        maybeFail();
        return sku.length();
    }

    @Override
    public int reserve(String sku) {
        maybeFail();
        return sku.length();
    }

    @Override
    public int count(String sku) {
        maybeFail();
        return 1;
    }

    @Override
    public String slow(long millis) throws InterruptedException {
        Thread.sleep(millis);
        return "done";
    }

    @Override
    public CompletableFuture<String> later(CompletableFuture<String> answer) {
        return answer;
    }

    @Override
    public CompletableFuture<String> hedged(String sku) {
        // The first attempt never answers; the hedge, started 20 ms later, answers at once.
        return calls.incrementAndGet() == 1 ? new CompletableFuture<>() : CompletableFuture.completedFuture(sku);
    }

    @Override
    public String alone(CountDownLatch inside, CountDownLatch release) throws InterruptedException {
        inside.countDown();
        release.await();
        return "alone";
    }

    @Override
    public String limited() {
        return "ok";
    }
}
