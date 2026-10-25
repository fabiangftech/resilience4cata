package dev.resilience4cata.examples.docs.howitworks;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;

/** The interface of docs/{en,es}/start/how-it-works.md; its generated class is shown abridged on the page. */
// tag::how-it-works-interface[]
public interface StockClient {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    int stock(String sku, int warehouse);
}
// end::how-it-works-interface[]
