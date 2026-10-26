package dev.resilience4cata.quarkus.guide;

import dev.resilience4cata.annotation.Retry;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

// tag::quarkus-rest-client[]
@RegisterRestClient(configKey = "stock")
@Path("/stock")
@Retry(maxAttempts = 3, delay = "50ms")
public interface StockClient {

    @GET
    @Path("/{sku}")
    int available(@PathParam("sku") String sku);
}
// end::quarkus-rest-client[]
