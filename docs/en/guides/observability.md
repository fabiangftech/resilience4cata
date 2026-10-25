# Observability

See what the policies do through events, read their state through snapshots and handles, and build metrics, logs and health checks on top of both.

resilience4cata 0.1.0 gives you two building blocks:

1. **Events**: a record for each thing a policy does other than letting a call through (a retry, a breaker transition, a rejection, a timeout, a fallback), delivered to listeners you register on the registry builder.
2. **Snapshots and handles**: the current state of each instance, read when you ask for it.

> [!NOTE]
> There is no Micrometer, OpenTelemetry or logging module in 0.1.0. The [Micrometer recipe](#micrometer) below is code you put in your application; it is tested in this repository against Micrometer 1.17.1, but it is not a library artifact.

## Events :id=events

Every event implements the sealed interface `dev.resilience4cata.event.ResilienceEvent`, with `instance()` (the instance id: the `name`, the derived `pkg.Type#method(java.lang.String,int)` id, or a pipeline id) and `nanoTime()` (a reading of the registry's clock: `System.nanoTime()`, or the [manual clock](testing.md) in tests). Each policy has its own sealed interface of records:

| Policy | Event record | Fields (after `instance`, `nanoTime`) | Published when |
|---|---|---|---|
| Circuit breaker | `CircuitBreakerEvent.StateTransition` | `CircuitBreakerState from`, `CircuitBreakerState to` | the state changes, exactly once per change, including `forceOpen()`, `disable()` and `reset()` |
| Circuit breaker | `CircuitBreakerEvent.CallNotPermitted` | `CircuitBreakerState state` | a call is rejected (`OPEN`, `FORCED_OPEN`, or `HALF_OPEN` with every probe taken) |
| Retry | `RetryEvent.Retrying` | `int attempt`, `Duration delay`, `@Nullable Throwable failure` | an attempt failed and another one will run after `delay`; `failure` is `null` when a [classifier](classifiers.md) rejected a result |
| Retry | `RetryEvent.GaveUp` | `int attempts`, `RetryEvent.Reason reason`, `@Nullable Throwable failure` | the retry stops without a success: `MAX_ATTEMPTS`, `MAX_DURATION`, `NOT_RETRYABLE` (the failure is not in `retryOn`, is in `abortOn`, or is an open breaker's `CallNotPermittedException`), `INTERRUPTED`, `CLASSIFIER_FAILED` |
| Rate limiter | `RateLimiterEvent.Rejected` | none | a call is rejected with `RateLimitExceededException` |
| Timeout | `TimeoutEvent.TimedOut` | `Duration timeout` | a call times out |
| Bulkhead | `BulkheadEvent.Rejected` | `int maxConcurrent` | a call is rejected with `BulkheadFullException` |
| Fallback | `FallbackEvent.Applied` | `Throwable failure` | a fallback is about to run for `failure`; not published when the failure is rethrown because no fallback takes it (`skipOn`, or no sibling method accepts its type) |

A retry with `enabled = false` publishes nothing. A first failure that is not retryable publishes `GaveUp` with `attempts = 1` and `NOT_RETRYABLE`.

### Listening :id=listening

Listeners are registered on `ResilienceRegistry.Builder`, and only there: they are fixed when the registry is built. Listen to one record type, a whole policy interface, or `ResilienceEvent`; on every instance, or on one instance id:

<!-- snippet: observability-listen -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        // one record type, on every instance
        .listen(CircuitBreakerEvent.StateTransition.class,
                t -> log.add(t.instance() + ": " + t.from() + " -> " + t.to()))
        // every retry event (Retrying and GaveUp), on one instance only
        .listen("payments", RetryEvent.class, e -> log.add(e.toString()))
        // everything: the hierarchy is sealed, so a switch can be exhaustive
        .listen(ResilienceEvent.class, e -> {
            switch (e) {
                case RetryEvent.GaveUp g -> log.add("gave up after " + g.attempts() + ": " + g.reason());
                default -> { }
            }
        })
        .build();
```

- **Listeners run synchronously** on the thread that caused the event: the caller's thread, or a library virtual thread for async timeouts and delayed async retries. Keep them fast; hand slow work (a network call) to your own executor.
- **An exception thrown by a listener** does not reach the call or the other listeners: it goes to the thread's uncaught exception handler.
- **Order:** events published from different threads can arrive out of order; each breaker transition arrives exactly once.
- `EventListener<E>` is a functional interface (`void onEvent(E event)`), so a lambda or a method reference works.

### What events cost :id=cost

Each policy resolves its listeners once, when the instance is created, into one field per event type. An event type nobody listens to costs **one null check, and only on non-success paths**. A successful call publishes nothing and checks nothing. When someone listens, the event record is allocated and the clock is read only at that point.

There are **no per-call counters** (no "successful calls" or latency totals) by design: a counter updated on every call would be a shared write on the hot path. If you need call counts or latency, measure them in your own code around the call, or with your HTTP client's or framework's metrics.

## Snapshots :id=snapshots

`registry.snapshot()` returns a `ResilienceSnapshot` with one list per policy, each sorted by instance id. It is built when you call it, never on the call path, and may allocate and walk the breaker windows: call it at scrape or health-check time, not per request.

<!-- snippet: observability-snapshot -->
```java
ResilienceSnapshot snapshot = registry.snapshot(); // built when asked, never on the call path
for (CircuitBreakerSnapshot b : snapshot.circuitBreakers()) {
    lines.add(b.instance() + " " + b.state() + " failureRate=" + b.failureRate()
            + " buffered=" + b.bufferedCalls() + " failed=" + b.failedCalls() + " slow=" + b.slowCalls());
}
for (BulkheadSnapshot b : snapshot.bulkheads()) {
    lines.add(b.instance() + " in use " + b.concurrentCalls() + "/" + b.maxConcurrent()
            + ", waiting " + b.waitingCalls() + ", free " + b.availableSlots());
}
for (RateLimiterSnapshot r : snapshot.rateLimiters()) {
    lines.add(r.instance() + " permits " + r.availablePermits() + "/" + r.permits());
}
```

| Record | Fields |
|---|---|
| `CircuitBreakerSnapshot` | `instance`, `state`, `failureRate` and `slowCallRate` (percent, or `-1` until the window holds `minimumCalls` calls, `halfOpenCalls` while half-open; `slowCallRate` is also `-1` when slow-call detection is off), `bufferedCalls`, `failedCalls`, `slowCalls` (calls in the current window: the sliding window while closed, the probes while half-open, none while open, forced open or disabled) |
| `BulkheadSnapshot` | `instance`, `concurrentCalls`, `waitingCalls`, `maxConcurrent`, and the method `availableSlots()` |
| `RateLimiterSnapshot` | `instance`, `availablePermits`, `permits` |
| `RetrySnapshot` | `instance`, `config` (the resolved `RetryConfig`) |
| `TimeoutSnapshot` | `instance`, `config` (the resolved `TimeoutConfig`) |

## Handles :id=handles

Handles read one instance live, and the breaker's can also change its state:

| Method | Returns |
|---|---|
| `registry.circuitBreaker(id)` | `CircuitBreakerHandle`: `id()`, `state()`, `config()`, `snapshot()`, `forceOpen()`, `disable()`, `reset()` |
| `registry.circuitBreakers()` | every breaker handle, sorted by id |
| `registry.rateLimiter(id)` | `RateLimiterHandle`: `id()`, `config()`, `availablePermits()`, `snapshot()` |
| `registry.bulkhead(id)` | `BulkheadHandle`: `id()`, `config()`, `concurrentCalls()`, `waitingCalls()`, `snapshot()` |

An instance exists only once its interface has been wrapped (`Resilient.of`, `Resilient.create`) or its `Pipeline` built; before that, or for an id that does not exist, the lookup throws `IllegalArgumentException` [R4C-REG-009](../errors.md#r4c-reg-009) with the closest id. Retry and timeout have no handle; their configuration is in the snapshot. There is no list method for bulkheads and rate limiters: enumerate them through `registry.snapshot()`.

## Micrometer recipe :id=micrometer

Counters from events, gauges from handles. Copy the two classes into your application; they need `io.micrometer:micrometer-core` (the recipe is tested with 1.17.1).

### Metric names :id=metric-names

Names are lowercase and dotted, as Micrometer recommends; every meter has an `instance` tag (instance ids are fixed in your code, so the tag is bounded) and at most one or two more tags with a fixed set of values.

| Meter | Type | Tags | Source |
|---|---|---|---|
| `r4c.calls.rejected` | counter | `instance`, `kind` = `circuitbreaker` \| `ratelimiter` \| `bulkhead` | `CallNotPermitted`, `RateLimiterEvent.Rejected`, `BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | counter | `instance`, `from`, `to` (lowercase states) | `StateTransition` |
| `r4c.retry.retries` | counter | `instance` | `Retrying` |
| `r4c.retry.gave.up` | counter | `instance`, `reason` (lowercase `Reason`) | `GaveUp` |
| `r4c.timeout.timed.out` | counter | `instance` | `TimedOut` |
| `r4c.fallback.applied` | counter | `instance` | `Applied` |
| `r4c.circuitbreaker.state` | gauge | `instance`, `state`: 1 for the current state, 0 for the others | `state()` |
| `r4c.circuitbreaker.failure.rate`, `r4c.circuitbreaker.slow.call.rate` | gauge (percent) | `instance` | `snapshot()`; NaN until the window holds `minimumCalls` calls |
| `r4c.bulkhead.concurrent.calls`, `r4c.bulkhead.waiting.calls`, `r4c.bulkhead.max.concurrent` | gauge | `instance` | `BulkheadHandle` |
| `r4c.ratelimiter.available.permits` | gauge | `instance` | `RateLimiterHandle` |

Don't add the exception class or message as a tag: they are unbounded in practice.

### Counters from events :id=counters

<!-- snippet: observability-counters -->
```java
import dev.resilience4cata.event.BulkheadEvent;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.RateLimiterEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.event.TimeoutEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Turns resilience4cata events into Micrometer counters. Your code, not a library module: copy it.
 * Register it with ResilienceRegistry.Builder.listen(ResilienceEvent.class, ...) before build().
 */
public final class ResilienceEventCounters implements EventListener<ResilienceEvent> {

    private final MeterRegistry meters;

    public ResilienceEventCounters(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void onEvent(ResilienceEvent event) {
        switch (event) {
            case CircuitBreakerEvent.StateTransition t ->
                    count("r4c.circuitbreaker.transitions", t, "from", lower(t.from()), "to", lower(t.to()));
            case CircuitBreakerEvent.CallNotPermitted r -> count("r4c.calls.rejected", r, "kind", "circuitbreaker");
            case RateLimiterEvent.Rejected r -> count("r4c.calls.rejected", r, "kind", "ratelimiter");
            case BulkheadEvent.Rejected r -> count("r4c.calls.rejected", r, "kind", "bulkhead");
            case RetryEvent.Retrying r -> count("r4c.retry.retries", r);
            case RetryEvent.GaveUp g -> count("r4c.retry.gave.up", g, "reason", lower(g.reason()));
            case TimeoutEvent.TimedOut t -> count("r4c.timeout.timed.out", t);
            case FallbackEvent.Applied f -> count("r4c.fallback.applied", f);
        }
    }

    private void count(String name, ResilienceEvent event, String... tags) {
        Counter.builder(name).tag("instance", event.instance()).tags(tags).register(meters).increment();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
```

`Counter.builder(…).register(meters)` looks the counter up on each event (Micrometer returns the existing one). That costs an allocation and a map lookup per event, on failure paths only; if your events are very frequent, cache the counters by instance and tag values.

### Gauges from handles :id=gauges

<!-- snippet: observability-gauges -->
```java
import dev.resilience4cata.BulkheadHandle;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimiterHandle;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Micrometer gauges read from the registry's handles when the meters are scraped. Your code, not a
 * library module: copy it. Call bind(...) after wrapping your services: an instance exists only then.
 */
public final class ResilienceGauges {

    private ResilienceGauges() {
    }

    public static void bind(ResilienceRegistry registry, MeterRegistry meters) {
        for (CircuitBreakerHandle breaker : registry.circuitBreakers()) {
            for (CircuitBreakerState state : CircuitBreakerState.values()) { // 1 for the current state, else 0
                Gauge.builder("r4c.circuitbreaker.state", breaker, b -> b.state() == state ? 1 : 0)
                        .tag("instance", breaker.id()).tag("state", state.name().toLowerCase(Locale.ROOT))
                        .strongReference(true).register(meters);
            }
            Gauge.builder("r4c.circuitbreaker.failure.rate", breaker, b -> percent(b.snapshot().failureRate()))
                    .tag("instance", breaker.id()).baseUnit("percent").strongReference(true).register(meters);
            Gauge.builder("r4c.circuitbreaker.slow.call.rate", breaker, b -> percent(b.snapshot().slowCallRate()))
                    .tag("instance", breaker.id()).baseUnit("percent").strongReference(true).register(meters);
        }
        ResilienceSnapshot snapshot = registry.snapshot(); // lists every bulkhead and rate limiter
        for (BulkheadSnapshot b : snapshot.bulkheads()) {
            BulkheadHandle bulkhead = registry.bulkhead(b.instance());
            Gauge.builder("r4c.bulkhead.concurrent.calls", bulkhead, BulkheadHandle::concurrentCalls)
                    .tag("instance", b.instance()).strongReference(true).register(meters);
            Gauge.builder("r4c.bulkhead.waiting.calls", bulkhead, BulkheadHandle::waitingCalls)
                    .tag("instance", b.instance()).strongReference(true).register(meters);
            Gauge.builder("r4c.bulkhead.max.concurrent", bulkhead, h -> h.config().maxConcurrent())
                    .tag("instance", b.instance()).strongReference(true).register(meters);
        }
        for (RateLimiterSnapshot r : snapshot.rateLimiters()) {
            RateLimiterHandle limiter = registry.rateLimiter(r.instance());
            Gauge.builder("r4c.ratelimiter.available.permits", limiter, RateLimiterHandle::availablePermits)
                    .tag("instance", r.instance()).strongReference(true).register(meters);
        }
    }

    /** The snapshot reports -1 until the window holds minimumCalls calls: no value yet. */
    private static double percent(float rate) {
        return rate < 0 ? Double.NaN : rate;
    }
}
```

Gauges are read when the registry is scraped. `strongReference(true)` keeps the handle alive even if nothing else holds it (Micrometer keeps gauge objects weakly by default).

### Wiring :id=wiring

The listener must be registered before `build()`, and the gauges bound after the services are wrapped, because instances exist only then. Instances created later (another `Resilient.of` call) need another `ResilienceGauges.bind(…)` call, or bind only once at the end of your wiring.

<!-- snippet: observability-micrometer-wiring -->
```java
MeterRegistry meters = new SimpleMeterRegistry(); // in your app: the registry you already export
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(ResilienceEvent.class, new ResilienceEventCounters(meters))
        .build();
PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
ResilienceGauges.bind(registry, meters); // after wrapping: the instances exist now
```

### What Prometheus shows :id=prometheus

With a `PrometheusMeterRegistry`, Micrometer's naming convention turns dots into underscores and adds `_total` to counters. After a breaker opened, a scrape contains lines like these (abridged, values are an example):

```text
r4c_retry_retries_total{instance="payments"} 3.0
r4c_retry_gave_up_total{instance="payments",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{instance="payments",reason="not_retryable"} 1.0
r4c_calls_rejected_total{instance="payments",kind="circuitbreaker"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",instance="payments",to="open"} 1.0
r4c_circuitbreaker_state{instance="payments",state="closed"} 0.0
r4c_circuitbreaker_state{instance="payments",state="open"} 1.0
r4c_circuitbreaker_failure_rate_percent{instance="payments"} 100.0
r4c_bulkhead_concurrent_calls{instance="payments"} 0.0
r4c_bulkhead_max_concurrent{instance="payments"} 10.0
r4c_ratelimiter_available_permits{instance="payments"} 96.0
```

### Grafana panels :id=grafana

PromQL for the panels most services need:

| Panel | Query | Notes |
|---|---|---|
| Rejections per second, by policy | `sum by (instance, kind) (rate(r4c_calls_rejected_total[5m]))` | stacked time series |
| Breakers open now | `sum by (instance) (r4c_circuitbreaker_state{state=~"open\|forced_open"})` | stat or state timeline; alert when `> 0` for a few minutes |
| Breaker transitions | `increase(r4c_circuitbreaker_transitions_total{to="open"}[1h])` | a flapping breaker opens many times |
| Retries per second | `sum by (instance) (rate(r4c_retry_retries_total[5m]))` | compare with your request rate |
| Give-ups by reason | `sum by (instance, reason) (increase(r4c_retry_gave_up_total[1h]))` | `not_retryable` includes calls stopped by an open breaker |
| Failure rate | `r4c_circuitbreaker_failure_rate_percent` | gaps until `minimumCalls` |
| Bulkhead saturation | `r4c_bulkhead_concurrent_calls / r4c_bulkhead_max_concurrent` | 1 means full; also plot `r4c_bulkhead_waiting_calls` |
| Timeouts per second | `sum by (instance) (rate(r4c_timeout_timed_out_total[5m]))` | |
| Fallbacks per second | `sum by (instance) (rate(r4c_fallback_applied_total[5m]))` | the share of answers that are degraded |

## Logging transitions :id=logging

A listener that logs every breaker state change through `System.Logger` (which goes to your logging backend through its JDK bridge, for example SLF4J's `slf4j-jdk-platform-logging`):

<!-- snippet: observability-logging -->
```java
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import java.lang.System.Logger.Level;

/** Logs every circuit breaker state change: a warning when a breaker opens, info otherwise. */
public final class BreakerTransitionLogger implements EventListener<CircuitBreakerEvent.StateTransition> {

    private final System.Logger logger;

    public BreakerTransitionLogger(System.Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onEvent(CircuitBreakerEvent.StateTransition t) {
        boolean opened = t.to() == CircuitBreakerState.OPEN || t.to() == CircuitBreakerState.FORCED_OPEN;
        logger.log(opened ? Level.WARNING : Level.INFO, "Circuit breaker {0}: {1} -> {2}",
                t.instance(), t.from(), t.to());
    }
}
```

<!-- snippet: observability-logging-wiring -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(CircuitBreakerEvent.StateTransition.class, new BreakerTransitionLogger(logger))
        .build();
```

Log transitions and give-ups, not every `Retrying` or rejection: those can arrive at the rate of your traffic during an incident. Count them instead.

## Health checks :id=health

A check built on the snapshot, for a readiness or diagnostics endpoint:

<!-- snippet: observability-health -->
```java
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import java.util.List;

/** A readiness-style check: degraded while any circuit breaker rejects calls. */
public record ResilienceHealth(boolean healthy, List<String> openBreakers) {

    public static ResilienceHealth of(ResilienceRegistry registry) {
        List<String> open = registry.snapshot().circuitBreakers().stream()
                .filter(b -> b.state() == CircuitBreakerState.OPEN || b.state() == CircuitBreakerState.FORCED_OPEN)
                .map(CircuitBreakerSnapshot::instance)
                .toList();
        return new ResilienceHealth(open.isEmpty(), open);
    }
}
```

<!-- snippet: observability-health-use -->
```java
ResilienceHealth health = ResilienceHealth.of(registry);
if (!health.healthy()) {
    // report "degraded" with health.openBreakers() on your readiness endpoint
}
```

> [!WARNING]
> Don't wire an open breaker into a **liveness** probe: restarting the service does not fix the dependency the breaker protects, and every instance would restart at once. Report it as degraded, or on a readiness endpoint only if routing traffic away actually helps.
