package dev.resilience4cata.spring.unprocessed;

import dev.resilience4cata.annotation.Retry;

/** An annotated class; tests load it through {@link WithoutGeneratedClasses}, as if the processor had not run. */
public class PriceService {

    @Retry(maxAttempts = 2)
    public String price(String sku) {
        return "9.99";
    }
}
