package dev.resilience4cata.micronaut.load;

import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;

/** The implementation that is replaced by {@code ShippingClient$Resilient}. */
@ResilientBean(ShippingClient.class)
@Requires(env = "load")
public class WarehouseShippingClient implements ShippingClient {

    private final Warehouse warehouse;

    public WarehouseShippingClient(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    @Override
    public int ship(String id) {
        return warehouse.work(id);
    }
}
