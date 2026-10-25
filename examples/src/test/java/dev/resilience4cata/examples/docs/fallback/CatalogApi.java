package dev.resilience4cata.examples.docs.fallback;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Sibling fallbacks picked by exception type, filters, and the async form. */
// java:S1172: a fallback mirrors the method's parameters, then takes the failure that selects it by type
// (suppressed on the interface because the fallbacks are documentation snippets)
@SuppressWarnings("java:S1172")
public interface CatalogApi {

    // tag::fallback-overloads[]
    @Fallback(method = "describeFallback")
    @CircuitBreaker(name = "catalog")
    String describe(String sku) throws IOException;

    default String describeFallback(String sku, FileNotFoundException e) {
        return "no such product: " + sku;               // most specific type first
    }

    default String describeFallback(String sku, IOException e) {
        return "description unavailable";
    }

    static String describeFallback(String sku, CallNotPermittedException e) {
        return "catalog is resting";                    // the breaker is open
    }
    // end::fallback-overloads[]

    // tag::fallback-filters[]
    @Fallback(method = "emptyResults", applyOn = IOException.class, skipOn = FileNotFoundException.class)
    String search(String query) throws IOException;

    default String emptyResults(String query) {
        return "[]";
    }
    // end::fallback-filters[]

    // tag::fallback-async[]
    @Fallback(method = "cachedDescription")
    CompletableFuture<String> describeAsync(String sku);

    default CompletionStage<String> cachedDescription(String sku, Throwable failure) {
        return CompletableFuture.completedFuture("cached description of " + sku);
    }
    // end::fallback-async[]
}
