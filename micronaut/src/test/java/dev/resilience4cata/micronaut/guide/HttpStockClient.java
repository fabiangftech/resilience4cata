package dev.resilience4cata.micronaut.guide;

import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Requires(env = "guide")
// tag::micronaut-interface-bean[]
@Singleton
@ResilientBean(StockClient.class) // the bean is replaced by StockClient$Resilient
public class HttpStockClient implements StockClient {

    @Override
    public int available(String sku) {
        return 42; // an HTTP call in a real application
    }
}
// end::micronaut-interface-bean[]
