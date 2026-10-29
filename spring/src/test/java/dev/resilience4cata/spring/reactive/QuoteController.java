package dev.resilience4cata.spring.reactive;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The endpoints: each returns the protected bean's {@code Mono} or {@code Flux} as it is (streams as server-sent events). */
@RestController
public class QuoteController {

    private final QuoteService quotes;
    private final RatesClient rates;

    public QuoteController(QuoteService quotes, RatesClient rates) {
        this.quotes = quotes;
        this.rates = rates;
    }

    @GetMapping("/quotes/{sku}")
    Mono<String> quote(@PathVariable String sku) {
        return quotes.quote(sku);
    }

    @GetMapping("/cached-quotes/{sku}")
    Mono<String> quoteOrCached(@PathVariable String sku) {
        return quotes.quoteOrCached(sku);
    }

    @GetMapping("/slow-quotes/{sku}")
    Mono<String> slowQuote(@PathVariable String sku) {
        return quotes.slowQuote(sku);
    }

    @GetMapping("/rates/{currency}")
    Mono<String> rate(@PathVariable String currency) {
        return rates.rate(currency);
    }

    // tag::spring-reactive-endpoint[]
    @GetMapping(value = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> ticks(@PathVariable String sku) {
        return quotes.ticks(sku);   // the bean's Flux as it is: server-sent events
    }
    // end::spring-reactive-endpoint[]

    @GetMapping(value = "/rate-ticks/{currency}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> rateTicks(@PathVariable String currency) {
        return rates.rateTicks(currency);
    }

    @ExceptionHandler(CallTimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    String timedOut(CallTimeoutException e) {
        return "timed out";
    }

    @ExceptionHandler({CallNotPermittedException.class, dev.resilience4cata.BulkheadFullException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    String rejected(RuntimeException e) {
        return "rejected";
    }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    String failed(IOException e) {
        return "failed";
    }
}
