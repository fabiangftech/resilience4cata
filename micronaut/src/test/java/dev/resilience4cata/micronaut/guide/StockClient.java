package dev.resilience4cata.micronaut.guide;

import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

// tag::micronaut-interface[]
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface StockClient {

    int available(String sku);
}
// end::micronaut-interface[]
