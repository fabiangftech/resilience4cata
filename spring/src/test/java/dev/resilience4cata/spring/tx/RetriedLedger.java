package dev.resilience4cata.spring.tx;

import dev.resilience4cata.annotation.Retry;
import org.springframework.transaction.annotation.Transactional;

/** R4C-SPR-004: {@code @Retry} on the class, {@code @Transactional} on a method. */
@Retry(maxAttempts = 3, delay = "10ms")
public class RetriedLedger {

    @Transactional
    public String post(String entry) {
        return "posted " + entry;
    }
}
