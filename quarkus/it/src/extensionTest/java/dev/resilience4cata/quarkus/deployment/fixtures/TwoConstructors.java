package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.inject.Singleton;

/** Several constructors, none @Inject: CDI uses the no-argument one. */
@Singleton
public class TwoConstructors {

    private final String prefix;

    public TwoConstructors() {
        this("default");
    }

    public TwoConstructors(String prefix) {
        this.prefix = prefix;
    }

    @Retry(maxAttempts = 2, delay = "0ms")
    public String name() {
        return prefix;
    }
}
