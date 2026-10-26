package dev.resilience4cata.examples.docs.hedge;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;

/** A hedge with the other policies around and inside it. */
public interface PriceApi {

    // tag::hedge-combined[]
    @Hedge(name = "prices", delay = "30ms", maxAttempts = 3, abortOn = IllegalArgumentException.class)
    @CircuitBreaker(name = "prices")
    @Timeout("200ms")
    @Fallback(method = "cachedPrice")
    CompletableFuture<Long> price(String sku);

    default CompletableFuture<Long> cachedPrice(String sku, Throwable failure) {
        return CompletableFuture.completedFuture(-1L);
    }
    // end::hedge-combined[]
}
