package dev.resilience4cata.quarkus.deployment.fixtures;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.concurrent.atomic.AtomicInteger;

/** An @ApplicationScoped, @Named implementation of an annotated interface. */
@ApplicationScoped
@Named("list")
public class ListPricing implements Pricing {

    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public int price(String sku) {
        calls.incrementAndGet();
        if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("flaky");
        }
        return 10;
    }

    public void failNext(int count) {
        failures.set(count);
    }

    public int calls() {
        return calls.get();
    }
}
