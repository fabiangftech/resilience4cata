package dev.resilience4cata.spring.scenarios;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The qualified implementation, second in lists. */
@Component
@Qualifier("backup")
@Order(2)
public class BackupQuotes implements Quotes {

    public final AtomicInteger attempts = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    @Override
    public String quote(String sku) {
        attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("backup down");
        }
        return "backup " + sku;
    }
}
