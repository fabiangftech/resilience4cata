package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Timeout;

/** An annotated interface whose policies have derived instance ids (no {@code name}). */
public interface Shipping {

    @Timeout("2s")
    String track(String parcel);

    @CircuitBreaker(name = "shipping-quotes", minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "30s")
    int quote(String parcel);
}
