package dev.resilience4cata.quarkus.deployment.fixtures;

import jakarta.enterprise.context.ApplicationScoped;

/** R4C-QK-003: two unrelated annotated interfaces. */
@ApplicationScoped
public class TwoFacedCatalog implements Pricing, Inventory {

    @Override
    public int price(String sku) {
        return 1;
    }

    @Override
    public int stock(String sku) {
        return 2;
    }
}
