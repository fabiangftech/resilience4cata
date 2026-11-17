package dev.resilience4cata.spring.otel;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.opentelemetry.OpenTelemetryMetrics;
import dev.resilience4cata.spring.ResilienceRegistryCustomizer;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Spring guide's OpenTelemetry metrics: the application's own wiring, as the starter binds only Micrometer. */
@Configuration(proxyBeanMethods = false)
public class OpenTelemetrySetup {

    // tag::spring-opentelemetry[]
    @Bean
    OpenTelemetryMetrics resilienceMetrics(OpenTelemetry openTelemetry) {
        return OpenTelemetryMetrics.create(openTelemetry); // closed with the context
    }

    @Bean
    ResilienceRegistryCustomizer resilienceCounters(OpenTelemetryMetrics metrics) {
        return metrics::register; // the counters' listener, before the registry is built
    }

    @Bean
    SmartInitializingSingleton resilienceGauges(OpenTelemetryMetrics metrics, ResilienceRegistry registry) {
        return () -> metrics.bindTo(registry); // the gauges, read at each collection
    }
    // end::spring-opentelemetry[]
}
