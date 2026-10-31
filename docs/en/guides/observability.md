# Observability

See what the policies do through events, read their state through snapshots and handles, and build metrics, logs and health checks on top of both.

resilience4cata gives you two building blocks:

1. **Events**: a record for each thing a policy does other than letting a call through (a retry, a breaker transition, a rejection, a timeout, a fallback), delivered to listeners you register on the registry builder.
2. **Snapshots and handles**: the current state of each instance, read when you ask for it.

> [!TIP]
> For metrics, add the [Micrometer module](micrometer.md) or the [OpenTelemetry module](opentelemetry.md): three lines of setup give you the [metrics below](#metric-names), built on these two blocks. This page describes the blocks themselves, for logging, health checks, or another metrics library.

## Events :id=events

Every event implements the sealed interface `dev.resilience4cata.event.ResilienceEvent`, with `instance()` (the instance id: the `name`, the derived `pkg.Type#method(java.lang.String,int)` id, or a pipeline id) and `nanoTime()` (a reading of the registry's clock: `System.nanoTime()`, or the [manual clock](testing.md) in tests). Each policy has its own sealed interface of records:

| Policy | Event record | Fields (after `instance`, `nanoTime`) | Published when |
|---|---|---|---|
| Circuit breaker | `CircuitBreakerEvent.StateTransition` | `CircuitBreakerState from`, `CircuitBreakerState to` | the state changes, exactly once per change, including `forceOpen()`, `disable()` and `reset()` |
| Circuit breaker | `CircuitBreakerEvent.CallNotPermitted` | `CircuitBreakerState state` | a call is rejected (`OPEN`, `FORCED_OPEN`, or `HALF_OPEN` with every probe taken) |
| Retry | `RetryEvent.Retrying` | `int attempt`, `Duration delay`, `@Nullable Throwable failure` | an attempt failed and another one will run after `delay`; `failure` is `null` when a [classifier](classifiers.md) rejected a result |
| Retry | `RetryEvent.GaveUp` | `int attempts`, `RetryEvent.Reason reason`, `@Nullable Throwable failure` | the retry stops without a success: `MAX_ATTEMPTS`, `MAX_DURATION`, `NOT_RETRYABLE` (the failure is not in `retryOn`, is in `abortOn`, or is an open breaker's `CallNotPermittedException`), `INTERRUPTED`, `CLASSIFIER_FAILED`, `BUDGET_EXHAUSTED` (the [retry budget](../policies/retry.md#retry-budget) is used up) |
| Hedge | `HedgeEvent.Hedged` | `int attempt`, `HedgeEvent.Reason reason` | a [hedge](../policies/hedge.md) started attempt `attempt` (2 or more) next to the others: `DELAY` (no result within `delay`) or `FAILURE` (an attempt failed with a failure in `hedgeOn`) |
| Hedge | `HedgeEvent.Won` | `int attempt` | attempt `attempt` (2 or more) succeeded first and the others were cancelled; not published when the first attempt wins |
| Hedge | `HedgeEvent.Skipped` | `int attempt`, `HedgeEvent.SkipReason reason` | a hedge that would have started did not: `BUDGET_EXHAUSTED` (the hedge budget is used up), `REJECTED` (an inner policy rejected an attempt), `ABORTED` (a failure in `abortOn`, or not in `hedgeOn`, ended the call) |
| Hedge | `HedgeEvent.Failed` | `int attempts`, `Throwable failure` | the hedged call fails with `failure` after `attempts` attempts |
| Rate limiter | `RateLimiterEvent.Rejected` | none | a call is rejected with `RateLimitExceededException` |
| Timeout | `TimeoutEvent.TimedOut` | `Duration timeout` | a call times out |
| Bulkhead | `BulkheadEvent.Rejected` | `int maxConcurrent` | a call is rejected with `BulkheadFullException` |
| Adaptive limit | `AdaptiveLimitEvent.Rejected` | `int limit` | a call is rejected with `LimitExceededException` |
| Adaptive limit | `AdaptiveLimitEvent.LimitChanged` | `int from`, `int to`, `long windowLatencyNanos` | a window ended and the [adaptive limit](../policies/adaptive-limit.md) moved its limit |
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
        // everything: keep a default branch, new policies add event types
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

> [!IMPORTANT]
> **New versions add event types.** The hierarchy is sealed and grows in minor versions (a new policy brings its own event interface, as `HedgeEvent` did in 0.4.0). Keep a `default` branch in a `switch` over `ResilienceEvent` or over one policy's interface, or listen to the record types you need (`listen(RetryEvent.GaveUp.class, …)`), which new types never affect. Code compiled against an older version that receives an unknown type throws `MatchException` in the listener; like any listener exception it goes to the uncaught exception handler and never breaks the call.

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
| `AdaptiveLimitSnapshot` | `instance`, `algorithm`, `limit`, `inFlight`, `minLimit`, `maxLimit`, `windows` (ended so far), `windowLatency` (the last window's average), `longTermLatency` (Gradient2's long-term average), and the method `availableSlots()` |
| `RateLimiterSnapshot` | `instance`, `availablePermits`, `permits` |
| `RetrySnapshot` | `instance`, `config` (the resolved `RetryConfig`) |
| `HedgeSnapshot` | `instance`, `config` (the resolved `HedgeConfig`), `budget` (`calls`, `hedges`, `allowedHedges` in the budget's window; `null` without a budget) |
| `TimeoutSnapshot` | `instance`, `config` (the resolved `TimeoutConfig`) |

`snapshot.only(id)` keeps the instances with one id (`isEmpty()` when there is none). `snapshot.toMap()` turns the
snapshot into maps, lists, strings, numbers, booleans and nulls, which any JSON encoder writes as they are: one key
per policy kind, each a list of one map per instance whose keys are the fields above, in that order, with
configurations nested as maps of their attributes. Durations are ISO-8601 strings (`PT0.2S`), enums and classes
their names, and a rate that is not a finite number is `null`. The Spring Boot and Micronaut endpoints serve exactly
this; keys may be added in minor versions, never removed or renamed.

## Handles :id=handles

Handles read one instance live, and the breaker's can also change its state:

| Method | Returns |
|---|---|
| `registry.circuitBreaker(id)` | `CircuitBreakerHandle`: `id()`, `state()`, `config()`, `snapshot()`, `forceOpen()`, `disable()`, `reset()` |
| `registry.circuitBreakers()` | every breaker handle, sorted by id |
| `registry.rateLimiter(id)` | `RateLimiterHandle`: `id()`, `config()`, `availablePermits()`, `snapshot()` |
| `registry.bulkhead(id)` | `BulkheadHandle`: `id()`, `config()`, `concurrentCalls()`, `waitingCalls()`, `snapshot()` |
| `registry.adaptiveLimit(id)` | `AdaptiveLimitHandle`: `id()`, `config()`, `limit()`, `inFlight()`, `snapshot()` |

An instance exists only once its interface has been wrapped (`Resilient.of`, `Resilient.create`) or its `Pipeline` built; before that, or for an id that does not exist, the lookup throws `IllegalArgumentException` [R4C-REG-009](../errors.md#r4c-reg-009) with the closest id. Retry and timeout have no handle; their configuration is in the snapshot. There is no list method for bulkheads, rate limiters and adaptive limits: enumerate them through `registry.snapshot()`.

## Metrics :id=metrics

The [Micrometer module](micrometer.md) (`resilience4cata-micrometer`) turns the events into counters and the handles into gauges. Its page has the setup, a real Prometheus scrape, Grafana queries and cardinality advice. The [OpenTelemetry module](opentelemetry.md) (`resilience4cata-opentelemetry`) reports the same metrics, with the same names and tags, through the OpenTelemetry Metrics API, so dashboards work with either.

### Metric names :id=metric-names

Names are lowercase and dotted, as Micrometer recommends (Prometheus shows them as `r4c_retry_retries_total` and so on); every meter has an `instance` tag (the instance id: instance ids are fixed in your code, so the tag is bounded) and at most one or two more tags with a fixed set of values. Tag values are lowercase.

| Meter | Type | Tags | Source |
|---|---|---|---|
| `r4c.calls.rejected` | counter | `instance`, `kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `CallNotPermitted`, `RateLimiterEvent.Rejected`, `AdaptiveLimitEvent.Rejected`, `BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | counter | `instance`, `from`, `to` (lowercase states) | `StateTransition` |
| `r4c.retry.retries` | counter | `instance` | `Retrying` |
| `r4c.retry.gave.up` | counter | `instance`, `reason` (lowercase `Reason`) | `GaveUp` |
| `r4c.hedge.hedged` | counter | `instance`, `reason` = `delay` \| `failure` | `Hedged` |
| `r4c.hedge.won` | counter | `instance` | `Won` |
| `r4c.hedge.skipped` | counter | `instance`, `reason` = `budget_exhausted` \| `rejected` \| `aborted` | `Skipped` |
| `r4c.hedge.failed` | counter | `instance` | `Failed` |
| `r4c.adaptivelimit.limit.changes` | counter | `instance` | `LimitChanged` |
| `r4c.timeout.timed.out` | counter | `instance` | `TimedOut` |
| `r4c.fallback.applied` | counter | `instance` | `Applied` |
| `r4c.circuitbreaker.state` | gauge | `instance`, `state`: 1 for the current state, 0 for the others | `state()` |
| `r4c.circuitbreaker.failure.rate`, `r4c.circuitbreaker.slow.call.rate` | gauge (percent) | `instance` | `snapshot()`; NaN until the window holds `minimumCalls` calls |
| `r4c.bulkhead.concurrent.calls`, `r4c.bulkhead.waiting.calls`, `r4c.bulkhead.max.concurrent` | gauge | `instance` | `BulkheadHandle` |
| `r4c.ratelimiter.available.permits` | gauge | `instance` | `RateLimiterHandle` |
| `r4c.adaptivelimit.limit`, `r4c.adaptivelimit.concurrent.calls` | gauge | `instance` | `AdaptiveLimitHandle` |

### Another metrics library :id=diy

A bridge to another library does what the Micrometer module does, with three pieces of the core API: a listener for the counters, the handles read at collection time for the gauges, and `registry.onInstanceCreated(…)` to learn about instances created after you bound the registry (instances are created when a service is wrapped or a pipeline is built, never during a call):

<!-- snippet: observability-diy -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        // counters: from events, on the thread that caused them
        .listen(RetryEvent.Retrying.class, e -> retries.merge(e.instance(), 1L, Long::sum))
        .build();
// gauges: one per instance, read at collection time; new instances are announced once each
registry.onInstanceCreated(() -> {
    for (CircuitBreakerHandle breaker : registry.circuitBreakers()) {
        gauges.putIfAbsent(breaker.id(), () -> breaker.state() == CircuitBreakerState.OPEN ? 1 : 0);
    }
});
```

`onInstanceCreated` runs its action once per new instance, on the thread that creates it, after the instance is visible in `snapshot()` and the handle lookups; it returns a `Registration` whose `close()` stops it. Bulkheads and rate limiters have no list method: find them in `registry.snapshot()`. Cache your counters by instance and tag values, so an event does not register a meter each time.

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
