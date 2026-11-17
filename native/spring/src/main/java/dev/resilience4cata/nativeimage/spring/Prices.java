package dev.resilience4cata.nativeimage.spring;

import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;

/** An annotated interface: Spring wraps its implementation bean in {@code Prices$Resilient}. */
public interface Prices {

    @Retry(name = "prices", maxAttempts = 2, delay = "5ms")
    @Fallback(method = "listPrice")
    String price(String sku);

    default String listPrice(String sku, Throwable failure) {
        return sku + ": list price";
    }
}
