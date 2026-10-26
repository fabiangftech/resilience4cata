package dev.resilience4cata.spring.tx;

import dev.resilience4cata.annotation.Retry;

/** Jakarta's {@code @Transactional} on a retried method: repeated on the generated override (see TransactionTest). */
public class JakartaLedger {

    @Retry
    @jakarta.transaction.Transactional
    public String post(String entry) {
        return "posted " + entry;
    }
}
