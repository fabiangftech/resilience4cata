package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/** A Quarkus REST resource with a retried endpoint and no scope annotation (Quarkus makes resources singletons). */
@Path("/orders")
public class OrdersResource {

    private static final Flaky FLAKY = new Flaky();

    @GET
    @Path("/{id}")
    @Retry(maxAttempts = 3, delay = "0ms")
    public String get(@PathParam("id") String id) {
        FLAKY.call();
        return "order " + id;
    }

    @GET
    @Path("/fail/{count}")
    public int failNext(@PathParam("count") int count) {
        FLAKY.failNext(count);
        return FLAKY.calls();
    }
}
