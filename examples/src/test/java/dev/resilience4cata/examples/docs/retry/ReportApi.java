package dev.resilience4cata.examples.docs.retry;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;

/** A retry with its own abortOn around a circuit breaker: the open breaker still stops the retries. */
public interface ReportApi {

    // tag::retry-open-breaker[]
    @Retry(maxAttempts = 5, delay = "1s", abortOn = IllegalArgumentException.class)
    @CircuitBreaker(windowSize = 2, minimumCalls = 2, waitInOpen = "1m")
    String report(String id);
    // end::retry-open-breaker[]
}
