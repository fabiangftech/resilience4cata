package dev.resilience4cata.spring.tx;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Timeout;
import org.springframework.transaction.annotation.Transactional;

/** Transactional, with policies that run the call once: allowed. */
@Transactional
public class GuardedLedger {

    @CircuitBreaker
    @Timeout("1s")
    public String post(String entry) {
        return entry;
    }
}
