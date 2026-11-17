package dev.resilience4cata.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.event.AdaptiveLimitEvent;
import dev.resilience4cata.event.BulkheadEvent;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.HedgeEvent;
import dev.resilience4cata.event.RateLimiterEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.event.TimeoutEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An event whose attributes are cached allocates nothing in this module, and nothing in the SDK's synchronous
 * {@code add} either once its series exists: the guide's "allocates nothing beyond the event record itself".
 */
class AllocationTest {

    private static final int EVENTS_PER_ROUND = 25_000;

    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final SdkMeterProvider meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    private final OpenTelemetryMetrics metrics =
            OpenTelemetryMetrics.create(OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build());

    @AfterEach
    void close() {
        metrics.close();
        meterProvider.close();
    }

    /** One event of every type the listener counts, built once: only the listener and the SDK are measured. */
    private static final List<ResilienceEvent> EVENTS = List.of(
            new CircuitBreakerEvent.StateTransition("hot", 0, CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN),
            new CircuitBreakerEvent.Rejected("hot", 0, CircuitBreakerState.OPEN),
            new RateLimiterEvent.Rejected("hot", 0),
            new BulkheadEvent.Rejected("hot", 0, 4),
            new AdaptiveLimitEvent.Rejected("hot", 0, 4),
            new AdaptiveLimitEvent.LimitChanged("hot", 0, 4, 5, Duration.ofMillis(3)),
            new RetryEvent.Retrying("hot", 0, 1, Duration.ZERO, null),
            new RetryEvent.GaveUp("hot", 0, 3, RetryEvent.Reason.MAX_ATTEMPTS, null),
            new TimeoutEvent.TimedOut("hot", 0, Duration.ofSeconds(1)),
            new FallbackEvent.Applied("hot", 0, new IllegalStateException()),
            new HedgeEvent.Hedged("hot", 0, 2, HedgeEvent.Reason.DELAY),
            new HedgeEvent.Won("hot", 0, 2),
            new HedgeEvent.Skipped("hot", 0, 2, HedgeEvent.SkipReason.BUDGET_EXHAUSTED),
            new HedgeEvent.Failed("hot", 0, 2, new IllegalStateException()));

    @Test
    void cachedEventsAllocateNothingInTheListenerNorInTheSdk() {
        EventListener<ResilienceEvent> listener = metrics.listener();
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long published = 0;
        for (int i = 0; i < 20_000; i++) { // first events build the attributes and the SDK's series; then the JIT
            publishAll(listener);
            published++;
        }

        // Measured in rounds until the JIT is done (a slow CI machine may not be after the warm-up); a real
        // allocation shows in every round.
        long allocated = Long.MAX_VALUE;
        int rounds = 0;
        while (rounds < 20 && allocated >= 1024) {
            rounds++;
            long before = threads.getThreadAllocatedBytes(thread);
            for (int i = 0; i < EVENTS_PER_ROUND; i++) {
                publishAll(listener);
            }
            allocated = Math.min(allocated, threads.getThreadAllocatedBytes(thread) - before);
            published += EVENTS_PER_ROUND;
        }

        assertThat(allocated).as("bytes allocated by %d cached events of %d types (the measurement may allocate a little)",
                (long) EVENTS_PER_ROUND * EVENTS.size(), EVENTS.size()).isLessThan(1024);
        List<MetricData> collected = List.copyOf(reader.collectAllMetrics());
        assertThat(sum(collected, "r4c.retry.retries", Attributes.of(AttributeKey.stringKey("name"), "hot")))
                .as("every event was counted").isEqualTo(published);
        assertThat(sum(collected, "r4c.calls.rejected", Attributes.builder().put("name", "hot")
                .put("kind", "adaptivelimit").build())).isEqualTo(published);
        assertThat(collected).extracting(MetricData::getName).filteredOn(n -> n.startsWith("r4c."))
                .as("one counter per event type but the four rejections, which share one")
                .hasSize(EVENTS.size() - 3);
    }

    private static void publishAll(EventListener<ResilienceEvent> listener) {
        for (ResilienceEvent event : EVENTS) {
            listener.onEvent(event);
        }
    }

    private static long sum(List<MetricData> collected, String name, Attributes attributes) {
        return collected.stream().filter(m -> m.getName().equals(name))
                .flatMap(m -> m.getLongSumData().getPoints().stream())
                .filter(p -> p.getAttributes().equals(attributes)).mapToLong(LongPointData::getValue).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " " + attributes + " in " + collected));
    }
}
