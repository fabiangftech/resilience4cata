package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.inject.Singleton;

/**
 * An annotated class with Micronaut AOP on a retried method: {@code @Audited} is copied onto the generated
 * override, so Micronaut's proxy ({@code AuditedLedger$Resilient$Intercepted}) goes around the policies and sees
 * one call for all the attempts.
 */
@Singleton
public class AuditedLedger {

    private final Outage outage;

    public AuditedLedger(Outage outage) {
        this.outage = outage;
    }

    @Audited
    @Retry(name = "ledger", maxAttempts = 3, delay = "10ms")
    public int post(int cents) {
        outage.call();
        return cents;
    }
}
