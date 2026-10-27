package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;

/** A base class with a retried method, extended by the actual bean. */
public class BaseClient {

    public final AtomicInteger attempts = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    @Retry(maxAttempts = 3, delay = "10ms")
    public String fetch(String id) {
        attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("down");
        }
        return "fetched-" + id;
    }
}
