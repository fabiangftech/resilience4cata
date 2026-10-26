package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.ResilienceRegistry;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/** The manual way: a factory method typed {@code Catalog} that returns the generated subclass. */
@Factory
public class CatalogFactory {

    @Singleton
    Catalog catalog(ResilienceRegistry registry, Outage outage) {
        return new Catalog$Resilient(registry, outage);
    }
}
