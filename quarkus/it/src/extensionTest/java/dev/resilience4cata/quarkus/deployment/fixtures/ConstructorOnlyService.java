package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;

/** R4C-QK-005: normal-scoped, annotated, and only a constructor with parameters. */
@ApplicationScoped
public class ConstructorOnlyService {

    private final Helper helper;

    public ConstructorOnlyService(Helper helper) {
        this.helper = helper;
    }

    @Retry(maxAttempts = 2)
    public String call() {
        return helper.name();
    }
}
