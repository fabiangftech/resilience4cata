package dev.resilience4cata.quarkus.deployment.load;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.concurrent.CompletionStage;

/** {@link LoadResource}'s endpoints, each request on its own virtual thread. */
@Path("/vload")
@RunOnVirtualThread
public class VirtualLoadResource {

    @Inject
    InventoryService inventory;

    @Inject
    ShippingClient shipping;

    @GET
    @Path("/reserve/{sku}")
    public int reserve(@PathParam("sku") String sku) {
        return inventory.reserve(sku);
    }

    @GET
    @Path("/queued/{sku}")
    public int queued(@PathParam("sku") String sku) {
        return inventory.queued(sku);
    }

    @GET
    @Path("/later/{sku}")
    public CompletionStage<Integer> later(@PathParam("sku") String sku) {
        return inventory.later(sku);
    }

    @GET
    @Path("/stock/{sku}")
    public int stock(@PathParam("sku") String sku) {
        return inventory.stock(sku);
    }

    @GET
    @Path("/quote/{sku}")
    public int quote(@PathParam("sku") String sku) {
        return inventory.quote(sku);
    }

    @GET
    @Path("/slow/{sku}")
    public int slow(@PathParam("sku") String sku) {
        return inventory.slow(sku);
    }

    @GET
    @Path("/ship/{id}")
    public int ship(@PathParam("id") String id) {
        return shipping.ship(id);
    }
}
