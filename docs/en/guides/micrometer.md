# Micrometer

Export what the policies do to any Micrometer backend (Prometheus, Datadog, OTLP, …): counters for retries, rejections, breaker transitions, timeouts and fallbacks, and gauges for the state of every circuit breaker, bulkhead and rate limiter.

The module `resilience4cata-micrometer` (available from 0.3.0) is built on the [events and handles](observability.md) of `core`; `core` itself does not depend on Micrometer. It brings in `io.micrometer:micrometer-core` as a transitive dependency (version 1.17.1, the one it is tested with).

## Install :id=install

Import the BOM, as for the other modules ([installation](../start/installation.md)), and add the module:

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.11.0')
    implementation 'dev.resilience4cata:resilience4cata-micrometer'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation(platform("dev.resilience4cata:resilience4cata-bom:0.11.0"))
    implementation("dev.resilience4cata:resilience4cata-micrometer")
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-micrometer</artifactId>
</dependency>
```

<!-- tabs:end -->

With JPMS, add `requires dev.resilience4cata.micrometer;` (it `requires transitive` both `dev.resilience4cata.core` and `micrometer.core`).

## Set up :id=setup

Three lines, around the registry you already build:

<!-- snippet: micrometer-setup -->
```java
MeterRegistry meterRegistry = new SimpleMeterRegistry(); // in your app: the registry you already export
MicrometerMetrics metrics = MicrometerMetrics.create(meterRegistry);
ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // counters, from events
        .build();
metrics.bindTo(registry); // gauges, for every breaker, bulkhead and rate limiter, even those created later
```

- `register(builder)` adds one event listener to the builder and returns the builder, so you keep configuring it (`.retry(…)`, `.listen(…)`, [external configuration](configuration.md)) before `build()`. It must run before `build()`: listeners are fixed when the registry is built.
- `bindTo(registry)` registers the gauges of the instances that exist, and of every instance the registry creates afterwards. Instances are created when a service is wrapped (`Resilient.of`, `Resilient.create`) or a `Pipeline` is built, so you can bind right after `build()`, before wrapping anything.

Annotated services and [pipelines](pipeline.md) are reported the same way. The `instance` tag is the instance id: the `name` set in the annotation, the derived id `pkg.Type#method(java.lang.String)` when there is no name, or the pipeline id.

## Metrics :id=metrics

The names and tags are the ones in [Observability](observability.md#metric-names); the Prometheus column shows how a `PrometheusMeterRegistry` exports them (dots become underscores, counters get `_total`, the percent unit becomes a `_percent` suffix).

| Meter | Type | Tags | In Prometheus |
|---|---|---|---|
| `r4c.calls.rejected` | counter | `instance`, `kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `r4c_calls_rejected_total` |
| `r4c.circuitbreaker.transitions` | counter | `instance`, `from`, `to` | `r4c_circuitbreaker_transitions_total` |
| `r4c.retry.retries` | counter | `instance` | `r4c_retry_retries_total` |
| `r4c.retry.gave.up` | counter | `instance`, `reason` | `r4c_retry_gave_up_total` |
| `r4c.hedge.hedged` | counter | `instance`, `reason` | `r4c_hedge_hedged_total` |
| `r4c.hedge.won` | counter | `instance` | `r4c_hedge_won_total` |
| `r4c.hedge.skipped` | counter | `instance`, `reason` | `r4c_hedge_skipped_total` |
| `r4c.hedge.failed` | counter | `instance` | `r4c_hedge_failed_total` |
| `r4c.adaptivelimit.limit.changes` | counter | `instance` | `r4c_adaptivelimit_limit_changes_total` |
| `r4c.timeout.timed.out` | counter | `instance` | `r4c_timeout_timed_out_total` |
| `r4c.fallback.applied` | counter | `instance` | `r4c_fallback_applied_total` |
| `r4c.circuitbreaker.state` | gauge | `instance`, `state` | `r4c_circuitbreaker_state` |
| `r4c.circuitbreaker.failure.rate` | gauge, percent | `instance` | `r4c_circuitbreaker_failure_rate_percent` |
| `r4c.circuitbreaker.slow.call.rate` | gauge, percent | `instance` | `r4c_circuitbreaker_slow_call_rate_percent` |
| `r4c.bulkhead.concurrent.calls` | gauge | `instance` | `r4c_bulkhead_concurrent_calls` |
| `r4c.bulkhead.waiting.calls` | gauge | `instance` | `r4c_bulkhead_waiting_calls` |
| `r4c.bulkhead.max.concurrent` | gauge | `instance` | `r4c_bulkhead_max_concurrent` |
| `r4c.ratelimiter.available.permits` | gauge | `instance` | `r4c_ratelimiter_available_permits` |
| `r4c.adaptivelimit.limit` | gauge | `instance` | `r4c_adaptivelimit_limit` |
| `r4c.adaptivelimit.concurrent.calls` | gauge | `instance` | `r4c_adaptivelimit_concurrent_calls` |

Tag values are lowercase: states `closed`, `open`, `half_open`, `forced_open`, `disabled`; retry reasons `max_attempts`, `max_duration`, `not_retryable`, `interrupted`, `classifier_failed`, `budget_exhausted`; hedge reasons `delay`, `failure` (`hedged`) and `budget_exhausted`, `rejected`, `aborted` (`skipped`) (a reason added in a later version appears on its own).

When each meter appears:

- **Counters** are registered at their first event: a service that never retried has no `r4c_retry_retries_total` series yet. In PromQL, `… or vector(0)` fills the gap when you need a zero.
- **Gauges** are registered when the instance is bound: the circuit breaker's five `state` series (one is 1, the others 0), its two rates, the bulkhead's three gauges, the rate limiter's one and the adaptive limit's two. Retries, hedges, timeouts and fallbacks have no gauges.
- **The rates are NaN** until the breaker's window holds `minimumCalls` calls, while the breaker is open, forced open or disabled (there is no window then), and, for the slow-call rate, when slow-call detection is off (no `slowCallDuration`). Prometheus stores NaN as a value; graphs show a gap.

## What Prometheus shows :id=prometheus

The `PaymentGateway` of these examples has `@Retry(maxAttempts = 3)`, `@CircuitBreaker(windowSize = 4, minimumCalls = 4)`, a rate limiter of 100 permits per second and a bulkhead of 10, all named `payments`. After two calls against a gateway that is down (the first call fails three times; the fourth failure opens the breaker, which rejects the second call's retry), a scrape contains these lines. They come from a real `PrometheusMeterRegistry` scrape in the project's tests, without the `# HELP` and `# TYPE` lines:

<!-- snippet: micrometer-prometheus -->
```text
r4c_bulkhead_concurrent_calls{instance="payments"} 0.0
r4c_bulkhead_max_concurrent{instance="payments"} 10.0
r4c_bulkhead_waiting_calls{instance="payments"} 0.0
r4c_calls_rejected_total{instance="payments",kind="circuitbreaker"} 1.0
r4c_circuitbreaker_failure_rate_percent{instance="payments"} NaN
r4c_circuitbreaker_slow_call_rate_percent{instance="payments"} NaN
r4c_circuitbreaker_state{instance="payments",state="closed"} 0.0
r4c_circuitbreaker_state{instance="payments",state="disabled"} 0.0
r4c_circuitbreaker_state{instance="payments",state="forced_open"} 0.0
r4c_circuitbreaker_state{instance="payments",state="half_open"} 0.0
r4c_circuitbreaker_state{instance="payments",state="open"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",instance="payments",to="open"} 1.0
r4c_ratelimiter_available_permits{instance="payments"} 98.0
r4c_retry_gave_up_total{instance="payments",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{instance="payments",reason="not_retryable"} 1.0
r4c_retry_retries_total{instance="payments"} 3.0
```

The failure rate is NaN because the breaker is open. The rate limiter shows 98 of 100 permits: four attempts took one each, and the retry delays gave some back (the test runs on the [manual clock](testing.md), so the numbers are exact).

## Grafana panels :id=grafana

PromQL for the panels most services need:

| Panel | Query | Notes |
|---|---|---|
| Rejections per second, by policy | `sum by (instance, kind) (rate(r4c_calls_rejected_total[5m]))` | stacked time series |
| Breakers open now | `sum by (instance) (r4c_circuitbreaker_state{state=~"open\|forced_open"})` | stat or state timeline; alert when `> 0` for a few minutes |
| Breaker transitions | `increase(r4c_circuitbreaker_transitions_total{to="open"}[1h])` | a flapping breaker opens many times |
| Retries per second | `sum by (instance) (rate(r4c_retry_retries_total[5m]))` | compare with your request rate |
| Give-ups by reason | `sum by (instance, reason) (increase(r4c_retry_gave_up_total[1h]))` | `not_retryable` includes calls stopped by an open breaker |
| Failure rate | `r4c_circuitbreaker_failure_rate_percent` | gaps until `minimumCalls`, and while open |
| Bulkhead saturation | `r4c_bulkhead_concurrent_calls / r4c_bulkhead_max_concurrent` | 1 means full; also plot `r4c_bulkhead_waiting_calls` |
| Rate limiter headroom | `min by (instance) (r4c_ratelimiter_available_permits)` | near 0 means callers are being throttled |
| Timeouts per second | `sum by (instance) (rate(r4c_timeout_timed_out_total[5m]))` | |
| Fallbacks per second | `sum by (instance) (rate(r4c_fallback_applied_total[5m]))` | the share of answers that are degraded |

## Cardinality :id=cardinality

Every series carries `instance`, and its other tags have a fixed set of values, so the number of series grows with the number of instances, not with traffic:

- a circuit breaker: 7 gauge series, plus up to 25 `transitions` series (in practice the handful of transitions that happen) and one `rejected`;
- a retry: one `retries` series and one `gave.up` series per reason that occurred;
- a hedge: up to two `hedged` series, one `won`, one `failed`, and one `skipped` series per reason that occurred;
- a bulkhead: 3 gauges and one `rejected`; a rate limiter: 1 gauge and one `rejected`; a timeout or a fallback: one counter.

Keep instance ids bounded: they come from your code (an annotation `name`, a method, a pipeline id), so they are, unless you build pipeline ids from request data. Never do that, for metrics and for the policies themselves, which would then never share state. Prefer a `name` shared by the methods that call the same dependency: one series per dependency is easier to read than one per method. Don't add the exception class or message as a tag yourself: they are unbounded in practice.

## Lifecycle :id=lifecycle

- **One `MicrometerMetrics` per `MeterRegistry`.** To export to several backends, give it a `CompositeMeterRegistry`. One `MicrometerMetrics` can serve several resilience registries: call `register` on each builder and `bindTo` on each registry. Micrometer identifies a meter by name and tags only, so if two bound registries have an instance with the same id, their counters add up and the gauges of the registry bound first are reported.
- **`bindTo` is idempotent** for the same registry, and safe from any thread, as is everything else in the class.
- **Nothing keeps a discarded registry alive.** Gauges hold the policies weakly (Micrometer's default), so the meter registry never keeps a `ResilienceRegistry` reachable; once that registry is garbage collected, its gauges report NaN. The other way round, a bound registry keeps the `MicrometerMetrics` and its meter registry reachable, which is what you want while it runs.
- **`close()`** removes every meter it registered from the meter registry, stops counting events and stops registering gauges for new instances. The services keep working. Call it when you discard a registry before the end of the application (in tests, or when a component is restarted):

<!-- snippet: micrometer-close -->
```java
metrics.close(); // removes every meter it registered; the services keep working
```

## What it costs :id=cost

- A **successful call** costs nothing more: it publishes no event, and gauges are not on the call path.
- An **event** (a retry, a rejection, a transition, a timeout, a fallback) costs a lookup of the cached counter by instance id, an array read and Micrometer's increment. The first event of each counter registers it with Micrometer. Apart from the event record itself, a cached event allocates nothing (a test checks it). If Micrometer throws (a registry that rejects a meter, for example), `core` passes the exception to the thread's uncaught exception handler and the call goes on.
- A **scrape** reads the handles; the two rates take a breaker snapshot each, which walks the breaker's window.
- **Creating an instance** (when wrapping a service or building a pipeline) lists the registry's instances once to register the new gauges.

The overhead of the module has not been measured yet.
