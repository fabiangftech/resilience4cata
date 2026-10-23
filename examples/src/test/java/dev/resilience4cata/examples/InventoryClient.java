package dev.resilience4cata.examples;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;
import java.util.List;

/** A client whose methods use @Retry directly. */
public interface InventoryClient {

    @Retry(maxAttempts = 3, delay = "1ms", jitter = Jitter.NONE)
    int stock(String sku);

    @Retry(maxAttempts = 4, delay = "1ms", jitter = Jitter.NONE, retryOn = IOException.class)
    String fetch(String url) throws IOException;

    @Retry(maxAttempts = 3, delay = "1ms", jitter = Jitter.NONE, abortOn = IllegalArgumentException.class)
    void reserve(String sku, int quantity);

    @Retry(enabled = "false")
    int disabled();

    @Retry(config = "inventory")
    int named();

    @Retry(maxAttempts = 3, delay = "1ms", jitter = Jitter.NONE)
    <T extends Comparable<T>> T max(List<T> values);

    int unprotected();

    default String describe() {
        return "inventory";
    }
}
