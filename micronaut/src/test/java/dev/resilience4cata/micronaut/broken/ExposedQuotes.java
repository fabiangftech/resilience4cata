package dev.resilience4cata.micronaut.broken;

import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** R4C-MN-001: exposed as itself too, which the wrapper is not. */
@Singleton
@ResilientBean(Quotes.class)
@Bean(typed = {Quotes.class, ExposedQuotes.class})
@Requires(property = "test.broken", value = "R4C-MN-001")
public class ExposedQuotes implements Quotes {

    @Override
    public int quote(String sku) {
        return 1;
    }
}
