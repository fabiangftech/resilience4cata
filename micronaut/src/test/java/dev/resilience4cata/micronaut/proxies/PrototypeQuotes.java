package dev.resilience4cata.micronaut.proxies;

import dev.resilience4cata.annotation.CircuitBreaker;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;

/** A prototype annotated class: every instance is a new generated subclass, sharing the named breaker. */
@Prototype
@Requires(env = "proxies")
public class PrototypeQuotes {

    private final Backend backend;

    public PrototypeQuotes(Backend backend) {
        this.backend = backend;
    }

    @CircuitBreaker(name = "mn-prototype-quotes", windowSize = 2, minimumCalls = 2, failureRateThreshold = 50,
            waitInOpen = "1h")
    public String quote(String sku) {
        return backend.call(sku);
    }
}
