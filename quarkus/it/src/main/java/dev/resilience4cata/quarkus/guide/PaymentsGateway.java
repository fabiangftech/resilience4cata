package dev.resilience4cata.quarkus.guide;

import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

// tag::quarkus-interface[]
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface PaymentsGateway {

    String charge(String order, long cents);
}
// end::quarkus-interface[]
