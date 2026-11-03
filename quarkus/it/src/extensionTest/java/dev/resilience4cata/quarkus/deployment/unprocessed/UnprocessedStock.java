package dev.resilience4cata.quarkus.deployment.unprocessed;

import dev.resilience4cata.annotation.CircuitBreaker;

/** An annotated interface; tests add it without its generated wrapper, as if the processor had not run. */
public interface UnprocessedStock {

    @CircuitBreaker(name = "stock")
    int stock(String sku);
}
