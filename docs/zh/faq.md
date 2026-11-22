# 常见问题

人们最先会问的问题的简短回答，并附有指向详细内容的链接。

## 概述 :id=general

### resilience4cata 是什么？ :id=what

一个面向纯 Java 21+ 的注解驱动韧性库：重试 (retry)、对冲请求 (hedging)、熔断器 (circuit breaker)、限流器 (rate limiter)、自适应并发限流、超时 (timeout)、舱壁隔离 (bulkhead) 和降级 (fallback)。注解处理器在编译时把注解变成普通的 Java 源码，因此运行时没有代理、没有字节码生成，也没有 agent。参见[工作原理](start/how-it-works.md)。

### 支持哪些 Java 版本？ :id=java

Java 21 或更高版本，编译时和运行时都是如此。库使用 `--release 21` 编译，并在 Java 21 和 25 上测试。

### 它能与 Spring、Quarkus、Micronaut 或 Jakarta CDI 一起使用吗？ :id=frameworks

| 框架 | 集成 |
|---|---|
| Spring Boot 4.1+ | `resilience4cata-spring`（从 0.4.0 起）：带注解的类以其生成的子类实例化，带注解接口的 bean 会被包装，注册表从 `application.yml` 配置，另外还有健康检查、actuator 端点和指标。不使用 AOP。[Spring Boot 指南](guides/spring.md) |
| Spring Boot 3 | 不支持 |
| Micronaut 5.2+（Java 25） | `resilience4cata-micronaut`（从 0.5.0 起）：标记了 `@ResilientBean` 的带注解接口的 bean 会被包装，使用 `-Ar4c.inject=micronaut` 编译的带注解类以其生成的子类创建，注册表从 `application.yml` 配置，另外还有健康检查、管理端点和指标。不使用 AOP。[Micronaut 指南](guides/micronaut.md) |
| Micronaut 4 | 不支持 |
| Quarkus 3.40+ | `resilience4cata-quarkus`（从 0.5.0 起），一个 Quarkus 扩展：带注解的类以其生成的子类作为 bean，带注解接口的 bean 和 REST 客户端通过其包装对象注入，注册表从 `application.properties` 配置，另外还有就绪检查、指标和构建时检查（包括与 SmallRye Fault Tolerance 的冲突）。不使用拦截器。[Quarkus 指南](guides/quarkus.md)，以及 [SmallRye Fault Tolerance 与 resilience4cata 对比](guides/quarkus.md#smallrye) |
| Jakarta CDI | 目前还没有，也不需要：生成的类是普通 Java 代码，所以你可以自己创建对象（`Resilient.of(…)` 或 `new C$Resilient(registry, …)`），再按你的框架的方式把它注册为 bean。带注解类的生成构造函数会复制 `@Inject` 和限定符，因此容器也可以直接实例化 `C$Resilient` |

无论哪种情况，运行时都没有代理、没有 AOP，也没有注解扫描。

### 它是 Resilience4j 的分支吗？ :id=fork

不是。它覆盖类似的策略，但设计不同：用编译时生成的代码代替装饰器，所有策略共用一个注册表，配置只解析一次。它的基准测试与 Resilience4j 2.4.0 对比。有些默认值不同，容易让人意外：重试从 200 ms 开始以带抖动的指数方式退避，限流器会立即拒绝，而 `Error` 不会被重试（[默认值](migrate/from-resilience4j.md#config-mapping)、[差异](migrate/from-resilience4j.md#differences)）。参见[从 Resilience4j 迁移](migrate/from-resilience4j.md)。

### 它依赖什么？ :id=dependencies

`core` 和 `annotations` 只依赖 JDK，`core` 另外依赖 [JSpecify](https://jspecify.dev/) 注解（`org.jspecify:jspecify`）。处理器只在处理器路径上，注解是 `compileOnly`：不会有其他东西进入你的运行时 class path。

## 使用 :id=using

### 必须使用注解处理器吗？ :id=processor

使用注解时，是的：处理器是必需的，不存在基于反射的后备方案。如果它没有运行，`Resilient.of` 会以 [R4C-RT-003](errors.md#r4c-rt-003) 失败，其消息会给出需要添加的依赖。在框架集成中，同样的错误会改为在启动时报告：Spring Boot 中为 [R4C-SPR-006](errors.md#r4c-spr-006)，Micronaut 中带注解的类为 [R4C-MN-007](errors.md#r4c-mn-007)，Quarkus 中为 [R4C-QK-008](errors.md#r4c-qk-008)（在构建时）。不使用注解时，[`Pipeline`](guides/pipeline.md) API 不需要处理器。

### 用接口还是带注解的类？ :id=style

如果接口归你所有，并且实现可以是任何东西（HTTP 客户端、lambda、测试桩），就为**接口**添加注解。如果没有接口，或者类对自身的调用也必须经过策略，就为**类**添加注解。参见[带注解的类](guides/annotated-classes.md)。

### 可以改变策略的顺序吗？ :id=order

可以，在方法、类型或 bundle 上使用 `@PolicyOrder`。默认顺序是 Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method，与注解的书写顺序无关，因此重试的每次尝试都会被熔断器记录并拥有自己的超时，而打开的熔断器会停止重试。例如，`@PolicyOrder({TIMEOUT, RETRY})` 会在整个重试循环外面套一个超时。降级始终在最外层，且顺序在编译时确定。参见[改变顺序](policies/README.md#changing-the-order)。

### 为什么实现调用我的方法时它没有被重试？ :id=self-invocation

在接口风格下，策略位于生成的包装对象中；实现对自身的调用不会经过它。参见[故障排查](guides/troubleshooting.md#self-invocation)。

### 支持响应式类型（`Mono`、`Flux`、`Single`）吗？ :id=reactive

Reactor 的 `Mono` 和 `Flux` 支持，需要 `resilience4cata-reactor`：每个策略在每次订阅时应用一次，使用订阅者的上下文，并随订阅一起取消；在 `Flux` 上，重试和降级只在第一个元素之前起作用，超时在每个元素到达时重新计时。参见 [Project Reactor](guides/reactor.md)。其他响应式类型（Mutiny 的 `Uni` 和 `Multi`、RxJava 的类型）暂不支持：与韧性注解一起使用时会产生编译错误 [R4C-ASY-002](errors.md#r4c-asy-002)，因为策略只会保护构建 pipeline 的过程，而不是实际的工作。请在实现中转换为 `CompletionStage`、`Mono` 或 `Flux`。参见[异步方法](guides/async.md)。

### 它能与虚拟线程一起使用吗？ :id=virtual-threads

能。调用路径不获取锁，也不使用 `synchronized`，因此不会把虚拟线程钉 (pin) 在它的载体线程上。库自己的延迟回调（异步重试、异步超时）在新的虚拟线程上运行。

同步策略就是为每个请求一个线程的模型而设计的：超时会中断被阻塞的虚拟线程，并在抛出异常之前清除该中断（线程原本就有的中断会被保留）；舱壁隔离或限流器的等待以及重试的暂停会 park 虚拟线程并释放其载体线程；超时为某个线程做的记录会随线程一起消失。测试套件让数千个虚拟线程经过每个阻塞策略，同时用 JFR 录制检查没有线程被钉住；并在虚拟线程上运行各框架的负载测试：Spring Boot 使用 `spring.threads.virtual.enabled=true`，Micronaut 使用其阻塞 executor（在 Java 21 及更高版本上为虚拟线程），Quarkus 端点使用 `@RunOnVirtualThread`。

### 可以在运行时更改配置吗？ :id=reload

不可以，这是有意的设计。注解、命名配置和外部配置都在每个实例创建时解析一次。要在生产环境中重新调整，请修改外部配置（例如 `instances.<id>.*`）并重启。要强制设定熔断器的状态，请使用它的 handle：`registry.circuitBreaker(id).forceOpen()`、`disable()`、`reset()`。

### 如何通过文件或环境变量进行配置？ :id=external-config

添加 `resilience4cata-config`，并用 `ResilienceConfig.load()` 构建注册表。键的形式如 `defaults.retry.maxAttempts=5` 或 `R4C_DEFAULTS_RETRY_MAXATTEMPTS=5`。参见[配置](guides/configuration.md)和[键参考](reference/configuration.md)。

### 什么是实例 id？ :id=instance-id

每个带注解的方法都有自己的策略实例，其 id 为 `<type>#<method>(<parameter types>)`，例如 `com.acme.CatalogClient#price(java.lang.String,int)`。`name` 属性会替换它，具有相同 `name` 的方法共享一个实例。id 被 handle、事件、快照和 `instances.<id>.*` 键使用。

## 运维 :id=operations

### 如何获取指标？ :id=metrics

使用 [Micrometer 模块](guides/micrometer.md)（`resilience4cata-micrometer`）：三行设置代码就能得到来自事件的计数器，以及每个熔断器、舱壁隔离和限流器的 gauge。对于其他指标库，可以基于[事件和 handle](guides/observability.md#diy) 构建同样的东西。

### 它会统计成功调用或测量延迟吗？ :id=success-metrics

不会。成功的调用不发布事件，也不更新计数器：在每次调用时更新计数器意味着在热路径上写共享内存。请在你自己的代码中或用框架的指标来测量调用次数和延迟。

### 如何测试在重试之间等待数秒的代码？ :id=testing

使用 `resilience4cata-testkit`：`ManualClock` 让休眠、等待和超时都跟随一个由测试推进的时钟，所以长达数秒的延迟不耗费真实时间。参见[测试](guides/testing.md)。

### 为什么我的超时没有停止调用？ :id=timeout

同步超时会中断调用方的线程；忽略中断的代码（经典的阻塞式 socket I/O、忙循环）会一直运行到返回为止。同时也要设置客户端自己的 I/O 超时。参见[故障排查](guides/troubleshooting.md#timeout-not-interrupting)。

### 它有多快？ :id=performance

在 AWS Graviton3 上，第一次尝试即成功的重试每次调用耗时 1.4 ns，熔断器 1.9 ns，分配 0 字节，而 Resilience4j 2.4.0 分别为 22.9 ns 和 89.9 ns（[在候选版本上测得](benchmarks.md#release-candidate)）。熔断器的 1.9 ns 在其窗口中没有失败时成立；当每 10 次调用失败 1 次且有四个线程时，为 278 ns 对 940 ns（[真实的失败率](benchmarks.md#failure-rates)）。有竞争的路径在两个库中都要花费数百纳秒，而且这些都是仅在 ARM 上测得的平均值。参见[性能](performance.md)。

## 项目 :id=project

### 它可以用于生产环境吗？ :id=production

每个策略都有单元测试和端到端测试，无锁策略还有并发测试（jcstress，在 x86 和 ARM 上），内存分配在 CI 中设有门禁，每周还有一次两小时的浸泡测试 (soak test) 在负载下运行所有策略。从 1.0.0 候选版本起 API 已冻结：除非对某个候选版本的反馈迫使做出更改，否则 1.0.0 将按原样发布它，而[更新日志](changelog.md)会列出任何此类更改。1.0.0 将保持兼容的内容见[兼容性](reference/compatibility.md)。

### 哪些功能还不支持？ :id=not-supported

缓存和线程池式舱壁隔离；自适应限流的 Vegas 算法和等待（`maxWait`）；同步对冲请求（`@Hedge` 适用于异步方法）；Mutiny 和 RxJava 类型（支持 Reactor 的 `Mono` 和 `Flux`）；`? super` 异步类型；运行时重新配置；Spring Boot、Micronaut 和 Quarkus 以外框架的集成模块。哪些已有计划、哪些被有意排除，见[设计决策](design.md)。

### 路线图上有什么？ :id=roadmap

只是计划，而非承诺；名称和范围可能变化：

| 功能 | 阶段 | 说明 |
|---|---|---|
| 对冲请求（`@Hedge`） | 已在 0.4.0 完成 | 异步方法和 `Pipeline.executeAsync`（[对冲请求](policies/hedge.md)）；同步对冲以后再做。Resilience4j 有 `resilience4j-hedge`（异步，仅编程式） |
| 自适应并发限流（`@AdaptiveLimit`） | 已在 0.6.0 完成 | Gradient2 和 AIMD，同步和异步（[自适应限流](policies/adaptive-limit.md)）；Vegas 和等待以后再做。Resilience4j 没有 |
| OpenTelemetry 链路追踪 | 以后 | 指标从 0.3.0 起已在 `resilience4cata-opentelemetry` 中提供（[指南](guides/opentelemetry.md)） |
| 纯 Java 的 YAML 文件 | 以后 | `config` 模块读取属性文件；Spring Boot、Micronaut 和 Quarkus 已经读取它们自己的 YAML。将作为单独的模块，使 `core` 保持无依赖 |
| 可配置的执行顺序（`@PolicyOrder`） | 已在 0.10.0 完成 | 仅限注解；`Pipeline` 保持默认顺序（[改变顺序](policies/README.md#changing-the-order)） |
| `locked` 属性（外部配置无法覆盖的注解值） | 已在 0.7.0 完成 | 注解上的 `locked = true`，`Pipeline.Builder.locked()`（[锁定实例](guides/configuration.md#locking-an-instance)） |
| `@Cached`、Mutiny 和 RxJava、Kotlin 协程、故障注入、截止时间传播 | 以后 | |

在故障注入功能出现之前，请在测试中用受保护接口或类的桩来制造失败，并用 [testkit](guides/testing.md) 的 `ManualClock` 驱动延迟和超时。

### 在哪里报告 bug？ :id=bugs

在 [GitHub issue tracker](https://github.com/fabiangftech/resilience4cata/issues) 上。如果有 `R4C-…` 错误码，请附上它，以及注解、Java 和 resilience4cata 的版本。
