package dev.resilience4cata.micronaut.broken;

import dev.resilience4cata.micronaut.unprocessed.WithoutGeneratedClasses;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/** R4C-MN-007: a bean of an annotated class compiled without the processor (its generated subclass is hidden). */
@Factory
@Requires(property = "test.broken", value = "R4C-MN-007")
public class UnprocessedPriceFactory {

    @Singleton
    @Named("prices")
    Object prices() throws ReflectiveOperationException {
        return new WithoutGeneratedClasses().loadClass("dev.resilience4cata.micronaut.unprocessed.PriceService")
                .getConstructor().newInstance();
    }
}
