package dev.resilience4cata.examples.docs.configuration;

import dev.resilience4cata.annotation.CircuitBreaker;

/** The locked client of docs/{en,es}/guides/configuration.md#locking-an-instance. */
// tag::configuration-locked-client[]
public interface PaymentsClient {

    @CircuitBreaker(name = "payments", failureRateThreshold = 30, locked = true)
    String charge(String order);
}
// end::configuration-locked-client[]
