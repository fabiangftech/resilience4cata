package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

/** A @Dependent annotated class with field injection. */
@Dependent
public class Ticket {

    @Inject
    Helper helper;

    @Retry(maxAttempts = 2, delay = "0ms")
    public String issue() {
        return helper.name();
    }
}
