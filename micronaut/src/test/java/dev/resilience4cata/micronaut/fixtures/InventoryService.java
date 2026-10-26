package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * An annotated class that Micronaut creates itself: with {@code -Ar4c.inject=micronaut} the generated
 * {@code InventoryService$Resilient} is the {@code @Singleton} that replaces it, with field injection and
 * {@code @PostConstruct} inherited.
 */
@Singleton
public class InventoryService {

    @Inject
    Outage outage;

    private boolean started;

    @PostConstruct
    void start() {
        started = true;
    }

    @Retry(name = "inventory", maxAttempts = 3, delay = "10ms")
    public int stock(String sku) {
        outage.call();
        return 42;
    }

    /** Calls {@link #stock} on {@code this}: the generated subclass intercepts it. */
    public int twice(String sku) {
        return stock(sku) + stock(sku);
    }

    public boolean started() {
        return started;
    }
}
