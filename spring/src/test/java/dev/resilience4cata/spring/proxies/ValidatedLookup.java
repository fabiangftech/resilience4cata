package dev.resilience4cata.spring.proxies;

import dev.resilience4cata.annotation.Retry;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;

/** An annotated class with method validation: the validation proxy rejects a blank id before the policies run. */
@Validated
public class ValidatedLookup {

    private final Backend backend;

    public ValidatedLookup(Backend backend) {
        this.backend = backend;
    }

    @Retry(maxAttempts = 3, delay = "1ms")
    public String find(@NotBlank String id) {
        return backend.call(id);
    }
}
