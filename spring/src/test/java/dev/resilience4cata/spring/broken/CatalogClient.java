package dev.resilience4cata.spring.broken;

import dev.resilience4cata.annotation.Retry;

/** A second annotated interface. */
@Retry
public interface CatalogClient {

    String describe(String sku);
}
