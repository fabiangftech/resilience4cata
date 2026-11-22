# 1.9 ns 的熔断器：在编译期生成的弹性代码

大多数 Java 弹性库在运行时用装饰器包装每次调用，而 resilience4cata 在编译期写出包装代码，于是一个健康的熔断器每次调用的开销大约只有两纳秒。

*2026 年 10 月 27 日 · 作者 [fabiangftech](https://github.com/fabiangftech)，resilience4cata 的作者*

## 装饰器的问题 :id=the-problem-with-decorators

重试、熔断器、超时和舱壁隔离通常以运行时装饰器的形式位于调用路径上。你为每个策略用一个函数包装 `Supplier` 或 `Callable`，或者让框架在你的 bean 前面放一个代理替你完成。这种方式可行、灵活，也正是许多 Java 团队使用的 Resilience4j 的构建方式。

但它在每次调用时都有开销，包括成功的调用，而成功的调用几乎就是全部。每一层都是又一次通过函数式接口的调用，而它背后的策略状态由所有线程共享。在只有一个被装饰方法的基准测试中，JIT 在每个调用点只看到一种类型，可以内联其中大部分，这是最好的情况。真实的服务会装饰几十个方法，这些共享的调用点就不再是单态的了。

我想知道，如果代码是一次只为一个方法写的，就像你有足够耐心时手写的那样，同样的保证可以便宜到什么程度。

## 让编译器来写 :id=let-the-compiler-write-it

resilience4cata 是一组注解加一个注解处理器。你给接口加上注解：

<!-- compare:start -->
<!-- snippet: how-it-works-interface -->
```java
public interface StockClient {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    int stock(String sku, int warehouse);
}
```
<!-- not-compiled: 生成的代码，有删节（包名已缩短） -->
```java
@Override
public int stock(String p0, int p1) {
    final RetryPolicy r4c$retry = this.retry0;
    final long r4c$start = r4c$retry.start();
    int r4c$attempts = 0;
    while (true) {
        try {
            return this.r4c$circuitBreaker0(p0, p1);
        } catch (Throwable r4c$e) {
            final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);
            if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {
                throw r4c$e;
            }
        }
    }
}

private int r4c$circuitBreaker0(String p0, int p1) {
    final CircuitBreakerPolicy r4c$cb = this.circuitBreaker0;
    final long r4c$token = r4c$cb.acquire();
    if (r4c$token < 0) {
        throw r4c$cb.rejection();
    }
    final long r4c$started = r4c$cb.startTimer();
    try {
        final int r4c$result = this.delegate.stock(p0, p1);
        r4c$cb.onSuccess(r4c$token, r4c$started);
        return r4c$result;
    } catch (Throwable r4c$e) {
        r4c$cb.onFailure(r4c$token, r4c$started, r4c$e);
        throw r4c$e;
    }
}
```
<!-- compare:end -->

在编译期，处理器写出 `StockClient$Resilient`，一个实现该接口的普通类。每个受保护的方法都变成直线式代码：重试是一个循环，熔断器是一个 `try` 块，每个策略一个 `final` 字段。调用路径上没有 lambda、没有代理、也没有反射，而且每个调用点都是单态的，因为每个方法都有自己的调用点。在运行时，你只需包装一次你的实现：

<!-- not-compiled: 一行用法示例；快速入门中有完整且经过测试的程序 -->
```java
StockClient client = Resilient.of(StockClient.class, new HttpStockClient(), registry);
```

这些字段背后的策略遵循一个小协议（`acquire`、`onSuccess`、`onFailure`），其设计目标是让成功路径做尽可能少的事：

- **没有内存分配。** 拒绝时抛出每个策略一个的预分配、无栈异常，事件只在有人监听时才创建。
- **除非功能需要，否则不读时钟。** 熔断器只在你配置了慢调用阈值时才对调用计时。
- **没有变化就没有共享写入。** 当熔断器处于关闭状态且只见过快速成功时，一次调用只读一个字段，不写任何东西。

最后这条规则作用最大。在争用下，写入其他核心正在读取的缓存行，其代价远超任何无锁技巧所能节省的。在 Apple M1 上用四个线程测试，熔断器第一个版本每次成功调用耗时 244 ns；当窗口中只有快速成功时跳过写入，降到 10 ns；健康时只读单个字段，降到约 3 ns（[成功路径是如何做到的](../benchmarks.md#circuit-breaker-1-and-4-threads-2026-10-23)）。

## 数据 :id=the-numbers

每项对比在两个库中都使用等效配置，为公平所做的每项调整都写在基准测试的 Javadoc 中。

> [!MEASURED]
> AWS `c7g.xlarge`（Graviton3），JDK 21，resilience4cata 0.1.0 对比 Resilience4j 2.4.0，每次调用的时间和分配字节数。来自 [MVP 完成（2026-10-24，AWS Graviton3）](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)。

| 场景 | 线程数 | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| 重试，第一次尝试成功 | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| 重试，失败一次后成功 | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| 熔断器，每次调用都成功 | 1 | **1.9 ns**, 0 B | 89.2 ns, 0 B |
| 熔断器，每次调用都成功 | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| 舱壁隔离，已满（每次调用都被拒绝） | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| 舱壁隔离，有空闲槽位 | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |
| 重试 + 熔断器 | 1 | **2.3 ns**, 0 B | 175 ns, 288 B |

这张表也展示了这种方法帮不上忙的地方：

- **有些场景是持平的。** 有空闲槽位的舱壁隔离在两个库中都用一次原子操作获取并归还许可：20.5 ns 对 20.4 ns。
- **争用仍然要花几百纳秒。** 四个线程、十次调用中有一次失败时，熔断器耗时 274 ns（Resilience4j：910 ns），因为每次调用都会推进一个共享的窗口游标。共享计数器以同样的方式拖累舱壁隔离和限流器。
- **异步调用会分配内存。** 在两个库中，每个异步层都会返回一个新的 future；只有同步路径是零分配的。
- **有些行并非同类对比。** Resilience4j 的重试在两次尝试之间调用 `Thread.sleep(0)`，所以它那一行使用了去掉该调用的变体（它的最好情况）。它的 `TimeLimiter` 需要一个 `Future`，因此同步超时意味着另一个线程；那项对比在[性能页面](../performance.md#headline)上，附有这条说明。

对于一次走网络的调用，一纳秒重要吗？单独来看很少重要。当弹性策略包装的调用通常很便宜（缓存、本地服务、内存队列），当同样的代码每秒运行数百万次，或者当你不想再去考虑某个策略是否贵到不值得添加时，它就重要了。

## 你要付出的代价 :id=what-it-costs-you

编译期生成有其取舍，而且是有意为之的：

- **注解处理器是必需的。** 没有反射兜底：如果生成的类缺失，`Resilient.of` 会立即失败并指出需要添加的依赖。
- **错误就是编译错误。** 无效的时长、未知的属性或签名错误的降级方法都会让构建失败，并给出诸如 `R4C-ATR-001` 这样的错误码，记录在[错误参考](../errors.md)中。
- **配置只解析一次。** 值可以来自注解、属性文件、环境变量，或者 Spring、Micronaut 和 Quarkus 的配置，但它们在注册表构建时就固定下来：没有热重载，这是[设计决定](../design.md)。
- **它还很年轻。** 当前版本是 0.8；在 1.0 之前 API 仍可能变化。

## 试一试 :id=try-it

在 Gradle 中添加两行：

<!-- not-compiled: 构建配置 -->
```groovy
implementation 'dev.resilience4cata:resilience4cata-all:0.8.0'
annotationProcessor 'dev.resilience4cata:resilience4cata-processor:0.8.0'
```

然后，[快速入门](../start/quickstart.md)大约五分钟就能保护一个不稳定的调用。还有 [Spring Boot](../guides/spring.md)、[Micronaut](../guides/micronaut.md) 和 [Quarkus](../guides/quarkus.md) 的集成、带手动时钟的 [testkit](../guides/testing.md)，以及一份[从 Resilience4j 迁移的指南](../migrate/from-resilience4j.md)。代码以 Apache-2.0 许可证发布在 [GitHub](https://github.com/fabiangftech/resilience4cata) 上，欢迎提交 issue 和参与讨论。
