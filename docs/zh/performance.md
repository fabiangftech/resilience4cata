# 性能

resilience4cata 每次调用的开销是多少（与配置等价的 Resilience4j 2.4.0 对比测得），以及如何复现这些结果。

低开销正是 resilience4cata 存在的理由：注解处理器为每个方法生成直线代码，因此一次成功的调用只需读取几个字段，并且对大多数策略而言，没有内存分配、没有锁、也不读取时钟。本页汇总了测量结果；[基准测试详解](benchmarks.md)包含每一次运行、其设置以及每个数字是如何得出的。

## 测量什么，以及有多公平 :id=method

- **JMH 1.37**，3 个 fork × 5 × 1 s 预热和 5 × 1 s 测量，使用 `-prof gc` 统计每次调用分配的字节数。纯 JMH，不使用 Gradle 插件。
- **两个库使用等价的配置**：相同的尝试次数、延迟、窗口大小、阈值和上限；没有事件监听器；失败使用预先分配、不带堆栈的异常，这样两个库都不用为创建异常付出代价。Resilience4j 的拒绝异常设置了 `writableStackTraceEnabled(false)`。
- **为公平性所做的调整都有记录**，写在每个基准测试的 Javadoc 中。主要的一项：Resilience4j 2.4.0 即使延迟为零，也会在两次尝试之间调用 `Thread.sleep`，因此重试的对比使用了一个去掉该 sleep 的变体（`resilience4jRetryOnceNoSleep`），这是 Resilience4j 的最佳情况。
- **数字中包含的行为差异**：Resilience4j 的熔断器总是为调用计时（resilience4cata 只在设置了 `slowCallDuration` 时才计时）；Resilience4j 的 `TimeLimiter` 需要一个 `Future`，因此同步调用必须转到另一个线程（resilience4cata 在调用方的线程上处理超时）。
- **两台机器，从不混用**：一台 AWS `c7g.xlarge`（4 个 Graviton3 核心，无 SMT，固定频率，Amazon Corretto 21），以及用于早期几轮的一台 Apple M1 笔记本（JDK 21）。来自不同机器的数字不可比较。
- 只有一个被装饰方法的基准测试是 Resilience4j 的最佳情况：JIT 在每个调用点只看到一种类型。真实应用中有很多种。
- **闭环中每次调用的平均耗时**（JMH `AverageTime`）：每个线程在上一次调用返回后立即再次调用。这会掩盖尾部延迟：在竞争下，限流器会 park 在竞争中失败的线程；当四个线程使一个限流器饱和时，有 0.1 % 到 1 % 的调用要等待 110 µs 或更久（Resilience4j 的：p99.9 为 910 µs）。舱壁隔离没有这样的尾部。百分位数见：[基准测试](benchmarks.md#percentiles)。
- **目前只在 ARM 上测量**：Graviton3 和 Apple M1。x86-64 尚未测量；在那里原子操作是完整内存屏障，而读取时钟很便宜，因此某些结果的排序可能不同。

## 主要结果 :id=headline

> [!MEASURED]
> AWS `c7g.xlarge`（Graviton3），JDK 21，与 Resilience4j 2.4.0 对比，单位为每次调用的纳秒数和分配字节数。第一次尝试即成功的重试、每次调用都成功的熔断器以及同步超时，为 1.0.0-RC1 的代码，来自[候选版本 (2026-11-02)](benchmarks.md#release-candidate)；有失败调用的熔断器，来自[真实的失败率 (2026-11-02)](benchmarks.md#failure-rates)；有空闲槽位的舱壁隔离，为 0.13.0 之后的代码，来自[用 volatile add 归还槽位 (2026-11-01)](benchmarks.md#release-counters)，其中 Resilience4j 单线程测得 20.4 ns，四线程为 348 到 450 ns；其余各行为 resilience4cata 0.1.0，来自 [MVP 完成 (2026-10-24, AWS Graviton3)](benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)。

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 重试，第一次尝试即成功 | 1 | **1.4 ns**, 0 B | 22.9 ns, 0 B |
| 重试，失败一次后成功（Resilience4j 去掉了它的 `sleep(0)`） | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| 熔断器，每次调用都成功 | 1 | **1.9 ns**, 0 B | 89.9 ns, 0 B |
| 熔断器，每次调用都成功 | 4 | **1.9 ns**, 0 B | 850 ns, 2 B |
| 熔断器，每 1 000 次调用失败 1 次 | 4 | **11.2 ns**, 0 B | 1025 ns, 26 B |
| 熔断器，每 100 次调用失败 1 次 | 4 | **65.4 ns**, 0 B | 1006 ns, 22–26 B |
| 熔断器，每 10 次调用失败 1 次 | 4 | **278 ns**, 0 B | 940 ns, 2 B |
| 限流器，有空闲许可 | 4 | **187 ns**, 0 B | 275 ns, 40 B |
| 限流器，已耗尽（每次调用都被拒绝） | 4 | **34.7 ns**, 0 B | 1599 ns, 505 B |
| 舱壁隔离，有空闲槽位 | 1 | **17.3 ns**, 0 B | 20.4 ns, 0 B |
| 舱壁隔离，有空闲槽位 | 4 | **195.5 ns**, 0 B | 348–450 ns, 0 B |
| 舱壁隔离，已满（每次调用都被拒绝） | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| 超时，同步调用按时完成（Resilience4j：`TimeLimiter` + executor） | 1 | **23.6 ns**, 0 B | 8908 ns, 183 B |
| 异步超时，stage 仍未完成 | 1 | **152 ns**, 192 B | 598 ns, 240 B |
| 异步超时，stage 仍未完成 | 4 | **369 ns**, 192 B | 1563 ns, 234 B |

如实解读这张表：

- **熔断器的 1.9 ns 仅在其窗口中没有失败时成立。** 此时一次调用只读取一个字段。一旦最近 `windowSize` 次调用中包含失败或慢调用，每次调用都要写共享的窗口游标。在四个线程下，0.1 % 失败率时为 11.2 ns，1 % 时为 65.4 ns，10 % 时为 278 ns，而 Resilience4j 在每种失败率下都约为 1 000 ns：分别为 91×、15× 和 3.4×，而不是 450×（[真实的失败率](benchmarks.md#failure-rates)）。
- **Resilience4j 每次调用都做了一些 resilience4cata 不做的工作**：它的重试在内置指标中统计每次调用（`LongAdder` 计数器），它的熔断器总是为调用计时。除熔断器的窗口外，resilience4cata 不保留任何按调用的计数器（[为什么没有成功调用的指标](faq.md#success-metrics)）。
- **单线程、有空闲槽位的舱壁隔离曾经打平**（20.5 对 20.4 ns，两者都用一次原子操作获取、一次归还），直到改为用 volatile add 归还槽位——在 Graviton3 上它测得比仅 release 语义的 add 更快：17.3 ns。
- **有竞争的路径在两个库中都要花费数百纳秒。** 四线程下熔断器的混合情形（278 ns）主要开销来自每次调用都要推进的一个共享窗口游标；四线程下的舱壁隔离和限流器为一个共享计数器付出代价，而限流器还会 park 在其上竞争失败的线程。
- **同步超时是开销最大的同步策略**（单线程 23.6 ns；0.1.0 的主要结果测得 22.2 ns，此后再未出现过，参见[候选版本](benchmarks.md#release-candidate)）。与 Resilience4j 的对比并非同类比较：它的 `TimeLimiter` 把调用交给另一个线程，这正是其 8.9 µs 的主要去处。
- **异步调用在两个库中都会分配内存**：每一层都返回一个新的 future。resilience4cata 的异步路径并非零分配；只有同步路径是。
- **限流器每次调用读取一次时钟**，两个库都是如此；这次读取占了它大部分开销（在 Apple Silicon 上，仅 `System.nanoTime()` 单线程就约 21 ns，四线程为 163 ns；参见[限流器，1 和 4 线程](benchmarks.md#rate-limiter-1-and-4-threads-2026-10-24)）。

### Classifier、Pipeline 和带注解的类 :id=more

> [!MEASURED]
> 与上面相同的机器和轮次（[MVP 完成](benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)、[带注解的类](benchmarks.md#annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3)），每次调用都成功，resilience4cata 一侧为 0 B。

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 熔断器 + 结果 classifier | 1 | **2.4 ns** | 102 ns, 24 B (`recordResult`) |
| 熔断器 + 结果 classifier | 4 | **2.4 ns** | 902 ns, 18 B |
| 重试 + 熔断器 + classifier | 1 | **2.7 ns** | 148 ns, 104 B |
| 重试 + 熔断器，生成的代码 | 1 | **2.3 ns** | 175 ns, 288 B |
| 重试 + 熔断器，编程式 `Pipeline` | 1 | **2.9 ns** | （相同的 Resilience4j 组合） |
| 重试，带注解的类（子类）与接口包装对象对比 | 1 | 1.0 vs 1.2 ns | — |

- 一个 `Pipeline`，如果其唯一的 `call.call()` 调用点已经见过另外五个 pipeline，测得为 **7.9 ns** 而不是 2.9 ns：当一个应用中有许多 pipeline 时，这个调用点会变成超多态 (megamorphic)。生成的代码每个方法有一个调用点，不需要付出这个代价。

### Mono 和 Flux 方法（Project Reactor） :id=reactor

> [!MEASURED]
> AWS `c7g.xlarge`（Graviton3），JDK 21，与使用相同设置的 resilience4j-reactor 2.4.0 operator 对比，每次调用都成功。来自 [Flux 方法和已完成的 stage (2026-10-29, AWS Graviton3)](benchmarks.md#flux-and-done-stages)。

`Mono` 方法，除非另有说明，值在订阅时已经可用：

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 熔断器 | 1 | **106 ns**, 176 B | 243 ns, 248 B |
| 熔断器 | 4 | **105 ns**, 176 B | 1291 ns, 240 B |
| 重试，第一次尝试即成功 | 1 | **135 ns**, 227 B | 725 ns, 1000 B |
| 超时 | 1 | **105 ns**, 176 B | 3184 ns, 1344 B |
| 超时，值稍后才到达 | 1 | **430 ns**, 480 B | 3686 ns, 1797 B |
| 舱壁隔离 | 1 | **126 ns**, 176 B | 157 ns, 200 B |
| 重试 + 熔断器 + 超时 + 舱壁隔离 | 1 | **201 ns**, 376 B | 4319 ns, 2624 B |

`Flux` 方法，除非另有说明，为一个包含十个元素的完整流：

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 熔断器 | 1 | **195 ns**, 283 B | 272 ns, 216 B |
| 熔断器 | 4 | **189 ns**, 317 B | 1322 ns, 218 B |
| 重试，第一次尝试即成功 | 1 | **270 ns**, 392 B | 1197 ns, 1024 B |
| 超时（每个元素到达时重新计时） | 1 | **681 ns**, 384 B | 28896 ns, 3983 B |
| 超时，一千个元素 | 1 | **45 µs**, 384 B | 2632 µs, 241529 B |
| 重试 + 熔断器 + 超时 + 舱壁隔离 | 1 | **789 ns**, 616 B | 30583 ns, 5104 B |

- 在这台机器上，不带任何策略的订阅对 `Mono.just` 耗时 52 ns、分配 88 B，对十个元素耗时 56 ns、分配 96 B；每一行都包含这部分开销。
- 最接近的情形：四线程下包裹 `Mono` 的舱壁隔离，以及四线程下包裹 `Flux` 的舱壁隔离（755 vs 697 ns）或单线程下的限流器（240 vs 234 ns），都在误差范围内。`Flux` 超时每个元素约耗时 44 ns，且不为每个元素设置定时器；Resilience4j 则为每个元素调度一个。响应式方法会分配内存，所有异步路径都是如此；内存分配门禁会跟踪它们的字节数。

### 一个应用中的多个服务 :id=many-services

> [!MEASURED]
> Apple M1，JDK 21，四个互不相关的服务，各有自己的重试，每次操作对每个服务调用一次。来自[重试，四个服务](benchmarks.md#retry-four-services-2026-10-23)。

| 每次调用 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| 开销，第一次尝试即成功 | **0.6 ns**, 0 B | 21.3 ns, 80 B |
| 开销，失败一次后成功 | **9.4 ns**, 0 B | 273 ns, 16 B（去掉了它的 `sleep(0)`） |

当四个被装饰的 supplier 共享 Resilience4j 的调用点时，它的成功路径开始每次调用分配 80 B；resilience4cata 生成的每个类都有自己的调用点，保持在 0 B。

## 零分配门禁 :id=allocation-gate

每个 pull request 都会检查内存分配。`./gradlew :benchmark:allocationCheck` 使用 `-prof gc` 运行 `benchmark/allocation-budgets.properties` 中列出的基准测试，若某个基准测试每次调用分配达到其预算 + 1 B 就会失败。**每条同步成功路径和拒绝路径的预算都是 0 B**：重试、熔断器（计数窗口和时间窗口、打开状态）、限流器、舱壁隔离、超时、classifier、生成的代码以及 `Pipeline`（包括使用捕获型 lambda 时）。异步路径为它们的 future 设有预算（重试为 48 B，stage 已完成时其他策略为 24 B）。门禁看不到的一个例外：新线程上的第一次带超时调用会把该线程注册到超时的 watchdog，约 270 B，这在每个请求一个新虚拟线程时就有影响（[每个新虚拟线程一次超时](benchmarks.md#review-fixes)）。详见[内存分配门禁](benchmarks.md#allocation-gate)。

事件只在有人监听时才分配内存：没有监听器的事件类型在失败或拒绝路径上只多一次 null 检查，在已满的舱壁上测得为 0.3 ns。

## 性能契约 :id=contract

项目为自己设定的目标，以及每一项如何检查：

| 目标 | 检查方式 |
|---|---|
| 每个策略以及完整策略组合的成功路径上 **0 B/op**（线程的第一次带超时调用：约 270 B，仅一次） | CI 中的 JMH `-prof gc`：如果列出的某个基准测试达到其预算 + 1 B/op（每条同步路径为 0；异步 future 为测得的值），`./gradlew :benchmark:allocationCheck` 会让 pull request 失败 |
| 热路径上没有锁，也没有 `synchronized` | 代码审查、JMH 竞争场景 |
| 每个方法单态、直线的生成代码；没有共享的 lambda 链 | 使用被污染 JIT profile 的 JMH、`-XX:+PrintInlining` |
| 除非某项功能需要（限流、慢调用检测、时间窗口、自适应限流的延迟、重试的 `maxDuration`），成功路径上不读取时钟；每个这样的策略都自己读取时钟，因此多个这样的策略组合时会读取不止一次 | 代码审查 |
| 预先分配、不带堆栈的拒绝异常；事件只在有人监听时才分配 | JMH 拒绝场景（打开的熔断器、已满的舱壁、耗尽的限流器），在 CI 中以 0 B/op 设门禁 |
| 正确的无锁结构 | jcstress，在每次推送到 main 后以及每晚于 x86 和 ARM 上在 CI 中运行，每周还以更长的默认模式在 ARM 上运行 |
| 公开、可复现的与 Resilience4j 2.4.0 的对比 | `benchmark` 模块，使用等价的 Resilience4j 配置并附有公平性说明（[基准测试详解](benchmarks.md)） |

大多数真实调用受 I/O 限制（毫秒级），所以重点在于开销接近于零，并在多线程下依然如此，而不仅仅是原始速度。

## 它为什么便宜 :id=why

- **生成的、按方法的直线代码**：没有共享的装饰器 lambda，因此 JIT 可以在每个调用点以单一接收者类型进行内联。
- **成功时不分配任何内存**，而拒绝时每个实例抛出一个预先分配、不带堆栈的异常。
- 任何调用路径上**都没有锁**，原子操作通过测量来选择：获取槽位时用 acquire 语义的 compare-and-set 代替完整内存屏障，使空闲舱壁在 Apple M1 上从 23.2 ns 降到 9.3 ns（[舱壁隔离，1 和 4 线程](benchmarks.md#bulkhead-1-and-4-threads-2026-10-23)），而用 volatile add 归还槽位在 Graviton3 上测得比仅 release 语义的 add 更快（[release 计数器](benchmarks.md#release-counters)）。
- **健康时没有共享写入**：窗口中只有快速成功的熔断器，每次调用只需一次读取即可检查。
- 除非某项功能需要（慢调用检测、时间窗口、限流、自适应限流的延迟、重试的 `maxDuration`），**不读取时钟**。超时使用一个共享的 watchdog 线程，而不是每次调用都读取时钟。

## 复现 :id=reproduce

在仓库的克隆中（运行 Gradle 需要 JDK 17 或更高版本；它使用 Java 21 工具链）：

```bash
./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"                 # one benchmark class
./gradlew :benchmark:jmh -Pjmh="CircuitBreakerBenchmark -t 4 -prof gc"   # four threads
./gradlew :benchmark:jmh -PjmhJdk=25                                     # every benchmark on another JDK
./gradlew :benchmark:allocationCheck                                     # the allocation gate
```

结果写入 `benchmark/build/jmh/results.json`。源码位于 `benchmark/src/main/java/dev/resilience4cata/benchmark/`，每个基准测试的 Javadoc 中都写有对应的 Resilience4j 实现以及为公平性所做的调整。请在空闲的机器上、使用多个 fork 进行测量，并且只比较来自同一台机器的数字。
