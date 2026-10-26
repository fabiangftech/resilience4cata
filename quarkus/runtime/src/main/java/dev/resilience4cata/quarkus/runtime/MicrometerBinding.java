package dev.resilience4cata.quarkus.runtime;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.micrometer.MicrometerMetrics;
import dev.resilience4cata.quarkus.ResilienceRegistryCustomizer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Micrometer metrics through {@code resilience4cata-micrometer}, registered by the deployment module when Quarkus
 * Micrometer is enabled and that module is on the class path: the event counters are registered on the registry
 * builder, the gauges bound once the registry is built, and the meters removed at shutdown. Successful calls publish
 * nothing, so they cost nothing.
 */
@Singleton
public class MicrometerBinding implements ResilienceRegistryCustomizer, RegistryBound {

    private final MicrometerMetrics metrics;

    /**
     * Created by ArC.
     *
     * @param meters the application's meter registry
     */
    @Inject
    public MicrometerBinding(MeterRegistry meters) {
        this.metrics = MicrometerMetrics.create(meters);
    }

    @Override
    public void customize(ResilienceRegistry.Builder builder) {
        metrics.register(builder);
    }

    @Override
    public void bind(ResilienceRegistry registry) {
        metrics.bindTo(registry);
    }

    @PreDestroy
    void close() {
        metrics.close();
    }
}
