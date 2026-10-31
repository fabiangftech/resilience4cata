package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.micrometer.MicrometerMetrics;
import dev.resilience4cata.opentelemetry.OpenTelemetryMetrics;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The Micrometer and OpenTelemetry modules report the same metrics with the same help text, so that a
 * dashboard or an alert written for one reads the same with the other. Both modules watch one registry
 * here, and every metric of the specification is produced at least once.
 */
class MetricsParityTest {

    private final ManualClock clock = new ManualClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();

    @Test
    void bothModulesReportTheSameMetricsWithTheSameDescriptions() throws Exception {
        MicrometerMetrics micrometer = MicrometerMetrics.create(meters);
        OpenTelemetryMetrics openTelemetry = OpenTelemetryMetrics.create(provider);
        ResilienceRegistry registry = micrometer.register(openTelemetry.register(ResilienceTesting.registry(clock))).build();
        micrometer.bindTo(registry);
        openTelemetry.bindTo(registry);

        produceEveryMetric(registry);

        Map<String, String> fromMicrometer = new TreeMap<>();
        for (Meter meter : meters.getMeters()) {
            fromMicrometer.put(meter.getId().getName(), meter.getId().getDescription());
        }
        Map<String, String> fromOpenTelemetry = new TreeMap<>();
        for (MetricData metric : reader.collectAllMetrics()) {
            fromOpenTelemetry.put(metric.getName(), metric.getDescription());
        }

        assertThat(fromMicrometer).as("every metric of the specification").hasSize(20);
        assertThat(fromOpenTelemetry).isEqualTo(fromMicrometer);
        provider.close();
    }

    private void produceEveryMetric(ResilienceRegistry registry) throws Exception {
        // Retries and give-ups.
        Pipeline<String> retried = Pipeline.builder(registry, "payments", String.class)
                .retry(r -> r.maxAttempts(2).delay(Duration.ZERO)).build();
        assertThatThrownBy(() -> retried.execute(() -> {
            throw new IOException("down");
        })).isInstanceOf(IOException.class);

        // A breaker whose window is full, with slow-call detection: both rates have a value.
        Pipeline<String> measured = Pipeline.builder(registry, "search", String.class)
                .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2).slowCallDuration(Duration.ofSeconds(1)))
                .build();
        measured.execute(() -> "a");
        measured.execute(() -> "b");

        // A breaker that opens and rejects: transitions, rejections, state.
        Pipeline<String> opened = Pipeline.builder(registry, "inventory", String.class)
                .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2).failureRateThreshold(50)).build();
        opened.execute(() -> "ok");
        assertThatThrownBy(() -> opened.execute(() -> {
            throw new IOException("down");
        })).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> opened.execute(() -> "ok")).isInstanceOf(dev.resilience4cata.CallNotPermittedException.class);

        // Timeouts and fallbacks.
        Pipeline<String> timed = Pipeline.builder(registry, "catalog", String.class)
                .timeout(t -> t.timeout(Duration.ofSeconds(1))).build();
        assertThatThrownBy(() -> timed.execute(() -> {
            clock.sleep(Duration.ofSeconds(2));
            return "late";
        })).isInstanceOf(dev.resilience4cata.CallTimeoutException.class);
        Pipeline<String> fallback = Pipeline.builder(registry, "prices", String.class).fallback(failure -> "cached").build();
        assertThat(fallback.execute(() -> {
            throw new UncheckedIOException(new IOException("down"));
        })).isEqualTo("cached");

        // Hedges: one that wins after the delay, one where every attempt fails, one skipped by its budget.
        Pipeline<String> hedged = Pipeline.builder(registry, "lookups", String.class)
                .hedge(h -> h.delay(Duration.ofMillis(10)).budgetPercent(0)).build();
        CompletableFuture<String> slow = new CompletableFuture<>();
        int[] calls = {0};
        CompletableFuture<String> won = hedged.executeAsync(() -> calls[0]++ == 0 ? slow : CompletableFuture.completedFuture("fast"));
        clock.advance(Duration.ofMillis(10));
        assertThat(won).isCompletedWithValue("fast");
        CompletableFuture<String> failed = hedged.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down")));
        clock.advance(Duration.ZERO); // the hedge after a failure starts at the next advance of the clock
        assertThat(failed).isCompletedExceptionally();
        Pipeline<String> rare = Pipeline.builder(registry, "rare", String.class)
                .hedge(h -> h.budgetMinHedgesPerSecond(0)).build();
        CompletableFuture<String> skipped = rare.executeAsync(() -> CompletableFuture.failedFuture(new IOException("down")));
        clock.advance(Duration.ZERO); // the budget is asked when the hedge would start
        assertThat(skipped).isCompletedExceptionally();

        // An adaptive limit that rejects and moves its limit: rejections, limit changes, gauges.
        Pipeline<String> adaptive = Pipeline.builder(registry, "orders", String.class)
                .adaptiveLimit(a -> a.algorithm(dev.resilience4cata.AdaptiveLimitConfig.Algorithm.AIMD).initialLimit(1)
                        .minWindowSamples(1))
                .build();
        adaptive.execute(() -> {
            assertThatThrownBy(() -> adaptive.execute(() -> "nested")).isInstanceOf(dev.resilience4cata.AdaptiveLimitFullException.class);
            clock.advance(Duration.ofSeconds(1));
            return "o";
        });

        // Bulkhead and rate limiter gauges.
        Pipeline.builder(registry, "reports", String.class).bulkhead(b -> b.maxConcurrent(1)).build().execute(() -> "r");
        Pipeline.builder(registry, "quotes", String.class)
                .rateLimiter(r -> r.permits(2).period(Duration.ofSeconds(1))).build().execute(() -> "q");
    }
}
