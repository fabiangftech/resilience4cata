package dev.resilience4cata.spring.proxies;

import dev.resilience4cata.annotation.Retry;

/** An annotated class with an audited, retried method: the aspect's class-based proxy is outside the policies. */
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
