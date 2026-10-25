package dev.resilience4cata.examples.docs.testing;

import dev.resilience4cata.annotation.CircuitBreaker;

// tag::testing-breaker-interface[]
public interface InventoryService {

    @CircuitBreaker(name = "inventory", windowSize = 4, minimumCalls = 4, waitInOpen = "30s", halfOpenCalls = 1)
    int stock(String sku);
}
// end::testing-breaker-interface[]
