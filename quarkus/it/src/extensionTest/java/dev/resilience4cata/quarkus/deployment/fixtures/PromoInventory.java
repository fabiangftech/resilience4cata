package dev.resilience4cata.quarkus.deployment.fixtures;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;

/** An enabled alternative implementation: its wrapper must win over the wrapper of the bean it replaces. */
@Alternative
@Priority(10)
@Singleton
public class PromoInventory implements Inventory {

    @Override
    public int stock(String sku) {
        return -1;
    }
}
