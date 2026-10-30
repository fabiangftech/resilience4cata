package dev.resilience4cata.micronaut.scopes;

import dev.resilience4cata.CallNotPermittedException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Calls the request-scoped bean twice per request and answers with what ran: the results, then the instance of
 * every attempt ({@code results | attempts}). On the blocking executor, as the retry pauses.
 */
@Controller("/scopes")
@Requires(property = "test.scopes", value = "web")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ScopesController {

    private final RequestQuotes quotes;
    private final Attempts attempts;

    public ScopesController(RequestQuotes quotes, Attempts attempts) {
        this.quotes = quotes;
        this.attempts = attempts;
    }

    @Get("/quotes/{sku}")
    String quotes(@PathVariable String sku, @QueryValue(defaultValue = "0") int failures) {
        attempts.prepare(failures);
        return quotes.quote(sku) + " " + quotes.quote(sku) + " | " + String.join(" ", attempts.instances());
    }

    @Get("/guarded-quotes/{sku}")
    String guardedQuote(@PathVariable String sku, @QueryValue(defaultValue = "0") int failures) {
        attempts.prepare(failures);
        return quotes.guardedQuote(sku);
    }

    @Error(exception = CallNotPermittedException.class)
    HttpResponse<String> open() {
        return HttpResponse.<String>status(HttpStatus.SERVICE_UNAVAILABLE).body("open");
    }

    @Error(exception = IllegalStateException.class)
    HttpResponse<String> down() {
        return HttpResponse.<String>status(HttpStatus.BAD_GATEWAY).body("down");
    }
}
