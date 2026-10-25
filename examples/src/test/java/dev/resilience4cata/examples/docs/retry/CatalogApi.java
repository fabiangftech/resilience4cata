package dev.resilience4cata.examples.docs.retry;

import dev.resilience4cata.annotation.Retry;

/** A retry budget: the "Retry budget" section's example. */
public interface CatalogApi {

    // tag::retry-budget[]
    @Retry(name = "catalog", maxAttempts = 3, delay = "100ms",
            budgetPercent = 20, budgetMinRetriesPerSecond = 1)
    String product(String id);
    // end::retry-budget[]
}
