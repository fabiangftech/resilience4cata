# Pipeline API

`Pipeline` 无需注解即可应用同样的策略，适用于你无法加注解的代码或在运行时才选择的策略。

## 何时使用 :id=when-to-use-it

| 在以下情况使用注解 | 在以下情况使用 `Pipeline` |
|---|---|
| 接口或类归你所有 | 代码是第三方类、lambda 或脚本 |
| 策略在编译时已知 | 策略在运行时选择 |
| 你希望在编译时检查每个属性和降级 | `build()` 运行时的检查就足够 |
| 每一纳秒都很重要 | 每次调用多几纳秒也无妨 |

## 第一个 pipeline :id=a-first-pipeline

从注册表构建一次 pipeline，然后复用它。它是不可变且线程安全的。

<!-- snippet: pipeline-minimal -->
```java
Pipeline<String> geocode = Pipeline.builder(registry, "geocoder", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(1)))
        .fallback(failure -> "unknown")
        .build();

String city = geocode.execute(() -> legacyGeocoder.city("10115"));   // "Berlin", after one retry
```

`execute` 让调用经过各个策略，并声明调用的受检异常（这里是 `IOException`），因此你处理的是调用本身抛出的异常。当没有任何东西能恢复时，它抛出调用的最后一次失败或一个拒绝：`CallNotPermittedException`、`RateLimitExceededException`、`CallTimeoutException` 或 `BulkheadFullException`。

## 每一个步骤 :id=every-step

<!-- snippet: pipeline-full -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retry("external", r -> r.maxAttempts(4))
        .rateLimiter("external", rl -> rl.permits(100))
        .build();

Pipeline<Receipt> charge = Pipeline.builder(registry, "payments", Receipt.class)
        .retry("external")                                   // named config
        .circuitBreaker(cb -> cb.failureRateThreshold(25))   // defaults + overrides
        .rateLimiter("external", rl -> rl.permits(50))       // named config + overrides
        .timeout(t -> t.timeout(Duration.ofSeconds(2)))
        .bulkhead()                                          // defaults
        .fallbackHandler(context -> Receipt.pending(context.attempts()))
        .build();

Receipt receipt = charge.execute(() -> client.charge(order));            // throws what charge throws
CompletableFuture<Receipt> later = charge.executeAsync(() -> client.chargeAsync(order));
charge.run(() -> client.cancel(order));                                  // a call with no result
```

- **策略：** `retry`、`hedge`、`circuitBreaker`、`rateLimiter`、`adaptiveLimit`、`timeout` 和 `bulkhead`，每个最多一次（[`R4C-RT-012`](../errors.md#r4c-rt-012)）。每个都可以不带参数（使用默认值）、带一个命名配置、带一个该策略配置 builder 的 `Consumer`（在默认值之上覆盖），或两者都带（在命名配置之上覆盖）。builder 的属性与注解相同；例如超时的时长是 `t.timeout(Duration)`。
- **顺序：** 策略的嵌套方式与注解相同，`Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → call`，与你调用 builder 方法的顺序无关。每次重试尝试都会经过熔断器，并获得自己的超时。
- **对冲请求：** `hedge(h -> h.delay(Duration.ofMillis(50)))` 在第一次尝试较慢时再次发起调用，并保留第一个成功结果（[Hedge](../policies/hedge.md)）；仅用于幂等调用，且只能与 `executeAsync` 一起使用：在带有对冲请求的 pipeline 上调用 `execute` 和 `run` 会抛出 [`R4C-RT-018`](../errors.md#r4c-rt-018)。一个 pipeline 不能同时拥有重试和对冲请求（[`R4C-RT-019`](../errors.md#r4c-rt-019)）。
- **降级：** `fallback(failure -> value)` 或 `fallbackHandler(context -> value)`；处理器的 `FallbackContext` 提供失败和 `attempts()`（它没有参数：`argumentCount()` 为 0）。`fallbackApplyOn(List.of(...))` 和 `fallbackSkipOn(List.of(...))` 选择它处理的失败；没有降级时使用它们会报错（[`R4C-RT-013`](../errors.md#r4c-rt-013)）。
- **异步降级：** `fallbackAsync(...)` 和 `fallbackAsyncHandler(...)` 返回一个 stage，用于 `executeAsync`。两种都有时，每种模式使用各自的降级；只有异步降级时，`execute` 和 `run` 会等待它的 stage。
- **分类器：** `classifier(...)` 同时用于重试和熔断器，或分别使用 `retryClassifier(...)` 和 `circuitBreakerClassifier(...)`，另有 `onExhaustedResult(Pipeline.ExhaustedResult.THROW)`；参见[分类器](classifiers.md)。

三种调用方式：

| 方法 | 用于 |
|---|---|
| `T execute(PipelineCall<? extends T, X>) throws X` | 返回值的调用 |
| `void run(PipelineRunnable<X>) throws X` | 没有结果的调用（分类器看到 `null`；降级的结果被丢弃） |
| `CompletableFuture<T> executeAsync(PipelineCall<? extends CompletionStage<T>, ?>)` | 返回 stage 的调用：每个策略的非阻塞形式，与[异步方法](async.md)相同 |

`build()` 会检查无法工作的组合：没有重试或熔断器的分类器（[`R4C-RT-014`](../errors.md#r4c-rt-014)）、没有重试的重试分类器（[`R4C-RT-015`](../errors.md#r4c-rt-015)）、没有重试分类器的 `onExhaustedResult`（[`R4C-RT-016`](../errors.md#r4c-rt-016)）、没有熔断器的熔断器分类器（[`R4C-RT-017`](../errors.md#r4c-rt-017)）、同时使用重试和对冲请求（[`R4C-RT-019`](../errors.md#r4c-rt-019)）。空白的 id 会报 [`R4C-RT-011`](../errors.md#r4c-rt-011)。

## id 就是实例 id :id=the-id-is-the-instance-id

传给 `Pipeline.builder` 的 id 为 pipeline 的策略实例命名，就像注解的 `name` 属性那样：

<!-- snippet: pipeline-handle -->
```java
Pipeline<String> search = Pipeline.builder(registry, "search", String.class)
        .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2))
        .build();

CircuitBreakerHandle breaker = registry.circuitBreaker("search");   // the pipeline's id is the instance id
breaker.forceOpen();                                                // every call is now rejected
```

因此 `registry.circuitBreaker(id)`、`rateLimiter(id)` 和 `bulkhead(id)` 能找到它们，事件以 `id()` 携带该 id，外部配置可以用 `instances.<id>.*` 重新调整它们。使用相同 id 构建的 pipeline 共享它们的实例，`name` 为该 id 的注解也一样；它们必须全部解析为相同的配置，否则 `build()` 会以 [`R4C-REG-008`](../errors.md#r4c-reg-008) 失败。

如果降级函数抛出调用未声明的受检异常，它会被包装在 `UndeclaredThrowableException` 中到达调用方。

## 开销 :id=what-it-costs

同步成功路径除了你自己的 lambda 之外不做任何内存分配。不过，一个 `Pipeline` 类服务于所有调用点，因此在有许多 pipeline 的应用中，它对你的 lambda 的那一处调用会变成 megamorphic，从而变慢；生成的代码每个方法有一个调用点，没有这项开销。

> [!MEASURED]
> 单线程下重试加熔断器：`Pipeline` 为 2.9 ns，注解为 2.3 ns，Resilience4j 2.4.0 为 175 ns 和 288 B。在同一个 JVM 中运行过其他五个 pipeline 之后，该 pipeline 测得 7.9 ns（AWS Graviton3 `c7g.xlarge`）。[基准测试](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)

在可以加注解的地方，注解仍是最快的选择。
