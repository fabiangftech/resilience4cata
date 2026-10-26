package dev.resilience4cata.quarkus.deployment.fixtures;

import dev.resilience4cata.annotation.Retry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/** R4C-QK-002: a retried transactional method of an annotated class. */
@ApplicationScoped
public class LedgerService {

    @Retry(maxAttempts = 3)
    @Transactional
    public String post(String entry) {
        return entry;
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    @Retry(maxAttempts = 3)
    public String outside(String entry) {
        return entry;
    }
}
