package dev.resilience4cata.quarkus.it;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import jakarta.enterprise.context.ApplicationScoped;

/** An annotated class whose calls allocate nothing themselves, for {@link AllocationTest}. */
@ApplicationScoped
public class Ratings {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    @Timeout("5s")
    public int rating(int sku) {
        return sku * 2;
    }
}
