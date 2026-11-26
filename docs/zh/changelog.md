# 更新日志

resilience4cata 每个版本中值得注意的改动，最新的在前。

格式遵循 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)，项目使用[语义化版本](https://semver.org/spec/v2.0.0.html)。在 1.0.0 之前，次版本可能会改变 API。

## [Unreleased] :id=unreleased

自 1.1.0 以来的修复，全部保持兼容：API、生成的代码和配置均未改变。其中一处影响启动：在 Micronaut 上，按指南所示在 YAML 中写不带引号的 `verify-instances: off`，会导致应用无法启动。

### 修复 :id=fixed

- 在 `*Config` 构建器、`Pipeline` 步骤或 `ResilienceRegistry.Builder` 中，超过约 292 年（`Long.MAX_VALUE` 纳秒）的时长现在会以 [R4C-REG-001](errors.md#r4c-reg-001) 被拒绝，并指出属性名。此前它会在创建策略时才失败，抛出不带 `R4C` 代码的 `ArithmeticException`。注解和配置文件早已拒绝此类值。
- **Micronaut：YAML 中不带引号的 `verify-instances: off` 会导致应用无法启动。** YAML 会将其读作布尔值 `false`，从而以 [R4C-MN-006](errors.md#r4c-mn-006) 失败；现在它表示 `off`。不带引号的 `on` 仍会被拒绝，并提示为该值加上引号。
- `resilience4cata-opentelemetry`：绑定注册表会让它一直存活到 `close()`，因此应用丢弃的注册表永远不会被回收，其仪表也会继续上报。现在它被回收后仪表即停止上报（[OpenTelemetry](guides/opentelemetry.md)）。
- `resilience4cata-micrometer`：当已绑定的注册表被回收后，之后绑定的、含有相同 id 实例的注册表永远得不到仪表，其状态一直为 NaN。现在它会接管这些过期的仪表（[Micrometer](guides/micrometer.md)）。

## [1.1.0] - 2026-11-24 :id=1.1.0

自 1.0.0 以来的修复，全部保持兼容：由 1.0.x 注解处理器生成的代码可以继续运行。有一处行为变化：订阅者在第一个元素之后取消的 `Flux`，现在对熔断器算作成功。

### 修复 :id=fixed-1

- **`Flux` 上的 `Pipeline` 丢失了取消信号。** 使用 `FluxPipelineOperator` 且没有重试和降级时，取消订阅（`next()`、`take(1)` 或断开连接的客户端都会这样做）无法到达各策略：舱壁隔离会一直占用槽位，导致后续调用被拒绝，数据源也保持订阅状态。生成的代码不受影响。
- **用 `next()` 或 `take(1)` 读取的 `Flux` 不会在熔断器中留下成功记录。** 这些操作符会在第一个元素之后取消流，而被取消的调用不会被记录，因此在大量健康的流中出现少数失败就可能打开熔断器，以这种方式读取的半开探测也永远无法关闭它。现在，在第一个元素之后取消算作成功（如果该元素很慢则算作慢调用）；在此之前取消仍不记录（[Flux 方法](guides/reactor.md#flux)）。
- **在睡眠时间过长的机器上，同步超时触发过晚。** 在看门狗的短睡眠总是比请求时间更长的环境中（Windows、虚拟化的 macOS），它的时钟会落后，超时可能比配置的时间晚数倍才触发。现在它能跟上真实时间，并且仍然绝不会提前触发（[超时](policies/timeout.md)）。
- 限流器可能使用早于另一线程许可的时钟读数来判断调用，使等待时间多出两次读取之间的间隔；在 `maxWait = 0` 时可能造成不必要的拒绝。
- 接近 `Long.MAX_VALUE` 纳秒的重试延迟不再溢出：完全抖动此前会抛出 `IllegalArgumentException`，而 `maxDuration` 可能放行超出限制的重试。

## [1.0.0] - 2026-11-21 :id=1.0.0

第一个稳定版本。API 与 1.0.0-RC4 相同，从此版本起适用[兼容性](reference/compatibility.md)承诺：在 1.x 内，Java API 保持二进制和源码兼容，由某个注解处理器生成的代码可以在之后每个 1.x 运行时上运行，注解、配置键、集成属性、指标名称和标签、快照 JSON 以及错误代码都会保留。自 1.0.0-RC4 以来只有修复，见下文。如果从 0.13.0 升级，也请阅读各候选版本：1.0.0-RC1 有两处小的 API 改动（一个事件被重命名，两个常量不再是 public），另有四处行为变化：缺失的注解处理器会被报告，而不是让 bean 处于无保护状态（1.0.0-RC2）；熔断器把 `Error` 记录为失败，且不保护任何东西的类型级注解成为编译错误（1.0.0-RC3）；熔断器不再记录方法自身的舱壁隔离 (bulkhead)、限流器或自适应限流的拒绝（1.0.0-RC4）。

### 修复 :id=fixed-2

- `resilience4cata-config` 会忽略 properties 文件或 classpath 资源开头的 UTF-8 字节顺序标记（BOM），因为 Windows 编辑器可能会写入它。此前它被读作第一个键的一部分，该键随后以未知键失败（[R4C-CFG-001](errors.md#r4c-cfg-001)），而给出的建议正是同一个键。
- **注解处理器与 Lombok 的先后顺序不再重要。** 当 resilience4cata 在注解处理器路径上排在 Lombok 之前时，带 `@RequiredArgsConstructor` 的注解类会得到一个调用了已被 Lombok 移除的 `super()` 的子类，而 `@Value` 类不会被报告为 final。现在使用 Lombok 注解的类会在下一轮、即 Lombok 之后处理（[配合 Lombok](guides/annotated-classes.md#with-lombok)）。
- **Eclipse 编译器（ECJ）**（Eclipse、VS Code 以及设置为用 Eclipse 编译的 IntelliJ IDEA 都在使用它）得到的结果与 `javac` 不同：对于类型为泛型类的内部类的参数，实例 id 被命名为 `…(Holder<V>.Inner)` 而不是 `…(Holder.Inner)`（配置键和指标标签因此不同）；当边界包含通配符或交集类型时，合法的泛型降级 (fallback) 被以 [R4C-FB-002](errors.md#r4c-fb-002) 或 [R4C-FB-003](errors.md#r4c-fb-003) 拒绝；对于 `Collection<? super T>` 参数，生成的代码无法编译；生成的字段编号不同。现在注解处理器在两种编译器下写出相同的文件。
- 在 GraalVM 原生镜像中，[R4C-CFG-003](errors.md#r4c-cfg-003)（找不到配置中指定的类）会显示需要添加的 reachability metadata 条目：镜像只能找到为反射注册过的类（[原生镜像](start/installation.md#native-image)）。

### 变更 :id=changed

- `resilience4cata-opentelemetry` 依赖 `io.opentelemetry:opentelemetry-api` 1.62.0（Spring Boot 4.1、Quarkus 3.40 和 Micronaut 5.2 所带的最旧版本），而不是 1.67.0，因此它不再抬高应用的 OpenTelemetry 版本；它的测试在 1.62.0 和最新版本上运行（[OpenTelemetry](guides/opentelemetry.md)）。
- 测试：CI 为一个不使用任何框架的纯 Java 应用构建原生镜像，并在其中运行它的测试，与 Spring Boot、Micronaut 和 Quarkus 的测试并列（[原生镜像](start/installation.md#native-image)）。
- 文档：超时的精度以进程能获得 CPU 为前提，因为在容器 CPU 限制下，被限流的 JVM 只有在再次运行时才会被中断；资源不足的冷启动可能因真实的超时而打开熔断器（[故障排查](guides/troubleshooting.md#breaker-opens-at-startup)）。两者都由试点在 CPU 限制下测量（[基准测试](benchmarks.md#cpu-limit)）。

## [1.0.0-RC4] - 2026-11-14 :id=1.0.0-rc4

1.0.0 的第四个候选版本。API 与 1.0.0-RC3 相同；由[试点](https://github.com/fabiangftech/resilience4cata/tree/main/pilot)发现的一处行为变化：熔断器不再记录方法自身的舱壁隔离、限流器或自适应限流的拒绝。

### 变更 :id=changed-1

- **熔断器从不记录本地限制的拒绝**：无论 `recordOn`、`ignoreOn` 或分类器怎么说，`BulkheadFullException`、`RateLimitExceededException` 和 `AdaptiveLimitFullException` 既不算失败也不算成功（半开探测会拿回它的许可）。一次填满方法自身舱壁隔离的突发流量可能在依赖没有任何失败的情况下打开熔断器，并把每次调用都送到降级：[试点](https://github.com/fabiangftech/resilience4cata/tree/main/pilot)在冷启动时遇到了这个问题。`CallTimeoutException` 和内层熔断器的 `CallNotPermittedException` 仍然算失败。不再需要列出这三种拒绝的 `ignoreOn`。

## [1.0.0-RC3] - 2026-11-05 :id=1.0.0-rc3

1.0.0 的第三个候选版本，在同样四位评审者对 1.0.0-RC2 进行第三次评审之后发布（[1.0 计划](plan-1.0.md#third-review)）。API 与 1.0.0-RC2 相同；两处行为变化：熔断器把 `Error` 记录为失败，以及不保护任何东西的类型级注解成为编译错误。

### 变更 :id=changed-2

- **熔断器默认把 `Error` 记录为失败**：`recordOn` 默认为 `Throwable` 而不是 `Exception`，与 Resilience4j 一致，因此反复出现的 `StackOverflowError` 或 `NoClassDefFoundError` 会打开熔断器，而不是算作成功。设置 `recordOn = Exception.class`（或 `defaults.circuitBreaker.recordOn=java.lang.Exception`）可恢复旧行为。重试不变：`retryOn` 仍默认为 `Exception`。
- **作用不到任何方法的类型级注解是编译错误**，[R4C-APT-007](errors.md#r4c-apt-007)：一个既不声明也不继承任何抽象或 default 方法的接口，或一个所有方法都是 final、static 或 private 的注解类。此前既不生成任何东西，也不报告任何东西，而 Spring Boot 和 Quarkus 随后会报告该类型在没有注解处理器的情况下被编译。
- 只包含 `@PolicyOrder` 的 `@ResiliencePolicy` 组合注解在注解处理器缺失检查中不再被视为策略（[R4C-SPR-006](errors.md#r4c-spr-006)、[R4C-MN-007](errors.md#r4c-mn-007)、[R4C-QK-008](errors.md#r4c-qk-008)）：注解处理器不会为它生成任何东西，因此使用它的 bean 会无故失败。
- R4C-SPR-006、R4C-MN-007 和 R4C-QK-008 的消息显示带 `:<version>` 的注解处理器坐标，在 `annotationProcessor` 上没有 BOM 时需要它。
- Quarkus 扩展的 jar 把它们的自动模块命名为 `dev.resilience4cata.quarkus` 和 `dev.resilience4cata.quarkus.deployment`（`Automatic-Module-Name`），与其他模块一致，而不是使用从文件名推导出的名称。
- 文档：首页和 FAQ 中的候选版本和已冻结的 API；FAQ、安装和故障排查页面中每个框架的注解处理器缺失错误；哪些原生构建经过测试；`-Ar4c.inject=micronaut` 会写入什么；头条数字的单一来源及其注意事项（仅 ARM、熔断器那一行没有失败、同步调用不分配内存）；超时的中断会关闭可中断的 I/O；Spring、Micronaut 和 Quarkus 模块中哪些包属于 API。

## [1.0.0-RC2] - 2026-11-04 :id=1.0.0-rc2

1.0.0 的第二个候选版本。API 与 1.0.0-RC1 相同；唯一的变化是缺失的注解处理器现在会被报告，而不是让 bean 处于无保护状态。

### 变更 :id=changed-3

- **缺失的注解处理器会让启动失败，而不是让 bean 处于无保护状态。** 在 Spring Boot 中，一个 bean 的类（或某个超类）或它实现的某个接口带有 resilience4cata 注解却没有生成的类时，启动会以 [R4C-SPR-006](errors.md#r4c-spr-006) 失败；在 Micronaut 中，这样一个类的 bean 以 [R4C-MN-007](errors.md#r4c-mn-007) 失败；在 Quarkus 中，构建以 [R4C-QK-008](errors.md#r4c-qk-008) 失败。这些注解的保留策略是 `CLASS`，因此此前没有任何东西察觉。`resilience4cata.spring.allow-missing-processor`、`resilience4cata.micronaut.allow-missing-processor` 和 `quarkus.resilience4cata.allow-missing-processor` 可以关闭该检查；被排除的 bean 不受检查。Spring 和 Micronaut 读取 class 文件，因此该检查不在原生镜像中运行。

## [1.0.0-RC1] - 2026-11-02 :id=1.0.0-rc1

1.0.0 的第一个候选版本，在 [1.0 计划](plan-1.0.md)中的评审之后发布：冻结前最后的 API 改动（两处小的不兼容改动：一个事件被重命名，两个常量不再是 public）、关于哪些东西可能增长的更清晰承诺、对照测量结果核实过的文档，以及修复的四处竞争。请在 1.0.0 之前试用它：除非有什么必须修改，1.0.0 会以相同的 API 随后发布，从那时起适用[兼容性](reference/compatibility.md)承诺。

### 新增 :id=added

- [兼容性](reference/compatibility.md)：从 1.0.0 起，在同一主版本内升级时哪些东西保持可用（Java API、生成的代码、配置键、指标名称、端点的 JSON、错误代码），以及哪些东西可能在次版本中增长。
- [HTTP 调用的重试与熔断器](guides/http-calls.md)：一份完整的方案（重试哪些失败、忽略客户端错误的熔断器、每次尝试一个超时、幂等性、如何选择数值），并针对真实的 HTTP 服务器进行了测试。
- 快速入门第 6 步 *See the breaker open*；[策略概览](policies/README.md#glossary)中的术语表；[迁移指南](migrate/from-resilience4j.md)中一张从 Resilience4j 指标名称到 resilience4cata 指标名称的对照表，以及另外三处差异（没有成功或延迟指标、注解类上的 `@Transactional`、对冲请求中的 thread-local）。
- [R4C-RT-003](errors.md#r4c-rt-003) 的消息（没有生成的类）也显示 Maven 的那一行，而不只是 Gradle 的。
- `CircuitBreakerFailureRateBenchmark`：熔断器在每 1 000 次或每 100 次调用中失败一次。在 Graviton3 上四个线程时，每次调用 11.2 和 65.4 ns，而 Resilience4j 约为 1 000 ns（[真实失败率](benchmarks.md#failure-rates)）；在内存分配门禁中为 0 B。
- `SECURITY.md`：受支持的版本以及如何私下报告漏洞（GitHub 私密漏洞报告）。

### 变更 :id=changed-4

- **`CircuitBreakerEvent.CallNotPermitted` 现在是 `CircuitBreakerEvent.Rejected`**，与舱壁隔离、限流器和自适应限流的拒绝事件一致。异常保留其名称 `CallNotPermittedException`。为旧类型注册的监听器必须改用新类型。
- **`HedgeConfig.MIN_ATTEMPTS` 和 `MAX_ATTEMPTS` 不再是 public。** 作为 public 常量，它们会被复制到调用方的 class 文件中，因此之后对边界的修改（2 到 10，记录在 `maxAttempts` 上）不会影响之前编译的代码。
- 承载调用结果的类型参数接受可为 null 的类型（`T extends @Nullable Object`）：`Pipeline`、`PipelineCall`、`FallbackHandler`、`Classifier`，以及生成代码的异步类型。Kotlin 和 NullAway 用户现在可以写 `Pipeline<String?>` 和返回 null 的降级。对 Java 编译器而言没有任何变化。
- `Resilient.create` 声明构造器参数可以为 null（`@Nullable Object...`），而它此前就已接受 null。
- [兼容性](reference/compatibility.md)：增加组件的事件或快照 record 会保留其先前的构造器，因此创建它们的代码仍能编译；在增加策略时，`PolicyConfig` 和 `CallRejectedException` 可能获得新的子类型。
- Micronaut：`ResilienceHealthIndicator` 和 `ResilienceEndpoint` 的 public 构造器标记为 `@Internal`（这些 bean 由 Micronaut 创建；它们不是给你的代码用的）。
- `*Config` builder 的每个 setter 在 Javadoc 中都有主描述；此前 `@return` 会被读作参数文本的一部分。
- 不等待的舱壁隔离和自适应限流用 volatile add 归还槽位，在 Graviton3 上测得它比 release-only add 更快：有空闲槽位的舱壁隔离调用耗时 17.3 ns 而不是 20.5 ns，四个线程时 196 ns 而不是 259 ns（[基准测试](benchmarks.md#release-counters)）。
- 带 `maxWait` 的舱壁隔离用它自己的计数器获取和归还空闲槽位，而不是使用 `java.util.concurrent.Semaphore` 的完整内存屏障原子操作：四个线程时，每次调用 385 → 210 ns（[基准测试](benchmarks.md#bulkhead-semaphore)）。
- `Flux` 方法每个流分配的内存更少：一次尝试是一个对象而不是两个，而在订阅期间就已结束的流作为一个共享的 stage 经过每个策略，而不是每个策略一个已完成的副本（一个策略少 24 B，重试、熔断器、超时和舱壁隔离一起少 104 B），并且最多快 9%（[基准测试](benchmarks.md#flux-ended)）。

### 修复 :id=fixed-3

- 带 `maxWait` 的舱壁隔离：在一个异步调用方等待时，槽位可能一直空闲，直到下一次释放或该调用方自己的 `maxWait` 到期。一次把槽位留给被阻塞的同步调用方的释放（每隔一次都会这样做）可能恰好发生在该调用方的等待未拿到槽位就结束的时候。现在，没有拿到槽位就结束的同步等待会把槽位交给异步调用方。由一个新的 jcstress 测试发现，它在修复前的 145 万次运行中出现过一次。
- 自适应限流：当一次样本写入输掉竞争、使其条带回退到低于已使用的总和时，一个窗口可能被读成 1 ns 并抬高限制。现在该窗口会被丢弃，下一个窗口从当前的总和开始计数。
- 限流器：一个输掉竞争的同步调用方会 park，然后根据 park 之前读取的时钟计算等待时间。它可能等待太久、在 `maxWait = 0` 时许可已经到期却被拒绝，或者把许可的时间算早了。现在它在 park 之后重新读取时钟，测得并没有变慢（[评审修复](benchmarks.md#review-fixes)）。
- 同步超时：调用超时时，即使调用方在 watchdog 中断之前就已被中断，线程的中断标志也会被清除。现在会保留该中断；只清除 watchdog 自己的中断。
- 对冲请求：延迟最多会晚大约两个定时器 tick 才开始（1 ms 的延迟在约 2.1 ms 后才发起对冲）。现在对冲请求的延迟从调用开始时计算，定时器在截止时间醒来，因此对冲请求在 `delay` 加上操作系统唤醒延迟时开始。返回时第一次尝试仍在运行的调用会多读一次时钟。
- 带时间窗口的熔断器（`windowType = TIME`）：秒是从 `System.nanoTime()` 的原点开始编号的，因此在时钟回绕的地方，每个更早的秒看起来都比当前秒更新，调用会丢失，熔断器无法打开。现在秒从窗口创建时开始计数。
- 注解处理器：由注解处理器的模糊测试发现的、无法编译的生成代码：泛型类的内部类（`Holder<Integer>.Inner`）会连同外部类型参数一起打印；带递归边界的类型变量（`<T extends Comparable<T>>`）会显式传递；抛出类型变量的方法上的降级处理器；接口的静态降级方法可以从子接口和实现它的类中找到。
- 注解处理器：两个冲突的组合注解（R4C-CMP-002）会在每个使用它们的方法上报告，而不只是在具有相同签名的多个方法中的第一个上报告；R4C-ATR-010 分别检查每个注解，因此一个方法可以设置 `windowType = TIME` 和 `windowDuration`，而它的类型设置 `windowSize`。
- Spring：带有自定义限定符注解（以 `@Qualifier` 为元注解、不是 `@Inherited`，例如 `@Email`）的注解类无法通过该限定符注入，因为它的 bean 是生成的子类，而子类不会重复类上的注解。现在这类限定符会被复制到 bean 定义中，包括 AOT。
- Javadoc：每个模块的页面标题都用的是 Gradle 项目名（"core 0.13.0 API"，Quarkus 扩展则是 "runtime 0.13.0 API"），javadoc.io 也是这样显示的。现在它们带有 artifact 名称："resilience4cata-core 0.13.0 API"、"resilience4cata-quarkus 0.13.0 API"。

## [0.13.0] - 2026-10-31 :id=0.13.0

1.0 之前计划的最后一批 API 改动，全部在熔断器和配置中。由 0.12.0 生成的代码必须用此注解处理器重新编译：熔断器的比率现在是 `double`。

### 变更 :id=changed-5

- **熔断器的比率是 `double`**，与 API 中其他所有小数一样：`@CircuitBreaker` 和 `CircuitBreakerConfig`（及其 builder）上的 `failureRateThreshold` 和 `slowCallRateThreshold`，以及 `CircuitBreakerSnapshot` 中的 `failureRate`/`slowCallRate`。使用 `50`、`50f` 或 `50.0` 的源码仍能编译；把它们存入 `float` 的代码需要强制转换或改用 `double`。熔断器仍像以前一样以单精度比较它们。
- **时间窗口有自己的长度：`windowDuration`**（`"60s"`，整数秒；默认 60 s），位于 `@CircuitBreaker`、`CircuitBreakerConfig` 以及键 `*.circuitBreaker.windowDuration` 上。`windowSize` 现在只表示计数窗口的调用数。每种窗口忽略另一个属性；在同一个注解上，`windowType = TIME` 时设置大小、`COUNT` 时设置时长，或两者都设置，是新的注解处理器错误 [R4C-ATR-010](errors.md#r4c-atr-010)。原先从 `windowSize` 获取长度（默认 100 s）的时间窗口必须改为设置 `windowDuration`。
- 带短前缀（`r4c.`、`R4C_`）的系统属性或环境变量，如果其第一段不是我们的（`defaults`、`configs`、`instances`、`profiles`）也不接近其中之一，会被当作其他工具的配置而忽略，而不是以 [R4C-CFG-001](errors.md#r4c-cfg-001) 中止应用：三个字母的前缀很容易被共用。我们自己的键的拼写错误（`R4C_DEFUALTS_…`）以及带长前缀 `resilience4cata.`/`RESILIENCE4CATA_` 的未知键仍会失败（[键的形式](reference/configuration.md#key-forms)）。

## [0.12.0] - 2026-10-31 :id=0.12.0

1.0 之前的 API 评审：此版本在许多地方改变了 API，因此升级前请阅读**变更**。由 0.11.0 或更早版本生成的代码必须用此注解处理器重新编译。

### 变更 :id=changed-6

- **生成的代码和 core 遵循同一条兼容性规则。** 在同一主版本内，由注解处理器 `1.a` 生成的代码可以在每个满足 `b >= a` 的 core（及 reactor）`1.b` 上运行，因此用 resilience4cata 编译的库在应用升级 resilience4cata 后仍能工作。每个生成的构造器首先检查运行时是否足够新，如果不够新，立即以新错误 [R4C-RT-021](errors.md#r4c-rt-021) 失败，而不是在生产环境中第一次失败时抛出 `NoSuchMethodError`。由 0.11.0 或更早版本生成的代码必须用此注解处理器重新编译。
- 生成的代码所调用的策略工厂（`ResilienceRegistry.retryPolicy(...)`、`circuitBreakerPolicy(...)` 等，共 16 个方法）不再是 `ResilienceRegistry` 的 public 方法：生成的代码调用 `dev.resilience4cata.generated.Policies`，它只供生成的代码使用。在代码中构建策略请使用 `Pipeline`。
- **七个 `*Config` 类型（`RetryConfig`、`CircuitBreakerConfig`……）是 final 类而不是 record**，只能通过 `builder()` 和 `toBuilder()` 构建，因此可以在次版本中添加属性而不留下旧的构造器。访问器（`config.maxAttempts()`）、`equals`、`hashCode` 和 `toString` 不变；public 构造器和 record 模式（`case RetryConfig(var …)`）已移除，0.2.0 的 `RetryConfig` 构造器也已移除。它们共享新的 sealed 接口 `PolicyConfig`，`LockedOverride.config()` 现在返回它而不是 `Record`。
- **`ResilienceSnapshot` 是 final 类而不是 record**，只由注册表创建：以后增加的策略类型只是多一个列表，而不是一个新的构造器。它的访问器不变；0.3.0 到 0.11.0 的构造器已移除。新增：`only(id)`（具有某个 id 的实例）、`isEmpty()`，以及 `toMap()`（以普通 map 和 list 表示的快照，结构有文档说明）（[快照](guides/observability.md#snapshots)）。0.2.0 的 `RetrySnapshot` 构造器已移除。
- Spring Boot actuator 端点和 Micronaut 管理端点都提供 `ResilienceSnapshot.toMap()`，因此它们的 JSON 相同。对于 Spring Boot，时长现在是 ISO-8601 字符串，非有限的比率为 `null`，与 Micronaut 已有的写法一致；`ResilienceEndpoint` 的操作返回一个 `Map`。
- **实例 id 只有一个名字：`id()`。** 快照（`CircuitBreakerSnapshot.instance()` 等）、事件（`ResilienceEvent.instance()`）、异常和 `FallbackContext`（`instanceId()`）以及 `LockedOverride` 现在都使用 `id()`，与 handle 和 `Pipeline` 一致。在端点的 JSON 中，键 `instance` 现在是 `id`。（指标标签在这里不变。）
- `AdaptiveLimitHandle.inFlight()` 和 `AdaptiveLimitSnapshot.inFlight` 现在是 `concurrentCalls`，与舱壁隔离以及指标 `r4c.adaptivelimit.concurrent.calls` 一致。
- `AdaptiveLimitEvent.LimitChanged.windowLatencyNanos`（一个 `long`）现在是 `windowLatency`，与事件中其他所有时长一样是 `Duration`。
- `LimitExceededException` 重命名为 `AdaptiveLimitFullException`，读起来不再像限流器的 `RateLimitExceededException`。
- 四种拒绝（`CallNotPermittedException`、`BulkheadFullException`、`RateLimitExceededException`、`AdaptiveLimitFullException`）继承新的 `CallRejectedException`，因此一个 `catch` 就能处理全部。
- **指标的 `instance` 标签现在是 `name`**，在 `resilience4cata-micrometer` 和 `resilience4cata-opentelemetry` 中都是如此。Prometheus 会给它抓取的每个序列加上它自己的 `instance` 标签（目标的 host:port），并把我们的标签重命名为 `exported_instance`；按 `instance` 过滤的仪表板和告警必须改用 `name`。名称保持点分形式（Micrometer 的风格），熔断器的比率保持百分比，现在作为固定约定写入文档（[指标名称](guides/observability.md#metric-names)）。
- `OpenTelemetryMetrics.bindTo` 的行为与 `MicrometerMetrics.bindTo` 一致：再次绑定同一个注册表不做任何事（此前会把每个 gauge 报告两次），在 `close()` 之后绑定也不做任何事（此前会抛出 `IllegalStateException`）。
- `PipelineOperator`（用于 `Mono`）重命名为 `MonoPipelineOperator`，与 `FluxPipelineOperator` 并列。
- **profile 不再压过更强的来源。** 在同一来源中，活动 profile 下的键仍然优先于没有 profile 的同一个键，但环境变量或系统属性现在优先于文件中的 profile 键，与 Spring Boot 和 Quarkus 一致：当 profile 设置了同一个键时，运维人员的 `R4C_…` 变量不再被忽略。
- Spring Boot：Java API 是包 `dev.resilience4cata.spring`（`ResilienceRegistryCustomizer`、`ResilienceHealthIndicator`、`ResilienceEndpoint`）。`ResilienceSpringProperties` 移到了 `dev.resilience4cata.spring.autoconfigure`，自动配置类的 `@Bean` 方法不再是 public：那里的约定是自动配置类的名称（用于 `exclude`）、bean 名称以及 `resilience4cata.spring.*` 属性名称，这些都没有变。
- **策略注解上的 `enabled` 是 `boolean`**（`@Retry(enabled = false)`），而不是字符串 `""`、`"true"` 和 `"false"`；不写它仍表示继承。针对其他字符串的错误 [R4C-ATR-006](errors.md#r4c-atr-006) 已退役：现在由编译器检查该值。
- 只为了另一个 resilience4cata 模块才是 public 的成员（注册表对策略构造器和配置的使用、`PolicyEnvironment`、事件分发、`Pipeline` 为 reactor 模块提供的流式支持、Micronaut 的 `AnnotatedClassesFilter`）标记为 `@Internal`，API 兼容性检查会排除标记为 `@Internal` 或 `@Experimental` 的内容。只有测试使用的策略构造器不再是 public。

### 修复 :id=fixed-4

- Spring Boot：以 `r4c.` 开头的键（配置参考文档中记载、Micronaut 和 Quarkus 也会读取的短前缀）曾被静默忽略；现在它们像 `resilience4cata.` 键一样被读取。`r4c.spring.*` 会被跳过，与 `resilience4cata.spring.*` 一样。
- `r4c.hedge.failed` 的描述是 "Calls through a hedge that failed"：它统计经过对冲请求的每一次失败调用，无论是否发起了对冲。

## [0.11.0] - 2026-10-30 :id=0.11.0

### 新增 :id=added-1

- **`resilience4cata-reactor`：Project Reactor 的 `Mono`。** 返回 `Mono<T>`（或 `Mono<? extends T>`、`Mono<?>`）的注解方法会以异步形式获得每个策略，包括对冲请求：订阅之前什么都不运行，每次订阅是一次调用，每次尝试都会再次调用该方法，订阅者的 `Context` 会到达每次尝试和降级，取消订阅会经过每个策略取消调用（熔断器不记录任何东西，舱壁隔离和自适应限流释放它们的槽位）。降级可以返回值本身或它的 `Mono`。生成的 `Mono` 通过了 Reactive Streams TCK，并且没有任何策略会阻塞 Reactor 线程（用 BlockHound 检查过）。对于无法加注解的代码，`PipelineOperator.of(pipeline)` 通过 `Mono.transform` 应用一个 `Pipeline`。需要 Reactor 3.6 或更高版本。没有该模块就编译的 `Mono` 方法是新错误 [R4C-ASY-003](errors.md#r4c-asy-003)。已加入 BOM。见 [Project Reactor](guides/reactor.md)。
- **`resilience4cata-reactor`：Reactor 的 `Flux`。** 返回 `Flux<T>` 的注解方法会获得适配流的策略：每次尝试都重新订阅方法的 `Flux`，其元素在到来时带着订阅者的需求和上下文送给订阅者。第一个元素会提交（commit）这次调用：重试和降级只在它之前起作用，之后的失败会原样到达订阅者。`@Timeout` 在每个元素上重新开始计时（到第一个元素、两个元素之间、到结束），而不是每个元素一个定时器；熔断器的慢调用和自适应限流的样本是到第一个元素的时间；舱壁隔离在整个流期间占用其槽位；限流器每次尝试取一个许可。降级返回一个 `Flux` 或一个值。在 `Flux` 方法上，`@Hedge`、分类器以及改变顺序的 `@PolicyOrder` 是新错误 [R4C-ASY-004](errors.md#r4c-asy-004)。生成的 `Flux` 通过了 Reactive Streams TCK 的每一条规则。对于无法加注解的代码，使用 `FluxPipelineOperator.of(pipeline)`（带对冲请求或分类器的 pipeline 是新错误 [R4C-RT-020](errors.md#r4c-rt-020)）。见 [Project Reactor](guides/reactor.md#flux)。
- 当调用立即返回一个已完成的 stage 时，熔断器、舱壁隔离、限流器和自适应限流会直接记录它，没有链接也没有回调，与超时已有的做法一样：对于结果立即可用的异步方法，每次调用的字节数和纳秒数更少。
- Spring Boot、Micronaut 和 Quarkus 对事务内被重试或对冲的方法的检查（[R4C-SPR-004](errors.md#r4c-spr-004)、[R4C-MN-005](errors.md#r4c-mn-005)、[R4C-QK-002](errors.md#r4c-qk-002)）也能看到 `Mono` 方法，它们的重试和对冲层位于生成代码的一个 lambda 中。
- 原生镜像现在在 CI 中构建和测试：Quarkus 指南的应用作为 GraalVM 原生镜像（GraalVM for JDK 25），提供它的注解类、接口和 REST 客户端包装器、就绪检查和指标，以及注解类和被包装接口的 `Mono` 与 `Flux` 方法，无需额外的原生配置。见 [Quarkus](guides/quarkus.md#native)。

### 变更 :id=changed-7

- [R4C-ASY-002](errors.md#r4c-asy-002) 不再涵盖 Reactor 的 `Mono` 和 `Flux`，它的消息会建议配合 `resilience4cata-reactor` 使用它们。

### 修复 :id=fixed-5

- **库稍后运行的工作不再继承另一个请求的线程上下文，也不再在重新加载后继承旧的类加载器。** 库的定时器线程每个 JVM 只启动一次，以前会继承启动它们的那个请求的上下文类加载器和 `InheritableThreadLocal` 值；它们启动的虚拟线程（重试的下一次尝试、对冲请求、触发的异步超时）又会继承这些，而排队的舱壁隔离调用会继承释放其槽位的那次调用的这些值。因此在热重新加载之后（Quarkus dev 模式、Spring DevTools），之后的尝试会使用旧应用的类加载器运行（该类加载器也因此留在内存中），而在使用可继承的安全上下文或 MDC 时，一次尝试可能带着另一个请求的值运行。现在定时器线程持有库的类加载器，不继承任何东西；工作以发起调用的线程的上下文类加载器运行，没有继承的值。
- **异步限流器不再 park 调用线程。** 在争夺许可失败后，它曾 park 1 ns 再重试，而事件循环线程（Netty、Reactor）绝不能这样做；现在它会立即重试。在单个限流器上竞争激烈时，这会让每次调用的开销更高（[基准测试](benchmarks.md)）。同步限流器不变。
- **Spring Boot：应用的 post-processor 所依赖的 bean 不再悄悄失去其策略。** Spring 会在注册 resilience4cata 的 post-processor 之前创建这样的 bean，因此实现了注解接口的 bean 从未被包装。现在启动会以新错误 [R4C-SPR-005](errors.md#r4c-spr-005) 失败，该错误会指出那个 post-processor，并建议延迟注入该 bean（`ObjectProvider` 或 `@Lazy`）。
- **Micronaut：注解类的 `@EventListener` 方法每个事件只收到一次。** Micronaut 会为每个监听方法生成一个适配器 bean，类和它的生成子类各一个；两者都调用子类 bean，因此每个事件都被处理了两次。现在类的适配器会与类本身一起被排除。

## [0.10.0] - 2026-10-27 :id=0.10.0

### 新增 :id=added-2

- **`@PolicyOrder`：改变方法上策略的顺序。** 它列出若干层（`Layer.RETRY`、`TIMEOUT`、`CIRCUIT_BREAKER`……），这些层按列出的顺序一起移到其中最外层所在的位置；其他层保持默认顺序。`@PolicyOrder({TIMEOUT, RETRY})` 在整个重试循环外加一个超时，`{CIRCUIT_BREAKER, RETRY}` 让熔断器对每个操作只记录一个结果，`{BULKHEAD, RETRY}` 在多次重试之间一直占用槽位。可用于方法、类型或组合注解；在编译期决定，降级始终在最外层。把一个层列出两次是 [R4C-ORD-001](errors.md#r4c-ord-001)。没有它，或者它不改变任何东西时，生成的代码不变。`Pipeline` 保持默认顺序。见[改变顺序](policies/README.md#changing-the-order)。

## [0.9.0] - 2026-10-27 :id=0.9.0

### 变更 :id=changed-8

- **响应式返回类型是编译错误。** 带有弹性注解、返回惰性响应式类型（`Flow.Publisher` 或 Reactive Streams 的 `Publisher`，因此包括 Reactor 的 `Mono` 和 `Flux`；Mutiny 的 `Uni` 和 `Multi`；RxJava 的类型）的方法以前会被当作同步方法编译，因此策略只保护了构建管道这一步，失败从不会被重试或超时。现在它是 [R4C-ASY-002](errors.md#r4c-asy-002)；请改为返回 `CompletionStage`（`uni.subscribeAsCompletionStage()`、`mono.toFuture()`）。
- **Quarkus：两种悄悄失去保护的情况现在是构建错误。** 作为注解类的 Quarkus REST 资源（[R4C-QK-007](errors.md#r4c-qk-007)：Quarkus REST 自己创建资源类，因此生成的子类从未提供这些端点），以及带 `@LookupIfProperty`/`@LookupUnlessProperty` 的受保护 bean（[R4C-QK-006](errors.md#r4c-qk-006)：该条件被忽略了）。

### 修复 :id=fixed-6

- **Quarkus：注解类上的构建期条件。** 注解类上的 `@IfBuildProfile`、`@UnlessBuildProfile`、`@IfBuildProperty` 和 `@UnlessBuildProperty` 曾被忽略，因此生产实现和测试实现都会成为 bean（注入有歧义）。现在被禁用的类的生成子类也会被禁用。
- **在生成的子类上重复的注解。** 重写方法不再重复 Bean Validation 注解（`@Valid` 和约束，Bean Validation 会从被重写的方法继承它们）：一个 `@Valid` 返回值曾导致 Quarkus 应用无法启动（HV000131）。在 Quarkus 中，没有值的类注解 `@Named` 现在以注解类的名字命名 bean，而不是 `…$Resilient`，并且 `@Scheduled` 方法不再让 bean 有两个作用域。在 Micronaut 中，一个标记为 `@ResilientBean`、自身也带有策略注解的类会把其接口的策略应用两次（一个 `@Retry(maxAttempts = 3)` 产生了 9 次尝试）；现在它的生成子类不再被再次包装。
- **Micronaut：注解类的子类和 `@MockBean`。** 生成的子类带有 `@Replaces(C.class)`，而 Micronaut 会把它应用到每个可赋值给 `C` 的 bean：`C` 的一个注解子类（带有自己的 `@Named`）会消失，而测试中 `C` 的 `@MockBean` 会导致完全没有 bean。现在生成的子类不再带有它；集成只排除 `C` 自己的 bean 定义，而不带限定符的 `@Inject C` 仍然优先选择 `C$Resilient` 而不是子类 bean，就像 Micronaut 优先选择精确类型一样。注解类的 `@MockBean` 桩不再被报告为未受保护（R4C-MN-004）。在没有 `-Ar4c.inject=micronaut` 的情况下编译、但 Micronaut 仍然创建了的生成子类（其构造器重复了 `@Inject`）会以 R4C-MN-004 报告，因为它缺少类的作用域。
- **Spring Boot。** actuator 端点在 Web 应用中无法启动（`Failed to extract parameter names`），因为该模块编译时没有 `-parameters`。一个注解类如果既有无参构造器又有其他构造器，且都没有 `@Autowired`，会无法启动（`No default constructor found`）：现在 Spring 使用无参构造器的生成镜像，就像它在类上使用该构造器一样。泛型注解接口的 bean（`NameLookup implements Lookup<String>`）在被包装后又可以按其类型参数注入了（`@Autowired Lookup<String>`）。
- **受保护 bean 的测试替身。** Spring：注解接口的 `@MockitoSpyBean` 在 Spring 创建之后被包装，因此 `verify(spy)` 以 `NotAMockException` 失败；现在 spy 不再被包装，就像 `@MockitoBean` 的 mock 一样。Quarkus：注解接口上的 `@InjectMock` 失败，因为它的包装器是 `@Singleton`；现在 `@ApplicationScoped` bean 的包装器也是 `@ApplicationScoped`（位于客户端代理之后）。Micronaut：`@ResilientBean` 接口（一个 `@Client`）的 `@MockBean` 会以 R4C-MN-003 使启动失败；现在它按原样注入。
- **配置与消息。** `down-when-open`（Spring、Micronaut 和 Quarkus）会在逗号处拆分像 `com.acme.Charges#charge(java.lang.String,int)` 这样的派生 id，因此匹配不到任何熔断器；现在各部分会被重新拼接起来。使用急切单例的 Micronaut 会因 `@Prototype`、`@Refreshable` 或请求作用域的受保护 bean 的实例键而以 R4C-CFG-013 使启动失败；存在此类 bean 时默认值保持 `warn`。无效的 `resilience4cata.micronaut.verify-instances` 值现在以新代码 [R4C-MN-006](errors.md#r4c-mn-006) 失败。Spring 针对 R4C-CFG-013 的启动报告会为启动后创建的 bean（`@Lazy`、prototype、pipeline）指向 `resilience4cata.spring.verify-instances=warn`。R4C-CFG-013 的消息在列出已知实例之前结束句子，并写作 `<interface or class>`。消息和元数据中的链接使用站点当前的 URL。
- **Quarkus：泛型注解接口。** 对注解的 `Store<T>` 执行 `@Inject Store<String>` 会得到未受保护的 bean，因为包装器的 bean 类型是原始类型；现在它们保留 bean 的类型参数。
- **注解类保留参数名。** 生成子类的重写方法曾把参数命名为 `p0`、`p1`……，因此在被调用方法上读取名称的功能在注解类上会失效：Spring 的 `@Cacheable(key = "#sku")` 和 `@EventListener(condition = "#event.urgent")`、Spring MVC 和 Micronaut 控制器中没有显式名称的 `@PathVariable`/`@QueryValue`，以及 Bean Validation（它会把一个违规报告两次：`p0` 和真实名称）。现在它们保留声明的名称；只有名为 `java`、`dev`、静态降级的包名第一部分，或以 `r4c$` 开头的参数会被重命名。从 class 文件继承的方法只有在该类以 `-parameters` 编译时才有名称，Spring Boot、Micronaut 和 Quarkus 的构建都会这样做。接口包装器不变。

## [0.8.0] - 2026-10-27 :id=0.8.0

### 新增 :id=added-3

- **`resilience4cata-all`：一个依赖即可开始。** 它依赖 `core`、`annotations` 和 `config`，自身没有代码，因此项目只需要两行：`resilience4cata-all`，以及注解处理器路径上的注解处理器（任何依赖都无法把注解处理器带到那里）。它也是一个 JPMS 模块 `dev.resilience4cata.all`，以传递方式读取这三个模块。单独的模块和 BOM 照常可用；使用它们可以让注解保持 compile-only。已加入 BOM。见[简便方式](start/installation.md#the-short-way)。

## [0.7.0] - 2026-10-27 :id=0.7.0

### 新增 :id=added-4

- **`@Retry`、`@Hedge`、`@CircuitBreaker`、`@RateLimiter`、`@AdaptiveLimit`、`@Timeout` 和 `@Bulkhead` 上的 `locked = true`**（以及 `Pipeline.Builder.locked()`）：注解设置的值优先于每实例覆盖（`instances.<id>.*`、`retryInstance(...)` 等）；注解没有设置的属性仍然可以覆盖。被忽略的覆盖会在实例创建时记录日志，由新的 `registry.lockedOverrides()`（`LockedOverride`）列出，并由 `config.verifyInstances(registry)` 以新代码 [R4C-CFG-014](errors.md#r4c-cfg-014) 逐键报告，因此 Spring、Micronaut 和 Quarkus 的集成会在启动时报告它。只有带 `locked = true` 的注解的生成代码会改变。见[锁定实例](guides/configuration.md#locking-an-instance)。
- 构建会用 japicmp 把每个已发布模块的公共 API 与上一个版本进行对比（`./gradlew apiCompatibility`，属于 `check` 的一部分）。

## [0.6.0] - 2026-10-26 :id=0.6.0

### 新增 :id=added-5

- **`@AdaptiveLimit`：自适应并发限制**，同步和异步，可用于接口、注解类和 `Pipeline.adaptiveLimit(...)`。与舱壁隔离一样，它限制同时运行的调用数，但会根据调用的延迟在 `minLimit` 和 `maxLimit` 之间自行调整这个上限：`GRADIENT2`（默认）把每个窗口的平均延迟与长期平均值比较，`AIMD` 每次加一，在发生丢弃后按 `backoffRatio` 退避；两者都遵循 Netflix 的 concurrency-limits。`dropOn` 中的失败（默认是来自内层策略的 `CallTimeoutException`、`BulkheadFullException` 或 `TimeoutException`）算作一次丢弃；其他失败不算样本。限制已满时立即以新的 `LimitExceededException` 拒绝。限制在每个窗口（`window`，至少 `minWindowSamples` 次调用）重新计算一次，由窗口结束后完成调用的线程执行：没有后台线程，成功路径上没有内存分配。它位于限流器和超时之间：`Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead`。新 API：`AdaptiveLimitConfig`（包含算法的调优参数：`smoothing`、`rttTolerance`、`longWindow`、`queueSize`、`backoffRatio`）、来自 `registry.adaptiveLimit(id)` 的 `AdaptiveLimitHandle`、`registry.snapshot().adaptiveLimits()` 中的 `AdaptiveLimitSnapshot`、`AdaptiveLimitEvent.Rejected` 和 `LimitChanged`、注册表 builder 的 `adaptiveLimitDefaults`、`adaptiveLimit`、`adaptiveLimitOverlay` 和 `adaptiveLimitInstance`、外部配置键 `*.adaptiveLimit.*`，以及两个指标模块中的指标 `r4c.adaptivelimit.limit`、`r4c.adaptivelimit.concurrent.calls`、`r4c.adaptivelimit.limit.changes` 和 `r4c.calls.rejected{kind=adaptivelimit}`。新的注解处理器错误 [R4C-ATR-009](errors.md#r4c-atr-009)。见[自适应限流](policies/adaptive-limit.md)。
- 当某次尝试被自适应限流拒绝时，`@Hedge` 会停止发起新的尝试，与熔断器打开或舱壁隔离已满时一样。
- **Quarkus Dev UI 卡片**：在 dev 模式下，`/q/dev-ui` 显示注册表中的每个策略实例（熔断器、舱壁隔离、限流器、自适应限流、带预算的重试和对冲请求、超时），每两秒刷新一次，并且可以强制打开、禁用或重置熔断器。仅限 dev 模式：在测试或生产构建中，该卡片及其 JsonRPC 服务都不存在。见 [Quarkus，Dev UI](guides/quarkus.md#dev-ui)。

### 修复 :id=fixed-7

- Spring Boot 的 `resilience4cata` actuator 端点在报告单个实例时（`/actuator/resilience4cata/{instance}`）现在也包含对冲请求。

## [0.5.0] - 2026-10-26 :id=0.5.0

### 新增 :id=added-6

- **`resilience4cata-micronaut`：Micronaut 5 集成**（Micronaut 5.2 或更高版本，Java 25，无 AOP）。标记为 `@ResilientBean(I.class)` 的注解接口的 bean 在 Micronaut 创建它们时会被替换为生成的包装器（只以 `I` 暴露；bean 上 Micronaut 的 AOP 在策略内部运行）；注解类由 Micronaut 借助下面的新注解处理器选项创建为其生成子类，或从 `@Factory` 返回；一个 `@Context` 的 `ResilienceConfig` 和 `ResilienceRegistry`，按 Micronaut 的优先级从应用的 `resilience4cata.*` 配置进行配置（YAML 列表、带引号的派生实例 id、每个错误键所在的文件），支持 `BeanCreatedEventListener<ResilienceRegistry.Builder>` 定制以及 `EventListener`/`Ticker` bean；存在 `resilience4cata-micrometer` 和 `MeterRegistry` 时提供 Micrometer 指标；一个 `resilience4cata` 健康指示器（熔断器打开时仍为 UP，除非被列出）；一个只读、敏感的 `resilience4cata` 管理端点。新的错误领域 `R4C-MN-001`…`005`：误用 `@ResilientBean` 会中止启动；`R4C-MN-004` 报告未以其生成子类创建的注解类；`R4C-MN-005` 会阻止注解类中被重试或对冲的 `@Transactional` 方法（每次尝试都会共享同一个事务），除非设置 `resilience4cata.micronaut.allow-retry-inside-transaction=true`。该模块以 `--release 25` 编译，这是 Java 21 基线的唯一例外。见 [Micronaut](guides/micronaut.md)。
- **注解处理器选项 `-Ar4c.inject=micronaut`**：注解类的生成子类会重复类的类型级注解（作用域、限定符、`@Requires`……）并添加 `@Replaces(C.class)`，使 Micronaut 用它来代替该类创建 bean。没有该选项时生成的代码不变。`micronaut` 以外的值，或编译 class path 上没有 Micronaut 时使用该选项，是新错误 [R4C-APT-006](errors.md#r4c-apt-006)。
- **`resilience4cata-quarkus`：Quarkus 扩展**（Quarkus 3.40 或更高版本；运行时 `resilience4cata-quarkus` 和部署 `resilience4cata-quarkus-deployment`，都在 BOM 中；无拦截器）。在构建期，注解类会被否决（veto），它的生成子类成为带有该类的作用域、限定符和构造型的 bean（拦截对 `this` 的调用）；注解接口的 bean 通过一个合成的包装 bean 注入（`Resilient.of`，一个带有 bean 限定符的已启用 alternative；对该类的 `@Inject` 仍得到原 bean）；带包装器的 REST 客户端接口（`@RegisterRestClient`）会得到一个包裹 `@RestClient` bean 的 `@Default` bean。一个 `ResilienceRegistry` bean（`@DefaultBean`）通过 SmallRye Config 从应用的 `resilience4cata.*` 键进行配置（Quarkus profile、表达式、`R4C_` 变量、每个错误键所在的文件和行），在启动时创建，因此配置错误会中止启动；会应用 `ResilienceRegistryCustomizer`、`EventListener` 和 `Ticker` bean；`resilience4cata.instances.<id>` 键在启动时校验（`quarkus.resilience4cata.verify-instances`，默认 `warn`）。配合 `quarkus-micrometer` 和 `resilience4cata-micrometer` 提供 Micrometer 指标（否则使用 OpenTelemetry），一个 `resilience4cata` 就绪检查（熔断器打开时仍为 UP，除非在 `quarkus.resilience4cata.health.down-when-open` 中列出）。新的错误领域 `R4C-QK-001`…`005`，在 Quarkus 构建应用时报告：[R4C-QK-001](errors.md#r4c-qk-001)（同一方法上同时有 MicroProfile/SmallRye Fault Tolerance 和 resilience4cata 注解）、[R4C-QK-002](errors.md#r4c-qk-002)（注解类中被重试或对冲的 `@Transactional` 方法；`quarkus.resilience4cata.allow-retry-inside-transaction`）、[R4C-QK-003](errors.md#r4c-qk-003)（多个互不相关的注解接口）、[R4C-QK-004](errors.md#r4c-qk-004)（注解类上的 producer）、[R4C-QK-005](errors.md#r4c-qk-005)（没有无参构造器的 normal-scoped 注解类）。见 [Quarkus](guides/quarkus.md)，其中包括 SmallRye Fault Tolerance 与 resilience4cata 的对比。指南应用的原生镜像是手动构建的（GraalVM for JDK 25.0.4、Quarkus 3.40.1、macOS arm64），它提供了注解类、接口包装器、REST 客户端包装器、就绪检查和指标，无需额外的原生配置；原生构建不属于 CI。在原生镜像中，配置错误给出的是 SmallRye 在那里报告的来源（对于构建期固定的值是 `Runtime Values`），而不是文件和行。

### 修复 :id=fixed-8

- 在手动 ticker（`ManualClock`）上，下一次延迟为零（`delay = 0`，或抽到零的 `FULL` 抖动）的异步重试会在一个新的虚拟线程上而不是在时钟上开始下一次尝试，因此测试会与之竞争。现在策略在 ticker 上调度的每个延迟（包括零）都经过 `Ticker.schedule`，并在下一次推进时钟的线程上运行（包括 `advance(Duration.ZERO)`），与失败后的对冲请求已有的做法一样。没有 ticker 时没有任何变化。现在可以用零延迟调用 `Ticker.schedule`。
- GraalVM 原生镜像中的 [R4C-RT-003](errors.md#r4c-rt-003) 现在还会指向注解处理器写出的 reachability metadata（`META-INF/native-image/dev.resilience4cata.generated/<class>/reachability-metadata.json`），它必须位于镜像构建的 class path 上。在 JVM 上该消息不变。

## [0.4.0] - 2026-10-25 :id=0.4.0

### 新增 :id=added-7

- **`@Hedge`：对冲请求**，用于异步方法和 `Pipeline.executeAsync`。当调用在 `delay`（默认 1 s）内没有产生结果时，或者以 `hedgeOn` 中的失败而失败时立即，再次发起同一个调用；第一个成功的结果胜出，其他尝试会经过每个内层策略被取消（熔断器不记录任何东西，舱壁隔离释放槽位）。`maxAttempts`（2 到 10，默认 2）、`abortOn`，以及默认开启的对冲预算（`budgetPercent` 10、`budgetMinHedgesPerSecond` 1、`budgetWindow` 10 s）。在顺序中占据重试的位置，不能与 `@Retry` 组合使用。新增 `HedgeConfig`、`HedgeCancelledException`、`HedgeEvent`（`Hedged`、`Won`、`Skipped`、`Failed`）、`ResilienceSnapshot.hedges()` 中的 `HedgeSnapshot`、`ResilienceRegistry.Builder.hedgeDefaults/hedge/hedgeOverlay/hedgeInstance`、`Pipeline.Builder.hedge(...)`，以及配置键 `defaults.hedge.*`（以及 `configs.…`、`instances.…`、环境变量）。仅用于幂等调用。新错误 [R4C-HDG-001](errors.md#r4c-hdg-001)（同步方法）、[R4C-HDG-002](errors.md#r4c-hdg-002)（与 `@Retry` 同用）、[R4C-ATR-008](errors.md#r4c-atr-008)（`maxAttempts`）、[R4C-RT-018](errors.md#r4c-rt-018)（带对冲请求的 `Pipeline.execute`）和 [R4C-RT-019](errors.md#r4c-rt-019)（同一个 pipeline 中有重试和对冲请求）；[R4C-ATR-007](errors.md#r4c-atr-007) 现在也涵盖对冲预算。见[对冲请求](policies/hedge.md)。
- **`resilience4cata-spring`：Spring Boot 4 集成**（Spring Boot 4.1 或更高版本，一个模块，无 AOP）。注解类以其生成子类代替该类实例化（bean 类型、名称、限定符和构造器注入不变；拦截对 `this` 的调用）；注解接口的 bean 在其他所有 post-processor 之后用 `Resilient.of` 包装；一个 `ResilienceRegistry` bean（`@ConditionalOnMissingBean`）按 Spring 的优先级从应用的 `resilience4cata.*` 属性进行配置，支持 `[…]` 实例 id 和 YAML 列表，在启动时校验并给出每个错误键所在的文件和行；会应用 `ResilienceRegistryCustomizer`、`EventListener` 和 `Ticker` bean；存在 `resilience4cata-micrometer` 时提供 Micrometer 指标；一个 `resilience4cata` 健康指示器（熔断器打开时仍为 UP，除非被列出）；一个只读的 `resilience4cata` actuator 端点；IDE 元数据；故障分析；可与 Spring AOT 配合使用。新的错误领域 `R4C-SPR-001`…`004`；当注解类中被重试或对冲的方法同时是 `@Transactional` 时（每次尝试都会共享同一个事务），`R4C-SPR-004` 会中止启动，除非设置 `resilience4cata.spring.allow-retry-inside-transaction=true`。见 [Spring Boot](guides/spring.md)。
- `resilience4cata-config`：`ResilienceConfig.Builder.precedenceByOrder()`（来源按添加顺序排列优先级，适用于有自己优先级规则的框架）和 `properties(description, map, origins)`（每个键在哪里设置，用于消息）。
- **无需配置的 GraalVM 原生镜像。** 注解处理器为每个生成的类写出 `META-INF/native-image/dev.resilience4cata.generated/<generated class>/reachability-metadata.json`（每个类一个文件，因此注解处理器保持 isolating），为 `Resilient.of` 和 `Resilient.create` 注册其 public 构造器，条件是注解接口（对注解类则是 `Resilient`）。`core` 附带一个 `native-image.properties`，在运行时初始化其后台线程的持有者。需要 GraalVM for JDK 23 或更高版本；见[原生镜像](start/installation.md#native-image)。
- 注解类：生成的构造器保留类构造器的参数名，并复制构造器和参数上的注解（保留策略为 `CLASS` 或 `RUNTIME`），因此依赖注入容器在 `C$Resilient` 上也能看到 `@Inject`/`@Autowired`、`@Qualifier`、`@Named`、`@Value`……。`@ConstructorProperties` 中注册表的名称排在最前。生成的重写方法会重复被重写方法及其参数上的注解（resilience4cata 自己的注解和组合注解除外），因此只在被调用方法上读取的注解（例如 Spring 下 Jakarta 的 `@Transactional` 或拦截器绑定）不再丢失。见[构造器与依赖注入](guides/annotated-classes.md#constructor-injection)。

### 变更 :id=changed-9

- `ResilienceEvent` 允许一个新的 sealed 子类型 `HedgeEvent`：对 `ResilienceEvent` 的 `switch` 如果没有 `default` 分支，在处理 `HedgeEvent`（或加上 `default`）之前将无法编译。`ResilienceSnapshot` 有一个新组件 `hedges()`；其五个列表的构造器被保留。从现在起，这个层次结构会在次版本中增长：保留一个 `default` 分支，或者监听特定的 record 类型（[可观测性](guides/observability.md#listening)）。
- 为注解类生成的子类不再是 `final`（`public class C$Resilient extends C`），因此框架可以代理它（用于 `@Transactional` 的 CGLIB、作用域代理）。接口包装器仍然是 `final`。
- 注解类的生成构造器按类构造器的参数名命名其参数（`registry, warehouse` 而不是 `registry, p0`）；名为 `registry` 的参数会使注册表参数变为 `r4c$registry`，名为 `java` 或 `dev` 的参数变为 `r4c$p<index>`。调用不受影响。

### 修复 :id=fixed-9

- **取消异步调用现在会取消其内部的工作。** 取消由生成的异步方法或 `Pipeline.executeAsync` 返回的 future（或以 `CancellationException` 异常完成它），以前只会停在最外层的策略：内部的 stage 继续运行，舱壁隔离的槽位一直被占用直到该 stage 结束，重试可能发起另一次尝试，降级可能运行，熔断器可能把取消记录为失败。现在取消会穿过每一层一直传到方法的 stage：重试取消其退避并不再发起新的尝试，超时取消其截止时间，舱壁隔离在 stage 结束时立即释放槽位（对 `CompletableFuture` 是立刻），排队的调用离开队列，熔断器不记录任何东西（半开许可会被归还），降级不运行，仍在等待限流器许可的调用会归还许可且永不开始。取消不会发布任何事件。到期的超时以同样的方式取消内层，因此超时内的舱壁隔离在调用超时时会释放其槽位。每个策略每次调用返回的对象数与以前相同。见[异步方法](guides/async.md#cancellation)。

## [0.3.0] - 2026-10-25 :id=0.3.0

### 新增 :id=added-8

- `resilience4cata-opentelemetry`：OpenTelemetry 指标。`OpenTelemetryMetrics.create(openTelemetry)`、`register(builder)` 和 `bindTo(registry)` 把拒绝、重试、放弃、熔断器状态转换、超时和降级报告为由事件驱动的计数器，把熔断器、舱壁隔离和限流器的状态报告为每次采集时从快照读取的异步 gauge，因此之后创建的实例也会被包含。指标名称与 Micrometer 模块相同。只有指标，尚无追踪。见 [OpenTelemetry](guides/opentelemetry.md)。
- `resilience4cata-junit`：一个 JUnit Jupiter 扩展。`@ResilienceTest` 向每个测试（及其 `@BeforeEach`/`@AfterEach` 方法）注入一个新的 `ManualClock` 和一个跟随它的 `ResilienceRegistry`，支持 `seed`、`startNanos` 以及可选的 `failOnPendingTasks`；`@ConfigureRegistry` 方法用于定制 builder。需要 JUnit 5.10 或更高版本，包括 JUnit 6。见[测试](guides/testing.md#junit)。
- 按时完成的异步 `@Timeout` 调用现在会立即从 `ManualClock` 上移除其截止时间，因此 `pendingTasks()` 不再把它计入，直到截止时间过去。
- **`resilience4cata-micrometer`**：三行代码即可获得 [Micrometer 指标](guides/micrometer.md)（`MicrometerMetrics.create(meterRegistry)`、`register(builder)`、`bindTo(registry)`）：来自事件的计数器（拒绝、熔断器状态转换、重试、放弃、超时、降级），以及每个熔断器、舱壁隔离和限流器的 gauge，包括绑定之后创建的实例。取代了可观测性指南中手写的方案。
- `ResilienceRegistry.onInstanceCreated(Runnable)`：每创建一个新的策略实例运行一次动作（在包装服务或构建 pipeline 时，从不在调用期间），使指标桥接可以为之后创建的实例注册指标（[可观测性](guides/observability.md#diy)）。
- **重试预算**，用于 `@Retry`、`RetryConfig` 和 `Pipeline`：`budgetPercent`、`budgetMinRetriesPerSecond` 和 `budgetWindow` 把一个重试实例（由其所有调用方共享）的重试次数限制为每秒的保留量加上窗口内调用数的一个百分比，使故障不会放大对出故障依赖的负载。`budgetPercent` 用于开启它，默认保留量为每秒 10 次重试。默认关闭；不开启时，生成的代码和成功路径不变。被拒绝的重试会发布带新原因 `BUDGET_EXHAUSTED` 的 `RetryEvent.GaveUp`；`RetrySnapshot.budget()` 显示窗口。配置键 `defaults.retry.budget…`（以及 `configs.…`、`instances.…`、环境变量）；新的注解处理器错误 [R4C-ATR-007](errors.md#r4c-atr-007)。[重试预算](policies/retry.md#retry-budget)
- 测试模块的错误代码：`R4C-TK-001`…`003`（`ManualClock`、`ResilienceTesting`）和 `R4C-JU-001`…`003`（JUnit 扩展），每个都在[错误参考](errors.md#tests-testkit-r4c-tk)中记录了修正方法。
- Micrometer 和 OpenTelemetry 模块为每个指标提供相同的描述（Prometheus 的 `# HELP` 文本），由一个测试检查。

## [0.2.0] - 2026-10-25 :id=0.2.0

修复 0.1.0 的三个行为 bug；其中一个修复改变了一个默认值（见"从 0.1.0 升级"）。

### 从 0.1.0 升级 :id=upgrading-from-010

- 让 `core` 和 `processor` 保持同一版本（导入 BOM）。由 0.2.0 注解处理器生成的代码需要 0.2.0 的 `core`；由 0.1.0 生成的代码仍能链接到 0.2.0 的 `core`，后者只增加了 API。
- `abortOn` 默认不再包含 `CallNotPermittedException`，因为打开的熔断器现在会自己停止重试。如果你只是为此才在自己的 `abortOn` 列表中加入了 `CallNotPermittedException`，请移除它；保留它也没有害处。
- 被打开的熔断器拒绝的调用不再能被任何配置（包括分类器）重试。

### 变更 :id=changed-10

- **打开的熔断器总是会停止重试。** 以 `CallNotPermittedException` 被拒绝的调用永远不会被重试，无论 `abortOn`、`retryOn` 或分类器怎么说，在生成的代码（同步和异步）和 `Pipeline` 中都是如此。在 0.1.0 中，这来自默认的 `abortOn`，因此设置你自己的 `abortOn` 会悄悄去掉它，重试会一直调用一个打开的熔断器。`RetryConfig.DEFAULTS.abortOn()`（以及 `defaults.retry.abortOn`）现在为空；[重试](policies/retry.md#attributes)。

### 修复 :id=fixed-10

- 对于没有任何同级降级方法接收的失败（例如唯一的同级方法接收 `Exception` 时的 `Error`），不再发布 `FallbackEvent.Applied`：这样的失败会被重新抛出，现在该事件表示确实运行了一个降级。
- 错误消息 R4C-ATR-001 和 R4C-CFG-002 只说明一次时长语法，而不是两次；R4C-CFG-007 在 "Known configs" 之前结束句子。
- 注解处理器的模块描述符也声明了 `AnnotationClaimer`，因此当注解处理器与其他注解处理器一起从注解处理器模块路径运行时，`-Xlint:processing` 保持安静。
- 在 `@CircuitBreaker`（或 `Pipeline` 的熔断器）内部抛出异常的分类器现在会立即结束调用，同步和异步都是：外层的重试不再把分类器的异常当作方法的失败进行分类并重试（这可能打开熔断器，转而抛出 `CallNotPermittedException`）。熔断器记录一次失败，重试发布一次 `GaveUp(CLASSIFIER_FAILED)`，降级或调用方得到分类器的异常。见[抛出异常的分类器](guides/classifiers.md#a-classifier-that-throws)。

## [0.1.0] - 2026-10-25 :id=0.1.0

第一个版本。Maven Central 上 groupId `dev.resilience4cata` 下的 artifact：`resilience4cata-annotations`、`-core`、`-processor`、`-config`、`-testkit` 和 `-bom`。需要 Java 21 或更高版本；在 Java 21 和 25 上测试。

### 新增 :id=added-9

- **六个以注解形式提供的策略**，按固定顺序应用（Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → 方法）：[`@Retry`](policies/retry.md)（固定或指数退避、抖动、时间预算）、[`@CircuitBreaker`](policies/circuit-breaker.md)（计数或时间窗口、慢调用、半开探测）、[`@RateLimiter`](policies/rate-limiter.md)（GCRA、可选等待）、[`@Timeout`](policies/timeout.md)（在调用方线程上，基于中断）、[`@Bulkhead`](policies/bulkhead.md)（信号量、可选等待）和 [`@Fallback`](policies/fallback.md)（同级方法或处理器类）。
- **注解处理器**，为每个带注解的类型生成一个普通 Java 类：包装一个实现的 `<Interface>$Resilient`（`Resilient.of`），或继承[注解类](guides/annotated-classes.md)的 `<Class>$Resilient`（`Resilient.create`）。注解及其值在编译期检查；在 Gradle 中支持 isolating 增量处理；可与 `-Xlint:all -Werror` 配合使用。
- 返回 `CompletionStage<T>` 或 `CompletableFuture<T>`（也包括 `? extends T`）的**异步方法**，每个策略都有非阻塞形式（[异步方法](guides/async.md)）。
- 使用 `@ResiliencePolicy` 的**[组合注解](guides/bundles.md)**，以及类型级注解。
- **[分类器](guides/classifiers.md)**，根据结果或异常判断调用是否失败，以及 `@Retry(onExhaustedResult = THROW)`。
- **[`Pipeline`](guides/pipeline.md)**，一个具有相同策略和顺序的编程式 API，用于无法加注解的代码。
- **`ResilienceRegistry`**：默认值、命名配置、每实例覆盖、按 `name` 共享的实例，以及熔断器（`forceOpen`、`disable`、`reset`）、限流器和舱壁隔离的 handle。
- **[事件与快照](guides/observability.md)**：在注册表 builder 上注册的监听器，每个实例的只读快照。
- **`resilience4cata-config`**：来自 properties 文件、classpath 资源、环境变量和系统属性的[外部配置](guides/configuration.md)，支持 profile、`extends`、每实例覆盖、严格校验和 `verifyInstances`（[键](reference/configuration.md)）。
- **`resilience4cata-testkit`**：[`ManualClock` 和 `ResilienceTesting`](guides/testing.md)，用于对基于时间的行为进行确定性测试。
- **`resilience4cata-bom`**，用于统一各模块的版本。
- 每个编译期和运行时错误都带有**[错误代码](errors.md)**（`R4C-…`），每个都记录了修正方法。
- CI 中的**内存分配门禁**：每条同步成功路径和拒绝路径都分配 0 字节（[性能](performance.md)）。

### 已知限制 :id=known-limitations

- 没有 Spring、CDI、Micrometer 或 OpenTelemetry 集成模块；没有 YAML 配置。
- 策略顺序无法更改，注册表构建后配置也无法更改。
- 在接口风格中，实现对自身的调用不会被拦截。
- 只支持 `CompletionStage<T>` 和 `CompletableFuture<T>` 作为异步类型（不支持 `? super T`，不支持响应式类型）。

[0.1.0]: https://github.com/fabiangftech/resilience4cata/releases/tag/v0.1.0
