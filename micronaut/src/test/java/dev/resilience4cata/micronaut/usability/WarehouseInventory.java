package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Timeout;
import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Follows the interface section of the guide ({@code @ResilientBean}) while also annotating a method of the class:
 * the class becomes an annotated class too, whose generated subclass already applies the interface's policies.
 */
@Singleton
@ResilientBean(Inventory.class)
@Requires(property = "test.usability", value = "both-styles")
public class WarehouseInventory implements Inventory {

    private final Failures failures;

    public WarehouseInventory(Failures failures) {
        this.failures = failures;
    }

    @Override
    @Timeout("5s")
    public int count(String sku) {
        failures.call();
        return 3;
    }
}
