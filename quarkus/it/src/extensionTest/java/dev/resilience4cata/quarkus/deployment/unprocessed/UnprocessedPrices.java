package dev.resilience4cata.quarkus.deployment.unprocessed;

import dev.resilience4cata.annotation.Retry;
import jakarta.inject.Singleton;

/** An annotated class bean; tests add it without its generated subclass, as if the processor had not run. */
@Singleton
public class UnprocessedPrices {

    @Retry(maxAttempts = 2)
    public String price(String sku) {
        return "9.99";
    }
}
