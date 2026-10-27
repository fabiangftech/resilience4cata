package dev.resilience4cata.quarkus.usability.fixtures;

import java.util.concurrent.atomic.AtomicInteger;

/** Counts calls and fails the next ones it is told to: shared state for the fixtures, changed through methods. */
public final class Flaky {

    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    public void call() {
        calls.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("flaky");
        }
    }

    public void failNext(int count) {
        failures.set(count);
    }

    public int calls() {
        return calls.get();
    }
}
