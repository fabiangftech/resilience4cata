package dev.resilience4cata.quarkus.runtime;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.opentelemetry.OpenTelemetryMetrics;
import dev.resilience4cata.quarkus.ResilienceRegistryCustomizer;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * OpenTelemetry metrics through {@code resilience4cata-opentelemetry}, registered by the deployment module when
 * Quarkus OpenTelemetry metrics are present, Micrometer is not, and that module is on the class path (Micrometer is
 * preferred when both are, as SmallRye Fault Tolerance does): the event counters are registered on the registry
 * builder, the gauges bound once the registry is built, and the meters removed at shutdown. Successful calls publish
 * nothing, so they cost nothing.
 */
@Singleton
public class OpenTelemetryBinding implements ResilienceRegistryCustomizer, RegistryBound {

    private final OpenTelemetryMetrics metrics;

    /**
     * Created by ArC.
     *
     * @param openTelemetry the application's OpenTelemetry
     */
    @Inject
    public OpenTelemetryBinding(OpenTelemetry openTelemetry) {
        this.metrics = OpenTelemetryMetrics.create(openTelemetry);
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
