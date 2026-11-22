# 设计决策

> **状态：0.13.0 已发布到 Maven Central。** 六个 MVP 策略已端到端可用，支持同步和异步，适用于接口和带注解的类，此外还有外部配置、分类器、编程式 API、事件、快照和 testkit；参见[实现状态](#implementation-status)。初次接触？从[快速开始](start/quickstart.md)入手。本页还总结了在编写第一行代码之前（第 1 阶段）做出的设计决策。
>
> 西班牙语版本：[es/design.md](../es/design.md)

resilience4cata 是一个面向 **纯 Java 21+** 的弹性库：熔断器（circuit breaker）、重试（retry）、超时（timeout）、舱壁隔离（bulkhead）、限流器（rate limiter）和降级（fallback），通过 **注解** 声明，**无需 Spring、CDI 或 AspectJ** 即可工作，并且设计目标是 **开销低于 Resilience4j**。

## 为什么还要一个弹性库 :id=why-another-resilience-library

1. **无需框架的注解。** Resilience4j 的注解只有通过 Spring AOP、AspectJ 或 Micronaut 才能生效。SmallRye Fault Tolerance 的独立模式只支持编程式用法。没有任何主流库在纯 Java 中提供声明式弹性能力（[01 §1.3、§4.2](research/01-landscape.md)）。
2. **可度量的性能。** Resilience4j 在每次调用时都会分配内存（在其自身的 JMH 套件中，单独一个熔断器为 272 B/op），其默认滑动窗口会加锁，并且通过 JIT 无法内联的共享 lambda 来组合策略（[01 §1.7](research/01-landscape.md)、[03 §0](research/03-performance-internals.md)）。Polly v8（.NET）表明接近零内存分配是可以做到的。
3. **多配置作为一等特性**：支持继承的命名配置、profile、用户自定义的组合注解（如 `@ExternalCall`），以及无需重新编译的外部覆盖（[04 §6](research/04-api-and-configuration.md)）。

## 第 1 阶段做出的决策 :id=decisions-taken-in-phase-1

| 主题 | 决策 | 详情 |
|---|---|---|
| Java 基线 | Java 21+，在 21 和 25 上测试 | [架构](architecture.md#build-and-tooling) |
| 构建 | Gradle，使用精简的 Groovy DSL；约定插件用 Java 编写；版本目录。项目中不使用 Kotlin | [架构](architecture.md#build-and-tooling) |
| 注解机制 | **编译期注解处理器（APT）**，生成普通 Java 源码。`core` 中没有运行时代理、字节码生成或 agent | [架构](architecture.md#annotation-mechanism) |
| 框架 | 目前仅支持纯 Java | — |
| 模块 | 单词名称：`annotations`、`core`、`processor`、`config`、`testkit`、`micrometer`、`bom`，以及内部的 `benchmark`、`stress`、`examples` | [架构](architecture.md#modules) |
| `core` 依赖 | 除 JSpecify 外没有依赖（见下文） | [架构](architecture.md#modules) |
| MVP 策略 | `@CircuitBreaker`、`@Retry`、`@Timeout`、`@Bulkhead`、`@RateLimiter`、`@Fallback` | [特性](policies/README.md) |
| 性能约定 | 成功路径 0 B/op，热路径无锁，由 CI 中的 JMH 强制保证 | [特性](performance.md) |
| 文档 | `docs/en` 和 `docs/es` 中的纯 Markdown，由 Docsify 提供服务。英文为准 | [架构](architecture.md#documentation) |
| `annotations` 模块 | 与 `core` 分离 | [架构](architecture.md#modules) |
| 时长 | 字符串（`"250ms"`、`"2s"`、`"PT2S"`），由注解处理器校验 | [特性](policies/README.md) |
| Gradle 约定构建 | 名为 `conventions` 的 included build（保持单词命名规则） | [架构](architecture.md#build-and-tooling) |
| 注解处理器 | **必需。** 没有运行时代理作为后备。如果缺少生成的类，`Resilient.of` 会在启动时失败，并给出如何添加注解处理器的提示。无法加注解的代码使用 `core` 中的编程式 API | [架构](architecture.md#annotation-mechanism) |
| 注解保留策略 | `CLASS`：注解不会在运行时加载，因此 `annotations` 可以作为 `compileOnly` 依赖。如果将来增加代理后备，改为 `RUNTIME` 是兼容的变更 | [架构](architecture.md#annotation-mechanism) |
| 接口注解与实现注解 | 使用 `Resilient.of(interface, impl, registry)` 时，只有接口上的注解生效。在子类风格（`Resilient.create(Impl.class, registry)`）中——一个类只要自己声明了至少一个注解就会进入该风格——类上的注解 **优先**，按属性逐个合并：接口 < 接口方法 < 类 < 类方法（覆盖父类方法的方法优先于被覆盖的方法）。覆盖 `Object` 方法的方法只采用写在它们自身上的注解 | [架构](architecture.md#interface-vs-implementation-annotations) |
| 自调用 | 在子类风格中 **会被拦截**：`this.other()` 会经过生成的覆盖方法，因此其策略生效。在接口包装器风格中无法做到，因为用户实现内部的调用永远不会到达包装器；这一点已写入文档，需要该能力的用户应改为给非 final 类加注解 | [架构](architecture.md#self-invocation) |
| 空值注解 | JSpecify 作为 `core` 的真实（`api`）依赖：这是“零依赖”的唯一例外。它是一个只有几 KB 的纯注解 jar，为 IDE、Kotlin 和 NullAway 提供空值信息 | [架构](architecture.md#modules) |
| 热重载 | **不支持。** 注册表（registry）构建完成后配置即不可变；修改配置意味着重启应用。这使得热路径上的每个配置值都保存在 `final` 字段中 | [特性](guides/configuration.md) |
| 坐标 | 域名 `resilience4cata.dev`：Maven groupId `dev.resilience4cata`，根包 `dev.resilience4cata` | [架构](architecture.md#modules) |

## 1.0 的 API 决策 :id=api-decisions

在冻结前评审 API 时做出（[1.0 计划](plan-1.0.md)，第 1 阶段）。每一项都有意保留了当前的 API；1.0 之后再修改将需要新的主版本，除非该修改是纯新增的。

| 主题 | 决策 | 原因 |
|---|---|---|
| `@Timeout` 没有 `name` | 保留 | 超时没有值得按名称共享的状态；其 `value` 就是时长。以后添加 `name` 属于新增 |
| 构建器的列表参数（`retryOn(List<Class<? extends Throwable>>)`） | 保留 | 在调用处直接写 `List.of(A.class, B.class)` 能推断出正确的类型；只有类型为 `List<Class<? extends Exception>>` 的变量会被拒绝。为了放宽到 `Collection<…>` 而给每个列表属性增加第二种形式并不值得 |
| 参数顺序：`Resilient.of(type, delegate, registry)`、`Resilient.create(type, registry, args…)`、`Pipeline.builder(registry, id)` | 保留 | `create` 以构造函数的可变参数结尾，因此 registry 不能放在最后；`of` 和 `create` 都以类型开头，而 `Pipeline.builder` 没有类型可以放在开头 |
| 注解枚举 `LimitAlgorithm` 与 `AdaptiveLimitConfig.Algorithm` | 保留 | 注解枚举是顶层类型，单独叫 `Algorithm` 会含糊不清；配置枚举是嵌套类型，由配置的名称来限定。重命名配置枚举还会破坏由 0.13.0 生成、引用了它的代码 |
| 快照的结构（重试、超时和对冲请求的快照内嵌 `config`；舱壁隔离、限流器和自适应限流复制少量字段；熔断器的快照两者都没有） | 保留 | 每个快照携带运维人员在实时计数旁边会去看的内容；以后可以增加组件（保留旧构造函数），因此缺少的字段以后仍可加入 |
| 在窗口达到 `minimumCalls` 之前，`CircuitBreakerSnapshot` 的比率为 `-1` | 保留 | 已在 record 上写明，端点的 JSON 中显示的也是同样的 `-1`。以后可以添加 `hasRates()` 访问器 |
| `Pipeline.Builder.retry(String)` 与 `retry(Consumer)` 并存（每个策略都有相同的成对方法） | 保留 | `retry(null)` 存在歧义，但 null 在那里从来不是有意义的参数；`retry(String, Consumer)` 接受可为 null 的 consumer |
| `LockedOverride.policy` 是 `String` | 保留 | 它是策略的配置键（`retry`、`circuitBreaker`、…），与用户在属性文件中写的文本相同 |
| 两个名为 `ExhaustedResult` 的枚举（`Pipeline.ExhaustedResult`，以及注解中的那个，后者还有 `INHERIT`） | 保留 | 它们位于不同的模块，很少被同时导入；注解需要 `INHERIT`，而 pipeline 不能有它 |

## 实现状态 :id=implementation-status

| 部分 | 状态 |
|---|---|
| `@Retry` | **已端到端完成**：注解、注解处理器的校验和代码生成、`RetryPolicy` 运行时、带默认值和命名配置的注册表、组合注解、类型级和方法级合并 |
| `@CircuitBreaker` | **已端到端完成**：基于计数和基于时间的窗口、无锁状态机、带 `maxWaitInHalfOpen` 的半开探测、慢调用、`recordOn`/`ignoreOn`、按 `name` 共享实例、嵌套在 `@Retry` 内部，以及通过 `registry.circuitBreaker(id)` 调用 `forceOpen`/`disable`/`reset` |
| `@Timeout` | **已端到端完成**（异步：见 *异步方法*）。同步方法：调用留在调用方线程上，一个共享的看门狗线程在调用超时时中断它（精度：`timeout / 16`，1–50 ms，从不提前）；之后中断标志总会被清除。按每次尝试生效，位于熔断器内部 |
| `@Bulkhead` | **已端到端完成**：基于 `maxConcurrent` 的信号量式限制；`maxWait = "0"` 时是一个无锁计数器，拒绝只做读取，否则是一个非公平信号量，用同一个计数器表示空闲槽位，并在不发生 pinning 的情况下挂起等待的虚拟线程；按 `name` 共享实例；位于最内层，因此只在方法运行期间占用槽位，等待槽位的时间计入 `@Timeout`。`maxWait > 0` 的异步方法以非阻塞方式等待：按 FIFO 排队，直接获得释放出的槽位（调用在新的虚拟线程上开始），并在 `maxWait` 之后被拒绝；同步和异步调用方共享槽位，两者之间大致公平 |
| `@RateLimiter` | **已端到端完成**：在一个 `long` 上用 GCRA（通用信元速率算法）实现每个 `period` 内 `permits` 个许可：空闲一段时间后最多突发 `permits` 个，之后每隔 `period / permits` 一个许可；没有后台线程，拒绝只做读取。配置 `maxWait` 时，调用预留下一个许可并睡眠到该许可到期；被中断的等待会归还许可。按 `name` 共享实例，可通过 `registry.rateLimiter(id)` 查看。该层位于熔断器内部，因此被熔断器拒绝的调用不会消耗许可 |
| `@Fallback` | **已端到端完成**（异步：见 *异步方法*），作为最外层，因此也能看到拒绝（`CallNotPermittedException`、`BulkheadFullException`、`RateLimitExceededException`、`CallTimeoutException`）。两种形式，都在编译期检查：一个有类型的处理器类（`FallbackHandler<T>`，`T` 可赋值给返回类型，public 无参构造函数，每个方法一个实例），它接收一个包含失败、尝试次数和参数的 `FallbackContext`；或者是带原始参数外加一个可选异常参数的同级方法，最具体的异常类型优先。使用接口包装器时，同级方法必须是 `default`、抽象或 `static` 的接口方法，因为生成的类无法调用 private 方法。`applyOn`/`skipOn` 过滤失败（`skipOn` 优先）。与 `@Retry` 一起使用时，降级在重试循环放弃的地方运行，因此无需在成功路径上分配任何内存就能知道尝试次数。处理器抛出的受检异常，如果方法声明了它就重新抛出，否则包装为 `UndeclaredThrowableException`。支持泛型方法：同级方法声明相同的类型参数（按位置，边界相同），并以显式类型实参调用；只有当结果类型不依赖于方法的类型参数时，处理器类才可用 |
| `@Hedge` | **已完成（仅异步）**，于 0.4.0：在 `delay` 时间内没有结果时再次发起同一调用，或者在出现 `hedgeOn` 中的失败后立即发起；第一个成功的胜出，其他尝试会通过每一个内层策略被取消（熔断器不做记录，舱壁隔离释放槽位）。每次调用一个表示进度的 `int`，通过 compare-and-set 修改，恰好决定一次结果；延迟在无锁异步定时器上执行（在 `ManualClock` 上则是精确的）；对冲预算（默认开启）限制额外负载。在同一方法上与 `@Retry` 互斥；同步方法目前是编译错误（[特性](policies/hedge.md)） |
| `@AdaptiveLimit` | **已完成**，于 0.6.0，同步和异步：一个限额会自行变化的舱壁隔离，在 `minLimit` 和 `maxLimit` 之间，根据调用的延迟调整（Netflix concurrency-limits：默认的 `GRADIENT2`，或 `AIMD`），每个窗口由窗口结束后完成调用的那个线程重新计算一次；丢弃（`dropOn`：默认是超时和舱壁已满）会让 AIMD 回退；仅支持快速失败，暂不支持 Vegas（[页面](policies/adaptive-limit.md)） |
| 异步方法 | 所有策略 **均已完成**：返回 `CompletionStage<T>` 或 `CompletableFuture<T>` 的方法会使用异步管道，由返回类型决定（无需额外注解）。在委托返回 stage 之前抛出的异常，或返回 `null` stage，都算作调用失败。重试是被调度的，从不睡眠：第一次尝试在调用方线程上运行，后续尝试在新的虚拟线程上运行。超时以 `CallTimeoutException` 让 future 失败，并取消迟到的 stage（`Future.cancel`）；它运行在一个无锁定时器上（条带化收件箱，每种时长一条通道，通过状态 CAS 取消），从不提前触发，最多迟约两个 tick（tick = 最短异步超时 / 16，1–50 ms），已经完成的 stage 会跳过定时器。舱壁隔离会一直占用槽位直到 stage 完成。限流器的 `maxWait` 预留许可，并在许可到期时以非阻塞方式开始调用。降级可以返回 `T` 或 `T` 的 stage。整个库只有一个守护定时器线程；它触发的一切都在虚拟线程上运行。支持通配符 stage（`CompletionStage<? extends X>`、`CompletableFuture<?>`）：策略、降级和分类器使用上界 `X`，降级也可以返回 `CompletionStage<? extends X>`。限制：不支持 `? super X` 或自定义 stage 类型；取消返回的 future 不会取消委托 |
| `@Retry` / `@CircuitBreaker` 上的 `classifier` | **已完成**，同步和异步：一个 `Classifier<T>` 同时对结果和异常做出判断（`SUCCESS`、`FAILURE`、`IGNORE`），在编译期检查，每个方法一个实例。它取代 `retryOn`/`recordOn`（两者组合是编译错误）；`abortOn`/`ignoreOn` 仍然优先。`FAILURE` 结果会被重试，尝试次数用尽时原样返回（无 `@Fallback` 时），或者在 `onExhaustedResult = THROW` 时作为 `ResultRejectedException` 抛出并交给 `@Fallback`。抛出异常的分类器（无论是重试的还是熔断器的）会立即结束调用，并产生 `GaveUp` 事件（`CLASSIFIER_FAILED`）。没有分类器的方法生成的代码与之前相同（[特性](guides/classifiers.md)） |
| 编程式 API（`Pipeline`） | **已完成**：从 `ResilienceRegistry` 构建的不可变 pipeline，用于无法加注解的代码；策略、顺序和语义与生成的代码相同，支持同步和异步，带降级和分类器，`run(...)` 用于没有结果的调用，返回 stage 的异步降级，以及 `onExhaustedResult`；同步成功路径无内存分配（[特性](guides/pipeline.md)） |
| 事件与快照 | **已完成**：有类型的 sealed 事件（熔断器状态转换和拒绝、重试、超时、舱壁隔离和限流器拒绝、降级），在注册表构建器上注册，没人监听时没有开销；`registry.snapshot()` 以及各 handle 上的 `snapshot()` 返回只读 record（[特性](guides/observability.md)） |
| 测试支持（`testkit`） | **已完成**：`ManualClock` 通过 `Builder.ticker(...)` 驱动重试、熔断器、限流器、同步和异步超时、同步和异步舱壁等待以及异步延迟；通过 `Builder.random(...)` 使用带种子的抖动（[特性](guides/testing.md)） |
| 带注解的类（子类风格） | **已端到端完成**，同步和异步：声明了弹性注解的非 final 类会得到一个生成的 `<Class>$Resilient extends <Class>`，通过 `Resilient.create(Type.class, registry, constructorArgs...)` 或 `new Type$Resilient(registry, ...)` 创建。只有带策略的方法会被覆盖，每个都以 `super.method(...)` 结尾，因此自调用（`this.other()`）会被拦截。所实现接口和父类上的注解按属性逐个合并：接口 < 接口方法 < 类 < 类方法。final、sealed、abstract、内部类和 private 类、record、枚举，以及在方法级加了注解的 final 方法都是编译错误。只实现了带注解接口的类不会被处理：它通过 `Resilient.of` 获得接口的策略（[特性](guides/annotated-classes.md)） |
| 外部配置（`config` 模块） | **已完成（MVP）**：properties 文件、classpath 资源、环境变量（`R4C_…`）和系统属性（`r4c.…`）；默认值、命名配置、`extends`、profile、按实例覆盖（`instances.<id>.*`，优先于注解值）；同时在代码中和外部定义的命名配置按属性逐个采用外部值；严格校验，一次性报告所有问题，附带来源和“did you mean”（[特性](guides/configuration.md)），以及用于检查不匹配任何实例的覆盖 id 的 `verifyInstances(registry)`。；注解上的 `locked = true` 让其值不被按实例覆盖改变（[锁定](guides/configuration.md#locking-an-instance)）。尚未支持：YAML |
| Gradle 增量处理注册 | **已完成**：注解处理器注册为 isolating（`META-INF/gradle/incremental.annotation.processors`）；每个生成的类以被包装的接口作为唯一的 originating element，因此编辑一个无关文件只会重新编译该文件，编辑一个 bundle、处理器类或父接口只会重新处理使用它的接口 |
| JMH 基准测试 | 重试（一个和四个服务）、熔断器（计数和时间窗口）、超时、舱壁隔离和限流器（1 和 4 个线程）、异步方法、分类器以及编程式 `Pipeline`，与 Resilience4j 对比；完整的基线与新版本对比通过 `infra/bench.sh` 在 AWS 上并行运行（[基准测试](benchmarks.md)） |
| jcstress（`stress` 模块） | **已完成**：21 个针对无锁策略的 jcstress 测试（舱壁计数器、限流器突发和归还、熔断器状态转换和 quiet 状态、超时/看门狗的 stamp 协议、熔断器转换事件恰好发布一次），经过变异检查（18 个变异体捕获了 17 个；超时调用结束处一个仅 release 语义的 CAS 有测试守护，但在这台 Mac 上未能复现）。使用 `./gradlew :stress:jcstress -Pjcstress="-m quick"` 运行；`./gradlew build` 只编译它们 |
| 错误码 | **已完成**：每一个注解处理器诊断（`R4C-APT/ATR/CMP/ASY/CLS/FB/SUB`）、运行时包装和 pipeline 错误（`R4C-RT`）、注册表和策略配置错误（`R4C-REG`：无效值、未知或重复的命名配置、冲突的共享实例、未知的实例 id，附带“Did you mean …?”）以及配置问题（`R4C-CFG`）都有一个稳定的错误码，并在[错误参考](errors.md)中有一节，包含错误示例和修复方法；构建会编译注解处理器的示例 |
| 发布 | Maven Central 的配置已就绪（`r4c.publish`、`bom`、`centralBundle`）；已发布 0.1.0 到 0.13.0（`release.yml`）——参见[发布](releasing.md) |
| 用户指南 | **已完成**：[入门](start/quickstart.md)，每个代码片段都由 `examples/.../GuideTest.java` 编译并运行；GitHub 上的根目录 `README.md` |
| CI（GitHub Actions） | **已完成**，在 GitHub 上：每次 push 和 PR 在 JDK 21 和 25 上运行 `build`（`./gradlew build -PtestJdk=N`），每个 PR 运行 `alloc-gate`（`./gradlew :benchmark:allocationCheck`：40 个基准测试对照 `benchmark/allocation-budgets.properties` 中的 B/op 预算），每次 push 到 main 之后以及每晚运行 `stress`（jcstress quick，在 x86 和 ARM 上），每周运行 `stress-default`（jcstress 默认模式，在 x86 和 ARM 上），`stress-mutants`（手动：一次削弱一个保护条件并检查 jcstress 能否捕获，在 x86 和 ARM 上），每晚运行 `fuzz`（使用新种子运行模型测试和注解处理器的模糊测试），每周运行 `soak`（每个策略在负载下运行 2 小时，检查没有任何东西增长：[浸泡测试](benchmarks.md#soak-test)），每周运行 `mutation`（针对 `core` 单元测试的 PIT 变异测试，报告没有被任何测试检测到的变异体），`sonar`（带覆盖率的 SonarCloud），在涉及构建或注解处理器的 PR 上运行 `consumer`（像用户那样，由一个 Gradle 项目和一个 Maven 项目在 class path 和 module path 上安装并使用已发布的构件），对工作流文件运行 `actionlint`；`release`（手动，在 `v<version>` 标签上）先运行完整的 `build`；Dependabot 负责版本目录和 actions，后者固定到提交 SHA |

## 未决问题 :id=open-questions

目前没有未决问题。新的问题会添加在这里。

## 研究文档 :id=research-documents

| # | 文档 | 主题 |
|---|---|---|
| 01 | [现状概览](research/01-landscape.md) | 深入分析 Resilience4j、竞品、机会 |
| 02 | [注解机制](research/02-annotation-mechanisms.md) | APT 对比代理、ByteBuddy 和 agent，附我们自己的 JMH 测试 |
| 03 | [性能内部机制](research/03-performance-internals.md) | 各策略的算法和无锁设计，基准测试方法 |
| 04 | [API 与配置](research/04-api-and-configuration.md) | 特性分级、注解、配置模型 |
| 05 | [项目架构](research/05-project-architecture.md) | 模块、Gradle、JPMS、工具、文档、版本管理 |

**关于证据。** 每份研究文档都为其论断标注了类别：在源码或文档中已验证、已测量，或推断得出。文档 04 是根据已有知识撰写的，没有在线核对来源。其中的引用，尤其是 SmallRye 的注解名称和 MicroProfile 的执行顺序，在依赖之前必须先核实。关于 resilience4cata 的性能论断只有在经过测量后才有效；测量结果见[基准测试](benchmarks.md)。
