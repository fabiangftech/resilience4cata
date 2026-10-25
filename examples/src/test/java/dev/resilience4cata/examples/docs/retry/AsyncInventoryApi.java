package dev.resilience4cata.examples.docs.retry;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;

/** The asynchronous form of {@link InventoryApi}. */
public interface AsyncInventoryApi {

    // tag::retry-async[]
    @Retry(maxAttempts = 3, delay = "100ms")
    CompletableFuture<Integer> stock(String sku);
    // end::retry-async[]
}
