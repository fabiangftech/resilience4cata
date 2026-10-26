package dev.resilience4cata.quarkus.deployment.fixtures;

import jakarta.enterprise.context.ApplicationScoped;

/** R4C-QK-001: both libraries' @Retry on one method, the classic wrong import. */
@ApplicationScoped
public class MixedClient {

    @dev.resilience4cata.annotation.Retry(maxAttempts = 3)
    @org.eclipse.microprofile.faulttolerance.Retry(maxRetries = 2)
    public String fetch() {
        return "ok";
    }
}
