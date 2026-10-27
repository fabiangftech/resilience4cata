package dev.resilience4cata.examples.docs.policies;

import static dev.resilience4cata.annotation.Layer.CIRCUIT_BREAKER;
import static dev.resilience4cata.annotation.Layer.RETRY;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;

/** The breaker outside the retry: it records the outcome of the whole operation, once, not of every attempt. */
public interface SettlementApi {

    @PolicyOrder({CIRCUIT_BREAKER, RETRY})
    @CircuitBreaker(name = "settlement")
    @Retry(maxAttempts = 3, delay = "100ms", jitter = Jitter.NONE)
    String settle(String id);
}
