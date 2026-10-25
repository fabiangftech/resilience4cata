package dev.resilience4cata.examples.docs.retry;

// tag::retry-minimal[]
import dev.resilience4cata.annotation.Retry;

public interface InventoryApi {

    @Retry(maxAttempts = 3, delay = "100ms")
    int stock(String sku);
}
// end::retry-minimal[]
