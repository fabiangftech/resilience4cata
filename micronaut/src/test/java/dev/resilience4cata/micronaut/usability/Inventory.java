package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface whose implementation ({@link WarehouseInventory}) has annotations of its own. */
public interface Inventory {

    @Retry(maxAttempts = 3)
    int count(String sku);
}
