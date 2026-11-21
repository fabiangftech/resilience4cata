# 03 — 性能内幕：各策略的算法与并发

> **历史文档。** 本研究早于实现，按原样保留；关于库目前的功能，请参阅[功能](../policies/README.md)、[架构](../architecture.md)和[基准测试](../benchmarks.md)。

> 第 1 阶段研究。没有生产代码。简短的代码片段仅作示意。最后更新：2026-10-23。
> 范围：每种弹性策略应使用哪种算法，以及如何用现代 Java（21+）实现它，使热路径开销低、无内存分配，并在竞争下可扩展。包括 Resilience4j 目前的做法及其代价，以及一套基准测试方法。
> 不在范围内（由同系列其他文档覆盖）：竞争者概况（01）、注解处理机制、API/配置对外形态（04）、模块布局（05）。

## 证据图例 :id=evidence-legend

| 标签 | 含义 |
|---|---|
| **[SRC]** | 通过阅读源代码验证。Resilience4j 为 `master` 提交 [`b7c0069`](https://github.com/resilience4j/resilience4j/tree/b7c0069802e373ed72dff0b817c270fbb6e966ba)（2026-10-07，2.4.0 之后）。OpenJDK 为标签 `jdk-21+35` 和 `jdk-25+36`。Netty `4.1`。 |
| **[DOC]** | 根据官方文档、JEP 页面、规范或论文验证。 |
| **[INF]** | 由我们根据代码、JVM 行为或一般知识推断，**未经测量**。必须在后续阶段用 JMH 和/或 jcstress 验证。 |
| **[PROP]** | 我们的设计提案。它是有待基准测试的假设，而不是事实。 |

没有标注 **[DOC]** 或 **[SRC]** 的数字只是用于推理的数量级。

---

## 0. 要点速览 :id=_0-tldr

| 策略 | Resilience4j 目前（主要开销） | 对 resilience4cata 的建议 |
|---|---|---|
| 熔断器 | 每个被记录的结果都要经过 `ReentrantLock`（默认的 `SYNCHRONIZED` 策略）；每次调用一个新的 `Snapshot`；基于时间的窗口和 OPEN 状态中有 `Instant` 分配；`LOCK_FREE` 选项每次调用分配一个节点和一份统计副本。[SRC] | 状态字放在 `VarHandle` 的 int/long 中。计数窗口用一个通过 `getAndSet` 更新的槽数组，加上按结果分类的计数器。时间窗口用一个带纪元标记的桶环。只在比率可能上升时才评估阈值。半开状态使用带代数标记的许可。所有路径零内存分配。[PROP] |
| 限流器 | `AtomicRateLimiter`：每次 CAS 尝试都新建一个不可变的 `State`，装箱的 `Supplier<Long>` 时钟，`parkNanos(1)` 退避，周期边界处的固定窗口突发。`SemaphoreBasedRateLimiter`：一个**公平**信号量，每个限流器一个调度线程。[SRC] | 在单个 `long`（理论到达时间）上用 CAS 循环实现 **GCRA**。令牌桶配置可以映射到它上面。没有内存分配，没有后台线程。[PROP] |
| 舱壁隔离 | `Semaphore.tryAcquire(timeout)`，每次调用为事件创建一个捕获型 lambda，每次释放还要操作第二个有竞争的原子变量（`grantsInProgress`）。[SRC] | 一个快速失败的计数器（`getAndAdd`），只有在 `maxWait > 0` 时才走在信号量上等待的慢路径。有了 Loom，线程池 bulkhead 只是小众功能。自适应限流是一个单独的策略。[PROP] |
| 重试 | 即使第一次尝试就成功，每次调用也有四次内存分配（`ContextImpl` + 3 个原子变量）；间隔函数中有装箱。[SRC] | 尝试状态放在局部变量中（生成的代码或一个循环）。只在失败时分配。使用 `ThreadLocalRandom` 的去相关抖动。用一个 `long` 上的令牌桶实现重试**预算**。[PROP] |
| 超时 | 同步路径需要一个 `Future`（一次线程切换）。异步路径每次调用都使用 `ScheduledExecutorService`。[SRC] | 一个中断调用者的看门狗时间轮（虚拟线程使 I/O 可被中断）。哈希或分层时间轮，O(1) 取消。没有线程切换。[PROP] |
| 横切关注点 | 某些事件在“有无消费者”检查之前就已分配；每个拒绝异常都调用 `String.format`；lambda 嵌套产生超多态调用点。[SRC][INF] | 原始类型参数的监听器回调，由一个可常量折叠的“无监听器”检查守卫。无堆栈、预分配的拒绝异常，或者不抛异常的结果。**按调用点生成的、直线式的 acquire/release 代码，而不是嵌套的 lambda。** 每个管道共享一对 `nanoTime`。[PROP] |

最大的单一杠杆可能不是某个算法，而是**组合**（§7.4）。Resilience4j 的装饰器 lambda 在整个应用中每种策略类型只共享一个字节码调用点，所以 JIT 看到的是超多态的 `supplier.get()` 调用，无法跨层内联或做逃逸分析。一个注解驱动的库可以为每个带注解的方法生成单态的、直线式的代码。[INF]

---

## 1. 底层工具箱（现代 Java） :id=_1-low-level-toolbox-modern-java

### 1.1 原子原语 :id=_11-atomic-primitives

- **`VarHandle` 与 `Atomic*`。** 两者编译成相同的 CPU 指令（x86 上是 `LOCK CMPXCHG` / `LOCK XADD`；AArch64 上是 LSE `CAS`/`LDADD` 或 LL/SC）。在策略对象的字段上使用 `VarHandle`，可以避免单独的 `AtomicLong` 带来的额外对象和指针跳转。它还提供更弱的模式（`getOpaque`、`getAcquire`/`setRelease`、`weakCompareAndSetPlain`），在 ARM 上更便宜。使用 `static final VarHandle` 字段，让 JIT 把它们当作常量。[DOC]（[VarHandle javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/VarHandle.html)）
- **`getAndAdd` 与 CAS 循环。** `getAndAdd`（x86 `LOCK XADD`，ARM `LDADD`）总能用一条指令成功。CAS 循环在竞争下可能重试很多次。优先使用能用 `getAndAdd` 表达的算术，把 CAS 留给需要“比较”语义的转换（状态机、GCRA）。[INF]
- **`LongAdder` / `Striped64`。** 写入进入按线程探针选择的单元，所以有竞争的递增可以扩展。读取（`sum()`）是 O(单元数) 的，且不是原子快照。它非常适合指标计数器，但不适用于需要一致、精确值的决策（例如“count < max 时才放行”）。[DOC]（[LongAdder](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/LongAdder.html)）
- **锁。** 无竞争的 `ReentrantLock` 加锁约一次 CAS，解锁约一次 release 存储。在竞争下它会让线程停放（park），而一个被调度出去的持锁者会让所有人停滞（Resilience4j 自己的配置 Javadoc 也这样说，见 §2.1）。`synchronized` 在 JDK 21–23 上会固定虚拟线程（JDK 24 中由 [JEP 491](https://openjdk.org/jeps/491) 修复）。库在持有监视器时绝不能阻塞。[DOC]

### 1.2 伪共享与填充 :id=_12-false-sharing-and-padding

- 两个被频繁、独立写入的字段位于同一条 64 字节缓存行上（在许多 Intel CPU 上因相邻行预取而是 128 字节，Apple M 系列的行大小也是 128 字节），会使多个核心争夺这条缓存行。[INF]
- `@jdk.internal.vm.annotation.Contended`（最初来自 [JEP 142](https://openjdk.org/jeps/142)）只有在 JVM 以 `-XX:-RestrictContended` 运行时才对用户类生效，并且它位于内部包中。库不能依赖它。[DOC]
- **通过类层次手动填充**（JCTools 和 Agrona 的做法：`class Pad0 { long p01..p16; } class Value extends Pad0 { volatile long value; } class Pad1 extends Value { long p17..p32; }`）之所以有效，是因为 HotSpot 先布局父类字段。这是可移植的技术。[INF]
- **每个策略对象的布局规则：** 把以读为主的配置（阈值、间隔）和不可变引用放在一组。把每个**被独立写入**的热字（状态、计数器、TAT）隔离到各自填充过的缓存行上。不要对所有东西都做填充：填充耗费内存，当有 1 万个以上命名实例时（多租户）这很重要。让填充成为针对“热”实例的构建时选择，或者只填充写入频繁的字。[PROP]

### 1.3 时间 :id=_13-time

- `System.nanoTime()` 的开销：在使用 TSC 时钟源的 Linux 上，约 **25 ns 延迟和约 26 ns 粒度**，在线程间扩展良好。在 Windows 上（较早的测量）延迟约 14 ns，但**粒度约 370 ns**，并且在 32 个线程下**不能**扩展（约 15 µs）。[DOC]（[Shipilëv, *Nanotrusting the Nanotime*](https://shipilev.net/blog/2014/nanotrusting-nanotime/)，JDK 7 时代的数字；需要在 JDK 21/25 以及 macOS/AArch64 上重新测量。）
- `Clock.instant()` / `Instant.now()` 会分配一个 `Instant` 并读取墙钟时间，而墙钟时间不是单调的。用它测量时间间隔是错误的，在热路径上也是浪费。[INF]
- **缓存时钟：** 一个守护“ticker”线程每 1 ms 把 `nanoTime()` 写入一个 `volatile long`。读者只付出一次普通加载。代价：一个常驻线程、1 ms 的分辨率，以及 ticker 在 CPU 饥饿下停滞的风险（时间冻结，超时永远不触发）。适合**粗粒度决策**（OPEN 的等待是否结束？属于哪个时间桶？），但不适合低于约 10 ms 的慢调用阈值。[INF]
- **提案：** 一个 `Ticker` 抽象，有三种实现：`nano`（默认）、`cached(1ms)`（需主动启用）和 `manual`（测试用）。每个**管道**一对 `nanoTime()`，而不是每个策略一对（§7.5）。当管道中没有任何策略需要时长时（没有慢调用阈值、没有延迟指标、没有自适应限流），就完全跳过计时。[PROP]

### 1.4 内存分配与 JIT :id=_14-allocation-and-the-jit

- C2 的逃逸分析可以消除短命的内存分配，但前提是一切都被内联。超多态调用点（超过 2 种接收者类型）、深层内联（默认的 `MaxInlineLevel` 在 JDK 14 中通过 [JDK-8234863](https://bugs.openjdk.org/browse/JDK-8234863) 从 9 提高）和大方法都会使它失效。库代码必须**在构造上就无内存分配**，而不是“如果 JIT 手下留情就无内存分配”。[DOC][INF]
- 在 Resilience4j 中看到的装箱陷阱：`Function<Clock, Long>` 时间戳、`Supplier<Long>` 纳秒时间、`CheckedConsumer<Long>` 休眠、`Function<Long, Long>` 退避。每个大于 127 的 `Long` 都可能是一次内存分配。[SRC]
- CI 门禁：JMH `-prof gc` 必须对每条正常路径和每条拒绝路径报告 `gc.alloc.rate.norm ≈ 0 B/op`（§9）。[PROP]

---

## 2. 熔断器 :id=_2-circuit-breaker

### 2.1 Resilience4j 的做法 [SRC] :id=_21-how-resilience4j-does-it-src

文件：[`CircuitBreakerStateMachine`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java)、[`CircuitBreakerMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerMetrics.java)、[`FixedSizeSlidingWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/FixedSizeSlidingWindowMetrics.java)、[`SlidingTimeWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/SlidingTimeWindowMetrics.java)、[`LockFreeFixedSizeSlidingWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/LockFreeFixedSizeSlidingWindowMetrics.java)、[`CASBackoffUtil`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/CASBackoffUtil.java)、[`CircuitBreakerConfig`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/CircuitBreakerConfig.java)。

- **状态：** `AtomicReference<CircuitBreakerState>`。每个状态是一个对象（`ClosedState`、`OpenState`、`HalfOpenState`……），持有自己的 `CircuitBreakerMetrics` 和 `AtomicBoolean`。转换使用 `getAndUpdate(lambda)`，会分配新的状态对象（很少发生，所以没问题）。
- **CLOSED 下的放行：** `isClosed.get()`，所以快速路径很便宜。
- **记录（默认策略 `SYNCHRONIZED`）：** 每次 `onSuccess`/`onError` 都会获取一个 `ReentrantLock`，更新一个 `TotalAggregation` 加一个环形桶，并**分配一个 `SnapshotImpl`** 作为返回值。然后 `CircuitBreakerMetrics.checkIfThresholdsExceeded` 在**每一次**调用（包括成功的调用）中计算失败率和慢调用率。
  - 基于计数：一个 `Measurement` 对象数组，头部索引在锁的保护下。
  - 基于时间：每**秒**一个 `PartialAggregation`（`timeWindowSizeInSeconds`），并且**每次记录**都调用 `clock.instant().getEpochSecond()`（除非逃逸分析消除，否则会分配一个 `Instant`）。
- **`LOCK_FREE` 策略**（在 PR [#2241](https://github.com/resilience4j/resilience4j/pull/2241) 中加入，2024 年 12 月，需主动启用；默认仍是 `SYNCHRONIZED`）：一个由不可变节点组成的链表。每次 `record` 都**复制**尾部的 `PackedAggregation`，**分配一个新的 `Node`**，用 CAS 把它接上，然后推进头/尾。竞争下的退避：先 `Thread.onSpinWait()` 自旋几次，然后用带线程 id 抖动的 `LockSupport.parkNanos(...)`。配置的 Javadoc 自己说明了其中的权衡：无锁“allocat[es] more objects”，而 synchronized 意味着“one thread that acquired the lock and is superseded from the CPU will block all other threads”。
- **计时：** `getCurrentTimestamp()` 调用 `Function<Clock, Long>`（默认 `clock -> System.nanoTime()`），所以它是**装箱的**，每次调用两次。
- **OPEN 状态：** `tryAcquirePermission` 在**每个被拒绝的调用**上都调用 `clock.instant().isAfter(retryAfterWaitDuration)`（又一个 `Instant`）。然后拒绝会在每次调用时用 **`String.format`** 构建 `CallNotPermittedException`（堆栈跟踪可以通过 `writableStackTraceEnabled` 禁用，但消息总会被格式化）。
- **事件：** 成功/错误事件在分配**之前**由 `eventProcessor.hasConsumers()` 守卫。但 `publishEventIfHasConsumer(new CircuitBreakerOnCallNotPermittedEvent(name))`、`...OnIgnoredErrorEvent`、`...OnResetEvent` 以及阈值事件都**先分配事件**，之后才检查。所以即使没有监听器，每个被拒绝的调用也会分配一个事件（除非逃逸分析把它消除）。
- **结果处理：** 结果非 null 时，`onResult` 调用 `handlePossibleTransition(Either.left(result))`，这会在每次成功且结果非 null 的调用中分配一个 `Either`，并调用 `transitionOnResult` 函数。
- **半开：** `AtomicInteger.getAndUpdate(current -> current == 0 ? current : --current)`，一个通过 lambda 实现的 CAS 循环（非捕获型，所以不分配）。
- **过期结果：** 结果被应用到 `stateReference.get()`，即**当前**状态。所以一个在 CLOSED 时被放行、在 HALF_OPEN 期间才完成的慢调用，会被算作一次探测结果。[SRC：`onSuccess` → `stateReference.get().onSuccess(...)`] 这算不算缺陷有争议，但它使半开状态的决策带有噪声。[INF]
- 日志：每条成功/错误路径上都有 `LOG.debug(...)`（禁用时开销低，但仍然是一次调用和一次级别检查）。[SRC]

### 2.2 窗口算法 :id=_22-window-algorithms

| 窗口 | 内存 | 更新开销 | 读取开销 | 说明 |
|---|---|---|---|---|
| 基于计数的环（最近 N 次调用） | O(N) 个槽 | O(1) | 有累计计数器时 O(1) | 精确的“最近 N 次”。排序需要一个全局序列计数器，因此有一条热缓存行。 |
| 基于时间、分桶（B 个桶 × 分辨率） | O(B) | 均摊 O(1) | 求和 O(B) | 在桶边界处是近似的。可以分段。 |
| 基于时间、指数衰减（失败率的 EWMA） | O(1) | 用 `exp` 或查找表为 O(1) | O(1) | 没有窗口边界。很难解释“minimumNumberOfCalls”。适合作为*自适应*模式的候选，而非默认。[INF] |

#### 基于计数、无锁、无内存分配 [PROP] :id=count-based-lock-free-allocation-free-prop

```java
// Illustrative only.
final class CountWindow {
  static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(int[].class);
  final int[] slots;          // outcome codes: 0 = EMPTY, 1 = OK, 2 = FAIL, 3 = SLOW_OK, 4 = SLOW_FAIL
  final int mask;             // N rounded up to a power of two; or use % N
  volatile long seq;          // padded; the only globally hot word
  // per-outcome counters, each on its own padded line (int fields + VarHandle getAndAdd)

  void record(int outcome) {
    long s = (long) SEQ.getAndAdd(this, 1L);
    int old = (int) SLOTS.getAndSet(slots, (int) (s & mask), outcome);
    if (old != outcome) { dec(old); inc(outcome); }   // the counters mirror the slot contents
  }
}
```

- 每次调用：对 `seq` 一次 `getAndAdd`，对一个槽一次 `getAndSet`，以及 0–2 次计数器 `getAndAdd`。没有锁，没有内存分配，没有重试循环，因此是无等待（wait-free）的。[PROP]
- 计数器的值最终等于槽数组的直方图。暂时地，计数器可能偏差正在进行中的 `record` 调用数，甚至在一个槽在其写入者递增之前就被覆盖时变成 −1。**读取时截断为 ≥ 0。** 用 [jcstress](https://github.com/openjdk/jcstress) 验证这些不变式。[INF]
- 把所有计数器打包进一个 `long`（对打包的有符号增量做一次 `getAndAdd`）可以节省原子操作。但一个暂时为负的字段会向相邻字段借位，可能触发虚假的 OPEN。只有在使用保护位时才这样做；先测量。[INF]
- 非 2 的幂的 N 可以使用 `Math.floorMod(s, N)`（一次除法，几 ns），或者把窗口向上取整。[INF]

#### 基于时间的分桶环 [PROP] :id=time-based-bucketed-ring-prop

- B 个分辨率可配置的桶（例如 10 × 1 s 或 20 × 500 ms）。每个桶有一个 `long epoch` 加上计数器。记录时：`tick = now / resolution`（来自缓存时钟或管道共享的 `nanoTime`）。如果 `bucket.epoch != tick`，就 CAS 纪元并重置计数器。在边界处竞争的递增可能丢失或落入被重置的桶中，误差上限约为一个桶的在途调用数。对于统计性策略来说这是可接受的。[INF]
- 没有 `Instant`，没有纪元秒：使用单调的 tick。[PROP]
- **针对极端竞争的分段：** S 个独立的子环，按线程探针选择（类似 `Striped64`），只在评估时求和。时间窗口能容忍这一点，因为顺序无关紧要。计数窗口不行（“最近 N 次”的顺序是全局的）。[INF]

#### 只在比率可能上升时评估阈值 [PROP] :id=evaluate-thresholds-only-when-a-rate-can-rise-prop

- 在 CLOSED 状态下的**快速成功**时，失败率和慢调用率都不可能上升。跳过评估，在健康的系统中这能为 >99% 的调用省去除法和比较。
- 在失败、慢速成功时，以及**窗口尚未“预热”时**（总数 < `minimumNumberOfCalls`）进行评估。否则，凑满最小调用数的那次成功（例如前 10 次调用中有 6 次失败）要等到下一次失败才会打开熔断器。

### 2.3 状态机 [PROP] :id=_23-state-machine-prop

- 一个放在 `VarHandle` 中的 `volatile int state`（或一个打包了 `state:2 | generation:30 | halfOpenPermits:16` 的 `long`），外加 `long openUntilNanos`。
- **CLOSED 快速路径：** 对 `state` 做一次 acquire/普通加载和一个分支。返回一个**原始类型的许可令牌**（`long` = 代数）而不是对象，这样生成的代码可以把它传给 `onSuccess(token, nanos)`。
- **OPEN 快速路径：** 加载 `state`，然后加载 `openUntilNanos` 并与 `now` 比较。这里使用缓存的 ticker 没有问题。过期后，第一个成功 CAS OPEN→HALF_OPEN 的线程获胜。其他线程重新读取。用一个**预分配的、无堆栈的**异常或一个结果码拒绝（§7.3）。不分配任何东西。
- **HALF_OPEN：** 许可作为一个计数器字段。`getAndAdd(-1)`，如果低于 0 就撤销。**代数标记：** 令牌代数 ≠ 当前代数的结果被丢弃（或只计入指标），这样 CLOSED 时期遗留的过期调用不会决定探测结果。可以选择把并发探测数与总探测数分开限制。
- **转换：** 对状态字做 CAS。获胜者重置窗口（罕见路径，可以分配内存或加锁）并发布事件。
- **强制状态**（DISABLED、FORCED_OPEN、METRICS_ONLY）只是更多的状态码。

### 2.4 慢调用检测 :id=_24-slow-call-detection

- 这需要开始/结束时长，因此要两次 `nanoTime` 调用（在 Linux 上总共约 50 ns [DOC，见 §1.3]）。与管道中所有其他需要计时的策略共享它们（§7.5）。[PROP]
- 如果慢调用阈值 ≥ 100 ms，1 ms 的缓存 ticker 就足够精确，并把每次读取变成一次普通加载。让这成为按策略的选择，根据阈值自动决定。[PROP]
- 当没有配置慢调用阈值且没有开启时长指标时，**根本不要对调用计时**。Resilience4j 总是计时。[SRC][PROP]

---

## 3. 限流器 :id=_3-rate-limiter

### 3.1 算法 :id=_31-algorithms

| 算法 | 状态 | 突发行为 | 能否在一个字上无锁？ | 说明 |
|---|---|---|---|---|
| 固定窗口计数器 | 计数 + 窗口 id | 跨窗口边界时最多可达**限额的 2 倍** | 能（把 id + 计数打包进一个 `long`） | 最简单；有边界突发。 |
| 滑动窗口日志 | 最近 L 个许可的时间戳 | 精确 | 不能（O(L) 内存，时间戳环） | 精确，但内存 ∝ 限额。 |
| 滑动窗口计数器 | 两个相邻窗口的计数，加权 | 近似、平滑 | 几乎可以（两个字） | Cloudflare/nginx 风格的近似。 |
| 令牌桶 | 令牌数 + 上次补充时间 | 最多到容量 | 能，通过与 GCRA 的等价性 | 经典算法。 |
| 漏桶（作为计量器） | 水位 + 上次时间 | 最多到容量 | 能，与 GCRA 相同 | 在数学上等价于 GCRA。 |
| 漏桶（作为队列） | 等待者队列 | 无（平滑输出） | 不能 | 这是调度器，不是限流器。 |
| **GCRA** | 一个 `long` TAT（理论到达时间） | 最多到配置的突发量 | **能，一次 CAS** | 最适合进程内限流器。 |

参考资料：[GCRA (Wikipedia)](https://en.wikipedia.org/wiki/Generic_cell_rate_algorithm)、[Brandur — Rate limiting, cells, and GCRA](https://brandur.org/rate-limiting)。[DOC]

### 3.2 单个字上的 GCRA [PROP] :id=_32-gcra-on-one-word-prop

```java
// Illustrative only. T = emission interval (period / limit), tau = T * burst.
long tryAcquire(int permits, long nowNanos) {
  long inc = T * permits;
  for (;;) {
    long tat = (long) TAT.getVolatile(this);
    long newTat = Math.max(tat, nowNanos) + inc;
    long wait = newTat - nowNanos - tau;          // > 0 means "not yet"
    if (wait > 0) return wait;                    // the caller rejects, or waits ≤ timeout and then calls reserve()
    if (TAT.compareAndSet(this, tat, newTat)) return 0;
    Thread.onSpinWait();
  }
}
```

- 一次 volatile 读、一次 CAS，**没有内存分配，没有后台线程**，并且对任意许可数都正确。“预留”变体（Resilience4j 的 `reservePermission`）即使在 `wait > 0` 时（以超时为上限）也会 CAS 写入 `newTat`，并返回 `wait`。[PROP]
- 不带预留的拒绝是**只读的**：没有 CAS，所以在过载期间不会让缓存行来回弹跳。这很重要，因为过载正是限流器最热的时候。[INF]
- 令牌桶配置（`capacity`、`refillRate`）映射为 `T = 1/rate`、`tau = T × (capacity − 1)`。“每周期限额”配置映射为 `T = period/limit`、`burst = limit`。舍入：以整数纳秒表示的 `period/limit` 每个许可的漂移 < 1 ns。如果需要精确，可以跟踪余数。[INF]
- **竞争：** 每个*被放行*的调用都对同一个字做 CAS。在多核、极高速率下，可以分片成 K 个速率各为 rate/K 的子限流器，按线程探针选择，并可选地允许相互借用。这是用精确性换取可扩展性，所以应设为需主动启用。[PROP]

### 3.3 Resilience4j 的做法 [SRC] :id=_33-how-resilience4j-does-it-src

[`AtomicRateLimiter`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/AtomicRateLimiter.java)（默认）：

- 一种**固定周期**算法：`currentCycle = nanos / limitRefreshPeriod`。许可在每个周期边界补充到 `limitForPeriod`（有上限），所以客户端可以在边界附近突发约 2 倍的限额。[SRC + INF]
- 状态是一个放在 `AtomicReference` 中的**不可变 `State` 对象（config、activeCycle、activePermissions、nanosToWait）**。**每次 CAS 尝试都会分配一个新的 `State`**，无论成功还是失败，被拒绝的调用也是如此。
- 时钟是 `Supplier<Long> nanoTimeSupplier`（**装箱**）。`config.getTimeoutDuration().toNanos()` 和 `getLimitRefreshPeriod().toNanos()` 在每次调用时重新计算。
- CAS 失败的退避是 `LockSupport.parkNanos(1)`。对于 Linux 上的平台线程，实际的最小休眠时间受计时器松弛（timer slack，默认约 50 µs）限制，远远超过一次重试的开销。[SRC + INF]
- `waitingThreads` 是一个共享的 `AtomicInteger`，在等待前后递增和递减。

[`SemaphoreBasedRateLimiter`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/SemaphoreBasedRateLimiter.java)：

- `new Semaphore(limit, /* fair */ true)`。公平的 AQS 信号量会让每个有竞争的获取者按 FIFO 排队，在竞争下比非公平版本慢得多。[SRC + INF]
- 一个 `scheduleAtFixedRate` 任务刷新许可，除非用户提供了调度器，否则它使用**每个限流器自己的单线程调度器**。也就是每个命名限流器一个线程。刷新操作（`limit - availablePermits()` 然后 `release`）与并发的获取不是原子的。[SRC + INF]
- 不支持 `reservePermission`。[SRC]

### 3.4 等待许可 :id=_34-waiting-for-a-permit

- **默认快速失败。** 如果配置了等待：在虚拟线程上，`LockSupport.parkNanos(wait)` 会卸载线程，开销很低。在平台线程上，它会阻塞一个操作系统线程，这是用户自己的选择。[INF]
- 使用预留（GCRA reserve）时，等待者知道自己确切的时间槽，所以不会出现唤醒时的惊群效应或再次竞争。[PROP]

---

## 4. 舱壁隔离（bulkhead） :id=_4-bulkhead

### 4.1 信号量 bulkhead :id=_41-semaphore-bulkhead

- **快速失败（maxWait = 0），常见情况** [PROP]：

  ```java
  if ((int) INFLIGHT.getAndAdd(this, 1) >= max) { INFLIGHT.getAndAdd(this, -1); return REJECTED; }
  // ... call ...
  INFLIGHT.getAndAdd(this, -1);   // + signal a waiter only if waiting is enabled and waiters > 0
  ```

  进入一次 `LOCK XADD`，退出一次，无等待。并发数永远不会超过 `max`。暂时的多计数可能在恰好到达限额时导致一次虚假拒绝，这对 bulkhead 来说是可接受的。[INF]
- **带等待（`maxWait > 0`）：** 退回到 `java.util.concurrent.Semaphore`（默认非公平；公平作为可选项），或者复用计数器加一个等待者队列。只有这条慢路径才付出 AQS 的开销。[PROP]

**Resilience4j `SemaphoreBulkhead`**（[source](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/internal/SemaphoreBulkhead.java)）[SRC]：
- `tryAcquirePermission()` 即使等待时间为 0 也调用 `semaphore.tryAcquire(maxWait.toMillis(), MILLISECONDS)`，并且每次调用都创建一个**捕获型 lambda** `() -> callPermitted ? new ...PermittedEvent : new ...RejectedEvent`（除非逃逸分析消除，否则是一次内存分配）。
- 每次 `releasePermission()`/`onComplete()` 都调用 `grantPendingPermissions()`，它在**第二个共享原子变量**上执行 `grantsInProgress.getAndIncrement()`（以及之后的 `addAndGet`），即使不存在异步等待者也是如此。所以每次调用是两到三次有竞争的原子操作，而不是两次。
- 异步路径使用一个 `ConcurrentLinkedQueue<PendingPermission>`，并为每个等待中的获取调度一个超时任务。
- 拒绝：`BulkheadFullException`，堆栈跟踪可选（可配置）。[SRC]

### 4.2 线程池 bulkhead 与虚拟线程 :id=_42-thread-pool-bulkhead-vs-virtual-threads

- Resilience4j 的 [`FixedThreadPoolBulkhead`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/internal/FixedThreadPoolBulkhead.java) 把调用隔离在一个有界的 `ThreadPoolExecutor` 中，并返回 `CompletionStage`。它的开销是每次调用一次线程切换、一个队列和若干 future 分配。[SRC + INF]
- [JEP 444](https://openjdk.org/jeps/444)（虚拟线程，在 JDK 21 中成为正式功能）：“virtual threads are not expensive so there is never a need to pool them”，以及“do not be tempted to pool virtual threads in order to limit concurrency”；而应使用“constructs specifically designed for that purpose, such as semaphores.”[DOC]
- 因此在 Loom 的世界里，信号量 bulkhead **就是**唯一的 bulkhead。线程池隔离只剩两种用途：(a) 仍在平台线程上运行阻塞代码、需要让调用线程保持空闲的应用，以及 (b) “超时即放弃”语义，即调用者必须返回而工作在别处继续。两者都很小众。以后作为一个单独的、可选的实现再提供。[INF]

### 4.3 固定（pinning） :id=_43-pinning

- JDK 21–23：在 `synchronized` 内阻塞会固定载体线程。**JDK 24+（[JEP 491](https://openjdk.org/jeps/491)）**：监视器不再导致固定，`Object.wait()` 会卸载线程。对于本地栈帧，固定依然存在：回调进 Java 并阻塞的本地代码、在类加载期间或**类初始化器**中阻塞，以及等待另一个线程的类初始化。[DOC]
- 库的规则：不在任何可能阻塞的东西外面用 `synchronized`。不在静态初始化器中阻塞（例如不要在 `<clinit>` 中启动一个 ticker 线程并等待它）。使用 `j.u.c.locks` 或无锁代码。在文档中说明用户自己的 `synchronized` 代码在 JDK 21–23 上会导致固定。[PROP]
- 避免每次调用的 `ThreadLocal` 缓存：在虚拟线程下，每次调用往往运行在一个新线程上，所以缓存就变成了内存分配。JEP 444 明确警告不要在线程局部变量中池化昂贵的资源。优先使用栈上局部变量，或用 `ScopedValue` 传播上下文（在 JDK 25 中成为正式功能，[JEP 506](https://openjdk.org/jeps/506)；在 Java 21 基线上不使用预览就无法使用）。[DOC][INF]

### 4.4 结构化并发（状态核实于 2026-10-23） :id=_44-structured-concurrency-status-checked-2026-10-23

| JDK | JEP | 状态 |
|---|---|---|
| 21 | [453](https://openjdk.org/jeps/453) | 预览 |
| 22–24 | [462](https://openjdk.org/jeps/462)、[480](https://openjdk.org/jeps/480)、[499](https://openjdk.org/jeps/499) | 第 2–4 次预览 |
| 25 | [505](https://openjdk.org/jeps/505) | 第 5 次预览：`StructuredTaskScope.open(Joiner, cfg -> cfg.withTimeout(...))` |
| 26 | [525](https://openjdk.org/jeps/525) | 第 6 次预览（Joiner 超时回调） |
| 27 | [533](https://openjdk.org/jeps/533) | 第 7 次预览（joiner 抛出 `ExecutionException`）（[InfoQ](https://www.infoq.com/news/2026/05/jep-533-jdk-27/)） |
| 28（计划于 2027 年 3 月 GA） | [543](https://openjdk.org/jeps/543) | **正式**，“without further change”；状态为 *Integrated* |

[DOC] 推论：以 Java 21 为基线的 jar **无法**使用 `StructuredTaskScope`（预览 class 文件与某个确切的 JDK 版本绑定）。现在就在普通虚拟线程上实现超时和对冲。以后再为 JDK 28+ 增加一个多版本 jar 或单独模块的适配器。[INF]

---

## 5. 重试 :id=_5-retry

### 5.1 退避策略 :id=_51-backoff-strategies

| 策略 | 公式 | 评论 |
|---|---|---|
| 固定 | `base` | 同步化的重试风暴。 |
| 指数（有上限） | `min(cap, base · m^n)` | 仍然是同步化的。 |
| 完全抖动 | `rand(0, min(cap, base · 2^n))` | 在 AWS 的模拟中总工作量最低。 |
| 等量抖动 | `t/2 + rand(0, t/2)` | 保留一个最小等待时间。 |
| **去相关抖动** | `sleep = min(cap, rand(base, prev · 3))` | 分散效果好，有状态（需要 `prev`）。 |

来源：[AWS Architecture Blog — Exponential Backoff And Jitter (Brooker)](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)。[DOC]

实现：`ThreadLocalRandom.current().nextLong(lo, hi)`（无竞争，无内存分配）。用反复相乘或带溢出饱和的移位来计算指数，不要每次尝试都调用 `Math.pow`。一切都以 `long` 纳秒表示，热路径上没有 `Duration`。[PROP]

### 5.2 Resilience4j 的做法 [SRC] :id=_52-how-resilience4j-does-it-src

[`RetryImpl`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-retry/src/main/java/io/github/resilience4j/retry/internal/RetryImpl.java)、[`Retry.decorateCheckedSupplier`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-retry/src/main/java/io/github/resilience4j/retry/Retry.java)：
- `retry.context()` → `new ContextImpl()`，带一个 `new AtomicInteger` 和两个 `new AtomicReference`：**每次调用四次内存分配，即使第一次尝试就成功**。
- `onComplete` 递增 `LongAdder`（这没问题），并在重试路径上把值包装进 `Optional`。
- `IntervalBiFunction`/`IntervalFunction` 接收装箱的 `Long`；`sleepFunction` 是 `CheckedConsumer<Long>`（`Thread::sleep`）。
- `onResult` 在每次调用时评估结果谓词（这是预期的），并且在按结果重试时 `Either.right(result)` 会分配内存。

### 5.3 提案 [PROP] :id=_53-proposal-prop

- **零失败路径只花一次谓词检查**：尝试次数、上一个异常和退避状态都放在生成方法的局部变量中（或放在一个静态辅助方法内的单个循环中）。在第一次失败之前什么都不分配。
- **休眠：** 在调用线程上使用 `Thread.sleep(Duration)` 或 `LockSupport.parkNanos`。在虚拟线程上这会卸载线程。JDK 在一个内部的 `ScheduledThreadPoolExecutor` 上调度唤醒。JDK 21 只有**一个** `UNPARKER`；JDK 25 有一个延迟任务调度器的**数组**（`jdk.virtualThreadScheduler.timerQueues`）来减少竞争。[SRC：`jdk-21+35` / `jdk-25+36` 的 `VirtualThread.java`] 对于异步 API，使用 `CompletableFuture.delayedExecutor` 或我们自己的时间轮（§6.3）。
- **重试预算**（避免重试风暴；这是一个差异点，因为 Resilience4j 没有 [INF]）：
  - Google SRE：每请求的限制（≤ 3 次尝试）加上**每客户端重试比例 < 10%**。[DOC]（[SRE book — Handling Overload](https://sre.google/sre-book/handling-overload/)）
  - gRPC 重试节流：每个目标一个令牌计数，初始为 `maxTokens`。每次失败 −1，每次成功 +`tokenRatio`。只有令牌数 > `maxTokens/2` 时才允许重试。[DOC]（[gRPC A6](https://github.com/grpc/proposal/blob/master/A6-client-retries.md)）
  - 实现：一个以定点毫令牌表示的 `long`，用 `getAndAdd` 更新（成功/失败），用一次读取做重试决策。无内存分配，每次调用一次原子操作。对针对同一依赖的所有重试策略共享这个预算。[PROP]
- 重试与熔断器和限流器之间相互作用。顺序和预算必须在管道中明确（见同系列文档 04）。[INF]

---

## 6. 超时 / 时间限制器 :id=_6-timeout--time-limiter

### 6.1 根本问题 :id=_61-the-fundamental-problem

Java 无法抢占正在运行的计算。超时只能 (a) 停止*等待*在别处运行的结果，或者 (b) *中断*正在运行它的线程，而这只有在代码到达可中断点时才有用。[INF]

### 6.2 选项 :id=_62-options

| 选项 | 线程切换 | 每次调用的开销 | 能否取消工作？ |
|---|---|---|---|
| 在执行器中运行，`future.get(timeout)`（Resilience4j 同步 `TimeLimiter`） | 是 | 任务 + future + 队列；上下文切换 | 只能通过 `cancel(true)` → 中断 |
| 在一个**新的虚拟线程**中运行，带超时 join | 是（廉价） | VT 创建 + 启动 + join（约 µs 级）[INF] | 中断 |
| `StructuredTaskScope` + `withTimeout` | 是 | scope + 子任务 | 中断（取消 scope）—— 直到 JDK 28 前都是预览 |
| `CompletableFuture.orTimeout`（异步） | 否（对已经是异步的工作而言） | `Timeout` + `Canceller` + 调度任务 | 否（以异常完成） |
| **调用线程上的看门狗** [PROP] | **否** | 一次计时器插入 + 取消 | 中断调用者 |

- **Resilience4j** [SRC]（[`TimeLimiterImpl`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-timelimiter/src/main/java/io/github/resilience4j/timelimiter/internal/TimeLimiterImpl.java)）：同步路径是 `decorateFutureSupplier(Supplier<Future>)`，所以**用户必须提供一个线程**，调用执行一次 `future.get(timeout.toMillis(), MILLISECONDS)`。异步路径每次调用使用一个 `ScheduledExecutorService` 超时以及 `whenComplete`（内存分配：调度任务、完成回调 lambda、依赖的 future）。超时时它创建一个具名的 `TimeoutException`。
- **`CompletableFuture.orTimeout`** [SRC]：JDK 21 在单个静态守护线程上调度（`CompletableFutureDelayScheduler`，一个 `ScheduledThreadPoolExecutor`）。在 JDK 25 中，它被改写为基于 `ForkJoinPool` 使用的新 `java.util.concurrent.DelayScheduler`。`ScheduledThreadPoolExecutor` 的插入/取消是在一个有锁保护的堆上的 O(log n) 操作，在高调用速率下会成为竞争点。[INF]
- **虚拟线程下的可中断性：** 在 JDK 21 中，中断一个阻塞在 `Socket` connect/read/write 上的虚拟线程“will cause it to wakeup and close the socket”，方法抛出 `SocketException`。[SRC：`java/net/Socket.java` Javadoc，`jdk-21+35`] 所以基于中断的超时对虚拟线程上的阻塞 I/O 是有效的，而最常见的超时目标（基于 socket 的 HTTP/JDBC）正好受益于此。在平台线程上，经典的 `Socket` I/O 不可中断。[INF]

### 6.3 看门狗设计 [PROP] :id=_63-watchdog-design-prop

- 调用留在调用者的线程上（没有线程切换，保留线程局部变量、MDC 和事务）。调用前，向计时器注册 `(thread, deadline)`。调用后，取消。
- **竞争协议：** 每次调用一个状态，`RUNNING → DONE`（调用者 CAS）或 `RUNNING → INTERRUPTING → INTERRUPTED`（计时器 CAS，然后 `thread.interrupt()`，然后一次 release 存储）。如果调用者输掉 CAS，它就等待（短暂自旋）直到 `INTERRUPTED`，然后在抛出 `TimeoutException` 之前用 `Thread.interrupted()` 清除中断标志。这保证不会有游离的中断泄漏到之后无关的工作中。用 jcstress 做模型检查。
- **计时器结构：** 大多数超时都会被**取消**（调用先完成），所以取消必须是 O(1) 且廉价的。
  - 哈希时间轮计时器（Netty `HashedWheelTimer`：默认 tick 100 ms，512 个桶，新增和取消的超时使用 MPSC 队列，一个工作线程）。[SRC]（[source](https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/HashedWheelTimer.java)）
  - 分层时间轮（Varghese & Lauck 1987；Kafka 和 Caffeine 的 `TimerWheel` 也在使用）用有界的桶处理大范围的超时。[DOC]（[Varghese & Lauck, *Hashed and Hierarchical Timing Wheels*](https://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf)）
  - **更便宜的“槽扫描”变体：** 每个在途的计时调用在一个预分配的数组中占用一个槽（空闲链表索引），写入 `deadline` 和 `thread`，调用结束后清除。一个看门狗线程每个 tick（例如 1 ms）扫描已占用的槽。插入和取消只是普通存储加一次空闲链表的弹出/压入；扫描是 O(在途数)。当并发有界时（而 bulkhead 通常会限制它）效果最好。与时间轮做基准测试对比。
- 看门狗线程必须是平台守护线程（它不能依赖虚拟线程调度器是否空闲），惰性启动，但**不能**在 `<clinit>` 中启动（固定问题，§4.3）。
- **异步 API：** 对于 `CompletionStage` 方法，在我们自己的时间轮上使用类似 `orTimeout` 的语义，而不是 JDK 的延迟器，以便整个库共享一个计时器。

---

## 7. 横切关注点 :id=_7-cross-cutting-concerns

### 7.1 事件与监听器 :id=_71-events-and-listeners

- **规则：** 没人监听时零内存分配、没有虚分派。一个 `static final Listener[] NONE` 哨兵，加上 `if (listeners != NONE)`，其中 `listeners` 是在构建时设置的 `final` 字段。如果在构建时就知道监听器，生成的代码可以完全省略这个调用。[PROP]
- **用原始类型回调代替事件对象：** `interface CircuitBreakerListener { default void onSuccess(long nanos) {} default void onRejected() {} default void onStateChange(int from, int to) {} }`。即使*有*监听器也不分配内存。事件对象适配器（用于日志或 Micrometer）只在适配器内部分配。[PROP]
- **事件顺序与异常：** 监听器抛出异常不能破坏调用。捕获它，并计入一个“监听器失败”`LongAdder`。[PROP]
- **Resilience4j** [SRC]：`EventProcessor` 对每个事件类名使用一个 `CopyOnWriteArraySet`，通过 `event.getClass().getName()` 查找（每个发布的事件一次 `ConcurrentHashMap` 查找）。守卫是存在的，但有几个调用点在检查之前就分配了（§2.1）。Bulkhead/retry 传递的是捕获型 `Supplier` lambda。

### 7.2 指标 :id=_72-metrics

- 计数器：在热路径上递增 `LongAdder`（或分段的、填充过的 long）；**基于拉取**的暴露方式（一个在抓取时读取 `sum()` 的 gauge）。热路径上不调用 Micrometer 或其注册表。[PROP]
- 延迟直方图（仅在启用时）：固定的对数线性桶（HdrHistogram 风格），放在一个 `AtomicLongArray` 或分段数组中。记录 = `numberOfLeadingZeros` + 数组索引 + `getAndAdd`。对冲延迟（p95）和自适应限流需要它。[PROP]（[HdrHistogram](https://github.com/HdrHistogram/HdrHistogram)）

### 7.3 异常 :id=_73-exceptions

- `Throwable` 构造的开销主要来自 `fillInStackTrace`，它与栈深度成正比，通常是微秒级。`(message, cause, enableSuppression=false, writableStackTrace=false)` 构造函数可以避免它。[DOC][INF]（[Throwable 构造函数](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html#%3Cinit%3E(java.lang.String,java.lang.Throwable,boolean,boolean))）
- Resilience4j 确实为 `CallNotPermittedException`、`BulkheadFullException` 和 `RequestNotPermitted` 使用了这个构造函数（堆栈跟踪可配置，默认可写）。但它在**每次拒绝时**都用 `String.format`（熔断器）或字符串拼接来构建消息。[SRC]
- **提案：** 拒绝异常**按策略实例预分配**，无堆栈，禁用抑制。消息只计算一次（“CircuitBreaker 'x' is OPEN”）。如有需要，状态在 `getMessage()` 时从字段读取。调试模式（配置开关）会创建带堆栈跟踪的新异常。此外，为把拒绝当作正常流程的调用者提供一个不抛异常的 API（`Result`/sealed 结果类型，或返回状态的 `tryCall`）。[PROP]
- 在热路径上**绝不**包装用户异常（同步路径中不用 `CompletionException`/`ExecutionException` 包装）。在生成的代码中用“sneaky throw”重新抛出受检异常，保留声明的签名。[PROP]

### 7.4 不使用嵌套 lambda 或超多态调用点的组合 :id=_74-composition-without-nested-lambdas-or-megamorphic-sites

**问题。** `Decorators.ofSupplier(s).withRetry(r).withCircuitBreaker(cb).withBulkhead(b).decorate()` 构建了一条 lambda 链。每个 `CircuitBreaker.decorateCheckedSupplier` lambda 的方法体是**整个应用中的同一个字节码位置**[SRC：`CircuitBreaker.java`]。它的 `supplier.get()` 调用点会看到应用中每一个被装饰的 supplier 类，因此在出现第三种不同类型后就变成超多态的。超多态调用点被编译为 itable/vtable 调用，阻止了内联，所以逃逸分析无法消除 `Long`、`Either`、`Snapshot` 和事件的分配。真实应用有许多被装饰的方法，所以只有一个被装饰 supplier 的微基准测试对 Resilience4j 来说快得*不切实际*。[INF；用 `-XX:+PrintInlining` / JITWatch 验证]

**提案** [PROP]：
1. **acquire/release 协议，而不是环绕包装。** 每个策略都暴露原始类型的、非 lambda 的操作：`long cb.acquire(now)`、`cb.onSuccess(token, nanos)`、`cb.onFailure(token, nanos, Throwable)`、`boolean bh.tryEnter()` 和 `bh.exit()`、`long rl.tryAcquire(now)`，等等。
2. **按带注解方法生成的直线式代码**（无论同系列研究选择哪种机制：生成子类或包装器的注解处理器）。每个方法有自己的字节码，所以每个调用点都是**单态**的（`final` 策略类，通过常量 `static final` 字段或 final 实例字段调用）。形态：

   ```java
   // Generated, illustrative.
   long t0 = TICKER.read();
   if (!BH.tryEnter()) throw BH.rejected();
   try {
     long tok = CB.acquire(t0);          // throws a preallocated rejection if OPEN
     try {
       R r = target.call(args);          // direct invokevirtual on the real class
       long t1 = TICKER.read();
       CB.onSuccess(tok, t1 - t0);
       return r;
     } catch (Throwable e) { CB.onFailure(tok, TICKER.read() - t0, e); throw e; }
   } finally { BH.exit(); }
   ```

3. **重试作为一个循环**，在同一个生成方法中包住内部代码块。没有 lambda，没有上下文对象。
4. **编程式（非注解）使用的后备方案：** 一个单独的 `Pipeline` 类，顺序固定，每种策略一个 `final` 字段（null = 不存在），通过 `if (cb != null)` 分支执行同样的 acquire/release 序列。这是一个类，其中每个调用点只看到一个具体的策略类（策略是 `final` 类），所以保持单态。用户的 `Callable.call()` 仍是一个超多态调用点，不使用代码生成就无法避免。[INF]

### 7.5 共享时钟读取 :id=_75-shared-clock-reads

在一个包含 N 个计时策略的组合中，Resilience4j 每个策略都读取一次时钟（熔断器读两次，再加上限流器、时间限制器等等）。生成的管道在调用前读一次 `t0`，调用后读一次 `t1`，并把时长传给所有策略。[PROP]

---

## 8. 差异化功能 :id=_8-differentiating-features

### 8.1 对冲请求（hedging） :id=_81-hedging

- 如果第一个请求在一段延迟（通常约为 p95 延迟）后仍未完成，就发送第二个请求，取第一个成功的结果，并取消其余请求。在 *The Tail at Scale* 中，把对冲推迟到 95 百分位的预期延迟，可以把额外负载限制在约 5%，同时大幅削减尾延迟。[DOC]（[Dean & Barroso, CACM 2013](https://research.google/pubs/the-tail-at-scale/)）
- gRPC 对冲：`maxAttempts`（≤ 5）、`hedgingDelay`、`nonFatalStatusCodes`；对冲请求遵守重试节流。[DOC]（[gRPC A6](https://github.com/grpc/proposal/blob/master/A6-client-retries.md)）
- 基于 Loom 的实现：第一次尝试在调用者线程上运行；计时器（§6.3）触发时，对冲请求启动一个虚拟线程；第一个完成者通过对结果槽的 CAS 获胜；失败者被中断。对冲要求幂等性（一个显式的注解属性），并且必须消耗重试预算。延迟来自实时的延迟直方图（§7.2），或者是固定的。[PROP]
- Resilience4j 有一个较新的 `hedge` 模块（见文档 01）。在那里比较语义。[INF]

### 8.2 自适应并发限制 :id=_82-adaptive-concurrency-limits

- [Netflix concurrency-limits](https://github.com/Netflix/concurrency-limits)：限额 ≈ RPS × 延迟（Little 定律）。算法：**Vegas**（队列估计 `L·(1 − minRTT/sampleRTT)`，对照 α/β 阈值 ±1）、**Gradient2**（长期与短期延迟 EWMA 的偏离）、**AIMD**（基于丢失）。限流器：`SimpleLimiter`、分区式（按优先级分配份额）、阻塞式。[DOC]
- Envoy 的自适应并发过滤器使用梯度控制器，并定期重新测量 minRTT。[DOC]（[Envoy 文档](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/adaptive_concurrency_filter)）
- **热路径设计** [PROP]：在途计数器（与 bulkhead 相同，§4.1）对照一个 `volatile int limit`；完成时，把 RTT 加到按窗口的累加器上（`LongAdder` 求和 + 计数；通过对一个 `long` 做 CAS-min 得到最小 RTT）。**越过窗口边界的那个线程**（对窗口纪元做 CAS）重新计算 `limit`。其他所有线程只付出 2 次 `getAndAdd` 加一次 `LongAdder.add`。没有后台线程。
- 这是一个明显的差异点：一个无需调优的“自适应 bulkhead”。[INF]

### 8.3 负载削减 :id=_83-load-shedding

- 客户端自适应节流（Google SRE）：以概率 `max(0, (requests − K·accepts) / (requests + 1))` 在本地拒绝，K ≈ 2。[DOC（K 和各定义在页面上有引用；公式本身在 [SRE book 页面](https://sre.google/sre-book/handling-overload/)上以图片形式呈现）]。实现：两个滑动窗口计数器（§2.2 时间环）加 `ThreadLocalRandom`。[PROP]
- 针对等待中的调用者的服务端队列规则：CoDel 风格（“如果队列在 X ms 内一直不为空，就削减”），以及 Facebook 描述的过载下的**自适应 LIFO**。[DOC]（[Maurer, *Fail at Scale*, ACM Queue 2015](https://doi.org/10.1145/2814330)；Java 中的先例是 HBase 的 [`AdaptiveLifoCoDelCallQueue`](https://hbase.apache.org/2.5/devapidocs/src-html/org/apache/hadoop/hbase/ipc/AdaptiveLifoCoDelCallQueue.html)）
- 优先级分区（关键请求与批处理请求按份额分配限额），就像 Netflix 的分区限流器那样。[DOC]

---

## 9. 基准测试方法（JMH 对比 Resilience4j） :id=_9-benchmark-methodology-jmh-vs-resilience4j

### 9.1 基本规则 :id=_91-ground-rules

- 只用 [JMH](https://github.com/openjdk/jmh)。fork ≥ 3，预热 ≥ 5×1 s，测量 ≥ 5×1 s。固定 `-Xms=-Xmx`，加上 `-XX:+AlwaysPreTouch`。在 **JDK 21 和 JDK 25** 上运行（虚拟线程计时器和 `CompletableFuture` 延迟器都有变化，§5.3/§6.2），在 **x86-64 和 AArch64** 上运行（原子操作的行为不同）。尽可能固定 CPU 频率并禁用睿频。发布 JMH JSON 和确切的硬件信息。[PROP]
- 与**最新的 Resilience4j 版本（2.4.0）**在*等价*配置下比较：相同的窗口类型和大小，`SYNCHRONIZED` 和 `LOCK_FREE` 两种策略都测，拒绝相关的基准测试使用 `writableStackTraceEnabled=false`（对他们公平），事件分别测有消费者和无消费者的情况。复用他们自己的 JMH 场景（例如 [`CircuitBreakerBenchmark`](https://github.com/resilience4j/resilience4j/tree/master/resilience4j-circuitbreaker/src/jmh/java/io/github/resilience4j/circuitbreaker)），让人无法指责我们挑选数据。[PROP]
- **基线**：未经装饰的调用，这样我们可以报告*开销* = 策略 − 基线。[PROP]
- **profile 污染（对两个库都公平）：** 在测量之前，在同一个 JVM 中装饰并运行 ≥ 3–4 个不同的 supplier/目标类，使共享调用点像在真实应用中一样变成超多态的。同时报告“干净”和“被污染”的数字。[PROP]
- 分析器：`-prof gc`（`gc.alloc.rate.norm` B/op）、`-prof perfnorm`（每次操作的指令数和缓存未命中数）、`-prof perfasm` 或 async-profiler 查找热点，虚拟线程运行时使用 JFR `jdk.VirtualThreadPinned`。用 `Blackhole.consumeCPU(n)` 模拟工作负载。[DOC][PROP]
- 正确性：对每个无锁结构（窗口计数器、状态 CAS、GCRA、看门狗协议）使用 [jcstress](https://github.com/openjdk/jcstress)。一个快但错误的熔断器就是一个缺陷。[PROP]

### 9.2 场景 :id=_92-scenarios

| # | 场景 | 模式 | 线程 | 展示什么 |
|---|---|---|---|---|
| 1 | 每种策略的正常路径（CB 闭合、RL 未达限额、BH 未达限额、重试第一次就成功、TL 很快） | `AverageTime`，ns/op | 1 | 纯开销，B/op |
| 2 | 同上，共享实例 | `Throughput` | 1、2、4、…、2× 核数 | 竞争下的扩展性（伪共享、CAS 风暴、锁护航） |
| 3 | 拒绝路径（CB 打开、RL 耗尽、BH 已满） | `Throughput` + `-prof gc` | 1 和最大值 | 过载下（最关键的时候）异常和事件的开销 |
| 4 | 混合结果（1%、10%、50% 失败，慢调用） | `Throughput` | 最大值 | 阈值评估和窗口更新 |
| 5 | CB 抖动（频繁打开/半开/闭合） | `Throughput` | 最大值 | 转换和探测的正确性/开销 |
| 6 | 有失败的重试，零退避 | `AverageTime` | 1 | 每次尝试的重试机制开销 |
| 7 | 完整组合 Retry→CB→RL→BH→TL 对比 `Decorators` | `AverageTime`、`Throughput` | 1、最大值 | 组合开销（§7.4） |
| 8 | 超时：每次调用调度+取消 对比 `orTimeout` 对比 R4j `TimeLimiter` | `Throughput` | 1、最大值 | 计时器数据结构的开销 |
| 9 | 1 万个虚拟线程经过带等待的 BH/RL | `SampleTime`（p50/p99/p999） | VT | 公平性、尾延迟、固定 |
| 10 | 内存占用：1 万个命名实例 | JOL / 堆直方图 | — | 填充和状态的内存开销 |
| 11 | 被污染与干净的 JIT profile（§9.1） | 同 #1/#7 | 1 | 真实情况与最佳情况的数字 |

### 9.3 预期结果（待确认的假设） :id=_93-expected-outcome-hypotheses-to-confirm

- 单线程正常路径：优势来自消除内存分配和更少的时钟读取。预计在“干净”profile 下有适度的收益（每次操作几十 ns），在“被污染”profile 下收益更大。[INF]
- 竞争下的吞吐量：预计最大的收益出现在熔断器（锁对比无等待）和限流器（每次 CAS 尝试的内存分配和 `parkNanos` 退避对比普通 CAS）。[INF]
- 拒绝路径：预分配的无堆栈异常加上不急切创建事件，应当比每次拒绝都调用 `String.format` 便宜几个数量级。[INF]
- 在测量之前，我们不得宣称任何数字。以上所有内容都有待第 2 阶段确认。[PROP]

---

## 10. 各策略的建议（汇总） :id=_10-recommendations-by-policy-summary

1. **熔断器：** 一个 `VarHandle` 状态字加代数令牌。无等待的计数窗口（槽 `getAndSet` + 计数器）和带纪元标记的时间桶。预热后在快速成功时跳过评估。除非需要，否则不计时。预分配的拒绝。
2. **限流器：** GCRA（一个 `long`，CAS）。只读的拒绝路径。令牌桶和按周期的配置都映射到它上面。可选分片。
3. **舱壁隔离：** 一个快速失败的 `getAndAdd` 计数器。只有等待时才用信号量。线程池隔离以后再做，且是可选的。自适应限流作为一个并列的策略。
4. **重试：** 只用局部变量，失败时才分配。使用 `ThreadLocalRandom` 的去相关抖动或完全抖动。按依赖共享的、基于令牌的重试预算。
5. **超时：** 一个调用者线程上的看门狗，带严格的中断协议。一个哈希时间轮或槽扫描计时器，配一个平台守护线程。异步使用同一个计时器。
6. **差异化功能：** 在虚拟线程上、由直方图驱动延迟的对冲请求；Vegas/Gradient 自适应限流；SRE 自适应节流；重试预算。
7. **横切关注点：** 原始类型的监听器回调；拉取式指标；**生成的单态 acquire/release 代码**；每个管道一对时钟读取；对写入频繁的字做填充；把 `0 B/op` 作为 CI 门禁。

---

## 来源 :id=sources

- Resilience4j 源码，`master` @ `b7c0069`：<https://github.com/resilience4j/resilience4j>（文件链接见正文）。无锁窗口 PR：<https://github.com/resilience4j/resilience4j/pull/2241>。Releases：<https://github.com/resilience4j/resilience4j/releases>
- OpenJDK `VirtualThread.java`：<https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/VirtualThread.java>、<https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/lang/VirtualThread.java>
- OpenJDK `CompletableFuture.java`（21 对比 25）：<https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java>、<https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java>
- OpenJDK `Socket.java`（虚拟线程的中断语义）：<https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/net/Socket.java>
- JEP：[444](https://openjdk.org/jeps/444)、[491](https://openjdk.org/jeps/491)、[505](https://openjdk.org/jeps/505)、[506](https://openjdk.org/jeps/506)、[525](https://openjdk.org/jeps/525)、[533](https://openjdk.org/jeps/533)、[543](https://openjdk.org/jeps/543)、[142](https://openjdk.org/jeps/142)；[JDK-8234863](https://bugs.openjdk.org/browse/JDK-8234863)
- InfoQ 关于 JEP 533（JDK 27）：<https://www.infoq.com/news/2026/05/jep-533-jdk-27/>；关于 JEP 543（JDK 28）：<https://www.infoq.com/news/2026/10/jep-543-jdk-28/>
- A. Shipilëv，*Nanotrusting the Nanotime*：<https://shipilev.net/blog/2014/nanotrusting-nanotime/>
- M. Brooker，*Exponential Backoff And Jitter*：<https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/>
- Google SRE book，*Handling Overload*：<https://sre.google/sre-book/handling-overload/>
- gRPC A6 客户端重试与对冲：<https://github.com/grpc/proposal/blob/master/A6-client-retries.md>
- Dean & Barroso，*The Tail at Scale*：<https://research.google/pubs/the-tail-at-scale/>
- Netflix concurrency-limits：<https://github.com/Netflix/concurrency-limits>
- Envoy 自适应并发：<https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/adaptive_concurrency_filter>
- B. Maurer，*Fail at Scale*，ACM Queue（2015）：<https://doi.org/10.1145/2814330>；HBase `AdaptiveLifoCoDelCallQueue`：<https://hbase.apache.org/2.5/devapidocs/src-html/org/apache/hadoop/hbase/ipc/AdaptiveLifoCoDelCallQueue.html>
- GCRA：<https://en.wikipedia.org/wiki/Generic_cell_rate_algorithm>、<https://brandur.org/rate-limiting>
- Varghese & Lauck，时间轮：<https://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf>
- Netty `HashedWheelTimer`：<https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/HashedWheelTimer.java>
- JMH：<https://github.com/openjdk/jmh>；jcstress：<https://github.com/openjdk/jcstress>；HdrHistogram：<https://github.com/HdrHistogram/HdrHistogram>
