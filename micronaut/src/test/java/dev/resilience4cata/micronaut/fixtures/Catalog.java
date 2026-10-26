package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.annotation.Retry;

/** An annotated class that is not a bean itself: {@link CatalogFactory} creates its generated subclass. */
public class Catalog {

    private final Outage outage;

    public Catalog(Outage outage) {
        this.outage = outage;
    }

    @Retry(name = "catalog", maxAttempts = 2, delay = "10ms")
    public String describe(String sku) {
        outage.call();
        return sku;
    }
}
