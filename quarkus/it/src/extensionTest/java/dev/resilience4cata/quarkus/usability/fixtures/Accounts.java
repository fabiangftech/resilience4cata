package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** An annotated class whose methods use Bean Validation, as Quarkus applications with Hibernate Validator do. */
@ApplicationScoped
public class Accounts {

    private final Flaky flaky = new Flaky();

    @Retry(maxAttempts = 3, delay = "0ms")
    public String open(@NotBlank String owner) {
        flaky.call();
        return "account of " + owner;
    }

    @Retry(maxAttempts = 3, delay = "0ms")
    @NotNull
    public Account find(@NotNull String owner) {
        flaky.call();
        return new Account(owner);
    }

    public void failNext(int count) {
        flaky.failNext(count);
    }

    public int calls() {
        return flaky.calls();
    }

    /** A result. */
    public record Account(String owner) {
    }
}
