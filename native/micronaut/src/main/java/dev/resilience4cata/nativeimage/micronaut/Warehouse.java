package dev.resilience4cata.nativeimage.micronaut;

import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** The backend: fails the next {@code failures} calls, and counts every call. */
@Singleton
public class Warehouse {

    final AtomicInteger failures = new AtomicInteger();
    final AtomicInteger calls = new AtomicInteger();

    public int stock(String sku) {
        calls.incrementAndGet();
        if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("warehouse down");
        }
        return sku.length();
    }
}
