package dev.resilience4cata.examples.docs.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.resilience4cata.AdaptiveLimitConfig;
import dev.resilience4cata.AdaptiveLimitFullException;
import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.opentelemetry.OpenTelemetryMetrics;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The OTLP setup of {@code docs/en/guides/opentelemetry.md} with the real OTLP/HTTP exporter, against an OTLP receiver
 * in this process that decodes the requests with the protocol's own classes. Every policy makes its metrics, and
 * what arrives on the wire must match the guide's metric table: the same names, instrument types, units, attribute
 * keys and attribute values, cumulative monotonic sums, and the library's scope.
 */
class OtlpExportTest {

    private static final Path GUIDE = Path.of("../docs/en/guides/opentelemetry.md");

    private final List<ExportMetricsServiceRequest> received = new CopyOnWriteArrayList<>();
    private HttpServer receiver;

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext("/v1/metrics", exchange -> {
            try (InputStream body = exchange.getRequestBody()) {
                InputStream in = "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))
                        ? new GZIPInputStream(body) : body;
                received.add(ExportMetricsServiceRequest.parseFrom(in));
            }
            byte[] response = ExportMetricsServiceResponse.getDefaultInstance().toByteArray();
            exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    @Test
    void whatTheOtlpExporterSendsMatchesTheGuidesTable() throws Exception {
        String collector = "http://localhost:" + receiver.getAddress().getPort();
        // tag::opentelemetry-otlp[]
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(SdkMeterProvider.builder()
                        .registerMetricReader(PeriodicMetricReader.builder(OtlpHttpMetricExporter.builder()
                                        .setEndpoint(collector + "/v1/metrics") // http://localhost:4318/v1/metrics by default
                                        .build())
                                .setInterval(Duration.ofSeconds(30))
                                .build())
                        .build())
                .build();
        // end::opentelemetry-otlp[]
        try (openTelemetry) {
            OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(openTelemetry);
            ManualClock clock = new ManualClock();
            ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
            metrics.bindTo(registry);
            everyPolicyMakesItsMetrics(registry, clock);

            assertThat(openTelemetry.getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS).isSuccess())
                    .as("the export to the receiver succeeded").isTrue();
        }

        List<ScopeMetrics> scopes = received.stream().flatMap(r -> r.getResourceMetricsList().stream())
                .flatMap(r -> r.getScopeMetricsList().stream())
                .filter(s -> s.getScope().getName().equals("dev.resilience4cata")).toList();
        assertThat(scopes).as("requests with the library's scope").isNotEmpty()
                .allSatisfy(s -> assertThat(s.getScope().getVersion()).isNotBlank());
        Map<String, Metric> wire = new LinkedHashMap<>();
        scopes.forEach(s -> s.getMetricsList().forEach(m -> wire.putIfAbsent(m.getName(), m)));
        Map<String, Row> table = table();

        assertThat(wire.keySet()).as("every metric of the guide's table, and only those").isEqualTo(table.keySet());
        Set<String> documentedValues = new TreeSet<>();
        table.values().forEach(r -> documentedValues.addAll(r.values()));
        for (Row row : table.values()) {
            Metric metric = wire.get(row.name());
            assertThat(metric.getUnit()).as("%s unit", row.name()).isEqualTo(row.unit());
            assertThat(metric.getDescription()).as("%s description", row.name()).isNotBlank();
            List<NumberDataPoint> points = switch (row.instrument()) {
                case "LongCounter" -> {
                    assertThat(metric.hasSum()).as("%s is a sum", row.name()).isTrue();
                    assertThat(metric.getSum().getIsMonotonic()).as("%s is monotonic", row.name()).isTrue();
                    assertThat(metric.getSum().getAggregationTemporality())
                            .isEqualTo(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE);
                    yield metric.getSum().getDataPointsList();
                }
                case "ObservableLongGauge", "ObservableDoubleGauge" -> {
                    assertThat(metric.hasGauge()).as("%s is a gauge", row.name()).isTrue();
                    yield metric.getGauge().getDataPointsList();
                }
                default -> throw new AssertionError("unknown instrument in the guide: " + row.instrument());
            };
            NumberDataPoint.ValueCase type = row.instrument().equals("ObservableDoubleGauge")
                    ? NumberDataPoint.ValueCase.AS_DOUBLE : NumberDataPoint.ValueCase.AS_INT;
            assertThat(points).as("%s points", row.name()).isNotEmpty().allSatisfy(p -> {
                assertThat(p.getValueCase()).as("%s value type", row.name()).isEqualTo(type);
                assertThat(p.getAttributesList()).extracting(KeyValue::getKey).as("%s attribute keys", row.name())
                        .containsExactlyInAnyOrderElementsOf(row.keys());
                p.getAttributesList().stream().filter(a -> !a.getKey().equals("name"))
                        .forEach(a -> assertThat(documentedValues).as("%s value of %s", row.name(), a.getKey())
                                .contains(a.getValue().getStringValue()));
            });
        }
    }

    /** Events for every counter of the table and instances for every gauge, rates included. */
    private static void everyPolicyMakesItsMetrics(ResilienceRegistry registry, ManualClock clock) throws Exception {
        Pipeline<String> search = Pipeline.builder(registry, "search", String.class)
                .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2).slowCallDuration(Duration.ofSeconds(1))).build();
        search.execute(() -> "a");
        search.execute(() -> "b"); // a full window: both rates have a point
        Pipeline<String> inventory = Pipeline.builder(registry, "inventory", String.class).circuitBreaker().build();
        registry.circuitBreaker("inventory").forceOpen();
        assertThatThrownBy(() -> inventory.execute(() -> "never")).isInstanceOf(CallNotPermittedException.class);

        Pipeline<String> payments = Pipeline.builder(registry, "payments", String.class)
                .retry(r -> r.maxAttempts(2).delay(Duration.ofMillis(10))).build();
        assertThatThrownBy(() -> payments.execute(() -> { throw new IllegalStateException("down"); }))
                .isInstanceOf(IllegalStateException.class);

        Pipeline<String> reports = Pipeline.builder(registry, "reports", String.class).bulkhead(b -> b.maxConcurrent(1)).build();
        reports.execute(() -> {
            assertThatThrownBy(() -> reports.execute(() -> "nested")).isInstanceOf(BulkheadFullException.class);
            return "done";
        });

        Pipeline<String> quotes = Pipeline.builder(registry, "quotes", String.class)
                .rateLimiter(r -> r.permits(1).period(Duration.ofSeconds(1))).build();
        quotes.execute(() -> "first");
        assertThatThrownBy(() -> quotes.execute(() -> "second")).isInstanceOf(RateLimitExceededException.class);

        Pipeline<String> orders = Pipeline.builder(registry, "orders", String.class)
                .adaptiveLimit(a -> a.algorithm(AdaptiveLimitConfig.Algorithm.AIMD).initialLimit(1).minWindowSamples(1))
                .build();
        orders.execute(() -> {
            assertThatThrownBy(() -> orders.execute(() -> "nested")).isInstanceOf(AdaptiveLimitFullException.class);
            clock.advance(Duration.ofSeconds(1)); // the window ends with this call: the limit changes
            return "done";
        });

        Pipeline<String> prices = Pipeline.builder(registry, "prices", String.class)
                .hedge(h -> h.delay(Duration.ofMillis(10)).maxAttempts(3).budgetPercent(0)).build();
        Pipeline<String> strict = Pipeline.builder(registry, "strict", String.class)
                .hedge(h -> h.abortOn(List.of(IllegalStateException.class)).budgetPercent(0)).build();
        CompletableFuture<String> slow = new CompletableFuture<>();
        int[] calls = {0};
        CompletableFuture<String> won = prices.executeAsync(
                () -> calls[0]++ == 0 ? slow : CompletableFuture.completedFuture("fast"));
        clock.advance(Duration.ofMillis(10)); // hedged after the delay; the hedge wins
        prices.executeAsync(() -> CompletableFuture.failedFuture(new IllegalArgumentException("down")));
        strict.executeAsync(() -> CompletableFuture.failedFuture(new IllegalStateException("bad")));
        clock.advance(Duration.ZERO);
        assertThat(won).isCompletedWithValue("fast");

        Pipeline<String> catalog = Pipeline.builder(registry, "catalog", String.class)
                .timeout(t -> t.timeout(Duration.ofSeconds(1))).fallback(e -> "cached").build();
        assertThat(catalog.executeAsync(CompletableFuture::new)).isNotDone();
        clock.advance(Duration.ofSeconds(1)); // timed out, then the fallback
    }

    /** One row of the guide's metric table. */
    private record Row(String name, String instrument, String unit, List<String> keys, List<String> values) {
    }

    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    /** The metric table of the guide's "Metrics" section, by metric name. */
    private static Map<String, Row> table() throws IOException {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(GUIDE)) {
            if (!line.startsWith("| `r4c.")) {
                continue;
            }
            String[] cells = line.split("(?<!\\\\)\\|");
            String name = code(cells[1]).getFirst();
            String instrument = code(cells[2]).getFirst();
            String unit = cells[3].strip().equals("none") ? "" : code(cells[3]).getFirst();
            List<String> keys = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (String piece : splitOutsideParentheses(cells[4])) {
                List<String> codes = code(piece);
                if (!codes.isEmpty()) {
                    keys.add(codes.getFirst());
                    values.addAll(codes.subList(1, codes.size()));
                }
            }
            rows.put(name, new Row(name, instrument, unit, keys, values));
        }
        assertThat(rows).as("rows of the table in " + GUIDE).hasSizeGreaterThan(10);
        return rows;
    }

    private static List<String> code(String cell) {
        List<String> codes = new ArrayList<>();
        Matcher matcher = CODE.matcher(cell);
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
    }

    /** {@code `name`, `to` (`a`, `b`)} → {@code `name`} and {@code `to` (`a`, `b`)}. */
    private static List<String> splitOutsideParentheses(String cell) {
        List<String> pieces = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < cell.length(); i++) {
            char c = cell.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                pieces.add(cell.substring(start, i));
                start = i + 1;
            }
        }
        pieces.add(cell.substring(start));
        return pieces;
    }
}
