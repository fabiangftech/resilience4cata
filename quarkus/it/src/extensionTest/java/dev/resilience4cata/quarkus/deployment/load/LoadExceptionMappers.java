package dev.resilience4cata.quarkus.deployment.load;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.RateLimitExceededException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Each rejection or failure of the load endpoints maps to its own status (outside a resource: for every resource). */
public class LoadExceptionMappers {

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
