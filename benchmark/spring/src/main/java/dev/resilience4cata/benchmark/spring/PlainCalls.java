package dev.resilience4cata.benchmark.spring;

import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/** The calls without any library: the baseline, a bean with no proxy. */
@Component
public class PlainCalls {

    static final CompletableFuture<String> DONE = CompletableFuture.completedFuture("ok");

    public String retry() {
        return "ok";
    }

    public String retryOnce(Flip flip) {
        try {
            flip.attempt();
        } catch (RuntimeException e) {
            flip.attempt();
        }
        return "ok";
    }

    public String circuitBreaker() {
        return "ok";
    }

    public String bulkhead() {
        return "ok";
    }

    public String rateLimiter() {
        return "ok";
    }

    public CompletableFuture<String> timeoutAsync() {
        return DONE;
    }

    public String stack() {
        return "ok";
    }
}
