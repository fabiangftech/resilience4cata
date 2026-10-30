package dev.resilience4cata.micronaut.proxies;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** An annotated class with an around advice: Micronaut's proxy extends the generated subclass, outside the retry. */
@Singleton
@Requires(env = "proxies")
public class AuditedLedger {

    private final Backend backend;

    public AuditedLedger(Backend backend) {
        this.backend = backend;
    }

    @Audit
    @Retry(maxAttempts = 3, delay = "1ms")
    public String post(String id) {
        return backend.call(id);
    }
}
