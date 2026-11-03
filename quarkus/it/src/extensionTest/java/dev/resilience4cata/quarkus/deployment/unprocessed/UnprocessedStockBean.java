package dev.resilience4cata.quarkus.deployment.unprocessed;

import jakarta.inject.Singleton;

/** A bean implementing {@link UnprocessedStock}. */
@Singleton
public class UnprocessedStockBean implements UnprocessedStock {

    @Override
    public int stock(String sku) {
        return 7;
    }
}
