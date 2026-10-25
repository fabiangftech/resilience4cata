package dev.resilience4cata.examples.docs.testing;

import dev.resilience4cata.annotation.Backoff;
import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;

// tag::testing-first-interface[]
public interface QuoteService {

    @Retry(maxAttempts = 4, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    String quote(String sku);
}
// end::testing-first-interface[]
