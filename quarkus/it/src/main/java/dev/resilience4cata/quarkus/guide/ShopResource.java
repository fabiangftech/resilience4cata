package dev.resilience4cata.quarkus.guide;

import dev.resilience4cata.ResilienceRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/** Calls every protected bean over HTTP: what the native-image smoke build exercises. */
@Path("/shop")
public class ShopResource {

    @Inject
    OrderService orders;

    @Inject
    OrderRepository repository;

    @Inject
    Checkout checkout;

    @Inject
    StockReport stock;

    @Inject
    ResilienceRegistry registry;

    /** Loads an order whose first load fails: retried by the generated subclass. */
    @GET
    @Path("/order/{id}")
    public String order(@PathParam("id") String id) {
        repository.failNext(1);
        return orders.find(id).id();
    }

    @GET
    @Path("/pay/{order}")
    public String pay(@PathParam("order") String order) {
        return checkout.pay(order);
    }

    /** A stock line whose first request fails with a 503: retried by the REST client's wrapper. */
    @GET
    @Path("/stock/{sku}")
    public String stock(@PathParam("sku") String sku) {
        StockResource.FAILURES.set(1);
        return stock.line(sku);
    }

    /** The policy instances, as the registry's snapshot sees them. */
    @GET
    @Path("/instances")
    public String instances() {
        return String.valueOf(registry.snapshot().circuitBreakers().size() + registry.snapshot().retries().size());
    }
}
