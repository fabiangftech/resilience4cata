package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.annotation.Retry;

/** An annotated interface whose bean has Micronaut AOP: the advice runs inside the policies, once per attempt. */
public interface Ledger {

    @Retry(name = "remote-ledger", maxAttempts = 3, delay = "10ms")
    int post(int cents);
}
