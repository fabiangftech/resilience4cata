package dev.resilience4cata.benchmark.spring;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/**
 * resilience4j-spring-boot4 on a class: its aspects run in a CGLIB proxy. The instances are configured in
 * {@link SpringBenchmark#RESILIENCE4J} to match {@link CataCalls}. The aspects nest as Retry, CircuitBreaker,
 * RateLimiter, TimeLimiter, Bulkhead, the same order as resilience4cata's default.
 */
@Component
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
