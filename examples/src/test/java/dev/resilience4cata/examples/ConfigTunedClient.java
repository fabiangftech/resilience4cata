package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;

/** A client whose annotated values are retuned by external configuration in {@link ConfigEndToEndTest}. */
public interface ConfigTunedClient {

    @Retry(maxAttempts = 2, delay = "1ms", jitter = Jitter.NONE)
    String quote(String sku);

    @Retry(name = "config-orders", config = "external")
    String order(int id);

    @CircuitBreaker(name = "config-shipping", minimumCalls = 50)
    String ship(int id);

    @Retry(maxAttempts = 3, delay = "1ms", jitter = Jitter.NONE)
    String untouched();
}
