package dev.resilience4cata.spring.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.spring.Runners;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The guide's OpenTelemetry wiring ({@link OpenTelemetrySetup}) in a Spring context with an annotated class: the
 * rejection counter and the breaker gauges reach the application's {@code OpenTelemetry}, and closing the context
 * removes them.
 */
class OpenTelemetryTest {

    private static final AttributeKey<String> NAME = AttributeKey.stringKey("name");

    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build()).build();
    /** The SDK as an application's bean, minus close(): the context must not shut the SDK down before the check. */
    private final OpenTelemetry openTelemetry = new OpenTelemetry() {
        @Override
        public TracerProvider getTracerProvider() {
            return sdk.getTracerProvider();
        }

        @Override
        public MeterProvider getMeterProvider() {
            return sdk.getMeterProvider();
        }

        @Override
        public ContextPropagators getPropagators() {
            return sdk.getPropagators();
        }
    };

    private final ApplicationContextRunner runner = Runners.runner()
            .withUserConfiguration(OpenTelemetrySetup.class)
            .withBean(OpenTelemetry.class, () -> openTelemetry)
            .withBean(Quotes.class);

    @AfterEach
    void close() {
        sdk.close();
    }

    @Test
    void countersAndGaugesReachOpenTelemetryUntilTheContextCloses() {
        runner.run(context -> {
            Quotes quotes = context.getBean(Quotes.class);
            assertThat(quotes.quote("ACME")).isEqualTo("ACME:42");
            ResilienceRegistry registry = context.getBean(ResilienceRegistry.class);
            registry.circuitBreaker("quotes").forceOpen();
            assertThatThrownBy(() -> quotes.quote("ACME")).isInstanceOf(CallNotPermittedException.class);

            Collection<MetricData> metrics = reader.collectAllMetrics();
            assertThat(metric(metrics, "r4c.calls.rejected").getLongSumData().getPoints())
                    .filteredOn(p -> "quotes".equals(p.getAttributes().get(NAME)))
                    .extracting(p -> p.getAttributes().get(AttributeKey.stringKey("kind")), LongPointData::getValue)
                    .containsExactly(Tuple.tuple("circuitbreaker", 1L));
            assertThat(metric(metrics, "r4c.circuitbreaker.state").getLongGaugeData().getPoints())
                    .filteredOn(p -> "quotes".equals(p.getAttributes().get(NAME)) && p.getValue() == 1)
                    .extracting(p -> p.getAttributes().get(AttributeKey.stringKey("state")))
                    .containsExactly("forced_open");
        });
        // Counters are cumulative and stay in the SDK; the gauges' callback is removed with the metrics.
        assertThat(reader.collectAllMetrics()).as("closing the context closes the metrics").isNotEmpty()
                .noneMatch(m -> m.getName().equals("r4c.circuitbreaker.state"));
    }

    private static MetricData metric(Collection<MetricData> metrics, String name) {
        return metrics.stream().filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not collected: "
                        + metrics.stream().map(MetricData::getName).toList()));
    }
}
