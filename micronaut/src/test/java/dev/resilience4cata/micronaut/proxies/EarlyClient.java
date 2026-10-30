package dev.resilience4cata.micronaut.proxies;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface whose bean another bean-created listener depends on. */
public interface EarlyClient {

    @Retry(maxAttempts = 3, delay = "1ms")
    String fetch(String id);
}
