package dev.resilience4cata.examples.docs.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.micrometer.MicrometerMetrics;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The code of docs/en/guides/micrometer.md, end to end against generated code. */
class MicrometerGuideTest {

    private final PaymentGateway delegate = order -> {
        throw new UncheckedIOException(new IOException("gateway down"));
    };

    @Test
    void theThreeLineSetupReportsAnnotatedServices() {
        // tag::micrometer-setup[]
        MeterRegistry meterRegistry = new SimpleMeterRegistry(); // in your app: the registry you already export
        MicrometerMetrics metrics = MicrometerMetrics.create(meterRegistry);
        ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // counters, from events
                .build();
        metrics.bindTo(registry); // gauges, for every breaker, bulkhead and rate limiter, even those created later
        // end::micrometer-setup[]
        assertThat(meterRegistry.getMeters()).as("nothing exists before a service is wrapped").isEmpty();

        PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);

        assertThat(meterRegistry.get("r4c.circuitbreaker.state").tags("name", "payments", "state", "closed").gauge()
                .value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("r4c.circuitbreaker.failure.rate").tag("name", "payments").gauge().value()).isNaN();
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> payments.charge("o-1")).isInstanceOf(RuntimeException.class);
        }
        assertThat(meterRegistry.get("r4c.retry.retries").tag("name", "payments").counter().count()).isEqualTo(3.0);
        assertThat(meterRegistry.get("r4c.retry.gave.up").tags("name", "payments", "reason", "max_attempts").counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("r4c.retry.gave.up").tags("name", "payments", "reason", "not_retryable").counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("r4c.circuitbreaker.transitions").tags("name", "payments", "from", "closed", "to", "open")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("r4c.calls.rejected").tags("name", "payments", "kind", "circuitbreaker").counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("r4c.circuitbreaker.state").tags("name", "payments", "state", "open").gauge()
                .value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("r4c.bulkhead.max.concurrent").tag("name", "payments").gauge().value()).isEqualTo(10.0);
        assertThatThrownBy(() -> payments.charge("o-2")).isInstanceOf(CallNotPermittedException.class);
        assertThat(meterRegistry.get("r4c.calls.rejected").tags("name", "payments", "kind", "circuitbreaker").counter()
                .count()).isEqualTo(2.0);

        // tag::micrometer-close[]
        metrics.close(); // removes every meter it registered; the services keep working
        // end::micrometer-close[]
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void pipelinesAreReportedUnderTheirId() throws Exception {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        MicrometerMetrics metrics = MicrometerMetrics.create(meterRegistry);
        ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()).build();
        metrics.bindTo(registry);

        Pipeline<String> inventory = Pipeline.builder(registry, "inventory", String.class)
                .rateLimiter(r -> r.permits(5))
                .fallback(failure -> "unknown")
                .build();
        assertThat(inventory.execute(() -> {
            throw new IOException("down");
        })).isEqualTo("unknown");

        assertThat(meterRegistry.get("r4c.fallback.applied").tag("name", "inventory").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("r4c.ratelimiter.available.permits").tag("name", "inventory").gauge().value())
                .isEqualTo(4.0);
    }

    @Test
    void thePrometheusSampleOnThePageIsARealScrape() throws IOException {
        ManualClock clock = new ManualClock();
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerMetrics metrics = MicrometerMetrics.create(prometheus);
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        metrics.bindTo(registry);
        PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> payments.charge("o-1")).isInstanceOf(RuntimeException.class);
        }

        List<String> scraped = prometheus.scrape().lines().filter(line -> line.startsWith("r4c_")).toList();

        assertThat(scraped).isEqualTo(sample());
    }

    /** The sample shown on the page: the data lines of the scrape, without # HELP and # TYPE. */
    private static List<String> sample() throws IOException {
        try (InputStream in = MicrometerGuideTest.class.getResourceAsStream("/docs/micrometer/prometheus-sample.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(text.split("\n")).filter(line -> line.startsWith("r4c_")).toList();
        }
    }
}
