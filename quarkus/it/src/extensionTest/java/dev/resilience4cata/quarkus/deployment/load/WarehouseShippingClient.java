package dev.resilience4cata.quarkus.deployment.load;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The implementation that the extension wraps with {@code ShippingClient$Resilient}. */
@ApplicationScoped
public class WarehouseShippingClient implements ShippingClient {

    @Inject
    Warehouse warehouse;

    @Override
    public int ship(String id) {
        return warehouse.work(id);
    }
}
