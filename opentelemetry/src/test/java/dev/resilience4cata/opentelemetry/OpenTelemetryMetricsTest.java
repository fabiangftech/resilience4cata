package dev.resilience4cata.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.BulkheadFullException;
import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.generated.TimeoutPolicy;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenTelemetryMetricsTest {

    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final SdkMeterProvider meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    private final OpenTelemetryMetrics metrics =
            OpenTelemetryMetrics.create(OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build());
    private final ManualClock clock = new ManualClock();

    @AfterEach
    void closeProvider() {
        metrics.close();
        meterProvider.close();
    }

    @Test
    void retriesAndGiveUpsAreCountedByReason() {
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        Pipeline<String> pipeline = Pipeline.builder(registry, "payments", String.class)
                .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(10)).abortOn(List.of(IllegalArgumentException.class)))
                .build();

        assertThatThrownBy(() -> pipeline.execute(() -> { throw new IllegalStateException("down"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pipeline.execute(() -> { throw new IllegalArgumentException("bad"); }))
                .isInstanceOf(IllegalArgumentException.class);

        collect();
        assertThat(counter("r4c.retry.retries", Attributes.of(key("instance"), "payments"))).isEqualTo(2);
        assertThat(counter("r4c.retry.gave.up", attrs("instance", "payments", "reason", "max_attempts"))).isEqualTo(1);
        assertThat(counter("r4c.retry.gave.up", attrs("instance", "payments", "reason", "not_retryable"))).isEqualTo(1);
        assertThat(metric("r4c.retry.retries").getUnit()).isEqualTo("{retry}");
        assertThat(metric("r4c.retry.retries").getLongSumData().isMonotonic()).isTrue();
    }

    @Test
    void theBreakerIsReportedWithStatesRatesTransitionsAndRejections() {
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        metrics.bindTo(registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "inventory", String.class)
                .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2).slowCallDuration(Duration.ofSeconds(1)))
                .build();

        assertThatThrownBy(() -> pipeline.execute(() -> { throw new IllegalStateException(); }))
                .isInstanceOf(IllegalStateException.class);
        collect();
        assertThat(gauge("r4c.circuitbreaker.state", attrs("instance", "inventory", "state", "closed"))).contains(1.0);
        // one call in a window of minimumCalls = 2: no rate yet (NaN is not a valid point)
        assertThat(gauge("r4c.circuitbreaker.failure.rate", instance("inventory"))).isEmpty();
        assertThat(gauge("r4c.circuitbreaker.slow.call.rate", instance("inventory"))).isEmpty();

        assertThatThrownBy(() -> pipeline.execute(() -> {
            clock.advance(Duration.ofSeconds(2)); // slow, and a failure
            throw new IllegalStateException();
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pipeline.execute(() -> "never runs")).isInstanceOf(CallNotPermittedException.class);

        collect();
        assertThat(gauges("r4c.circuitbreaker.state")).isEqualTo(Map.of(
                "instance=inventory,state=closed", 0.0, "instance=inventory,state=open", 1.0,
                "instance=inventory,state=half_open", 0.0, "instance=inventory,state=forced_open", 0.0,
                "instance=inventory,state=disabled", 0.0));
        // open: no window, so no rates
        assertThat(gauge("r4c.circuitbreaker.failure.rate", instance("inventory"))).isEmpty();
        assertThat(gauge("r4c.circuitbreaker.slow.call.rate", instance("inventory"))).isEmpty();
        assertThat(counter("r4c.circuitbreaker.transitions", attrs("instance", "inventory", "from", "closed", "to", "open")))
                .isEqualTo(1);
        assertThat(counter("r4c.calls.rejected", attrs("instance", "inventory", "kind", "circuitbreaker"))).isEqualTo(1);

        CircuitBreakerHandle breaker = registry.circuitBreaker("inventory");
        breaker.forceOpen();
        collect();
        assertThat(gauge("r4c.circuitbreaker.state", attrs("instance", "inventory", "state", "forced_open"))).contains(1.0);
        assertThat(counter("r4c.circuitbreaker.transitions",
                attrs("instance", "inventory", "from", "open", "to", "forced_open"))).isEqualTo(1);
    }

    @Test
    void ratesAreReportedInPercentOnceTheWindowHoldsMinimumCalls() throws Exception {
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        metrics.bindTo(registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "search", String.class)
                .circuitBreaker(cb -> cb.windowSize(4).minimumCalls(4).slowCallDuration(Duration.ofSeconds(1)))
                .build();
        pipeline.execute(() -> {
            clock.advance(Duration.ofSeconds(2));
            return "slow";
        });
        assertThatThrownBy(() -> pipeline.execute(() -> { throw new IllegalStateException(); }))
                .isInstanceOf(IllegalStateException.class);
        pipeline.execute(() -> "fast");
        collect();
        assertThat(gauge("r4c.circuitbreaker.failure.rate", instance("search"))).isEmpty(); // 3 calls of 4

        pipeline.execute(() -> "fast");
        collect();
        assertThat(gauge("r4c.circuitbreaker.slow.call.rate", instance("search"))).contains(25.0);
        assertThat(gauge("r4c.circuitbreaker.failure.rate", instance("search"))).contains(25.0);
        assertThat(metric("r4c.circuitbreaker.failure.rate").getUnit()).isEqualTo("%");
        assertThat(metric("r4c.circuitbreaker.slow.call.rate").getUnit()).isEqualTo("%");
        assertThat(metric("r4c.circuitbreaker.failure.rate").getType()).isEqualTo(MetricDataType.DOUBLE_GAUGE);
    }

    @Test
    void bulkheadGaugesAndRejections() throws Exception {
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        metrics.bindTo(registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "reports", String.class)
                .bulkhead(b -> b.maxConcurrent(1))
                .build();

        String result = pipeline.execute(() -> {
            collect(); // inside the call: one slot taken
            assertThatThrownBy(() -> pipeline.execute(() -> "nested")).isInstanceOf(BulkheadFullException.class);
            return "done";
        });
        assertThat(result).isEqualTo("done");
        assertThat(gauge("r4c.bulkhead.concurrent.calls", instance("reports"))).contains(1.0);

        collect();
        assertThat(gauge("r4c.bulkhead.concurrent.calls", instance("reports"))).contains(0.0);
        assertThat(gauge("r4c.bulkhead.waiting.calls", instance("reports"))).contains(0.0);
        assertThat(gauge("r4c.bulkhead.max.concurrent", instance("reports"))).contains(1.0);
        assertThat(metric("r4c.bulkhead.max.concurrent").getType()).isEqualTo(MetricDataType.LONG_GAUGE);
        assertThat(counter("r4c.calls.rejected", attrs("instance", "reports", "kind", "bulkhead"))).isEqualTo(1);
    }

    @Test
    void rateLimiterGaugeAndRejections() throws Exception {
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        metrics.bindTo(registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "quotes", String.class)
                .rateLimiter(r -> r.permits(2).period(Duration.ofSeconds(1)))
                .build();

        pipeline.execute(() -> "first");
        collect();
        assertThat(gauge("r4c.ratelimiter.available.permits", instance("quotes"))).contains(1.0);
        pipeline.execute(() -> "second");
        assertThatThrownBy(() -> pipeline.execute(() -> "third")).isInstanceOf(RateLimitExceededException.class);

        collect();
        assertThat(gauge("r4c.ratelimiter.available.permits", instance("quotes"))).contains(0.0);
        assertThat(counter("r4c.calls.rejected", attrs("instance", "quotes", "kind", "ratelimiter"))).isEqualTo(1);
        assertThat(metric("r4c.ratelimiter.available.permits").getUnit()).isEqualTo("{permit}");
    }

    @Test
    void timeoutsAndFallbacksAreCounted() throws Exception {
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        TimeoutPolicy timeout = registry.timeoutPolicy("slow", "", "", t -> t.timeout(Duration.ofSeconds(1)));
        CompletableFuture<String> timedOut = timeout.executeAsync(CompletableFuture::new);
        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(timedOut::get).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CallTimeoutException.class);

        Pipeline<String> pipeline = Pipeline.builder(registry, "catalog", String.class).fallback(e -> "cached").build();
        assertThat(pipeline.execute(() -> { throw new IllegalStateException(); })).isEqualTo("cached");

        collect();
        assertThat(counter("r4c.timeout.timed.out", instance("slow"))).isEqualTo(1);
        assertThat(counter("r4c.fallback.applied", instance("catalog"))).isEqualTo(1);
    }

    @Test
    void instancesCreatedAfterBindingAreReportedAtTheNextCollection() {
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        metrics.bindTo(registry);
        collect();
        assertThat(gauges("r4c.bulkhead.max.concurrent")).isEmpty();

        Pipeline.builder(registry, "late", String.class).bulkhead(b -> b.maxConcurrent(7)).build();
        Pipeline.builder(registry, "later", String.class).circuitBreaker().build();
        collect();
        assertThat(gauge("r4c.bulkhead.max.concurrent", instance("late"))).contains(7.0);
        assertThat(gauge("r4c.circuitbreaker.state", attrs("instance", "later", "state", "closed"))).contains(1.0);
    }

    @Test
    void closeRemovesTheGaugesAndStopsTheCounters() throws Exception {
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        metrics.bindTo(registry);
        Pipeline<String> pipeline = Pipeline.builder(registry, "orders", String.class)
                .rateLimiter(r -> r.permits(1).period(Duration.ofSeconds(1)))
                .build();
        pipeline.execute(() -> "ok");
        assertThatThrownBy(() -> pipeline.execute(() -> "rejected")).isInstanceOf(RateLimitExceededException.class);
        collect();
        assertThat(gauges("r4c.ratelimiter.available.permits")).isNotEmpty();

        metrics.close();
        metrics.close(); // idempotent
        assertThatThrownBy(() -> pipeline.execute(() -> "rejected")).isInstanceOf(RateLimitExceededException.class);
        collect();
        assertThat(gauges("r4c.ratelimiter.available.permits")).isEmpty();
        assertThat(counter("r4c.calls.rejected", attrs("instance", "orders", "kind", "ratelimiter"))).isEqualTo(1);
        assertThatThrownBy(() -> metrics.bindTo(registry)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theScopeIsTheLibraryWithItsVersion() throws Exception {
        ResilienceRegistry registry = metrics.register(ResilienceTesting.registry(clock)).build();
        Pipeline<String> pipeline = Pipeline.builder(registry, "x", String.class).fallback(e -> "y").build();
        pipeline.execute(() -> { throw new IllegalStateException(); });
        collect();
        assertThat(metric("r4c.fallback.applied").getInstrumentationScopeInfo().getName()).isEqualTo("dev.resilience4cata");
        assertThat(OpenTelemetryMetrics.version()).isNotBlank().doesNotStartWith("$");
        assertThat(metric("r4c.fallback.applied").getInstrumentationScopeInfo().getVersion())
                .isEqualTo(OpenTelemetryMetrics.version());
    }

    @Test
    void theListenerNeverThrowsEvenIfOpenTelemetryDoes() throws Exception {
        OpenTelemetryMetrics failing = OpenTelemetryMetrics.create(throwingOn("add", MeterProvider.class));
        ResilienceRegistry registry = failing.register(ResilienceTesting.registry(clock)).build();
        Pipeline<String> pipeline = Pipeline.builder(registry, "x", String.class).fallback(e -> "y").build();
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        Thread thread = Thread.currentThread();
        Thread.UncaughtExceptionHandler previous = thread.getUncaughtExceptionHandler();
        thread.setUncaughtExceptionHandler((t, e) -> uncaught.add(e)); // where core sends a listener's exception
        try {
            assertThat(pipeline.execute(() -> { throw new IllegalStateException(); })).isEqualTo("y");
        } finally {
            thread.setUncaughtExceptionHandler(previous);
        }
        assertThat(uncaught).isEmpty();
    }

    /** An OpenTelemetry API object whose methods named {@code failing} throw; others return more of the same. */
    private static <T> T throwingOn(String failing, Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getName().equals(failing)) {
                throw new IllegalStateException("broken exporter");
            }
            Class<?> returns = method.getReturnType();
            return returns.isInterface() ? throwingOn(failing, returns) : null;
        }));
    }

    @Test
    void attributeSetsAreBuiltOnceAndReused() {
        assertThat(metrics.attributes("payments")).isSameAs(metrics.attributes("payments"));
    }

    // --- collection helpers

    private Collection<MetricData> collected = List.of();

    private void collect() {
        collected = reader.collectAllMetrics();
    }

    private MetricData metric(String name) {
        return collected.stream().filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no metric " + name + " in " + collected));
    }

    private long counter(String name, Attributes attributes) {
        return metric(name).getLongSumData().getPoints().stream()
                .filter(p -> p.getAttributes().equals(attributes)).mapToLong(LongPointData::getValue).findFirst()
                .orElseThrow(() -> new AssertionError("no point " + attributes + " in " + metric(name)));
    }

    /** Every gauge point of a metric, by its attributes as {@code k=v,k=v} (sorted keys); empty if absent. */
    private Map<String, Double> gauges(String name) {
        Map<String, Double> points = new TreeMap<>();
        for (MetricData m : collected) {
            if (!m.getName().equals(name)) {
                continue;
            }
            if (m.getType() == MetricDataType.LONG_GAUGE) {
                m.getLongGaugeData().getPoints().forEach(p -> points.put(text(p.getAttributes()), (double) p.getValue()));
            } else {
                for (DoublePointData p : m.getDoubleGaugeData().getPoints()) {
                    points.put(text(p.getAttributes()), p.getValue());
                }
            }
        }
        return points;
    }

    private Optional<Double> gauge(String name, Attributes attributes) {
        return Optional.ofNullable(gauges(name).get(text(attributes)));
    }

    private static String text(Attributes attributes) {
        Map<String, String> sorted = new TreeMap<>();
        attributes.forEach((k, v) -> sorted.put(k.getKey(), String.valueOf(v)));
        return String.join(",", sorted.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
    }

    private static Attributes instance(String id) {
        return Attributes.of(key("instance"), id);
    }

    private static Attributes attrs(String... keyValues) {
        var builder = Attributes.builder();
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.put(keyValues[i], keyValues[i + 1]);
        }
        return builder.build();
    }

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }
}
