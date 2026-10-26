package dev.resilience4cata.micronaut.fixtures;

import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** How many of the next calls of the fixtures fail, and how many ran: shared by the fixture beans of a context. */
@Singleton
public class Outage {

    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    /** The next {@code count} calls fail. */
    public void failNext(int count) {
        failures.set(count);
    }

    /** Counts a call, and throws if it is one of the failing ones. */
    public void call() {
        calls.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("backend down");
        }
    }

    public int calls() {
        return calls.get();
    }
}
