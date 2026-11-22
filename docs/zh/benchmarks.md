# 基准测试

> 这里只收录实测结果。每个数字都来自本仓库中的一次运行；其他内容都只是假设，应放在研究文档中。

## 重试，单线程（2026-10-23） :id=retry-single-thread-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"` — 源码：`benchmark/src/main/java/dev/resilience4cata/benchmark/RetryBenchmark.java`。

**环境：** JMH 1.37，JDK 21.0.10，Apple M1 笔记本，3 forks × 5 × 1 s 预热 × 5 × 1 s 测量，`-Xms1g -Xmx1g`。两个库：3 次尝试，零延迟，无抖动，所有异常都重试，没有事件监听器。失败使用预先分配、无栈的异常，因此两个库都无需为创建异常付出代价。

| 场景 | 基线（无库） | resilience4cata 0.1.0-SNAPSHOT | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 第一次尝试成功 | 1.14 ± 0.13 ns | **1.55 ± 0.08 ns**，0 B/op | 12.61 ± 0.10 ns，0 B/op |
| 失败一次后成功 | 3.03 ± 1.43 ns | **7.50 ± 0.75 ns**，0 B/op | 411.6 ± 15.1 ns，16 B/op |
| 同上，Resilience4j 去掉其 `Thread.sleep(0)` | — | — | 38.8 ± 3.6 ns，16 B/op |

**相对基线的开销：**
- 成功路径：resilience4cata 约 **0.4 ns**，Resilience4j 约 **11.5 ns**。
- 一次重试：约 **4.5 ns**；而 Resilience4j 仅重试机制本身就是 **36 ns**，按其默认行为则是 **409 ns**。

**如何解读这些数字：**
- Resilience4j 2.4.0 即使延迟为零，也会在两次尝试之间调用 `Thread.sleep`（已在其字节码中核实）。411 ns 中的大部分来自于此。`NoSleep` 变体用 Resilience4j 自己的 `RetryImpl.setSleepFunction` 去掉了它，以便单独衡量其余部分的成本。
- 这是 Resilience4j 的最佳情况：只有一个被装饰的 supplier，JIT 在每个调用点只看到一种类型，逃逸分析能在成功路径上消除它每次调用产生的对象。真实应用会装饰很多方法。研究预测在 JIT profile 被污染时差距会更大（[03 §7.4](research/03-performance-internals.md)）；该场景尚未测量。
- 仅单线程。重试没有共享的可变状态，因此预计竞争对它影响不大；但对熔断器和限流器会有影响。
- 一台笔记本，一个 JDK。待测：JDK 25、x86-64、被污染的 profile。

## 重试，四个服务（2026-10-23） :id=retry-four-services-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="MultiTypeRetryBenchmark -prof gc"` — 源码：`benchmark/src/main/java/dev/resilience4cata/benchmark/MultiTypeRetryBenchmark.java`。

环境和设置同上，但有四个互不相关的服务（各自的接口、各自的实现类），各自从自己的调用点调用，就像真实应用一样。**一次操作调用这四个服务**；"每次调用"一栏是除以四后的结果。

| 场景 | 基线 | resilience4cata | Resilience4j 2.4.0 | Resilience4j，无 `Thread.sleep(0)` |
|---|---:|---:|---:|---:|
| 第一次尝试成功，每次操作 | 1.87 ± 0.03 ns | **4.39 ± 0.11 ns**，0 B | 87.1 ± 4.0 ns，320 B | — |
| ↳ 每次调用的开销 | — | **0.6 ns**，0 B | 21.3 ns，80 B | — |
| 失败一次后成功，每次操作 | 3.57 ± 0.09 ns | **41.1 ± 0.9 ns**，0 B | 2794 ± 128 ns，64 B | 1094 ± 13 ns，64 B |
| ↳ 每次调用的开销 | — | **9.4 ns**，0 B | 698 ns，16 B | 273 ns，16 B |

**与单服务运行相比的变化：**
- **Resilience4j 开始在成功路径上分配内存：每次调用 80 B**，而单服务运行时为 0 B。四个被装饰的 supplier 都经过 `Retry.decorateSupplier` 内部同一个 `supplier.get()` 调用点。JIT 无法再跨它内联，逃逸分析也就不再消除 Resilience4j 每次调用产生的对象。这正是 [03 §7.4](research/03-performance-internals.md) 预测的效果，现在已经实测。
- **resilience4cata 保持 0 B**，其成功路径开销几乎没变（每次调用 0.4 → 0.6 ns）：每个生成的类都有自己的调用点，只有单一的接收者类型。
- 它的重试路径从每次调用 4.5 ns 变为 9.4 ns。Resilience4j 在去掉 sleep 后从 36 ns 变为 273 ns：穿过 JIT 无法内联的栈帧的失败，展开（unwind）起来要昂贵得多。

仍待测：JDK 25、x86-64、多于一个线程。

## 熔断器，1 和 4 个线程（2026-10-23） :id=circuit-breaker-1-and-4-threads-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="CircuitBreakerBenchmark -t 4 -prof gc"` — 源码：`benchmark/src/main/java/dev/resilience4cata/benchmark/CircuitBreakerBenchmark.java`。

机器和 JMH 设置同上。两个库：基于计数的窗口，100 次调用，100 次调用后开始评估，失败率 50% 时打开，没有事件监听器。所有线程共享**一个**熔断器。Resilience4j 用两种窗口策略分别测量：`SYNCHRONIZED`（其默认值）和 `LOCK_FREE`。在混合场景中十次调用有一次失败，因此熔断器保持关闭，同时其窗口记录失败。默认值的差异：Resilience4j 总是为慢调用检测给调用计时；resilience4cata 除非设置了 `slowCallDuration`，否则不计时。

| 场景 | 线程数 | 基线 | resilience4cata | R4j `SYNCHRONIZED` | R4j `LOCK_FREE` |
|---|---:|---:|---:|---:|---:|
| 每次调用都成功 | 1 | 1.10 ns | **2.03 ± 0.03 ns**，0 B | 66.4 ± 1.7 ns，16 B | 124 ± 16 ns，224 B |
| 十次失败一次 | 1 | 1.91 ns | **11.4 ± 0.3 ns**，0 B | 95.0 ± 4.6 ns，7 B | 135 ± 7 ns，243 B |
| 每次调用都成功 | 4 | 1.44 ns | **3.16 ± 0.08 ns**，0 B | 477 ± 41 ns，1 B | 1074 ± 96 ns，776 B |
| 十次失败一次 | 4 | 2.82 ns | **202 ± 21 ns**，0 B | 601 ± 13 ns，1 B | 1103 ± 64 ns，768 B |

（时间为每次调用；B 为每次调用分配的字节数。）

**相对基线的开销：** 单线程每次成功调用约 **0.9 ns**（Resilience4j：65 ns），四个线程共享熔断器时约 **1.7 ns**（Resilience4j：476 ns）。

**成功路径是如何做到这一步的。** 第一个可用版本在单线程上需要 9.5 ns，四个线程时 244 ns。每一步都经过了测量：

| 改动 | 1 线程，成功 | 4 线程，成功 | 4 线程，混合 |
|---|---:|---:|---:|
| 第一版：原子计数器位于独立对象中，acquire 读 | 9.5 ns | 244 ns | 500 ns |
| 窗口中只有快速成功时跳过写入 | 9.5 ns | 10.2 ns | 259 ns |
| 用 opaque 读代替 acquire 读（安全：能到达的一切都是 `final`） | 5.7 ns | — | — |
| 计数器作为窗口的字段，不再跳指针 | 4.9 ns | — | — |
| `quiet` 字段：健康时每次调用只读一次 | 2.0 ns | 3.1 ns | 317–351 ns ⚠ |
| 仅在变化时写 `quiet`；游标放在独立对象中；关闭状态下不维护 `completed` 计数器 | 2.0 ns | 3.2 ns | 202–230 ns |

- **在竞争下，起主导作用的是共享写入，而不是锁。** 写一条被其他核心读取的缓存行，代价超过无锁设计所节省的。最大的收益来自于没有变化时干脆不写。
- **在 ARM 上，acquire 读并不免费。** 每一次都变成一条 `ldar`；每次调用五次，约 4 ns。
- **每个改动都在全部四个场景中检查过。** 其中两个改动在修复之前让四线程混合场景变得更糟（⚠ 行）：把游标和计数器放在一起，以及每次失败都重写 `quiet`，二者都把每次调用的写入放到了每次调用都会读的缓存行上。
- **四线程混合场景仍是瓶颈**（约 200 ns）：每次调用都推进一个共享游标，使窗口精确保留最近 100 次调用。放宽这一语义（例如分条带的窗口）可以消除它；这是一个设计决策，尚未实施。
- Resilience4j 的 `LOCK_FREE` 策略在这里每个场景都比其默认策略慢，并且每次调用分配 200–800 B。
- 一台笔记本（Apple M1：4 个性能核 + 4 个能效核），JDK 21。待测：JDK 25、x86-64、8 个以上线程。

## 熔断器，基于时间的窗口（2026-10-23） :id=circuit-breaker-time-based-window-2026-10-23

同一个基准测试类（`*Time*` 方法）：10 秒窗口，100 次调用后开始评估，阈值 50%；Resilience4j 使用 `TIME_BASED` 及其默认的 `SYNCHRONIZED` 策略。

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 每次调用都成功 | 1 | **46.6 ± 0.3 ns**，0 B | 111.4 ± 0.5 ns，0 B |
| 十次失败一次 | 1 | **69 ± 19 ns**，0 B | 135.3 ± 0.8 ns，0 B |
| 每次调用都成功 | 4 | **164 ± 28 ns**，0 B | 703 ± 52 ns，41 B |
| 十次失败一次 | 4 | **191 ± 11 ns**，0 B | 744 ± 53 ns，41 B |

**时钟占主导。** 基于时间的窗口每次调用都要读时钟，而在这台机器上仅 `System.nanoTime()` 本身就要 **单线程 21 ns、四线程 163 ns**（单独测量）。在 macOS 上，HotSpot 通过对一个全局值做 compare-and-set 来保证 `nanoTime` 跨线程单调，因此并发调用者会在它上面竞争；在 Linux 上它是一次没有共享状态的 vDSO 调用。去掉时钟后，resilience4cata 的时间窗口单线程约 25 ns，四线程只有几纳秒。每个计数器都是分条带的（`LongAdder`），因此成功调用之间不会竞争；唯一的内存分配是每秒一个桶。

两个结论：
- 基于计数的窗口除非设置了 `slowCallDuration`，否则不读时钟，在两者都适用的地方要便宜得多。它是默认值。
- 一个缓存的粗粒度时钟（由后台线程每毫秒更新）可以消除这部分成本的大头。尚未实施：这意味着每个 JVM 一个守护线程，以及毫秒级精度。

## 超时，1 和 4 个线程（2026-10-23） :id=timeout-1-and-4-threads-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="TimeoutBenchmark -t 4 -prof gc"` — 源码：`benchmark/src/main/java/dev/resilience4cata/benchmark/TimeoutBenchmark.java`。

按时完成的调用（工作量极小，超时 1 s）。resilience4cata 在调用者的线程上执行调用，如果调用超时，一个共享的 watchdog 会中断它。Resilience4j 的 `TimeLimiter` 需要一个 `Future`，因此阻塞调用必须交给另一个线程：这就是它用于同步代码的方式，也是这里测量的方式（一个固定的 4 个平台线程的线程池）。

| 线程数 | 基线 | resilience4cata | Resilience4j 2.4.0（`TimeLimiter` + executor） |
|---:|---:|---:|---:|
| 1 | 1.10 ns | **15.6 ± 0.1 ns**，0 B | 9 225 ± 1 353 ns，189 B |
| 4 | 1.44 ns | **20.2 ± 2.4 ns**，0 B | 19 194 ± 3 763 ns，160 B |

- **每次调用约 14.5 ns 开销**，大约比把调用交给另一个线程少 600 倍。每次调用既不读时钟，也没有共享写入：每个线程只写它自己可复用的 scope。
- 这比其他策略的成本高（重试 0.4 ns，熔断器 0.9 ns）。可能的原因（尚未调查）：查找线程 scope 的 `ThreadLocal` 查找，以及调用结束时的 compare-and-set。
- 不读时钟的代价是精度：watchdog 以当前使用中最短超时的 `timeout / 16` 为间隔滴答（1 到 50 ms），并在调用超时后、大约两个滴答之内中断它，绝不会提前。

## 舱壁隔离，1 和 4 个线程（2026-10-23） :id=bulkhead-1-and-4-threads-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="BulkheadBenchmark -t 4 -prof gc"` — 源码：`benchmark/src/main/java/dev/resilience4cata/benchmark/BulkheadBenchmark.java`。

所有线程共享一个舱壁隔离（bulkhead）。*空闲*：上限 1000，永远达不到，不等待。*等待*：同样的上限，加上 `maxWait = 100ms`，因此 resilience4cata 使用其 `Semaphore` 模式。*已满*：上限为 1，由 setup 占用，因此每次调用都被拒绝。Resilience4j：`SemaphoreBulkhead`，相同上限，其拒绝异常禁用了栈跟踪。

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 空闲 | 1 | **9.3 ± 0.2 ns**，0 B | 22.9 ± 0.1 ns，0 B |
| 空闲 | 4 | **324 ± 15 ns**，0 B | 445 ± 4 ns，0 B |
| 等待模式，空闲 | 1 | 23.2 ± 0.3 ns，0 B | 23.4 ± 1.2 ns，0 B |
| 等待模式，空闲 | 4 | 447 ± 6 ns，0 B | 451 ± 12 ns，0 B |
| 已满（每次调用都被拒绝） | 1 | **2.4 ± 0.6 ns**，0 B | 143 ± 11 ns，464 B |
| 已满（每次调用都被拒绝） | 4 | **3.3 ± 0.4 ns**，0 B | 267 ± 41 ns，464 B |

- **拒绝便宜 60–80 倍**：已满的舱壁只读取其计数器（没有写入，因此没有竞争），并抛出一个预先分配的异常。在过载时（这正是舱壁起作用的时候），这能防止拒绝路径本身成为瓶颈。
- **空闲槽位：单线程 9.3 ns 对 22.9 ns。** 第一版测得 23.2 ns，与 Resilience4j 相同：它使用了全屏障（volatile）原子操作。获取槽位只需要 *acquire* 语义，释放只需要 *release* 语义，和锁一样；在 ARM 上这会把 `casal`/`ldaddal` 变成更便宜的指令，使其降到 9.3 ns（四线程时 421 → 324 ns）。
- **四个线程时，两个库都要为竞争付出代价**（300–450 ns）：精确的全局上限需要一个共享计数器，每次调用写两次。把上限拆分到多个条带上可以降低它，代价是上限只能按条带执行；尚未实施。
- 等待模式使用 JDK 的 `Semaphore`，和 Resilience4j 一样，因此成本相同。
- 同样的弱化内存序的改动也许适用于超时在调用结束时的 compare-and-set 以及熔断器的窗口更新；尚未测量。

## 限流器，1 和 4 个线程（2026-10-24） :id=rate-limiter-1-and-4-threads-2026-10-24

`./gradlew :benchmark:jmh -Pjmh="RateLimiterBenchmark -t 4 -prof gc"` — 源码：`benchmark/src/main/java/dev/resilience4cata/benchmark/RateLimiterBenchmark.java`。

所有线程共享一个限流器，两个库都不等待许可。*空闲*：每秒十亿个许可，永远达不到，因此每次调用都放行。*耗尽*：每小时一个许可，由 setup 取走，因此每次调用都被拒绝。Resilience4j：其默认的 `AtomicRateLimiter`，相同上限，其拒绝异常禁用了栈跟踪。两个库每次调用都读一次 `System.nanoTime()`。

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 空闲 | 1 | **25.3 ± 0.4 ns**，0 B | 57.5 ± 5.2 ns，40 B |
| 空闲 | 4 | **131 ± 1 ns**，0 B | 206 ± 7 ns，40 B |
| 耗尽（每次调用都被拒绝） | 1 | **22.9 ± 0.1 ns**，0 B | 243 ± 12 ns，560 B |
| 耗尽（每次调用都被拒绝） | 4 | **164 ± 1 ns**，0 B | 1763 ± 60 ns，524 B |

- **resilience4cata 的大部分成本来自时钟。** 在这台机器上，`System.nanoTime()` 单线程 21 ns、四线程 163 ns（见上面的时间窗口一节）；限流器本身在单线程上只增加约 2–4 ns。拒绝只读不写，因此四个线程时，耗尽的限流器的成本恰好等于有竞争的时钟的成本。在 Linux 上，`nanoTime` 没有共享状态，这些数字应该会低得多；尚未测量。
- **拒绝便宜 10 倍且不分配内存**；Resilience4j 每次尝试都分配一个新的 `State`，每次被拒绝的调用约 520 B。
- **竞争：第一版在四线程时落败，658 ns 对 214 ns。** 每个被放行的调用都会写那唯一的共享 `tat`，而在 compare-and-set 中失败的调用者会立即重试，于是缓存行在核心之间来回弹跳。换用不同的原子操作（strong、weak、acquire）毫无变化；用 `onSpinWait` 自旋（一次或带指数退避）和 `Thread.yield()` 几乎没有帮助（565–665 ns 和 414 ns）。像 Resilience4j 那样在 compare-and-set 失败后 park 1 ns，把它降到了 **131 ns**（八线程时 351 ns，Resilience4j 为 523 ns）。成本转移到了竞争失败的调用者身上：在 Linux 上，平台线程最短的 park 大约是 50 µs 的定时器松弛（timer slack）。这只在被放行的调用到达速度快于缓存行在核心间移动的速度时才会发生，远高于限流器通常配置的任何速率。

## 异步方法，1 个线程（2026-10-24） :id=async-methods-1-thread-2026-10-24

`./gradlew :benchmark:jmh -Pjmh="AsyncBenchmark -prof gc"` — 源码：`benchmark/src/main/java/dev/resilience4cata/benchmark/AsyncBenchmark.java`。

只测成功路径：委托返回一个已经完成的 `CompletableFuture`，每个基准测试返回调用者拿到的 stage。Resilience4j 使用其 `decorateCompletionStage` 变体，设置相同；它的重试和 time limiter 各得到一个单线程的 `ScheduledExecutorService`。*组合*（Stack）在两个库中以相同顺序嵌套重试、熔断器、超时和舱壁隔离。熔断器、限流器、超时和组合这几行用 6 个 fork 重跑过，因为第一次运行噪声很大（resilience4cata 的熔断器 ±137 ns）。

| 策略 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| 熔断器 | **18.8 ± 0.5 ns**，48 B | 85.8 ± 3.7 ns，71 B |
| 重试 | **19.3 ± 1.6 ns**，48 B | 28.7 ± 1.4 ns，48 B |
| 舱壁隔离 | **28.1 ± 0.8 ns**，48 B | 33.1 ± 9.5 ns，48 B |
| 限流器 | **48.0 ± 1.6 ns**，48 B | 63.6 ± 1.3 ns，88 B |
| 超时 | **213 ± 19 ns**，200 B | 3551 ± 1772 ns，184 B |
| 组合（重试、熔断器、超时、舱壁隔离） | **344 ± 21 ns**，362 B | 3600 ± 2205 ns，352 B |

- **内存分配与 Resilience4j 大致相同**：每一层都返回一个新的 future（48 B）。异步不可能做到零分配；同步路径不受这项工作影响。
- **超时在两个库中都占主导。** 每次调用都在 `ScheduledThreadPoolExecutor` 上调度一个任务，并在 stage 完成时取消它：一把锁，外加一次堆插入和删除。resilience4cata 会立即移除被取消的任务（`setRemoveOnCancelPolicy`）。Resilience4j 的数字在不同 fork 之间差异很大（1.8–5.3 µs）；未调查。一个像同步 watchdog 那样不加锁的定时器应该能降低 resilience4cata 的 213 ns；尚未实施。
- 限流器这一行包含一次 `System.nanoTime()`（≈21 ns，见上文）；熔断器除非设置了 `slowCallDuration`，否则不读时钟。

## 定时器重写与异步舱壁等待（2026-10-24） :id=timer-rewrite-and-async-bulkhead-wait-2026-10-24

机器和设置相同。在异步定时器（`TimeoutTimer`）、同步超时协议和异步舱壁等待的并行工作合并之后运行，在空闲机器上逐个运行基准测试。

**异步超时。** `*TimeoutPending` 返回一个仍处于 pending 状态的 future，基准测试在调用后立即完成它，因此测量的是定时器的调度和取消；`baselinePending` 是不用任何库时的同样操作（14.5 ns，24 B）。这里 Resilience4j 得到一个 remove-on-cancel 的调度器。`*Timeout` 返回一个已经完成的 future：resilience4cata 能识别它并跳过定时器（它不可能超时）。

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 待完成的 stage | 1 | **127 ± 10 ns**，192 B | 257 ± 11 ns，234 B |
| 待完成的 stage | 4 | **510 ± 35 ns**，192 B | 928 ± 60 ns，234 B |
| 已完成的 stage | 1 | **7.1 ± 0.2 ns**，24 B（原为 213 ns） | 3795 ± 3163 ns，181 B |
| 已完成的 stage | 4 | **11.2 ± 0.6 ns**，24 B | 3173 ± 1003 ns，180 B |

- 与 Resilience4j 的调度器相比，无锁定时器让一次带超时的异步调用成本减半，即使给 Resilience4j 设置了 remove-on-cancel 也是如此。四个线程时两者都要为共享状态付出代价：resilience4cata 的按线程 id 分条带的收件箱，以及结果 future 的完成。
- 行为变化：异步超时现在和同步超时一样基于滴答（绝不提前，最多晚约两个滴答，滴答 = 最短异步超时 / 16，限定在 1–50 ms 内），而之前 `ScheduledThreadPoolExecutor` 的精度在亚毫秒级。

**同步超时**（`TimeoutBenchmark`），去掉每次调用的一次 ThreadLocal 查找，并在调用结束时使用 acquire CAS 之后：

| 线程数 | 之前 | 之后 | Resilience4j 2.4.0 |
|---:|---:|---:|---:|
| 1 | 15.6 ns | **13.1 ± 0.1 ns** | 9039 ± 439 ns |
| 4 | 20.2 ns | **15.9 ± 0.7 ns** | 18423 ± 771 ns |

单线程上相对基线的开销从约 14.5 ns 降到 12 ns。只带 release 的 CAS 会更便宜，但会允许提前中断（见 `TimeoutPolicy.end` 中的注释），因此不采用。

**异步等待队列之后的舱壁隔离同步路径**（`BulkheadBenchmark`）：`maxWait > 0` 时，`exit()` 现在多做一次 volatile 读，以查看是否有异步调用者在排队。没有可测量的成本：*等待* 单线程 23.1 ± 0.05 ns（原为 23.2），四线程 442 ± 3 ns（原为 447）；*空闲* 9.2 ns 和 *已满* 2.2 ns，没有变化。

## 在 AWS 上并行运行（`infra/`） :id=parallel-runs-on-aws-infra

`infra/bench.sh <base-rev> <new-rev> [include-regex]` 在临时 EC2 机器上并行运行所有基准测试，并比较两个 git 版本。一次完整运行（150 个基准测试/线程数单元，283 次 JMH 运行）端到端约 20 分钟，而在一台机器上约需 3 小时，费用远低于 1 美元。

- **准备。** 每个版本都在一个全新的克隆中本地编译；其基准测试运行时 classpath 被打包并上传到每次运行专用的 S3 bucket，因此机器上只需安装一个 JDK（Amazon Corretto 21）并运行 `org.openjdk.jmh.Main`。Terraform（`infra/main.tf`）创建一个一次性的 VPC、bucket、仅限该 bucket 的实例角色以及机器；`bench.sh` 在结束时销毁一切，失败时也会销毁。
- **公平性。** 一个单元是一个基准测试方法在一种线程数下的运行。它的 base 和 new 运行在同一台机器上背靠背执行（轮流决定谁先跑），因此每一次 base/new 比较都在相同的硬件上进行。单元按耗时从长到短分配给负载最轻的机器（`infra/plan.py`）。
- **硬件。** `c7g.xlarge`：4 个 Graviton3 核心，没有 SMT，时钟固定（无睿频），这使得机器之间可以互换：每台机器还会运行一个校准基准测试（`BulkheadBenchmark.resilience4cataFree`，1 个线程），在 19 台机器上测得 20.45–20.47 ns（0.1% 的离散度）。JMH 设置沿用基准测试自身的设置（3 forks × 5 × 1 s 预热和测量，`-prof gc`）。
- **账户限制。** 该账户同一时间只允许 3 个 spot 实例（与其 96 vCPU 的 spot 配额分开计算）以及 64 个按需 vCPU，因此一次运行使用同一类型的 3 台 spot 机器和 16 台按需机器（`MACHINES`、`SPOT_MACHINES`）。
- Graviton 的数字不能与上面各节中 Apple Silicon 的数字比较；只在同一次运行内比较 base 和 new。

## MVP 完成：分类器、pipeline、事件、testkit（2026-10-24，AWS Graviton3） :id=mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3

Base：commit `3bc496f`。New：分类器、编程式 `Pipeline`、事件和快照，以及 testkit（`PolicyEnvironment`）的合并工作。按上文所述用 `infra/bench.sh` 测量。第一次运行中发现的两个性能回退已修复并重新测量；表格展示的是最终数字。

**无回退检查**（resilience4cata，ns/op，同步路径全部 0 B/op）：

| 基准测试 | 线程数 | Base | New | 变化 |
|---|---:|---:|---:|---:|
| 重试，成功 | 1 | 1.4 ± 0.0 | 1.2 ± 0.1 | −11% |
| 重试，成功 | 4 | 1.3 ± 0.0 | 1.4 ± 0.1 | +3% |
| 重试，重试一次 | 1 | 7.3 ± 0.1 | 6.5 ± 0.1 | −11% |
| 重试，重试一次 | 4 | 7.3 ± 0.1 | 6.6 ± 0.1 | −10% |
| 重试，四个服务，重试一次 | 1 | 38.4 ± 0.1 | 38.8 ± 0.1 | +1% |
| 重试，四个服务，重试一次 | 4 | 39.2 ± 0.6 | 37.6 ± 1.2 | −4% |
| 熔断器，成功 | 1 | 1.9 ± 0.0 | 1.9 ± 0.0 | 0% |
| 熔断器，成功 | 4 | 1.9 ± 0.0 | 1.9 ± 0.0 | 0% |
| 熔断器，混合 | 1 | 17.5 ± 0.1 | 17.2 ± 0.0 | −2% |
| 熔断器，混合 | 4 | 282 ± 10 | 274 ± 29 | −3% |
| 熔断器（时间窗口），混合 | 4 | 126 ± 9 | 130 ± 10 | +3% |
| 限流器，空闲 | 1 | 42.2 ± 0.1 | 41.8 ± 0.1 | −1% |
| 限流器，空闲 | 4 | 188 ± 2 | 187 ± 2 | 0% |
| 限流器，耗尽 | 4 | 35.0 ± 0.3 | 34.7 ± 0.2 | −1% |
| 舱壁隔离，空闲 | 1 | 20.5 ± 0.0 | 20.5 ± 0.0 | 0% |
| 舱壁隔离，已满（拒绝） | 1 | 2.1 ± 0.1 | 2.3 ± 0.0 | **+12%** |
| 舱壁隔离，已满（拒绝） | 4 | 2.0 ± 0.0 | 2.3 ± 0.0 | **+15%** |
| 舱壁隔离，等待模式 | 1 | 18.9 ± 0.0 | 18.9 ± 0.0 | 0% |
| 舱壁隔离，等待模式 | 4 | 359 ± 36 | 338 ± 38 | −6% |
| 超时（同步） | 1 | 22.4 ± 0.2 | 22.2 ± 0.2 | −1% |
| 超时（同步） | 4 | 23.5 ± 0.3 | 23.5 ± 0.5 | 0% |
| 异步熔断器 | 1 | 24.4 ± 0.1 | 24.4 ± 0.1 | 0% |
| 异步重试 | 1 | 24.1 ± 0.1 | 24.1 ± 0.1 | 0% |
| 异步限流器 | 4 | 275 ± 1 | 273 ± 6 | −1% |
| 异步超时，待完成的 stage | 1 | 152 ± 17 | 152 ± 12 | 0% |
| 异步组合（五个策略） | 1 | 122 ± 0.3 | 122 ± 0.2 | 0% |

- **舱壁隔离的拒绝多花 0.3 ns**（在 Apple Silicon 上也测到了：2.06 → 2.36 ns）。已满的舱壁现在在返回 `false` 之前会检查 `final` 的事件监听器字段：这是拒绝事件的代价。成功路径上没有这样的检查。
- **已修复：有竞争的重试失败。** 第一次运行显示 *四个服务，重试一次* 在 4 线程下从 37 ns 变为 57 ± 22 ns，各 fork 分成约 40 ns 和 60–85 ns 两组。`RetryPolicy` 新增了第二个 `ClassValue`（用于分类器的判定），在没有分类器时从未被查询。每个 `ClassValue` 共享异常类那个小小的查找缓存，在某些 JVM 中多出来的这一个让查找发生冲突，退回到 `ClassValue` 较慢的路径，而该路径会写这个共享缓存。改为每个策略一个 `ClassValue`、返回三值判定，各 fork 恢复一致（39.2 → 37.6 ns）。
- **已修复：同步超时，单线程**（22.4 → 23.8 ns，复现了两次）。`TimeoutPolicy.end` 的超时分支因为事件调用而变大，这影响了 JIT 如何内联每次调用的方法。把该分支移到单独的方法中，使其回到 22.2 ns。
- 在第一次运行中，*舱壁隔离，等待模式* 在 4 线程下显示 366 → 452 ns；重复运行得到 359 → 338 ns，因此那个差异是 JVM 运行之间的噪声，而不是回退。

**在 Graviton3 上与 Resilience4j 2.4.0 对比**（新版本，ns/op 和 B/op）：

| 基准测试 | 线程数 | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| 重试，成功 | 1 | **1.2 ns**，0 B | 22.9 ns，0 B |
| 重试，重试一次（Resilience4j 去掉其 `sleep(0)`） | 1 | **6.5 ns**，0 B | 61.6 ns，16 B |
| 熔断器，成功（Resilience4j：计数窗口，默认 `SYNCHRONIZED` 策略） | 1 | **1.9 ns**，0 B | 89.2 ns，0 B |
| 熔断器，成功 | 4 | **1.9 ns**，0 B | 832 ns，2 B |
| 熔断器，混合 | 4 | **274 ns**，0 B | 910 ns，2 B |
| 限流器，空闲 | 4 | **187 ns**，0 B | 275 ns，40 B |
| 限流器，耗尽 | 4 | **34.7 ns**，0 B | 1599 ns，505 B |
| 舱壁隔离，空闲 | 1 | 20.5 ns，0 B | 20.4 ns，0 B |
| 舱壁隔离，空闲 | 4 | **264 ns**，0 B | 432 ns，0 B |
| 舱壁隔离，已满 | 1 | **2.3 ns**，0 B | 139 ns，416 B |
| 超时（同步） | 1 | **22.2 ns**，0 B | 8842 ns，176 B |
| 异步超时，待完成的 stage | 1 | **152 ns**，192 B | 598 ns，240 B |
| 异步超时，待完成的 stage | 4 | **369 ns**，192 B | 1563 ns，234 B |

**分类器**（`ClassifierBenchmark`：受保护调用上的一个基于结果的分类器，所有调用都成功）：

| 基准测试 | 线程数 | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| 熔断器，无分类器 | 1 | 2.1 ns，0 B | — |
| 熔断器 + 分类器 | 1 | **2.4 ns**，0 B | 102 ns，24 B（`recordResult`） |
| 熔断器 + 分类器 | 4 | **2.4 ns**，0 B | 902 ns，18 B |
| 重试 + 熔断器 + 分类器 | 1 | **2.7 ns**，0 B | 148 ns，104 B（`retryOnResult` + `recordResult`） |
| 重试 + 熔断器 + 分类器 | 4 | **2.8 ns**，0 B | 1274 ns，90 B |

在一次成功调用上，分类器每个策略约花费 0.3 ns：一次直接的、单态的 `classify` 调用。

**编程式 `Pipeline`**（`PipelineBenchmark`，ns/op；生成的代码和 pipeline 都分配 0 B）：

| 策略 | 线程数 | 生成的代码（注解） | `Pipeline` | Resilience4j |
|---|---:|---:|---:|---:|
| 重试 + 熔断器 | 1 | 2.3 | 2.9 | 175，288 B |
| 重试 + 熔断器 | 4 | 2.4 | 3.0 | 1647，830 B |
| 重试、熔断器、限流器、舱壁隔离 | 1 | 61.6 | 62.3 | 261，328 B |
| 重试、熔断器、限流器、舱壁隔离 | 4 | 318 | 294 | 1234，350 B |
| 全部六个策略 | 1 | 76.3 | 80.3 | — |
| 捕获变量的 lambda（重试 + 熔断器） | 1 | 2.4 | 2.8 | 190，336 B |

- 在这些组合上，pipeline 比生成的代码多约 0.5 ns，并且保持零分配，即使 lambda 每次调用都捕获一个参数也是如此（逃逸分析会消除它）。
- `pipelinePolluted`（同一个 pipeline，在另外五个 pipeline 经过 `Pipeline.execute` 运行之后）单线程测得 7.9 ns，未污染时为 2.9 ns：在有很多 pipeline 的应用中，`Pipeline` 内唯一的 `call.call()` 调用点会变成超多态（megamorphic）。生成的代码每个方法一个调用点，没有这项成本。
- Resilience4j 没有在调用者线程上执行的同步超时，因此六个策略那一行没有 Resilience4j 的数据。

## 注解类、泛型、配置覆盖、ticker 舱壁隔离（2026-10-24，AWS Graviton3） :id=annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3

Base：commit `53a6651`。New：注解类（子类风格）、泛型方法和通配符异步 stage 上的 `@Fallback`、`onExhaustedResult` / 分类器失败时的 `GaveUp` / `Pipeline.run` 和异步 pipeline 降级、`config` 的按实例覆盖，以及同步舱壁隔离在手动 ticker 上的 `maxWait`。方法与上一节相同（`infra/bench.sh`，19 台 `c7g.xlarge`，160 个单元，329 次运行；校准 20.51–20.54 ns，0.1% 离散度）。

**无回退检查**（resilience4cata，ns/op，除非注明均为 1 个线程；同步路径全部 0 B/op）：

| 基准测试 | Base | New | 变化 |
|---|---:|---:|---:|
| 重试，成功 | 1.3 ± 0.0 | 1.3 ± 0.0 | +2% |
| 重试，重试一次 | 6.5 ± 0.0 | 6.5 ± 0.0 | +1% |
| 重试，四个服务，重试一次（4 线程） | 37.8 ± 0.9 | 37.7 ± 0.5 | 0% |
| 熔断器，成功 | 1.8 ± 0.0 | 1.9 ± 0.0 | +1% |
| 熔断器，混合 | 17.2 ± 0.0 | 17.2 ± 0.0 | 0% |
| 限流器，空闲 | 41.7 ± 0.1 | 41.7 ± 0.1 | 0% |
| 限流器，耗尽 | 34.2 ± 0.1 | 34.6 ± 0.1 | +1% |
| 舱壁隔离，空闲 | 20.5 ± 0.0 | 20.5 ± 0.0 | 0% |
| 舱壁隔离，已满 | 2.3 ± 0.0 | 2.3 ± 0.0 | −1% |
| 舱壁隔离，等待模式 | 18.9 ± 0.0 | 18.9 ± 0.0 | 0% |
| 重试 + 熔断器上的分类器 | 2.8 ± 0.0 | 2.7 ± 0.0 | −3% |
| 异步熔断器 | 24.4 ± 0.1 | 24.4 ± 0.1 | 0% |
| 异步超时，待完成的 stage | 149 ± 12 | 149 ± 12 | 0% |
| 异步组合（五个策略） | 122 ± 0.2 | 122 ± 0.2 | 0% |
| 超时（同步） | 22.1 ± 0.2 | 23.7 ± 0.1 | **+7%** |
| 超时（同步），4 线程 | 23.6 ± 0.2 | 23.4 ± 0.3 | −1% |

- 单线程下除同步超时外，一切都在 ±2% 之内。四线程下差异有正有负，都在误差范围内（例如 *舱壁隔离，空闲* 258 → 228 ± 32 ns，*熔断器，混合* 257 → 271 ± 22 ns）。
- **未解决：同步超时单线程 +1.5 ns。** 它是稳定的（6 个 fork：22.1–22.4 对 23.6–23.8 ns，用 `-XX:-TieredCompilation` 也是：22.3–22.5 对 23.6–23.8 ns），但它运行的代码没有变化：生成的 `TimedService$Resilient`、`TimeoutPolicy` 和 `Watchdog` 完全相同，策略解析后的状态完全相同，watchdog 的 `ThreadLocal` 在两个构建中都落在它的本位槽中。四线程下没有差异。上一节对同一数字的"修复"（把超时分支移出 `end`）可能是同一种布局效应朝另一方向的表现。下一步：在机器上用 `-prof perfasm`（需要 `perf` 和 `hsdis`）比较编译后的代码。
- 带分类器的重试层在 `classify` 外面多了一个 `try`/`catch`（用于分类器抛异常时的 `GaveUp`）；分类器基准测试显示没有成本。

**本轮新增**（ns/op，0 B/op）：

| 基准测试 | 线程数 | 结果 |
|---|---:|---:|
| 重试，成功：注解类（子类）对接口包装器 | 1 | **1.0** 对 1.2 |
| 重试，重试一次：注解类对接口包装器 | 1 | **5.6** 对 6.5 |
| 重试，重试一次：注解类对接口包装器 | 4 | **5.5** 对 6.6 |
| `Pipeline.run`（void 调用），重试 + 熔断器 | 1 | 3.0（`execute`：2.9） |

- 子类风格比包装器略快：覆盖方法直接调用 `super.method()`，而不是经过 `delegate` 字段，少一次加载和一次接口调用。
- `Pipeline.run` 的成本与 `execute` 相同，并且不分配内存。
- 按设计未测量：配置的按实例覆盖（在创建时解析一次）以及舱壁隔离的 ticker 模式（仅用于测试时钟；默认路径在 `tryAcquire` 失败后多读一个 `final` boolean，而 *舱壁隔离，等待模式* 没有变化）。

## 重试预算，0.3.0（2026-10-25，AWS Graviton3） :id=retry-budget-030-2026-10-25-aws-graviton3

Base：`6d82305`（0.2.0）。New：0.3.0 的合并内容（重试预算、Micrometer、OpenTelemetry 和 JUnit 模块、`onInstanceCreated`）。方法同上（`infra/bench.sh`，`c7g.xlarge`；校准 20.48–20.53 ns，0.2% 离散度）。所有同步路径保持 0 B/op。

**不带预算时无回退**（ns/op，1 / 4 线程）：

| 基准测试 | Base | New |
|---|---:|---:|
| `RetryBenchmark.resilience4cataSuccess` | 1.4 / 1.3 | 1.2 / 1.3 |
| `MultiTypeRetryBenchmark.resilience4cataSuccess` | 4.3 / 4.3 | 3.9 / 4.0 |
| `PipelineBenchmark.pipelineRetryCircuitBreaker` | 2.9 / 2.9 | 2.9 / 2.9 |
| `RetryBenchmark.resilience4cataRetryOnce` | 6.7 / 6.5 | 6.8 / 6.7 |
| `SubclassBenchmark.wrapperRetryOnce` | 6.6 / 6.5 | 6.8 / 6.8 |

被重试的调用多花 0.1–0.3 ns（失败路径上对预算的 null 检查）；成功调用不变。

**预算对每次调用的计数**（`RetryBudgetBenchmark`，`budgetPercent = 1000`，因此每次重试都被批准；ns/op，1 / 4 线程；不带预算时：成功 1.3 / 1.3，重试 6.9 / 7.1）：

| 调用如何计数 | 成功 | 重试一次 |
|---|---:|---:|
| 在线程自己的条带上做 compare-and-set 循环（`threadId & mask`） | 9.7 / 9.8 | 64 / 380 |
| 在哈希得到的条带上做一次原子加（`getAndAddRelease`，LDADD） | 12.0 / 12.0 | 73 / 400 |
| **在哈希得到的条带上普通的读后写（已发布）** | **3.5 / 3.6** | **62 / 301** |

两条经验。一次 compare-and-set，即使在一条没有其他线程写的缓存行上、没有竞争，在 Graviton3 上每次调用也要约 8 ns，而单指令的原子加更贵；因此发布的计数器改为有损的（共享一个条带并发生竞争的两个线程可能丢失一次调用，这只会降低预算）。另外，用线程 id 的低位选取条带，会把所有 JMH 工作线程放到同一个条带上（它们的 id 恰好是条带数的倍数）：compare-and-set 版本为此付出了竞争的代价（380 ns），而有损版本丢失了太多调用，以至于即使留了所需的十倍余量，预算仍然拒绝重试。先对 id 做混合（Fibonacci 哈希）修复了这两个问题。4 个线程时，被重试的调用主要耗在共享的重试计数器和时钟读取上，而这些只发生在失败路径上。

## 对冲请求与异步取消，0.4.0（2026-10-25，AWS Graviton3） :id=hedge-and-async-cancellation-040-2026-10-25-aws-graviton3

Base：`4fc079a`（0.3.0 加文档）。New：0.4.0 的合并内容（所有异步策略中的向下取消、`@Hedge`、生成的子类不再是 `final`）。方法同上（`infra/bench.sh`，`c7g.xlarge`；校准 20.51–20.54 ns）。

**异步取消。** 第一版在构造函数中用一次 release 存储把每个策略的 future 链接到其内层 stage。在 Graviton3 上，C2 把它编译成每个策略层额外两个全屏障（`dmb ish`），每个约 5 ns（异步熔断器 24.4 → 29.1 ns）；在 Apple Silicon 上同样的屏障在噪声范围内。普通存储就足够了（future 总是通过 happens-before 关系传递出去），并把成本降到每层约 0.5 ns（ns/op，1 / 4 线程；内存分配不变）：

| 基准测试 | Base | Release 存储 | 普通存储（已发布） |
|---|---:|---:|---:|
| `AsyncBenchmark.resilience4cataCircuitBreaker` | 24.4 / 24.9 | 29.1 / 29.9 | 24.9 / 25.6 |
| `AsyncBenchmark.resilience4cataRetry` | 24.1 / 24.6 | 28.9 / 29.5 | 24.7 / 25.3 |
| `AsyncBenchmark.resilience4cataBulkhead` | 41.8 / 392 | 46.2 / 503 | 42.8 / 418 |
| `AsyncBenchmark.resilience4cataRateLimiter` | 66.0 / 275 | 71.2 / 294 | 66.9 / 278 |
| `AsyncBenchmark.resilience4cataStack` | 121.5 / 573 | 139.3 / 554 | 123.5 / 558 |

同一改动中的一次重构还把舱壁隔离的异步回调移到了一个共享的辅助方法中；多出来的一层调用让 C2 无法内联组合路径，逃逸分析也不再消除其中一个对象（四策略组合从 200 B/op 变为 224 B/op）。内存分配门禁发现了它；回调已重新放回一个方法中。

**对冲请求**（新增，无 base；ns/op 和 B/op，1 / 4 线程）。Resilience4j 2.4.0 的 `resilience4j-hedge` 使用相同设置（调整见 `HedgeBenchmark` 的 Javadoc）：

| 基准测试 | resilience4cata | Resilience4j 2.4.0 | 手写 `CompletableFuture` |
|---|---:|---:|---:|
| 第一次尝试已经完成 | 9.4 / 9.7 ns，24 B | — | — |
| 第一次尝试在延迟之前胜出 | 154 / 267 ns，216 B | 3601 / 5577 ns，1561 B | 649 / 1968 ns，297 B |
| 同上，无对冲预算 | 150 / 268 ns，216 B | | |
| 对冲请求触发（1 ms 延迟；µs） | 2114 / 2116 µs | 1067 / 1070 µs | |

对冲请求触发时，耗时是延迟加上各库定时器的精度：我们的定时器以延迟的十六分之一（至少 1 ms）为滴答，最多晚约两个滴答触发，因此 1 ms 的延迟约在 2 ms 后发出对冲；Resilience4j 的调度 executor 准时触发，但把每个被取消的延迟都留在队列中，这正是其常见路径上大部分成本的来源。生成的子类去掉 `final` 后没有可测量的变化（`SubclassBenchmark` 在噪声范围内）。

## 自适应限流，0.6.0（2026-10-26，AWS Graviton3） :id=adaptive-limit-060-2026-10-26-aws-graviton3

Base：`67e1ebd`（0.5.0 加文档）。New：`@AdaptiveLimit` 的合并内容。方法同上（`infra/bench.sh`，`c7g.xlarge`，3 forks；在 19 台机器上校准 20.47–20.50 ns）。`AdaptiveLimitBenchmark` 是新增的，因此没有 base；ns/op 和 B/op，1 / 4 线程，所有线程使用同一个限流器（公平性调整见基准测试的 Javadoc：相同的上限、Netflix 默认的容差和窗口，以及 `minRttThreshold(0)`，使 Netflix 像 `@AdaptiveLimit` 一样记录每个样本）：

| 基准测试 | ns/op，1 / 4 线程 | B/op，1 / 4 线程 |
|---|---:|---:|
| `@AdaptiveLimit`（Gradient2，上限从未达到） | 86.3 / 380 | 0 / 0 |
| 同上，在已完成 stage 上的异步调用 | 108.7 / 565 | 48 / 48 |
| 同上，每次调用都被拒绝（上限已满） | 2.1 / 2.1 | 0 / 0 |
| 上限相同的固定 `@Bulkhead` | 20.5 / 284 | 0 / 0 |
| Netflix concurrency-limits 0.5.4，`SimpleLimiter` + 窗口化的 `Gradient2Limit` | 118.0 / 1087 | 40 / 95 |
| Netflix，每次调用都被拒绝 | 3.0 / 2.9 | 0 / 0 |

- **与固定舱壁隔离的差别在于时钟。** 自适应限流必须为每次调用计时，因此要读两次 `System.nanoTime()`。在这台机器上一次读取约 33 ns（耗尽的限流器读一次时钟、几乎不做别的事，耗时 34.6 ns），因此两次读取解释了单线程上 `@AdaptiveLimit` 与 `@Bulkhead` 之间的 66 ns；限流器自身的工作（槽位、写到本线程条带上的样本、窗口检查）在这个噪声之内。
- **与 Netflix 的限流器相比：** 单线程快 27%，四线程快 2.9 倍，并且不分配内存。Netflix 每次调用都分配 `Optional` 和监听器，其窗口化样本由所有线程共享；而 `@AdaptiveLimit` 把每个样本写到调用线程的条带上，只让结束窗口的那个线程读取所有样本。
- **拒绝只读不写：** 无论线程数多少都是 2.1 ns。

套件的其余部分没有变化，只有 `Pipeline` 例外：未配置 `adaptiveLimit` 的 pipeline 现在每次调用多检查一个 null 字段，+0.2 ns（`pipelineRetryCircuitBreaker` 3.0 → 3.2 ns；生成的代码不变）。第一次运行中有四个 4 线程基准测试变化了 15–48%（`CircuitBreakerBenchmark.resilience4cataMixed`、`PipelineBenchmark.pipelineFour`、`BulkheadBenchmark.resilience4cataWaiting`、`HedgeBenchmark.resilience4cataPending`），尽管它们的代码没有改变；每个用 6 个 fork 重跑后，base 与 new 的范围完全重叠（−7% 到 +5%），因此它们是噪声。

## Reactor 支持与异步限流器，0.11.0（2026-10-27，AWS Graviton3） :id=reactor-support-and-the-async-rate-limiter-0110-2026-10-27-aws-graviton3

Base：`9a26494`（0.10.0 加文档）。New：`resilience4cata-reactor`，以及不再 park 的异步限流器。方法同上（`infra/bench.sh`，`c7g.xlarge`，3 forks；在 19 台机器上校准 20.45–20.48 ns）。

**`Mono` 方法与 resilience4j-reactor 2.4.0 对比。** `ReactorBenchmark` 是新增的，因此没有 base。每个基准测试构建受保护的 `Mono` 并订阅它；Resilience4j 使用其 Reactor 操作符配合 `transformDeferred`，以及 `AsyncBenchmark` 的设置（公平性说明见基准测试的 Javadoc）。数据源是 `Mono.just`（订阅时值已存在），对于 `*Pending` 则是一个在订阅后立即填充的 `Sinks.One`，使每一层都要等待。ns/op 和 B/op，1 / 4 线程；字节数取典型 fork（见下面的说明）：

| 基准测试 | resilience4cata，ns/op | Resilience4j，ns/op | resilience4cata，B/op | Resilience4j，B/op |
|---|---:|---:|---:|---:|
| 无策略（`baseline`） | 52.4 / 53.1 | — | 88 | — |
| 熔断器 | **130.2 / 118.8** | 246.4 / 1378.7 | 200 | 248 |
| 重试，第一次尝试成功 | **127.8 / 139.0** | 726.2 / 734.1 | 200 | 1000 |
| 超时 | **105.2 / 105.3** | 3165.5 / 6229.7 | 176 | 1344 |
| 舱壁隔离 | **144.1 / 621.7** | 157.5 / 683.7 | 200 | 200 |
| 限流器 | **166.5 / 351.6** | 184.2 / 659.3 | 200 | 240 / 195 |
| 重试 + 熔断器 + 超时 + 舱壁隔离 | **227.3 / 587.7** | 4337.8 / 5598.1 | 368 | 2624 |
| 同一组合，`MonoPipelineOperator` | 251.0 / 606.2 | （同样的 Resilience4j 组合） | 448 | — |
| 无策略，pending（`baselinePending`） | 152.7 / 154.3 | — | 168 | — |
| 超时，pending | **441.0 / 560.5** | 3725.1 / 6332.8 | 480 | 1787 |
| 组合，pending | **703.1 / 935.5** | 4394.8 / 6513.9 | 912 | 2704 |

- **每一组对比都更快，单线程和四线程都是。** 差距：从 1.9 倍（熔断器，单线程）到 11.6 倍（熔断器，四线程），重试 5.7 倍，组合在单线程上 19 倍。舱壁隔离最接近：单线程快 9%，四线程在误差范围内（621.7 ± 107.6 对 683.7 ± 38.0），两者都要为一个共享计数器付出代价。
- **超时：** 订阅时就已存在的值不可能错过截止时间，因此 `@Timeout` 不会为它启动定时器（105 ns）；Resilience4j 的 `TimeLimiterOperator` 就是 `Mono.timeout`，每次订阅都会在 Reactor 的 parallel 调度器上调度一个任务。值处于 pending 时两者都会启动定时器，resilience4cata 仍快 8.4 倍（单线程）和 11.3 倍（四线程）。
- **订阅本身 88 B 之外的每次调用字节数：** 包在常量 `Mono` 外的一个策略花费 112 B（方法的 `Mono` 及其 lambda、递送值的订阅、调用的 lambda、策略的 future、常量的已完成 future）；它的值不需订阅即可读取，Reactor 自己的操作符也是这样做的。这些对象在某些 fork 中被标量替换，在另一些中则没有：同一个基准测试在一个 fork 中测得 200 B，在另一个中测得 224–280 B，因此内存分配门禁的预算取最高的 fork，而不是这些典型值。

**异步限流器不再 park。** `RateLimiterPolicy.reserve` 过去在其 `tat` 上的 CAS 失败后会 park 1 ns，与同步的 `tryAcquire` 一样。异步调用者可能是事件循环线程，绝不能 park，BlockHound 也报告了这一点（examples 中的 `MonoBlockHoundTest`），因此 `reserve` 现在立即重试 CAS（`Thread.onSpinWait()` 也会被 BlockHound 报告）。`AsyncBenchmark.resilience4cataRateLimiter`，base → new：单线程 67.1 → 66.9 ns，四线程 277.7 ± 0.6 → 297.2 ± 28.1 ns（+7%）；两者都是 48 B。测量的其他 `AsyncBenchmark` 方法没有变化（熔断器 25.0 → 24.9 / 25.6 → 25.6，组合 123.6 → 123.4 / 589.0 → 558.0）。在 Apple M1 上（JDK 21，4 forks），同样的改动在竞争下代价大得多，四线程时 264.5 ± 52.2 → 661.0 ± 29.0 ns，那里 park 也让失败者远离了 macOS 上有竞争的 `System.nanoTime()`；指数式 `onSpinWait` 退避（最多 64 次自旋）在那里测得同样的 659.9 ± 16.0 ns，因此被否决。同步路径仍然会 park。

## Flux 方法与已完成的 stage，0.11.0（2026-10-29，AWS Graviton3） :id=flux-and-done-stages

Base：`4649484`（这次调优之前的 `Flux` 支持）。New：`53cba0a`：没有超时的尝试中，元素原样交给订阅者；超时在监听时使用更便宜的序列化器；没有重试或降级时不设尝试边界；仲裁器（arbiter）的快速路径；以及熔断器、舱壁隔离、限流器和自适应限流的"已完成"捷径。方法同上（`infra/bench.sh`，`c7g.xlarge`，3 forks；在 19 台机器上校准 20.51–20.54 ns）。

**`Flux` 方法与 resilience4j-reactor 2.4.0 对比。** `FluxBenchmark` 是新增的，因此没有 base。每个基准测试构建受保护的 `Flux` 并订阅，每个元素都到达一个 `Blackhole`；一次操作是一整个流。Resilience4j 使用其 Reactor 操作符配合 `transformDeferred` 以及 `ReactorBenchmark` 的设置；在 `Flux` 上它的 time limiter 是 `flux.timeout`，一个每个元素都会重启的截止时间，与 `@Timeout` 相同（公平性说明见基准测试的 Javadoc）。除非注明，订阅时就有十个元素；`pending`：订阅后立即发出十个元素，使每一层都要等待，两种超时都会启动定时器；`1000 elements`：订阅时就有一千个元素。ns/op 为 1 / 4 线程，B/op 为单线程上的典型 fork：

| 基准测试 | resilience4cata，ns/op | Resilience4j，ns/op | resilience4cata，B/op | Resilience4j，B/op |
|---|---:|---:|---:|---:|
| 无策略（`baseline`） | 56.0 / 57.3 | — | 96 | — |
| 熔断器 | **195.1 / 189.3** | 272.3 / 1322.0 | 283 | 216 |
| 重试，第一次尝试成功 | **270.3 / 215.8** | 1196.8 / 1257.1 | 392 | 1024 |
| 超时 | **680.5 / 688.4** | 28896.1 / 63439.9 | 384 | 3983 |
| 舱壁隔离 | **212.3** / 754.9 | 244.6 / **697.2** | 288 | 219 |
| 限流器 | 240.1 / **354.1** | **234.3** / 885.4 | 283 | 248 |
| 重试 + 熔断器 + 超时 + 舱壁隔离 | **788.8 / 984.3** | 30583.2 / 63403.4 | 616 | 5104 |
| 同一组合，`FluxPipelineOperator` | 824.4 / 1040.0 | （同样的 Resilience4j 组合） | 752 | — |
| 无策略，pending（`baselinePending`） | 1088.3 / 1004.5 | — | 1248 | — |
| 超时，pending | **1842.8 / 2135.3** | 30396.9 / 50648.3 | 1656 | 5390 |
| 组合，pending | **2157.6 / 2703.1** | 31580.6 / 55194.6 | 2224 | 6264 |
| 无策略，1000 个元素（`baselineLong`） | 1043.2 / 1042.6 | — | 96 | — |
| 超时，1000 个元素 | **45057.8 / 45107.2** | 2632248.7 / 5172988.8 | 384 | 241529 |
| 组合，1000 个元素 | **46189.5 / 46545.0** | 2822600.4 / 5586559.5 | 579 | 242903 |

- **差距在超时上：** Reactor 的 `flux.timeout` 为每个元素在 parallel 调度器上调度一个任务（这里每个元素约 2.6 µs 和 240 B）；`@Timeout` 每个元素读一次时钟、写一个字段，不移动定时器（每个元素约 44 ns，0 B），因此一千个元素耗时 45 µs，对比 2.6 ms。十个元素时：单线程 42 倍，四线程 92 倍；组合 39 倍和 64 倍。
- **熔断器**单线程快 1.4 倍，四线程快 7 倍，那里 Resilience4j 的熔断器发生竞争。**重试**快 4.4 倍和 5.8 倍：Resilience4j 的 `RetryOperator` 每次订阅都要构建一个重试上下文和重新订阅的机制。
- **有两种情况在误差范围内：** 四线程的舱壁隔离（754.9 ± 36.7 对 697.2 ± 41.8，两者都要为一个共享计数器付出代价，和 `Mono` 方法一样），以及单线程的限流器（240.1 ± 9.7 对 234.3 ± 0.9）。单线程的舱壁隔离和四线程的限流器更快（1.2 倍和 2.5 倍）。
- **字节数：** 一个受保护的流会分配其 `Flux`、调用的订阅、尝试及其订阅者，以及各层的 lambda，订阅之外约 190 B，每个流一次；它的元素不分配内存。对于单个熔断器、舱壁隔离或限流器，Resilience4j 分配得更少（各一个订阅者），但一旦涉及重试或超时就多得多。

**调优，在进行时于 Apple M1 上测量（JDK 21，2 forks，十个元素）：** 第一版在单个策略上落败（熔断器 428 ns 对 148 ns）。每个元素两次 CAS 来将元素与结束信号串行化，每个元素约花费 8.5 ns；替代它们的 store-buffering 配对约 7–11 ns（在 volatile 写之后的 volatile 读要等待 store buffer），因此没有超时的尝试的元素（其结束只可能来自它自己的流）完全跳过它（在那里熔断器 217 → 146 ns）。"已完成"捷径以及在没有重试时跳过的边界拿下了剩下的部分。

**现有基准测试中的"已完成"捷径**，base → new，ns/op 1 / 4 线程（单线程上的字节数）：

| 基准测试 | Base | New |
|---|---:|---:|
| `AsyncBenchmark` 熔断器 | 24.9 / 25.5（48 B） | **9.7 / 9.2**（24 B） |
| `AsyncBenchmark` 舱壁隔离 | 42.7 / 396.1（48 B） | **28.4 / 312.1**（24 B） |
| `AsyncBenchmark` 限流器 | 67.1 / 283.8（48 B） | **52.5** / 289.6（24 B） |
| `AsyncBenchmark` 组合 | 123.4 / 586.2（200 B） | **91.2 / 474.5**（152 B） |
| `AdaptiveLimitBenchmark` 异步 | 108.6 / 505.1（48 B） | **94.7** / 499.8（24 B） |
| `ReactorBenchmark` 熔断器 | 129.3 / 121.7 | **105.7 / 105.3**（176 B） |
| `ReactorBenchmark` 舱壁隔离 | 140.8 / 514.5 | **126.4** / 525.3（176 B） |
| `ReactorBenchmark` 限流器 | 166.7 / 360.7 | **146.5** / 377.6（176 B） |
| `ReactorBenchmark` 组合 | 228.5 / 567.4 | **201.4 / 540.8**（376 B） |

调用返回时已经完成的 stage（缓存的值、`Mono.just`、在订阅期间就已结束的流）现在会立即被记录，并以一个已完成的副本作答，就像超时早已做的那样，而不再使用链接的 future、lambda 以及 `whenComplete` 的依赖 future。重试、超时、对冲请求、pending 的 stage 以及所有同步路径的变化都没有超出误差（`PipelineBenchmark`、`HedgeBenchmark`、`AsyncBenchmark.resilience4cataRetry`/`Timeout*`、`ReactorBenchmark.*Pending`：都在 ±9% 之内，全部落在误差范围内）。`ReactorBenchmark` 中 Resilience4j 的数字也没有变化（熔断器 243.3 / 1290.7，组合 4318.7 / 6671.2），因此与 resilience4j-reactor 相比，`Mono` 熔断器现在单线程快 2.3 倍，四线程快 12.3 倍。

## 框架 bean 与全面对比，0.13.0（2026-11-01，AWS Graviton3） :id=frameworks

每个基准测试对比每个竞品，外加三个新的基准测试，调用框架交给应用的 bean：`SpringBenchmark`（Spring Boot 4.1.2，resilience4j-spring-boot4 2.4.0 的切面）、`MicronautBenchmark`（Micronaut 5.2.2 运行在 JDK 25 上，resilience4j-micronaut 2.4.0 的拦截器以及 Micronaut 自己的 `@Retryable`/`@CircuitBreaker`）和 `QuarkusBenchmark`（Quarkus 3.40.1，SmallRye Fault Tolerance 的注解，以及在 bean 中构建的 Resilience4j 装饰器）。resilience4cata 的 bean 是各集成创建的生成子类。New：`f6e9097`。方法同上（`infra/bench.sh`，`c7g.xlarge`，3 forks；在 19 台和 10 台机器上校准 20.48–20.53 ns，两次运行）。设置与核心基准测试相同（重试 3 次尝试、无延迟，熔断器计数窗口 100，舱壁隔离 1000、不等待，永不耗尽的限流器，超时 1 s）；每个 setup 都会检查每个 Resilience4j 实例确实参与了调用，因此缺失的拦截器不会被误认为很快（公平性说明见各基准测试的 Javadoc）。在每个框架中，普通 bean 的成本为 0.5–1.6 ns。

**框架 bean**，ns/op 1 / 4 线程，与该框架中最快的竞品对比（单线程上的 B/op：resilience4cata 在每一行都分配 0 B）：

| 调用 | Spring：resilience4cata | Spring：Resilience4j | Micronaut：resilience4cata | Micronaut：最快的竞品 | Quarkus：resilience4cata | Quarkus：最快的竞品 |
|---|---:|---:|---:|---:|---:|---:|
| 重试，第一次尝试成功 | **1.1 / 1.1** | 1346.0 / 1530.9 | **1.1 / 1.0** | 104.8 / 100.4 (R4j) | **1.1 / 1.1** | 22.9 / 51.5 (R4j) |
| 重试，失败一次 | **6.1 / 6.7** | 5068.3 / 6007.2 | **10.2 / 9.3** | 959.5 / 1758.8 (R4j / Micronaut) | **6.2 / 6.3** | 471.3 / 568.4 (R4j) |
| 熔断器 | **1.5 / 1.4** | 1737.0 / 2142.5 | **1.5 / 1.5** | 304.5 / 1500.7 (R4j) | **1.5 / 1.5** | 101.6 / 897.6 (R4j) |
| 打开的熔断器 | **2.3 / 2.3** | 4174.2 / 4444.6 | **2.3 / 2.3** | 1127.4 / 1316.6 (R4j) | **2.3 / 2.3** | 355.9 / 452.7 (R4j) |
| 舱壁隔离 | **20.4 / 280.4** | 1687.7 / 4411.2 | **20.7 / 279.9** | 490.2 / 2797.8 (R4j) | 20.4 / **270.2** | 20.3 / 460.8 (R4j) |
| 限流器 | **42.0 / 184.4** | 1481.8 / 3956.2 | **42.0 / 181.7** | 141.9 / 629.8 (R4j) | **42.0 / 180.6** | 66.7 / 270.2 (R4j) |
| 超时，同步 | — | — | — | — | **24.2 / 23.2** | 1510.2 / 2246.9 (FT) |
| 超时，已完成的 future | **3.7 / 3.7** | 3632.2 / 8404.7 | **4.2 / 4.2** | 27816.7 / 17510.6 (R4j) | **3.7 / 3.7** | 444.1 / 1194.5 (R4j) |
| 重试 + 熔断器 + 限流器 + 舱壁隔离 | **62.1 / 289.6** | 6104.4 / 8317.4 | **62.1 / 268.2** | 1117.9 / 3474.8 (R4j) | **62.0 / 329.3** | 217.2 / 1067.9 (R4j) |

- **Spring：** resilience4j-spring-boot4 的切面每次调用花费 1.3–6 µs 和 1.6–6.3 KB：它们的切点是动态的，匹配时每次调用都要按名称加载一个类（`Class.forName`）。resilience4cata 的子类就是一个普通的覆盖方法，因此一个 Spring bean 的成本就是策略在核心基准测试中的成本。
- **Micronaut：** resilience4j-micronaut 的拦截器花费 100 ns–1.1 µs；Micronaut 自己的 `@Retryable` 和 `@CircuitBreaker` 花费 1.0–1.8 µs 和 2.3–3.6 KB（`micronautRetry`、`micronautCircuitBreaker`）。经由拦截器的 Resilience4j 超时在不同 fork 之间波动很大（27.8 ± 23.1 µs）。
- **Quarkus：** Fault Tolerance 的拦截器每次调用花费 0.3–1.9 µs 和 0.6–2.2 KB；Resilience4j 的装饰器在一个完全没有拦截器的 bean 中手工调用，在除同步超时（Resilience4j 没有）之外的每一行都是最快的竞品。它的舱壁隔离（两个原子操作，和我们一样）在单线程上打平：20.3 ± 0.0 对 20.4 ± 0.1。

**其他基准测试**，与上面各节相同的行，重新与最快的 Resilience4j 变体（`LockFree`、`Synchronized`、`NoSleep`）或 Netflix 的限流器对比：本次运行和框架基准测试中共 156 个可比较的情况，赢了 150 个。其余六个是单线程的 Quarkus 舱壁隔离（见上文）和下面五个；允许等待的舱壁隔离在单线程上险胜、在四线程上打平，因此也列出：

| 基准测试 | resilience4cata | 最快的竞品 | 原因 |
|---|---:|---:|---|
| `HedgeBenchmark` 对冲请求触发（1 ms 延迟），1 / 4 线程 | 2114.7 / 2116.6 | **1067.6 / 1069.4** | `TimeoutTimer` 的粗粒度滴答：绝不提前，最多晚约 2 个滴答，因此 1 ms 的对冲请求在约 2 ms 后开始 |
| `BulkheadBenchmark` 空闲槽位，1 线程 | 20.5 ± 0.0 | **20.4 ± 0.0** | 两者都是两个原子操作：硬件的下限 |
| `BulkheadBenchmark` 允许等待，1 / 4 线程 | **18.9** / 427.0 ± 85.1 | 20.4 / 445.6 ± 52.6 | 四线程时打平 |
| `FluxBenchmark` 舱壁隔离，4 线程 | 740.4 ± 83.9 | 688.2 ± 38.6 | 在误差范围内（309 B 对 203 B） |
| `FluxBenchmark` 限流器，1 线程 | 256.6 ± 22.9 | 234.2 ± 1.0 | 在误差范围内（296 B 对 248 B） |

## 准时的对冲延迟（2026-11-01，AWS Graviton3） :id=hedge-precision

Base：`04e045e`（main）。New：`7c9f25f`：对冲请求的延迟进入 `TimeoutTimer` 的一条精确通道；调用者传入它的开始时间（一次时钟读取），截止时间是 `start + delay`，定时器线程在最早的此类截止时间醒来，而不只是在下一个滴答。方法同上（`infra/bench.sh`，`c7g.xlarge`；三次运行，后两次用 6 forks；校准 20.49–20.54 ns）。ns/op 1 / 4 线程（触发的对冲请求为 µs）：

| 基准测试 | Base | New | Resilience4j |
|---|---:|---:|---:|
| 对冲请求触发（1 ms 延迟），µs | 2113.5 / 2116.8 | **1059.6 / 1067.7** | 1067.3 / 1070.5 |
| 第一次尝试仍在运行，调用返回 | 149.9 / 280.3 | 179.4 / 259.1 | 3667.7 / 5441.8 |
| 同上，无预算 | 152.4 / 256.7 | 180.1 / 268.7 | — |
| 第一次尝试已经完成 | 9.4 / 9.7 | 9.5 / 9.7 | — |

- **触发的对冲请求**过去最多会晚两个定时器滴答才开始：在 1 ms 延迟下滴答为 1 ms，定时器在调用后最多一个滴答才取到该条目，然后再等待完整的延迟，因此在这个闭环中每次调用两者都要付出（2.1 ms）。现在它在延迟加上线程唤醒时间时触发，与 Resilience4j 的 `ScheduledThreadPoolExecutor` 相同：单线程快 0.7%，四线程快 0.3%（在那里属于误差范围内）。两个库都不可能更低：两者都要等待延迟和操作系统的唤醒。
- **代价是一次时钟读取**，发生在调用返回时第一次尝试仍在运行的情况下，单线程约 29 ns（四线程时变化在误差范围内）。已经完成的尝试不读时钟。仍比 `resilience4j-hedge` 快约 20 倍。
- **异步超时没有变化**（`AsyncBenchmark.resilience4cataTimeout*`、`TimeoutBenchmark`、`ReactorBenchmark` 和 `FluxBenchmark` 的超时：都在 ±5% 之内）。第一版通过反转每个收件箱来按从旧到新的顺序取出条目，这让定时器线程把每个收件箱遍历两遍：四线程下的 `AsyncBenchmark.resilience4cataTimeoutPending` 在两次运行中从 436 ns 变为 514 ns，因为定时器线程与基准测试的线程共享这四个核心。现在只有精确条目会被重新排序，普通条目像以前一样一遍取完（455.0 ± 61.1 → 437.1 ± 36.7）。

## Flux 流：每次尝试少一个对象，每层不再复制（2026-11-01，AWS Graviton3） :id=flux-ended

Base：`04e045e`（main）。New：`b6d6ac4`：一个 `Flux` 尝试就是它自己的订阅者（一个对象而不是两个），并且在订阅期间就已结束的流以一个共享、不可变的 stage（`Elements.ended()`）穿过每一个策略层，而不是每层一个已完成的副本；超时、`Pipeline` 和尝试边界都能识别它。方法同上（`infra/bench.sh`，`c7g.xlarge`，在 19 台机器上 3 forks；舱壁隔离、限流器和 resilience4j 的行再次用 6 forks 运行，表中显示的就是这些结果；校准 20.47–20.53 ns）。ns/op 1 / 4 线程，B/op 为单线程上的典型 fork：

| `FluxBenchmark` | Base | New | Base B/op | New B/op | Resilience4j |
|---|---:|---:|---:|---:|---:|
| 熔断器 | 189.2 / 204.0 | **182.8 / 200.5** | 288 | 264 | 272.2 / 1401.7 |
| 舱壁隔离 | 211.1 / 762.2 | **194.6 / 702.8** | 288 | 264 | 267.7 / 721.0 |
| 限流器 | 239.8 / 376.7 | **222.5 / 348.2** | 283 | 264 | 229.2 / 843.0 |
| 重试，第一次尝试成功 | 249.7 / 232.3 | 252.8 / 222.7 | 392 | 392 | 1202.0 / 1277.3 |
| 超时 | 676.9 / 683.9 | **665.7 / 666.2** | 384 | 360 | 29051.8 / 63827.3 |
| 重试 + 熔断器 + 超时 + 舱壁隔离 | 787.8 / 1023.1 | **751.3 / 928.8** | 616 | 512 | 31170.0 / 61439.4 |
| 同一组合，`FluxPipelineOperator` | 822.6 / 1047.2 | **778.6 / 975.3** | 752 | 648 | — |

- [全面对比](#frameworks)中**处于误差范围内的两个 `Flux` 情况**现在领先了：单线程限流器领先 3%（Resilience4j 的各 fork 测得 234 ns，只有一个是 205 ns），四线程舱壁隔离领先 2.5%，仍在双方误差范围内（我们的 fork 592–804 ns，Resilience4j 的 695–742 ns：两者都在竞争下为一个共享计数器付出代价，和 `Mono` 方法一样）。单线程的舱壁隔离快 1.4 倍。
- **字节数**：每个单一策略每个流少 24 B，组合少 104 B。对于一个熔断器、舱壁隔离或限流器，一个流仍比 resilience4j-reactor 的操作符多分配 27–48 B（它的 `Flux`、调用的订阅、尝试以及各层的 lambda，对比一个操作符订阅者），而一旦涉及重试或超时就少得多。
- **未变化：** pending 流和长流、重试（它的尝试对已结束的流本来就跳过边界），以及 `ReactorBenchmark`（`Mono`）和 `AsyncBenchmark` 的每一行，都在误差范围内。第一次运行显示四线程的 `Flux` 限流器慢 10%、`Mono` 重试最多慢 10%，各自来自一两个慢的 fork；用六个 fork 时两者分别快 8% 和没有变化。四线程的同步自适应限流（`AdaptiveLimitBenchmark.resilience4cata`，本次改动未触及的代码）在那次运行中测得 +40%，用六个 fork 时 +6%（342.3 ± 43.6 → 364.2 ± 45.2），而它旁边的普通舱壁隔离变化了 −12%：那里的四线程行在不同运行之间就是有这么大的波动。

## 使用无等待舱壁隔离原子操作的等待舱壁隔离（2026-11-01，AWS Graviton3） :id=bulkhead-semaphore

Base：`410a649`（main）。New：`6395eb1`：在 `maxWait > 0` 时，舱壁隔离过去使用 `java.util.concurrent.Semaphore`，其 `tryAcquire` 和 `release` 是在 AQS 状态上的全屏障 compare-and-set 循环；现在它使用 `BulkheadSemaphore`，其许可是一个带填充的计数器，通过一次 opaque 读和一次 acquire compare-and-set 获取，与不等待时相同，而 `AbstractQueuedSynchronizer` 只负责让等待的线程排队和 park。释放是一次 volatile 加，随后唤醒队列。方法同上（`infra/bench.sh`，`c7g.xlarge`，在 8 台机器上 6 forks；校准 20.48–20.51 ns）。ns/op 1 / 4 线程：

| 基准测试 | Base | New | Resilience4j |
|---|---:|---:|---:|
| `BulkheadBenchmark`，允许等待 | 18.9 / 385.1 | **18.6 / 209.7** | 20.1 / 475.4 |
| `BulkheadBenchmark`，不等待（代码未变） | 20.5 / 263.9 | 20.5 / 257.4 | 20.0 / 430.4 |
| `AsyncBenchmark` 舱壁隔离（不等待） | 28.4 / 321.2 | 28.5 / 329.2 | 40.9 / 590.3 |
| `QuarkusBenchmark` 舱壁隔离（不等待） | 20.4 / 275.0 | 20.4 / 278.7 | 20.3 / 467.3 |

- **四线程时，等待舱壁隔离比之前快 1.8 倍**，比 Resilience4j 的 `SemaphoreBulkhead` 快 2.3 倍，而之前两者打平（都在一个共享状态上使用 AQS 的全屏障原子操作）。
- **等待舱壁隔离现在比不等待的更快**（18.6 对 20.5 ns，210 对 257 ns），尽管两者获取槽位的方式相同。剩下的唯一区别是归还的方式：等待版本用 volatile `getAndAdd`，不等待版本用 `getAndAddRelease`。
- 字节数不变（同步 0 B）。不等待的舱壁隔离没有变化，在误差范围内。

## 用 volatile 加归还槽位（2026-11-01，AWS Graviton3） :id=release-counters

不等待的舱壁隔离和自适应限流过去用 `getAndAddRelease` 归还槽位，这是在那里正确的最弱原子操作；现在它们使用 volatile `getAndAdd`（`95aa67a`）。更强的原子操作测得更快。这来自[等待舱壁隔离](#bulkhead-semaphore)的发现：它的释放是一次 volatile 加，而它已经变得比不等待的舱壁隔离更快。release 变体在 Graviton3 上为何更慢，没有得到证实。两次运行（`infra/bench.sh`，`c7g.xlarge`，6 forks）：异步、`Mono`、`Flux` 和框架各行在 8 台按需机器上对比 `a0139b2`；同步、自适应限流和 `Pipeline` 各行来自一次 base 为 `410a649` 的运行，其代码与 `a0139b2` 的区别只在 `Flux` 和异步捷径上，这些行用不到。校准基准测试就是这个舱壁隔离，因此它现在测得 17.3 ns 而不是 20.5。ns/op 1 / 4 线程：

| 基准测试 | Base | New | Resilience4j |
|---|---:|---:|---:|
| `BulkheadBenchmark`，不等待 | 20.5 / 259.0 | **17.3 / 195.5** | 20.4 / 347.6–450.3 |
| `QuarkusBenchmark` 舱壁隔离 | 20.4 / 264.1 | **17.2 / 198.6** | 20.3 / 381.4–467.5 |
| `SpringBenchmark` 舱壁隔离 | 20.4 / 278.8 | **17.2 / 197.4** | 1696.8 / 3934.1 |
| `MicronautBenchmark` 舱壁隔离 | 20.8 / 276.5 | **17.3 / 264.3** | 496.2 / 2760.2 |
| `FluxBenchmark` 舱壁隔离 | 195.2 / 737.4 | **191.3 / 537.5** | 257.2 / 729.3 |
| `ReactorBenchmark` 舱壁隔离 | 126.3 / 518.2 | **122.8 / 464.3** | 157.2 / 686.0 |
| `AsyncBenchmark` 舱壁隔离 | 28.4 / 346.0 | **25.4** / 359.5 | 40.9 / 510.5 |
| `AsyncBenchmark` 组合 | 91.8 / 410.6 | **87.8 / 343.5** | 990.9 / 1766.5 |
| `AdaptiveLimitBenchmark` 同步 | 86.1 / 345.3 | **83.2 / 289.8** | 118.0 / 1068.6 (Netflix) |
| `AdaptiveLimitBenchmark` 异步 | 94.7 / 441.9 | **92.2 / 369.5** | — |
| `PipelineBenchmark` 四个策略，生成的代码 | 62.2 / 339.3 | **59.7 / 268.1** | 259.7 / 1253.0 |

- **最后两个打平的情况现在都赢了**：单线程上有空闲槽位的舱壁隔离，直接调用（17.3 对 20.4 ns）和在 Quarkus 中（17.2 对 20.3 ns），[全面对比](#frameworks)曾把它们算作在硬件下限处打平。那个下限并不是硬件的。
- **四线程的 `Flux` 舱壁隔离**在 [Flux 改动](#flux-ended)之后只是在误差范围内领先，现在快 1.4 倍（537.5 对 729.3 ns）。
- 在误差范围内的行：四线程的异步舱壁隔离（+4%，± 57 ns），以及四线程下包含舱壁隔离的 `Pipeline` 各行（+6 到 +8%，± 12 到 ± 38 ns，来自另一个 base 的那次运行）。Resilience4j 自己的各行在两个版本之间、代码相同的情况下，四线程时变化最多达 30%。

## 真实失败率下的熔断器（2026-11-02，AWS Graviton3） :id=failure-rates

熔断器的标题数字是两个极端：每次调用都成功（四线程 1.9 ns）和十次调用失败一次（274 ns）。大多数健康的服务处于两者之间，因此 `CircuitBreakerFailureRateBenchmark` 让 1 000 次调用中失败一次（0.1 %）或 100 次中失败一次（1 %），配置与 `*Mixed` 基准测试相同（100 次调用的计数窗口，100 次后开始判断，50 % 时打开，两个库使用同样预先分配的无栈失败）。下面成功和十分之一失败的行来自同一次运行（`CircuitBreakerBenchmark`）。代码为 0.13.0 之后（`1ea4fc6`），作为每台机器的两半运行（8 台机器，`-f 6`；两半在误差范围内一致，因此表格给出它们的平均值），每次调用的纳秒数：

| 失败 | 线程数 | resilience4cata | Resilience4j `SYNCHRONIZED` | Resilience4j `LOCK_FREE` |
|---|---:|---:|---:|---:|
| 无 | 1 | **1.9** | 89.9 | 134.6 |
| 无 | 4 | **1.9** | 807.8 | 1708.3 |
| 0.1 % | 1 | **4.6** | 103.6 | 146.3 |
| 0.1 % | 4 | **11.2** | 1024.8 | 1596.8 |
| 1 % | 1 | **16.2** | 104.6 | 147.4 |
| 1 % | 4 | **65.4** | 1005.5 | 1592.0 |
| 10 % | 1 | **17.2** | 98.3 | 136.3 |
| 10 % | 4 | **278.0** | 939.6 | 1615.2 |

resilience4cata 在每一行都分配 0 B；Resilience4j 为 0 到 26 B（`SYNCHRONIZED`）和 208 到 816 B（`LOCK_FREE`）。

- **成本取决于窗口中包含失败的频率**（从代码推断，未经 profiling）。1 000 次调用中失败一次时，大小为 100 的窗口在十分之一的调用期间包含该失败；其余十分之九是安静的，每次读一个字段。100 次中失败一次时，窗口中总有一个失败，因此每次调用都要推进共享的窗口游标；此时大多数调用发现自己的槽位已经是成功，便不再写别的。十次中失败一次时，失败几乎每一圈都在槽位之间移动，因此更多调用要重写槽位和共享计数器。
- **四线程时与 Resilience4j 的差距**：0.1 % 时 91 倍，1 % 时 15 倍，10 % 时 3.4 倍。Resilience4j 的成本几乎不随失败率变化：无论如何它都会记录每次调用。
- 唯一有离散的一行：四线程 1 %，两半分别为 63.1 和 67.6 ns（fork 从 57 到 74 ns），这是竞争下的共享游标。

## 1.0 前审查中的修复，以及每个新虚拟线程上的超时（2026-11-02，AWS Graviton3） :id=review-fixes

[1.0 计划](plan-1.0.md)（第 3 阶段）修复了审查中发现的四个竞态：异步调用者等待时舱壁隔离的一个槽位被闲置、条带回退后自适应限流的窗口被读成 1 ns、限流器根据 park 之前读取的时钟计算等待时间，以及同步超时清除了调用者原本就有的中断。Base `9184cc5` 对比 `a307d4b`，10 台机器，`-f 6`，单线程和四线程下每次调用的纳秒数：

| 基准测试 | Base | New |
|---|---:|---:|
| `RateLimiterBenchmark`，许可空闲 | 41.7 / 187.0 | 41.7 / **183.6** |
| `RateLimiterBenchmark`，耗尽 | 34.6 / 34.8 | 34.6 / 34.8 |
| `BulkheadBenchmark`，等待模式，空闲 | 18.7 / 204.8 | 18.6 / 209.9 |
| `BulkheadBenchmark`，空闲 | 17.3 / 205.9 | 17.3 / 208.1 |
| `AdaptiveLimitBenchmark` | 83.6 / 306.7 | 83.1 / 290.9 |
| `TimeoutBenchmark` | 23.8 / 23.6 | 23.8 / 23.5 |

- **限流器现在在竞争失败后会重新读取时钟**，因此有竞争的调用者不再从一个过时的时间开始等待。额外的读取只在已经 park 过的路径上：四线程时 183.6 对 187.0 ns（fork 182.9–184.0 对 185.5–188.8）。
- 其他修复都在冷路径上（放弃等待的同步等待、结束的窗口、触发的超时）。四线程的舱壁隔离各行变化了 +1 % 和 +3 %，与同一次运行中的噪声相当：Resilience4j 自己的舱壁隔离代码未变，却变化了 +3 % 和 +9 %。

**每次调用一个新虚拟线程的同步超时**（`VirtualThreadTimeoutBenchmark`，新增）：每次调用都启动一个虚拟线程，在其上进行带超时的调用并 join 它，因此每次调用都是其线程的第一次调用，都要把线程注册到 watchdog（一个 `ThreadScopes` 对象，以及向 watchdog 共享队列的一次添加；死掉的线程会在下一个滴答时被遗忘）。

| 线程数 | 线程，普通调用 | 线程，`@Timeout` 调用 | 差异 |
|---:|---:|---:|---:|
| 1 | 9291 ns，487 B | 9254 ns，760 B | 无可测量差异，+273 B |
| 4 | 9274 ns，443 B | 10069 ns，711 B | +795 ns（+8.6 %），+268 B |

启动虚拟线程占主导。四个线程同时启动时，超时给每个线程增加约 0.8 µs，最可能的原因是注册要经过的共享队列（推断，未经 profiling）。在一个进行多次带超时调用的线程上，只有第一次需要付出代价。

## 候选版本上的首页数据行（2026-11-02，AWS Graviton3） :id=release-candidate

首页上的四个对比，在 1.0.0-RC1 的代码上重新测量（`439ba5c`；发布提交只改动了版本号），以便它们都来自同一次运行。每个基准测试都作为每台机器的两半运行（8 台机器，`-f 6`）；表格给出两半的平均值，每次调用的纳秒数和字节数：

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 重试，第一次尝试成功 | 1 | **1.4 ns**，0 B | 22.9 ns，0 B |
| 重试，第一次尝试成功 | 4 | **1.3 ns**，0 B | 23.0 ns，0 B |
| 熔断器，每次调用都成功（Resilience4j 使用 `SYNCHRONIZED`） | 1 | **1.9 ns**，0 B | 89.9 ns，0 B |
| 熔断器，每次调用都成功 | 4 | **1.9 ns**，0 B | 849.5 ns，2 B |
| 同步超时（Resilience4j：`TimeLimiter` + executor） | 1 | **23.6 ns**，0 B | 8907.6 ns，183 B |
| 同步超时 | 4 | **23.6 ns**，0 B | 9976.8 ns，151 B |
| 舱壁隔离，有空闲槽位 | 1 | **17.3 ns**，0 B | 20.1 ns，0 B |
| 舱壁隔离，有空闲槽位 | 4 | **192.3 ns**，0 B | 410.8 ns，0 B |

- 单线程重试在两半之间有差异，代码相同：一半为 1.42 到 1.54 ns，另一半为 1.25 到 1.28 ns，各自在六个 fork 之间都很稳定。这是 JVM 运行之间的布局效应；表格给出平均值 1.4 ns。0.1.0 的标题数字测得 1.2 ns。
- 同步超时测得 23.6 ns，与 [MVP 之后的那次运行](#annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3)（23.7 ns）一致；0.1.0 标题数字中的 22.2 ns 此后再未出现。
- 四线程时，舱壁隔离（192.3 ns）和 Resilience4j 的（fork 从 361 到 528 ns）都带有通常的竞争离散。

## 竞争下的百分位数（2026-11-12，AWS Graviton3） :id=percentiles

其他各节给出的是每次调用的平均时间，这会掩盖尾部。这里同样的 `RateLimiterBenchmark` 和 `BulkheadBenchmark` 在 JMH 的 `SampleTime` 模式下运行，该模式为单个调用计时：在 `580a090` 上执行 `MACHINES=6 JMH_OPTIONS="-bm sample" infra/bench.sh HEAD HEAD '^(RateLimiterBenchmark|BulkheadBenchmark)[.]'`，6 台 `c7g.xlarge`，每个基准测试和线程数 3 forks，每次运行都作为其机器的两半（表格给出两半的平均值；四线程时每行有 120 万到 220 万次计时调用）。

如何解读：

- **每个值都包含约 34 ns 的测量开销。** 每个被采样的调用都用两次时钟读取计时：`baseline` 行（直接调用服务，没有策略）的中位数为 34 ns，而平均时间模式测得约 1 ns。请在行与行之间比较，而不要与其他各节的平均值比较。
- **2 到 6 µs 的 p99.99 来自机器**，而不是库：基线也有（一次中断、一次 JIT 或 GC 安全点）。最大的样本（四线程时约 10 ms）在基线中也有，因此被排除。
- **闭环**，与其他各节一样：每个线程在调用返回后立即再次调用，因此一次停顿的调用会推迟该线程后续的调用，而不是堆积更多调用者。开环运行（按固定速率发起调用，延迟从计划的开始时间算起）尚未进行。

四个线程，共享一个限流器或舱壁隔离：

| 场景 | 库 | p50 | p90 | p99 | p99.9 | p99.99 | 平均值 |
|---|---|---:|---:|---:|---:|---:|---:|
| 无策略（`baseline`） | — | 34 ns | 35 ns | 46 ns | 52 ns | 3.5 µs | 54 ns |
| 限流器，许可空闲 | resilience4cata | 78 ns | 81 ns | 160 ns | **110 µs** | 320 µs | 539 ns |
| | Resilience4j 2.4.0 | 100 ns | 102 ns | 167 ns | 910 µs | 8.4 ms | 4.4 µs |
| 限流器，耗尽（全部被拒绝） | resilience4cata | 74 ns | 76 ns | 78 ns | 81 ns | 3.9 µs | 96 ns |
| | Resilience4j 2.4.0 | 431 ns | 488 ns | 56.7 µs | 224 µs | 387 µs | 2.1 µs |
| 舱壁隔离，有空闲槽位 | resilience4cata | 348 ns | 410 ns | 526 ns | 555 ns | 5.9 µs | 373 ns |
| | Resilience4j 2.4.0 | 540 ns | 784 ns | 1.1 µs | 1.8 µs | 6.2 µs | 605 ns |
| 舱壁隔离，允许等待，有空闲槽位 | resilience4cata | 354 ns | 477 ns | 544 ns | 716 ns | 5.4 µs | 407 ns |
| | Resilience4j 2.4.0 | 576 ns | 850 ns | 1.5 µs | 3.1 µs | 7.6 µs | 643 ns |
| 舱壁隔离，已满（全部被拒绝） | resilience4cata | 36 ns | 38 ns | 47 ns | 54 ns | 2.8 µs | 48 ns |
| | Resilience4j 2.4.0 | 297 ns | 316 ns | 348 ns | 1.9 µs | 15.3 µs | 347 ns |

- **有竞争的限流器有平均值所掩盖的尾部。** 0.1 % 到 1 % 的调用在争夺共享 `tat` 时失败，park 1 ns，然后等待 110 µs 或更久（p99.9），最长 320 µs（p99.99）；539 ns 的平均值主要来自这些调用。这比[限流器一节](#rate-limiter-1-and-4-threads-2026-10-24)预期的 50 µs 定时器松弛更长。Resilience4j 的 `AtomicRateLimiter` 也会让失败者 park，它的尾部在 p99.9 长 8 倍（910 µs），在 p99.99 长 26 倍（8.4 ms）。这只在被放行的调用到达速度快于共享缓存行在核心间移动的速度时才会发生，这里是四个什么别的都不做的线程。
- **耗尽的限流器没有尾部**：拒绝只读不写，因此直到 p99.9 的每个百分位数都在其中位数 30 ns 之内。Resilience4j 的拒绝会写其共享状态，并在竞争失败时 park：p99 为 56.7 µs。
- **舱壁隔离没有 park 造成的尾部**：槽位通过一次 compare-and-set 获取，通过一次加法归还，有空闲槽位时没有人 park。resilience4cata 的 p99.9 为 555 ns（允许等待时 716 ns），Resilience4j 为 1.8 µs（3.1 µs）。
- **已满舱壁隔离的拒绝在 resilience4cata 中保持在基线水平**（中位数 36 ns，一次读取和一个预先分配的异常）。Resilience4j 的在 p99.9 达到 1.9 µs（未调查）。

单线程时没有任何东西会 park。resilience4cata 的各行直到 p99.9 都在其中位数 10 ns 之内（限流器 80 → 88 ns，舱壁隔离 54 → 62 ns）。Resilience4j 的各行在调用通过时也一样，在拒绝时 p99.9 达到 1.5 到 1.9 µs（它的限流器每次拒绝分配约 550 B，[见上文](#rate-limiter-1-and-4-threads-2026-10-24)）。

## CPU 限制下的服务（2026-11-15，AWS Graviton3） :id=cpu-limit

试点（`pilot/README.md`：基于 Maven Central 上 1.0.0-RC4 的 Spring Boot、Micronaut 和 Quarkus 服务，以每秒 1500 个订单的 k6 负载运行 10 分钟，其中后端宕机一分钟、缓慢另一分钟，一台 `c7g.4xlarge`，JDK 25）运行了三次：不加限制，以及每个服务分别限制为 1 个和 0.5 个核心（`CPU=<cores> pilot/run.sh aws`）。该限制是一个周期为 100 ms 的 cgroup v2 配额，也就是 Kubernetes `limits.cpu` 所设置的东西；服务的线程运行在 4 个核心上，因此它可能提前用完一个周期的配额，然后被冻结到下一个周期。有限制时 JVM 只看到 1 个处理器。在除第一分钟（JIT 编译期间）以外的时间，服务平均使用 0.34–0.42 个核心。

| | 无限制 | 1 核 | 0.5 核 |
|---|---|---|---|
| 被限流的周期，第一分钟 | – | 16–22 % | 67–99 %（线程每秒等待 1.6–2.2 s） |
| 被限流的周期，稳定阶段 | – | 0 % | Spring 8 %，Micronaut 0 %，Quarkus 2 % |
| 200 ms `@Timeout` 的最晚中断，在方法中的时间 | 221.6–225.1 ms | 最长 1629 ms | 最长 4802 ms，全部在第一分钟 |
| 最早中断 | 188 ms | 188 ms | 188 ms |
| 第一分钟内由打开的熔断器应答的订单 | 0 % | Spring 2.5 % | 2.9–35.9 % |

- **超时只有在进程被冻结时才会迟到。** 不加限制时，最晚的中断发生在 watchdog 的两个滴答之内（200 ms 超时的滴答为 25 ms）。在 0.5 核时，每个晚于 250 ms 的中断都发生在第一分钟，那时服务大部分时间都在被限流：watchdog 的线程和被中断的线程一起被冻结。在稳定阶段没有晚于 250 ms 的中断。任何一次运行中都没有超时提前触发。
- **资源不足的冷启动会打开熔断器**：调用确实花了超过 200 ms，而超时会被算作依赖的失败。熔断器在 `waitInOpen`（5 s）后再次关闭。
- **策略在每次运行中都守住了它们的上限**：舱壁隔离中最多 40 个调用，限流器的许可，熔断器在结束时关闭，没有任何东西残留在内部，一个库线程，堆保持平稳。
- **在这个负载下 Spring 需要多于半个核心**：在 0.5 核时它的调用慢到在稳定阶段填满了舱壁隔离（1.4 % 的订单由舱壁隔离的降级应答，p99 318 ms）。Micronaut 和 Quarkus 通过了每一项检查。

## 原生镜像的资源占用（2026-11-17，GitHub runner） :id=native-footprint

resilience4cata 给原生镜像增加了什么：`./gradlew :native:footprint:footprint`（`native` workflow 的 `native (footprint)` 作业）构建原生测试中的 Spring Boot 和 Micronaut 应用（`native/spring`、`native/micronaut`：一个注解类、一个带降级的被包装接口、两种风格的 `Mono` 和 `Flux` 方法、YAML 配置、健康检查、Micrometer 指标、端点），以及不带该库的相同源码（`native/spring-plain`、`native/micronaut-plain`：不使用注解处理器编译，classpath 上没有任何 resilience4cata 模块，即同样的 bean 但不受保护）。四个镜像依次启动，每个 10 次。启动时间是从启动进程到第一个被应答的 `GET /stock/abc` 的时间，在带该库的应用中这是一次经过重试和熔断器的调用。RSS 在该调用之后立即用 `ps` 测量，并在 8 个线程向五个端点发出 2000 个请求之后再测一次。GraalVM CE for JDK 25.0.2，`ubuntu-24.04` runner（x86，4 个 CPU），Spring Boot 4.1.2，Micronaut platform 5.2.4。中位数，以及 10 次运行的范围：

| 镜像 | 大小 | 启动 | 启动后 RSS | 负载后 RSS |
|---|---:|---:|---:|---:|
| Spring Boot | 91.6 MB | 78 ms (73–129) | 122.5 MB | 149.8 MB (145.2–149.9) |
| 不带 resilience4cata 的 Spring Boot | 89.7 MB | 67 ms (64–81) | 113.4 MB | 139.2 MB (139.1–139.3) |
| Micronaut | 79.3 MB | 32 ms (28–37) | 86.9 MB | 134.2 MB (133.6–134.7) |
| 不带 resilience4cata 的 Micronaut | 77.3 MB | 27 ms (25–34) | 82.5 MB | 131.1 MB (130.9–131.6) |

差异（带减去不带）：两者都是 **镜像 +1.9 MB**；**启动 +11 ms（Spring Boot）和 +5 ms（Micronaut）**；**启动后 RSS +9.1 和 +4.4 MB，负载后 +10.6 和 +3.1 MB**。该差异涵盖了带该库的应用所拥有的一切：core、config、框架集成、Micrometer 绑定、reactor 模块、生成的类和策略的状态，以及第一次调用的工作（注册表的实例、从 YAML 读取的配置）。额外的 RSS 用在了哪里没有分析。这些数字针对的是这些小型应用；更大的应用会在两边都加入自己的类，因此该库所占的比例更小。RSS 在不同运行之间几乎不变，启动时间相差几毫秒。

## 内存分配门禁 :id=allocation-gate

共享 CI runner 上的计时是噪声，但每次操作分配的字节数不是，因此 CI 以它为门禁。`./gradlew :benchmark:allocationCheck` 以简短的设置、三个 fork 运行 `benchmark/allocation-budgets.properties` 中列出的每个基准测试（`-f 3 -wi 3 -w 200ms -i 3 -r 200ms -t 1 -prof gc`），如果分配最少的那个 fork 达到其预算 + 1 B/op，或者某个基准测试从结果中缺失，就会失败。用三个 fork 是因为 C2 在每个 JVM 中只决定一次是否对某个对象做标量替换：一个 fork 在每次迭代中保持相同的字节数，而下一个 fork 可能多分配一两个对象。在 CI runner（x86）上，单 fork 门禁的 21 次运行测得 `FluxBenchmark.resilience4cataPipelineStack` 为 696、728、752 和 776 B，`resilience4cataTimeout` 为 384 到 440 B，因此门禁时不时会因为根本没有分配的改动而失败；更长的预热反而让每个 fork 都落在较高的值上（Apple M1）。代码开始进行的内存分配会在每个 fork 中显现，因此最低的 fork 仍然能捕获它。表格显示每个 fork 的字节数。它会打印一张表格并写入 `benchmark/build/jmh/allocation-report.md`；`alloc-gate` workflow 在每个 PR 上运行它。添加一个基准测试只需一行：`<BenchmarkClass>.<method> = <bytes per op>`。

预算：每个同步成功和拒绝路径都是 0 B/op（重试、熔断器 计数/时间/打开、限流器 空闲/耗尽、舱壁隔离 空闲/已满/等待、超时、分类器、生成的代码和 `Pipeline`（包括 `run` 和捕获变量的 lambda）、子类和包装器）。异步路径会分配它们的 future：重试 48 B；自 0.11.0 起，熔断器、舱壁隔离、限流器、自适应限流和超时在已完成 stage 上为 24 B（一个已完成的副本）（[见上文](#flux-and-done-stages)），四策略组合为 152 B（之前为 200 B）；pending stage 上的超时为 200 B（测得 191–192 B；定时器线程的共享工作会有几个字节的波动，8 B 的余量仍少于每次调用一个对象）。于 2026-10-24 在本地 JDK 21（Apple M1）上测量。`CircuitBreakerBenchmark.*Open`（熔断器被强制打开，每次调用都被拒绝）是为门禁而加的；为了公平，Resilience4j 在那里设置了 `writableStackTraceEnabled(false)`。

## 浸泡测试（soak test） :id=soak-test

每次调用泄漏几个字节，或者每个曾经调用过的线程泄漏一个对象，在只运行几秒钟的测试中是看不出来的。`./gradlew :examples:soakTest -Psoak.duration=2h`（默认 2 分钟）在真实时钟上运行每一个策略，包括同步、异步、`Mono` 和 `Flux`，外加一个 `Pipeline`。负载来自平台线程和新的虚拟线程。后端会应答、失败、太晚应答或从不应答，每四秒中有一秒是失败风暴，因此熔断器一直在打开和关闭。一些调用者会取消。堆固定为 512 MB，测试在运行期间约采样 60 次：

- 一次完整 GC 后的堆；
- 库的线程；
- 超时 watchdog 为其保留 scope 的线程；
- 定时器任务；
- Micrometer 的 meter、OpenTelemetry 的序列（两个模块都监听注册表；OpenTelemetry 通过周期性 reader 每秒导出一次）以及注册表实例。

出现以下情况时测试失败：

- 运行最后四分之一中 GC 后的最低堆，比 20% 到 45% 之间的最低堆高出 16 MB 以上（`-Psoak.heapBudgetMb`）；
- 预热之后出现了新的库线程或注册表实例，或者某个 meter 或 OpenTelemetry 序列不属于任何实例；
- 负载停止后，OpenTelemetry 和 Micrometer 统计的事件不一致；
- 负载停止后，还残留有调用、定时器任务、舱壁隔离或自适应限流的槽位，或者已死线程的 watchdog scope；
- 出现意外的失败，或 Reactor 丢弃了一个错误。有一种情况会被计数但允许：调用者自己的 `timeout` 操作符丢弃了一个与其取消发生竞争的错误，这是 Reactive Streams 规则所允许的。

报告写入 `examples/build/soak/report.md`，堆增长时还会附带一个堆转储。`soak` workflow 每周在 JDK 21 和 25 上运行该测试 2 小时，也可以手动以选定的时长运行，并把报告复制到作业摘要中。

该测试经过了变异检验，于 2026-10-31 在本地 JDK 21（Apple M1，8 个 CPU）上进行：

- 当 watchdog 永不遗忘已死线程时，测试在一分钟后失败：watchdog 检查失败（注册了 89,576 个线程），堆检查也失败了（22.7 → 46.5 MB）。
- 当每个延迟的异步任务都被保留时，堆检查在两分钟后失败（50.8 → 158.0 MB）。
