package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;

/** The production implementation: a bean in the prod build profile only. */
@ApplicationScoped
@IfBuildProfile("prod")
public class ProdGreeting implements Greeting {

    @Override
    @Retry(maxAttempts = 2, delay = "0ms")
    public String greet() {
        return "prod";
    }
}
