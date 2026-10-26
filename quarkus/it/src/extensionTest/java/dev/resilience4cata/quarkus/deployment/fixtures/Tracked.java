package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.CircuitBreaker;
import jakarta.inject.Singleton;

/** Creates the breaker "tracked" (and the instance "named-breaker" for the config tests). */
@Singleton
public class Tracked {

    @CircuitBreaker(name = "tracked")
    public String call() {
        return "ok";
    }
}
