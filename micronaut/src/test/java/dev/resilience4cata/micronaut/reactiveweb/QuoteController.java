package dev.resilience4cata.micronaut.reactiveweb;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.sse.Event;
import java.io.IOException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The endpoints: each returns the protected bean's {@code Mono} as it is, or its {@code Flux} as server-sent events. */
@Controller
@Requires(env = "reactiveweb")
public class QuoteController {

    private final QuoteService quotes;
    private final RatesClient rates;

    public QuoteController(QuoteService quotes, RatesClient rates) {
        this.quotes = quotes;
        this.rates = rates;
    }

    @Get(uri = "/quotes/{sku}", produces = MediaType.TEXT_PLAIN)
    Mono<String> quote(String sku) {
        return quotes.quote(sku);
    }

    @Get(uri = "/cached-quotes/{sku}", produces = MediaType.TEXT_PLAIN)
    Mono<String> quoteOrCached(String sku) {
        return quotes.quoteOrCached(sku);
    }

    @Get(uri = "/slow-quotes/{sku}", produces = MediaType.TEXT_PLAIN)
    Mono<String> slowQuote(String sku) {
        return quotes.slowQuote(sku);
    }

    @Get(uri = "/rates/{currency}", produces = MediaType.TEXT_PLAIN)
    Mono<String> rate(String currency) {
        return rates.rate(currency);
    }

    // tag::micronaut-reactive-endpoint[]
    @Get(uri = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM)
    Flux<Event<String>> ticks(String sku) {
        return quotes.ticks(sku).map(Event::of);   // Micronaut frames server-sent events from Event<T>
    }
    // end::micronaut-reactive-endpoint[]

    @Get(uri = "/rate-ticks/{currency}", produces = MediaType.TEXT_EVENT_STREAM)
    Flux<Event<String>> rateTicks(String currency) {
        return rates.rateTicks(currency).map(Event::of);
    }

    static final java.util.concurrent.atomic.AtomicInteger PLAIN_CANCELLED = new java.util.concurrent.atomic.AtomicInteger();

    @Get(uri = "/feed/{sku}", produces = MediaType.TEXT_EVENT_STREAM)
    Flux<Event<String>> feed(String sku) {
        return quotes.feed(sku).map(Event::of);
    }

    @Error(exception = CallTimeoutException.class)
    HttpResponse<String> timedOut() {
        return HttpResponse.<String>status(HttpStatus.GATEWAY_TIMEOUT).body("timed out");
    }

    @Error(exception = CallNotPermittedException.class)
    HttpResponse<String> open() {
        return HttpResponse.<String>status(HttpStatus.SERVICE_UNAVAILABLE).body("rejected");
    }

    @Error(exception = BulkheadFullException.class)
    HttpResponse<String> full() {
        return HttpResponse.<String>status(HttpStatus.SERVICE_UNAVAILABLE).body("rejected");
    }

    @Error(exception = IOException.class)
    HttpResponse<String> failed() {
        return HttpResponse.<String>status(HttpStatus.BAD_GATEWAY).body("failed");
    }
}
