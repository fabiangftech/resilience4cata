package dev.resilience4cata.quarkus;

import dev.resilience4cata.ResilienceRegistry;

/**
 * Adjusts the extension's {@link ResilienceRegistry} before it is built: named configs, defaults, listeners, a
 * ticker. Every customizer bean is applied, in {@code @Priority} order (highest first, as ArC sorts
 * {@code @All} lists), before the {@code resilience4cata.*} properties, so the properties win over code:
 *
 * <ul>
 *   <li>{@code resilience4cata.configs.<name>.*} and {@code resilience4cata.instances.<id>.*} override a
 *       customizer's {@code retry("name", ...)} or {@code retryInstance(...)} attribute by attribute;
 *   <li>{@code resilience4cata.defaults.<policy>.*} replaces a customizer's {@code <policy>Defaults(...)}
 *       for that policy (the defaults of a policy are set by one call).
 * </ul>
 *
 * <pre>{@code
 * @Singleton
 * class SlowBackend implements ResilienceRegistryCustomizer {
 *     public void customize(ResilienceRegistry.Builder builder) {
 *         builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofMillis(200)));
 *     }
 * }
 * }</pre>
 *
 * <p>Not used when the application produces its own {@code ResilienceRegistry} bean.
 */
@FunctionalInterface
public interface ResilienceRegistryCustomizer {

    /**
     * Customizes the builder.
     *
     * @param builder the registry builder
     */
    void customize(ResilienceRegistry.Builder builder);
}
