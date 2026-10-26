package dev.resilience4cata.quarkus.it;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;

/** An annotated interface whose calls allocate nothing themselves, for {@link AllocationTest}. */
@Retry(maxAttempts = 3)
@CircuitBreaker
public interface Quotes {

    int quote(int sku);
}
