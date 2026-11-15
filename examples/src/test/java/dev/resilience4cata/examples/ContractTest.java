package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.micrometer.MicrometerMetrics;
import dev.resilience4cata.opentelemetry.OpenTelemetryMetrics;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What {@code docs/en/reference/compatibility.md} promises beyond the Java API, compared with the files of the
 * repository's {@code contract} directory (see {@link Contract}): metric names, types, units and tags in both metrics
 * modules, the JSON of the endpoints ({@code ResilienceSnapshot.toMap()}), and the error codes. The configuration keys
 * and the integrations' property names are checked in their own modules. Every metric and every snapshot kind is
 * produced by {@link MetricsParityTest#produceEveryMetric}.
 */
class ContractTest {

    /** A section of the error reference: {@code ### R4C-ATR-001 ...}. */
    private static final Pattern ERROR_HEADING = Pattern.compile("^### (R4C-[A-Z]+-\\d{3})\\b");

    private final ManualClock clock = new ManualClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    private ResilienceRegistry registry;

    @BeforeEach
    void produceEverything() throws Exception {
        MicrometerMetrics micrometer = MicrometerMetrics.create(meters);
        OpenTelemetryMetrics openTelemetry = OpenTelemetryMetrics.create(provider);
        registry = micrometer.register(openTelemetry.register(ResilienceTesting.registry(clock))).build();
        micrometer.bindTo(registry);
        openTelemetry.bindTo(registry);
        MetricsParityTest.produceEveryMetric(registry, clock);
        // The rejections the scenario does not produce: each kind is a literal of its own in both modules.
        Pipeline<String> bulkhead = Pipeline.builder(registry, "contract-bulkhead", String.class)
                .bulkhead(b -> b.maxConcurrent(1)).build();
        bulkhead.execute(() -> {
            assertThatThrownBy(() -> bulkhead.execute(() -> "nested")).isInstanceOf(BulkheadFullException.class);
            return "outer";
        });
        Pipeline<String> limited = Pipeline.builder(registry, "contract-ratelimiter", String.class)
                .rateLimiter(r -> r.permits(1).period(Duration.ofHours(1))).build();
        limited.execute(() -> "first");
        assertThatThrownBy(() -> limited.execute(() -> "second")).isInstanceOf(RateLimitExceededException.class);
        // A retry with a budget, whose snapshot has the budget's keys.
        Pipeline.builder(registry, "contract-budget", String.class).retry(r -> r.budgetPercent(10)).build()
                .execute(() -> "ok");
    }

    /** {@code <module> <metric> ...}: the type and unit, every tag key, and every value of a tag other than {@code name}. */
    @Test
    void metricNamesTypesUnitsAndTags() throws Exception {
        Set<String> lines = new TreeSet<>();
        for (Meter meter : meters.getMeters()) {
            String metric = "micrometer " + meter.getId().getName();
            String unit = meter.getId().getBaseUnit();
            lines.add(metric + " " + meter.getId().getType().name().toLowerCase(Locale.ROOT)
                    + (unit == null ? "" : " unit=" + unit));
            for (Tag tag : meter.getId().getTags()) {
                tag(lines, metric, tag.getKey(), tag.getValue());
            }
        }
        for (MetricData data : reader.collectAllMetrics()) {
            String metric = "opentelemetry " + data.getName();
            lines.add(metric + " " + data.getType().name().toLowerCase(Locale.ROOT)
                    + (data.getUnit().isEmpty() ? "" : " unit=" + data.getUnit()));
            for (PointData point : data.getData().getPoints()) {
                point.getAttributes().forEach((key, value) -> tag(lines, metric, key.getKey(), String.valueOf(value)));
            }
        }
        provider.close();
        Contract.check("metrics.txt", lines);
    }

    private static void tag(Set<String> lines, String metric, String key, String value) {
        lines.add(metric + " tag " + key);
        if (!key.equals("name")) { // the instance id: the application's, not ours
            lines.add(metric + " tag " + key + "=" + value);
        }
    }

    /** {@code <path> <JSON type>}, with {@code []} for the elements of a list. */
    @Test
    void endpointJson() throws Exception {
        provider.close();
        Set<String> lines = new TreeSet<>();
        flatten("", registry.snapshot().toMap(), lines);
        Contract.check("snapshot-json.txt", lines);
    }

    private static void flatten(String path, Object value, Set<String> lines) {
        if (value instanceof Map<?, ?> map) {
            lines.add((path.isEmpty() ? "$" : path) + " object");
            map.forEach((key, child) -> flatten(path.isEmpty() ? key.toString() : path + "." + key, child, lines));
        } else if (value instanceof List<?> list) {
            lines.add(path + " array");
            list.forEach(element -> flatten(path + "[]", element, lines));
        } else {
            lines.add(path + " " + switch (value) {
                case null -> "null";
                case String s -> "string";
                case Boolean b -> "boolean";
                case Number n -> "number";
                default -> throw new AssertionError("not a JSON value at " + path + ": " + value.getClass());
            });
        }
    }

    /** Every code of the error reference, retired ones included: a code is never renumbered or reused. */
    @Test
    void errorCodes() throws Exception {
        provider.close();
        Set<String> codes = new TreeSet<>();
        for (String line : Files.readAllLines(Path.of("..", "docs", "en", "errors.md"))) {
            Matcher matcher = ERROR_HEADING.matcher(line);
            if (matcher.find()) {
                codes.add(matcher.group(1));
            }
        }
        Contract.check("error-codes.txt", codes);
    }
}
