package dev.resilience4cata.spring.scenarios;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The {@code @Primary} implementation, first in lists. */
@Component
@Primary
@Order(1)
public class PrimaryQuotes implements Quotes {

    public final AtomicInteger attempts = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    @Override
    public String quote(String sku) {
        attempts.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("primary down");
        }
        return "primary " + sku;
    }
}
