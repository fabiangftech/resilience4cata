package dev.resilience4cata.quarkus.deployment.reactiveweb;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Each rejection or failure maps to its own status. */
public class QuoteExceptionMappers {

    @ServerExceptionMapper
    public Response timedOut(CallTimeoutException e) {
        return Response.status(504).entity("timed out").build();
    }

    @ServerExceptionMapper
    public Response open(CallNotPermittedException e) {
        return Response.status(503).entity("rejected").build();
    }

    @ServerExceptionMapper
    public Response full(BulkheadFullException e) {
        return Response.status(503).entity("rejected").build();
    }

    @ServerExceptionMapper
    public Response failed(IOException e) {
        return Response.status(502).entity("failed").build();
    }
}
