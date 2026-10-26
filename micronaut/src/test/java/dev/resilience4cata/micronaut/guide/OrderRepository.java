package dev.resilience4cata.micronaut.guide;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** Fails the first {@link #failures} loads. */
@Singleton
@Requires(env = "guide")
public class OrderRepository {

    public final AtomicInteger failures = new AtomicInteger();

    public Order load(String id) {
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("database unavailable");
        }
        return new Order(id);
    }
}
