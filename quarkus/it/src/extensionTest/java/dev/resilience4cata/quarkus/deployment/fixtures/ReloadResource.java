package dev.resilience4cata.quarkus.deployment.fixtures;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.concurrent.CompletionStage;

/** The endpoints of ReloadTest. */
@Path("/reload")
public class ReloadResource {

    @Inject
    ReloadService service;

    @GET
    @Path("/attempts")
    public String attempts() {
        return service.attempts();
    }

    @GET
    @Path("/loader")
    public CompletionStage<String> loader() {
        return service.loaderOfALaterAttempt();
    }
}
