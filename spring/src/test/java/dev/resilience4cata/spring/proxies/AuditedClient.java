package dev.resilience4cata.spring.proxies;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface: the wrapper goes around the aspect's proxy of the implementation, so it runs per attempt. */
public interface AuditedClient {

    @Retry(maxAttempts = 3, delay = "1ms")
    String fetch(String id);
}
