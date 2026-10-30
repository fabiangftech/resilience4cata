package dev.resilience4cata.spring.scopes;

import dev.resilience4cata.CallNotPermittedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calls the scoped beans twice per request where it matters, and answers with what ran: the results, then the
 * instance of every attempt ({@code results | attempts}).
 */
@RestController
public class ScopesController {

    private final RequestQuotes quotes;
    private final TraceClient trace;
    private final CartClient cart;
    private final Attempts attempts;

    public ScopesController(RequestQuotes quotes, TraceClient trace, CartClient cart, Attempts attempts) {
        this.quotes = quotes;
        this.trace = trace;
        this.cart = cart;
        this.attempts = attempts;
    }

    @GetMapping("/scopes/quotes/{sku}")
    public String quotes(@PathVariable String sku, @RequestParam(defaultValue = "0") int failures) {
        attempts.prepare(failures);
        return quotes.quote(sku) + " " + quotes.quote(sku) + " | " + String.join(" ", attempts.instances());
    }

    @GetMapping("/scopes/guarded-quotes/{sku}")
    public String guardedQuote(@PathVariable String sku, @RequestParam(defaultValue = "0") int failures) {
        attempts.prepare(failures);
        return quotes.guardedQuote(sku);
    }

    @GetMapping("/scopes/trace")
    public String trace(@RequestParam(defaultValue = "0") int failures) {
        attempts.prepare(failures);
        return trace.trace() + " " + trace.trace() + " | " + String.join(" ", attempts.instances());
    }

    @GetMapping("/scopes/cart/add/{item}")
    public String add(@PathVariable String item, @RequestParam(defaultValue = "0") int failures) {
        attempts.prepare(failures);
        return cart.add(item) + " | " + String.join(" ", attempts.instances());
    }

    @GetMapping("/scopes/cart")
    public String items() {
        return String.join(",", cart.items());
    }

    @ExceptionHandler(CallNotPermittedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String open(CallNotPermittedException e) {
        return "open";
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String down(IllegalStateException e) {
        return "down";
    }
}
