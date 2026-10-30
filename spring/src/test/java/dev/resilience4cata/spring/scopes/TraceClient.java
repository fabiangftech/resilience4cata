package dev.resilience4cata.spring.scopes;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface whose implementation is request-scoped: the scoped proxy is wrapped once. */
public interface TraceClient {

    @Retry(maxAttempts = 3, delay = "5ms")
    String trace();
}
