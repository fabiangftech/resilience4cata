package dev.resilience4cata.spring.guide;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Repository;

/** Stands for a database: fails the first {@link #failures} loads. */
@Repository
public class OrderRepository {

    final AtomicInteger loads = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();

    public Order load(String id) {
        loads.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("database unavailable");
        }
        return new Order(id);
    }
}
