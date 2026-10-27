package dev.resilience4cata.micronaut.usability;

import dev.resilience4cata.annotation.Retry;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** An annotated class that a test replaces with {@code @MockBean}. */
@Singleton
@Requires(property = "test.usability", value = "mock")
public class PriceService {

    private final Failures failures;

    public PriceService(Failures failures) {
        this.failures = failures;
    }

    @Retry(maxAttempts = 3)
    public int price(String sku) {
        failures.call();
        return 100;
    }
}
