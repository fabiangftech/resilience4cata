package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** A @Singleton annotated class with a qualifier and an @Inject constructor. */
@Singleton
@Named("greeter")
public class NamedGreeter {

    private final Helper helper;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    @Inject
    public NamedGreeter(Helper helper) {
        this.helper = helper;
    }

    @Retry(maxAttempts = 3, delay = "0ms")
    public String greet(String name) {
        calls.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("flaky");
        }
        return helper.name() + " greets " + name;
    }

    public void failNext(int count) {
        failures.set(count);
    }

    public int calls() {
        return calls.get();
    }
}
