package dev.resilience4cata.spring.scopes;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/** An annotated class in the request scope: one generated-subclass instance per request, behind a scoped proxy. */
@Component
@RequestScope
public class RequestQuotes {

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final String id = "quotes-" + INSTANCES.incrementAndGet();
    private final Attempts attempts;

    public RequestQuotes(Attempts attempts) {
        this.attempts = attempts;
    }

    @Retry(maxAttempts = 3, delay = "5ms")
    public String quote(String sku) {
        attempts.attempt(id);
        return id + ":" + sku;
    }

    @CircuitBreaker(name = "request-quotes", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50,
            waitInOpen = "1h")
    public String guardedQuote(String sku) {
        attempts.attempt(id);
        return id + ":" + sku;
    }
}
