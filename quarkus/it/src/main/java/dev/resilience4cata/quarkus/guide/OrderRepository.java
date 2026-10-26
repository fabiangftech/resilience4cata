package dev.resilience4cata.quarkus.guide;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands for a database: fails the next loads it is told to. State is read and written through methods: a
 * normal-scoped bean is injected as a client proxy, whose own fields are not the bean's.
 */
@ApplicationScoped
public class OrderRepository {

    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    public void failNext(int count) {
        failures.set(count);
    }

    public int loads() {
        return loads.get();
    }

    public Order load(String id) {
        loads.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("database unavailable");
        }
        return new Order(id);
    }
}
