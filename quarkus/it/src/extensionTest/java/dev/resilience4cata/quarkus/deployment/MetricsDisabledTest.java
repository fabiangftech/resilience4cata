package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.deployment.fixtures.Tracked;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked$Resilient;
import dev.resilience4cata.quarkus.runtime.MicrometerBinding;
import dev.resilience4cata.quarkus.runtime.OpenTelemetryBinding;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** quarkus.resilience4cata.metrics.enabled=false binds the registry to neither Micrometer nor OpenTelemetry. */
class MetricsDisabledTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Tracked.class, Tracked$Resilient.class))
            .overrideConfigKey("quarkus.resilience4cata.metrics.enabled", "false")
            .overrideConfigKey("quarkus.micrometer.enabled", "false")
            .overrideConfigKey("quarkus.otel.metrics.enabled", "true")
            .overrideConfigKey("quarkus.otel.traces.enabled", "false")
            .overrideConfigKey("quarkus.otel.exporter.otlp.enabled", "false");

    @Inject
    Instance<OpenTelemetryBinding> openTelemetry;

    @Inject
    Instance<MicrometerBinding> micrometer;

    @Test
    void noBinding() {
        assertThat(openTelemetry.isResolvable()).isFalse();
        assertThat(micrometer.isResolvable()).isFalse();
    }
}
