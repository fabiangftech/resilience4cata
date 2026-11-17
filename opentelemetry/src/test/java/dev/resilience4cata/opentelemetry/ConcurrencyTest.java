package dev.resilience4cata.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Events from many threads, for instance ids seen for the first time, while another thread collects and instances
 * are created: every event is counted, nothing throws, and a {@code close()} in the middle of it leaves no gauge.
 * The races of {@code bindTo} and {@code close} themselves are jcstress tests ({@code OpenTelemetryMetricsStress}).
 */
class ConcurrencyTest {

    private static final int THREADS = 8;
    private static final int EVENTS = 19_200; // 300 per id and thread
    private static final int IDS = 64;

    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    private final OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(provider);
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        metrics.close();
        provider.close();
    }

    @Test
    void eventsFromManyThreadsAreAllCountedWhileCollecting() throws InterruptedException {
        EventListener<ResilienceEvent> listener = metrics.listener();
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        metrics.bindTo(registry);
        AtomicBoolean done = new AtomicBoolean();
        Thread collector = Thread.ofPlatform().start(guarded(() -> {
            int instance = 0;
            while (!done.get()) {
                reader.collectAllMetrics(); // gauges read the registry while it gains instances
                Pipeline.builder(registry, "created-" + instance++, String.class).bulkhead().build();
            }
        }));

        runThreads(t -> {
            for (int i = 0; i < EVENTS; i++) {
                listener.onEvent(new RetryEvent.Retrying("id-" + ((t * 7 + i) % IDS), 0, 1, Duration.ZERO, null));
            }
        });
        done.set(true);
        collector.join();

        assertThat(failures).isEmpty();
        Map<String, Long> counted = retries(reader.collectAllMetrics());
        assertThat(counted).hasSize(IDS);
        assertThat(counted.values().stream().mapToLong(Long::longValue).sum()).isEqualTo((long) THREADS * EVENTS);
        assertThat(counted.values()).as("each id gets the same share").containsOnly((long) THREADS * EVENTS / IDS);
    }

    @Test
    void closeInTheMiddleOfEventsAndCollectionsThrowsNothingAndLeavesNoGauge() throws InterruptedException {
        EventListener<ResilienceEvent> listener = metrics.listener();
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        Pipeline.builder(registry, "shared", String.class).circuitBreaker().build();
        metrics.bindTo(registry);
        CountDownLatch halfway = new CountDownLatch(THREADS);
        AtomicBoolean done = new AtomicBoolean();
        Thread collector = Thread.ofPlatform().start(guarded(() -> {
            while (!done.get()) {
                reader.collectAllMetrics();
            }
        }));
        Thread closer = Thread.ofPlatform().start(guarded(() -> {
            halfway.await();
            metrics.close();
        }));

        runThreads(t -> {
            for (int i = 0; i < EVENTS; i++) {
                if (i == EVENTS / 2) {
                    halfway.countDown();
                }
                listener.onEvent(new RetryEvent.Retrying("id-" + (i % IDS), 0, 1, Duration.ZERO, null));
            }
        });
        closer.join();
        done.set(true);
        collector.join();

        assertThat(failures).isEmpty();
        Collection<MetricData> after = reader.collectAllMetrics();
        assertThat(after).extracting(MetricData::getName).doesNotContain("r4c.circuitbreaker.state");
        long counted = retries(after).values().stream().mapToLong(Long::longValue).sum();
        assertThat(counted).as("events before close are counted, later ones dropped")
                .isBetween((long) THREADS * EVENTS / 2, (long) THREADS * EVENTS);
        long frozen = counted;
        listener.onEvent(new RetryEvent.Retrying("id-0", 0, 1, Duration.ZERO, null));
        assertThat(retries(reader.collectAllMetrics()).values().stream().mapToLong(Long::longValue).sum())
                .as("nothing is counted after close").isEqualTo(frozen);
    }

    private interface Body {
        void run(int thread) throws Exception;
    }

    private interface Action {
        void run() throws Exception;
    }

    private void runThreads(Body body) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            threads.add(Thread.ofPlatform().start(guarded(() -> {
                start.await();
                body.run(thread);
            })));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private Runnable guarded(Action action) {
        return () -> {
            try {
                action.run();
            } catch (Throwable e) {
                failures.add(e);
            }
        };
    }

    private static Map<String, Long> retries(Collection<MetricData> collected) {
        Map<String, Long> counts = new ConcurrentHashMap<>();
        collected.stream().filter(m -> m.getName().equals("r4c.retry.retries"))
                .flatMap(m -> m.getLongSumData().getPoints().stream())
                .forEach((LongPointData p) -> counts.merge(p.getAttributes().get(OpenTelemetryMetrics.NAME), p.getValue(),
                        Long::sum));
        return counts.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
