package dev.resilience4cata.spring.tx;

import dev.resilience4cata.annotation.Retry;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Retried methods that suspend or refuse a transaction, and a transactional method that is not retried. */
@Transactional
public class OutsideLedger {

    @Retry
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String report(String day) {
        return day;
    }

    @Retry
    @Transactional(propagation = Propagation.NEVER)
    public String audit(String day) {
        return day;
    }

    public String post(String entry) {
        return entry;
    }
}
