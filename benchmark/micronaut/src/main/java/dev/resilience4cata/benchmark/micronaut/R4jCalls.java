package dev.resilience4cata.benchmark.micronaut;

import io.github.resilience4j.micronaut.annotation.Bulkhead;
import io.github.resilience4j.micronaut.annotation.CircuitBreaker;
import io.github.resilience4j.micronaut.annotation.RateLimiter;
import io.github.resilience4j.micronaut.annotation.Retry;
import io.github.resilience4j.micronaut.annotation.TimeLimiter;
import jakarta.inject.Singleton;
import java.util.concurrent.CompletableFuture;

/**
 * resilience4j-micronaut on a class: Micronaut's compile-time AOP proxy runs its interceptors. The instances are
 * configured in {@code application.properties} to match {@link CataCalls}.
 */
@Singleton
public class R4jCalls {

    @Retry(name = "retry")
    public String retry() {
        return "ok";
    }

    @Retry(name = "retry")
    public String retryOnce(Flip flip) {
        flip.attempt();
        return "ok";
    }

    @CircuitBreaker(name = "breaker")
    public String circuitBreaker() {
        return "ok";
    }

    @CircuitBreaker(name = "open")
    public String open() {
        return "ok";
    }

    @Bulkhead(name = "bulkhead")
    public String bulkhead() {
        return "ok";
    }

    @RateLimiter(name = "limiter")
    public String rateLimiter() {
        return "ok";
    }

    @TimeLimiter(name = "timeout")
    public CompletableFuture<String> timeoutAsync() {
        return PlainCalls.DONE;
    }

    @Retry(name = "stack")
    @CircuitBreaker(name = "stack")
    @RateLimiter(name = "stack")
    @Bulkhead(name = "stack")
    public String stack() {
        return "ok";
    }
}
