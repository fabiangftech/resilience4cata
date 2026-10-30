package dev.resilience4cata.micronaut.proxies;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A retried event listener of an annotated class. */
@Singleton
@Requires(env = "proxies")
public class OrderEvents {

    public record OrderPlaced(String id) {
    }

    public final List<String> handled = new CopyOnWriteArrayList<>();
    private final Backend backend;

    public OrderEvents(Backend backend) {
        this.backend = backend;
    }

    @EventListener
    @Retry(maxAttempts = 3, delay = "1ms")
    public void on(OrderPlaced event) {
        handled.add(backend.call(event.id()));
    }
}
