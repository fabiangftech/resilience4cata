package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.quarkus.deployment.fixtures.Tracked;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Shared steps of the metrics binding tests (OpenTelemetryBindingTest, MetricsBindingChoiceTest...). */
final class MetricsAssertions {

    static final String REJECTED = "r4c.calls.rejected";
    static final String STATE = "r4c.circuitbreaker.state";
    private static final AttributeKey<String> NAME = AttributeKey.stringKey("name");

    private MetricsAssertions() {
    }

    /** One successful call, then one rejected by the forced-open breaker "tracked". */
    static void rejectOneCall(Tracked tracked, ResilienceRegistry registry) {
        assertThat(tracked.call()).isEqualTo("ok");
        registry.circuitBreaker("tracked").forceOpen();
        try {
            assertThatThrownBy(tracked::call).isInstanceOf(CallNotPermittedException.class);
        } finally {
            registry.circuitBreaker("tracked").reset();
        }
    }

    /** The latest exported metric of that name with a point for the instance "tracked", waiting for an export. */
    static MetricData awaitExported(InMemoryMetricExporter exporter, String metric) throws InterruptedException {
        return awaitExported(exporter, metric, point -> true);
    }

    /**
     * The latest exported metric of that name with a point of the instance "tracked" that matches, waiting for an
     * export. A gauge needs the condition: an export taken while {@link #rejectOneCall} held the breaker forced open
     * has the "tracked" points too, with {@code forced_open} at 1.
     */
    static MetricData awaitExported(InMemoryMetricExporter exporter, String metric, Predicate<PointData> point)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() - deadline < 0) {
            Optional<MetricData> found = latest(exporter, metric, point);
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError(metric + " was never exported; exported: "
                + exporter.getFinishedMetricItems().stream().map(MetricData::getName).distinct().toList());
    }

    /** The names of every exported resilience4cata metric. */
    static List<String> exportedResilienceMetrics(InMemoryMetricExporter exporter) {
        return exporter.getFinishedMetricItems().stream().map(MetricData::getName).filter(n -> n.startsWith("r4c."))
                .distinct().toList();
    }

    private static Optional<MetricData> latest(InMemoryMetricExporter exporter, String metric,
            Predicate<PointData> point) {
        List<MetricData> items = exporter.getFinishedMetricItems();
        for (int i = items.size() - 1; i >= 0; i--) {
            MetricData data = items.get(i);
            if (data.getName().equals(metric)
                    && data.getData().getPoints().stream().anyMatch(p -> "tracked".equals(name(p)) && point.test(p))) {
                return Optional.of(data);
            }
        }
        return Optional.empty();
    }

    static String name(PointData point) {
        return point.getAttributes().get(NAME);
    }
}
