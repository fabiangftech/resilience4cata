package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;

/** A feature switched on by a run-time property, looked up programmatically. */
@ApplicationScoped
@LookupIfProperty(name = "beta.enabled", stringValue = "true")
public class BetaFeature {

    @Retry(maxAttempts = 2, delay = "0ms")
    public String run() {
        return "beta";
    }
}
