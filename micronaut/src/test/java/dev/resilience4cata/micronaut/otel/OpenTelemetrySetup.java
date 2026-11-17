package dev.resilience4cata.micronaut.otel;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.opentelemetry.OpenTelemetryMetrics;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.inject.Singleton;

/** The Micronaut guide's OpenTelemetry metrics: the application's own wiring, as the module binds only Micrometer. */
@Factory
@Requires(property = "test.opentelemetry")
public class OpenTelemetrySetup {

    // tag::micronaut-opentelemetry[]
    @Singleton
    @Bean(preDestroy = "close")
    OpenTelemetryMetrics resilienceMetrics(OpenTelemetry openTelemetry) {
        return OpenTelemetryMetrics.create(openTelemetry);
    }

    @Singleton
    BeanCreatedEventListener<ResilienceRegistry.Builder> resilienceCounters(OpenTelemetryMetrics metrics) {
        return event -> metrics.register(event.getBean()); // the counters' listener, before the registry is built
    }

    @Singleton
    BeanCreatedEventListener<ResilienceRegistry> resilienceGauges(OpenTelemetryMetrics metrics) {
        return event -> {
            metrics.bindTo(event.getBean()); // the gauges, read at each collection
            return event.getBean();
        };
    }
    // end::micronaut-opentelemetry[]
}
