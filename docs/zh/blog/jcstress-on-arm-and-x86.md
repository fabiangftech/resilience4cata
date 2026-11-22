# 在 ARM 和 x86 上测试无锁代码

resilience4cata 的并发测试现在在每次合并后都会在 ARM 和 x86 上运行：全部 65 个测试在两种架构上都通过，而它们反复发现的那个竞态，结果在 x86 上和在 ARM 上一样常见。

*2026 年 11 月 10 日 · 作者 [fabiangftech](https://github.com/fabiangftech)，resilience4cata 的作者*

## 为什么仅靠 x86 不够 :id=why-x86-alone-was-not-enough

各个策略把状态保存在无锁结构中：熔断器用 compare-and-set 切换它的阶段，舱壁隔离用一个原子计数器统计槽位，重试预算用分条的、有意允许丢失的计数器统计调用。它们每一个都使用仍然正确的最弱内存顺序，因为在 ARM 上，一次完整的内存屏障比一次 acquire 或普通访问要贵两到三倍（[基准测试](../benchmarks.md)）。

我们用 [jcstress](https://github.com/openjdk/jcstress) 检查这些内存顺序是否正确：每个测试在多个线程上把几个动作运行数百万次，覆盖多种线程布局，只要出现任何被标记为禁止的结果就失败。库中有 65 个这样的测试。

在此之前，CI 只在 x86 上运行它们。x86 是 TSO（全存储排序）：它让几乎每一对内存访问都保持程序顺序，唯一允许的重排序是较晚的读越过较早的写。所以如果有人误把一次 volatile 读削弱为普通读，x86 仍然表现得像是有序的，测试就不可能失败。ARM 的重排序要自由得多，而它也正是这个库调优时所用的架构。到目前为止唯一一次 ARM 运行是手动的，在 Graviton3 上，为候选版本而做。

## CI 现在运行什么 :id=what-ci-runs-now

| 工作流 | 何时 | 模式 | 机器 |
|---|---|---|---|
| `stress` | 每次推送到 `main`，以及每晚 | quick | x86 和 ARM 并行 |
| `stress-arm` | 每周，以及手动触发 | default（更多 fork，运行更久） | ARM |

两者都运行在 GitHub 托管的 runner 上，这对公开仓库是免费的。每周运行有自己的工作流，这样推送到 `main`（会重启 `stress`）就不会取消一次长达数小时的运行。

## 结果 :id=the-results

自这次改动以来 `stress` 运行了三次：pull request、合并，以及第二天晚上。两种机器上的每次运行：

| | x86 (`ubuntu-24.04`) | ARM (`ubuntu-24.04-arm`) |
|---|---|---|
| jcstress 看到的 CPU 拓扑 | 2 核 × 2 硬件线程 | 4 核 × 1 线程 |
| 每次运行的配置数 | 2322 | 1050 |
| 失败、软错误、硬错误 | 0, 0, 0 | 0, 0, 0 |
| 作业耗时 | 59–66 min | 22–24 min |
| "interesting" 测试的执行次数 | 每次运行约 2.06 亿 | 每次运行约 7300 万 |

配置数不同，是因为 jcstress 会在 CPU 拓扑上以不同布局尝试各个 actor，而每核两个硬件线程时可尝试的布局更多（这是根据日志中的拓扑行推断的，没有在 jcstress 的代码中核实）。这也解释了大部分的耗时差异：x86 运行的配置数是 ARM 的两倍多。

## 唯一的 interesting 结果 :id=the-one-interesting-result

有一个测试在每次运行、两种机器上都被标记为 "interesting"：`RetryBudgetStress.CallsAreNeverOvercounted`。这是有意为之的。[重试预算](../policies/retry.md#retry-budget)统计每次调用，以便按流量比例允许重试，它在一个分条计数器上用一次普通读和写而不是原子加法来计数：在 Graviton3 上每次调用 3.5 ns，而 compare-and-set 为 9.7 ns（[重试预算，0.3.0](../benchmarks.md#retry-budget-030-2026-10-25-aws-graviton3)）。代价是两个线程在同一个分条上竞争时可能丢失一次调用。丢失一次调用会让预算略微更严格，而绝不会更宽松。

该测试让两个线程使用同一个分条，各自统计两次调用。四次调用是正常结果；两次或三次是丢失了调用，这是允许的，并报告为 interesting；任何超过四次的结果，或者统计到一次重试，都是被禁止的，会让构建失败。从未见到过这种情况。

| 运行 | x86：丢失调用的执行占比 | ARM：丢失调用的执行占比 |
|---|---:|---:|
| Pull request | 6.7 % | 5.5 % |
| 合并 | 6.3 % | 7.6 % |
| 第二天晚上 | 18.6 % | 8.6 % |

这纠正了我们之前写过的一点。在 Graviton3 那次运行之后（10.9 % 的执行丢失了调用），[1.0 计划](../plan-1.0.md)中说这种丢失在 ARM 上比在 x86 上常见得多。这几次运行并没有显示这一点：两者的比例相互重叠，最高的一次出现在 x86 上。只要看看这个竞态需要什么，就能理解这一点。丢失一次调用根本不需要重排序，只需要两个线程在任何一方写回之前都读取了计数器，而 TSO 并不阻止这种情况。比例取决于两个线程重叠的频率，这会随机器、布局以及共享 runner 的噪声而变化。

## 这还不能说明什么 :id=what-this-does-not-show-yet

- **ARM 作业能否发现错误的内存顺序。** 一次绿色的运行并不是证明。库中的每个竞态测试都经过了变异检查（削弱保护条件，确认测试失败），但那些变异体是在 x86 和 Apple M1 上运行的，而不是在这些 runner 上。在 ARM 上再运行它们是下一步，它仍列在[计划](../plan-1.0.md#after-10)中待办。*更新，2026 年 11 月 12 日：已完成。ARM 没有发现任何 x86 漏掉的问题：在一种机器上被发现的变异体在两种机器上都被发现，而存活下来的在两种机器上都存活（详见[计划](../plan-1.0.md#after-10)）。*
- **每周的 default 模式需要多久。** 根据 quick 运行推测，在四个 ARM 核心上大概需要一到两个小时；上限是将近六个小时。第一次计划运行会给出答案。
- **任何关于速度的结论。** 这些是正确性测试。性能数据仍然只来自 ARM（Graviton3 和 Apple M1）；x86 [还没有测量过](../performance.md)。

## 自己运行 :id=run-them-yourself

```bash
./gradlew :stress:jcstress -Pjcstress="-m quick"                     # what CI runs on every push
./gradlew :stress:jcstress                                           # default mode, as the weekly ARM run
./gradlew :stress:jcstress -Pjcstress="-m quick -t RetryBudget"      # one group
```

报告位于 `stress/build/jcstress/results/index.html`。在 ARM 机器上（Apple Silicon、Graviton、Ampere），同样的命令会测试 x86 所隐藏的内存顺序。
