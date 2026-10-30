package dev.resilience4cata.quarkus.deployment.proxies;

import dev.resilience4cata.annotation.Retry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** An annotated class with an intercepted method: ArC's interception is outside the retry. */
@Singleton
public class AuditedLedger {

    @Inject
    Backend backend;

    @Audit
    @Retry(maxAttempts = 3, delay = "1ms")
    public String post(String id) {
        return backend.call(id);
    }
}
