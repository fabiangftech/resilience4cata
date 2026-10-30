package dev.resilience4cata.quarkus.deployment.reactiveweb;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import reactor.adapter.JdkFlowAdapter;

/**
 * The endpoints: Quarkus REST does not subscribe to Reactor's types (it would write a {@code Mono} as an entity), so each
 * converts the protected bean's {@code Mono} to a {@code Uni} and its {@code Flux} to a {@code Multi} (server-sent
 * events), through Reactor's {@code JdkFlowAdapter} since Mutiny takes a {@code Flow.Publisher}; Mutiny subscribes once per request and cancels the subscription when the client goes away.
 */
@Path("/web")
public class QuoteResource {

    @Inject
    QuoteService quotes;

    @Inject
    RatesClient rates;

    // tag::quarkus-reactive-endpoint[]
    @GET
    @Path("/quotes/{sku}")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> quote(@PathParam("sku") String sku) {
        return Uni.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(quotes.quote(sku)));
    }

    @GET
    @Path("/ticks/{sku}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> ticks(@PathParam("sku") String sku) {
        return Multi.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(quotes.ticks(sku)));
    }
    // end::quarkus-reactive-endpoint[]

    @GET
    @Path("/cached-quotes/{sku}")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> quoteOrCached(@PathParam("sku") String sku) {
        return Uni.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(quotes.quoteOrCached(sku)));
    }

    @GET
    @Path("/slow-quotes/{sku}")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> slowQuote(@PathParam("sku") String sku) {
        return Uni.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(quotes.slowQuote(sku)));
    }

    @GET
    @Path("/rates/{currency}")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> rate(@PathParam("currency") String currency) {
        return Uni.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(rates.rate(currency)));
    }

    @GET
    @Path("/feed/{sku}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> feed(@PathParam("sku") String sku) {
        return Multi.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(quotes.feed(sku)));
    }

    @GET
    @Path("/rate-ticks/{currency}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> rateTicks(@PathParam("currency") String currency) {
        return Multi.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(rates.rateTicks(currency)));
    }
}
