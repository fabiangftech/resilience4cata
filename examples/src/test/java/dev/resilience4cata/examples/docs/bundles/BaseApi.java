package dev.resilience4cata.examples.docs.bundles;

import dev.resilience4cata.annotation.Retry;

/** A superinterface whose type-level annotation is the lowest level for the methods it declares. */
@Retry(maxAttempts = 2, delay = "1ms")
public interface BaseApi {
    String get(String id);
}
