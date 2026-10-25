package dev.resilience4cata.examples.docs.circuitbreaker;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.WindowType;
import java.util.concurrent.CompletableFuture;

/** A tuned breaker: time window, slow calls and ignored exceptions. */
public interface LedgerApi {

    // tag::circuit-breaker-tuned[]
    @CircuitBreaker(windowType = WindowType.TIME, windowSize = 60, minimumCalls = 5,
            slowCallDuration = "2s", slowCallRateThreshold = 80,
            ignoreOn = IllegalArgumentException.class)
    long balance(String account);
    // end::circuit-breaker-tuned[]

    // tag::circuit-breaker-async[]
    @CircuitBreaker(name = "ledger-async", windowSize = 4, minimumCalls = 4)
    CompletableFuture<Long> balanceAsync(String account);
    // end::circuit-breaker-async[]
}
