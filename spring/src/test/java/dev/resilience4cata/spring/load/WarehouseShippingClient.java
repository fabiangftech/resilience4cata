package dev.resilience4cata.spring.load;

import org.springframework.stereotype.Component;

/** The implementation that Spring wraps with {@code ShippingClient$Resilient}. */
@Component
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
