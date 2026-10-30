package dev.resilience4cata.micronaut.scopes;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A singleton that the scoped beans report every attempt to, with the id of the instance that ran it. */
@Singleton
@Requires(property = "test.scopes")
public class Attempts {

    private final List<String> instances = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();

    void prepare(int failures) {
        instances.clear();
        this.failures.set(failures);
    }

    /** Records an attempt of {@code instance}, and fails it if the request asked for more failures. */
    void attempt(String instance) {
        instances.add(instance);
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("backend down");
        }
    }

    List<String> instances() {
        return List.copyOf(instances);
    }
}
