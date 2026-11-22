# 可观测性

通过事件了解策略在做什么，通过快照和句柄读取它们的状态，并在这两者之上构建指标、日志和健康检查。

resilience4cata 提供两个基础构件：

1. **事件**：策略除放行调用之外所做的每件事（重试、熔断器状态转换、拒绝、超时、降级）都对应一条记录，交付给你在注册表构建器上注册的监听器。
2. **快照和句柄**：每个实例的当前状态，在你请求时读取。

> [!TIP]
> 若需要指标，请添加 [Micrometer 模块](micrometer.md)或 [OpenTelemetry 模块](opentelemetry.md)：三行设置即可获得构建在这两个构件之上的[下列指标](#metric-names)。本页描述这些构件本身，用于日志、健康检查或其他指标库。

## 事件 :id=events

每个事件都实现密封接口 `dev.resilience4cata.event.ResilienceEvent`，带有 `id()`（实例 id：`name`、派生的 `pkg.Type#method(java.lang.String,int)` id 或 pipeline id）和 `nanoTime()`（注册表时钟的读数：`System.nanoTime()`，或测试中的[手动时钟](testing.md)）。每个策略都有自己的由记录组成的密封接口：

| 策略 | 事件记录 | 字段（在 `id`、`nanoTime` 之后） | 发布时机 |
|---|---|---|---|
| 熔断器 | `CircuitBreakerEvent.StateTransition` | `CircuitBreakerState from`、`CircuitBreakerState to` | 状态改变时，每次改变恰好一次，包括 `forceOpen()`、`disable()` 和 `reset()` |
| 熔断器 | `CircuitBreakerEvent.Rejected` | `CircuitBreakerState state` | 调用被拒绝时（`OPEN`、`FORCED_OPEN`，或所有探测都已被占用的 `HALF_OPEN`） |
| 重试 | `RetryEvent.Retrying` | `int attempt`、`Duration delay`、`@Nullable Throwable failure` | 一次尝试失败，且将在 `delay` 之后运行另一次尝试时；当[分类器](classifiers.md)拒绝某个结果时，`failure` 为 `null` |
| 重试 | `RetryEvent.GaveUp` | `int attempts`、`RetryEvent.Reason reason`、`@Nullable Throwable failure` | 重试在没有成功的情况下停止时：`MAX_ATTEMPTS`、`MAX_DURATION`、`NOT_RETRYABLE`（失败不在 `retryOn` 中、在 `abortOn` 中，或是打开的熔断器的 `CallNotPermittedException`）、`INTERRUPTED`、`CLASSIFIER_FAILED`、`BUDGET_EXHAUSTED`（[重试预算](../policies/retry.md#retry-budget)已用完） |
| 对冲请求 | `HedgeEvent.Hedged` | `int attempt`、`HedgeEvent.Reason reason` | [对冲请求](../policies/hedge.md)在其他尝试之外启动了第 `attempt` 次尝试（2 或更大）时：`DELAY`（`delay` 内没有结果）或 `FAILURE`（某次尝试以 `hedgeOn` 中的失败而失败） |
| 对冲请求 | `HedgeEvent.Won` | `int attempt` | 第 `attempt` 次尝试（2 或更大）最先成功、其他尝试被取消时；第一次尝试胜出时不发布 |
| 对冲请求 | `HedgeEvent.Skipped` | `int attempt`、`HedgeEvent.SkipReason reason` | 本应启动的对冲请求没有启动时：`BUDGET_EXHAUSTED`（对冲预算已用完）、`REJECTED`（某个内层策略拒绝了一次尝试）、`ABORTED`（`abortOn` 中的失败或不在 `hedgeOn` 中的失败结束了调用） |
| 对冲请求 | `HedgeEvent.Failed` | `int attempts`、`Throwable failure` | 对冲调用在 `attempts` 次尝试后以 `failure` 失败时 |
| 限流器 | `RateLimiterEvent.Rejected` | 无 | 调用以 `RateLimitExceededException` 被拒绝时 |
| 超时 | `TimeoutEvent.TimedOut` | `Duration timeout` | 调用超时时 |
| 舱壁隔离 | `BulkheadEvent.Rejected` | `int maxConcurrent` | 调用以 `BulkheadFullException` 被拒绝时 |
| 自适应限流 | `AdaptiveLimitEvent.Rejected` | `int limit` | 调用以 `AdaptiveLimitFullException` 被拒绝时 |
| 自适应限流 | `AdaptiveLimitEvent.LimitChanged` | `int from`、`int to`、`Duration windowLatency` | 一个窗口结束、[自适应限流](../policies/adaptive-limit.md)调整了其限制时 |
| 降级 | `FallbackEvent.Applied` | `Throwable failure` | 降级即将针对 `failure` 运行时；当失败因没有降级接收它（`skipOn`，或没有兄弟方法接受其类型）而被重新抛出时不发布 |

`enabled = false` 的重试不发布任何事件。不可重试的第一次失败会发布 `attempts = 1`、`NOT_RETRYABLE` 的 `GaveUp`。

### 监听 :id=listening

监听器在 `ResilienceRegistry.Builder` 上注册，并且只能在那里注册：注册表构建时它们就固定了。可以监听一种记录类型、一个完整的策略接口或 `ResilienceEvent`；可以针对所有实例，也可以针对某一个实例 id：

<!-- snippet: observability-listen -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        // one record type, on every instance
        .listen(CircuitBreakerEvent.StateTransition.class,
                t -> log.add(t.id() + ": " + t.from() + " -> " + t.to()))
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

- **监听器同步运行**，在引起事件的线程上：调用方的线程，或者对于异步超时和延迟的异步重试，是库的虚拟线程。请让它们保持快速；把缓慢的工作（网络调用）交给你自己的执行器。
- **监听器抛出的异常** 不会到达调用或其他监听器：它会交给线程的未捕获异常处理器。
- **顺序：**从不同线程发布的事件可能乱序到达；每次熔断器状态转换恰好到达一次。
- `EventListener<E>` 是一个函数式接口（`void onEvent(E event)`），因此 lambda 或方法引用都可以使用。

> [!IMPORTANT]
> **新版本会增加事件类型。** 该层次结构是密封的，并在次版本中增长（新策略会带来自己的事件接口，就像 0.4.0 中的 `HedgeEvent`）。在针对 `ResilienceEvent` 或某个策略接口的 `switch` 中保留 `default` 分支，或者监听你需要的记录类型（`listen(RetryEvent.GaveUp.class, …)`），新类型永远不会影响它们。针对旧版本编译的代码在接收到未知类型时会在监听器中抛出 `MatchException`；与任何监听器异常一样，它会交给未捕获异常处理器，绝不会破坏调用。

### 事件的开销 :id=cost

每个策略在实例创建时一次性解析其监听器，每种事件类型对应一个字段。没有人监听的事件类型的开销是**一次空值检查，而且只在非成功路径上**。成功的调用不发布任何内容，也不检查任何内容。有人监听时，只在那一刻才分配事件记录并读取时钟。

按照设计，**没有按调用的计数器**（没有"成功调用数"或延迟总计）：每次调用都更新的计数器会成为热路径上的共享写操作。如果你需要调用计数或延迟，请在你自己的代码中围绕调用测量，或使用 HTTP 客户端或框架的指标。

## 快照 :id=snapshots

`registry.snapshot()` 返回一个 `ResilienceSnapshot`，每个策略一个列表，各自按实例 id 排序。它在你调用时构建，绝不在调用路径上，并且可能分配内存并遍历熔断器窗口：请在抓取或健康检查时调用它，而不是每个请求都调用。

<!-- snippet: observability-snapshot -->
```java
ResilienceSnapshot snapshot = registry.snapshot(); // built when asked, never on the call path
for (CircuitBreakerSnapshot b : snapshot.circuitBreakers()) {
    lines.add(b.id() + " " + b.state() + " failureRate=" + b.failureRate()
            + " buffered=" + b.bufferedCalls() + " failed=" + b.failedCalls() + " slow=" + b.slowCalls());
}
for (BulkheadSnapshot b : snapshot.bulkheads()) {
    lines.add(b.id() + " in use " + b.concurrentCalls() + "/" + b.maxConcurrent()
            + ", waiting " + b.waitingCalls() + ", free " + b.availableSlots());
}
for (RateLimiterSnapshot r : snapshot.rateLimiters()) {
    lines.add(r.id() + " permits " + r.availablePermits() + "/" + r.permits());
}
```

| 记录 | 字段 |
|---|---|
| `CircuitBreakerSnapshot` | `id`、`state`、`failureRate` 和 `slowCallRate`（百分比；在窗口中有 `minimumCalls` 次调用之前为 `-1`，半开期间为 `halfOpenCalls`；慢调用检测关闭时 `slowCallRate` 也为 `-1`）、`bufferedCalls`、`failedCalls`、`slowCalls`（当前窗口中的调用：关闭时为滑动窗口，半开时为探测调用，打开、强制打开或禁用时没有） |
| `BulkheadSnapshot` | `id`、`concurrentCalls`、`waitingCalls`、`maxConcurrent`，以及方法 `availableSlots()` |
| `AdaptiveLimitSnapshot` | `id`、`algorithm`、`limit`、`concurrentCalls`、`minLimit`、`maxLimit`、`windows`（到目前为止已结束的窗口数）、`windowLatency`（上一个窗口的平均值）、`longTermLatency`（Gradient2 的长期平均值），以及方法 `availableSlots()` |
| `RateLimiterSnapshot` | `id`、`availablePermits`、`permits` |
| `RetrySnapshot` | `id`、`config`（解析后的 `RetryConfig`） |
| `HedgeSnapshot` | `id`、`config`（解析后的 `HedgeConfig`）、`budget`（预算窗口中的 `calls`、`hedges`、`allowedHedges`；没有预算时为 `null`） |
| `TimeoutSnapshot` | `id`、`config`（解析后的 `TimeoutConfig`） |

`snapshot.only(id)` 只保留具有该 id 的实例（没有时 `isEmpty()`）。`snapshot.toMap()` 把快照转换为映射、列表、字符串、数字、布尔值和 null，任何 JSON 编码器都可以原样写出：每种策略一个键，每个键对应一个列表，列表中每个实例一个映射，映射的键就是上面的字段，顺序相同，配置以其属性组成的嵌套映射表示。时长是 ISO-8601 字符串（`PT0.2S`），枚举和类是它们的名称，不是有限数字的比率为 `null`。Spring Boot 和 Micronaut 的端点提供的正是这些内容；次版本中可能增加键，但绝不会删除或重命名。

## 句柄 :id=handles

句柄实时读取一个实例，熔断器的句柄还可以改变其状态：

| 方法 | 返回 |
|---|---|
| `registry.circuitBreaker(id)` | `CircuitBreakerHandle`：`id()`、`state()`、`config()`、`snapshot()`、`forceOpen()`、`disable()`、`reset()` |
| `registry.circuitBreakers()` | 所有熔断器句柄，按 id 排序 |
| `registry.rateLimiter(id)` | `RateLimiterHandle`：`id()`、`config()`、`availablePermits()`、`snapshot()` |
| `registry.bulkhead(id)` | `BulkheadHandle`：`id()`、`config()`、`concurrentCalls()`、`waitingCalls()`、`snapshot()` |
| `registry.adaptiveLimit(id)` | `AdaptiveLimitHandle`：`id()`、`config()`、`limit()`、`concurrentCalls()`、`snapshot()` |

实例只有在其接口被包装（`Resilient.of`、`Resilient.create`）或其 `Pipeline` 被构建之后才存在；在此之前，或对于不存在的 id，查找会抛出 `IllegalArgumentException` [R4C-REG-009](../errors.md#r4c-reg-009)，并给出最接近的 id。重试和超时没有句柄；它们的配置在快照中。舱壁、限流器和自适应限流没有列表方法：请通过 `registry.snapshot()` 枚举它们。

## 指标 :id=metrics

[Micrometer 模块](micrometer.md)（`resilience4cata-micrometer`）把事件转换为计数器，把句柄转换为仪表（gauge）。它的页面包含设置、一次真实的 Prometheus 抓取、Grafana 查询以及关于基数的建议。[OpenTelemetry 模块](opentelemetry.md)（`resilience4cata-opentelemetry`）通过 OpenTelemetry Metrics API 报告相同的指标，名称和标签也相同，因此仪表盘对两者都适用。

### 指标名称 :id=metric-names

名称为小写并以点分隔，这是 Micrometer 推荐的做法（Prometheus 会将其显示为 `r4c_retry_retries_total` 等）；每个 meter 都有一个 `name` 标签（实例 id：实例 id 在你的代码中是固定的，因此该标签是有界的），最多再有一两个取值集合固定的标签。标签值为小写。该标签不叫 `instance`，因为 Prometheus 会为它抓取的每个序列添加自己的 `instance` 标签（目标的 host:port），并会把我们的标签重命名为 `exported_instance`。两个格式决定自 1.0 起固定：两个模块中的名称都遵循 Micrometer（以及 Resilience4j）的点分风格，而不是 OpenTelemetry 语义约定中段内使用 `_` 的写法；熔断器的比率是 0 到 100 的百分比，与 `failureRateThreshold` 一样。

| Meter | 类型 | 标签 | 来源 |
|---|---|---|---|
| `r4c.calls.rejected` | 计数器 | `name`、`kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `CircuitBreakerEvent.Rejected`、`RateLimiterEvent.Rejected`、`AdaptiveLimitEvent.Rejected`、`BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | 计数器 | `name`、`from`、`to`（小写状态） | `StateTransition` |
| `r4c.retry.retries` | 计数器 | `name` | `Retrying` |
| `r4c.retry.gave.up` | 计数器 | `name`、`reason`（小写的 `Reason`） | `GaveUp` |
| `r4c.hedge.hedged` | 计数器 | `name`、`reason` = `delay` \| `failure` | `Hedged` |
| `r4c.hedge.won` | 计数器 | `name` | `Won` |
| `r4c.hedge.skipped` | 计数器 | `name`、`reason` = `budget_exhausted` \| `rejected` \| `aborted` | `Skipped` |
| `r4c.hedge.failed` | 计数器 | `name` | `Failed` |
| `r4c.adaptivelimit.limit.changes` | 计数器 | `name` | `LimitChanged` |
| `r4c.timeout.timed.out` | 计数器 | `name` | `TimedOut` |
| `r4c.fallback.applied` | 计数器 | `name` | `Applied` |
| `r4c.circuitbreaker.state` | 仪表 | `name`、`state`：当前状态为 1，其他为 0 | `state()` |
| `r4c.circuitbreaker.failure.rate`、`r4c.circuitbreaker.slow.call.rate` | 仪表（百分比） | `name` | `snapshot()`；在窗口中有 `minimumCalls` 次调用之前为 NaN |
| `r4c.bulkhead.concurrent.calls`、`r4c.bulkhead.waiting.calls`、`r4c.bulkhead.max.concurrent` | 仪表 | `name` | `BulkheadHandle` |
| `r4c.ratelimiter.available.permits` | 仪表 | `name` | `RateLimiterHandle` |
| `r4c.adaptivelimit.limit`、`r4c.adaptivelimit.concurrent.calls` | 仪表 | `name` | `AdaptiveLimitHandle` |

### 其他指标库 :id=diy

到其他库的桥接所做的事情与 Micrometer 模块相同，只需核心 API 的三个部分：一个用于计数器的监听器，在采集时读取以生成仪表的句柄，以及 `registry.onInstanceCreated(…)`，用于获知在绑定注册表之后创建的实例（实例在包装服务或构建 pipeline 时创建，绝不在调用期间创建）：

<!-- snippet: observability-diy -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        // counters: from events, on the thread that caused them
        .listen(RetryEvent.Retrying.class, e -> retries.merge(e.id(), 1L, Long::sum))
        .build();
// gauges: one per instance, read at collection time; new instances are announced once each
registry.onInstanceCreated(() -> {
    for (CircuitBreakerHandle breaker : registry.circuitBreakers()) {
        gauges.putIfAbsent(breaker.id(), () -> breaker.state() == CircuitBreakerState.OPEN ? 1 : 0);
    }
});
```

`onInstanceCreated` 对每个新实例运行一次其动作，在创建该实例的线程上，并且在实例已能在 `snapshot()` 和句柄查找中看到之后运行；它返回一个 `Registration`，其 `close()` 会停止它。舱壁和限流器没有列表方法：请在 `registry.snapshot()` 中查找它们。按实例和标签值缓存你的计数器，这样事件就不会每次都注册一个 meter。

## 记录状态转换日志 :id=logging

一个通过 `System.Logger` 记录每次熔断器状态变化的监听器（`System.Logger` 通过其 JDK 桥接进入你的日志后端，例如 SLF4J 的 `slf4j-jdk-platform-logging`）：

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
                t.id(), t.from(), t.to());
    }
}
```

<!-- snippet: observability-logging-wiring -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(CircuitBreakerEvent.StateTransition.class, new BreakerTransitionLogger(logger))
        .build();
```

记录状态转换和放弃的日志，而不是每次 `Retrying` 或拒绝：在故障期间，它们可能以你的流量速率到达。请改为对它们计数。

## 健康检查 :id=health

一个基于快照构建的检查，用于就绪或诊断端点：

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
                .map(CircuitBreakerSnapshot::id)
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
> 不要把打开的熔断器接入**存活（liveness）**探针：重启服务并不能修复熔断器所保护的依赖，而且所有实例会同时重启。请把它报告为降级状态，或者只有在把流量引开确实有帮助时，才在就绪端点上报告。
