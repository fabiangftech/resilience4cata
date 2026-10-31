# OpenTelemetry

Export what the policies do as OpenTelemetry metrics: rejections, retries, breaker transitions, timeouts and fallbacks as counters, and the state of breakers, bulkheads and rate limiters as gauges.

`resilience4cata-opentelemetry` builds on the [events and snapshots](observability.md) of `core`: a listener turns events into counters, and asynchronous gauges read the registry's snapshot each time the SDK collects. It reports the same metric names and attributes as the Micrometer module, so dashboards and alerts work with either.

This release has **metrics only**. There are no tracing spans yet.

## Dependency :id=dependency

The module depends on `resilience4cata-core` and the OpenTelemetry API (`io.opentelemetry:opentelemetry-api`, 1.67.0), nothing else. You bring the SDK and the exporter you use: the module never configures them.

<!-- tabs:start -->
#### **Gradle**
```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.11.0')
    implementation 'dev.resilience4cata:resilience4cata-opentelemetry'    // version from the BOM

    implementation platform('io.opentelemetry:opentelemetry-bom:1.67.0')  // your SDK and exporter
    implementation 'io.opentelemetry:opentelemetry-sdk'
}
```
#### **Gradle (Kotlin)**
```kotlin
dependencies {
    implementation(platform("dev.resilience4cata:resilience4cata-bom:0.11.0"))
    implementation("dev.resilience4cata:resilience4cata-opentelemetry")    // version from the BOM

    implementation(platform("io.opentelemetry:opentelemetry-bom:1.67.0"))  // your SDK and exporter
    implementation("io.opentelemetry:opentelemetry-sdk")
}
```
#### **Maven**
```xml
<dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-opentelemetry</artifactId>
    <!-- version from the imported resilience4cata-bom -->
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <!-- version from the imported opentelemetry-bom -->
</dependency>
```
<!-- tabs:end -->

The module is tested with OpenTelemetry 1.67.0. OpenTelemetry keeps its stable 1.x API backward compatible, so a newer 1.x SDK should work too (not tested). With JPMS, add `requires dev.resilience4cata.opentelemetry;`. The OpenTelemetry API jar is an automatic module (`io.opentelemetry.api`), required transitively.

## Setup :id=setup

Three steps: create the metrics on your `OpenTelemetry` instance, register their listener on the registry builder, and bind the registry for the gauges.

<!-- snippet: opentelemetry-wiring -->
```java
OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(openTelemetry); // your SDK instance
ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // adds the counters' listener
        .build();
metrics.bindTo(registry); // gauges, read at each collection: later instances are included
PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
```

- `register(builder)` must run **before `build()`**: listeners are fixed when the registry is built. It returns the same builder, so the rest of your configuration (`retry(...)`, `ticker(...)`, `ResilienceConfig.applyTo(...)`) chains as usual.
- `bindTo(registry)` can run at any time. Policy instances are created lazily, when an interface is wrapped or a pipeline is built; the gauges read `registry.snapshot()` at each collection, so instances created after `bindTo` appear at the next one with no extra call.
- `create(MeterProvider)` does the same as `create(OpenTelemetry)` when you have only the meter provider. `GlobalOpenTelemetry.get()` works too, if your application (or the Java agent) sets it.

## Metrics :id=metrics

Every metric has an `instance` attribute: the policy instance id (the `name` in the annotation, the derived `pkg.Type#method(...)` id, or the pipeline id). Instance ids are fixed in your code, so the attribute is bounded. Attribute values are lowercase.

| Name | OpenTelemetry instrument | Unit | Attributes | Source |
|---|---|---|---|---|
| `r4c.calls.rejected` | `LongCounter` | `{call}` | `instance`, `kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `CircuitBreakerEvent.CallNotPermitted`, `RateLimiterEvent.Rejected`, `AdaptiveLimitEvent.Rejected`, `BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | `LongCounter` | `{transition}` | `instance`, `from`, `to` (`closed`, `open`, `half_open`, `forced_open`, `disabled`) | `CircuitBreakerEvent.StateTransition` |
| `r4c.retry.retries` | `LongCounter` | `{retry}` | `instance` | `RetryEvent.Retrying` |
| `r4c.retry.gave.up` | `LongCounter` | `{call}` | `instance`, `reason` (`max_attempts`, `max_duration`, `not_retryable`, `interrupted`, `classifier_failed`, `budget_exhausted`) | `RetryEvent.GaveUp` |
| `r4c.hedge.hedged` | `LongCounter` | `{attempt}` | `instance`, `reason` (`delay`, `failure`) | `HedgeEvent.Hedged` |
| `r4c.hedge.won` | `LongCounter` | `{call}` | `instance` | `HedgeEvent.Won` |
| `r4c.hedge.skipped` | `LongCounter` | `{attempt}` | `instance`, `reason` (`budget_exhausted`, `rejected`, `aborted`) | `HedgeEvent.Skipped` |
| `r4c.hedge.failed` | `LongCounter` | `{call}` | `instance` | `HedgeEvent.Failed` |
| `r4c.adaptivelimit.limit.changes` | `LongCounter` | `{window}` | `instance` | `AdaptiveLimitEvent.LimitChanged` |
| `r4c.timeout.timed.out` | `LongCounter` | `{call}` | `instance` | `TimeoutEvent.TimedOut` |
| `r4c.fallback.applied` | `LongCounter` | `{call}` | `instance` | `FallbackEvent.Applied` |
| `r4c.circuitbreaker.state` | `ObservableLongGauge` | none | `instance`, `state`: 1 for the current state, 0 for the other four | snapshot `state` |
| `r4c.circuitbreaker.failure.rate` | `ObservableDoubleGauge` | `%` | `instance` | snapshot `failureRate` |
| `r4c.circuitbreaker.slow.call.rate` | `ObservableDoubleGauge` | `%` | `instance` | snapshot `slowCallRate` |
| `r4c.bulkhead.concurrent.calls` | `ObservableLongGauge` | `{call}` | `instance` | snapshot `concurrentCalls` |
| `r4c.bulkhead.waiting.calls` | `ObservableLongGauge` | `{call}` | `instance` | snapshot `waitingCalls` |
| `r4c.bulkhead.max.concurrent` | `ObservableLongGauge` | `{call}` | `instance` | snapshot `maxConcurrent` |
| `r4c.ratelimiter.available.permits` | `ObservableLongGauge` | `{permit}` | `instance` | snapshot `availablePermits` |
| `r4c.adaptivelimit.limit` | `ObservableLongGauge` | `{call}` | `instance` | snapshot `limit` |
| `r4c.adaptivelimit.concurrent.calls` | `ObservableLongGauge` | `{call}` | `instance` | snapshot `concurrentCalls` |

- **Rates are percentages** (0 to 100) with unit `%`, as in the snapshot and the Micrometer module, not ratios (0 to 1) with unit `1`.
- **A rate has no point until it means something.** The snapshot reports `-1` until the breaker's window holds `minimumCalls` calls (`halfOpenCalls` while half-open), while the breaker is open, forced open or disabled (there is no window), and, for `slow.call.rate`, when slow-call detection is off. OpenTelemetry points cannot be NaN, so the module records no point for that instance at that collection: the series has a gap.
- **Bulkhead gauges are gauges**, not `ObservableUpDownCounter`s, so that every backend sees the same type as with the Micrometer module.
- `reason` follows `RetryEvent.Reason`: a reason added in a later version appears as a new attribute value with no change in the module.
- The instrumentation scope is `dev.resilience4cata`, with the module's version as scope version.

Don't add the exception class or message as an attribute: they are unbounded in practice. Retry and timeout instances have no gauges: their state is their configuration.

## What it costs :id=cost

- **Successful calls cost nothing.** Policies publish no event on success, and the listener is the only code this module adds to a call path.
- **Each event costs one counter `add`.** The listener finds the event's `Attributes` in a cache (built once per instance and attribute values, since OpenTelemetry attributes are immutable), so it allocates nothing beyond the event record itself; the SDK's own `add` then does its aggregation work. Events are published only on rejections, retries, give-ups, transitions, timeouts and fallbacks.
- **Gauges cost nothing between collections.** One batch callback builds one `registry.snapshot()` per collection, which walks the breakers' windows, at the reader's interval.
- **The listener never throws**: a failure inside OpenTelemetry is logged at `DEBUG` through `System.Logger` and the call goes on.

Not measured yet: the cost of an event with the SDK's aggregation.

## Exporting :id=exporting

The module only creates instruments; your SDK decides where they go. Two common setups:

### Prometheus :id=prometheus

The SDK's Prometheus exporter (`io.opentelemetry:opentelemetry-exporter-prometheus`, published with an `-alpha` version) serves `/metrics` from its own HTTP server. This setup is tested in this repository:

<!-- snippet: opentelemetry-prometheus -->
```java
PrometheusHttpServer prometheus = PrometheusHttpServer.builder().setPort(port).build(); // 9464 by default
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(prometheus).build())
        .build();
```

After a breaker opened, a scrape contains lines like these (from the test; abridged, `# HELP`/`# TYPE` lines and the `otel_scope_version` label left out):

```text
r4c_retry_retries_total{instance="payments",otel_scope_name="dev.resilience4cata"} 3.0
r4c_retry_gave_up_total{instance="payments",otel_scope_name="dev.resilience4cata",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{instance="payments",otel_scope_name="dev.resilience4cata",reason="not_retryable"} 1.0
r4c_calls_rejected_total{instance="payments",kind="circuitbreaker",otel_scope_name="dev.resilience4cata"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",instance="payments",otel_scope_name="dev.resilience4cata",to="open"} 1.0
r4c_circuitbreaker_state{instance="payments",otel_scope_name="dev.resilience4cata",state="closed"} 0.0
r4c_circuitbreaker_state{instance="payments",otel_scope_name="dev.resilience4cata",state="open"} 1.0
r4c_bulkhead_max_concurrent{instance="payments",otel_scope_name="dev.resilience4cata"} 10.0
r4c_ratelimiter_available_permits{instance="payments",otel_scope_name="dev.resilience4cata"} 100.0
```

The exporter turns dots into underscores, adds `_total` to counters and `_percent` to the rates (`r4c_circuitbreaker_failure_rate_percent`), and drops the `{call}`-style units: the **names are the same as with Micrometer's Prometheus registry**, so the [PromQL panels](micrometer.md#grafana) of the observability guide work unchanged. The exporter adds `otel_scope_name` and `otel_scope_version` labels (`setOtelScopeLabelsEnabled(false)` removes them) and a `target_info` series.

### OTLP :id=otlp

To send metrics to an OpenTelemetry Collector or an OTLP backend, register a periodic reader with the OTLP exporter (`io.opentelemetry:opentelemetry-exporter-otlp`). Illustrative, not tested in this repository:

<!-- not-compiled: illustrative, needs opentelemetry-exporter-otlp and a collector -->
```java
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(
                                OtlpGrpcMetricExporter.builder().setEndpoint("http://localhost:4317").build())
                        .setInterval(Duration.ofSeconds(30))
                        .build())
                .build())
        .build();
```

With the SDK autoconfiguration module or the OpenTelemetry Java agent, the SDK is configured by `OTEL_*` environment variables instead, and `OpenTelemetryMetrics.create(GlobalOpenTelemetry.get())` uses it.

## Same names as Micrometer :id=naming

The Micrometer module (`resilience4cata-micrometer`) implements the same specification: the same metric names, attribute keys and values, and units, rates in percent. Switching from one to the other, or running services with both, keeps your dashboards and alerts. The only differences come from the exporters (the extra `otel_scope_*` labels and `target_info` above).

## Closing :id=closing

<!-- snippet: opentelemetry-close -->
```java
metrics.close(); // at shutdown: removes the gauge callbacks, and the counters stop counting
```

`OpenTelemetryMetrics` is `AutoCloseable`. Closing removes the batch callback of every bound registry; the listener stays on the registry (listeners cannot be removed) but records nothing more. `close()` is idempotent, and `bindTo` throws `IllegalStateException` after it. Closing the SDK (`openTelemetry.close()`) is still your job.

One `OpenTelemetryMetrics` can bind several registries, but if their instance ids overlap, the gauges report the same attribute sets twice: use one per registry, or distinct ids.

## Tracing :id=tracing

Not in this release. Spans for a resilience library need decisions that metrics don't: whether a retry is one span or one per attempt, how a fallback or an open breaker marks the span, and the cost of a span on success paths, which events never touch. A later version could start without that cost by adding span events from the same listener (`retry`, `circuit_breaker.transition`, `fallback`) to the span that is current on the thread, and leave span creation to your instrumentation.
