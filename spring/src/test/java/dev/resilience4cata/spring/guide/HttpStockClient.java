package dev.resilience4cata.spring.guide;

import org.springframework.stereotype.Component;

// tag::spring-interface-bean[]
@Component
public class HttpStockClient implements StockClient {

    @Override
    public int available(String sku) {
        return 42; // an HTTP call in a real application
    }
}
// end::spring-interface-bean[]
