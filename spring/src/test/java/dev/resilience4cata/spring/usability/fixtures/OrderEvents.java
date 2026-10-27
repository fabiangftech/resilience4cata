package dev.resilience4cata.spring.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A retried application event listener with a condition on its parameter. */
@Component
public class OrderEvents {

    /** The event. */
    public record OrderPlaced(String id, boolean urgent) {
    }

    public final List<String> handled = new CopyOnWriteArrayList<>();
    public final AtomicInteger failures = new AtomicInteger();

    @Retry(maxAttempts = 3, delay = "10ms")
    @EventListener(condition = "#placed.urgent")
    public void onUrgent(OrderPlaced placed) {
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("down");
        }
        handled.add(placed.id());
    }
}
