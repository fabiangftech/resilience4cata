/**
 * One dependency to start with: reads {@code core}, the annotations and {@code config} transitively.
 *
 * <p>Holds no code. The annotation processor ({@code resilience4cata-processor}) is not included: it must go on the
 * annotation processor path.
 */
module dev.resilience4cata.all {
    requires transitive dev.resilience4cata.core;
    requires transitive dev.resilience4cata.annotations;
    requires transitive dev.resilience4cata.config;
}
