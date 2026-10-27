package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** An annotated class whose retried method validates its result in cascade ({@code @Valid} on the return value). */
@ApplicationScoped
public class Profiles {

    @Retry(maxAttempts = 2, delay = "0ms")
    @Valid
    public Profile load(String name) {
        return new Profile(name);
    }

    /** A validated result. */
    public record Profile(@NotBlank String name) {
    }
}
