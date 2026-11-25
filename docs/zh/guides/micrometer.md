# Micrometer

把策略的行为导出到任何 Micrometer 后端（Prometheus、Datadog、OTLP 等）：针对重试、拒绝、熔断器状态转换、超时和降级的计数器，以及针对每个熔断器、舱壁和限流器状态的仪表（gauge）。

`resilience4cata-micrometer` 模块（自 0.3.0 起可用）构建在 `core` 的[事件和句柄](observability.md)之上；`core` 本身不依赖 Micrometer。它以传递依赖的形式引入 `io.micrometer:micrometer-core`（版本 1.17.1，即测试所用的版本）。

## 安装 :id=install

与其他模块一样导入 BOM（[安装](../start/installation.md)），然后添加该模块：

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    implementation 'dev.resilience4cata:resilience4cata-micrometer'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation(platform("dev.resilience4cata:resilience4cata-bom:1.1.0"))
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

使用 JPMS 时，添加 `requires dev.resilience4cata.micrometer;`（它对 `dev.resilience4cata.core` 和 `micrometer.core` 都使用 `requires transitive`）。

## 设置 :id=setup

围绕你已经在构建的注册表，只需三行：

<!-- snippet: micrometer-setup -->
```java
MeterRegistry meterRegistry = new SimpleMeterRegistry(); // in your app: the registry you already export
MicrometerMetrics metrics = MicrometerMetrics.create(meterRegistry);
ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // counters, from events
        .build();
metrics.bindTo(registry); // gauges, for every breaker, bulkhead and rate limiter, even those created later
```

- `register(builder)` 向构建器添加一个事件监听器并返回该构建器，因此你可以在 `build()` 之前继续配置它（`.retry(…)`、`.listen(…)`、[外部配置](configuration.md)）。它必须在 `build()` 之前运行：监听器在注册表构建时就固定了。
- `bindTo(registry)` 为已存在的实例以及注册表此后创建的每个实例注册仪表。实例在包装服务（`Resilient.of`、`Resilient.create`）或构建 `Pipeline` 时创建，因此你可以在 `build()` 之后、包装任何东西之前立即绑定。

注解服务和 [pipeline](pipeline.md) 以相同方式报告。`name` 标签是实例 id：注解中设置的 `name`；没有名称时是派生 id `pkg.Type#method(java.lang.String)`；或者是 pipeline id。

## 指标 :id=metrics

名称和标签就是[可观测性](observability.md#metric-names)中的那些；Prometheus 列显示 `PrometheusMeterRegistry` 如何导出它们（点变为下划线，计数器加上 `_total`，百分比单位变为 `_percent` 后缀）。

| Meter | 类型 | 标签 | 在 Prometheus 中 |
|---|---|---|---|
| `r4c.calls.rejected` | 计数器 | `name`、`kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `r4c_calls_rejected_total` |
| `r4c.circuitbreaker.transitions` | 计数器 | `name`、`from`、`to` | `r4c_circuitbreaker_transitions_total` |
| `r4c.retry.retries` | 计数器 | `name` | `r4c_retry_retries_total` |
| `r4c.retry.gave.up` | 计数器 | `name`、`reason` | `r4c_retry_gave_up_total` |
| `r4c.hedge.hedged` | 计数器 | `name`、`reason` | `r4c_hedge_hedged_total` |
| `r4c.hedge.won` | 计数器 | `name` | `r4c_hedge_won_total` |
| `r4c.hedge.skipped` | 计数器 | `name`、`reason` | `r4c_hedge_skipped_total` |
| `r4c.hedge.failed` | 计数器 | `name` | `r4c_hedge_failed_total` |
| `r4c.adaptivelimit.limit.changes` | 计数器 | `name` | `r4c_adaptivelimit_limit_changes_total` |
| `r4c.timeout.timed.out` | 计数器 | `name` | `r4c_timeout_timed_out_total` |
| `r4c.fallback.applied` | 计数器 | `name` | `r4c_fallback_applied_total` |
| `r4c.circuitbreaker.state` | 仪表 | `name`、`state` | `r4c_circuitbreaker_state` |
| `r4c.circuitbreaker.failure.rate` | 仪表，百分比 | `name` | `r4c_circuitbreaker_failure_rate_percent` |
| `r4c.circuitbreaker.slow.call.rate` | 仪表，百分比 | `name` | `r4c_circuitbreaker_slow_call_rate_percent` |
| `r4c.bulkhead.concurrent.calls` | 仪表 | `name` | `r4c_bulkhead_concurrent_calls` |
| `r4c.bulkhead.waiting.calls` | 仪表 | `name` | `r4c_bulkhead_waiting_calls` |
| `r4c.bulkhead.max.concurrent` | 仪表 | `name` | `r4c_bulkhead_max_concurrent` |
| `r4c.ratelimiter.available.permits` | 仪表 | `name` | `r4c_ratelimiter_available_permits` |
| `r4c.adaptivelimit.limit` | 仪表 | `name` | `r4c_adaptivelimit_limit` |
| `r4c.adaptivelimit.concurrent.calls` | 仪表 | `name` | `r4c_adaptivelimit_concurrent_calls` |

标签值为小写：状态 `closed`、`open`、`half_open`、`forced_open`、`disabled`；重试原因 `max_attempts`、`max_duration`、`not_retryable`、`interrupted`、`classifier_failed`、`budget_exhausted`；对冲请求原因 `delay`、`failure`（`hedged`）以及 `budget_exhausted`、`rejected`、`aborted`（`skipped`）（后续版本新增的原因会自动出现）。

各个 meter 何时出现：

- **计数器** 在其第一个事件时注册：从未重试过的服务还没有 `r4c_retry_retries_total` 序列。在 PromQL 中，需要零值时用 `… or vector(0)` 填补空缺。
- **仪表** 在实例被绑定时注册：熔断器的五个 `state` 序列（一个为 1，其余为 0）、它的两个比率、舱壁的三个仪表、限流器的一个以及自适应限流的两个。重试、对冲请求、超时和降级没有仪表。
- **比率为 NaN**：直到熔断器的窗口中有 `minimumCalls` 次调用为止；在熔断器打开、强制打开或禁用期间（此时没有窗口）；以及对于慢调用比率，在慢调用检测关闭时（没有 `slowCallDuration`）。Prometheus 把 NaN 作为值存储；图表中显示为空缺。

## Prometheus 中显示的内容 :id=prometheus

这些示例中的 `PaymentGateway` 具有 `@Retry(maxAttempts = 3)`、`@CircuitBreaker(windowSize = 4, minimumCalls = 4)`、每秒 100 个许可的限流器以及大小为 10 的舱壁，全部命名为 `payments`。在针对一个已宕机的网关发起两次调用之后（第一次调用失败三次；第四次失败使熔断器打开，熔断器拒绝了第二次调用的重试），一次抓取包含以下各行。它们来自项目测试中一次真实的 `PrometheusMeterRegistry` 抓取，去掉了 `# HELP` 和 `# TYPE` 行：

<!-- snippet: micrometer-prometheus -->
```text
r4c_bulkhead_concurrent_calls{name="payments"} 0.0
r4c_bulkhead_max_concurrent{name="payments"} 10.0
r4c_bulkhead_waiting_calls{name="payments"} 0.0
r4c_calls_rejected_total{kind="circuitbreaker",name="payments"} 1.0
r4c_circuitbreaker_failure_rate_percent{name="payments"} NaN
r4c_circuitbreaker_slow_call_rate_percent{name="payments"} NaN
r4c_circuitbreaker_state{name="payments",state="closed"} 0.0
r4c_circuitbreaker_state{name="payments",state="disabled"} 0.0
r4c_circuitbreaker_state{name="payments",state="forced_open"} 0.0
r4c_circuitbreaker_state{name="payments",state="half_open"} 0.0
r4c_circuitbreaker_state{name="payments",state="open"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",name="payments",to="open"} 1.0
r4c_ratelimiter_available_permits{name="payments"} 98.0
r4c_retry_gave_up_total{name="payments",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{name="payments",reason="not_retryable"} 1.0
r4c_retry_retries_total{name="payments"} 3.0
```

失败率为 NaN，因为熔断器是打开的。限流器显示 100 个许可中还剩 98 个：四次尝试各占用一个，而重试延迟期间又恢复了一些（测试运行在[手动时钟](testing.md)上，因此数字是精确的）。

## Grafana 面板 :id=grafana

大多数服务所需面板的 PromQL：

| 面板 | 查询 | 说明 |
|---|---|---|
| 每秒拒绝数，按策略 | `sum by (instance, kind) (rate(r4c_calls_rejected_total[5m]))` | 堆叠时间序列 |
| 当前打开的熔断器 | `sum by (instance) (r4c_circuitbreaker_state{state=~"open\|forced_open"})` | 统计值或状态时间线；持续几分钟 `> 0` 时告警 |
| 熔断器状态转换 | `increase(r4c_circuitbreaker_transitions_total{to="open"}[1h])` | 反复振荡的熔断器会打开很多次 |
| 每秒重试数 | `sum by (instance) (rate(r4c_retry_retries_total[5m]))` | 与你的请求速率对比 |
| 按原因的放弃次数 | `sum by (instance, reason) (increase(r4c_retry_gave_up_total[1h]))` | `not_retryable` 包括被打开的熔断器阻止的调用 |
| 失败率 | `r4c_circuitbreaker_failure_rate_percent` | 达到 `minimumCalls` 之前以及打开期间有空缺 |
| 舱壁饱和度 | `r4c_bulkhead_concurrent_calls / r4c_bulkhead_max_concurrent` | 1 表示已满；同时绘制 `r4c_bulkhead_waiting_calls` |
| 限流器余量 | `min by (instance) (r4c_ratelimiter_available_permits)` | 接近 0 表示调用方正在被限流 |
| 每秒超时数 | `sum by (instance) (rate(r4c_timeout_timed_out_total[5m]))` | |
| 每秒降级数 | `sum by (instance) (rate(r4c_fallback_applied_total[5m]))` | 降级回答所占的比例 |

## 基数 :id=cardinality

每个序列都带有 `name`，其他标签的取值集合是固定的，因此序列数量随实例数量增长，而不随流量增长：

- 一个熔断器：7 个仪表序列，外加最多 25 个 `transitions` 序列（实际上只有发生过的少数几种转换）和一个 `rejected`；
- 一个重试：一个 `retries` 序列，以及每个出现过的原因各一个 `gave.up` 序列；
- 一个对冲请求：最多两个 `hedged` 序列、一个 `won`、一个 `failed`，以及每个出现过的原因各一个 `skipped` 序列；
- 一个舱壁：3 个仪表和一个 `rejected`；一个限流器：1 个仪表和一个 `rejected`；一个超时或一个降级：一个计数器。

让实例 id 保持有界：它们来自你的代码（注解的 `name`、方法、pipeline id），因此本来就是有界的，除非你用请求数据构建 pipeline id。永远不要这样做，无论是为了指标还是为了策略本身——那样策略将永远无法共享状态。优先使用由调用同一依赖的方法共享的 `name`：每个依赖一个序列比每个方法一个序列更易读。不要自己把异常类或消息添加为标签：实际上它们是无界的。

## 生命周期 :id=lifecycle

- **每个 `MeterRegistry` 一个 `MicrometerMetrics`。** 要导出到多个后端，请给它一个 `CompositeMeterRegistry`。一个 `MicrometerMetrics` 可以服务多个弹性注册表：在每个构建器上调用 `register`，在每个注册表上调用 `bindTo`。Micrometer 仅通过名称和标签识别 meter，因此如果两个已绑定的注册表中有 id 相同的实例，它们的计数器会相加，而报告的是先绑定的注册表的仪表。
- **`bindTo` 是幂等的**（对同一注册表而言），并且可以从任何线程安全调用，类中的其他所有方法也是如此。
- **没有任何东西会让被丢弃的注册表保持存活。** 仪表以弱引用持有策略（Micrometer 的默认行为），因此 meter 注册表永远不会让 `ResilienceRegistry` 保持可达；一旦该注册表被垃圾回收，它的仪表就报告 NaN，直到之后绑定的某个注册表创建了相同 id 的实例，由该实例的仪表取而代之。反过来，已绑定的注册表会让 `MicrometerMetrics` 及其 meter 注册表保持可达，这正是它运行期间你所需要的。
- **`close()`** 从 meter 注册表中移除它注册的每个 meter，停止计数事件，并停止为新实例注册仪表。服务继续正常工作。当你在应用结束之前丢弃一个注册表时（在测试中，或组件重启时），请调用它：

<!-- snippet: micrometer-close -->
```java
metrics.close(); // removes every meter it registered; the services keep working
```

## 开销 :id=cost

- **成功的调用** 没有任何额外开销：它不发布事件，仪表也不在调用路径上。
- **事件**（重试、拒绝、状态转换、超时、降级）的开销是按实例 id 查找缓存的计数器、一次数组读取以及 Micrometer 的递增。每个计数器的第一个事件会把它注册到 Micrometer。除事件记录本身之外，缓存命中的事件不分配任何内存（有测试检查这一点）。如果 Micrometer 抛出异常（例如拒绝某个 meter 的注册表），`core` 会把异常交给线程的未捕获异常处理器，调用继续进行。
- **抓取** 读取句柄；两个比率各获取一次熔断器快照，这会遍历熔断器的窗口。
- **创建实例**（包装服务或构建 pipeline 时）会列出一次注册表的实例，以注册新的仪表。

该模块的开销尚未测量。
