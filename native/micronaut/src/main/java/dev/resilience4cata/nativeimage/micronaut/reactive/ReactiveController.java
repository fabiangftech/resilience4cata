package dev.resilience4cata.nativeimage.micronaut.reactive;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.sse.Event;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The reactive beans over HTTP, on the event loop: each endpoint returns the bean's {@code Mono} as it is, or its
 * {@code Flux} as server-sent events (Micronaut frames them from {@code Event<T>}). {@code failures} prepares the
 * backend; {@code /reactive/calls} says how many times it was called since.
 */
@Controller("/reactive")
public class ReactiveController {

    private final QuoteService quotes;
    private final RatesClient rates;
    private final QuoteBackend backend;

    public ReactiveController(QuoteService quotes, RatesClient rates, QuoteBackend backend) {
        this.quotes = quotes;
        this.rates = rates;
        this.backend = backend;
    }

    @Get(uri = "/quotes/{sku}", produces = MediaType.TEXT_PLAIN)
    public Mono<String> quote(String sku, @QueryValue(defaultValue = "0") int failures) {
        backend.prepare(failures, false);
        return quotes.quote(sku);
    }

    @Get(uri = "/slow-quotes/{sku}", produces = MediaType.TEXT_PLAIN)
    public Mono<String> slowQuote(String sku) {
        backend.prepare(0, true);
        return quotes.slowQuote(sku);
    }

    @Get(uri = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<String>> ticks(String sku, @QueryValue(defaultValue = "0") int failures) {
        backend.prepare(failures, false);
        return quotes.ticks(sku).map(Event::of);
    }

    @Get(uri = "/rates/{currency}", produces = MediaType.TEXT_PLAIN)
    public Mono<String> rate(String currency, @QueryValue(defaultValue = "0") int failures) {
        backend.prepare(failures, false);
        return rates.rate(currency);
    }

    @Get(uri = "/rate-ticks/{currency}", produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<String>> rateTicks(String currency, @QueryValue(defaultValue = "0") int failures) {
        backend.prepare(failures, false);
        return rates.rateTicks(currency).map(Event::of);
    }

    @Get(uri = "/calls", produces = MediaType.TEXT_PLAIN)
    public int calls() {
        return backend.calls.get();
    }

    @Get(uri = "/beans", produces = MediaType.TEXT_PLAIN)
    public String beans() {
        return quotes.getClass().getName() + " " + rates.getClass().getName();
    }
}
