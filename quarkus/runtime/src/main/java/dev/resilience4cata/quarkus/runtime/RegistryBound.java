package dev.resilience4cata.quarkus.runtime;

import dev.resilience4cata.ResilienceRegistry;

/**
 * A bean told about the extension's registry once it is built, such as the metrics bindings, which add gauges
 * that read the registry. Internal to the extension.
 */
public interface RegistryBound {

    /**
     * Called once, right after the registry is built.
     *
     * @param registry the registry
     */
    void bind(ResilienceRegistry registry);
}
