package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Retry;

@Retry(maxAttempts = 2)
public interface Inventory {

    int stock(String sku);
}
