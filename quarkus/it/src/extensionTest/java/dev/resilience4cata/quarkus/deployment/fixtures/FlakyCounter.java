package dev.resilience4cata.quarkus.deployment.fixtures;

import java.util.concurrent.atomic.AtomicInteger;

/** Fails the next calls it is told to; counts the calls. */
public final class FlakyCounter {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    public void failNext(int count) {
        failures.set(count);
    }

    public int calls() {
        return calls.get();
    }

    public void call() {
        calls.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("flaky");
        }
    }
}
