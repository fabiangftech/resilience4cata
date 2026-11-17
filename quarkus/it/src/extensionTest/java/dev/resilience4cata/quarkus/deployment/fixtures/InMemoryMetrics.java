package dev.resilience4cata.quarkus.deployment.fixtures;

import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The application's OpenTelemetry metric exporter: Quarkus's {@code cdi} exporter takes the {@code MetricExporter}
 * bean, so the metrics tests read what was exported instead of sending it anywhere.
 */
@Singleton
public class InMemoryMetrics {

    @Produces
    @Singleton
    InMemoryMetricExporter exporter() {
        return InMemoryMetricExporter.create();
    }
}
