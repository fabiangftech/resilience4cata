package dev.resilience4cata.micronaut.usability;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;

/**
 * What the processor writes without -Ar4c.inject=micronaut for a class with an {@code @Inject} constructor: the
 * mirrored constructor repeats {@code @Inject}, so Micronaut makes it a bean, but it carries no
 * {@code @ContainerBean} marker and none of the class's scope or qualifiers. Written by hand, since this module's
 * tests are compiled with the option.
 */
@Requires(property = "test.usability", value = "missing-option")
public class Unmarked$Resilient extends Unmarked {

    @Inject
    public Unmarked$Resilient() {
    }
}
