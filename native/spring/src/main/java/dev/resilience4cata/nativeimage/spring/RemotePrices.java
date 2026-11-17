package dev.resilience4cata.nativeimage.spring;

import org.springframework.stereotype.Component;

/** The implementation the wrapper delegates to. */
@Component
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
