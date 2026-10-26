package dev.resilience4cata.quarkus.deployment.fixtures;

import jakarta.enterprise.context.Dependent;

/** A @Dependent implementation of an annotated interface: one delegate (and one wrapper) per injection. */
@Dependent
public class CountingInventory implements Inventory {

    @Override
    public int stock(String sku) {
        return System.identityHashCode(this);
    }
}
