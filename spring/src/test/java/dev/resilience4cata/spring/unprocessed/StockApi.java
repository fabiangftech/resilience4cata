package dev.resilience4cata.spring.unprocessed;

import dev.resilience4cata.annotation.CircuitBreaker;

/** An annotated interface; tests load it through {@link WithoutGeneratedClasses}, as if the processor had not run. */
public interface StockApi {

    @CircuitBreaker(name = "stock")
    int stock(String sku);
}
