package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** An annotated class with an annotated subclass, {@link PriorityOrders}. */
@Singleton
@Requires(property = "test.usability", value = "hierarchy")
public class Orders {

    protected final Failures failures;

    public Orders(Failures failures) {
        this.failures = failures;
    }

    @Retry(maxAttempts = 3)
    public String find(String id) {
        failures.call();
        return "order " + id;
    }
}
