# OpenTelemetry

将策略的行为导出为 OpenTelemetry 指标：拒绝、重试、熔断器状态转换、超时和降级作为计数器，熔断器、舱壁隔离和限流器的状态作为 gauge。

`resilience4cata-opentelemetry` 构建在 `core` 的[事件和快照](observability.md)之上：一个监听器把事件转换为计数器，异步 gauge 在 SDK 每次采集时读取注册表的快照。它报告的指标名称和属性与 Micrometer 模块相同，因此仪表盘和告警对两者都适用。

本版本**只有指标**，还没有追踪 span。

## 依赖 :id=dependency

该模块依赖 `resilience4cata-core` 和 OpenTelemetry API（`io.opentelemetry:opentelemetry-api`，1.62.0 或更高版本），别无其他。SDK 和导出器由你自己提供：模块从不配置它们。

<!-- tabs:start -->
#### **Gradle**
```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.0.0')
    implementation 'dev.resilience4cata:resilience4cata-opentelemetry'    // version from the BOM

    implementation platform('io.opentelemetry:opentelemetry-bom:1.67.0')  // your SDK and exporter
    implementation 'io.opentelemetry:opentelemetry-sdk'
}
```
#### **Gradle (Kotlin)**
```kotlin
dependencies {
    implementation(platform("dev.resilience4cata:resilience4cata-bom:1.0.0"))
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

该模块需要 OpenTelemetry 1.62.0 或更高版本，这是 Spring Boot 4.1、Quarkus 3.40 和 Micronaut 5.2 所带的最旧版本；它的测试在 1.62.0（API 和 SDK）和 1.67.0 上运行。OpenTelemetry 保持其稳定的 1.x API 向后兼容，因此更新的 1.x SDK 应该也能工作。使用 JPMS 时，添加 `requires dev.resilience4cata.opentelemetry;`。OpenTelemetry API jar 是一个自动模块（`io.opentelemetry.api`），以传递方式 require。

## 配置 :id=setup

三个步骤：在你的 `OpenTelemetry` 实例上创建指标，在注册表 builder 上注册它们的监听器，然后绑定注册表以获得 gauge。

<!-- snippet: opentelemetry-wiring -->
```java
OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(openTelemetry); // your SDK instance
ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // adds the counters' listener
        .build();
metrics.bindTo(registry); // gauges, read at each collection: later instances are included
PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
```

- `register(builder)` 必须在 **`build()` 之前**运行：监听器在构建注册表时就固定了。它返回同一个 builder，因此其余配置（`retry(...)`、`ticker(...)`、`ResilienceConfig.applyTo(...)`）照常链式调用。
- `bindTo(registry)` 可以在任何时候运行。策略实例是延迟创建的，在包装接口或构建 pipeline 时才创建；gauge 在每次采集时读取 `registry.snapshot()`，因此在 `bindTo` 之后创建的实例会在下一次采集时出现，无需额外调用。
- 当你只有 meter provider 时，`create(MeterProvider)` 与 `create(OpenTelemetry)` 作用相同。如果你的应用（或 Java agent）设置了 `GlobalOpenTelemetry.get()`，它也可以使用。
- 在框架中：当存在 Quarkus OpenTelemetry 而没有 Micrometer 时，[Quarkus 扩展](quarkus.md#observability)会自行注册这些指标；[Spring Boot](spring.md#opentelemetry) 和 [Micronaut](micronaut.md#opentelemetry) 只绑定 Micrometer，需要把同样的三个步骤作为 bean 装配起来。

## 指标 :id=metrics

每个指标都有一个 `name` 属性：策略实例 id（注解中的 `name`、派生的 `pkg.Type#method(...)` id，或 pipeline id）。实例 id 在你的代码中是固定的，因此该属性的取值是有界的。属性值为小写。

| 名称 | OpenTelemetry 仪器 | 单位 | 属性 | 来源 |
|---|---|---|---|---|
| `r4c.calls.rejected` | `LongCounter` | `{call}` | `name`, `kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `CircuitBreakerEvent.Rejected`, `RateLimiterEvent.Rejected`, `AdaptiveLimitEvent.Rejected`, `BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | `LongCounter` | `{transition}` | `name`, `from`, `to` (`closed`, `open`, `half_open`, `forced_open`, `disabled`) | `CircuitBreakerEvent.StateTransition` |
| `r4c.retry.retries` | `LongCounter` | `{retry}` | `name` | `RetryEvent.Retrying` |
| `r4c.retry.gave.up` | `LongCounter` | `{call}` | `name`, `reason` (`max_attempts`, `max_duration`, `not_retryable`, `interrupted`, `classifier_failed`, `budget_exhausted`) | `RetryEvent.GaveUp` |
| `r4c.hedge.hedged` | `LongCounter` | `{attempt}` | `name`, `reason` (`delay`, `failure`) | `HedgeEvent.Hedged` |
| `r4c.hedge.won` | `LongCounter` | `{call}` | `name` | `HedgeEvent.Won` |
| `r4c.hedge.skipped` | `LongCounter` | `{attempt}` | `name`, `reason` (`budget_exhausted`, `rejected`, `aborted`) | `HedgeEvent.Skipped` |
| `r4c.hedge.failed` | `LongCounter` | `{call}` | `name` | `HedgeEvent.Failed` |
| `r4c.adaptivelimit.limit.changes` | `LongCounter` | `{window}` | `name` | `AdaptiveLimitEvent.LimitChanged` |
| `r4c.timeout.timed.out` | `LongCounter` | `{call}` | `name` | `TimeoutEvent.TimedOut` |
| `r4c.fallback.applied` | `LongCounter` | `{call}` | `name` | `FallbackEvent.Applied` |
| `r4c.circuitbreaker.state` | `ObservableLongGauge` | 无 | `name`, `state`：当前状态为 1，其他四个为 0 | 快照 `state` |
| `r4c.circuitbreaker.failure.rate` | `ObservableDoubleGauge` | `%` | `name` | 快照 `failureRate` |
| `r4c.circuitbreaker.slow.call.rate` | `ObservableDoubleGauge` | `%` | `name` | 快照 `slowCallRate` |
| `r4c.bulkhead.concurrent.calls` | `ObservableLongGauge` | `{call}` | `name` | 快照 `concurrentCalls` |
| `r4c.bulkhead.waiting.calls` | `ObservableLongGauge` | `{call}` | `name` | 快照 `waitingCalls` |
| `r4c.bulkhead.max.concurrent` | `ObservableLongGauge` | `{call}` | `name` | 快照 `maxConcurrent` |
| `r4c.ratelimiter.available.permits` | `ObservableLongGauge` | `{permit}` | `name` | 快照 `availablePermits` |
| `r4c.adaptivelimit.limit` | `ObservableLongGauge` | `{call}` | `name` | 快照 `limit` |
| `r4c.adaptivelimit.concurrent.calls` | `ObservableLongGauge` | `{call}` | `name` | 快照 `concurrentCalls` |

- **比率是百分比**（0 到 100），单位为 `%`，与快照和 Micrometer 模块一致，而不是单位为 `1` 的比值（0 到 1）。
- **比率在有意义之前没有数据点。** 在熔断器的窗口积累到 `minimumCalls` 次调用之前（半开时为 `halfOpenCalls`），在熔断器打开、强制打开或禁用时（此时没有窗口），以及对于 `slow.call.rate` 在慢调用检测关闭时，快照报告 `-1`。OpenTelemetry 的数据点不能是 NaN，因此模块在那次采集中不为该实例记录数据点：该序列会出现空缺。
- **舱壁隔离的 gauge 就是 gauge**，而不是 `ObservableUpDownCounter`，这样每个后端看到的类型都与 Micrometer 模块相同。
- `reason` 跟随 `RetryEvent.Reason`：后续版本新增的原因会作为新的属性值出现，模块无需改动。
- instrumentation scope 为 `dev.resilience4cata`，scope 版本为模块的版本。

不要把异常类或消息作为属性：在实践中它们是无界的。重试和超时实例没有 gauge：它们的状态就是它们的配置。

## 开销 :id=cost

- **成功的调用没有开销。** 策略在成功时不发布任何事件，而监听器是该模块添加到调用路径上的唯一代码。
- **每个事件的开销是一次计数器 `add`。** 监听器从缓存中找到事件的 `Attributes`（每个实例和属性值构建一次，因为 OpenTelemetry 属性是不可变的），因此除了事件记录本身之外不做任何内存分配；随后 SDK 自己的 `add` 执行聚合工作，并且一旦序列存在，也不再做任何内存分配（已用 SDK 1.62.0 和 1.67.0 的测试检查）。事件只在拒绝、重试、放弃、状态转换、超时和降级时发布。
- **两次采集之间 gauge 没有开销。** 一个批量回调在每次采集时构建一个 `registry.snapshot()`，它会遍历熔断器的窗口，频率为 reader 的间隔。
- **监听器从不抛出异常**：OpenTelemetry 内部的失败通过 `System.Logger` 以 `DEBUG` 级别记录，调用继续进行。

尚未测量：一个事件在 SDK 聚合下所花的时间。

## 导出 :id=exporting

该模块只创建仪器；由你的 SDK 决定数据发往何处。两种常见配置：

### Prometheus :id=prometheus

SDK 的 Prometheus 导出器（`io.opentelemetry:opentelemetry-exporter-prometheus`，以 `-alpha` 版本发布）从它自己的 HTTP 服务器提供 `/metrics`。此配置在本仓库中经过测试：

<!-- snippet: opentelemetry-prometheus -->
```java
PrometheusHttpServer prometheus = PrometheusHttpServer.builder().setPort(port).build(); // 9464 by default
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(prometheus).build())
        .build();
```

熔断器打开后，一次抓取包含如下行（来自测试；有删节，省略了 `# HELP`/`# TYPE` 行和 `otel_scope_version` 标签）：

```text
r4c_retry_retries_total{name="payments",otel_scope_name="dev.resilience4cata"} 3.0
r4c_retry_gave_up_total{name="payments",otel_scope_name="dev.resilience4cata",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{name="payments",otel_scope_name="dev.resilience4cata",reason="not_retryable"} 1.0
r4c_calls_rejected_total{kind="circuitbreaker",name="payments",otel_scope_name="dev.resilience4cata"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",name="payments",otel_scope_name="dev.resilience4cata",to="open"} 1.0
r4c_circuitbreaker_state{name="payments",otel_scope_name="dev.resilience4cata",state="closed"} 0.0
r4c_circuitbreaker_state{name="payments",otel_scope_name="dev.resilience4cata",state="open"} 1.0
r4c_bulkhead_max_concurrent{name="payments",otel_scope_name="dev.resilience4cata"} 10.0
r4c_ratelimiter_available_permits{name="payments",otel_scope_name="dev.resilience4cata"} 100.0
```

导出器把点变为下划线，给计数器加上 `_total`，给比率加上 `_percent`（`r4c_circuitbreaker_failure_rate_percent`），并去掉 `{call}` 之类的单位：**名称与 Micrometer 的 Prometheus 注册表相同**，因此可观测性指南中的 [PromQL 面板](micrometer.md#grafana)无需修改即可使用。导出器会添加 `otel_scope_name` 和 `otel_scope_version` 标签（`setOtelScopeLabelsEnabled(false)` 可去掉它们）以及一个 `target_info` 序列。

### OTLP :id=otlp

要把指标发送到 OpenTelemetry Collector 或 OTLP 后端，请用 OTLP 导出器（`io.opentelemetry:opentelemetry-exporter-otlp`）注册一个周期性 reader。通过 HTTP：

<!-- snippet: opentelemetry-otlp -->
```java
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(OtlpHttpMetricExporter.builder()
                                .setEndpoint(collector + "/v1/metrics") // http://localhost:4318/v1/metrics by default
                                .build())
                        .setInterval(Duration.ofSeconds(30))
                        .build())
                .build())
        .build();
```

对于 gRPC，以同样方式使用 `OtlpGrpcMetricExporter`（默认端口 4317）。此配置针对一个解码导出器发送内容的 OTLP 接收端进行了测试：名称、仪器类型、单位、属性和 scope 的到达结果与[上表](#metrics)一致。

使用 SDK 自动配置模块或 OpenTelemetry Java agent 时，SDK 改由 `OTEL_*` 环境变量配置，`OpenTelemetryMetrics.create(GlobalOpenTelemetry.get())` 会使用它。

## 与 Micrometer 名称相同 :id=naming

Micrometer 模块（`resilience4cata-micrometer`）实现了同一份规范：相同的指标名称、属性键和值以及单位，比率为百分比。从一个切换到另一个，或者同时运行使用两者的服务，你的仪表盘和告警都保持不变。唯一的差异来自导出器（上文提到的额外 `otel_scope_*` 标签和 `target_info`）。

## 关闭 :id=closing

<!-- snippet: opentelemetry-close -->
```java
metrics.close(); // at shutdown: removes the gauge callbacks, and the counters stop counting
```

`OpenTelemetryMetrics` 是 `AutoCloseable`。关闭会移除每个已绑定注册表的批量回调；监听器仍留在注册表上（监听器无法移除），但不再记录任何内容。`close()` 是幂等的，之后 `bindTo` 不做任何事，与 Micrometer 一致。关闭 SDK（`openTelemetry.close()`）仍是你的工作。

再次绑定同一个注册表不做任何事。一个 `OpenTelemetryMetrics` 可以绑定多个注册表，但如果它们的实例 id 有重叠，gauge 会把相同的属性集报告两次：每个注册表使用一个，或者使用不同的 id。

## 追踪 :id=tracing

本版本中没有。为弹性库生成 span 需要指标不需要的决策：一次重试是一个 span 还是每次尝试一个，降级或打开的熔断器如何标记 span，以及 span 在成功路径上的开销，而事件从不触及成功路径。后续版本可以在不付出该开销的情况下起步：从同一个监听器向线程当前的 span 添加 span 事件（`retry`、`circuit_breaker.transition`、`fallback`），并把 span 的创建交给你的 instrumentation。
