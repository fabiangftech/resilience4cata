package dev.resilience4cata.micronaut.scopes;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface. */
public interface TraceClient {

    @Retry(maxAttempts = 3, delay = "5ms")
    String trace();
}
