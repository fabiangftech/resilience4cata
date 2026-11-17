package dev.resilience4cata.nativeimage.spring;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import org.springframework.stereotype.Service;

/** An annotated class: Spring creates its generated subclass, {@code Inventory$Resilient}. */
@Service
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
