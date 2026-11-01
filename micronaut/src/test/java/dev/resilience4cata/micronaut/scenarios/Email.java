package dev.resilience4cata.micronaut.scenarios;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** A custom qualifier annotation, not {@code @Inherited}. */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface Email {
}
