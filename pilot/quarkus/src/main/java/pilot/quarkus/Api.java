package pilot.quarkus;

import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import pilot.common.Probe;

@Path("/")
@RunOnVirtualThread
public class Api {

    @Inject
    Inventory inventory;

    @Inject
    ResilienceRegistry registry;

    @GET
    @Path("orders/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    public String order(@PathParam("id") String id) throws IOException, InterruptedException {
        return inventory.stock(id);
    }

    @GET
    @Path("quotes/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    public String quote(@PathParam("id") String id) throws IOException, InterruptedException {
        return inventory.quote(id);
    }

    @GET
    @Path("pilot/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public String stats() {
        return Probe.stats(registry.snapshot().toMap());
    }

    @ServerExceptionMapper
    public Response limited(RateLimitExceededException e) {
        return Response.status(429).entity("limited").build();
    }

    @ServerExceptionMapper
    public Response failed(IOException e) {
        return Response.status(502).entity("failed").build();
    }
}
