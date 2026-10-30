package dev.resilience4cata.micronaut.load;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;

/** An annotated interface: its {@code @ResilientBean} implementation is wrapped. */
public interface ShippingClient {

    @Retry(name = "load-shipping", maxAttempts = 3, delay = "1ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    int ship(String id);
}
