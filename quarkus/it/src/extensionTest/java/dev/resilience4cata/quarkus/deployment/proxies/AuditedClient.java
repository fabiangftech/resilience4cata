package dev.resilience4cata.quarkus.deployment.proxies;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface whose implementation is intercepted: the wrapper is outside the interceptor. */
public interface AuditedClient {

    @Retry(maxAttempts = 3, delay = "1ms")
    String fetch(String id);
}
