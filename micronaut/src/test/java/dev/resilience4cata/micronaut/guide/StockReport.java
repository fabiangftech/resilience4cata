package dev.resilience4cata.micronaut.guide;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Requires(env = "guide")
// tag::micronaut-inject-interface[]
@Singleton
public class StockReport {

    private final StockClient stock; // the interface: the bean is StockClient$Resilient

    public StockReport(StockClient stock) {
        this.stock = stock;
    }

    public String line(String sku) {
        return sku + ": " + stock.available(sku);
    }
}
// end::micronaut-inject-interface[]
