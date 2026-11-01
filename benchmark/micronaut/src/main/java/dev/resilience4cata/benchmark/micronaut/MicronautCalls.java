package dev.resilience4cata.benchmark.micronaut;

import io.micronaut.retry.annotation.CircuitBreaker;
import io.micronaut.retry.annotation.Retryable;
import jakarta.inject.Singleton;

/**
 * Micronaut's own annotations (micronaut-retry): a retry and a circuit breaker, nothing else. {@code attempts}
 * counts retries, so 2 here is 3 attempts in all, as for the others. Micronaut's breaker is a retry that opens
 * after its retries are exhausted, with no window; it is closer to {@code stack}'s retry and breaker than to a
 * window-based breaker, so it is compared on the success path only.
 */
@Singleton
public class MicronautCalls {

    @Retryable(attempts = "2", delay = "0ms")
    public String retry() {
        return "ok";
    }

    @Retryable(attempts = "2", delay = "0ms")
    public String retryOnce(Flip flip) {
        flip.attempt();
        return "ok";
    }

    @CircuitBreaker(attempts = "2", delay = "0ms", reset = "60s")
    public String circuitBreaker() {
        return "ok";
    }
}
