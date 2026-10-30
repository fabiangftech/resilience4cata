package dev.resilience4cata.spring.scopes;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/** One instance per request, reached through the wrapper around its scoped proxy. */
@Component
@RequestScope
public class RequestTrace implements TraceClient {

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final String id = "trace-" + INSTANCES.incrementAndGet();
    private final Attempts attempts;

    public RequestTrace(Attempts attempts) {
        this.attempts = attempts;
    }

    @Override
    public String trace() {
        attempts.attempt(id);
        return id;
    }
}
