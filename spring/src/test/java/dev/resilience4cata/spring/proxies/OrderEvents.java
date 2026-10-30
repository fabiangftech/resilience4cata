package dev.resilience4cata.spring.proxies;

import dev.resilience4cata.annotation.Retry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;

/** An event listener of an annotated class: Spring invokes the generated override, so the handling is retried. */
public class OrderEvents {

    /** The event. */
    public record OrderPlaced(String id) {
    }

    public final List<String> handled = new CopyOnWriteArrayList<>();
    private final Backend backend;

    public OrderEvents(Backend backend) {
        this.backend = backend;
    }

    @EventListener
    @Retry(maxAttempts = 3, delay = "1ms")
    public void on(OrderPlaced order) {
        handled.add(backend.call(order.id()));
    }
}
