package dev.resilience4cata.examples;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Fails a given number of times, then succeeds. */
final class Flaky {
    private final AtomicInteger calls = new AtomicInteger();
    private final int failures;
    private final Supplier<RuntimeException> failure;

    Flaky(int failures) {
        this(failures, () -> new IllegalStateException("temporary failure"));
    }

    Flaky(int failures, Supplier<RuntimeException> failure) {
        this.failures = failures;
        this.failure = failure;
    }

    void call() {
        if (calls.incrementAndGet() <= failures) {
            throw failure.get();
        }
    }

    int calls() {
        return calls.get();
    }
}
