# 弹性注解在 Spring、Micronaut 和 Quarkus 中的开销

在这三个框架中，受保护的 bean 的开销就是其策略的开销，只有几纳秒；而通过各框架惯用拦截器实现的同样注解，每次调用的开销从几十纳秒到几微秒不等。

*2026 年 11 月 1 日 · 作者 [fabiangftech](https://github.com/fabiangftech)，resilience4cata 的作者*

## 问题 :id=the-question

[第一篇文章](compile-time-resilience.md)对比的是直接调用的策略：生成的类对比 Resilience4j 的装饰器。很少有应用会直接调用策略。它们在 bean 方法上加一个注解，剩下的交给框架：Spring 通过切面，Micronaut 通过拦截器，Quarkus 通过 CDI 拦截器，例如 SmallRye Fault Tolerance 的拦截器。这套机制在每次调用时都会运行，包括成功的调用，所以我想测一测应用实际得到的是什么。

## 测量方式 :id=how-it-was-measured

基准测试启动一个真实的应用上下文，并从一个线程和四个线程调用框架注入的 bean：

- **Spring Boot 4.1.2**：resilience4j-spring-boot4 2.4.0 的切面，对比 resilience4cata 生成的子类，后者由 Spring 集成注册为 bean。
- **Micronaut 5.2.2**，运行在 JDK 25 上：resilience4j-micronaut 2.4.0 的拦截器以及 Micronaut 自带的 `@Retryable` 和 `@CircuitBreaker`，对比生成的子类。
- **Quarkus 3.40.1**：SmallRye Fault Tolerance 的注解，以及在一个 bean 中构建一次、手动调用的 Resilience4j 装饰器，完全没有拦截器，这是 Resilience4j 在那里的最好情况。

设置与核心基准测试相同：一个三次尝试、无延迟的重试，一个窗口为 100 次调用的熔断器，一个容量为 1000 次调用、永远不会满的舱壁隔离，以及一个永远不会耗尽的限流器。一个很快的基准测试也可能意味着拦截器根本没运行，所以每个设置都会先检查每个 Resilience4j 实例都参与了它的调用：熔断器统计了这次调用，满的舱壁隔离会拒绝它，耗尽的限流器会拒绝它，打开的熔断器会拒绝它。

在 resilience4cata 这边，没有任何框架特定的东西。你加注解的类在编译时旁边会生成一个子类，集成把这个子类设为 bean：

<!-- not-compiled: 简短示例；Spring、Micronaut 和 Quarkus 指南中有经过测试的 bean -->
```java
@Service
public class Inventory {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    public int stock(String sku) {
        return warehouse.stock(sku);
    }
}
```

这个 bean 是 `Inventory$Resilient`，它的 `stock` 重写方法是调用 `super.stock(sku)` 的普通代码。中间没有代理，也没有拦截器链。

## 数据 :id=the-numbers

> [!MEASURED]
> AWS `c7g.xlarge`（Graviton3），3 个 fork，单线程每次调用的 ns 数；resilience4cata 在每一行都分配 0 B。每个框架中显示的是最快的对手：Spring 和 Micronaut 中是 Resilience4j 的拦截器，Quarkus 中是手动调用的 Resilience4j 装饰器或 Fault Tolerance。来自[框架 bean 与完整对比，0.13.0](../benchmarks.md#frameworks)。

| 调用 | Spring：resilience4cata | Spring：Resilience4j | Micronaut：resilience4cata | Micronaut：Resilience4j | Quarkus：resilience4cata | Quarkus：最快的对手 |
|---|---:|---:|---:|---:|---:|---:|
| 重试，第一次尝试成功 | **1.1** | 1346.0 | **1.1** | 104.8 | **1.1** | 22.9 |
| 熔断器 | **1.5** | 1737.0 | **1.5** | 304.5 | **1.5** | 101.6 |
| 打开的熔断器 | **2.3** | 4174.2 | **2.3** | 1127.4 | **2.3** | 355.9 |
| 限流器 | **42.0** | 1481.8 | **42.0** | 141.9 | **42.0** | 66.7 |
| 舱壁隔离 | **20.4** | 1687.7 | **20.7** | 490.2 | 20.4 | **20.3** |
| 重试 + 熔断器 + 限流器 + 舱壁隔离 | **62.1** | 6104.4 | **62.1** | 1117.9 | **62.0** | 217.2 |

四个线程时差距会拉大，因为拦截器背后的共享状态开始出现争用。Spring 的熔断器变成 1.4 ns 对 2142 ns，Micronaut 的则是 1.5 ns 对 1501 ns。

## 时间花在了哪里 :id=where-the-time-goes

resilience4cata 这一列在三个框架中是一样的，也和核心基准测试中一样。框架根本不在调用路径上。对手各列因框架而异，因为每个框架为不同类型的拦截付出代价：

- **Spring：** resilience4j-spring-boot4 的切面每次调用耗时 1.3 到 6 µs，分配 1.6 到 6.3 KB。它们的切点是动态的，而对切点求值时每次调用都会按名称加载一个类（`Class.forName`）。
- **Micronaut：** 它的编译期 AOP 便宜得多，resilience4j-micronaut 的拦截器耗时 100 ns 到 1.1 µs。Micronaut 自带的 `@Retryable` 和 `@CircuitBreaker` 耗时 1.0 到 1.8 µs，分配 2.3 到 3.6 KB。
- **Quarkus：** Fault Tolerance 的拦截器每次调用耗时 0.3 到 1.9 µs。手动调用、没有拦截器的 Resilience4j 装饰器是那里最快的对手。这也是表中唯一一个 resilience4cata 没有胜出的场景：有空闲槽位的舱壁隔离，20.4 ns 对 20.3 ns。两者都用两次原子操作获取并归还许可，我当时以为这是硬件的下限；其实不是（见下面的更新）。

## 没有胜出的场景 :id=the-cases-that-were-not-wins

同一次运行把每个核心基准测试都与最快的 Resilience4j 变体做了对比。在 156 个可比较的场景中（包括框架场景），resilience4cata 赢了 150 个。我说过会展示持平和落败的场景，下面就是另外六个：

- **延迟为 1 ms 的对冲请求慢了 2 倍**，单线程和四线程都是：2.1 ms 对 1.07 ms。共享定时器以延迟的十六分之一（至少 1 ms）为一个 tick 工作，一个延迟可能晚开始约两个 tick。现在对冲请求从调用开始时计算延迟，代价是一次时钟读取，并且定时器会在截止时间醒来。对冲请求在 1060 µs 时触发，而 Resilience4j 是 1067 µs（[准时的对冲延迟](../benchmarks.md#hedge-precision)）。两个库都很难再低多少，因为两者都要等待操作系统唤醒线程。
- **单线程下有两个舱壁隔离场景持平**：上面 Quarkus 那个，以及直接调用同一个舱壁隔离（20.5 ns 对 20.4 ns）。

> [!NOTE]
> 更新，同一天稍晚：这些并不是硬件的下限。在 Graviton3 上，用 volatile 加法而不是仅 release 的加法归还槽位测得更快，现在舱壁隔离耗时 17.3 ns，而 Resilience4j 是 20.4 ns（[用 volatile 加法归还槽位](../benchmarks.md#release-counters)）。

- **两个 `Flux` 场景在误差范围内**：四线程下的舱壁隔离和单线程下的限流器，在这两个场景中 resilience4cata 每个流分配的内存比 resilience4j-reactor 的操作符多。这一点正在改进。

输掉一个基准测试是有用的。对冲请求的延迟自 0.4.0 起就写在文档里（"最多晚约两个 tick"），但只有与精确定时器对比，才让它显出了本来面目。

## 注意事项 :id=caveats

- **微秒只在廉价调用旁边才重要。** 一个耗时 20 ms 的远程调用不会注意到 2 µs 的拦截。一个保护内存缓存、本地调用或每秒数百万次调用的服务则会注意到。
- **这些是微基准测试**，只在一种机器类型上运行，每个策略都配置为永不拒绝。[基准测试页面](../benchmarks.md)中有方法、公平性说明和所有数据，包括持平和落败的场景。
- **bean 是一个子类。** 对于加注解的类，基于类的代理（如 Spring 的 `@Transactional`）位于策略之外，因此一个事务会跨越所有重试。当被重试的方法加入事务时，每个集成都会拒绝启动，各指南解释了如何选择。

## 试一试 :id=try-it

添加你所用框架的集成以及注解处理器。[Spring Boot](../guides/spring.md)、[Micronaut](../guides/micronaut.md) 和 [Quarkus](../guides/quarkus.md) 指南中有 Gradle 和 Maven 的依赖以及经过测试的示例。如果你来自 Resilience4j，[迁移指南](../migrate/from-resilience4j.md)对应了每个注解和属性。代码以 Apache-2.0 许可证发布在 [GitHub](https://github.com/fabiangftech/resilience4cata) 上。
