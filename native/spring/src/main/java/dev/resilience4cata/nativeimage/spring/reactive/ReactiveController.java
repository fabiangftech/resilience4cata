package dev.resilience4cata.nativeimage.spring.reactive;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The reactive beans over Spring MVC, which subscribes to a {@code Mono} and streams a {@code Flux} as server-sent
 * events. {@code failures} prepares the backend; {@code /reactive/calls} says how many times it was called since.
 */
@RestController
@RequestMapping("/reactive")
public class ReactiveController {

    private final QuoteService quotes;
    private final RatesClient rates;
    private final QuoteBackend backend;

    public ReactiveController(QuoteService quotes, RatesClient rates, QuoteBackend backend) {
        this.quotes = quotes;
        this.rates = rates;
        this.backend = backend;
    }

    @GetMapping(path = "/quotes/{sku}", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> quote(@PathVariable String sku, @RequestParam(defaultValue = "0") int failures) {
        backend.prepare(failures, false);
        return quotes.quote(sku);
    }

    @GetMapping(path = "/slow-quotes/{sku}", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> slowQuote(@PathVariable String sku) {
        backend.prepare(0, true);
        return quotes.slowQuote(sku);
    }

    @GetMapping(path = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ticks(@PathVariable String sku, @RequestParam(defaultValue = "0") int failures) {
        backend.prepare(failures, false);
        return quotes.ticks(sku);
    }

    @GetMapping(path = "/rates/{currency}", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> rate(@PathVariable String currency, @RequestParam(defaultValue = "0") int failures) {
        backend.prepare(failures, false);
        return rates.rate(currency);
    }

    @GetMapping(path = "/rate-ticks/{currency}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> rateTicks(@PathVariable String currency, @RequestParam(defaultValue = "0") int failures) {
        backend.prepare(failures, false);
        return rates.rateTicks(currency);
    }

    @GetMapping("/calls")
    public int calls() {
        return backend.calls.get();
    }

    @GetMapping("/beans")
    public String beans() {
        return quotes.getClass().getName() + " " + rates.getClass().getName();
    }
}
