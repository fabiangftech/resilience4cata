package dev.resilience4cata.spring.guide;

import org.springframework.stereotype.Component;

// tag::spring-inject-interface[]
@Component
public class StockReport {

    private final StockClient stock; // the interface: the bean is StockClient$Resilient

    public StockReport(StockClient stock) {
        this.stock = stock;
    }

    public String line(String sku) {
        return sku + ": " + stock.available(sku);
    }
}
// end::spring-inject-interface[]
