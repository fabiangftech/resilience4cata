package dev.resilience4cata.quarkus.usability.fixtures;

/** Implemented by two annotated classes, one per value of a build property. */
public interface Toggle {

    String state();
}
