package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Bulkhead;
import java.util.concurrent.CompletableFuture;

/** An asynchronous bulkhead that waits for a slot without blocking the caller. */
public interface AsyncBulkheadClient {

    @Bulkhead(maxConcurrent = 1, maxWait = "5s")
    CompletableFuture<String> fetch(String id);

    @Bulkhead(maxConcurrent = 1, maxWait = "50ms")
    CompletableFuture<String> fetchBriefly(String id);
}
