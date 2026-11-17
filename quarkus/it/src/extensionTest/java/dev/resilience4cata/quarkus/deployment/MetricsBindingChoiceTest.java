package dev.resilience4cata.quarkus.deployment;

import static dev.resilience4cata.quarkus.deployment.MetricsAssertions.REJECTED;
import static dev.resilience4cata.quarkus.deployment.MetricsAssertions.exportedResilienceMetrics;
import static dev.resilience4cata.quarkus.deployment.MetricsAssertions.rejectOneCall;
import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.quarkus.deployment.fixtures.InMemoryMetrics;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked$Resilient;
import dev.resilience4cata.quarkus.runtime.MicrometerBinding;
import dev.resilience4cata.quarkus.runtime.OpenTelemetryBinding;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * With both Micrometer and OpenTelemetry metrics, the registry is bound to Micrometer only (as SmallRye Fault
 * Tolerance does), so nothing is counted twice: OpenTelemetry exports no resilience4cata metric.
 */
class MetricsBindingChoiceTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Tracked.class, Tracked$Resilient.class, InMemoryMetrics.class,
                    MetricsAssertions.class))
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
    MeterRegistry meters;

    @Inject
    ResilienceRegistry registry;

    @Inject
    Tracked tracked;

    @Test
    void micrometerWinsAndOpenTelemetryCountsNothing() throws InterruptedException {
        assertThat(micrometer.isResolvable()).isTrue();
        assertThat(openTelemetry.isResolvable()).isFalse();

        rejectOneCall(tracked, registry);

        assertThat(meters.find(REJECTED).tags("kind", "circuitbreaker", "name", "tracked").counter())
                .isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
        assertThat(meters.find("r4c.circuitbreaker.state").tags("name", "tracked", "state", "closed").gauge())
                .isNotNull().satisfies(g -> assertThat(g.value()).isEqualTo(1.0));
        // OpenTelemetry metrics are on and exported (the JVM's, at least) but none of them is ours.
        int exports = exporter.getFinishedMetricItems().size();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (exporter.getFinishedMetricItems().size() <= exports && System.nanoTime() - deadline < 0) {
            Thread.sleep(20);
        }
        Thread.sleep(300);
        assertThat(exporter.getFinishedMetricItems()).isNotEmpty();
        assertThat(exportedResilienceMetrics(exporter)).isEmpty();
    }
}
