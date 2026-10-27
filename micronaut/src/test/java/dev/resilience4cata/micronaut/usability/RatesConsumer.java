package dev.resilience4cata.micronaut.usability;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;

/** Injects the implementations of {@link Rates} the usual ways: default, qualified, and all of them. */
@Singleton
@Requires(property = "test.usability", value = "qualifiers")
public class RatesConsumer {

    final Rates primary;
    final Rates fed;
    final List<Rates> all;

    public RatesConsumer(Rates primary, @Named("fed") Rates fed, List<Rates> all) {
        this.primary = primary;
        this.fed = fed;
        this.all = all;
    }
}
