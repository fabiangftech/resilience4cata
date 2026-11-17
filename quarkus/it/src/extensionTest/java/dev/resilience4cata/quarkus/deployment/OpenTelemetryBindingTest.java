package dev.resilience4cata.quarkus.deployment;

import static dev.resilience4cata.quarkus.deployment.MetricsAssertions.REJECTED;
import static dev.resilience4cata.quarkus.deployment.MetricsAssertions.STATE;
import static dev.resilience4cata.quarkus.deployment.MetricsAssertions.awaitExported;
import static dev.resilience4cata.quarkus.deployment.MetricsAssertions.name;
import static dev.resilience4cata.quarkus.deployment.MetricsAssertions.rejectOneCall;
import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.quarkus.deployment.fixtures.InMemoryMetrics;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked$Resilient;
import dev.resilience4cata.quarkus.runtime.MicrometerBinding;
import dev.resilience4cata.quarkus.runtime.OpenTelemetryBinding;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * With Quarkus OpenTelemetry metrics and resilience4cata-opentelemetry but no Micrometer (here turned off: its
 * metrics capability is gone, its classes stay), the registry is bound to OpenTelemetry: event counters and snapshot
 * gauges reach the application's exporter.
 */
class OpenTelemetryBindingTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Tracked.class, Tracked$Resilient.class, InMemoryMetrics.class,
                    MetricsAssertions.class))
            .overrideConfigKey("quarkus.micrometer.enabled", "false")
            .overrideConfigKey("quarkus.otel.metrics.enabled", "true")
            .overrideConfigKey("quarkus.otel.traces.enabled", "false")
            .overrideConfigKey("quarkus.otel.exporter.otlp.enabled", "false")
            .overrideRuntimeConfigKey("quarkus.otel.metric.export.interval", "100ms");

    @Inject
    Instance<OpenTelemetryBinding> openTelemetry;

    @Inject
    Instance<MicrometerBinding> micrometer;

    @Inject
    InMemoryMetricExporter exporter;

    @Inject
    ResilienceRegistry registry;

    @Inject
    Tracked tracked;

    @Test
    void theRegistryIsBoundToOpenTelemetry() throws InterruptedException {
        assertThat(openTelemetry.isResolvable()).isTrue();
        assertThat(micrometer.isResolvable()).isFalse();

        rejectOneCall(tracked, registry);

        MetricData rejected = awaitExported(exporter, REJECTED);
        assertThat(rejected.getInstrumentationScopeInfo().getName()).isEqualTo("dev.resilience4cata");
        assertThat(rejected.getLongSumData().getPoints()).filteredOn(p -> "tracked".equals(name(p)))
                .singleElement().satisfies(p -> {
                    assertThat(p.getValue()).isEqualTo(1);
                    assertThat(p.getAttributes().get(AttributeKey.stringKey("kind"))).isEqualTo("circuitbreaker");
                });
        MetricData state = awaitExported(exporter, STATE);
        assertThat(state.getLongGaugeData().getPoints()).filteredOn(p -> "tracked".equals(name(p)))
                .extracting(p -> p.getAttributes().get(AttributeKey.stringKey("state")), LongPointData::getValue)
                .contains(org.assertj.core.groups.Tuple.tuple("closed", 1L));
    }
}
