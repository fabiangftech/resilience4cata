package dev.resilience4cata.spring.broken;

import dev.resilience4cata.spring.fixtures.InventoryClient;

/** R4C-SPR-003: two unrelated annotated interfaces, so no single wrapper fits. */
public class TwoFacedClient implements InventoryClient, CatalogClient {

    @Override
    public int stock(String sku) {
        return 1;
    }

    @Override
    public String describe(String sku) {
        return sku;
    }
}
