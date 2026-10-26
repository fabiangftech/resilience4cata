package dev.resilience4cata.spring;

import dev.resilience4cata.ResilienceRegistry;

/**
 * Adjusts the auto-configured {@link ResilienceRegistry} before it is built: named configs, defaults,
 * listeners, a ticker. Every customizer bean is applied, in {@code @Order}, before the
 * {@code resilience4cata.*} properties, so the properties win over code:
 *
 * <ul>
 *   <li>{@code resilience4cata.configs.<name>.*} and {@code resilience4cata.instances.<id>.*} override a
 *       customizer's {@code retry("name", ...)} or {@code retryInstance(...)} attribute by attribute;
 *   <li>{@code resilience4cata.defaults.<policy>.*} replaces a customizer's {@code <policy>Defaults(...)}
 *       for that policy (the defaults of a policy are set by one call).
 * </ul>
 *
 * <pre>{@code
 * @Bean
 * ResilienceRegistryCustomizer slowBackend() {
 *     return builder -> builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofMillis(200)));
 * }
 * }</pre>
 *
 * <p>Not used when the application defines its own {@code ResilienceRegistry} bean.
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
