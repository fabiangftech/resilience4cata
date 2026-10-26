package dev.resilience4cata.micrometer;

import dev.resilience4cata.AdaptiveLimitHandle;
import dev.resilience4cata.AdaptiveLimitSnapshot;
import dev.resilience4cata.BulkheadHandle;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimiterHandle;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import dev.resilience4cata.event.ResilienceEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;

/**
 * Micrometer metrics for resilience4cata: counters fed by the registry's events, and gauges that read
 * the circuit breakers, bulkheads, rate limiters and adaptive limits when the meters are collected.
 *
 * <pre>{@code
 * MicrometerMetrics metrics = MicrometerMetrics.create(meterRegistry);
 * ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()).build(); // counters
 * metrics.bindTo(registry);                                                             // gauges
 * }</pre>
 *
 * <p><b>Counters</b> ({@code r4c.calls.rejected}, {@code r4c.circuitbreaker.transitions},
 * {@code r4c.retry.retries}, {@code r4c.retry.gave.up}, {@code r4c.hedge.hedged}, {@code r4c.hedge.won},
 * {@code r4c.hedge.skipped}, {@code r4c.hedge.failed}, {@code r4c.adaptivelimit.limit.changes},
 * {@code r4c.timeout.timed.out}, {@code r4c.fallback.applied}) come from an event listener that {@link #register} adds to the
 * builder. A counter is registered with Micrometer at its first event and cached, so later events
 * only look it up and increment it. Successful calls publish no events, so they cost nothing.
 *
 * <p><b>Gauges</b> ({@code r4c.circuitbreaker.state}, {@code r4c.circuitbreaker.failure.rate},
 * {@code r4c.circuitbreaker.slow.call.rate}, {@code r4c.bulkhead.concurrent.calls},
 * {@code r4c.bulkhead.waiting.calls}, {@code r4c.bulkhead.max.concurrent},
 * {@code r4c.ratelimiter.available.permits}, {@code r4c.adaptivelimit.limit},
 * {@code r4c.adaptivelimit.concurrent.calls}) are registered by {@link #bindTo} for every instance
 * that exists then and, through {@link ResilienceRegistry#onInstanceCreated}, for every instance the
 * registry creates later. They read the policy only when Micrometer collects them.
 *
 * <p><b>Lifecycle.</b> Gauges hold the policies weakly (Micrometer's default), so meters never keep a
 * {@link ResilienceRegistry} alive: once the registry is garbage collected its gauges report NaN.
 * The registry keeps this object (and so the {@link MeterRegistry}) reachable while it is bound.
 * {@link #close()} removes every meter this object registered and stops both the counters and the
 * discovery of new instances.
 *
 * <p>Use one {@code MicrometerMetrics} per {@link MeterRegistry} (a {@code CompositeMeterRegistry}
 * if you export to several backends). Meters are identified by name and tags only, so if two bound
 * registries have an instance with the same id, the gauges of the first one bound are reported. This
 * class is thread-safe.
 */
public final class MicrometerMetrics implements AutoCloseable {

    private static final CircuitBreakerState[] STATES = CircuitBreakerState.values();

    private final MeterRegistry meters;
    private final EventCounters counters;
    private final ReentrantLock lock = new ReentrantLock();
    /** Guarded by {@link #lock}: gauge keys already registered, {@code <kind> <instance id>}. */
    private final Set<String> bound = new HashSet<>();
    /** Guarded by {@link #lock}: every gauge registered, removed by {@link #close()}. */
    private final List<Meter> gauges = new ArrayList<>();
    /** Guarded by {@link #lock}: registries bound, held weakly. */
    private final Set<ResilienceRegistry> registries = Collections.newSetFromMap(new WeakHashMap<>());
    /** Guarded by {@link #lock}. */
    private final List<ResilienceRegistry.Registration> registrations = new ArrayList<>();
    private volatile boolean closed;

    private MicrometerMetrics(MeterRegistry meters) {
        this.meters = meters;
        this.counters = new EventCounters(meters);
    }

    /**
     * Creates the metrics for one meter registry. Nothing is registered with Micrometer until events
     * arrive or a registry is bound.
     *
     * @param meters where the meters go
     * @return the metrics
     */
    public static MicrometerMetrics create(MeterRegistry meters) {
        return new MicrometerMetrics(Objects.requireNonNull(meters, "meters"));
    }

    /**
     * Adds the event listener that feeds the counters. Call it before {@code build()}: listeners are
     * fixed when the registry is built. It can be called on several builders; their events go to the
     * same counters.
     *
     * @param builder the registry builder
     * @return {@code builder}, to keep configuring it
     */
    public ResilienceRegistry.Builder register(ResilienceRegistry.Builder builder) {
        return builder.listen(ResilienceEvent.class, counters);
    }

    /**
     * Registers the gauges of {@code registry}'s circuit breakers, bulkheads, rate limiters and adaptive
     * limits: those
     * that exist now, and those it creates later (when an interface is wrapped or a pipeline is built).
     * Binding the same registry again does nothing; several registries can be bound. Does nothing
     * after {@link #close()}.
     *
     * @param registry the registry to report
     */
    public void bindTo(ResilienceRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        lock.lock();
        try {
            if (closed || !registries.add(registry)) {
                return;
            }
            // Subscribe first, then list what exists: an instance created in between is seen by one or both.
            registrations.add(registry.onInstanceCreated(new Discovery(this, registry)));
        } finally {
            lock.unlock();
        }
        refresh(registry);
    }

    /**
     * Removes every meter this object registered from the meter registry, stops counting events and
     * stops registering gauges for new instances. Calling it again does nothing.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            counters.close();
            registrations.forEach(ResilienceRegistry.Registration::close);
            registrations.clear();
            gauges.forEach(meters::remove);
            gauges.clear();
            bound.clear();
            registries.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Registers the gauges of every instance of {@code registry} that has none yet. */
    void refresh(ResilienceRegistry registry) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            for (CircuitBreakerHandle breaker : registry.circuitBreakers()) {
                if (bound.add("circuitbreaker " + breaker.id())) {
                    bindCircuitBreaker(breaker);
                }
            }
            ResilienceSnapshot snapshot = registry.snapshot(); // the only list of bulkheads, rate limiters and adaptive limits
            for (BulkheadSnapshot bulkhead : snapshot.bulkheads()) {
                if (bound.add("bulkhead " + bulkhead.instance())) {
                    bindBulkhead(registry.bulkhead(bulkhead.instance()));
                }
            }
            for (RateLimiterSnapshot limiter : snapshot.rateLimiters()) {
                if (bound.add("ratelimiter " + limiter.instance())) {
                    bindRateLimiter(registry.rateLimiter(limiter.instance()));
                }
            }
            for (AdaptiveLimitSnapshot limit : snapshot.adaptiveLimits()) {
                if (bound.add("adaptivelimit " + limit.instance())) {
                    bindAdaptiveLimit(registry.adaptiveLimit(limit.instance()));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void bindCircuitBreaker(CircuitBreakerHandle breaker) {
        for (CircuitBreakerState state : STATES) {
            gauge("r4c.circuitbreaker.state", "1 for the circuit breaker's current state, 0 for the others",
                    breaker.id(), breaker, b -> b.state() == state ? 1 : 0, null, "state", lower(state));
        }
        gauge("r4c.circuitbreaker.failure.rate",
                "Failure rate of the circuit breaker's window, in percent; no value until it holds minimumCalls calls",
                breaker.id(), breaker, b -> percent(b.snapshot().failureRate()), "percent");
        gauge("r4c.circuitbreaker.slow.call.rate",
                "Slow call rate of the circuit breaker's window, in percent; no value until it holds minimumCalls"
                        + " calls, or without slow-call detection",
                breaker.id(), breaker, b -> percent(b.snapshot().slowCallRate()), "percent");
    }

    private void bindBulkhead(BulkheadHandle bulkhead) {
        gauge("r4c.bulkhead.concurrent.calls", "Calls running in the bulkhead",
                bulkhead.id(), bulkhead, BulkheadHandle::concurrentCalls, null);
        gauge("r4c.bulkhead.waiting.calls", "Calls waiting for a place in the bulkhead",
                bulkhead.id(), bulkhead, BulkheadHandle::waitingCalls, null);
        gauge("r4c.bulkhead.max.concurrent", "The bulkhead's maxConcurrent",
                bulkhead.id(), bulkhead, b -> b.config().maxConcurrent(), null);
    }

    private void bindRateLimiter(RateLimiterHandle limiter) {
        gauge("r4c.ratelimiter.available.permits", "Permits the rate limiter can grant now",
                limiter.id(), limiter, RateLimiterHandle::availablePermits, null);
    }

    private void bindAdaptiveLimit(AdaptiveLimitHandle limit) {
        gauge("r4c.adaptivelimit.limit", "The adaptive limit's current limit",
                limit.id(), limit, AdaptiveLimitHandle::limit, null);
        gauge("r4c.adaptivelimit.concurrent.calls", "Calls running in the adaptive limit",
                limit.id(), limit, AdaptiveLimitHandle::inFlight, null);
    }

    private <T> void gauge(String name, String description, String instance, T policy, ToDoubleFunction<T> value,
            @Nullable String unit, String... tags) {
        // Weak reference to the policy (Micrometer's default): meters never keep the registry alive.
        gauges.add(Gauge.builder(name, policy, value).description(description).baseUnit(unit)
                .tag("instance", instance).tags(tags).register(meters));
    }

    /** The snapshot reports -1 until the window holds minimumCalls calls: no value yet. */
    private static double percent(float rate) {
        return rate < 0 ? Double.NaN : rate;
    }

    static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The action given to {@link ResilienceRegistry#onInstanceCreated}. It holds the registry weakly
     * so that this object, if kept by the application, does not keep a discarded registry alive.
     */
    private static final class Discovery implements Runnable {

        private final MicrometerMetrics metrics;
        private final WeakReference<ResilienceRegistry> registry;

        Discovery(MicrometerMetrics metrics, ResilienceRegistry registry) {
            this.metrics = metrics;
            this.registry = new WeakReference<>(registry);
        }

        @Override
        public void run() {
            ResilienceRegistry target = registry.get();
            if (target != null) {
                metrics.refresh(target);
            }
        }
    }

    /** For tests: the gauge keys bound so far. */
    Set<String> boundForTest() {
        lock.lock();
        try {
            return Set.copyOf(bound);
        } finally {
            lock.unlock();
        }
    }

    /** For tests: the counters' cache. */
    Map<String, Object> counterCacheForTest() {
        return counters.cacheForTest();
    }
}
