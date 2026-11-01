package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.quarkus.arc.properties.UnlessBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;

/** A bean unless the build property {@code scenarios.toggle} is {@code on}. */
@ApplicationScoped
@UnlessBuildProperty(name = "scenarios.toggle", stringValue = "on")
public class ToggleOff implements Toggle {

    @Override
    @Retry(maxAttempts = 2, delay = "0ms")
    public String state() {
        return "off";
    }
}
