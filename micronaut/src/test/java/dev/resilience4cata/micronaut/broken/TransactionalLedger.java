package dev.resilience4cata.micronaut.broken;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

/** R4C-MN-005: a retried transactional method of an annotated class: one transaction for every attempt. */
@Singleton
@Requires(property = "test.broken", value = "R4C-MN-005")
public class TransactionalLedger {

    @Transactional
    @Retry(maxAttempts = 3)
    public int post(int cents) {
        return cents;
    }

    /** A Mono method: its retry layer is in the lambda the generated override returns. */
    @Transactional
    @Retry(maxAttempts = 3)
    public reactor.core.publisher.Mono<Integer> postLater(int cents) {
        return reactor.core.publisher.Mono.just(cents);
    }

    @Transactional(propagation = io.micronaut.transaction.TransactionDefinition.Propagation.NOT_SUPPORTED)
    @Retry(maxAttempts = 3)
    public int read() {
        return 0;
    }
}
