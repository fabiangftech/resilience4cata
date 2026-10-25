package dev.resilience4cata.examples.docs.configuration;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

/** The client that the properties file of docs/{en,es}/guides/configuration.md retunes. */
// tag::configuration-client[]
public interface ShippingClient {

    @Retry(config = "external")
    @CircuitBreaker(name = "shipping", config = "external", minimumCalls = 20)
    String track(String parcel);

    @Retry(maxAttempts = 2)
    @Timeout("1s")
    String cancel(String parcel);
}
// end::configuration-client[]
