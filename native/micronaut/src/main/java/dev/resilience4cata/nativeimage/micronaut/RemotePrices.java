package dev.resilience4cata.nativeimage.micronaut;

import dev.resilience4cata.micronaut.ResilientBean;
import jakarta.inject.Singleton;

/** The implementation, exposed as {@code Prices} and replaced by {@code Prices$Resilient}. */
@Singleton
@ResilientBean(Prices.class)
public class RemotePrices implements Prices {

    private final Warehouse warehouse;

    public RemotePrices(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    @Override
    public String price(String sku) {
        return sku + ": " + warehouse.stock(sku) * 10;
    }
}
