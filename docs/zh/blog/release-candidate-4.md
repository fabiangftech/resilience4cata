# resilience4cata 1.0.0-RC4：你自己的舱壁隔离不再打开熔断器

1.0.0 的第四个候选版本已发布到 Maven Central，API 与 RC3 相同，只有一处行为变化：熔断器不再把方法自身的舱壁隔离、限流器或自适应限流的拒绝计为失败。

*2026 年 11 月 14 日 · 作者 [fabiangftech](https://github.com/fabiangftech)，resilience4cata 的作者*

## 试点发现了什么 :id=what-the-pilot-found

目前还没有人在生产环境中运行 resilience4cata，所以在 1.0 之前有一个[试点](https://github.com/fabiangftech/resilience4cata/tree/main/pilot)，这是在没有用户的情况下最接近生产的东西。它运行三个小服务，Spring Boot、Micronaut 和 Quarkus 各一个。每个服务都按照各自指南的说法从 Maven Central 获取 resilience4cata，并通过 HTTP 调用一个模拟后端。k6 向它们施加 10 分钟的负载：每秒 1500 个订单，其中后端宕机一分钟、变慢一分钟。调用后端的方法使用常见的策略组合：

<!-- not-compiled: 试点的服务，有删节 -->
```java
@Fallback(method = "lastKnown")
@Retry(name = "inventory", maxAttempts = 3, delay = "20ms")
@CircuitBreaker(name = "inventory", windowSize = 100, minimumCalls = 20, failureRateThreshold = 50,
        waitInOpen = "5s", halfOpenCalls = 5)
@Timeout("200ms")
@Bulkhead(name = "inventory", maxConcurrent = 40, maxWait = "20ms")
public String stock(String id) throws IOException, InterruptedException { ... }
```

在 1.0.0-RC3 上，Spring 服务在冷启动期间打开了熔断器，而后端一次都没有失败过。JVM 预热时调用较慢，于是 40 个调用占满了舱壁隔离，后续调用被以 `BulkheadFullException` 拒绝。在默认顺序中熔断器位于舱壁隔离之外，所以它把这些拒绝当作了失败。一旦它的窗口中有 20 次调用且其中一半是拒绝，它就打开了，在它保持打开期间订单都走了降级。

试点的第一次运行通过在熔断器上加 `ignoreOn = BulkheadFullException.class` 避开了这个问题。用户必须知道需要这一行，而错误信息中没有任何东西会告诉他们。

## 为什么这是一个陷阱 :id=why-it-is-a-trap

熔断器回答的是一个问题：依赖是否在失败？满的舱壁隔离、耗尽的限流器或满的自适应限流回答的是另一个问题：这个应用是否很忙？把两者混在一起有三个坏影响：

- 你自己的一阵突发流量会在依赖健康时打开熔断器，降级会去回答那些片刻之后本会成功的调用。
- 打开的熔断器随后甚至会拒绝舱壁隔离本会放行的调用，于是过载变成了一次至少持续 `waitInOpen` 的故障。
- 被舱壁隔离拒绝的半开探测会被算作一次失败的探测，于是熔断器在没有询问依赖的情况下再次打开。

Resilience4j 有同样的陷阱：它的舱壁隔离和限流器也位于熔断器之内，而它的 `BulkheadFullException` 和 `RequestNotPermitted` 会被计为失败，除非你把它们列在 `ignoreExceptions` 中。1.0.0-RC4 的区别在于你不再需要记住这一点。

## RC4 中的规则 :id=the-rule-in-rc4

熔断器从不记录 `BulkheadFullException`、`RateLimitExceededException` 或 `AdaptiveLimitFullException`。它们既不算失败也不算成功，收到它们的半开探测会归还其许可。无论 `recordOn`、`ignoreOn` 或分类器怎么说，这一点对同步、`CompletionStage`、`Mono` 和 `Flux` 方法以及 `Pipeline` 都成立。详情见[熔断器页面](../policies/circuit-breaker.md#with-other-policies)。

有两种异常仍然会被计入，因为它们确实反映了依赖的情况：

- `CallTimeoutException`：太慢的依赖就是在失败。因此慢依赖仍会通过超时和慢调用打开熔断器。
- 来自更内层另一个熔断器的 `CallNotPermittedException`：它背后的依赖正在失败。

这条规则是固定的，没有开关，这是有意为之：不应该有人需要靠配置才能走出陷阱。应该对过载做出反应的熔断器是另一种工具，而那正是[舱壁隔离](../policies/bulkhead.md)和[自适应限流](../policies/adaptive-limit.md)的用途。

## 对你有什么变化 :id=what-changes-for-you

- 如果你在熔断器上加了 `ignoreOn = BulkheadFullException.class`，或者限流器、自适应限流的拒绝，可以把它删掉。保留它也无害。
- 如果你从 Resilience4j 迁移，从 `ignoreExceptions` 中去掉这些条目：[迁移指南](../migrate/from-resilience4j.md#local-rejections)中有相应说明。
- 其他都没有变化。每个发布的模块都与 RC3 做了对比：API 中没有不兼容的变更。用 RC3 的处理器编译的 `examples` 测试在 RC4 的模块上通过，因此 RC3 生成的代码可以继续工作。

## 再次运行试点 :id=the-pilot-again

随后试点在新代码上运行，没有任何 `ignoreOn`，使用同一台机器。

> [!MEASURED]
> AWS `c7g.4xlarge`（Graviton3），JDK 25，2026-11-13，1.0.0-RC4 的代码。每个服务 4 个核心，后端 4 个，k6 8 个。这是 Spring Boot 的一行；Micronaut 和 Quarkus 在 p99 及以下与它相差不到一毫秒。`pilot/run.sh aws` 的运行 `20261113-202658`，汇总在[试点的 README](https://github.com/fabiangftech/resilience4cata/tree/main/pilot) 中。

| 阶段 | 由后端应答的订单 | p50 | p99 | p99.9 |
|---|---|---:|---:|---:|
| 稳定 | 100 %（其 1 % 的 503 通过重试消除） | 6.8 ms | 28.7 ms | 42.3 ms |
| 后端宕机 | 0 %：熔断器应答了每个订单 | 0.1 ms | 0.2 ms | 1.2 ms |
| 后端变慢（1 s） | 0 %：熔断器、舱壁隔离或超时；没有人等满那一秒 | 0.1 ms | 0.3 ms | 260.6 ms |
| 故障之后 | 100 % | 6.8 ms | 28.7 ms | 42.1 ms |

三个服务都通过了每项检查，每个约 104 万个请求。在 Spring 服务中，稳定阶段的 270,000 个订单中有一个是由打开的熔断器应答的。它恰好在第 4 分钟后端宕机前开始，而它的重试发现熔断器已经打开：这是熔断器在履行职责，而不是旧的陷阱。

## 试一试 :id=try-it

<!-- tabs:start -->

#### **Gradle**

<!-- not-compiled: 构建配置 -->
```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC4'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC4'
}
```

#### **Gradle (Kotlin)**

<!-- not-compiled: 构建配置 -->
```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.0.0-RC4")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.0.0-RC4")
}
```

#### **Maven**

<!-- not-compiled: 构建配置 -->
```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>1.0.0-RC4</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0-RC4</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

[快速入门](../start/quickstart.md)大约五分钟就能保护一个不稳定的调用；对于应用，请从 [Spring Boot](../guides/spring.md)、[Micronaut](../guides/micronaut.md) 或 [Quarkus](../guides/quarkus.md) 的指南开始。[RC2 公告](release-candidate.md)解释了候选版本在这里意味着什么，以及哪些反馈最有帮助。

## 接下来 :id=what-comes-next

如果没有其他需要修改的，1.0.0 将以相同的 API 发布，[兼容性](../reference/compatibility.md)承诺随之开始生效。这次改动正是候选版本存在的意义：在 1.0 之后，在次版本中改变熔断器的计数内容会让用户感到意外。如果你试用 RC4 时有什么让你意外的，请在 GitHub 上提交 [issue](https://github.com/fabiangftech/resilience4cata/issues) 或发起[讨论](https://github.com/fabiangftech/resilience4cata/discussions)。
