# 04 — 功能集、注解 API 与配置模型

> **历史文档。** 本研究早于实现，按原样保留；关于库如今的行为，请参阅 [功能](../policies/README.md)、[架构](../architecture.md) 和 [基准测试](../benchmarks.md)。

> 第 1 阶段研究。状态：**供讨论的提案**。没有生产代码；下面的每段 Java 代码都是 API 草图。
> 范围：用户看到和编写的内容。注解如何织入（APT、代理还是字节码）、并发内部机制以及模块布局由其他文档讨论，
> 只在它们约束 API 时才会提及。

## 目录 :id=contents

1. [摘要](#1-summary)
2. [已有方案](#2-prior-art)
3. [功能目录与分级](#3-feature-catalog-and-tiers)
4. [注解设计](#4-annotation-design)
5. [执行顺序](#5-execution-order)
6. [多套配置](#6-multiple-configurations)
7. [降级与异常分类](#7-fallbacks-and-exception-classification)
8. [异步与虚拟线程](#8-async-and-virtual-threads)
9. [事件、指标与状态查看](#9-events-metrics-and-state-inspection)
10. [开发者体验与测试](#10-developer-experience-and-testing)
11. [待决问题](#11-open-questions)
12. [参考资料](#12-sources)

---

## 1. 摘要 :id=_1-summary

- **六个 MVP 策略**：`@CircuitBreaker`、`@Retry`、`@Timeout`、`@Bulkhead`、`@RateLimiter`、`@Fallback`。异步支持
  来自返回类型（`CompletionStage`），而不是单独的注解。**v1** 增加 `@Hedge`、`@AdaptiveLimit`、
  重试预算、Micrometer/OpenTelemetry 绑定、YAML 和运行时重新配置。**之后**：`@Cached`、响应式类型、
  故障注入、框架集成。
- **组合注解是一等功能**：用户自定义的 `@ExternalCall` 通过 `@ResiliencePolicy` 元注解把多个策略打包在一起。
  这是在代码中实现“多套配置”的主要方式。
- **配置与状态分离。** *配置*（config）是具名的不可变模板（`config = "external"`）。*实例*（instance）是
  有状态的对象（熔断器窗口、限流器许可），由 `name` 标识，默认每个方法一个。Resilience4j 用同一个字符串表示两者，
  这是常见的困惑来源。
- **分层解析**，优先级从低到高：内置默认值 → `defaults` → 具名配置（可以 `extends` 另一个）→ profile 覆盖层 →
  类注解 → 方法注解 → 外部按实例覆盖（properties/YAML → 环境变量 → 系统属性）→ 编程式覆盖。
  一切都只在创建实例时解析**一次**，得到不可变的 record。热路径从不查询配置。
- **类型安全的降级**：主要形式是 `@Fallback(UserFallback.class)` 加上泛型接口 `FallbackHandler<T>`。为方便起见
  也提供方法名形式，由注解处理器在编译期检查。
- **异常和结果共用一个分类器**：`recordOn`/`ignoreOn` 类列表，外加一个可选的 `Classifier<T>`，它能同时看到结果
  和 throwable。
- **可测试性是设计的一部分**：可注入的 `Clock`/`Scheduler`、带种子的抖动 `Random`、`forceOpen()`，以及一个带
  JUnit 5 扩展和事件断言的 `test` 模块。
- **热重载**：不在 MVP 中。v1 提供显式的 `reconfigure(...)` API，替换一个不可变快照（每次调用一次 volatile 读），
  仅限于可以安全地在运行时修改的参数。

## 2. 已有方案 :id=_2-prior-art

| 库 | 声明方式 | 配置来源 | 降级 | 组合策略的顺序 |
|---|---|---|---|---|
| **Resilience4j**（通过 Spring AOP / CDI 使用注解） | `@CircuitBreaker(name, fallbackMethod)`、`@Retry`、`@RateLimiter`、`@Bulkhead(type)`、`@TimeLimiter` | `resilience4j.<policy>.configs.<cfg>.*` + `instances.<name>.baseConfig` | **字符串**方法名，通过反射解析，按异常参数类型匹配 | 固定为 `Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(fn)))))`，可通过 `*AspectOrder` 属性修改 |
| **MicroProfile Fault Tolerance 4.x** | `@Retry`、`@Timeout`、`@CircuitBreaker`、`@Bulkhead`、`@Fallback`、`@Asynchronous`，带类型化属性（`maxRetries`、`delay`+`delayUnit`、`failOn`/`skipOn`、`retryOn`/`abortOn`） | MP Config：`<class>/<method>/<Annotation>/<param>`、`<class>/<Annotation>/<param>`、`<Annotation>/<param>`；`<Annotation>/enabled` | `@Fallback(value = Handler.class)` **或** `fallbackMethod` 字符串（部署时校验） | 由规范定义：Fallback → Retry → CircuitBreaker → Timeout → Bulkhead（SmallRye 还加了 RateLimit） |
| **SmallRye FT**（MP FT 的实现） | 增加 `@RateLimit`、`@ExponentialBackoff`、`@FibonacciBackoff`、`@CustomBackoff`、`@RetryWhen`、`@BeforeRetry`，以及 `@ApplyGuard`/`@ApplyFaultTolerance`（复用以编程方式构建的具名 guard） | MP Config，外加编程式 `Guard` API | 与 MP 相同 | 与 MP 相同 |
| **Spring Retry** | `@Retryable(retryFor, noRetryFor, maxAttempts, backoff = @Backoff(...), recover = "...")`、`@CircuitBreaker` | 属性中的 SpEL 表达式（`maxAttemptsExpression`） | `@Recover`，按**签名**匹配（返回类型 + 异常 + 参数） | 每个方法一个策略 |
| **Spring Framework 7**（core） | `@Retryable`、`@ConcurrencyLimit`、`@EnableResilientMethods` | 属性加 `${…}` 占位符 | 无 | 不适用 |
| **Failsafe** | 没有注解。`Failsafe.with(fallback, retryPolicy, circuitBreaker, timeout).get(...)` | 仅 builder | `Fallback.of(...)` 策略（类型化） | **组合顺序就是参数顺序**（外 → 内） |
| **Polly v8（.NET）** | `ResiliencePipelineBuilder().AddRetry(...).AddCircuitBreaker(...).AddTimeout(...)` | `ResiliencePipelineRegistry<TKey>`、options 绑定，通过 `IOptionsMonitor` **动态重载** | Fallback 策略（类型化的 `Outcome<T>`） | `Add*` 调用的顺序（第一个 = 最外层）；包含对冲请求和混沌策略 |

我们从各家借鉴的东西：

- **MP FT**：类型化属性（`Class<? extends Throwable>[]`，而不是正则表达式）、按类/方法/注解确定的配置键、
  `enabled` 开关，以及配置覆盖注解。它的缺点是顺序僵硬，且没有可共享的具名配置（直到 SmallRye 的 `@ApplyGuard`）。
- **Resilience4j**：带 `baseConfig` 继承的具名配置，以及跨方法共享实例。它的缺点是字符串形式的降级没有编译期
  检查，同一个字符串既是配置键又是状态标识，而且顺序只能全局修改。
- **Failsafe / Polly**：在 builder 中显式表达顺序、类型化的降级，以及同时覆盖异常和结果的单一“是否处理”谓词
  （`handleResultIf`、`ShouldHandle`）。Polly 还贡献了对冲请求、pipeline 注册表，以及通过替换整个 pipeline 实现重载。
- **Spring Retry**：按签名查找降级方法（`@Recover`）不需要字符串，但歧义错误只在运行时出现。我们保留这个思路，
  并把检查移到编译期。

## 3. 功能目录与分级 :id=_3-feature-catalog-and-tiers

| 功能 | 级别 | 说明 |
|---|---|---|
| 熔断器（基于计数和基于时间的滑动窗口、慢调用率、半开探测调用数） | **MVP** | 与 R4j 模型相同，便于用户迁移。还提供 `forceOpen`/`disable`，用于运维和测试。 |
| 重试（固定、指数、指数 + full/equal 抖动、最大时长、基于结果的重试） | **MVP** | 默认开启抖动（[AWS backoff & jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)）。 |
| 超时（同步和异步） | **MVP** | 同步超时会中断调用方；在虚拟线程上的行为有文档说明（§8）。 |
| 舱壁隔离（bulkhead）（信号量，最大并发 + 最大等待） | **MVP** | 有了虚拟线程，线程池舱壁价值不大；最多作为 v1 的一个选项。 |
| 限流器（令牌桶/固定刷新、每周期许可数、最大等待） | **MVP** | 属于与 R4j 对比的功能集。 |
| 降级（类型化 handler / 经检查的方法） | **MVP** | §7。 |
| 异步：`CompletionStage`/`CompletableFuture` 返回类型 | **MVP** | 从方法签名中识别。 |
| 组合注解（`@ResiliencePolicy`） | **MVP** | 对“多套配置”的主要回答。 |
| 具名配置、`extends`、profile、properties + 环境变量 + 系统属性 | **MVP** | §6。 |
| 编程式 builder API（无注解） | **MVP** | 也是注解编译成或委托到的基础。 |
| 事件（类型化的 sealed 层次结构）、监听器注册 | **MVP** | 没有注册监听器时不做内存分配。 |
| 状态查看（`registry.snapshot()`） | **MVP** | 只读 record；适合健康检查端点。 |
| 测试：`Clock`、`Scheduler`、带种子的抖动、强制状态 | **MVP** | §10。 |
| 对冲请求（`@Hedge`：延迟、最大对冲尝试次数） | **v1** | [The Tail at Scale](https://research.google/pubs/the-tail-at-scale/)；Polly 的 hedging。在 v1 中与同一层级的 `@Retry` 互斥。 |
| 自适应并发限制（`@AdaptiveLimit`：AIMD、Vegas、Gradient2） | **v1** | Netflix [concurrency-limits](https://github.com/Netflix/concurrency-limits)。 |
| 重试预算（每个实例的重试比例有上限） | **v1** | 防止重试风暴（[Finagle retry budgets](https://twitter.github.io/finagle/guide/Clients.html#retries)）。 |
| 指标绑定：Micrometer、OpenTelemetry | **v1** | 独立模块；core 只暴露一个 SPI。 |
| YAML 配置源 | **v1** | 独立模块，因此 core 不需要 YAML 依赖。 |
| 运行时重新配置 API | **v1** | §6.7。 |
| JUnit 5 扩展 + AssertJ 风格的事件断言 | **v1**（Clock/强制状态在 MVP 中） | |
| 可配置的执行顺序 | **v1** | MVP 中为默认顺序。§5。 |
| `@Cached`（带 TTL 的结果缓存） | **之后** | 通常用 Caffeine 更合适；只有在能共用键/实例基础设施时才值得加入。 |
| 响应式类型（Reactor/Mutiny）、Kotlin 协程 | **之后** | 适配器模块。 |
| 故障注入 / 混沌（`@Inject…` 延迟/异常，类似 Polly 的 Simmy） | **之后** | 在测试和预发环境中非常有用。 |
| 跨嵌套调用传播截止时间 / 时间预算 | **之后** | 基于 `ScopedValue`（21 中为预览，[JDK 25、JEP 506](https://openjdk.org/jeps/506) 中正式发布）。 |
| Spring/Quarkus/Micronaut 集成 | **之后** | 按决定不在范围内。 |

## 4. 注解设计 :id=_4-annotation-design

### 4.1 原则 :id=_41-principles

1. **只要 Java 允许，就使用类型化属性。** 异常列表是 `Class<? extends Throwable>[]`，用枚举代替字符串标志，
   降级是类。
2. **每个属性都有一个“未设置”哨兵值**（`-1`、`""`、空数组、`Inherit.class`）。注解无法区分“没写”和
   “写了默认值”，所以如果属性中写真实的默认值，就会悄悄覆盖具名配置并破坏继承。实际生效的默认值放在配置层（§6），
   而不是注解里。
3. **时长是字符串**，采用一种严格的语法（`"250ms"`、`"2s"`、`"1m"`、`"PT2S"`），因为：注解不能持有 `Duration`；
   `value + unit` 成对（MP 风格）会让属性数量翻倍；而且 properties 文件使用同一种语法。注解处理器在编译期拒绝
   错误的字面量（§10.1）。
4. **可用于方法和类型。** 类型级注解作用于每个 public 方法，方法级注解*逐个属性*地覆盖它（而不是整体覆盖）。
5. **`config` 和 `name` 含义不同**：
   - `config` 选择从哪个具名模板开始。
   - `name` 选择*有状态的实例*。为空时，实例 id 为 `<fqcn>#<method>(<erased param types>)`。
     `name` 相同的两个方法共享同一个熔断器或限流器。这两个方法必须解析出完全相同的配置；否则启动失败。
6. **每个策略都支持 `enabled`**，可以从外部覆盖（如 MP 的 `<Annotation>/enabled`）。

### 4.2 提议的注解（草图） :id=_42-proposed-annotations-sketch

```java
package io.resilience4cata.annotation;   // package name provisional

@Retention(RUNTIME) @Target({METHOD, TYPE, ANNOTATION_TYPE}) @Documented
public @interface CircuitBreaker {
    String name() default "";                  // instance id; "" = per method
    String config() default "";                // named template; "" = "default"
    WindowType windowType() default WindowType.INHERIT;   // COUNT, TIME
    int windowSize() default -1;               // calls or seconds
    int minimumCalls() default -1;
    float failureRateThreshold() default -1f;  // percent, 0 < x <= 100
    float slowCallRateThreshold() default -1f;
    String slowCallDuration() default "";
    String waitInOpen() default "";            // e.g. "30s"
    int halfOpenCalls() default -1;
    Class<? extends Throwable>[] recordOn() default {};
    Class<? extends Throwable>[] ignoreOn() default {};
    Class<? extends Classifier> classifier() default Classifier.Inherit.class;
    String enabled() default "";               // "", "true", "false"  (tri-state)
}

@Retention(RUNTIME) @Target({METHOD, TYPE, ANNOTATION_TYPE}) @Documented
public @interface Retry {
    String name() default "";
    String config() default "";
    int maxAttempts() default -1;              // total attempts, including the first
    String delay() default "";                 // initial delay
    Backoff backoff() default Backoff.INHERIT; // FIXED, EXPONENTIAL
    double multiplier() default -1;
    String maxDelay() default "";
    Jitter jitter() default Jitter.INHERIT;    // NONE, FULL, EQUAL
    String maxDuration() default "";           // overall budget across attempts
    Class<? extends Throwable>[] retryOn() default {};
    Class<? extends Throwable>[] abortOn() default {};
    Class<? extends Classifier> classifier() default Classifier.Inherit.class;
    String enabled() default "";
}

public @interface Timeout {            // @Target/@Retention as above
    String value() default "";         // "2s"; empty = from config
    String config() default "";
    String enabled() default "";
}

public @interface Bulkhead {
    String name() default "";
    String config() default "";
    int maxConcurrent() default -1;
    String maxWait() default "";        // "0" = fail fast
    String enabled() default "";
}

public @interface RateLimiter {
    String name() default "";
    String config() default "";
    int permits() default -1;
    String period() default "";         // e.g. "1s"
    String maxWait() default "";
    String enabled() default "";
}

public @interface Fallback {
    Class<? extends io.resilience4cata.FallbackHandler<?>> value() default Inherit.class; // typed handler
    String method() default "";                    // alternative: sibling method, compile-time checked
    Class<? extends Throwable>[] applyOn() default {Throwable.class};
    Class<? extends Throwable>[] skipOn() default {};
}

// v1
public @interface Hedge { String name() default ""; String config() default "";
                          String delay() default ""; int maxHedged() default -1; }
public @interface AdaptiveLimit { String name() default ""; String config() default "";
                                  LimitAlgorithm algorithm() default LimitAlgorithm.INHERIT; /* AIMD, VEGAS, GRADIENT2 */ }
```

有两类注解是刻意**不提供**的：

- `@Asynchronous`（MP）：返回类型已经说明了调用是否异步。一个必须与返回类型保持一致的额外标记是多余的，也容易写错。
  要在另一个 executor 上运行*同步*方法，可以使用带 `offload = true` 的 `@Timeout`（待决问题 §11）或编程式 API。
- `@Bulkhead(type = THREADPOOL)`（R4j）：有了虚拟线程，信号量隔离才是正确的默认选择。

### 4.3 组合注解（“元注解”） :id=_43-composed-annotations-meta-annotations

用户应当能够为一组策略命名一次，然后到处复用：

```java
@ResiliencePolicy                              // marks a composed annotation; required
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
@Bulkhead(maxConcurrent = 50)
public @interface ExternalCall {}

@ResiliencePolicy
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Retry(maxAttempts = 1)                         // explicit: no retries for writes
@CircuitBreaker(config = "external")
@Timeout("5s")
public @interface NonIdempotentCall {}

@ExternalCall
public class PaymentsClient {
    public Receipt charge(Order o) { ... }                    // @ExternalCall applies

    @NonIdempotentCall                                         // overrides the class-level bundle
    public Receipt refund(Order o) { ... }

    @Timeout("500ms")                                          // overrides one attribute only
    public Status status(String id) { ... }
}
```

规则：

- **元注解深度**：不限，带环检测。组合注解本身也可以标注另一个组合注解。
- **合并语义**：类似 Spring 的属性覆盖，但更简单。先按策略类型、再逐个属性合并。直接注解优先于组合注解。
  方法级优先于类级。未设置的属性（哨兵值）从不覆盖任何东西。
- **冲突**：如果同一元素上的两个组合注解把同一个策略属性设成了不同的值，这是一个**编译期错误**（APT）
  或启动错误（仅运行时模式）。悄悄地“后者胜出”是不可接受的。
- 组合注解有自己的配置键（`ExternalCall/Timeout/value`），运维人员无需逐个修改类就能调整整组配置。

### 4.4 接口与继承 :id=_44-interfaces-and-inheritance

- 接口方法上的注解作用于实现类；实现类上的注解优先。
- `@Inherited` 只对类级注解生效（Java 语义）。通过方法重写的继承是显式实现的，并有文档说明。
- private、static 和 final 方法：能否支持取决于织入机制（见机制研究）。API 必须把不支持的位置报告为**错误**。
  不能重复 Spring AOP 那种悄无声息的“自调用不会被增强”的行为。

## 5. 执行顺序 :id=_5-execution-order

### 5.1 默认 :id=_51-default

从最外层到最内层：

```
Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method
```

理由：

- **降级在最外层**：它能在所有其他策略执行之后看到最终结果，包括 `CallNotPermitted`。
- **重试在熔断器之外**：每次尝试都会被熔断器记录，打开的熔断器会立即停止重试
  （`abortOn` 默认包含 `CallNotPermittedException`）。
- **限流器在熔断器之内**：被拒绝的调用不会消耗许可。
- **超时在重试之内**：超时作用于每次尝试。`Retry.maxDuration` 覆盖整个操作。
- **舱壁隔离在最内层**：只有在调用真正运行时才持有许可，退避休眠期间不持有。

这与 MP FT 一致，也兼容 R4j 的默认顺序（R4j 中降级不是一层）。

### 5.2 可配置的顺序（v1） :id=_52-configurable-order-v1

需要不同顺序的用户（例如让超时包住*整个*重试循环）可以显式声明。顺序在每个实例上校验并保存；从不按调用决定：

```java
@ResiliencePolicy
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Order({Layer.FALLBACK, Layer.TIMEOUT, Layer.RETRY, Layer.CIRCUIT_BREAKER})  // omitted layers keep their relative default order
@Retry(config = "external") @CircuitBreaker(config = "external") @Timeout("10s")
public @interface WholeOperationDeadline {}
```

全局默认顺序：`resilience4cata.order=fallback,retry,circuit-breaker,...`。与其使用第二个 `@Timeout`，*每次尝试*
的超时和*整个操作*的超时应当是两个独立的属性：`@Timeout("500ms")` 针对每次尝试，`@Retry(maxDuration = "3s")`
是总时长。这无需 `@Order` 就覆盖了约 90% 的实际需求。

任何以令人意外的方式改变语义的顺序（舱壁隔离在重试之外、降级不在最外层）都会在启动时产生一条说明后果的**警告**。

## 6. 多套配置 :id=_6-multiple-configurations

### 6.1 概念 :id=_61-concepts

| 概念 | 是什么 | 可变？ |
|---|---|---|
| **Defaults** | 每个策略的内置值，可在 `defaults` 下覆盖 | 否（构建之后） |
| **具名配置** | 一个模板，例如 `external`、`internal`、`batch`。可以 `extends` 另一个具名配置 | 否 |
| **Profile** | 一个整体开启的覆盖层（`prod`、`test`、`eu-west`）。它覆盖 defaults 和具名配置中的键 | 否 |
| **实例** | 带 id 的有状态对象（熔断器状态、窗口、许可） | 状态可变，配置只能通过 `reconfigure` 修改 |
| **解析后的策略** | 所有值都为 final 的不可变 record，每个实例计算一次 | 否 |

### 6.2 优先级（低 → 高） :id=_62-precedence-lowest-to-highest

```
 1. built-in defaults
 2. defaults.<policy>.*                       (file/env/…)
 3. configs.<name>.<policy>.*                 (walks the `extends` chain, root first)
 4. profiles.<active>.…                       (applies over 2 and 3, in profile activation order)
 5. composed-annotation attributes            (@ExternalCall)
 6. class-level annotation attributes
 7. method-level annotation attributes
 8. instances.<id>.<policy>.*                 external per-instance override
 9. programmatic override (builder)           highest; for tests and explicit code
```

在同一个外部层内，来源的优先级与 MP Config 相同：**系统属性 > 环境变量 > 应用文件 > classpath 默认值**。
每个来源通过一个小的 `ConfigSource` SPI 接入。

外部配置（8）覆盖注解（5–7），与 MP FT 一样。注解是开发者给出的默认值，运维人员必须能够在不重新编译的情况下修改它们。
想要一个不可被覆盖的值的用户，可以把实例标记为 `locked: true`，或以编程方式调用 `.locked()`，覆盖它的尝试会被记录到日志。

### 6.3 外部格式 :id=_63-external-format

Properties（core，无依赖）：

```properties
resilience4cata.profiles.active=prod

resilience4cata.defaults.retry.max-attempts=3
resilience4cata.defaults.retry.jitter=full

resilience4cata.configs.external.circuit-breaker.failure-rate-threshold=50
resilience4cata.configs.external.circuit-breaker.wait-in-open=30s
resilience4cata.configs.external.retry.delay=100ms

resilience4cata.configs.slow-external.extends=external
resilience4cata.configs.slow-external.timeout.value=10s

resilience4cata.profiles.test.configs.external.circuit-breaker.wait-in-open=10ms

# per instance, by explicit name …
resilience4cata.instances.payments.circuit-breaker.minimum-calls=20
# … or by derived id, MP-compatible spelling also accepted
com.acme.PaymentsClient/charge/Timeout/value=3s
```

YAML（`yaml` 模块，v1）是同一棵树：

```yaml
resilience4cata:
  defaults:
    retry: { max-attempts: 3, jitter: full }
  configs:
    external:
      circuit-breaker: { failure-rate-threshold: 50, wait-in-open: 30s }
      retry: { delay: 100ms }
    slow-external:
      extends: external
      timeout: { value: 10s }
  profiles:
    test:
      configs:
        external:
          circuit-breaker: { wait-in-open: 10ms }
```

**环境变量**采用 MP Config 的映射：转为大写，每个非字母数字字符都变成 `_`，因此得到
`RESILIENCE4CATA_CONFIGS_EXTERNAL_RETRY_DELAY=200ms`。当配置名包含 `-` 或 `_` 时，这个映射无法反推。由此得出两条规则：
名称限定为 `[a-z0-9]+(-[a-z0-9]+)*`，环境变量来源通过规范化*已知*的键来匹配，而不是解析环境变量名。

### 6.4 编程式 API :id=_64-programmatic-api

编程式配置与文件和注解的能力相同。注解只是这个模型之上的语法糖。

```java
Resilience resilience = Resilience.builder()
    .source(ConfigSources.classpath("resilience4cata.properties"))
    .source(ConfigSources.environment())
    .source(ConfigSources.systemProperties())
    .profiles("prod")
    .defaults(d -> d.retry(r -> r.maxAttempts(3).jitter(Jitter.FULL)))
    .config("external", c -> c
        .circuitBreaker(cb -> cb.failureRateThreshold(50).waitInOpen(Duration.ofSeconds(30)))
        .retry(r -> r.delay(Duration.ofMillis(100)).retryOn(IOException.class)))
    .config("slow-external", c -> c.extendsConfig("external").timeout(Duration.ofSeconds(10)))
    .clock(Clock.system())                        // replaceable in tests
    .listener(event -> log.debug("{}", event))
    .strict(true)                                 // fail on unknown keys/names (default true)
    .build();                                     // all validation happens here

// Without annotations: an explicit, reusable, immutable pipeline
Pipeline<Receipt> charge = resilience.pipeline("payments", Receipt.class)
    .config("external")
    .fallback((Throwable t) -> Receipt.pending())
    .build();
Receipt r = charge.execute(() -> client.charge(order));

// With annotations: the woven/generated object comes from the same Resilience instance
PaymentsClient client = resilience.wrap(PaymentsClient.class, new PaymentsClient());  // mechanism TBD (doc 02)
```

### 6.5 校验：何时进行 :id=_65-validation-when

| 检查 | 编译期（APT，如果使用） | `build()` / 启动 | 运行时 |
|---|---|---|---|
| 属性范围、时长语法、`retryOn ∩ abortOn` 重叠 | ✔ | ✔（用于仅运行时模式） | |
| 降级签名兼容性 | ✔ | ✔ | |
| 组合注解冲突、非法位置（private/final） | ✔ | ✔ | |
| 引用的 `config` 名称存在 | ✘（配置在外部） | ✔ | |
| 文件中的未知键（拼写错误），并提示“did you mean `wait-in-open`?” | | ✔ | |
| `extends` 循环、共享 `name` 但配置不一致 | | ✔ | |
| 不存在的 profile | | ✔（警告） | |

规则：**`build()` 返回之后，调用过程中不会再出现任何配置错误。** 为了启动速度，实例可以延迟创建（首次调用时）。
这种情况下，方法的配置仍然已在 `build()` 期间通过扫描已注册的注解类型（APT 索引列出了它们）被提前校验。

### 6.6 保持热路径干净 :id=_66-keeping-the-hot-path-clean

- 解析产生 `record ResolvedRetry(int maxAttempts, long delayNanos, ...)` 之类的 record：基本类型、final 字段、
  已换算为纳秒的时长、预编译进分类器的异常集合（§7.3）。
- 每个被织入的调用点直接持有其 `Pipeline` 的引用（在生成的代码中是 static final 字段，在代理模式下是按方法缓存的
  handle）。**每次调用都没有按名称的 map 查找。**
- 按名称查找（`resilience.circuitBreaker("payments")`）用于管理和测试，而不是用于调用。

### 6.7 热重载：值得付出代价吗？ :id=_67-hot-reload-worth-the-cost

**不在 MVP 中。v1 中作为显式 API 提供，而不是文件监视器。**

- *代价*：每个策略每次调用对配置快照做一次 `volatile`/acquire 读。这在 x86 上很便宜，在 ARM 上稍贵一些。
  它还会阻碍一些 JIT 常量折叠，而在策略参数本可被当作常量处理时，这一点很重要。从未被重新配置的实例应当**零代价**。
  可能的实现方式：一个在构建时选择不同实现的 `reloadable` 标志，或者 `MutableCallSite`/`SwitchPoint` 失效机制，
  后者让快速路径保持常量折叠。并发研究应当对这些方案做基准测试。
- *语义*：有些参数可以安全修改（阈值、延迟、最大尝试次数、许可数、超时）。另一些会改变状态的形态（窗口类型或大小、
  持有许可时的舱壁大小）。后者需要有文档说明的状态重置，否则会被拒绝。
- *API*：

```java
resilience.reconfigure("payments", c -> c.circuitBreaker(cb -> cb.failureRateThreshold(30)));
// or: resilience.reload()  -> re-reads all sources, validates everything, then swaps atomically, all-or-nothing
```

监视文件或远程配置留给集成层去做，由它们调用 `reload()`。Polly 的对应行为同样来自其宿主（`IOptionsMonitor`），
而不是核心库。

## 7. 降级与异常分类 :id=_7-fallbacks-and-exception-classification

### 7.1 类型化的降级 handler（主要形式） :id=_71-typed-fallback-handler-primary-form

```java
public interface FallbackHandler<T> {
    T apply(FallbackContext ctx) throws Throwable;   // ctx: failure, attempt count, args, method, instance id
}

public final class UserFallback implements FallbackHandler<User> {
    @Override public User apply(FallbackContext ctx) {
        return ctx.failure() instanceof CallNotPermittedException
            ? User.anonymous()
            : User.cached(ctx.argument(0, String.class));
    }
}

public class UserClient {
    @Retry @CircuitBreaker(config = "external")
    @Fallback(UserFallback.class)
    public User find(String id) { ... }
}
```

注解处理器检查 `T` 能否赋值给方法的返回类型，对异步方法则检查能否赋值给 `CompletionStage<T>` 中的 `T`。
handler 通过 `HandlerFactory` 为每个实例创建一次，默认使用无参构造器，之后可以接入依赖注入。

### 7.2 同类方法降级（便捷形式，编译期检查） :id=_72-sibling-method-fallback-convenience-form-compile-time-checked

```java
@Fallback(method = "findFallback")
public User find(String id) { ... }

private User findFallback(String id, IOException e) { ... }        // most specific match wins
private User findFallback(String id, Throwable t) { ... }
```

规则沿用 R4j 和 Spring `@Recover`，并且更加严格：

- 参数必须是原方法的参数，后面可选地跟**一个** `Throwable` 子类型。
- 返回类型必须能赋值给原返回类型。对异步方法，可以是 `T` 或 `CompletionStage<T>`。
- 存在多个重载时，异常类型最具体的那个胜出。如果都不匹配，则重新抛出原异常。
- **所有检查都在注解处理器中进行**，错误信息附带建议（§10.1）。在仅运行时模式下，它们在 `build()` 时执行。
- 字符串无法避免：Java 注解不能持有方法引用。曾考虑过从 APT 元模型生成常量（`UserClient_.FIND_FALLBACK`），
  但因为轮次顺序问题以及处理器未启用时令人困惑的错误而被否决。

### 7.3 异常与结果分类 :id=_73-exception-and-result-classification

每个记录结果的策略（CircuitBreaker、Retry、Hedge、AdaptiveLimit）有三种输入：

1. **允许/拒绝列表**：`recordOn`/`ignoreOn`（CB）、`retryOn`/`abortOn`（Retry）。优先级：**拒绝优先于允许**，
   然后层次结构中**最具体的类型**胜出（MP FT 语义）。默认值：CB 记录 `Exception`，Retry 重试 `Exception`，
   两者都不处理 `Error`（`StackOverflowError` 例外？→ 待决问题）。
2. **解包**：`CompletionException`、`ExecutionException`、`InvocationTargetException` 和
   `UndeclaredThrowableException` 在分类前被解包。这是可配置的。
3. **分类器**（Classifier），处理列表无法表达的任何情况，包括结果：

```java
@FunctionalInterface
public interface Classifier<T> {
    Outcome classify(T result, Throwable failure);   // exactly one of result/failure is meaningful
    enum Outcome { SUCCESS, FAILURE, IGNORE }        // IGNORE = do not count, do not retry
}

public final class HttpStatusClassifier implements Classifier<HttpResponse<?>> {
    public Outcome classify(HttpResponse<?> r, Throwable t) {
        if (t != null) return t instanceof HttpTimeoutException ? Outcome.FAILURE : Outcome.IGNORE;
        int s = r.statusCode();
        return s >= 500 || s == 429 ? Outcome.FAILURE : Outcome.SUCCESS;
    }
}

@Retry(config = "external", classifier = HttpStatusClassifier.class)
@CircuitBreaker(config = "external", classifier = HttpStatusClassifier.class)
public HttpResponse<String> get(URI uri) { ... }
```

- 用一个方法同时处理结果和异常，避免了 R4j 在 `recordExceptions`、`recordFailurePredicate` 和
  `recordResultPredicate` 之间的拆分，以及它们之间优先级的困惑。
- 结果为 `FAILURE` 时会被记录或重试。如果重试用尽，则返回最后一个结果（默认），或者抛出
  `ResultRejectedException`。这可以通过 `Retry.onExhaustedResult = RETURN | THROW` 配置。
- **性能**：列表在解析时被编译进一个 `ClassValue<Outcome>` 缓存，因此某个异常类在第一次之后的分类只需一次查找。
  这比每次失败都对列表逐个调用 `isAssignableFrom` 更便宜。

## 8. 异步与虚拟线程 :id=_8-async-and-virtual-threads

- **由返回类型决定**：`CompletionStage<T>`/`CompletableFuture<T>` 走异步 pipeline（非阻塞的重试调度在一个
  `Scheduler` 上，超时通过类似 `orTimeout` 的调度实现，舱壁许可在完成时释放）。其他任何返回类型都是同步的。
- **在虚拟线程上同步执行**是推荐的 Java 21 风格。重试退避让虚拟线程休眠，代价很低
  （[JEP 444](https://openjdk.org/jeps/444)）。信号量舱壁阻塞的是虚拟线程而不是平台线程。
- **同步超时**需要以下两种方式之一：(a) 在另一个（虚拟）线程上运行调用，超时时中断它；或者 (b) 一个中断调用方线程的
  watchdog。(b) 避免了线程切换，但依赖被调用方响应中断。提议：同步默认采用 (b)，并在文档中说明它是“协作式”的。
  具体实现由并发研究决定。结构化并发（[JEP 505](https://openjdk.org/jeps/505)）在近期的 JDK 中仍是预览功能，
  因此 API 不能依赖它。
- **Executor** 可按配置设置（`async.executor`），库自己卸载出去的任何工作默认使用
  `Executors.newVirtualThreadPerTaskExecutor()`。
- **对冲请求**（v1）需要并发，因此即使是同步方法也总会卸载对冲尝试，并取消落败的尝试。

## 9. 事件、指标与状态查看 :id=_9-events-metrics-and-state-inspection

```java
public sealed interface ResilienceEvent permits CircuitBreakerEvent, RetryEvent, TimeoutEvent,
                                                BulkheadEvent, RateLimiterEvent, FallbackEvent {
    String instance(); long nanoTime();
}
public sealed interface CircuitBreakerEvent extends ResilienceEvent {
    record StateTransition(String instance, long nanoTime, State from, State to) implements CircuitBreakerEvent {}
    record CallNotPermitted(String instance, long nanoTime) implements CircuitBreakerEvent {}
    record FailureRateExceeded(String instance, long nanoTime, float rate) implements CircuitBreakerEvent {}
}

resilience.listen(CircuitBreakerEvent.StateTransition.class, e -> alerting.notify(e));
resilience.listen("payments", RetryEvent.class, e -> ...);     // per instance
```

- **不使用时零代价**：每个实例持有一个 `final` 监听器引用，没有注册任何监听器时它是 `NOOP`。事件 record 只在
  `if (listener != NOOP)` 检查之后才创建。每次调用的成功事件需要显式开启（`events.per-call=true`）。
  状态转换事件总是会投递。
- **指标**：core 在实例本地的 `LongAdder` 中统计结果，通过 `snapshot()` 暴露。`micrometer` 和 `opentelemetry`
  模块（v1）把快照绑定为 gauge 或 function counter，因此热路径上没有 meter 查找。
- **状态查看**（健康检查、管理端点）：

```java
ResilienceSnapshot s = resilience.snapshot();
for (CircuitBreakerSnapshot cb : s.circuitBreakers()) {
    System.out.printf("%s %s failureRate=%.1f%% calls=%d%n", cb.instance(), cb.state(), cb.failureRate(), cb.bufferedCalls());
}
resilience.circuitBreaker("payments").forceOpen();   // operational controls: forceOpen, forceClosed, disable, reset
```

## 10. 开发者体验与测试 :id=_10-developer-experience-and-testing

### 10.1 错误信息 :id=_101-error-messages

所有错误都包含**位置**（类、方法、注解、属性，或配置键及其来源）、**问题是什么**以及**如何修复**：

```
error: [R4C-FB-002] @Fallback(method = "findFallbak") on com.acme.UserClient.find(String):
       no method named 'findFallbak' found.
       Did you mean 'findFallback'?
       Expected signature: User findFallbak(String id[, <? extends Throwable> e])

error: [R4C-CFG-011] unknown key 'resilience4cata.configs.external.circuit-breaker.wait-in-opne'
       (from classpath:resilience4cata.properties, line 7). Did you mean 'wait-in-open'?

error: [R4C-CMP-004] conflicting values for @Timeout.value on com.acme.PaymentsClient.charge(Order):
       "2s" from @ExternalCall and "5s" from @NonIdempotentCall. Set it explicitly on the method to resolve.
```

- 稳定的错误码（`R4C-…`），每个错误码都有一个文档页面，提供英文和西班牙文。
- **诊断输出** `resilience.describe("com.acme.PaymentsClient#charge")` 打印生效的配置，以及每个值的*来源*
  （`waitInOpen=30s ← configs.external (prod profile, env)`）。这对分层配置帮助很大。
- 使用注解处理器时可以进行编译期检查（机制的决定属于文档 02）。否则同样的检查在 `build()` 时执行，信息完全相同。

### 10.2 测试方案 :id=_102-testing-story

core 提供钩子（MVP）；`test` 模块提供便利功能（v1 中的 JUnit 扩展）。

```java
var clock = new ManualClock();                       // implements io.resilience4cata.Clock (nanoTime + wall)
var scheduler = new ManualScheduler(clock);          // retries/timeouts advance on clock.advance(...)

Resilience r = Resilience.builder()
    .clock(clock).scheduler(scheduler)
    .random(new SplittableRandom(42))                // deterministic jitter
    .profiles("test")
    .build();

UserClient client = r.wrap(UserClient.class, fakeClient);

fakeClient.failNext(5, new IOException("boom"));
assertThat(client.find("1")).isEqualTo(User.anonymous());

clock.advance(Duration.ofSeconds(30));               // move past waitInOpen without sleeping
assertThat(r.circuitBreaker("payments").state()).isEqualTo(State.HALF_OPEN);

// JUnit 5 (v1)
@ResilienceTest(profiles = "test")
class UserClientTest {
    @Inject ManualClock clock;
    @Inject RecordedEvents events;

    @Test void opensAfterFailures(@ForceState(instance = "payments", state = State.OPEN) CircuitBreakerHandle cb) {
        assertThrows(CallNotPermittedException.class, () -> client.find("1"));
        events.assertThat().containsExactly(CallNotPermitted.class);
    }
}
```

- **全部禁用**：在业务逻辑的单元测试中使用 `resilience4cata.enabled=false`，或 `Resilience.disabled()`，
  它是一个直通实现，只有在 `fallback.when-disabled=true` 时才仍然执行降级。
- **测试之间重置**：`resilience.reset()` 重置每个实例的状态，但保留配置。
- **故障注入**（之后）可在集成测试中复用：`@InjectLatency("200ms", rate = 0.1)`。

## 11. 待决问题 :id=_11-open-questions

1. 时长应该是字符串（提议）还是 `long` + `TimeUnit`（MP 风格）？字符串易读且与文件格式一致；数字即使没有处理器
   也能得到 javac 的类型检查。
2. 注解处理器应当是**必需的**还是可选的？如果可选，每项编译期检查都必须在 `build()` 时重复一遍。这取决于机制的决定
   （文档 02）。
3. `Error`（例如 `OutOfMemoryError`）是否应当绕过所有策略？提议：是，但可配置一个允许列表作为例外。
4. `@Timeout` 是否应当为不响应中断的代码提供 `offload = true`？
5. 外部配置是否应当能够**添加**方法没有声明的策略（`instances.<id>.retry.enabled=true`）？MP FT 只允许覆盖已声明的策略。
   允许这样做很强大，但会让代码更难理解，还会牺牲一些生成代码的灵活性。提议：否。
6. MP 兼容的键（`<class>/<method>/<Annotation>/<param>`）是只作为别名保留，还是去掉？它们有助于迁移，但会让文档翻倍。
7. 默认的实例粒度：按方法（提议）还是按类？R4j 的按名称共享常用于“每个下游服务一个熔断器”。显式的 `name` 覆盖了
   这种情况。

## 12. 参考资料 :id=_12-sources

- Resilience4j 文档：<https://resilience4j.readme.io/docs> — Spring Boot / 注解用法与切面顺序：
  <https://resilience4j.readme.io/docs/getting-started-3>；源码：<https://github.com/resilience4j/resilience4j>
- MicroProfile Fault Tolerance 4.1 规范（注解、配置键、执行顺序）：
  <https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html>；
  仓库：<https://github.com/eclipse/microprofile-fault-tolerance>
- MicroProfile Config 3.1，环境变量映射：
  <https://download.eclipse.org/microprofile/microprofile-config-3.1/microprofile-config-spec-3.1.html>
- SmallRye Fault Tolerance（扩展：`@RateLimit`、退避注解、`@ApplyGuard`、编程式 API）：
  <https://smallrye.io/docs/smallrye-fault-tolerance/>
- Spring Retry（`@Retryable`、`@Recover`、`@Backoff`）：<https://github.com/spring-projects/spring-retry>
- Spring Framework 7 核心韧性功能（`@Retryable`、`@ConcurrencyLimit`）：
  <https://docs.spring.io/spring-framework/reference/core/resilience.html>
- Failsafe 策略与组合：<https://failsafe.dev/policies/>、<https://failsafe.dev/>
- Polly v8：pipeline <https://www.pollydocs.org/pipelines/>，对冲请求 <https://www.pollydocs.org/strategies/hedging.html>，
  注册表与动态重载 <https://www.pollydocs.org/advanced/dependency-injection.html>，
  混沌 <https://www.pollydocs.org/chaos/>
- Netflix concurrency-limits（自适应限流）：<https://github.com/Netflix/concurrency-limits>
- J. Dean、L. Barroso，“The Tail at Scale”（对冲请求）：<https://research.google/pubs/the-tail-at-scale/>
- M. Brooker，“Exponential Backoff And Jitter”：<https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/>
- Finagle 重试预算：<https://twitter.github.io/finagle/guide/Clients.html#retries>
- JEP 444 虚拟线程：<https://openjdk.org/jeps/444>；JEP 505 结构化并发（预览）：
  <https://openjdk.org/jeps/505>；JEP 506 Scoped Values：<https://openjdk.org/jeps/506>
