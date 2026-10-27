package dev.resilience4cata.micronaut.usability;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** How many of the next calls fail, and how many ran: shared by the usability fixtures of a context. */
@Singleton
@Requires(property = "test.usability")
public class Failures {

    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    public void failNext(int count) {
        failures.set(count);
    }

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
