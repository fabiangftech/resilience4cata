package dev.resilience4cata.quarkus.deployment.load;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.RateLimitExceededException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.CompletionStage;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** One endpoint per protected method (blocking ones on worker threads); each rejection or failure maps to a status. */
@Path("/load")
public class LoadResource {

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

    @ServerExceptionMapper
    public Response full(BulkheadFullException e) {
        return Response.status(503).entity("full").build();
    }

    @ServerExceptionMapper
    public Response open(CallNotPermittedException e) {
        return Response.status(503).entity("open").build();
    }

    @ServerExceptionMapper
    public Response limited(RateLimitExceededException e) {
        return Response.status(429).entity("limited").build();
    }

    @ServerExceptionMapper
    public Response timedOut(CallTimeoutException e) {
        return Response.status(504).entity("timed out").build();
    }

    @ServerExceptionMapper
    public Response failed(IllegalStateException e) {
        return Response.status(502).entity("failed").build();
    }
}
