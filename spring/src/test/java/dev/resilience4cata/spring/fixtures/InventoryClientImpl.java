package dev.resilience4cata.spring.fixtures;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** A plain implementation; fails the first {@link #failures} calls. */
@Component
public class InventoryClientImpl implements InventoryClient {

    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger failures = new AtomicInteger();

    @Override
    public int stock(String sku) {
        calls.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("inventory down");
        }
        return 7;
    }
}
