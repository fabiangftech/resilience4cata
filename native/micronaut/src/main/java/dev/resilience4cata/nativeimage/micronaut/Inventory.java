package dev.resilience4cata.nativeimage.micronaut;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import jakarta.inject.Singleton;

/** An annotated class: Micronaut creates its generated subclass, {@code Inventory$Resilient}. */
@Singleton
public class Inventory {

    private final Warehouse warehouse;

    public Inventory(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    /** Three attempts by default; application.yml raises the instance "inventory" to four. */
    @Retry(name = "inventory", maxAttempts = 3, delay = "5ms")
    @CircuitBreaker(name = "inventory")
    public int stock(String sku) {
        return warehouse.stock(sku);
    }
}
