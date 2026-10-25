package dev.resilience4cata.examples.docs.testing;

import dev.resilience4cata.annotation.Retry;

// tag::testing-junit-payment-interface[]
public interface PaymentService {

    @Retry(config = "payments") // attempts and delays come from the registry's "payments" config
    String charge(String order);
}
// end::testing-junit-payment-interface[]
