package dev.resilience4cata.spring.tx;

import dev.resilience4cata.annotation.Retry;

/** R4C-SPR-004 with Jakarta's {@code @Transactional} on the class ({@code @Inherited}, so the subclass has it). */
@jakarta.transaction.Transactional
public class JakartaClassLedger {

    @Retry
    public String post(String entry) {
        return "posted " + entry;
    }
}
