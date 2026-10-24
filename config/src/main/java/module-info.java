/**
 * External configuration for resilience4cata: reads {@code .properties} files, classpath resources,
 * environment variables and system properties, validates them strictly, and applies them to a
 * {@link dev.resilience4cata.ResilienceRegistry.Builder}. No dependencies besides {@code core}.
 */
@org.jspecify.annotations.NullMarked
module dev.resilience4cata.config {
    requires transitive dev.resilience4cata.core;

    exports dev.resilience4cata.config;
}
