# 1.0 计划

发布 1.0.0 之前还剩哪些工作（来自四位独立评审者对 0.13.0 的评审），以及哪些工作留到 1.0 之后。

本页是工作计划：每项任务说明要改什么、改在哪里、如何检查，因此无需其他上下文即可从这里继续工作。在完成某项任务的同一个 pull request 中将其标记为完成（`[x]`），并附上该 pull request 的编号。

## 状态 :id=status

- Maven Central 上的版本：0.13.0，候选版本 1.0.0-RC1、1.0.0-RC2、1.0.0-RC3 和 1.0.0-RC4，以及 1.0.0。`main` 为 1.1.0-SNAPSHOT。
- 基于 AWS 运行结果的性能计划已完成（pull request #53–#59，见[基准测试](benchmarks.md)）。
- Javadoc 标题带有 artifact 名称（#60）；javadoc.io 已为全部 11 个模块同步。
- 第 1 至第 3 阶段已完成（#62 至 #65）。
- 同样四位评审者的第二次评审（2026-11-02，#65 之后）：API 设计者、性能评审者和新手认为可以发布 1.0；采用者要求先发布 1.0.0-RC1，并在 1.0 之前做三件事（注解处理器缺失时明确失败、在 ARM 上运行一次 jcstress、一份安全策略）。他们提出的小型文档修正在第 4 阶段中。
- 1.0.0-RC1 已发布到 Maven Central（2026-11-02）。`main` 为 1.0.0-SNAPSHOT，API 基线为 1.0.0-RC1，因此 API 已冻结：任何与该候选版本不兼容的改动都会让构建失败。
- SonarCloud 的主分支仍叫 `master`，因此对 `main` 的分析从未出现在项目概览中。现在它叫 `main`；其 21 个可靠性问题已经过审查（#68），并且只分析已发布的库（#69）。
- 1.0.0-RC2（2026-11-04）在注解处理器未运行时会让启动或构建失败。
- 对 RC2 的[第三次评审](#third-review)（2026-11-05）：三位评审者认为可以发布 1.0.0；其修正改变了两处行为，因此先发布 1.0.0-RC3。
- 1.0.0-RC3（2026-11-05）包含第三次评审的修正。安装代码片段现在显示该版本（所有者的决定）。
- `main` 只通过检查全部通过的 pull request 接受改动（管理员在发布提交时绕过该规则），且每个候选版本都有一个 GitHub release；1.0.0-RC3 为 *Latest*，与安装代码片段一致（2026-11-06）。
- 1.0.0-RC4（2026-11-14）：试点发现，冷启动时熔断器会因方法自身已满的舱壁隔离 (bulkhead) 而打开；现在熔断器从不记录本地限制的拒绝（#100）。API 与 RC3 相同。安装代码片段显示该版本，且它为 *Latest*。
- 1.0.0（2026-11-21）：第一个稳定版本，API 与 RC4 相同。安装代码片段显示该版本，且它为 *Latest*。`main` 为 1.1.0-SNAPSHOT，API 基线为 1.0.0，因此兼容性承诺以它为基准进行检查。

## 评审 :id=the-review

2026-11-02，四位评审者各从一个视角阅读了仓库。没有人运行构建。他们的发现是有待核实的论断，而非事实：下面每项任务都从确认该发现开始。

| 评审者 | 结论 |
|---|---|
| 一位正在决定是否采用的 Resilience4j 用户 | 尚不适合一般生产环境；可以在一个服务上试点。工程质量扎实；风险在于项目年轻且只有一位维护者。 |
| 一位 API 设计者 | 接近可以冻结；有五处现在代价很低、以后将无法更改的修正。 |
| 一位性能与并发的怀疑者 | 工程严谨，没有确认的 bug；头条数据是最佳情况；从未在 x86 上测量；四处可疑的漏洞。 |
| 一位弹性库新手 | 大约 15 分钟就保护了一个调用；有几处过时或相互矛盾的说法；部分页面过于偏向内部实现。 |

他们看重的、在修改其余部分时要保留的东西：

- 编译期错误，带有稳定的 `R4C-…` 代码，每个代码都有一个错误示例及其修正的文档，而 Resilience4j 在运行时通过反射解析 `fallbackMethod`。
- 每次调用的开销接近于零，且公开测量：持平和回退都会展示，公平性调整写在每个基准测试的 Javadoc 中，Resilience4j 获得无栈异常且没有死代码，内存分配门禁在 CI 中运行。
- 生成的代码是普通 Java：堆栈跟踪经过 `X$Resilient`，而不是代理链，并且在子类风格中会拦截自调用。
- 一份认真的迁移指南（配置表、默认值差异、何时继续使用 Resilience4j），以及由 japicmp 和生成代码规则支撑的明确兼容性承诺。
- 一个可以扩展的 API：配置是带 builder 的 final 类，`ResilienceSnapshot` 是一个类，handle 和 `FallbackContext` 由库实现，拒绝共享一个 sealed 的无栈基类，枚举在文档中说明会增长，`@Internal`/`@Experimental` 受到强制执行，`PipelineCall` 保留了精确的受检异常。
- 就评审者所读到的而言，无锁代码是可靠的：熔断器的 `quiet` 握手、超时的 `RUNNING → IDLE` acquire CAS、`BulkheadSemaphore` 的释放与重新检查、等待者的 `count++ → offer → drain` 顺序、`LinkedFuture` 的取消标记，以及倾向于更少重试的重试预算。
- 快速入门（一个经过测试的程序、它的输出、"刚才发生了什么"）和安装页面（`annotationProcessor` 上的 BOM、旧版 Maven、JDK 23 的发现机制、IDE、Lombok 顺序）。en/es 目录树一致，西班牙语读起来自然。

## 第 1 阶段：API，冻结之前 :id=phase-1-api-before-the-freeze

一个 pull request。这里的所有内容都会改变公共 API，这在 1.0 之前没有代价，在 1.0 之后则不可能。每项改动都有一行 changelog（en/es），如果涉及生成的代码，还要检查 `examples` 仍然通过。

- [x] **1.1 可为 null 的类型参数。** 在 `@NullMarked` 下，`Pipeline<T>`、`PipelineCall<T, X>`、`FallbackHandler<T>` 和 `Classifier<T>` 把 `T` 限定为非 null，然而 `PipelineRunnable extends PipelineCall<@Nullable Void, X>` 已经打破了该限定，`void` 方法的降级 (fallback) 也返回 null。在这四个类型上声明 `<T extends @Nullable Object>`，并在其他接受用户结果类型的地方同样声明（`AsyncCall`、`Recovery`、`PipelineRunnable`：需检查）。检查：`./gradlew build`；注解处理器的测试用 `-Werror` 编译生成的代码。
  - **结果：**（#62）已完成：这四个类型，加上 `AsyncCall`、`Recovery` 和各策略的 `executeAsync`；reactor 的 `Mono`/`Flux` 类型保持非 null（Reactor 禁止 null 元素）。
- [x] **1.2 会增长的 record。** `reference/compatibility.md` 说事件和快照 record 可能增加组件，但增加组件会移除规范构造器（二进制不兼容，japicmp 会失败）。决定：要么不支持构造事件和快照（在 `compatibility.md` 和这些 record 的 Javadoc 中说明），要么每增加一个组件都保留旧的构造器（说明这一点，并把规则加入 CLAUDE.md）。后者对用户的监听器测试更友好；优先选择它。
  - **结果：**（#62）已完成：保留旧构造器（`compatibility.md`、`ResilienceEvent`、CLAUDE.md）。
- [x] **1.3 会增长的 sealed 类型。** 把 `PolicyConfig` 和 `CallRejectedException`（它们的 `permits` 随每个新策略增长）加入 `compatibility.md` 和它们 Javadoc 中的"可能增长"列表，与事件一样：对它们做穷尽的 `switch` 需要一个 default 分支。
  - **结果：**（#62）已完成（`compatibility.md`、两个类的 Javadoc）。
- [x] **1.4 拒绝事件使用同一个名字。** `CircuitBreakerEvent.CallNotPermitted` → `Rejected`，与舱壁隔离、限流器和自适应限流的事件一致。异常保留原名。同时让注解枚举 `LimitAlgorithm` 与 `AdaptiveLimitConfig.Algorithm` 对齐（其他枚举 `Backoff`、`Jitter`、`WindowType` 与其配置枚举一致）。更新指标模块、`guides/observability.md`（en/es）中的事件表，以及 `examples/src/test/.../docs/` 下的代码片段。
  - **结果：**（#62）事件已完成。`LimitAlgorithm` 保留：见[设计](design.md#api-decisions)。
- [x] **1.5 会被内联的常量。** `HedgeConfig.MIN_ATTEMPTS`/`MAX_ATTEMPTS` 是编译期常量：它们会被复制到调用方的 class 文件中，因此提高上限 10 不会影响之前编译的代码。把它们设为 private（边界已有文档），或者不再是常量。
  - **结果：**（#62）已完成：private。
- [x] **1.6 小的重命名。** `ResilienceRegistry.Builder.listen(String instance, …)` → `id`。`Resilient.create(type, registry, Object... args)`：参数可能为 null，因此用 `@Nullable Object...`。派生 id 的格式在 `ResilienceRegistry` 和 `ResilienceEvent` 中是 `<interface>#…`，在 handle 中却是 `<type>#…`：使用 `<type>`（注解类）。
  - **结果：**（#62）已完成。
- [x] **1.7 做出决定，不一定改动。** 把每个决定写进 `design.md`：
  - `@Timeout` 没有 `name`（其他六个都有）。以后添加属于增量改动。
  - builder 的列表参数（`retryOn(List<Class<? extends Throwable>>)`）不接受 `List<Class<? extends Exception>>`；`Collection<? extends Class<? extends Throwable>>` 可以接受它，但只能在 1.0 之前改。
  - 参数顺序：`Resilient.of(type, delegate, registry)`、`create(type, registry, args…)`、`Pipeline.builder(registry, id)`。
  - Micronaut 的 `ResilienceHealthIndicator` 和 `ResilienceEndpoint` 有 public 构造器：将它们标记为 `@Internal`。
  - `AdaptiveLimitConfig.Builder` 的调优 setter 只有一行 Javadoc，没有主描述：补上。
  - 快照的形态不同：重试、超时和对冲请求的快照内嵌 `config`；舱壁隔离、限流器和自适应限流复制几个字段（`maxConcurrent`、`permits`、`minLimit`/`maxLimit`）；熔断器的快照两者都没有。选择一种形态，或写下为什么不同。
  - `CircuitBreakerSnapshot` 用 `-1` 表示"尚无比率"：保留这个哨兵值（有文档），或计划一个 `hasRates()` 访问器。
  - `Pipeline.Builder.retry(String)` 与 `retry(Consumer)` 并存（每个策略都有同样的一对）：`retry(null)` 有歧义。接受它，或重命名其中一个。
  - `LockedOverride.policy` 是一个取值集合封闭的 `String`：枚举会更安全；该字符串与配置键一致。
  - 有两个名为 `ExhaustedResult` 的枚举（`Pipeline.ExhaustedResult` 和注解中的那个，后者还有 `INHERIT`）：同时导入两者很别扭。接受或重命名。
  - **结果：**（#62）已完成：每一点都在[设计](design.md#api-decisions)中做了决定；Micronaut 的构造器为 `@Internal`，全部 51 个 builder setter 都有主描述。

整个阶段的检查：`./gradlew build :stress:compileJava`、`./gradlew :quarkus:it:check`、`./gradlew :core:apiCompatibility`（报告只列出预期的改动，别无其他）。

## 第 2 阶段：文档 :id=phase-2-documentation

一个 pull request，en 和 es 一起。其中大多是过时的数字或与代码矛盾的句子。

- [x] **2.1 过时的性能数字。**
  - 首页（`docs/{en,es}/README.md`）把图表标注为 "resilience4cata 0.1.0"，并显示舱壁隔离为 20.5 ns；根目录的 `README.md` 也是如此。当前值：17.3 ns（`benchmarks.md#release-counters`）。
  - `performance.md`：头条的舱壁隔离一行（20.5 / 264 ns，"持平"）早于 17.3 / 195.5 ns；"acquire/release … 23.2 → 9.3 ns" 与 volatile `getAndAdd` 释放相矛盾；主表是用 0.1.0 测量的。
  - `faq.md` 的 "How fast is it?" 把 1.9 ns（4 个线程）与 Resilience4j 的 89.2 ns（1 个线程）放在一起。使用同一行。
  - 根目录 `README.md` 引用的是四个线程下的熔断器（Resilience4j 为 832 ns），而 FAQ 用的是一个线程（89 ns）：README、首页、FAQ 和 `performance.md` 必须引用相同的行。
  - 决定是否用 1.0 的代码在 AWS 上重跑头条表格（`infra/bench.sh`），让每个引用的数字都有单一来源。
  - **结果：**（#63）已完成：舱壁隔离的行取自 [release counters](benchmarks.md#release-counters)（17.3 / 195.5 ns），用于 `performance.md`、首页（图例和日期也已更新）和 README，后者现在也引用单线程下的熔断器。FAQ 那句话是对的：熔断器在一个和四个线程下都是 1.9 ns。用 1.0 代码重跑头条表格留给 2.5 的 AWS 运行。
- [x] **2.2 诚实的头条。**
  - 熔断器在四个线程下的 1.9 ns 只在窗口中没有失败时成立（`quiet` 路径）。从大约 1 % 的失败开始，每次调用都会写共享游标："mixed"，274 对 910 ns。在头条旁边说明这一点。
  - 说明 Resilience4j 在每次调用时更新其内置指标，而 resilience4cata 不保留每次调用的计数器，这是差异的一部分。
  - 超时那一行（22 对 8842 ns）比较的是 watchdog 与 executor 交接：把它移出加粗的头条，或加以限定。
  - "每次调用一对时钟读取，无论有多少个策略"对于策略栈来说是错的（限流器、自适应限流、计时的熔断器、带 `maxDuration` 的重试各自都会读时钟）。改正它。
  - 基准测试在闭环中测量平均时间，这会掩盖尾部延迟：在引用有竞争的限流器和舱壁隔离的地方说明这一点（其竞争失败者会 park），直到有百分位运行结果为止。
  - x86 从未被计时：在 `performance.md` 中说明（"x86-64 pending" 已在那里；让它在头条旁边保持可见）。
  - 熔断器的计数窗口在竞争下是近似的（一次过时的 opaque `quiet` 读取可能在失败之后紧接着跳过记录一次成功；相同结果跳过槽位写入的做法可能在窗口回绕后重排写入）。把这一点写进 `policies/circuit-breaker.md` 和 `architecture.md`。
  - **结果：**（#63）已完成：熔断器的 quiet 路径、Resilience4j 的每次调用重试计数器（在其 2.4.0 jar 中核实：`RetryImpl` 中的 `LongAdder` 字段；未对其熔断器作此断言）、闭环平均值、仅 ARM、时钟读取那一行，以及近似的计数窗口（两处竞争都在代码中核实）。超时那一行已经加了限定。
- [x] **2.5 支撑诚实头条的基准测试。** 在 "success" 和 "mixed" 旁边加一个真实失败率（0.1 % 和 1 %）下的熔断器用例，一个和四个线程，Resilience4j 使用相同的失败率。在 AWS 上运行，并在 2.2 中引用。
  - **结果：**（#64）已完成：`CircuitBreakerFailureRateBenchmark`（0 B，在内存分配门禁中），在 8 台 Graviton3 机器上以 `-f 6` 与 success 和十分之一失败的用例一起运行（[真实失败率](benchmarks.md#failure-rates)）。四个线程下：0.1 % 时 11.2 ns，1 % 时 65.4 ns，而 Resilience4j 约为 1 000 ns。已在 `performance.md` 中引用。头条表格的其余部分（用 0.1.0 测量）没有重跑：它保留了自己的来源和日期，之后测量的行（舱壁隔离、熔断器）引用各自的运行。
- [x] **2.3 矛盾之处。**
  - `start/how-it-works.md` 的 "Policy order"："The order is not configurable yet"（自 `@PolicyOrder` 以来就是错的）。
  - 策略页面的要点条写着 "x of 7"；实际有 8 个策略。
  - 根目录 `README.md` 说"名称在 1.0 之前仍可能改变"，而 FAQ 说"不再计划 API 改动"。保留 FAQ 的说法。
  - `policies/retry.md` 的 "Small print" 为 `enabled` 引用了 R4C-ATR-006：该代码已退役。
  - `errors.md` 的前缀表没有 `R4C-ORD` 行。
  - `guides/troubleshooting.md` [R4C-RT-004]：按当前规则重写（core 可以比注解处理器新；更旧的 core 是 RT-021）。
  - `start/installation.md` 的 artifact 表：annotations 那一行缺少 `@Hedge`、`@AdaptiveLimit`、`@PolicyOrder`。
  - `policies/circuit-breaker.md` 的生成代码示例显示 `50.0f`；生成的代码输出 `50.0`。从 `examples/build/generated/...` 刷新它。
  - 首页标签写着 "Sync and CompletionStage"：加上 `Mono`/`Flux`。
  - FAQ 的路线图写着 "YAML configuration: v1"，但 Spring、Micronaut 和 Quarkus 已经能读 YAML：说明所指的是什么（纯 Java 的 YAML 文件），或删掉它。
  - **结果：**（#63）已完成。"x of 7" 是对的（Retry 和 Hedge 共用一层）：现在写的是 "layer x of 7"，返回类型列出了 `Mono` 和 `Flux`。
- [x] **2.4 新手路径。**
  - 快速入门第 6 步 "See the breaker open"：降低 `minimumCalls`，展示 `OPEN`、`CallNotPermittedException` 和降级给出的回答（代码片段在 `examples` 中测试）。
  - 一篇指南 "Retry and circuit breaker for an HTTP call"：哪些异常要重试或中止、内部的 `@Timeout`、熔断器的 `name`、合理的数值、幂等性。两个侧边栏中都加入新页面。
  - 一份简短的术语表，作为 `policies/README.md` 中的定义或单独一页：jitter 和 "full jitter"、半开与探测、实例 id 与派生 id、注册表、舱壁隔离、对冲请求、无栈异常、直线代码，以及 ABI 检查（生成代码示例中的 `Abi.require`）。
  - 策略页面：把生成代码块移到 "How it behaves" 下方（或折叠起来）。
  - R4C-RT-003：消息、`errors.md` 和安装页面的 "Verify that it works" 只展示了 Gradle；加上 Maven。
  - `guides/spring.md`：Maven 标签页添加了 `spring-boot-configuration-processor`，却没有版本也没有理由；解释它或删掉它。把面向维护者的细节（在 4.1.0 和 4.1.2 上测试过的版本、AOT 内部机制、读取字节码的事务检查）移到最后的 "Under the hood" 一节。事务一节（R4C-SPR-004）位于 "Configuration" 之前：把 "Configuration" 放在前面。
  - 侧边栏：把 "Project" 分组标注为面向贡献者。
  - `errors.md` 在一页上约有 3,200 行：在顶部加一个提示（用代码搜索，前缀表链接到每个领域），如果站点生成器和 `ErrorCodeTest` 允许，也可以按领域拆分。
  - Maven：`annotationProcessorPaths` 会取代对其他所有注解处理器（Lombok、MapStruct）的发现，因此加入我们的注解处理器可能会悄悄丢掉它们的。检查 `start/installation.md` 是否在 Maven 代码片段旁清楚地说明了这一点，并给出修正方法（把它们全部列出）。
  - 会让来自 Resilience4j 的人感到意外的默认值：在 `migrate/from-resilience4j.md` 中列一个简短清单（并从 FAQ 链接过去）。评审者提到的候选项：重试默认从 200 ms 开始以带抖动的指数方式退避；限流器立即拒绝（`maxWait = 0`）；对于注解类，`@Transactional` 位于策略之外，且启动检查会拒绝它，因此必须重构代码。还有评审者的一个论断：对熔断器来说 `Error` 算作成功：先在 `CircuitBreakerPolicy` 和生成的层中核实；如果属实，写入文档（并在第 1 阶段决定这是否是正确的行为）；如果不属实，则无需处理。
  - 指标名称：从 Resilience4j 迁移的用户必须重做仪表板和告警（`r4c.*`）。在迁移页面中加一张从 Resilience4j 指标名到我们指标名的对照表。
  - Resilience4j 有而我们没有的东西，在迁移页面中集中到一处（部分已在 "when to stay" 下）：线程池舱壁隔离、缓存、RxJava、Kotlin、热重新配置（按决定不提供）、自动的 open → half-open 转换、事件端点、成功与延迟指标，以及对冲请求不会把调用方的上下文带到对冲的尝试中。写之前先在代码中逐项核实。
  - **结果：**（#63）已完成，其中有三点是有意保留的：生成代码块仍放在代码片段旁边（并排查看正是 `compare` 组件的意义所在，而且它的窗格已经可以滚动）；Spring 指南的细节留在原处（post-processor 顺序、AOT 和原生镜像的限制是面向用户的；只简化了版本那一行）；事务那一段留在 "Annotated classes" 下，因为它适用于那里。`Error` 的论断属实（`recordOn` 默认为 `Exception`），已有文档，现在也从 FAQ 链接过去。

检查：`./gradlew :examples:test --tests '*DocsTest*' :site:test`，并用 `./gradlew :site:serve` 查看改动的页面。2.5 是一项基准测试改动（单独的 pull request，带一次 AWS 运行）。

## 第 3 阶段：可疑的 bug :id=phase-3-suspected-bugs

每个确认的 bug 一个 pull request。对每一个：先写一个能复现它的测试（确定性的 seam 或 jcstress，绝不依赖时序），看到它失败，然后修复，再对该测试做变异检查。如果无法复现，就在本页写明原因并关闭该任务。

- [x] **3.1 舱壁隔离：有空闲槽位，却留下一个排队的异步等待者。** `BulkheadPolicy.exit`（约第 323 行）：当 `hasQueuedThreads()` 且 `yieldToSync()` 时，它释放后直接返回，而不排空异步等待者。如果它让位给的同步线程随后超时、被中断，或输给一个插队的调用方，槽位就会一直空闲，而一个异步等待者在等待，直到下一次调用；如果没有流量，那个等待者会在 `maxWait` 时被拒绝。通过 `enqueueForTest`/`drainForTest` 和信号量进行测试。可能的修复：让位之后，如果信号量仍有许可且没有排队线程拿走它，就执行排空。目前 jcstress 不覆盖让位给同步线程的交接：在 `BulkheadWaitersStress`（或一个新类）中加一个用例并做变异检查。
  - **结果：**（#65）已确认并修复。新的 jcstress 用例 `BulkheadWaitersStress.YieldToSyncThatGivesUp` 在 145 万次运行中出现过一次"空闲槽位 + 排队等待者"。现在，没有拿到槽位就结束的同步等待会排空异步等待者，仅在拒绝路径上。修复后它在 default 模式下通过（约 230 万次运行）；变异检查就是修复前的那次运行。
- [x] **3.2 自适应限流：不一致的样本。** `AdaptiveLimitPolicy`（约第 281 行）：一个条带的 `SUM` 和 `COUNT` 是分开的有损写入，因此竞争可能保留其中一个而丢失另一个；一个倒退的条带（线程在读和写之间被抢占）可能使 `sum - usedSum` 为负，被钳制为 1 ns 的 RTT，从而把 Gradient2 推高。最小修复：负数或零的增量跳过该窗口，而不是变成 1 ns；更好的做法是一致地写入 sum 和 count（一次打包写入，或一个序列号）。用 `(id, config, env, stripes)` 构造器和 seam 进行测试；把这个条带竞争加入 `AdaptiveLimitStress`，它目前不覆盖这种情况。
  - **结果：**（#65）已确认并修复。`aStripeThatWentBackIsNotReadAsAOneNanosecondWindow`（seam `loseLatencyForTest`）在修复前把窗口读成了 1 ns。现在，汇总后的 count 或 sum 小于已用值时会丢弃该窗口并重新同步。未加入 jcstress：它无法让线程在有损读和写之间暂停。
- [x] **3.3 限流器：park 之后过时的 `now`。** `RateLimiterPolicy`（约第 134 行）：`now` 在循环之前只读一次；在一次失败的 CAS 和 1 ns 的 park（在 Linux 上是几十 µs）之后，等待时间是根据旧时间计算的：调用方睡得太久，或者在 `maxWait = 0` 时，许可已经到期却可能被拒绝。检查 park 之后重新读时钟是否会改变基准测试结果（测量单线程和四线程）。同样的 park 还会让竞争失败的调用方出现长尾（Linux 上约 50 µs 的定时器松弛），平均时间基准测试看不到这一点：用百分位运行来测量（1.0 之后，或者如果 3.3 改变了循环就在这里测）。
  - **结果：**（#65）通过阅读代码确认；无法用测试复现，因为在读取 `tat` 与 compare-and-set 之间什么也无法运行。修复方式是在 park 之后重新读时钟，仅在竞争路径上。在 AWS 上测量：四个线程，183.6 对 187.0 ns，因此没有变慢（[评审修复](benchmarks.md#review-fixes)）。尾部延迟留给 1.0 之后的百分位运行。
- [x] **3.4 超时：用户自己的中断被清除。** `TimeoutPolicy`（约第 218 行）：watchdog 中断之后，`Thread.interrupted()` 也会清除调用方此前设置的中断。确定语义（保留先前的中断：如果调用前已设置，就重新设置标志？）并用 `Watchdog(0)` 测试。
  - **结果：**（#65）已确认并修复。`anInterruptTheCallerAlreadyHadSurvivesTheTimeout` 在修复前失败。watchdog 在 release `TIMED_OUT` 之前记录 `callerInterrupted`，调用方随后保留该中断。超时和 watchdog 的 jcstress 通过。
- [x] **3.5 重试预算：无上界的丢失更新。** `RetryBudget`（约第 125 行）：一个在 opaque 读和写之间被抢占的线程可能抹掉其他线程在同一条带上的调用；当线程数超过 2 × CPU 数时条带会被共享。方向是安全的（更少的重试）。大概只需记录，不需修复：把上界（或没有上界）写进类的 Javadoc 和 `architecture.md`。
  - **结果：**（#65）已记录，未修复：RetryBudget 的 Javadoc 和 `architecture.md` 现在说明，这种丢失没有固定上界，只会降低预算，且只在条带被共享时发生。
- [x] **3.6 超时与虚拟线程。** 每个新线程的第一次计时调用都会在一个共享队列中注册一个 `ThreadScopes`，watchdog 在每个 tick 遍历所有已注册的线程。对于每个请求一个虚拟线程的情况，这意味着每个请求一次内存分配，以及与存活线程数成正比的扫描。为它做基准测试（一个每次调用启动一个虚拟线程的 JMH 用例）；然后决定是修复还是记录。
  - **结果：**（#65）用新的 `VirtualThreadTimeoutBenchmark` 测量：每个新线程约 270 B；一个线程时没有时间开销；四个线程同时启动时，在 9.3 µs 的线程启动上多出 +0.8 µs（+8.6 %）。已在超时页面和[基准测试](benchmarks.md#review-fixes)中记录，未改动：只有线程的第一次计时调用付出代价。

## 第 4 阶段：发布 :id=phase-4-release

- [x] （#66）第二次评审的文档修正：新线程的第一次计时调用会分配内存（`performance.md`）、会 park 的是限流器（而不是舱壁隔离）、会读取时钟的功能、快速入门第 6 步的措辞和指向 HTTP 指南的链接、指南中超时那一行的测试、FAQ 的默认值链接、自适应限流的近似平均值、本状态。
- [x] （#66）`SECURITY.md`：受支持的版本，以及如何私下报告漏洞。
- [x] 在 ARM（AWS Graviton）上完整运行一次 jcstress，因为 CI 只在 x86 上运行它。
  - **结果：** 2026-11-02，一台 `c7g.4xlarge`（16 个 Graviton3 核心），`-m default`，约 40 分钟：65 个测试，2598 个配置，没有失败，没有错误。如预期，有一个测试是 "interesting"：`RetryBudgetStress.CallsAreNeverOvercounted` 在 10.9 % 的样本中丢失了调用，从未多计一次。这就是任务 3.5 中记录的丢失。我们当时写道它在 ARM 上出现得远比 x86 频繁；CI 之后在两种机器上的运行（4 核 runner，quick 模式）看到的比率相近，ARM 上 5.5–8.6 %，x86 上 6.3–18.6 %，因为丢失一次调用不需要任何重排序（[博客](blog/jcstress-on-arm-and-x86.md)）。这次运行使用的是仓库之外的一次性 Terraform 配置；定期运行它列在"1.0 之后"中。
- [x] 决定注解处理器缺失时是否必须在 1.0 之前让 Spring、Micronaut 和 Quarkus 的启动失败（所有者的决定；采用者认为以后再加会在同一主版本内改变行为）。
  - **结果：** 是的，在 1.0.0 之前，于 1.0.0-RC2 中。一个 bean 的类（或某个超类）或它实现的某个接口声明了策略，却没有生成的类，现在会导致失败：
    - Spring 启动失败，报 R4C-SPR-006；
    - 创建注解类的 Micronaut bean 失败，报 R4C-MN-007（注解接口只通过 `@ResilientBean` 包装，而它已经会以 R4C-RT-003 失败）；
    - Quarkus 构建失败，报 R4C-QK-008。

    每个检查都有一个关闭开关（`allow-missing-processor`），被排除的 bean 不受检查。Spring 和 Micronaut 读取 class 文件（`PolicyAnnotations`，两者各有一份副本，保持同步），因此该检查不在原生镜像中运行；Quarkus 使用 Jandex 索引。测试在没有 `$Resilient` 的情况下加载带注解的 fixture：使用一个隐藏它的类加载器（Spring、Micronaut），或一个不含它的应用根目录（Quarkus）。每个检查都做了变异检查。
- [x] 决定是否发布候选版本：在 Central 上发布 `1.0.0-RC1`，使用一到两周，然后发布 1.0.0。（所有者的决定。）
  - **结果：** 是的。安装代码片段在 RC3 之前一直停留在 0.13.0；此后它们显示最新的候选版本（所有者的决定）。
- [x] 按照[发布](releasing.md)发布 1.0.0-RC1。
  - **结果：** 于 2026-11-02 发布（提交 `release 1.0.0-RC1`，标签 `v1.0.0-RC1`，`release.yml` 运行 41553226849，由所有者在 Central 门户中发布）。
    - 相对 0.13.0 的 `apiCompatibility` 只显示了预期的改动：重命名的事件、两个 Micronaut 构造器，以及 `FluxCall$Attempt`（仅源码层面）。
    - 生成代码检查（`-Pr4c.generatedBy=0.13.0`）不适用：从 0.x 到 1.0 跨越了主版本，而且测试已经使用 `CircuitBreakerEvent.Rejected`。
    - 首页的四项对比在候选版本的代码上重新测量过（[基准测试](benchmarks.md#release-candidate)，#67）。
- [x] 候选版本之后：`version=1.0.0-SNAPSHOT`、`r4c.apiBaseline=1.0.0-RC1`，javadoc.io 已同步，并为全部 11 个模块生成了 1.0.0-RC1 的文档。
- [x] 使用一到两周后发布 1.0.0，除非有什么必须修改：
  - 版本号、标签 `v1.0.0`、`release.yml`、在门户中 Publish；
  - 然后把安装代码片段改为 1.0.0（博客除外）、`r4c.apiBaseline=1.0.0`、javadoc.io；
  - 从那以后，生成代码检查（`-Pr4c.generatedBy=<release>`）是每次发布的第 1 步。
  - **结果：** 于 2026-11-21 发布（提交 `release 1.0.0`、标签 `v1.0.0`、`release.yml` 运行 44973253342，由所有者在 Central 门户中发布）。`apiCompatibility` 与 1.0.0-RC4 相比，在所有模块中都未发现不兼容的改动。随后：`version=1.1.0-SNAPSHOT`、`r4c.apiBaseline=1.0.0`、`upgrade/1.0.0.properties`，安装代码片段和试点改为 1.0.0，GitHub release `v1.0.0` 标记为 *Latest*。

## 第三次评审 :id=third-review

同样的四位评审者于 2026-11-05 阅读了 1.0.0-RC2。没有人运行整个构建；新手用 Maven Central 上的 RC2 jar 运行了快速入门。

| 评审者 | 结论 |
|---|---|
| 一位 API 设计者 | 用这套 API 发布 1.0.0；没有任何东西会成为永久性的错误。 |
| 一位性能与并发的怀疑者 | 发布 1.0.0：在 core 中没有发现排序、丢失唤醒、ABA 或恰好一次方面的 bug；第 3 阶段的修复是成立的。RC2 的检查中有一个误报。 |
| 一位新手 | 大约 10 分钟就保护了一个调用；快速入门的输出与 RC2 完全一致。有关于候选版本的过时说法。 |
| 一位正在决定是否采用的 Resilience4j 用户 | 在两三个服务上试点，尚不用于生产：到目前为止没有外部用户，只有一位维护者，且仅支持 Spring Boot 4.1。 |

- [x] 一个注解作用不到任何方法的类型（空的注解接口、只有 final 方法的类）既不生成任何东西，也不报告任何东西，于是 Spring 和 Quarkus 会报告注解处理器缺失。现在是 [R4C-APT-007](errors.md#r4c-apt-007)；在各集成的检查中，只含 `@PolicyOrder` 的组合注解不再被视为策略。
- [x] 熔断器把 `Error` 算作成功（`recordOn = Exception`），与 Resilience4j 不同。所有者的决定：`recordOn` 默认为 `Throwable`。
- [x] Quarkus 的 jar 没有 `Automatic-Module-Name`；注解处理器缺失的消息提到了注解处理器却没有版本。
- [x] 文档：首页、FAQ、安装、故障排查、注解类、超时、兼容性和性能页面，以及 RC2 的博客文章（见 changelog 的 Unreleased 条目）。
- [x] 带着这些改动发布 1.0.0-RC3（熔断器的默认值和新的编译错误改变了行为），然后发布 1.0.0。
  - **结果：** 于 2026-11-05 发布（提交 `release 1.0.0-RC3`，标签 `v1.0.0-RC3`，`release.yml` 运行 42052831462，由所有者在 Central 门户中发布）。安装代码片段显示 1.0.0-RC3。

留到 1.0 之后（见下文）：原生镜像中 Spring 和 Micronaut 的注解处理器缺失检查（Spring 可以在其 AOT 步骤中运行它）、无法取消注册的 `Ticker.onAdvance`、不传递新实例的 `onInstanceCreated`、无法停止共享的守护线程（在可重新加载的 Web 应用中造成泄漏）、只会缩小的 watchdog tick，以及三个集成中不同的开关名称（有意为之；将写入[设计](design.md)）。代码之外：目前还没有外部反馈。`main` 的分支保护和候选版本的 GitHub release 已于 2026-11-06 添加（[发布](releasing.md#release-steps)的第 8 步）。

## 1.0 之后 :id=after-10

增量性的，因此不阻碍冻结。大致按价值排序。标有 *（要求在 1.0 之前）* 的项目是某位评审者希望在发布之前完成的：由所有者决定是否提前。

- 在 x86（`c7i`）上运行与 Resilience4j 的对比，并与 Graviton 的结果一起发布；在 x86 上每个原子操作都是完整的内存屏障，而 `nanoTime` 很便宜，因此针对 ARM 调优的选择排名可能不同。*（要求在 1.0 之前）*
- ~~在 ARM runner（`ubuntu-24.04-arm` 或 Graviton）上定期以 default 模式运行 jcstress。~~ 已在 1.0 之前完成：`stress` 在每次推送到 main 之后和每晚在 x86 和 ARM 上以 quick 模式运行，`stress-default` 每周在 x86 和 ARM 上以 default 模式运行。随后在两种机器上重跑了变异体（`stress-mutants`，手动，quick 和 default 模式）：ARM 没有捕获到任何 x86 漏掉的东西。对照组和两个 store-buffering 变异体在两种机器上都被捕获；只削弱某个排序的五个变异体在两种机器上都存活，在 Apple M1 上也是如此。这五个中有两个（舱壁隔离信号量的重新检查、等待者计数）无论如何都会被紧挨在它们之前的一个原子读-改-写所排序；`TimeoutPolicy` 中的三个超出了这些机器会做的重排序范围，即使加上了为它们编写的测试（`TimeoutStress.TickSeesWholeCall`）也是如此。*（要求在 1.0 之前）*
- ~~为有竞争的限流器和舱壁隔离做百分位基准测试（`SampleTime`）。~~ 已在 1.0 之前完成（[百分位](benchmarks.md#percentiles)）：四个线程使一个限流器饱和时，0.1 到 1 % 的调用会 park 并等待 110 µs 或更久（p99.9；Resilience4j 为 910 µs）；舱壁隔离没有 park 造成的尾部。剩下的：一次开环运行（按固定速率调用，延迟从计划的开始时间算起）。*（要求在 1.0 之前）*
- ~~当注解类没有生成的类时，在 Spring、Micronaut 和 Quarkus 中明确失败。~~ 已在 1.0 之前完成：见第 4 阶段。
- 可选的成功和延迟指标，添加之前先测量（每次调用的共享写入），以便与 Resilience4j 的仪表板对等。*（要求在 1.0 之前）*
- 支持 Spring Boot 3，或给出有文档的理由和迁移路径。*（要求在 1.0 之前）*
- 项目健康：第二位拥有发布权限的维护者、一份安全策略（`SECURITY.md`）和一份支持策略。*（要求在 1.0 之前）*
- API 评审者列出的增量性 API 缺口：
  - `Ticker.onAdvance` 无法取消注册。
  - 事件只携带 `nanoTime`，没有挂钟时间。
  - `ConfigException.problems()` 返回 `List<String>`，而不是结构化的问题（代码、键、来源）。
  - 只有熔断器在注册表上有列表方法；重试、对冲请求和超时没有 handle。
- Resilience4j 拥有的功能，逐项决定（实现，或写明为何不实现）：线程池舱壁隔离、缓存、RxJava、Kotlin 支持、自动的 open → half-open 转换、事件端点、把调用方的上下文带入对冲的尝试。
- 大约六个月后，像采用方评审者那样重新评估采用信号：活跃度、维护者、已关闭的 issue。他们首先会推荐在一个高流量的纯 Java 服务上试点。在有这样的试点之前，`pilot/` 会在一台 AWS 机器上，用 Central 上的最新版本运行 Spring Boot、Micronaut 和 Quarkus 服务，在后端宕机和变慢的情况下承受 10 分钟的 k6 负载：2026-11-13 所有检查都通过了（见 `pilot/README.md`）。这不是一次有真实流量的试点。
- 来自第三次评审：Spring 或 Micronaut 原生镜像中的注解处理器缺失检查（Spring 可以在其 AOT 步骤中、在 JVM 上运行它）；`Ticker.onAdvance` 返回一种取消注册的方式；`onInstanceCreated` 传递新实例；一种停止共享守护线程的方式，或者说明在可重新加载的 Web 应用中 `core` 应放在服务器的 class path 上的文档；在没有短超时剩余时让 watchdog 的 tick 重新变大；把三个集成的开关名称作为有意为之的决定写入[设计](design.md#api-decisions)。
