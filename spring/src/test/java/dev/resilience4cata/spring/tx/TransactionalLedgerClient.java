package dev.resilience4cata.spring.tx;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.annotation.Transactional;

/** Fails the first {@link #failures} calls, each in a transaction. */
public class TransactionalLedgerClient implements LedgerClient {

    public final AtomicInteger failures = new AtomicInteger();

    @Override
    @Transactional
    public String post(String entry) {
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("ledger busy");
        }
        return "posted " + entry;
    }
}
