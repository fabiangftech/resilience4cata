package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/** R4C-QK-004: an annotated class that declares a producer. */
@Singleton
public class ProducingService {

    @Retry(maxAttempts = 2)
    public String call() {
        return "ok";
    }

    @Produces
    @Named("greeting")
    String greeting() {
        return "hello";
    }
}
