package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;

/** A bean only when the build property {@code scenarios.toggle} is {@code on}. */
@ApplicationScoped
@IfBuildProperty(name = "scenarios.toggle", stringValue = "on")
public class ToggleOn implements Toggle {

    private final Flaky flaky = new Flaky();

    @Override
    @Retry(maxAttempts = 2, delay = "0ms")
    public String state() {
        flaky.call();
        return "on";
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }
}
