package dev.resilience4cata.examples.docs.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.opentelemetry.OpenTelemetryMetrics;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The code of {@code docs/en/guides/opentelemetry.md}, run against generated code and a real SDK. */
class OpenTelemetryGuideTest {

    private static final AttributeKey<String> INSTANCE = AttributeKey.stringKey("instance");

    private final AtomicBoolean down = new AtomicBoolean(true);
    private final PaymentGateway delegate = order -> {
        if (down.get()) {
            throw new IllegalStateException("payment provider down");
        }
        return "charged " + order;
    };

    @Test
    void theModuleReportsCountersAndGauges() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        try (SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build()) {
            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder().setMeterProvider(provider).build();

            // tag::opentelemetry-wiring[]
            OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(openTelemetry); // your SDK instance
            ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // adds the counters' listener
                    .build();
            metrics.bindTo(registry); // gauges, read at each collection: later instances are included
            PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
            // end::opentelemetry-wiring[]

            for (int i = 0; i < 2; i++) { // 2 calls × 3 attempts: the 4th attempt opens the breaker
                assertThatThrownBy(() -> payments.charge("o-1")).isInstanceOf(RuntimeException.class);
            }
            assertThatThrownBy(() -> payments.charge("o-2")).isInstanceOf(CallNotPermittedException.class);

            Collection<MetricData> data = reader.collectAllMetrics();
            assertThat(sum(data, "r4c.retry.retries", Attributes.of(INSTANCE, "payments"))).isEqualTo(3);
            assertThat(sum(data, "r4c.retry.gave.up", Attributes.builder().put("instance", "payments")
                    .put("reason", "max_attempts").build())).isEqualTo(1);
            assertThat(sum(data, "r4c.calls.rejected", Attributes.builder().put("instance", "payments")
                    .put("kind", "circuitbreaker").build())).isEqualTo(2); // the retry's 2nd call and o-2
            assertThat(sum(data, "r4c.circuitbreaker.transitions", Attributes.builder().put("instance", "payments")
                    .put("from", "closed").put("to", "open").build())).isEqualTo(1);
            assertThat(data).extracting(MetricData::getName).contains("r4c.circuitbreaker.state",
                    "r4c.bulkhead.concurrent.calls", "r4c.bulkhead.waiting.calls", "r4c.bulkhead.max.concurrent",
                    "r4c.ratelimiter.available.permits");

            // tag::opentelemetry-close[]
            metrics.close(); // at shutdown: removes the gauge callbacks, and the counters stop counting
            // end::opentelemetry-close[]
            assertThat(reader.collectAllMetrics()).extracting(MetricData::getName)
                    .doesNotContain("r4c.circuitbreaker.state");
        }
    }

    @Test
    void prometheusScrapesTheSameNamesAsMicrometer() throws Exception {
        int port = freePort();
        // tag::opentelemetry-prometheus[]
        PrometheusHttpServer prometheus = PrometheusHttpServer.builder().setPort(port).build(); // 9464 by default
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(prometheus).build())
                .build();
        // end::opentelemetry-prometheus[]
        try (openTelemetry) {
            OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(openTelemetry);
            ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()).build();
            metrics.bindTo(registry);
            PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> payments.charge("o-1")).isInstanceOf(RuntimeException.class);
            }

            String scrape = scrape(port);

            assertThat(scrape).contains(
                    "r4c_retry_retries_total{instance=\"payments\",otel_scope_name=\"dev.resilience4cata\"",
                    "r4c_retry_gave_up_total{instance=\"payments\",otel_scope_name=\"dev.resilience4cata\"",
                    "r4c_calls_rejected_total{instance=\"payments\",kind=\"circuitbreaker\"",
                    "r4c_circuitbreaker_transitions_total{from=\"closed\",instance=\"payments\"",
                    "r4c_circuitbreaker_state{instance=\"payments\"",
                    "r4c_bulkhead_max_concurrent{instance=\"payments\"",
                    "r4c_ratelimiter_available_permits{instance=\"payments\"");
            assertThat(scrape).containsPattern("r4c_circuitbreaker_state\\{instance=\"payments\",[^}]*state=\"open\"} 1");
            assertThat(scrape).doesNotContain("r4c_circuitbreaker_failure_rate"); // open: no window, no point

            registry.circuitBreaker("payments").reset();
            down.set(false);
            for (int i = 0; i < 4; i++) {
                payments.charge("o-" + i);
            }
            assertThat(scrape(port)).containsPattern("r4c_circuitbreaker_failure_rate_percent\\{instance=\"payments\",[^}]*} 0");
        }
    }

    private static String scrape(int port) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics")).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    private static long sum(Collection<MetricData> data, String name, Attributes attributes) {
        return data.stream().filter(m -> m.getName().equals(name))
                .flatMap(m -> m.getLongSumData().getPoints().stream())
                .filter(p -> p.getAttributes().equals(attributes))
                .mapToLong(LongPointData::getValue).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " " + attributes + " in " + data));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
