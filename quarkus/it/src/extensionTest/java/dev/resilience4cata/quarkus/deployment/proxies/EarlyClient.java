package dev.resilience4cata.quarkus.deployment.proxies;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface called by a startup observer that runs before the extension's own. */
public interface EarlyClient {

    @Retry(maxAttempts = 3, delay = "1ms")
    String fetch(String id);
}
