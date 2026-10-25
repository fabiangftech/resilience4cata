package dev.resilience4cata.examples.docs.annotatedclasses;

import dev.resilience4cata.annotation.Retry;
import java.util.List;

/** The annotated class of docs/{en,es}/guides/annotated-classes.md. */
// tag::annotated-classes-service[]
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
// end::annotated-classes-service[]
