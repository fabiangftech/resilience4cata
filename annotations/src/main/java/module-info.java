/**
 * Resilience annotations. All annotations use {@code CLASS} retention: the annotation
 * processor reads them at compile time, so this module is not needed at runtime.
 */
module dev.resilience4cata.annotations {
    exports dev.resilience4cata.annotation;
}
