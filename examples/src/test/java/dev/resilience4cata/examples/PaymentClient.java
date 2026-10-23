package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Retry;

/** Type-level policy through a composed annotation, overridden attribute by attribute on one method. */
@ExternalCall
public interface PaymentClient {

    String charge(String order);

    @Retry(maxAttempts = 2)
    String refund(String order);
}
