package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;

/** The implementation for every build profile but prod. */
@ApplicationScoped
@UnlessBuildProfile("prod")
public class TestGreeting implements Greeting {

    @Override
    @Retry(maxAttempts = 2, delay = "0ms")
    public String greet() {
        return "test";
    }
}
