package dev.resilience4cata.spring.fixtures;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface: its implementation bean is wrapped with {@code InventoryClient$Resilient}. */
@Retry(maxAttempts = 3, delay = "100ms")
public interface InventoryClient {

    int stock(String sku);
}
