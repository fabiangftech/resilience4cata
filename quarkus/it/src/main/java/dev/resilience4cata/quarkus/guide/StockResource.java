package dev.resilience4cata.quarkus.guide;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.ServerErrorException;
import java.util.concurrent.atomic.AtomicInteger;

/** The stock service that {@link StockClient} calls, served by the same application for the tests and the native build. */
@Path("/stock")
public class StockResource {

    /** Requests to answer with a 503 before answering. */
    public static final AtomicInteger FAILURES = new AtomicInteger();
    /** Requests served. */
    public static final AtomicInteger REQUESTS = new AtomicInteger();

    @GET
    @Path("/{sku}")
    public int available(@PathParam("sku") String sku) {
        REQUESTS.incrementAndGet();
        if (FAILURES.getAndDecrement() > 0) {
            throw new ServerErrorException(503);
        }
        return sku.length();
    }
}
