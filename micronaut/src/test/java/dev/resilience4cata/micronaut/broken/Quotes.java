package dev.resilience4cata.micronaut.broken;

import dev.resilience4cata.annotation.Retry;

public interface Quotes {

    @Retry(maxAttempts = 2)
    int quote(String sku);
}
