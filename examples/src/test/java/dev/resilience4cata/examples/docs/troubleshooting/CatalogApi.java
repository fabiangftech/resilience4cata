package dev.resilience4cata.examples.docs.troubleshooting;

import dev.resilience4cata.annotation.Retry;
import java.util.List;

// tag::troubleshooting-self-invocation-api[]
public interface CatalogApi {

    @Retry(maxAttempts = 3, delay = "0")
    int price(String sku);

    int total(List<String> skus); // not annotated
}
// end::troubleshooting-self-invocation-api[]
