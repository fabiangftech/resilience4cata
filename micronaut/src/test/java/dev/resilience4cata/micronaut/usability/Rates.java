package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface with several implementations, told apart by {@code @Named} and {@code @Primary}. */
public interface Rates {

    @Retry(maxAttempts = 3)
    double rate(String currency);

    /** A default method calling the protected one: the wrapper delegates it to the bean. */
    default double inverse(String currency) {
        return 1 / rate(currency);
    }
}
