package dev.resilience4cata.micronaut.broken;

import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** R4C-MN-002: names a class, not an annotated interface. */
@Singleton
@ResilientBean(BaseQuotes.class)
@Requires(property = "test.broken", value = "R4C-MN-002")
public class ClassQuotes extends BaseQuotes {
}
