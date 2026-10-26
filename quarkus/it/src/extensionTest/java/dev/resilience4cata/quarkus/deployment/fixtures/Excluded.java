package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.inject.Singleton;

/** Listed in quarkus.resilience4cata.beans.exclude: stays as it is. */
@Singleton
public class Excluded {

    @Retry(maxAttempts = 2)
    public String name() {
        return "excluded";
    }
}
