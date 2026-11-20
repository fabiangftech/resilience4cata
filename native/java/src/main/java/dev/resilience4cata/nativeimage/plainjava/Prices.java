package dev.resilience4cata.nativeimage.plainjava;

import dev.resilience4cata.annotation.Retry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An annotated class: {@code Resilient.create} finds the generated subclass's constructor reflectively, so the
 * processor's metadata registers its public constructors and, for the varargs one, the array type.
 */
public class Prices {

    public final AtomicInteger calls = new AtomicInteger();
    private final String currency;
    private final List<String> regions;
    private int failures;

    public Prices(String currency, String... regions) {
        this.currency = currency;
        this.regions = List.of(regions);
    }

    public void failNext(int count) {
        failures = count;
    }

    @Retry(maxAttempts = 3, delay = "1ms")
    public String price(String sku) {
        calls.incrementAndGet();
        if (failures-- > 0) {
            throw new IllegalStateException("price service down");
        }
        return sku + ": 30 " + currency + " in " + regions;
    }

    /** Self-invocation goes through the generated override. */
    public String twice(String sku) {
        return price(sku) + " / " + price(sku);
    }
}
