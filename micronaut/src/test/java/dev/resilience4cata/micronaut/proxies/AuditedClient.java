package dev.resilience4cata.micronaut.proxies;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface whose implementation has an around advice: the wrapper is outside it. */
public interface AuditedClient {

    @Retry(maxAttempts = 3, delay = "1ms")
    String fetch(String id);
}
