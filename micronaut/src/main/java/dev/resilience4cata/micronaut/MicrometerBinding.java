package dev.resilience4cata.micronaut;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.micrometer.MicrometerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;

/**
 * Micrometer metrics through {@code resilience4cata-micrometer}, when it is on the class path and the application
 * has a {@code MeterRegistry} (from {@code micronaut-micrometer-core}): the event counters are registered on the
 * registry builder and the gauges bound to the registry once it is built. Successful calls publish nothing, so
 * they cost nothing. {@code resilience4cata.micronaut.metrics.enabled=false} turns it off.
 */
@Factory
@Requires(classes = {MicrometerMetrics.class, MeterRegistry.class})
@Requires(beans = MeterRegistry.class)
@Requires(property = MicronautSettings.PREFIX + ".metrics.enabled", notEquals = "false")
final class MicrometerBinding {

    /** The metrics, closed (meters removed) with the context. */
    @Singleton
    @Bean(preDestroy = "close")
    MicrometerMetrics resilienceMicrometerMetrics(MeterRegistry meters) {
        return MicrometerMetrics.create(meters);
    }

    /** Registers the counters' event listener on every registry builder. */
    @Singleton
    BeanCreatedEventListener<ResilienceRegistry.Builder> resilienceMetricsCounters(MicrometerMetrics metrics) {
        return event -> metrics.register(event.getBean());
    }

    /** Binds the gauges to the registry: instances created later are added as they appear. */
    @Singleton
    BeanCreatedEventListener<ResilienceRegistry> resilienceMetricsGauges(MicrometerMetrics metrics) {
        return (BeanCreatedEvent<ResilienceRegistry> event) -> {
            metrics.bindTo(event.getBean());
            return event.getBean();
        };
    }
}
