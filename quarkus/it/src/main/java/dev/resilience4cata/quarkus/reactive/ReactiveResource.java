package dev.resilience4cata.quarkus.reactive;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.Flow;
import org.reactivestreams.Publisher;
import reactor.adapter.JdkFlowAdapter;

/**
 * The reactive beans over HTTP. Quarkus REST does not subscribe to Reactor's types, so each endpoint converts to a
 * {@code Uni} or a {@code Multi} (server-sent events), as the Quarkus guide shows. {@code failures} and {@code hang}
 * prepare the backend; {@code /calls} says how many times it was called since.
 */
@Path("/reactive")
@Produces(MediaType.TEXT_PLAIN)
public class ReactiveResource {

    @Inject
    QuoteService quotes;

    @Inject
    RatesClient rates;

    @Inject
    QuoteBackend backend;

    @GET
    @Path("/quotes/{sku}")
    public Uni<String> quote(@PathParam("sku") String sku, @QueryParam("failures") int failures) {
        backend.prepare(failures, false);
        return Uni.createFrom().publisher(flow(quotes.quote(sku)));
    }

    @GET
    @Path("/slow-quotes/{sku}")
    public Uni<String> slowQuote(@PathParam("sku") String sku) {
        backend.prepare(0, true);
        return Uni.createFrom().publisher(flow(quotes.slowQuote(sku)));
    }

    @GET
    @Path("/ticks/{sku}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> ticks(@PathParam("sku") String sku, @QueryParam("failures") int failures) {
        backend.prepare(failures, false);
        return Multi.createFrom().publisher(flow(quotes.ticks(sku)));
    }

    @GET
    @Path("/rates/{currency}")
    public Uni<String> rate(@PathParam("currency") String currency, @QueryParam("failures") int failures) {
        backend.prepare(failures, false);
        return Uni.createFrom().publisher(flow(rates.rate(currency)));
    }

    @GET
    @Path("/rate-ticks/{currency}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> rateTicks(@PathParam("currency") String currency, @QueryParam("failures") int failures) {
        backend.prepare(failures, false);
        return Multi.createFrom().publisher(flow(rates.rateTicks(currency)));
    }

    @GET
    @Path("/calls")
    public int calls() {
        return backend.calls.get();
    }

    private static <T> Flow.Publisher<T> flow(Publisher<T> publisher) {
        return JdkFlowAdapter.publisherToFlowPublisher(publisher);
    }
}
