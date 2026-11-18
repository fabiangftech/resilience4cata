package dev.resilience4cata.nativeimage.micronaut;

import jakarta.inject.Singleton;

/** The implementation, exposed as {@code Prices}: native:micronaut's RemotePrices without {@code @ResilientBean}. */
@Singleton
public class PlainRemotePrices implements Prices {

    private final Warehouse warehouse;

    public PlainRemotePrices(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    @Override
    public String price(String sku) {
        return sku + ": " + warehouse.stock(sku) * 10;
    }
}
