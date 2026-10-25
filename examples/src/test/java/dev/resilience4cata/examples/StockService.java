package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Retry;
import java.util.List;

/** The annotated class of {@code docs/en/getting-started.md}, used by {@link GuideTest}. Keep it identical to the page. */
public class StockService {
    private final Warehouse warehouse;

    public StockService(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    @Retry(maxAttempts = 3, delay = "50ms")
    public int stock(String sku) {
        return warehouse.count(sku);
    }

    public int total(List<String> skus) {
        int total = 0;
        for (String sku : skus) {
            total += stock(sku);   // self-invocation: goes through stock()'s retry
        }
        return total;
    }
}
