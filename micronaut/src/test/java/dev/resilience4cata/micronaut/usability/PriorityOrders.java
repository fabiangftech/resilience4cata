package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/** A second {@code Orders} bean, annotated itself: Micronaut should create {@code PriorityOrders$Resilient}. */
@Singleton
@Named("priority")
@Requires(property = "test.usability", value = "hierarchy")
public class PriorityOrders extends Orders {

    public PriorityOrders(Failures failures) {
        super(failures);
    }

    @Override
    @Retry(maxAttempts = 4)
    public String find(String id) {
        failures.call();
        return "priority order " + id;
    }
}
