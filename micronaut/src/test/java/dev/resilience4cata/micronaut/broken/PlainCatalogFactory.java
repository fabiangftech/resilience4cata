package dev.resilience4cata.micronaut.broken;

import dev.resilience4cata.micronaut.fixtures.Catalog;
import dev.resilience4cata.micronaut.fixtures.Outage;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** R4C-MN-004: returns the annotated class itself, whose policies would not run. */
@Factory
@Requires(property = "test.broken", value = "R4C-MN-004")
public class PlainCatalogFactory {

    @Singleton
    @Replaces(Catalog.class)
    Catalog plainCatalog(Outage outage) {
        return new Catalog(outage);
    }
}
