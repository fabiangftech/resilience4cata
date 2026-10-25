package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Retry;

/** Two methods share one retry instance by name but configure it differently: R4C-REG-008 when wrapped. */
public interface SharedNameClient {

    @Retry(name = "backend", maxAttempts = 3)
    String stock(String sku);

    @Retry(name = "backend", maxAttempts = 5)
    String reserve(String sku);
}
