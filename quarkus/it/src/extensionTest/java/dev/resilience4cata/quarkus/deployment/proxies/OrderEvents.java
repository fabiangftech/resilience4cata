package dev.resilience4cata.quarkus.deployment.proxies;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A retried observer method of an annotated class. */
@Singleton
public class OrderEvents {

    public record OrderPlaced(String id) {
    }

    public final List<String> handled = new CopyOnWriteArrayList<>();

    @Inject
    Backend backend;

    @Retry(maxAttempts = 3, delay = "1ms")
    public void on(@Observes OrderPlaced event) {
        handled.add(backend.call(event.id()));
    }
}
