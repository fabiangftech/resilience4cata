package dev.resilience4cata.opentelemetry;

import dev.resilience4cata.AdaptiveLimitSnapshot;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
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
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * OpenTelemetry metrics for a {@link ResilienceRegistry}: counters fed by the registry's events, and
 * asynchronous gauges read from {@link ResilienceRegistry#snapshot()} at each collection.
 *
 * <pre>{@code
 * OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(openTelemetry);
 * ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()).build();
 * metrics.bindTo(registry);
 * }</pre>
 *
 * <p>The metrics have the same names, attributes and values as the Micrometer module's, so dashboards
 * built on one work with the other. Counters: {@value #CALLS_REJECTED}, {@value #TRANSITIONS},
 * {@value #RETRIES}, {@value #GAVE_UP}, {@value #HEDGED}, {@value #HEDGE_WON}, {@value #HEDGE_SKIPPED},
 * {@value #HEDGE_FAILED}, {@value #LIMIT_CHANGES}, {@value #TIMED_OUT}, {@value #FALLBACK_APPLIED}. Gauges:
 * {@value #STATE}, {@value #FAILURE_RATE}, {@value #SLOW_CALL_RATE}, {@value #CONCURRENT_CALLS},
 * {@value #WAITING_CALLS}, {@value #MAX_CONCURRENT}, {@value #AVAILABLE_PERMITS}, {@value #LIMIT},
 * {@value #LIMIT_CONCURRENT_CALLS}. Every metric has an
 * {@code instance} attribute (the policy instance id); attribute values are lowercase.
 *
 * <p>Counters cost nothing on success paths: the listener runs only when a policy publishes an event,
 * and finds its {@link Attributes} in a cache, so it allocates nothing beyond the event once an
 * attribute set has been seen. Gauges read the registry only when a reader collects, so instances
 * created after {@link #bindTo(ResilienceRegistry)} appear at the next collection.
 *
 * <p>The instrumentation scope is {@value #SCOPE}, with this module's version.
 */
public final class OpenTelemetryMetrics implements AutoCloseable {

    /** The instrumentation scope name. */
    public static final String SCOPE = "dev.resilience4cata";

    /** Counter: calls rejected by a circuit breaker, rate limiter, adaptive limit or bulkhead; attribute {@code kind}. */
    public static final String CALLS_REJECTED = "r4c.calls.rejected";
    /** Counter: circuit breaker state changes; attributes {@code from} and {@code to}. */
    public static final String TRANSITIONS = "r4c.circuitbreaker.transitions";
    /** Counter: retries scheduled after a failed attempt. */
    public static final String RETRIES = "r4c.retry.retries";
    /** Counter: retries that stopped without a success; attribute {@code reason}. */
    public static final String GAVE_UP = "r4c.retry.gave.up";
    /** Counter: hedged attempts started (attempt 2 or later); attribute {@code reason} ({@code delay}, {@code failure}). */
    public static final String HEDGED = "r4c.hedge.hedged";
    /** Counter: calls won by a hedged attempt (attempt 2 or later) rather than the first one. */
    public static final String HEDGE_WON = "r4c.hedge.won";
    /**
     * Counter: hedged attempts not started; attribute {@code reason} ({@code budget_exhausted}, {@code rejected},
     * {@code aborted}).
     */
    public static final String HEDGE_SKIPPED = "r4c.hedge.skipped";
    /** Counter: hedged calls that failed. */
    public static final String HEDGE_FAILED = "r4c.hedge.failed";
    /** Counter: windows after which an adaptive limit moved its limit. */
    public static final String LIMIT_CHANGES = "r4c.adaptivelimit.limit.changes";
    /** Counter: calls that timed out. */
    public static final String TIMED_OUT = "r4c.timeout.timed.out";
    /** Counter: fallbacks applied. */
    public static final String FALLBACK_APPLIED = "r4c.fallback.applied";
    /** Gauge: 1 for the breaker's current state, 0 for the others; attribute {@code state}. */
    public static final String STATE = "r4c.circuitbreaker.state";
    /** Gauge, percent: failure rate in the breaker's window, not reported until it holds {@code minimumCalls}. */
    public static final String FAILURE_RATE = "r4c.circuitbreaker.failure.rate";
    /** Gauge, percent: slow-call rate, not reported until the window holds {@code minimumCalls} or without slow-call detection. */
    public static final String SLOW_CALL_RATE = "r4c.circuitbreaker.slow.call.rate";
    /** Gauge: calls inside the bulkhead. */
    public static final String CONCURRENT_CALLS = "r4c.bulkhead.concurrent.calls";
    /** Gauge: calls waiting for a bulkhead slot. */
    public static final String WAITING_CALLS = "r4c.bulkhead.waiting.calls";
    /** Gauge: the bulkhead's {@code maxConcurrent}. */
    public static final String MAX_CONCURRENT = "r4c.bulkhead.max.concurrent";
    /** Gauge: permits the rate limiter would grant now. */
    public static final String AVAILABLE_PERMITS = "r4c.ratelimiter.available.permits";
    /** Gauge: the adaptive limit's current limit. */
    public static final String LIMIT = "r4c.adaptivelimit.limit";
    /** Gauge: calls inside the adaptive limit. */
    public static final String LIMIT_CONCURRENT_CALLS = "r4c.adaptivelimit.concurrent.calls";

    static final AttributeKey<String> INSTANCE = AttributeKey.stringKey("instance");
    static final AttributeKey<String> KIND = AttributeKey.stringKey("kind");
    static final AttributeKey<String> FROM = AttributeKey.stringKey("from");
    static final AttributeKey<String> TO = AttributeKey.stringKey("to");
    static final AttributeKey<String> REASON = AttributeKey.stringKey("reason");
    static final AttributeKey<String> STATE_KEY = AttributeKey.stringKey("state");

    private static final String CALL_UNIT = "{call}";
    private static final System.Logger LOGGER = System.getLogger(OpenTelemetryMetrics.class.getName());
    private static final CircuitBreakerState[] STATES = CircuitBreakerState.values();
    private static final RetryEvent.Reason[] REASONS = RetryEvent.Reason.values();
    private static final HedgeEvent.Reason[] HEDGE_REASONS = HedgeEvent.Reason.values();
    private static final HedgeEvent.SkipReason[] SKIP_REASONS = HedgeEvent.SkipReason.values();
    private static final String ATTEMPT_UNIT = "{attempt}";
    private static final @Nullable String VERSION = readVersion();

    private final Meter meter;
    private final LongCounter rejected;
    private final LongCounter transitionCounter;
    private final LongCounter retryCounter;
    private final LongCounter gaveUp;
    private final LongCounter hedged;
    private final LongCounter hedgeWon;
    private final LongCounter hedgeSkipped;
    private final LongCounter hedgeFailed;
    private final LongCounter limitChanges;
    private final LongCounter timedOut;
    private final LongCounter fallbacks;

    // Attributes are immutable: each set is built once per instance id and reused for every event and collection.
    private final ConcurrentHashMap<String, Attributes> instanceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attributes> breakerRejections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attributes> limiterRejections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attributes> bulkheadRejections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attributes> adaptiveLimitRejections = new ConcurrentHashMap<>();
    /** Per instance, indexed by {@code from.ordinal() * STATES.length + to.ordinal()}. */
    private final ConcurrentHashMap<String, Attributes[]> transitionAttributes = new ConcurrentHashMap<>();
    /** Per instance, indexed by reason ordinal. */
    private final ConcurrentHashMap<String, Attributes[]> reasonAttributes = new ConcurrentHashMap<>();
    /** Per instance, indexed by hedge reason ordinal. */
    private final ConcurrentHashMap<String, Attributes[]> hedgeReasonAttributes = new ConcurrentHashMap<>();
    /** Per instance, indexed by skip reason ordinal. */
    private final ConcurrentHashMap<String, Attributes[]> skipReasonAttributes = new ConcurrentHashMap<>();
    /** Per instance, indexed by state ordinal. */
    private final ConcurrentHashMap<String, Attributes[]> stateAttributes = new ConcurrentHashMap<>();

    private final List<BatchCallback> callbacks = new ArrayList<>();
    private volatile boolean closed;

    private OpenTelemetryMetrics(Meter meter) {
        this.meter = meter;
        rejected = counter(CALLS_REJECTED, CALL_UNIT,
                "Calls rejected by a circuit breaker, rate limiter, adaptive limit or bulkhead");
        transitionCounter = counter(TRANSITIONS, "{transition}", "Circuit breaker state changes");
        retryCounter = counter(RETRIES, "{retry}", "Attempts that failed and will be retried");
        gaveUp = counter(GAVE_UP, CALL_UNIT, "Retries that stopped without a success, by reason");
        hedged = counter(HEDGED, ATTEMPT_UNIT, "Hedged attempts started, by reason");
        hedgeWon = counter(HEDGE_WON, CALL_UNIT, "Calls won by a hedged attempt rather than the first one");
        hedgeSkipped = counter(HEDGE_SKIPPED, ATTEMPT_UNIT, "Hedged attempts not started, by reason");
        hedgeFailed = counter(HEDGE_FAILED, CALL_UNIT, "Hedged calls that failed");
        limitChanges = counter(LIMIT_CHANGES, "{window}", "Windows after which an adaptive limit moved its limit");
        timedOut = counter(TIMED_OUT, CALL_UNIT, "Calls that timed out");
        fallbacks = counter(FALLBACK_APPLIED, CALL_UNIT, "Failures answered by a fallback");
    }

    /**
     * Creates the metrics on the meter provider of an {@link OpenTelemetry} instance.
     *
     * @param openTelemetry the OpenTelemetry instance (for example {@code GlobalOpenTelemetry.get()}, or your SDK)
     * @return the metrics, not yet registered or bound
     */
    public static OpenTelemetryMetrics create(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        return create(openTelemetry.getMeterProvider());
    }

    /**
     * Creates the metrics on a meter provider, with the instrumentation scope {@value #SCOPE}.
     *
     * @param meterProvider the meter provider
     * @return the metrics, not yet registered or bound
     */
    public static OpenTelemetryMetrics create(MeterProvider meterProvider) {
        Objects.requireNonNull(meterProvider, "meterProvider");
        MeterBuilder builder = meterProvider.meterBuilder(SCOPE);
        if (VERSION != null) {
            builder.setInstrumentationVersion(VERSION);
        }
        return new OpenTelemetryMetrics(builder.build());
    }

    /**
     * Adds the listener that feeds the counters. Call it before {@link ResilienceRegistry.Builder#build()}:
     * listeners are fixed when the registry is built.
     *
     * @param builder the registry builder
     * @return the same builder
     */
    public ResilienceRegistry.Builder register(ResilienceRegistry.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        return builder.listen(ResilienceEvent.class, new Listener());
    }

    /**
     * Registers the gauges for a registry. They are read at each collection from
     * {@link ResilienceRegistry#snapshot()}, so policy instances created later are reported too: call it
     * once, whenever convenient. Binding several registries whose instance ids overlap reports the same
     * attribute sets twice; use one registry per {@code OpenTelemetryMetrics}.
     *
     * @param registry the registry
     * @throws IllegalStateException if these metrics are closed
     */
    public void bindTo(ResilienceRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        ObservableLongMeasurement state = meter.gaugeBuilder(STATE).ofLongs()
                .setDescription("1 for the circuit breaker's current state, 0 for the others").buildObserver();
        ObservableDoubleMeasurement failureRate = meter.gaugeBuilder(FAILURE_RATE).setUnit("%")
                .setDescription("Failure rate of the circuit breaker's window, in percent; no value until it holds minimumCalls calls")
                .buildObserver();
        ObservableDoubleMeasurement slowCallRate = meter.gaugeBuilder(SLOW_CALL_RATE).setUnit("%")
                .setDescription("Slow call rate of the circuit breaker's window, in percent; no value until it holds minimumCalls"
                        + " calls, or without slow-call detection")
                .buildObserver();
        ObservableLongMeasurement concurrent = longGauge(CONCURRENT_CALLS, CALL_UNIT, "Calls running in the bulkhead");
        ObservableLongMeasurement waiting = longGauge(WAITING_CALLS, CALL_UNIT, "Calls waiting for a place in the bulkhead");
        ObservableLongMeasurement maxConcurrent = longGauge(MAX_CONCURRENT, CALL_UNIT, "The bulkhead's maxConcurrent");
        ObservableLongMeasurement permits = longGauge(AVAILABLE_PERMITS, "{permit}", "Permits the rate limiter can grant now");
        ObservableLongMeasurement limit = longGauge(LIMIT, CALL_UNIT, "The adaptive limit's current limit");
        ObservableLongMeasurement limitConcurrent = longGauge(LIMIT_CONCURRENT_CALLS, CALL_UNIT,
                "Calls running in the adaptive limit");
        Gauges gauges = new Gauges(state, failureRate, slowCallRate, concurrent, waiting, maxConcurrent, permits, limit,
                limitConcurrent);
        Runnable observe = () -> gauges.observe(registry.snapshot());
        synchronized (callbacks) { // cold: once per bind, never on a call path
            if (closed) {
                throw new IllegalStateException("These OpenTelemetryMetrics are closed: create new ones to bind a registry.");
            }
            callbacks.add(meter.batchCallback(observe, state, failureRate, slowCallRate, concurrent, waiting,
                    maxConcurrent, permits, limit, limitConcurrent));
        }
    }

    /**
     * Removes the gauge callbacks of every bound registry and stops the counters: the listener stays on
     * the registry (listeners cannot be removed) but records nothing more. Counters already exported keep
     * their last values in the SDK until it drops them. Idempotent.
     */
    @Override
    public void close() {
        synchronized (callbacks) {
            closed = true;
            callbacks.forEach(BatchCallback::close);
            callbacks.clear();
        }
    }

    /** The gauges of one bound registry, recorded from its snapshot at each collection. */
    private final class Gauges {
        private final ObservableLongMeasurement state;
        private final ObservableDoubleMeasurement failureRate;
        private final ObservableDoubleMeasurement slowCallRate;
        private final ObservableLongMeasurement concurrent;
        private final ObservableLongMeasurement waiting;
        private final ObservableLongMeasurement maxConcurrent;
        private final ObservableLongMeasurement permits;
        private final ObservableLongMeasurement limit;
        private final ObservableLongMeasurement limitConcurrent;

        // java:S107: one parameter per gauge, called once per bind
        @SuppressWarnings("java:S107")
        Gauges(ObservableLongMeasurement state, ObservableDoubleMeasurement failureRate,
                ObservableDoubleMeasurement slowCallRate, ObservableLongMeasurement concurrent,
                ObservableLongMeasurement waiting, ObservableLongMeasurement maxConcurrent,
                ObservableLongMeasurement permits, ObservableLongMeasurement limit,
                ObservableLongMeasurement limitConcurrent) {
            this.state = state;
            this.failureRate = failureRate;
            this.slowCallRate = slowCallRate;
            this.concurrent = concurrent;
            this.waiting = waiting;
            this.maxConcurrent = maxConcurrent;
            this.permits = permits;
            this.limit = limit;
            this.limitConcurrent = limitConcurrent;
        }

        void observe(ResilienceSnapshot snapshot) {
            snapshot.circuitBreakers().forEach(this::observe);
            for (BulkheadSnapshot bulkhead : snapshot.bulkheads()) {
                Attributes instance = attributes(bulkhead.id());
                concurrent.record(bulkhead.concurrentCalls(), instance);
                waiting.record(bulkhead.waitingCalls(), instance);
                maxConcurrent.record(bulkhead.maxConcurrent(), instance);
            }
            for (RateLimiterSnapshot limiter : snapshot.rateLimiters()) {
                permits.record(limiter.availablePermits(), attributes(limiter.id()));
            }
            for (AdaptiveLimitSnapshot adaptive : snapshot.adaptiveLimits()) {
                Attributes instance = attributes(adaptive.id());
                limit.record(adaptive.limit(), instance);
                limitConcurrent.record(adaptive.concurrentCalls(), instance);
            }
        }

        private void observe(CircuitBreakerSnapshot breaker) {
            Attributes[] states = states(breaker.id());
            for (CircuitBreakerState s : STATES) {
                state.record(breaker.state() == s ? 1 : 0, states[s.ordinal()]);
            }
            Attributes instance = attributes(breaker.id());
            if (breaker.failureRate() >= 0) { // -1 until the window holds minimumCalls: no point (NaN is not valid)
                failureRate.record(breaker.failureRate(), instance);
            }
            if (breaker.slowCallRate() >= 0) {
                slowCallRate.record(breaker.slowCallRate(), instance);
            }
        }

        private Attributes[] states(String id) {
            return cached(stateAttributes, id, i -> {
                Attributes[] all = new Attributes[STATES.length];
                for (CircuitBreakerState breakerState : STATES) {
                    all[breakerState.ordinal()] = Attributes.of(INSTANCE, i, STATE_KEY, lower(breakerState));
                }
                return all;
            });
        }
    }

    private LongCounter counter(String name, String unit, String description) {
        return meter.counterBuilder(name).setUnit(unit).setDescription(description).build();
    }

    private ObservableLongMeasurement longGauge(String name, String unit, String description) {
        return meter.gaugeBuilder(name).ofLongs().setUnit(unit).setDescription(description).buildObserver();
    }

    /** Feeds the counters. Runs on the thread that caused the event; never throws. */
    private final class Listener implements EventListener<ResilienceEvent> {
        @Override
        public void onEvent(ResilienceEvent event) {
            if (closed) {
                return;
            }
            try {
                recordEvent(event);
            } catch (RuntimeException e) { // a metrics problem must never fail or slow down the call
                LOGGER.log(System.Logger.Level.DEBUG, "Could not record " + event, e);
            }
        }

        private void recordEvent(ResilienceEvent event) {
            String id = event.id();
            switch (event) {
                case CircuitBreakerEvent.StateTransition t ->
                        transitionCounter.add(1, transitions(id)[t.from().ordinal() * STATES.length + t.to().ordinal()]);
                case CircuitBreakerEvent.CallNotPermitted r -> rejected.add(1, breakerRejection(id));
                case RateLimiterEvent.Rejected r -> rejected.add(1, limiterRejection(id));
                case BulkheadEvent.Rejected r -> rejected.add(1, bulkheadRejection(id));
                case AdaptiveLimitEvent.Rejected r -> rejected.add(1, adaptiveLimitRejection(id));
                case AdaptiveLimitEvent.LimitChanged c -> limitChanges.add(1, attributes(id));
                case RetryEvent.Retrying r -> retryCounter.add(1, attributes(id));
                case RetryEvent.GaveUp g -> gaveUp.add(1, reasons(id)[g.reason().ordinal()]);
                case TimeoutEvent.TimedOut t -> timedOut.add(1, attributes(id));
                case FallbackEvent.Applied f -> fallbacks.add(1, attributes(id));
                case HedgeEvent.Hedged h -> hedged.add(1, hedgeReasons(id)[h.reason().ordinal()]);
                case HedgeEvent.Won w -> hedgeWon.add(1, attributes(id));
                case HedgeEvent.Skipped k -> hedgeSkipped.add(1, skipReasons(id)[k.reason().ordinal()]);
                case HedgeEvent.Failed f -> hedgeFailed.add(1, attributes(id));
            }
        }

        private Attributes breakerRejection(String id) {
            return cached(breakerRejections, id, i -> Attributes.of(INSTANCE, i, KIND, "circuitbreaker"));
        }

        private Attributes limiterRejection(String id) {
            return cached(limiterRejections, id, i -> Attributes.of(INSTANCE, i, KIND, "ratelimiter"));
        }

        private Attributes bulkheadRejection(String id) {
            return cached(bulkheadRejections, id, i -> Attributes.of(INSTANCE, i, KIND, "bulkhead"));
        }

        private Attributes adaptiveLimitRejection(String id) {
            return cached(adaptiveLimitRejections, id, i -> Attributes.of(INSTANCE, i, KIND, "adaptivelimit"));
        }

        private Attributes[] transitions(String id) {
            return cached(transitionAttributes, id, i -> {
                Attributes[] all = new Attributes[STATES.length * STATES.length];
                for (CircuitBreakerState from : STATES) {
                    for (CircuitBreakerState to : STATES) {
                        all[from.ordinal() * STATES.length + to.ordinal()] =
                                Attributes.of(INSTANCE, i, FROM, lower(from), TO, lower(to));
                    }
                }
                return all;
            });
        }

        private Attributes[] hedgeReasons(String id) {
            return cached(hedgeReasonAttributes, id, i -> {
                Attributes[] all = new Attributes[HEDGE_REASONS.length];
                for (HedgeEvent.Reason reason : HEDGE_REASONS) {
                    all[reason.ordinal()] = Attributes.of(INSTANCE, i, REASON, lower(reason));
                }
                return all;
            });
        }

        private Attributes[] skipReasons(String id) {
            return cached(skipReasonAttributes, id, i -> {
                Attributes[] all = new Attributes[SKIP_REASONS.length];
                for (HedgeEvent.SkipReason reason : SKIP_REASONS) {
                    all[reason.ordinal()] = Attributes.of(INSTANCE, i, REASON, lower(reason));
                }
                return all;
            });
        }

        private Attributes[] reasons(String id) {
            return cached(reasonAttributes, id, i -> {
                Attributes[] all = new Attributes[REASONS.length];
                for (RetryEvent.Reason reason : REASONS) {
                    all[reason.ordinal()] = Attributes.of(INSTANCE, i, REASON, lower(reason));
                }
                return all;
            });
        }
    }

    @SuppressWarnings("java:S3398") // shared by Gauges, the listener and the tests, so it stays here
    Attributes attributes(String id) {
        return cached(instanceCache, id, i -> Attributes.of(INSTANCE, i));
    }


    /** A lock-free read when present; {@code computeIfAbsent} only the first time an id is seen. */
    private static <V> V cached(ConcurrentHashMap<String, V> cache, String id, Function<String, V> create) {
        V value = cache.get(id);
        return value != null ? value : cache.computeIfAbsent(id, create);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** The module's version, written into {@code version.properties} by the build; {@code null} if absent. */
    private static @Nullable String readVersion() {
        try (InputStream in = OpenTelemetryMetrics.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            return version == null || version.isBlank() || version.startsWith("$") ? null : version;
        } catch (IOException | IllegalArgumentException e) {
            return null; // the scope then has no version: not worth failing for
        }
    }

    /** The instrumentation scope version, for tests. */
    static @Nullable String version() {
        return VERSION;
    }
}
