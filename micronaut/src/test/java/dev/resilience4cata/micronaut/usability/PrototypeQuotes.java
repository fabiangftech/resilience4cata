package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.CircuitBreaker;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;

/** A prototype annotated class: every injection is a new instance, the policy instance is shared by its name. */
@Prototype
@Requires(property = "test.usability", value = "scopes")
public class PrototypeQuotes {

    private final Failures failures;

    public PrototypeQuotes(Failures failures) {
        this.failures = failures;
    }

    @CircuitBreaker(name = "prototype-quotes", minimumCalls = 2, failureRateThreshold = 50)
    public String quote(String sku) {
        failures.call();
        return sku;
    }
}
