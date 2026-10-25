package dev.resilience4cata.examples.docs.fallback;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;

/** {@link QuoteApi} with a handler that records its context, for the page's claims about it. */
public interface RecordingQuoteApi {

    @Fallback(FallbackPageTest.Recording.class)
    @Retry(maxAttempts = 3, delay = "100ms")
    long quote(String sku, int quantity);
}
