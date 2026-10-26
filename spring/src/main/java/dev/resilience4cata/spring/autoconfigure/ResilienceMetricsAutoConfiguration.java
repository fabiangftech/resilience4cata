package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.micrometer.MicrometerMetrics;
import dev.resilience4cata.spring.ResilienceRegistryCustomizer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Micrometer metrics through {@code resilience4cata-micrometer}, when it is on the class path and the
 * application has a {@code MeterRegistry}: the event counters are registered on the registry builder, and
 * the gauges bound once the singletons exist. Successful calls publish nothing, so they cost nothing.
 */
@AutoConfiguration(afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnClass({MicrometerMetrics.class, MeterRegistry.class})
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnBooleanProperty(name = "resilience4cata.spring.metrics.enabled", matchIfMissing = true)
public final class ResilienceMetricsAutoConfiguration {

    /** Created by Spring. */
    public ResilienceMetricsAutoConfiguration() {
    }

    /**
     * The metrics, closed (meters removed) with the context.
     *
     * @param meters the application's meter registry
     * @return the metrics
     */
    @Bean
    @ConditionalOnMissingBean
    public MicrometerMetrics resilienceMicrometerMetrics(MeterRegistry meters) {
        return MicrometerMetrics.create(meters);
    }

    /**
     * Registers the counters' event listener on the registry builder.
     *
     * @param metrics the metrics
     * @return the customizer
     */
    @Bean
    public ResilienceRegistryCustomizer resilienceMetricsCustomizer(MicrometerMetrics metrics) {
        return metrics::register;
    }

    @Bean
    static SmartInitializingSingleton resilienceMetricsBinder(ObjectProvider<MicrometerMetrics> metrics,
            ObjectProvider<ResilienceRegistry> registry) {
        return () -> registry.ifUnique(r -> metrics.ifUnique(m -> m.bindTo(r)));
    }
}
