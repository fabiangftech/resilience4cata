package dev.resilience4cata.spring.proxies;

import dev.resilience4cata.annotation.CircuitBreaker;

/** A prototype-scoped annotated class: every instance is a new generated subclass, sharing the named breaker. */
public class PrototypeQuotes {

    private final Backend backend;

    public PrototypeQuotes(Backend backend) {
        this.backend = backend;
    }

    @CircuitBreaker(name = "prototype-quotes", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50, waitInOpen = "1h")
    public String quote(String sku) {
        return backend.call(sku);
    }
}
