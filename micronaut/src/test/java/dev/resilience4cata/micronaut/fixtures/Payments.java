package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface; its bean, {@link HttpPayments}, is wrapped by {@code Payments$Resilient}. */
public interface Payments {

    @Retry(name = "payments", maxAttempts = 3, delay = "100ms")
    int charge(int cents);
}
