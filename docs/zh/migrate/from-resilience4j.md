# 从 Resilience4j 迁移

Resilience4j 的注册表、配置、装饰器和 Spring 注解如何对应到 resilience4cata，哪些没有对应物，哪些行为不同。

## 你应该迁移吗？ :id=when

resilience4cata 为纯 Java 21+ 提供八种策略（重试、对冲请求、熔断器、限流器、自适应限流、超时、舱壁隔离、降级），代码在编译时生成。如果你需要以下任何一项，**请继续使用 Resilience4j**，resilience4cata 目前还没有它们：

- Spring Boot 3、CDI 或 AspectJ 集成（resilience4cata 只与 Spring Boot 4.1+ 集成，见[下文](#spring-boot)）；
- RxJava 或 Kotlin 协程操作符（resilience4cata 支持 `CompletionStage`、`CompletableFuture`，以及借助 `resilience4cata-reactor` 支持 Reactor 的 `Mono` 和 `Flux`：见[下文](#reactor)）；
- 线程池舱壁隔离、缓存，或用于任意 future 的 time limiter；
- 在运行时修改策略的配置（resilience4cata 在设计上只解析一次配置）；
- 21 之前的 Java 版本。

如果每次调用的开销和内存分配对你很重要（参见[性能](../performance.md)），如果你希望在编译时发现错误配置，或者你更喜欢简单的注解和可以阅读的生成代码而不是 AOP 代理，**可以考虑迁移**。

## 心智模型 :id=mental-model

| | Resilience4j | resilience4cata |
|---|---|---|
| 策略所在位置 | 装饰器（`Decorators.ofSupplier(…)`、`Retry.decorateSupplier(…)`）或围绕 bean 的 Spring AOP 切面 | 一个在编译时生成的类：`<Interface>$Resilient` 包装你的实现，或 `<Class>$Resilient` 继承你的类 |
| 何时发现错误配置 | 启动时或第一次调用时 | 大部分在编译时（[错误代码](../errors.md)） |
| 策略的顺序 | 你组合装饰器的顺序；使用 Spring 时为固定的切面顺序（可配置） | 默认为 Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method；`@PolicyOrder` 可按方法、类型或组合注解修改 |
| 注册表 | 每种策略一个（`CircuitBreakerRegistry`、`RetryRegistry`……） | 所有策略共用一个 `ResilienceRegistry` |
| 实例 | 按名称查找 | 默认每个带注解的方法一个；`name` 相同的方法共享一个 |

## 概念对应 :id=concepts

| Resilience4j | resilience4cata |
|---|---|
| `CircuitBreakerRegistry.of(config)`、`RetryRegistry.of(…)`…… | `ResilienceRegistry.builder()…build()` |
| 注册为命名配置的 `CircuitBreakerConfig.custom()…build()` | `registryBuilder.circuitBreaker("name", b -> b…)`，通过 `@CircuitBreaker(config = "name")` 使用；或在 properties 中使用 `configs.<name>.circuitBreaker.*` |
| 默认配置（Spring 中的 `configs.default`） | `registryBuilder.circuitBreakerDefaults(b -> …)` 或 `defaults.circuitBreaker.*` |
| `registry.circuitBreaker("inventory")` + `decorateSupplier` | 接口方法上的 `@CircuitBreaker(name = "inventory")` + `Resilient.of(…)` |
| 用于无法加注解代码的 `Decorators.ofSupplier(…).withRetry(…).withCircuitBreaker(…)` | [`Pipeline.builder(registry, id).retry().circuitBreaker()…build()`](../guides/pipeline.md) |
| Spring `@CircuitBreaker(name = "x", fallbackMethod = "f")` | `@CircuitBreaker(name = "x")` + `@Fallback(method = "f")` |
| Spring `@Retry`、`@RateLimiter`、`@Bulkhead`、`@TimeLimiter` | `@Retry`、`@RateLimiter`、`@Bulkhead`、`@Timeout`（包 `dev.resilience4cata.annotation`） |
| `resilience4j.circuitbreaker.instances.x.*` | `instances.x.circuitBreaker.*`（[键](../reference/configuration.md)） |
| `baseConfig: other` | `configs.<name>.extends=other` |
| `circuitBreaker.getEventPublisher().onStateTransition(…)` | `registryBuilder.listen(CircuitBreakerEvent.StateTransition.class, …)`（[事件](../guides/observability.md#events)） |
| `circuitBreaker.getMetrics()` | `registry.circuitBreaker(id).snapshot()` |
| `transitionToForcedOpenState()`、`transitionToDisabledState()`、`reset()` | `CircuitBreakerHandle` 上的 `forceOpen()`、`disable()`、`reset()` |
| `resilience4j-micrometer`（`TaggedCircuitBreakerMetrics`……） | `resilience4cata-micrometer`（[Micrometer](../guides/micrometer.md)）；meter 名称不同（`r4c.*`），因此仪表盘和告警需要新的查询 |
| `RetryConfig.retryOnResult(…)`、`CircuitBreakerConfig.recordResult(…)` | `@Retry` / `@CircuitBreaker` 上的 [`Classifier`](../guides/classifiers.md) |
| `failAfterMaxAttempts(true)`（`MaxRetriesExceededException`） | `@Retry(onExhaustedResult = THROW)`（`ResultRejectedException`） |
| `IntervalFunction.ofExponentialBackoff(…)`、`ofExponentialRandomBackoff(…)` | `backoff`、`multiplier`、`maxDelay`、`jitter` |
| `TimeLimiter` + 用于阻塞代码的 executor | 在调用方线程上的 `@Timeout` |
| `resilience4j-hedge`：`Hedge.of(config).decorateCompletionStage(…)` | 返回 `CompletableFuture` 的方法上的 [`@Hedge`](../policies/hedge.md)，或配合 `executeAsync` 使用的 `Pipeline…hedge(…)`（[对应关系](#map-hedge)） |
| `mono.transformDeferred(RetryOperator.of(retry))` 以及 `Mono` 上的其他 Reactor 操作符 | 带注解的 `Mono` 方法，或 `mono.transform(MonoPipelineOperator.of(pipeline))`（[下文](#reactor)） |
| `Flux` 上的同样操作符 | 带注解的 `Flux` 方法，或 `flux.transform(FluxPipelineOperator.of(pipeline))`（[下文](#reactor)） |
| `ThreadPoolBulkhead`、`Cache`、RxJava/Kotlin 操作符 | 没有对应物 |

## 配置对应 :id=config-mapping

Resilience4j 的 builder 方法（Spring Boot 属性使用相同名称的 kebab-case 形式）以及起相同作用的 resilience4cata 属性。默认值在某些地方不同；**默认值**列分别是各库自己的默认值。

### 熔断器 :id=map-circuit-breaker

| Resilience4j | 默认值 | resilience4cata | 默认值 |
|---|---|---|---|
| `failureRateThreshold` | 50 | `failureRateThreshold` | 50 |
| `slowCallRateThreshold` | 100 | `slowCallRateThreshold` | 100 |
| `slowCallDurationThreshold` | 60 s（调用总是计时） | `slowCallDuration` | 0 = 关闭（调用不计时） |
| `slidingWindowType` | `COUNT_BASED` | `windowType` | `COUNT` |
| `slidingWindowSize` | 100 | `windowSize` | 100 |
| `minimumNumberOfCalls` | 100 | `minimumCalls` | 100 |
| `waitDurationInOpenState` | 60 s | `waitInOpen` | 60 s |
| `permittedNumberOfCallsInHalfOpenState` | 10 | `halfOpenCalls` | 10 |
| `maxWaitDurationInHalfOpenState` | 0 | `maxWaitInHalfOpen` | 0 |
| `recordExceptions` / `recordException(predicate)` | 所有异常 | `recordOn`（类）或 `classifier` | `Throwable` |
| `ignoreExceptions` | 无 | `ignoreOn` | 无 |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `false` | 没有对应物：在下一次调用时进入半开 | — |
| `waitIntervalFunctionInOpenState`、`transitionOnResult`、`initialState`、`slidingWindowSynchronizationStrategy` | | 没有对应物 | — |
| `writableStackTraceEnabled` | `true` | 不需要：拒绝总是预先分配且没有栈跟踪 | — |

### 重试 :id=map-retry

| Resilience4j | 默认值 | resilience4cata | 默认值 |
|---|---|---|---|
| `maxAttempts` | 3 | `maxAttempts` | 3 |
| `waitDuration` | 500 ms，固定 | `delay`（+ `backoff = FIXED, jitter = NONE` 以获得相同行为） | 200 ms，指数，完全抖动 |
| `intervalFunction(IntervalFunction.ofExponentialBackoff(…))` | | `backoff = EXPONENTIAL`、`multiplier`、`maxDelay` | ×2，最大 10 s |
| `IntervalFunction.ofRandomized(…)`、`ofExponentialRandomBackoff(…)` | | `jitter = FULL` 或 `EQUAL` | `FULL` |
| `retryExceptions` / `retryOnException(predicate)` | 所有异常 | `retryOn` 或 `classifier` | `Exception` |
| `ignoreExceptions` | 无 | `abortOn` | —（打开的熔断器无论如何都不会被重试） |
| `retryOnResult(predicate)` | | `classifier` | — |
| `failAfterMaxAttempts` | `false` | `onExhaustedResult = THROW` | `RETURN` |
| 没有对应物 | | `maxDuration`（所有尝试的时间预算） | 0 = 无限制 |

### 限流器、超时、舱壁隔离 :id=map-others

| Resilience4j | 默认值 | resilience4cata | 默认值 |
|---|---|---|---|
| `RateLimiterConfig.limitForPeriod` | 50 | `@RateLimiter(permits)` | 50 |
| `RateLimiterConfig.limitRefreshPeriod` | 500 ns | `@RateLimiter(period)` | 1 s |
| `RateLimiterConfig.timeoutDuration` | 5 s | `@RateLimiter(maxWait)` | 0 = 立即拒绝 |
| `RateLimiterConfig.drainPermissionsOnResult` | | 没有对应物 | — |
| `TimeLimiterConfig.timeoutDuration` | 1 s | `@Timeout(value)` | 1 s |
| `TimeLimiterConfig.cancelRunningFuture` | `true` | 总是：如果异步调用的 stage 是 `Future` 则会被取消 | — |
| `BulkheadConfig.maxConcurrentCalls` | 25 | `@Bulkhead(maxConcurrent)` | 25 |
| `BulkheadConfig.maxWaitDuration` | 0 | `@Bulkhead(maxWait)` | 0 |
| `BulkheadConfig.fairCallHandlingStrategyEnabled` | `true` | 没有对应物：等待用的信号量不是公平的 | — |

> [!WARNING]
> 有三个默认值容易让人踩坑：resilience4cata 的重试使用**从 200 ms 开始、带完全抖动的指数退避**，而不是固定的 500 ms；它的限流器**立即拒绝**，而不是最多等待 5 s；它的熔断器**不对调用计时**，除非你设置 `slowCallDuration`。迁移时请显式设置它们。

### 对冲请求 :id=map-hedge

Resilience4j 的 `resilience4j-hedge` 模块（2.x）与我们一样是纯编程式的，并且只支持异步（没有 Spring 注解），最多发起一次对冲请求。

| Resilience4j | resilience4cata | 说明 |
|---|---|---|
| `HedgeConfig.Builder.preconfiguredDuration(d)` | `@Hedge(delay = "…")` | 我们的默认值为 1 s；`"0"` 会立即发起每次尝试 |
| `averagePlusPercentageDuration(…)`、`averagePlusAmountDuration(…)` | 没有对应物 | 只有固定延迟；把它设置在依赖的 p95 附近 |
| 总是一次对冲请求 | `@Hedge(maxAttempts = 2)`（默认值） | 最多 10 次尝试 |
| `withMaxConcurrency(n)`（对冲请求线程池的核心大小） | 没有对应物 | 对冲请求在新的虚拟线程上启动；改由[对冲预算](../policies/hedge.md#the-hedge-budget)限制其数量 |
| `withContextPropagators(…)` | 没有对应物 | 第 2 次及以后的尝试运行时没有调用方的 `ThreadLocal` |
| 用于阻塞代码的 `submit(callable, executor)` | 没有对应物 | 同步方法是编译错误（[R4C-HDG-001](../errors.md#r4c-hdg-001)）：请返回 `CompletableFuture` |
| `HedgeOnPrimarySuccessEvent`、`…OnSecondarySuccessEvent`、`…Failure…` | `HedgeEvent.Hedged`、`Won`、`Skipped`、`Failed` | 在延迟之前就胜出的第一次尝试不发布任何事件 |

语义有所不同：在 Resilience4j 中，第一个**完成**的尝试胜出，因此任一尝试的快速失败都会使调用失败；在 resilience4cata 中，第一个**成功**的尝试胜出，`hedgeOn` 中的失败会立即发起下一次尝试，只有当每次尝试都失败时（或有一次以 `abortOn` 中的失败结束时）调用才会失败。落败的尝试被取消，对冲请求内部的熔断器、舱壁隔离和超时会释放它占用的资源。

## 指标名称 :id=metric-names

基于 `resilience4j-micrometer` 构建的仪表盘和告警需要新的查询：meter 名为 `r4c.*`，并且每一个都有 `name` 标签，即实例 id（[指标名称](../guides/observability.md#metric-names)）。Resilience4j 2.4.0 的名称以及最接近的 resilience4cata meter：

| Resilience4j | resilience4cata | 说明 |
|---|---|---|
| `resilience4j.circuitbreaker.state` | `r4c.circuitbreaker.state` | 两者都是当前状态为 1，其他为 0 |
| `resilience4j.circuitbreaker.failure.rate`, `.slow.call.rate` | `r4c.circuitbreaker.failure.rate`, `.slow.call.rate` | 两者都是百分比 |
| `resilience4j.circuitbreaker.not.permitted.calls`, `.calls{kind=not_permitted}` | `r4c.calls.rejected{kind=circuitbreaker}` | |
| `resilience4j.circuitbreaker.calls{kind=successful\|failed\|ignored}` | 无 | 没有按调用的计数器（[下文](#no-success-metrics)） |
| `resilience4j.circuitbreaker.buffered.calls`, `.slow.calls` | 无 | 快照中的 `bufferedCalls` 和 `slowCalls` |
| `resilience4j.retry.calls{kind=…}` | `r4c.retry.retries`, `r4c.retry.gave.up{reason}` | 只有重试和放弃，没有成功计数 |
| `resilience4j.timelimiter.calls{kind=timeout}` | `r4c.timeout.timed.out` | |
| `resilience4j.bulkhead.available.concurrent.calls` | `r4c.bulkhead.concurrent.calls` | 我们的统计正在使用的槽位，而不是空闲的 |
| `resilience4j.bulkhead.max.allowed.concurrent.calls` | `r4c.bulkhead.max.concurrent` | |
| `resilience4j.ratelimiter.available.permissions` | `r4c.ratelimiter.available.permits` | |
| `resilience4j.ratelimiter.waiting_threads` | 无 | |
| `resilience4j.bulkhead.thread.pool.size` 及其他线程池舱壁隔离的 meter | 无 | 没有线程池舱壁隔离 |

## 行为差异 :id=differences

### 顺序 :id=order

默认为 Fallback → Retry（或 Hedge）→ CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method，与注解的顺序无关。对于两者都有的策略，它与 Resilience4j 默认的 Spring 切面顺序一致。Resilience4j 通过 `<module>AspectOrder` 属性全局修改顺序，而 resilience4cata 在编译时用 [`@PolicyOrder`](../policies/README.md#changing-the-order) 按方法、类型或组合注解修改；降级始终在最外层。

### 打开的熔断器会停止重试 :id=open-breaker

无论 `abortOn` 怎么设置，重试都从不重试 `CallNotPermittedException`，因此一旦熔断器打开，重试会立即放弃，而不是继续往里重试。在 Resilience4j 中，你需要自己把该异常加入 `ignoreExceptions`。

### 满的舱壁隔离不会打开熔断器 :id=local-rejections

无论 `recordOn` 或 `ignoreOn` 怎么设置，熔断器都从不记录 `BulkheadFullException`、`RateLimitExceededException` 或 `AdaptiveLimitFullException`：它们是本应用自身的负载，而不是依赖的失败。在 Resilience4j 中，舱壁隔离和限流器同样位于熔断器内部，它们的 `BulkheadFullException` 和 `RequestNotPermitted` 会被算作失败，除非你把它们加入 `ignoreExceptions`，因此一次突发流量就可能在依赖正常时打开熔断器。迁移时去掉这些条目；不需要任何替代。

### 默认不重试 Error :id=errors

`retryOn` 默认为 `Exception`：`Error`（例如 `OutOfMemoryError`、`AssertionError`）不会被重试。熔断器把它记录为失败，与 Resilience4j 相同（`recordOn` 默认为 `Throwable`）。`@Fallback(applyOn)` 默认为 `Throwable`，因此降级确实会捕获 `Error`，除非你把它们加入 `skipOn`。

### 超时中断调用方线程 :id=timeouts

同步 `@Timeout` 不会把调用移到另一个线程：它在你调用的地方运行，时间到时由一个共享的 watchdog 中断它。忽略中断的代码会一直运行到完成，之后调用方才会收到 `CallTimeoutException`。超时从不提前，最多延迟约两个 watchdog tick（tick 为 1–50 ms）。参见[超时](../policies/timeout.md)。

### 熔断器在下一次调用时进入半开 :id=half-open

没有定时器把打开的熔断器转为半开：由 `waitInOpen` 之后的第一次调用完成这一转换。在此期间获取的快照仍显示 `OPEN`。

### 配置只解析一次 :id=no-reload

注解值、命名配置和外部配置在创建实例时合并，之后不再改变。没有 `changeConfig`，没有刷新事件，没有热重载。监听器同样在构建注册表时就固定了。

### 接口风格中的自调用 :id=self-invocation

使用 `Resilient.of` 时，从你的实现对其自身方法的调用会跳过策略，与 Spring 代理一样。带注解的类（子类风格）确实会拦截 `this.method()`。参见[带注解的类](../guides/annotated-classes.md)。

### 除非命名，否则实例按方法划分 :id=instances

Resilience4j 的实例由使用其名称的所有地方共享。在 resilience4cata 中，每个带注解的方法都有自己的实例，其 id 形如 `com.acme.StockClient#stock(java.lang.String)`；给方法相同的 `name` 即可共享一个实例（此时它们解析后的配置必须完全相同，[R4C-REG-008](../errors.md#r4c-reg-008)）。`@Timeout` 没有 `name`。

### 注解处理器是必需的 :id=processor

没有注解处理器就不会生成任何东西，`Resilient.of` 会以 [R4C-RT-003](../errors.md#r4c-rt-003) 失败。在 Spring Boot、Micronaut 和 Quarkus 中，类或接口带有注解但没有生成类的 bean 会使启动（或 Quarkus 构建）以 [R4C-SPR-006](../errors.md#r4c-spr-006)、[R4C-MN-007](../errors.md#r4c-mn-007) 或 [R4C-QK-008](../errors.md#r4c-qk-008) 失败，而不是在没有保护的情况下运行。没有反射或代理的回退方案。

### 没有成功或延迟指标 :id=no-success-metrics

Resilience4j 统计每一次调用（它的重试和熔断器的 `calls` meter，以及它的 time limiter 的）。resilience4cata 不保留按调用的计数器，因为那会在每次调用时产生一次共享写入；它统计出错的情况（拒绝、重试、放弃、超时、降级），并以 gauge 暴露状态。请在你已经测量请求的地方统计成功次数和延迟，例如使用 Micrometer 的 `@Timed` 或你的 HTTP 服务器的指标（[原因](../faq.md#success-metrics)）。

### 带注解的类与 `@Transactional` :id=transactions

当 Spring 的注解位于类上时，Resilience4j 的切面和 Spring 的事务代理按切面优先级排序。在 resilience4cata 中，带注解的类会变成一个生成的子类，而 Spring 的代理包在它外面，位于策略之外：一个事务会横跨每一次尝试。因此，同时被重试（或对冲）且带事务的 bean 会以 [`R4C-SPR-004`](../errors.md#r4c-spr-004)（Micronaut 为 `R4C-MN-005`，Quarkus 为 `R4C-QK-002`）终止启动。请把策略放到接口上，或者把事务移到另一个 bean 中（[Spring：带注解的类](../guides/spring.md#classes)）。

### 对冲请求不携带线程局部变量 :id=hedge-context

对冲的尝试在新的虚拟线程上启动，没有调用方的 `ThreadLocal` 或 MDC，与异步重试一样（[Hedge](../policies/hedge.md)）。Reactor 的 context 确实会到达 `Mono` 方法的每一次尝试。

## 同一个服务，两种写法 :id=example

一个库存查询，带有重试（3 次尝试，间隔 500 ms，仅针对 `IOException`）、以 `inventory` 为名共享的熔断器、2 s 超时和一个降级。

**Resilience4j**，使用装饰器：

<!-- not-compiled: Resilience4j 代码 -->
```java
CircuitBreaker breaker = CircuitBreaker.of("inventory", CircuitBreakerConfig.custom()
        .failureRateThreshold(50)
        .slidingWindowSize(20)
        .minimumNumberOfCalls(10)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .permittedNumberOfCallsInHalfOpenState(3)
        .build());
Retry retry = Retry.of("inventory", RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(500))
        .retryExceptions(IOException.class)
        .ignoreExceptions(CallNotPermittedException.class)
        .build());
TimeLimiter timeLimiter = TimeLimiter.of(Duration.ofSeconds(2));
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

Supplier<CompletionStage<Integer>> decorated = Decorators
        .ofCompletionStage(() -> CompletableFuture.supplyAsync(() -> client.stock(sku), scheduler)) // a blocking call moved to a thread
        .withTimeLimiter(timeLimiter, scheduler)
        .withCircuitBreaker(breaker)
        .withRetry(retry, scheduler)
        .withFallback(List.of(Exception.class), e -> -1)
        .decorate();
int units = decorated.get().toCompletableFuture().join();
```

**resilience4cata**：接口上的注解，

<!-- snippet: migrate-r4c-interface -->
```java
public interface StockClient {

    @Fallback(method = "unknownStock")
    @Retry(name = "inventory", maxAttempts = 3, delay = "500ms", backoff = Backoff.FIXED, jitter = Jitter.NONE,
            retryOn = IOException.class)
    @CircuitBreaker(name = "inventory", failureRateThreshold = 50, windowSize = 20, minimumCalls = 10,
            waitInOpen = "30s", halfOpenCalls = 3)
    @Timeout("2s")
    int stock(String sku) throws IOException;

    default int unknownStock(String sku, Exception failure) {
        return -1;
    }
}
```

以及调用点：

<!-- snippet: migrate-r4c-use -->
```java
ResilienceRegistry registry = ResilienceTesting.registry(clock).build(); // ResilienceRegistry.builder() in production
StockClient stock = Resilient.of(StockClient.class, sku -> {
    calls.incrementAndGet();
    throw new IOException("inventory unreachable");
}, registry);

int units = stock.stock("SKU-1"); // three attempts 500 ms apart, then the fallback
```

同步调用保持同步：不需要 executor，因为超时是中断调用方线程，而不是把调用移到另一个线程。

**外部配置。** Spring Boot 的 `application.yml` 键及其在 `.properties` 文件中的 resilience4cata 等价写法（`instances.inventory.*` 为该实例覆盖注解；`defaults.*` 只填充注解没有设置的属性）：

<!-- snippet: migrate-properties -->
```properties
# resilience4j.circuitbreaker.instances.inventory.sliding-window-size=50
instances.inventory.circuitBreaker.windowSize=50
# resilience4j.circuitbreaker.instances.inventory.wait-duration-in-open-state=10s
instances.inventory.circuitBreaker.waitInOpen=10s
# resilience4j.retry.instances.inventory.max-attempts=5
instances.inventory.retry.maxAttempts=5
# resilience4j.circuitbreaker.configs.default.failure-rate-threshold=40
defaults.circuitBreaker.failureRateThreshold=40
```

用 `ResilienceConfig.load()`（作为类路径上的 `resilience4cata.properties`）或 `ResilienceConfig.builder().classpath(…)` 加载它，然后调用 `config.applyTo(ResilienceRegistry.builder())`。参见[配置](../guides/configuration.md)。

## 从 resilience4j-spring-boot3 迁移 :id=spring-boot

`resilience4cata-spring` 在 Spring Boot 4.1 或更新版本上取代 `resilience4j-spring-boot3`（[Spring Boot 指南](../guides/spring.md)）。去掉 `spring-boot-starter-aop`：没有任何织入或代理；注解处理器生成受保护的类，集成负责让 Spring 实例化它们。

| resilience4j-spring-boot3 | resilience4cata-spring |
|---|---|
| `resilience4j.<module>.instances.<name>.<key>` | `resilience4cata.instances.<name>.<policy>.<attribute>`（`circuit-breaker`、`retry`、`rate-limiter`、`timeout`、`bulkhead`） |
| `resilience4j.<module>.configs.default.<key>` | `resilience4cata.defaults.<policy>.<attribute>` |
| `resilience4j.<module>.configs.<name>.<key>` | `resilience4cata.configs.<name>.<policy>.<attribute>`，由 `@Retry(config = "<name>")` 等使用 |
| `baseConfig: <name>` | `resilience4cata.configs.<config>.extends: <name>`；注解通过 `config = "<name>"` 选择其配置 |
| `<module>AspectOrder` 属性 | 方法、类型或组合注解上的 `@PolicyOrder`（[修改顺序](../policies/README.md#changing-the-order)） |
| `CircuitBreakerConfigCustomizer`、`RetryConfigCustomizer`…… bean | `ResilienceRegistryCustomizer` bean |
| `RegistryEventConsumer` bean、`getEventPublisher()` | `EventListener<…>` bean |
| `/actuator/circuitbreakers`、`/actuator/retries`、`/actuator/ratelimiters`…… | `/actuator/resilience4cata`（所有策略，只读） |
| `/actuator/circuitbreakerevents` 及其他事件端点 | 无：不保留事件历史；请使用监听器 bean 或指标 |
| 健康指示器，默认关闭（`registerHealthIndicator`） | `resilience4cata` 指示器，默认开启，熔断器打开时仍为 UP，除非列在 `resilience4cata.spring.health.down-when-open` 中 |
| 指标 `resilience4j.circuitbreaker.*`…… | 指标 `r4c.*`（[Micrometer](../guides/micrometer.md)），需要 `resilience4cata-micrometer` |
| 通过反射解析的 `fallbackMethod = "f"` | `@Fallback(method = "f")`，在编译时检查 |

`application.yml` 中的相同键：

```yaml
# resilience4j.circuitbreaker.instances.inventory.sliding-window-size: 50
# resilience4j.circuitbreaker.configs.default.failure-rate-threshold: 40
# resilience4j.retry.instances.inventory.max-attempts: 5
resilience4cata:
  defaults:
    circuit-breaker:
      failure-rate-threshold: 40
  instances:
    inventory:
      circuit-breaker:
        window-size: 50
      retry:
        max-attempts: 5
```

拼写错误会使启动失败并给出该键所在的文件和行号，而不是被忽略。

## 从 resilience4j-reactor 迁移 :id=reactor

用 Resilience4j 的操作符装饰的 `Mono`：

<!-- not-compiled: Resilience4j 代码 -->
```java
Mono<Price> price = webClient.get().uri("/prices/{sku}", sku).retrieve().bodyToMono(Price.class)
        .transformDeferred(TimeLimiterOperator.of(timeLimiter))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .transformDeferred(RetryOperator.of(retry));
```

借助 `resilience4cata-reactor`，它变成一个带注解的 `Mono` 方法（在 `Mono<Price> price(String sku)` 上使用 `@Retry`、`@CircuitBreaker`、`@Timeout`），或者对于无法加注解的代码，整个 pipeline 用一个操作符：`.transform(MonoPipelineOperator.of(pipeline))`。参见 [Project Reactor](../guides/reactor.md)。

| resilience4j-reactor | resilience4cata-reactor |
|---|---|
| 每个策略一个操作符，按 `transformDeferred` 的顺序嵌套 | 每个策略一个注解，按[默认顺序](#order)或 `@PolicyOrder` 嵌套；一个 pipeline 用一个 `MonoPipelineOperator` |
| 重试再次订阅同一个 `Mono` | 每次尝试都会再次调用 `Mono` 方法（得到一个新的 `Mono`）；`MonoPipelineOperator` 再次订阅同一个来源，`defer(...)` 会重新构建它 |
| `TimeLimiterOperator`：在 Reactor 的 parallel 调度器上的 `Mono.timeout`，每次订阅都会设置 | 在库的定时器上的 `@Timeout`，订阅时值已经存在则跳过 |
| 在 `Flux` 上，重试在失败后再次订阅，即使已经发出了元素（它们会再次到来） | 在 `Flux` 方法上，重试和降级只在第一个元素之前起作用；之后的失败会到达订阅者 |
| `Flux` 上的 `TimeLimiterOperator`：`flux.timeout`，为每个元素调度一个定时器 | `@Timeout` 由每个元素重新开始计时，无需每个元素一个定时器（一次时钟读取） |
| `Flux` 上的 `CircuitBreakerOperator` 为慢调用对整个流计时 | 到第一个元素的时间 |

## 逐步迁移 :id=steps

1. 添加依赖，包括放在处理器路径上的注解处理器（[安装](../start/installation.md)）；使用 Spring Boot 时，添加 `resilience4cata-spring`（[上文](#spring-boot)）。
2. 对每个被装饰的调用，把调用放到一个接口后面（或给类加注解），并按上面的表格转换配置。在默认值不同的地方显式设置 `backoff`、`jitter` 和 `maxWait`。
3. 用 resilience4cata 注解和 `Resilient.of`（带注解的类用 `Resilient.create`）替换 `Decorators`/Spring 注解。无法放到接口后面的调用通过 [`Pipeline`](../guides/pipeline.md) 执行。
4. 把事件消费者迁移到 `ResilienceRegistry.Builder.listen(…)`，把指标迁移到 [Micrometer 模块](../guides/micrometer.md)。
5. 用 [`ManualClock`](../guides/testing.md) 替换依赖时间的测试。
6. 如果愿意，可以用 `-Xlint:all -Werror` 构建：生成的代码编译时没有警告。
