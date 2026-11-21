# 01 — 现状：Resilience4j 与已有方案

> **历史文档。** 本研究早于实现，按原样保留；关于库目前的功能，请参阅[功能](../policies/README.md)、[架构](../architecture.md)和[基准测试](../benchmarks.md)。

> 第 1 阶段研究。没有生产代码。最后更新：2026-10-23。
> 范围：已有哪些方案，基线（Resilience4j）内部如何工作，其开销来自哪里，以及一个新的、注解驱动的、纯 Java、Java 21+ 的库能在哪些方面做得更好。
> 不在范围内（由同系列其他文档覆盖）：注解处理机制的选择、并发原语的详细设计、API/配置的对外形态、模块布局。

## 证据图例 :id=evidence-legend

每条论断都带有标签：

| 标签 | 含义 |
|---|---|
| **[SRC]** | 通过阅读源代码验证。Resilience4j 为 `master` 提交 `b7c0069`（2026-10-07，2.4.0 之后）；Failsafe 为 `master`（最后提交 2025-12-28）；Sentinel `master` 通过 GitHub API 查看。 |
| **[BENCH]** | 根据已发布的基准测试或仓库中的结果文件验证。 |
| **[DOC]** | 根据官方文档、发布说明或项目 README 验证。 |
| **[ISSUE]** | 根据 GitHub issue/PR 验证（用户报告，未必经维护者确认）。 |
| **[INF]** | 由我们推断（基于代码/JVM 行为推理，未经测量）。必须在后续阶段用 JMH 验证。 |

---

## 1. 深入 Resilience4j :id=_1-resilience4j-in-depth

### 1.1 现状与形态 :id=_11-status-and-shape

- 最新版本 **2.4.0**（2026-03-14）；2.3.0（2025-01-03）；约 10.8k 星；约 348 个未关闭 issue。[DOC]（[releases](https://github.com/resilience4j/resilience4j/releases)）
- 2.4.0 将编译基线从 JDK 17 提升到 **JDK 21**，“for support virtual thread”（PR #2331）；`build.gradle` 设置了 `sourceCompatibility = JavaVersion.VERSION_21`。[DOC][SRC] 因此“Java 21”本身已不再是差异点。
- Vavr 依赖在 2.0.0 中被移除。[DOC]（[RELEASENOTES.adoc](https://github.com/resilience4j/resilience4j/blob/master/RELEASENOTES.adoc)）
- Gradle 多项目，约 38 个模块：`circuitbreaker`、`retry`、`ratelimiter`、`bulkhead`、`timelimiter`、`cache`（JCache）、`hedge`（较新）、`circularbuffer`、`consumer`、`core`、`annotations`、`micrometer`、`metrics`、`feign`、`kotlin`、`reactor`、`rxjava2/3`、`vavr`、`spring6`、`spring-boot3`、`spring-boot4`、`micronaut`、`commons-configuration`、`framework-common`、`all`、`bom`、`test`。[SRC]

### 1.2 各模块功能 :id=_12-features-per-module

| 模块 | 功能 | 实现说明 |
|---|---|---|
| **CircuitBreaker** | CLOSED / OPEN / HALF_OPEN，外加 DISABLED、FORCED_OPEN、METRICS_ONLY。基于计数或时间的滑动窗口；失败率**和**慢调用率阈值；`minimumNumberOfCalls`；记录/忽略异常的谓词；结果谓词。 | 每个状态一个状态对象，放在 `AtomicReference<CircuitBreakerState>` 之后；通过 CAS 转换。`ReentrantLock` 只保护 OPEN→HALF_OPEN。[SRC] |
| **Retry** | 最大尝试次数、`IntervalFunction`（固定、指数、随机、自定义）、按异常重试/按结果重试的谓词，同步 + 异步。 | 每次调用新建一个 `ContextImpl`，持有一个 `AtomicInteger` 和两个 `AtomicReference`（即使第一次尝试就成功，每次调用也有 4 个对象）；通过静态的 `Thread::sleep` 函数休眠。[SRC] |
| **RateLimiter** | `AtomicRateLimiter`（默认，基于周期）和 `SemaphoreBasedRateLimiter`（由调度器刷新）。 | 见 §1.4。 |
| **Bulkhead** | `SemaphoreBulkhead`（`java.util.concurrent.Semaphore`，可选公平性，`maxWaitDuration`）和 `FixedThreadPoolBulkhead`（`ThreadPoolExecutor` + `ArrayBlockingQueue`/`SynchronousQueue`，`CompletableFuture.supplyAsync`，`ContextPropagator`）。 | [SRC] |
| **TimeLimiter** | 为 `Future`（`future.get(timeout)`）和 `CompletionStage`（在 `ScheduledExecutorService` 上调度一个 `TimeoutException`）提供超时。 | 无法中断普通的同步调用：它需要一个 future，所以同步代码必须转移到另一个线程执行。[SRC] |
| **Cache** | 包装 JCache（JSR-107）。 | 很少使用；薄包装。[SRC][INF] |
| **Hedge** | 对冲请求（issue #1454）。 | 较新的模块。[SRC] |
| **Fallback** | 没有核心模块。可通过 `Decorators.withFallback(...)` 使用；使用注解时，是一个 `fallbackMethod = "name"` 字符串，由 Spring/Micronaut 集成通过反射解析。 | 见 §1.3。[SRC] |
| **Registries** | `CircuitBreakerRegistry` 等，带命名配置（"default" 加命名配置）；`InMemoryRegistryStore` 基于 `ConcurrentHashMap<String, CompletableFuture<E>>`，刻意避免 `computeIfAbsent`，以防止虚拟线程固定（pinning）。 | [SRC] |
| **Events** | `EventProcessor`，消费者放在 `CopyOnWriteArraySet` 中，另有一个按事件类型的 map。有 `hasConsumers()` 守卫。 | 见 §1.5。[SRC] |

### 1.3 注解如何工作，以及纯 Java 用户能得到什么 :id=_13-how-the-annotations-work-and-what-plain-java-users-get

`resilience4j-annotations` 模块**只包含注解声明**：`@CircuitBreaker(name, configuration, fallbackMethod)`、`@Retry`、`@RateLimiter`、`@Bulkhead`、`@TimeLimiter`、`@Timer`。它没有处理器。[SRC]（[CircuitBreaker.java](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-annotations/src/main/java/io/github/resilience4j/circuitbreaker/annotation/CircuitBreaker.java)）

行为由以下部分实现：

- **Spring**（`resilience4j-spring6`、`spring-boot3/4`）：由 Spring AOP 代理运行的、带 AspectJ 注解的 `@Around` 切面（`CircuitBreakerAspect`、`RetryAspect`……）。[SRC]
- **Micronaut**（`resilience4j-micronaut`）：Micronaut 编译期 AOP 的 `MethodInterceptor`。[SRC]

**纯 Java 得不到任何声明式能力。** 没有 Spring 或 Micronaut 时，用户只能使用函数式装饰器 API（`CircuitBreaker.decorateSupplier(cb, supplier)`、`Decorators.ofSupplier(..).withRetry(..).withCircuitBreaker(..)`），或者自己配置 AspectJ 织入（Spring 切面与 Spring bean 绑定：`SpelResolver`、`BeanFactory`、`FallbackExecutor`）。[SRC][INF] 对我们的目标用户来说，这是最大的易用性缺口。

**Spring 切面中的每次调用工作。** `CircuitBreakerAspect.circuitBreakerAroundAdvice` 在**每一次**调用时都做以下事情 [SRC]（[aspect](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/circuitbreaker/configure/CircuitBreakerAspect.java)、[DefaultSpelResolver](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/spelresolver/DefaultSpelResolver.java)、[FallbackExecutor](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackExecutor.java)、[FallbackMethod](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackMethod.java)）：

1. `MethodSignature.getMethod()`，然后通过字符串拼接构造 `methodName`（`declaringClass.getName() + "#" + method.getName()`）。
2. `spelResolver.resolve(method, args, annotation.name())`：对名称最多做四次 `Pattern.matcher(...).matches()` 正则检查，即使名称是字面量也是如此。如果它是表达式，就新建一个 `MethodBasedEvaluationContext`，并且**每次调用都解析一次 SpEL 表达式**。
3. 注册表查找 `getOrCreateCircuitBreaker(methodName, backend, configKey)`。
4. 分配一个捕获连接点（join point）的 `CheckedSupplier` lambda。
5. `FallbackExecutor.execute` **再次**通过 SpEL 解析降级方法名，然后调用 `FallbackMethod.create(...)`，它分配一个 `MethodMeta`，克隆 `getParameterTypes()`，在一个静态缓存中做一次 `ConcurrentHashMap.computeIfAbsent` 查找，并分配一个新的 `FallbackMethod`。之后通过反射 `Method.invoke` 调用降级方法。
6. 检查返回类型（`CompletionStage`、Reactor/RxJava 扩展列表）以选择处理器。

有用户报告该切面很慢（issue [#1377](https://github.com/resilience4j/resilience4j/issues/1377)，“CircuitBreaker advice taking 2s”，很可能是配置/慢调用造成的假象，未解决）。[ISSUE] 另外还有一个支持**元注解**/组合注解的未关闭请求（[#2405](https://github.com/resilience4j/resilience4j/issues/2405)，2026-03）。[ISSUE]

**结论：** 步骤 1–6 中除了真正的许可获取/结果记录之外，其余内容对每个方法都是不变的，可以在构建时或首次调用时一次性解析。[INF]

### 1.4 核心内部实现 :id=_14-core-internals

**熔断器状态机**（[CircuitBreakerStateMachine.java](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java)）[SRC]
- `decorateSupplier` 调用 `acquirePermission()`，读取开始时间戳，运行 supplier，然后调用 `onResult(duration, unit, result)` 或 `onError(...)`。不处理 `java.lang.Error`。
- 时间戳来自 `Function<Clock, Long> currentTimestampFunction`，默认为 `clock -> System.nanoTime()`。返回**装箱的 `Long`** 意味着每个时间戳可能有一次内存分配，每次调用两次，除非逃逸分析把它消除。签名为 [SRC]，内存分配为 [INF]。
- OPEN 状态的过期判断使用 `clock.instant().isAfter(...)`，每个被拒绝的调用都会分配一个 `Instant`。[SRC]/[INF]
- 记录路径：`ClosedState.onSuccess` → `CircuitBreakerMetrics.onSuccess` → `Metrics.record(...)` 每次调用都返回一个**新的 `SnapshotImpl`**。[SRC]

**滑动窗口**（`resilience4j-core/.../core/metrics`）[SRC]
- `FixedSizeSlidingWindowMetrics`（基于计数）和 `SlidingTimeWindowMetrics`（基于时间）：一个由 `Measurement` 桶组成的环加一个 `TotalAggregation`，所有修改都在**一个 `ReentrantLock`** 之下（2.3.0 之前是 `synchronized`，为避免虚拟线程固定而更改，#2232）。每次 `record` 都分配一个 `SnapshotImpl`。**这仍然是默认设置**（`DEFAULT_SLIDING_WINDOW_SYNCHRONIZATION_STRATEGY = SYNCHRONIZED`）。
- 自 2.3.0 起有一个可选的 `SlidingWindowSynchronizationStrategy.LOCK_FREE`（[PR #2241](https://github.com/resilience4j/resilience4j/pull/2241)，经 Lincheck 验证），起因是一位用户在负载下看到 `metrics.record` 阻塞（[#2151](https://github.com/resilience4j/resilience4j/issues/2151)）。[ISSUE] 基于计数的无锁版本是一个通过 CAS 链接的节点链表。**每次记录都会复制尾部的 `PackedAggregation`、分配一个新的 `Node`，并分配一个 `SnapshotImpl`**，所以它是无锁的，但不是无分配的，而且在竞争下会通过 `CASBackoffUtil` 重试。[SRC]
- 阅读时发现的缺陷：LOCK_FREE 基于时间的窗口是用 `io.github.resilience4j.core.Clock.SYSTEM` 构建的，**忽略了用户配置的 `Clock`**（SYNCHRONIZED 路径传入了 `clock`）（`CircuitBreakerMetrics.buildTimeBasedMetrics`）。[SRC] 这使得使用假时钟的测试在两种策略下行为不同。

**AtomicRateLimiter**（[source](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/AtomicRateLimiter.java)）[SRC]
- 不可变的 `State(config, activeCycle, activePermissions, nanosToWait)` 放在一个 `AtomicReference` 中。时间被划分为长度为 `limitRefreshPeriod` 的周期。许可在访问时惰性补充（没有后台线程），`activePermissions` 可以为负，以“预留”未来的许可。
- `updateStateWithBackOff`：CAS 循环。**每次尝试都分配一个新的 `State`**（失败的尝试即成为垃圾）；CAS 失败时以 `parkNanos(1)` 退避。在激烈竞争下，这是一种单一热点字的设计：所有调用者都对同一个引用做 CAS。[SRC]；竞争下的行为为 [INF]。
- 等待通过调用线程上的 `parkNanos` 循环完成。

**Bulkhead**：信号量变体使用 `java.util.concurrent.Semaphore`（AQS）。对低到中等竞争来说没问题；AQS 会让等待者排队。[SRC]/[INF]

**Retry**：上面描述的每次调用的 `ContextImpl`（4 次内存分配）。每个实例的指标是 `LongAdder`（这很好）。[SRC]

### 1.5 事件发布 :id=_15-event-publishing

- `EventProcessor.hasConsumers()` 检查两个集合（`CopyOnWriteArraySet` 加一个 `ConcurrentHashMap`）。[SRC]
- `publishSuccessEvent` / `publishCircuitErrorEvent` 在分配事件**之前**检查 `hasConsumers()`。`publishCallNotPermittedEvent`、`publishCircuitIgnoredErrorEvent`、`publishStateTransitionEvent`、`publishResetEvent` 以及失败率超限事件则**先分配事件**（每个事件的构造函数都会调用 `ZonedDateTime.now(...)`）（这发生在拒绝路径上，而熔断器处于 OPEN 时拒绝路径就是热路径）。[SRC] Issue [#1391](https://github.com/resilience4j/resilience4j/issues/1391)（成功路径上的事件分配，附有分析器截图）自 2021 年起仍未关闭。[ISSUE]
- **在 Spring Boot 中，事件始终开启：** `CircuitBreakerConfiguration.registerEventConsumer` 会为 actuator 端点给**每一个**熔断器挂上一个 `CircularEventConsumer`（默认缓冲区 **100**）。因此在最常见的部署中，每次调用都会分配一个事件对象（`CircuitBreakerOnSuccessEvent`，带一个 `ZonedDateTime`、一个 `Duration` 和一个名称），外加一次环形缓冲区插入。[SRC]；开销大小为 [INF]。

### 1.6 已发布的数字 :id=_16-published-numbers

- 仓库中附带 `resilience4j-circuitbreaker/src/jmh/resources/resultsWithGCProfiling.txt`（较早的运行，Parallel GC 时代）：[BENCH]

  | 基准测试 | ops/µs | B/op |
  |---|---|---|
  | `directSupplier`（无 CB） | 12.26 | ~0 |
  | `protectedSupplier`（CB） | 4.12 | **272** |
  | `protectedSupplierWithOneConsumer` | 4.02 | 284 |

  因此历史上一个裸熔断器在单线程下的开销约为 0.16 µs 和**每次调用 272 字节**。当前数字未发布；存在 CB、bulkhead、rate limiter 和指标的 JMH 源码（包括 `VirtualThreadMetricsBenchmark`）。[SRC]
- Issue [#117](https://github.com/resilience4j/resilience4j/issues/117)（2017）：一位维护者做了一个**零分配 CircuitBreaker** 的原型，由于需要修改 API 而未被采纳。[ISSUE] 这证明目标是可实现的，而阻碍它的是 API 的形态（事件对象、`Snapshot` 返回值）。
- Issue [#1513](https://github.com/resilience4j/resilience4j/issues/1513)（学士论文，R4j 1.6.1，Java 11）：在该用户的 JMH 运行中，CB 开销约为 500 ns/op。[ISSUE]（取决于硬件）。
- Issue [#2023](https://github.com/resilience4j/resilience4j/issues/2023)：信号量与线程池 bulkhead 在负载下行为出乎意料。[ISSUE]
- 学术界：一篇 SBRC 论文比较了 Polly 和 Resilience4j 中的 Retry/CB，发现对执行时间的影响为“slight to moderate”（[论文](https://sol.sbc.org.br/index.php/sbrc/article/download/21194/21019)）。[DOC]（仅摘要）
- 我们没有找到任何**公开的 Resilience4j、Failsafe 与 SmallRye 的 JMH 正面对比**。[DOC，否定性结果] 我们应该发布一个：它既是验证工具，也是宣传资产。

### 1.7 Resilience4j 的开销来自哪里（汇总） :id=_17-where-resilience4js-overhead-comes-from-summary

| # | 来源 | 证据 |
|---|---|---|
| 1 | Spring 切面中每次调用的 SpEL/正则名称解析、字符串拼接、降级方法查找、lambda 捕获 | [SRC] |
| 2 | 反射调用降级方法（`Method.invoke`） | [SRC] |
| 3 | 只要存在任意消费者（在 Spring Boot 下总是如此），每次调用都分配事件对象；某些事件类型即使没有消费者也会分配 | [SRC] |
| 4 | 每次 `record` 都分配 `SnapshotImpl`（两种策略都是）；无锁窗口还会分配 `Node` + 聚合副本 | [SRC] |
| 5 | 默认滑动窗口在一个 `ReentrantLock` 下串行化 → 线程数高时产生竞争（#2151） | [SRC][ISSUE] |
| 6 | 通过 `Function<Clock, Long>` 得到装箱的 `Long` 时间戳；OPEN 状态中的 `Instant` | [SRC] / [INF] |
| 7 | Retry 即使第一次尝试就成功，每次调用也分配 4 个对象 | [SRC] |
| 8 | 限流器：单个 `AtomicReference<State>`，每次 CAS 尝试新建一个 `State` | [SRC] |
| 9 | 装饰器叠加（`Decorators`）时出现的超多态 `Supplier` 链，限制了内联 | [INF] |
| 10 | 配置是带有 `Predicate`/`Function` 字段的大对象，每次调用都要检查（记录/忽略异常的谓词会遍历类层次） | [SRC] 部分 / [INF] |

---

## 2. 竞争者与已有方案 :id=_2-competitors-and-prior-art

### 2.1 Failsafe（`dev.failsafe`） :id=_21-failsafe-devfailsafe

- **现状：** 3.3.2 是最新的标签；最后的提交在 2025 年 12 月（仅 CI/测试），之前是 2024 年 6 月。**实际上处于维护状态。**[DOC]（[repo](https://github.com/failsafe-lib/failsafe)）
- **功能：** RetryPolicy、CircuitBreaker（基于计数和时间，按比例设定阈值）、RateLimiter（平滑和突发）、Bulkhead、Timeout、Fallback；策略组合 `Failsafe.with(fallback, retry, cb, timeout)`；同步、异步（`CompletableFuture`），以及面向回调 API 的 `AsyncExecution` 风格；用于可取消执行的 `Call`；每个策略的事件监听器。[DOC][SRC]
- **注解：** 无。[DOC]
- **性能：** 熔断状态记录是 **`synchronized`** 的（`CircuitState.recordSuccess/recordFailure`、`ClosedState.checkThreshold`、`HalfOpenState`），`SyncExecutionImpl.preExecute/postExecute` 也是。每次调用新建一个 `SyncExecutionImpl`，每次尝试新建一个 `ExecutionResult`。[SRC] 在 Java 21–23 上，`synchronized` 会固定虚拟线程（[JEP 491](https://openjdk.org/jeps/491) 直到 JDK 24 才修复）。[DOC]
- **可借鉴：** 清晰的组合模型（策略是纯配置；执行器按组合创建；最外层策略列在最前面）；`Call`/取消；一致的监听器模型；零依赖。
- **应避免：** 粗粒度的 `synchronized`；每次调用的执行对象；没有声明式层。

### 2.2 SmallRye Fault Tolerance / MicroProfile Fault Tolerance（关键参考） :id=_22-smallrye-fault-tolerance--microprofile-fault-tolerance-key-reference

- **现状：** MicroProfile FT 规范 **4.1**（4.1.2，2025 年 3 月）。SmallRye FT **6.9.x / 6.10.x**（2026 年 9 月），积极开发中；它是 Quarkus 和 WildFly 中的实现。[DOC]（[spec](https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html)、[SmallRye](https://github.com/smallrye/smallrye-fault-tolerance)）
- **规范注解：** `@Retry(maxRetries, delay, maxDuration, jitter, retryOn, abortOn)`、`@Timeout`、`@Fallback(value=Handler.class | fallbackMethod, applyOn, skipOn)`、`@CircuitBreaker(requestVolumeThreshold, failureRatio, successThreshold, delay, failOn, skipOn)`、`@Bulkhead(value, waitingTaskQueue)`、`@Asynchronous`。[DOC]
- **外部配置（借鉴这一点）：** 每个注解参数都可以通过 MicroProfile Config 覆盖，最具体的键优先：`<class>/<method>/<Annotation>/<param>` > `<class>/<Annotation>/<param>` > `<Annotation>/<param>`。`enabled` 同理，另有一个全局的 `MP_Fault_Tolerance_NonFallback_Enabled`。[DOC] 这正是我们目标中描述的“默认值 + 按方法覆盖 + 外部配置”模型，并且已在生产中得到验证。
- **值得借鉴的语义：** 定义明确的交互约定（Fallback 在最外层；每次重试尝试都经过 CB；超时在每次尝试时重新开始；CB 在 bulkhead 之前检查）；熔断器状态对每个（bean 类，方法）是单例。[DOC]
- **SmallRye 扩展：** `@RateLimit`、`@ExponentialBackoff`、`@FibonacciBackoff`、`@CustomBackoff`、`@RetryWhen`、`@BeforeRetry`、Kotlin/Mutiny/RxJava 异步类型。**`@ApplyGuard("name")`**（6.7.0+，之前为 `@ApplyFaultTolerance`）将一个命名的、共享的、以编程方式构建的 `Guard` 应用于多个方法，可通过 `smallrye.faulttolerance."name".retry.max-retries` 配置（6.8.0+）。[DOC]（[reusable](https://smallrye.io/docs/smallrye-fault-tolerance/6.8.0/reference/reusable.html)）
- **纯 Java：** `smallrye-fault-tolerance-standalone` **只支持编程式 API**（`Guard`、`TypedGuard`）；注解需要 CDI 拦截器（ArC/Weld）。[DOC]（[programmatic API](https://smallrye.io/docs/smallrye-fault-tolerance/6.9.3/reference/programmatic-api.html)）因此和 Resilience4j 一样，没有独立的声明式路径。
- **性能：** Quarkus 在构建时（ArC）校验注解并预先计算每个方法的策略链。[INF，来自 Quarkus 的架构；此处未做基准测试]
- **应避免：** 以 `Future` 作为异步类型；只能用 `Class<?>[]` 匹配异常；依赖容器。

### 2.3 Netflix Hystrix（历史） :id=_23-netflix-hystrix-historical

- **现状：** 自 2018 年起处于维护模式（最终版本 1.5.18）。Netflix 的 README 指向 Resilience4j 和**自适应并发限制**。[DOC]（[README](https://github.com/Netflix/Hystrix)）
- **教训：**
  - 默认的线程池隔离代价高昂（每次调用一次上下文切换，丢失线程局部变量），大多数用户改用了信号量。应让信号量/限额隔离成为默认，线程转移应是可选的。[DOC/INF]
  - 命令对象模式（每次调用一个 `HystrixCommand` 子类）很重，损害了易用性。`@HystrixCommand`（javanica）需要 AspectJ/Spring。[DOC]
  - 基于时间桶的滚动窗口“health counts”以及请求合并是好主意。基于 RxJava 的内部实现代价高。[INF]
  - 静态的、预先调好的阈值很脆弱。Netflix 转向了自适应限制（[concurrency-limits](https://github.com/Netflix/concurrency-limits)，v0.5.4，2025 年 12 月：Vegas、Gradient2、AIMD）。[DOC]

### 2.4 Alibaba Sentinel

- **现状：** 1.8.10（2026 年 5 月），活跃。[DOC]（[repo](https://github.com/alibaba/Sentinel)）
- **功能：** 按 QPS 或并发线程数进行流量控制，带预热和限流整形；熔断（慢调用比例、错误比例、错误数）；**热点参数**流控；系统自适应保护（负载/CPU/RT）；集群流控；控制台；动态规则源（Nacos、ZooKeeper、Apollo……）。[DOC]
- **注解：** `@SentinelResource(value, blockHandler, fallback, ...)`，通过 `sentinel-annotation-aspectj`（AspectJ）或 `sentinel-annotation-cdi-interceptor` 实现。[SRC] 同样没有独立织入。
- **内部实现（可借鉴）：** `LeapArray`，一个放在 `AtomicReferenceArray<WindowWrap>` 中的时间桶环，通过 CAS 创建，在 `tryLock()` 下重置，并以 `Thread.yield()` 作后备。每个桶的计数器是 **`LongAdder[]`**（`MetricBucket`），因此是分段的、对竞争友好的。[SRC]
- **应避免：** 全局 `Context`/`Entry` 线程局部机制，以及每个资源一条 slot 链、资源以字符串为键（很重）；规则与代码解耦使得局部推理变得困难。[INF]

### 2.5 Spring Retry → Spring Framework 7 弹性功能 :id=_25-spring-retry-to-spring-framework-7-resilience

- **Spring Retry 已归档**；其 README 写着它已被“superseded by Spring Framework 7”。最后版本 2.0.13（2026 年 6 月）。[DOC]（[repo](https://github.com/spring-projects/spring-retry)）
- **Spring Framework 7.0** 核心弹性功能：`@Retryable(includes, excludes, maxAttempts, delay, jitter, multiplier, maxDelay)`、`@ConcurrencyLimit(n)`（“particularly useful with Virtual Threads”）、`RetryTemplate`/`RetryPolicy`，通过 `@EnableResilientMethods` 启用；响应式返回类型使用 Reactor 的重试。**没有熔断器。** 基于代理。[DOC]（[blog](https://spring.io/blog/2025/09/09/core-spring-resilience-features)）
- **教训：** 平台现在已经拥有重试和并发限制，所以新库必须提供更多（熔断、限流、超时、组合、配置、性能）。它的 `maxAttempts` 计算的是*重试*次数，而不是总尝试次数，这与 Spring Retry 不同；我们必须给出无歧义的定义。[DOC]

### 2.6 Polly v8（.NET），借鉴设计思路 :id=_26-polly-v8-net-for-design-ideas

- **现状：** 8.8.0（2026 年 9 月），活跃；属于 `Microsoft.Extensions.Resilience` 的一部分。[DOC]（[repo](https://github.com/App-vNext/Polly)）
- **设计：** `ResiliencePipelineBuilder` 组合各种策略（重试、熔断器、超时、限流器、降级、**对冲**以及**混沌**策略）；`ResiliencePipelineRegistry` 带按键区分的管道和动态重新加载；内置遥测。[DOC]
- **性能（可借鉴）：** v8 是一次以内存分配为重点的重写。对于一个 5 策略管道（timeout+rate limiter+retry+CB+timeout）：**v7 2.277 µs / 2824 B → v8 2.089 µs / 40 B**。技巧：`ResilienceContext` 池化、**传递状态的静态 lambda**（无捕获）、`Outcome<T>` 携带异常**而不抛出**、用 switch 表达式谓词代替谓词列表、缓存的管道。[BENCH][DOC]（[performance](https://www.pollydocs.org/advanced/performance.html)）
- **教训：** 证明了对完整管道而言，约 99% 的内存分配削减是可实现的。每种策略的“结果/outcome”类型避免了由异常驱动的控制流。

### 2.7 较新/其他方案（2024–2026） :id=_27-newer--other-20242026

| 库 | 说明 |
|---|---|
| **Helidon 4 Fault Tolerance** | SE 编程式 API（`Async`、`Bulkhead`、`CircuitBreaker`、`Fallback`、`Retry`、`Timeout`），为虚拟线程而构建；MP 注解；**Helidon Declarative**（孵化中）`@Ft.Retry`、`@Ft.CircuitBreaker`……由一个**在构建时生成代码的注解处理器**处理，“to avoid reflection and bytecode manipulation at runtime”。[DOC]（[declarative](https://helidon.io/docs/v4/se/injection/declarative.html)、[SE FT](https://helidon.io/docs/v4/se/fault-tolerance.html)）这是与我们目标最接近的已有方案，不过它与 Helidon Inject 绑定。 |
| **Micronaut** | `@Retryable`、`@CircuitBreaker`（重试的一种变体），以及 Resilience4j-Micronaut 拦截器；**编译期 AOP**（没有运行时代理/反射）。[DOC]（[javadoc](https://docs.micronaut.io/5.0.1/api/io/micronaut/retry/annotation/package-summary.html)）与 Micronaut DI 容器绑定。 |
| **Netflix concurrency-limits** | 自适应并发限制（TCP 拥塞控制风格）。对于 bulkhead 的“自适应”模式，这是一个很强的差异化思路。[DOC] |
| **Spring Framework 7** | 见 §2.5。 |
| **Resilience4j 2.4 / Hedge** | 在虚拟线程方面追赶（JDK 21 基线，用 `ReentrantLock` 代替 `synchronized`）、可选的无锁窗口、对冲。[DOC] |

---

## 3. 功能对比矩阵 :id=_3-feature-comparison-matrix

图例：✅ 是 · ⚠️ 部分 / 有附加条件 · ❌ 否 · — 不适用。

| 能力 | Resilience4j 2.4 | Failsafe 3.3 | SmallRye FT 6.x | Hystrix 1.5 | Sentinel 1.8 | Spring FW 7 | Polly 8 (.NET) | Helidon 4 FT |
|---|---|---|---|---|---|---|---|---|
| 熔断器 | ✅ 计数+时间、慢调用 | ✅ 计数+时间 | ✅ 计数（滚动） | ✅ 时间桶 | ✅ 慢调用/错误比例/错误数 | ❌ | ✅ | ✅ |
| 重试 + 退避/抖动 | ✅ | ✅ | ✅（+Fibonacci、自定义） | ❌ | ❌ | ✅ | ✅ | ✅ |
| 限流器 | ✅ | ✅ 平滑/突发 | ✅ `@RateLimit` | ❌ | ✅ QPS、预热、热点参数 | ❌ | ✅ | ❌ |
| 信号量 bulkhead | ✅ | ✅ | ✅ | ✅ | ✅（线程数） | ✅ `@ConcurrencyLimit` | ✅（通过 RateLimiter 并发） | ✅ |
| 线程池 bulkhead | ✅ | ❌ | ✅（`@Asynchronous`） | ✅（默认） | ❌ | ❌ | — | ⚠️ |
| 超时 | ⚠️ 需要 Future/CS | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| 降级 | ⚠️ 装饰器 / 字符串方法名 | ✅ | ✅ 处理器或方法 | ✅ | ✅ | ❌ | ✅ | ✅ |
| 对冲 | ✅（新） | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| 自适应限制 | ❌（#201 自 2018 年未关闭） | ❌ | ❌ | ❌ | ⚠️ 系统保护 | ❌ | ❌ | ❌ |
| 注解 | ✅ 有声明，❌ 无独立处理 | ❌ | ✅（仅 CDI） | ⚠️ javanica + AspectJ | ✅（AspectJ/CDI） | ✅（Spring 代理） | — | ✅（MP/CDI，或 Helidon Declarative） |
| **纯 Java 中的注解** | ❌ | ❌ | ❌ | ❌ | ⚠️ 手动 AspectJ | ❌ | — | ❌ |
| 构建时（无反射）注解处理 | ⚠️ 仅通过 Micronaut | — | ⚠️ Quarkus 构建时 | ❌ | ❌ | ❌ | — | ✅（孵化中） |
| 命名配置 + 默认值 | ✅ | ❌（代码） | ✅ 通过 `@ApplyGuard` | ✅（Archaius） | ✅ 规则 | ❌ | ✅ 注册表 | ✅ |
| 通过外部配置按方法覆盖 | ⚠️ 按实例名 | ❌ | ✅ 类/方法/注解/参数 | ✅ | ✅（规则源） | ⚠️ 占位符 | ✅（options 重新加载） | ✅ |
| 运行时动态重新配置 | ⚠️ 部分（RateLimiter 限额；注册表替换） | ❌ | ⚠️ | ✅ | ✅ | ❌ | ✅ | ⚠️ |
| 事件监听器 | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ 遥测 | ✅ |
| 指标（Micrometer 等） | ✅ | ❌ | ✅（MP Metrics/Micrometer/OTel） | ✅ | ✅ 自有 | ⚠️ | ✅（.NET metrics） | ✅ |
| 异步（`CompletionStage`） | ✅ | ✅ | ✅ | ⚠️ Rx | ⚠️ | ✅（响应式） | ✅（Task/ValueTask） | ✅ |
| Reactor / RxJava / Kotlin | ✅ / ✅ / ✅ | ❌ | Mutiny、Rx、Kotlin | Rx | ⚠️ Reactor 适配器 | Reactor | — | ❌ |
| 对虚拟线程友好 | ✅ 自 2.3/2.4 起 | ⚠️ `synchronized`（< JDK 24 会固定） | ✅ | ❌ | ⚠️ | ✅ | — | ✅（为 VT 设计） |
| 零分配热路径 | ❌（历史上 CB 272 B/op） | ❌ | ? | ❌ | ⚠️ | ? | ✅ 接近（40 B / 5 种策略） | ? |
| 已发布的 JMH 数字 | ⚠️ 仓库中的旧文件 | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| 仍在维护（2026） | ✅ | ⚠️ 维护状态 | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |

“?” = 未调查或未知；待测量。

---

## 4. 机会：新库可以在哪些方面胜过 Resilience4j :id=_4-opportunities-where-a-new-library-can-beat-resilience4j

### 4.1 性能 :id=_41-performance

1. **稳态下零分配的热路径**（没有事件被消费，成功路径）。具体而言：没有 `Snapshot` 返回值（以拉取方式暴露指标，而不是每次记录都返回），原始类型 `long` 时间戳（直接用 `System.nanoTime()`，测试使用类似 `LongSupplier` 的接口，而不是 `Function<Clock, Long>`），第一次尝试成功时重试没有每次调用的上下文对象（惰性上下文），OPEN 检查中没有 `Instant`（存储 `long` 截止纳秒数）。可行的证据：R4j #117 原型、Polly v8 的 40 B/5 种策略。[SRC][BENCH] **向性能代理提出的目标：CB+Retry+Bulkhead 成功路径 0 B/op，用 JMH `-prof gc` 验证。**
2. **默认对竞争友好的指标**：分段/`LongAdder` 风格或按桶 CAS 的计数器（Sentinel `LeapArray` 风格），而不是单一锁（R4j 默认）或每次记录都分配的、对单个尾节点 CAS 的链表（R4j LOCK_FREE）。[SRC]
3. **没人监听时零成本、有人监听时成本很低的事件**：在*所有*路径上先检查再分配（R4j 对若干事件类型跳过了检查，包括 call-not-permitted，而且每个事件都会捕获 `ZonedDateTime.now()`），理想情况下对指标消费者完全避免事件对象（用计数器，而不是事件流）。不要默认给每个实例挂缓冲区（R4j 的 Spring Boot 集成就是这样做的）。[SRC]
4. **每个方法的一切只解析一次**：名称、配置、降级目标和返回类型适配器在构建时或首次调用时绑定到一个 final 的每方法句柄中，使每次调用的工作只剩获取许可、调用和记录。对比 R4j 每次调用的 SpEL/正则/反射（§1.3）。[SRC]
5. **无反射的降级**：生成的直接调用（或一次绑定的 `MethodHandle`）代替 `Method.invoke`，并且签名在编译期做类型检查（R4j 只在运行时记录“No fallback method match found”）。[SRC]
6. **单态、融合的管道**：将常见的组合（例如 Retry(CB(Bulkhead(call)))）融合进一个生成的/特化的方法中，而不是嵌套的超多态 `Supplier` lambda。[INF]，待用 JMH 和 JIT 日志确认。
7. **竞争下的限流器**：避免每次 CAS 尝试都分配一个 `State`（把周期+许可打包进一个 `long`，或使用按周期的 `AtomicLong`）。[SRC]/[INF]
8. **拒绝路径上把异常当作值**：预分配的、无堆栈的拒绝异常（`CallNotPermitted`、bulkhead 已满），或者为完全不想抛异常的用户提供 `Outcome` 风格的 API（Polly 的思路）。[DOC]/[INF]
9. **热路径上任何地方都不用 `synchronized`**：Java 21 是我们的最低版本，而 JEP 491 的固定问题修复要到 JDK 24 才有。Failsafe 就是前车之鉴。[SRC][DOC]
10. **发布一套可复现的 JMH 测试套件，比较 R4j、Failsafe、SmallRye standalone 和我们的库**（1/4/16/64 线程，平台线程和虚拟线程，`-prof gc`）。目前公开渠道中不存在这样的套件。[DOC，否定性结果]

### 4.2 易用性 :id=_42-ergonomics

1. **在纯 Java 中可用的声明式注解**，不需要 Spring、CDI 或 AspectJ 运行时。目前除了 Helidon Declarative（与容器绑定、孵化中）之外，没有人提供这一点。这是我们的首要差异点。（机制属于注解处理代理的范畴；Helidon/Micronaut 式的构建时生成是最有力的已有方案。）[DOC]
2. **MicroProfile 风格的分层配置**：注解值作为默认值，然后是命名配置（`@CircuitBreaker(config = "payments")`），然后是按 `class/method/policy/param` 的外部覆盖，配置源可插拔（properties、环境变量、系统属性），且不依赖框架。[DOC]
3. **通过注解应用的命名共享策略**（SmallRye `@ApplyGuard`）以及**组合元注解**（R4j #2405 仍未关闭）。[DOC][ISSUE]
4. **编译期校验**：未知的配置名称、错误的降级签名、不兼容的策略组合和无效的阈值都作为编译错误报告，而不是运行时警告。[INF]
5. **一个明确定义并有文档的策略顺序**（MicroProfile 有；R4j 的切面顺序由 Spring `@Order` 属性配置）。[DOC]
6. **无歧义的语义**：`maxAttempts` 与 `maxRetries`（Spring 7 改变了含义）、是否记录 `Error`、是否检查异常的原因链。[DOC]
7. 在虚拟线程上**为同步代码提供真正的超时**（R4j TimeLimiter 需要 `Future`）。[SRC]
8. **可选的自适应并发限制**（Netflix concurrency-limits），作为 bulkhead 的一种模式：R4j 自 2018 年起就有人请求这一功能（#201）。[ISSUE][DOC]
9. **测试易用性**：一个在所有地方都被遵守的可注入时钟（R4j LOCK_FREE 会忽略它）。[SRC]

### 4.3 风险 / 不应过度承诺的事情 :id=_43-risks--things-not-to-over-promise

- Resilience4j 拥有庞大的生态（Spring Boot starter、Micrometer、Feign、Reactor、Kotlin）。我们从零开始；只面向纯 Java 意味着受众更窄。[INF]
- 大多数真实调用是 I/O 密集型的（毫秒级），因此亚微秒级的节省主要对以下场景有意义：高 QPS 服务、CPU 密集型保护（R4j #1513 的流式处理场景）、对内存分配敏感/低延迟的系统，以及虚拟线程规模。宣传点应是“接近零开销、随线程数扩展”加上“无需框架的注解”，而不只是单纯的速度。[INF]
- JIT 逃逸分析可能已经消除了我们列出的部分内存分配（[INF] 项）。在宣称之前先测量。

---

## 5. 对后续阶段的建议 :id=_5-recommendations-for-the-next-phases

1. 采用 **MicroProfile FT 的注解语义和配置覆盖层级**作为参考模型（调整命名），并加入 SmallRye 风格的命名策略和元注解组合。
2. 注解处理使用**构建时生成的代码**（Helidon/Micronaut 已有方案）；最终决定留给注解机制研究。
3. 让**性能约定明确且经过测量**：成功路径 0 B/op，热路径上无锁，指标通过分段计数器实现，事件惰性分配，并从第一天起发布一套对比性的 JMH 测试套件。
4. v1 的范围限定为 **CircuitBreaker、Retry、Timeout、Bulkhead（信号量）、RateLimiter、Fallback**；把自适应限制和对冲视为 v2 的差异化功能；跳过 Cache 和线程池 bulkhead（虚拟线程使其意义降低）。

## 来源 :id=sources

- Resilience4j：[repo](https://github.com/resilience4j/resilience4j)、[发布说明](https://github.com/resilience4j/resilience4j/blob/master/RELEASENOTES.adoc)、[v2.4.0](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0)、issues [#117](https://github.com/resilience4j/resilience4j/issues/117)、[#201](https://github.com/resilience4j/resilience4j/issues/201)、[#1377](https://github.com/resilience4j/resilience4j/issues/1377)、[#1391](https://github.com/resilience4j/resilience4j/issues/1391)、[#1513](https://github.com/resilience4j/resilience4j/issues/1513)、[#2023](https://github.com/resilience4j/resilience4j/issues/2023)、[#2151](https://github.com/resilience4j/resilience4j/issues/2151)、[#2224](https://github.com/resilience4j/resilience4j/issues/2224)、[#2405](https://github.com/resilience4j/resilience4j/issues/2405)、PR [#2241](https://github.com/resilience4j/resilience4j/pull/2241)、[JMH 结果文件](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/jmh/resources/resultsWithGCProfiling.txt)
- Failsafe：[repo](https://github.com/failsafe-lib/failsafe)、[InfoQ 关于 3.x 的报道](https://www.infoq.com/news/2022/02/failsafe-resilience-3)
- MicroProfile FT 4.1：[spec](https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html)；SmallRye FT：[repo](https://github.com/smallrye/smallrye-fault-tolerance)、[编程式 API](https://smallrye.io/docs/smallrye-fault-tolerance/6.9.3/reference/programmatic-api.html)、[reusable / @ApplyGuard](https://smallrye.io/docs/smallrye-fault-tolerance/6.8.0/reference/reusable.html)
- Hystrix：[repo/README](https://github.com/Netflix/Hystrix)；Netflix [concurrency-limits](https://github.com/Netflix/concurrency-limits)
- Sentinel：[repo](https://github.com/alibaba/Sentinel)
- Spring：[Spring Retry（已归档）](https://github.com/spring-projects/spring-retry)、[Spring Framework 7 弹性功能博客](https://spring.io/blog/2025/09/09/core-spring-resilience-features)、[参考文档](https://docs.spring.io/spring-framework/reference/core/resilience.html)
- Polly：[repo](https://github.com/App-vNext/Polly)、[performance](https://www.pollydocs.org/advanced/performance.html)
- Helidon：[Declarative](https://helidon.io/docs/v4/se/injection/declarative.html)、[SE Fault Tolerance](https://helidon.io/docs/v4/se/fault-tolerance.html)；Micronaut [retry annotations](https://docs.micronaut.io/5.0.1/api/io/micronaut/retry/annotation/package-summary.html)
- JDK：[JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- 学术：[SBRC 论文，Polly vs Resilience4j](https://sol.sbc.org.br/index.php/sbrc/article/download/21194/21019)
