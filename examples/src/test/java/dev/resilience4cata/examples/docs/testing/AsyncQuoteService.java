package dev.resilience4cata.examples.docs.testing;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;

// tag::testing-junit-async-interface[]
public interface AsyncQuoteService {

    @Retry(maxAttempts = 3, delay = "1s") // the default FULL jitter
    CompletableFuture<String> quote(String sku);
}
// end::testing-junit-async-interface[]
