# Project Reactor

返回 Reactor 的 `Mono<T>` 或 `Flux<T>` 的方法会获得每个策略的异步形式，每次订阅执行一次，携带订阅者的 context，并在订阅者取消时经由各策略被取消。

<div class="r4c-facts">

| 模块 | Reactor | 类型 | 起始版本 |
|---|---|---|---|
| `resilience4cata-reactor` | 3.6 或更新 | `Mono<T>`, `Flux<T>` | 0.11.0 |

</div>

## 安装 :id=install

在其他模块旁边添加该模块；注解处理器不需要任何新东西。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC4'
    implementation 'dev.resilience4cata:resilience4cata-reactor:1.0.0-RC4'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC4'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.0.0-RC4")
    implementation("dev.resilience4cata:resilience4cata-reactor:1.0.0-RC4")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.0.0-RC4")
}
```

#### **Maven**

```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-reactor</artifactId>
  <version>1.0.0-RC4</version>
</dependency>
```

<!-- tabs:end -->

该模块依赖 `resilience4cata-core` 和 `reactor-core`；你的应用自身使用的更新版本的 Reactor 优先。没有该模块就编译 `Mono` 方法会报错 [`R4C-ASY-003`](../errors.md#r4c-asy-003)。

## Mono 接口 :id=a-mono-interface

注解与其他任何方法上的相同；由返回类型决定行为。

<!-- snippet: reactor-interface -->
```java
public interface PriceClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @CircuitBreaker(name = "prices")
    @Timeout("2s")
    @Fallback(method = "unknown")
    Mono<BigDecimal> price(String sku);

    default Mono<BigDecimal> unknown(String sku, Throwable failure) {
        return Mono.just(BigDecimal.ZERO);
    }
}
```

<!-- snippet: reactor-call -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
PriceClient prices = Resilient.of(PriceClient.class, http, registry);

Mono<BigDecimal> price = prices.price("sku-1");   // nothing runs yet
BigDecimal value = price.block();                 // subscribes: two failed attempts, then 42
```

带注解的类也以同样方式工作（[带注解的类](annotated-classes.md)），Spring、Micronaut 和 Quarkus 的 bean 也一样：在 WebFlux 控制器中，直接返回受保护 bean 的 `Mono` 即可。

## Mono 方法做了什么 :id=what-a-mono-method-does

- **订阅之前什么都不运行。** 调用 `price("sku-1")` 只是构建一个 `Mono`；每次订阅都是经过策略的一次调用，因此订阅两次（或 `repeat()`）就会调用两次。
- **每次尝试都会再次调用方法。** 重试或对冲请求会调用你的实现以获得一个新的 `Mono` 并订阅它，与 `CompletionStage` 方法相同。抛出异常或返回 `null` 的方法算作一次失败的尝试。
- **订阅者的 context 会到达每一次尝试**，降级的 `Mono` 也一样，因此追踪、Spring Security 的 `ReactiveSecurityContextHolder` 和你自己的键在策略内部都能工作：

<!-- snippet: reactor-context -->
```java
Mono<BigDecimal> price = prices.price("sku-1")
        .contextWrite(Context.of("tenant", "acme"));   // the delegate's Mono, and every retry, see it
```

- **空的 `Mono` 是没有值的成功**（对分类器而言为 `null`），结果也为空。
- **取消订阅会经由每个策略取消调用：** 当前尝试的 `Mono` 被取消，等待延迟的重试停止，熔断器不记录任何内容，舱壁隔离和自适应限流释放它们的槽位。从 WebFlux 端点断开连接的客户端就会触发这一过程。
- **失败无需请求量即可到达，值按请求量到达**，这是 Reactive Streams 的要求；生成的 `Mono` 通过了 [Reactive Streams TCK](https://github.com/reactive-streams/reactive-streams-jvm/tree/master/tck)。
- **没有任何策略会阻塞线程**，这一点由构建中的 BlockHound 检查：带 `maxWait` 的限流器或舱壁隔离会让调用等待而不挂起线程，与任何[异步方法](async.md)相同。

由哪个线程发出：第一次尝试在订阅线程上订阅。重试延迟、对冲请求、超时和限流器等待在库的定时器上触发，定时器把工作交给一个新的虚拟线程，因此后续尝试的值在那里到达。如果后面的操作符需要特定的调度器，请在方法之后添加 `publishOn(...)`。

> [!MEASURED]
> 在 AWS Graviton3 上，带熔断器的 `Mono` 方法每次调用耗时 106 ns，而 resilience4j-reactor 2.4.0 的操作符为 243 ns（四线程时为 105 对 1291 ns）；重试、熔断器、超时和舱壁隔离的组合为 201 ns 对 4319 ns，其中包括订阅 `Mono` 的 52 ns。参见[性能](../performance.md#reactor)。

## 降级 :id=fallbacks

`Mono` 方法的降级返回值（`BigDecimal`）或它的 `Mono`（`Mono<BigDecimal>`），可以是同级方法或 [`FallbackHandler`](../policies/fallback.md)；不接受 `CompletionStage`。它的 `Mono` 以调用方的 context 被订阅。

## Flux 方法 :id=flux

返回 `Flux<T>` 的方法获得同样的策略，并适配为流：每次订阅是一次调用，每次尝试都会再次订阅方法的 `Flux`，其元素在到达时传给订阅者，带有订阅者的请求量和 context。

<!-- snippet: reactor-flux-interface -->
```java
public interface PriceFeed {

    @Retry(maxAttempts = 3, delay = "50ms")
    @Timeout("5s")                       // at most 5 s to the first price, and between prices
    @Fallback(method = "closed")
    Flux<BigDecimal> prices(String sku);

    default Flux<BigDecimal> closed(String sku, Throwable failure) {
        return Flux.empty();
    }
}
```

<!-- snippet: reactor-flux-call -->
```java
PriceFeed feed = Resilient.of(PriceFeed.class, exchange, ResilienceRegistry.builder().build());

feed.prices("sku-1")
        .doOnNext(price -> System.out.println(price))   // two failed attempts, then 41, 42, 43
        .blockLast();
```

- **第一个元素使调用提交。** 在它之前，失败会被重试或由降级替代，与 `Mono` 相同。在它之后，失败原样到达订阅者：重试会再次发送元素，而降级会混合两个来源。

<!-- snippet: reactor-flux-committed -->
```java
StepVerifier.create(committed.prices("sku-1"))
        .expectNext(new BigDecimal(41))       // the first element: no retry, no fallback from now on
        .expectErrorMessage("feed broke")     // so the failure reaches the subscriber as it is
        .verify();
```

- **`@Timeout` 是没有元素的时间：** 到第一个元素、两个元素之间、以及到结束。持续发出元素的流无论持续多久都不会超时；停滞的流会超时，可能在第一个元素之前（随后被重试）或之后（订阅者收到 `CallTimeoutException`）。等待订阅者请求量所花的时间也计算在内，与 Reactor 的 `timeout` 操作符相同。
- **熔断器**在每次尝试的流结束时记录一次调用，因此元素之后的失败算作失败；`slowCallDuration` 是到第一个元素的时间。**自适应限流**在流运行期间占用一个槽位，并采样到第一个元素的时间。
- **舱壁隔离**一直占用槽位，直到流结束或被取消。**限流器**每次尝试获取一个许可，而不是每个元素一个。
- **降级**返回 `Flux<T>`，或一个值 `T`（只含这一个元素的流），可以是同级方法或 [`FallbackHandler`](../policies/fallback.md)。
- **请求量**会延续：下一次尝试会被请求订阅者请求过的全部数量，因为在第一个元素之前什么都没有发出。非正数的请求会被报告并忽略，与 Reactor 自己的操作符一样（对任何其他订阅者，Reactor 会自己发出规则 3.9 的错误）；生成的 `Flux` 通过了 Reactive Streams TCK 的每一条规则。
- **取消订阅**会经由每个策略取消调用；在 WebFlux 中，从返回 bean 的 `Flux` 的 server-sent events 端点（`produces = MediaType.TEXT_EVENT_STREAM_VALUE`）断开连接的客户端就会触发这一过程，舱壁隔离会释放它的槽位。
> [!MEASURED]
> 在 AWS Graviton3 上，十个元素的流经过带 `@Timeout` 的 `Flux` 方法耗时 681 ns，而 resilience4j-reactor 2.4.0 的 time limiter（`flux.timeout`，每个元素一个定时器）为 28896 ns；重试、熔断器、超时和舱壁隔离的组合为 789 ns 对 30583 ns；一千个元素带超时耗时 45 µs 对 2.6 ms。单独的舱壁隔离或限流器与 Resilience4j 的差距在误差范围内。参见[性能](../performance.md#reactor)。

- **不能用于 `Flux` 方法：** `@Hedge`（它会合并两个流）、分类器（多个元素只有一个结果：请使用 `retryOn`/`abortOn` 和 `recordOn`/`ignoreOn`），以及改变顺序的 `@PolicyOrder`。每一种都会报 [`R4C-ASY-004`](../errors.md#r4c-asy-004)。

## 无法加注解的代码 :id=code-you-cannot-annotate

`MonoPipelineOperator` 把一个 [pipeline](pipeline.md) 应用到 `Mono` 上，适用于 `WebClient` 调用或第三方客户端：

<!-- snippet: reactor-operator -->
```java
MonoPipelineOperator<String> resilient = MonoPipelineOperator.of(Pipeline.builder(registry, "rates", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(2)))
        .build());

Mono<String> rates = webClientCall.transform(resilient);   // each attempt subscribes to webClientCall again
```

每次尝试都会再次订阅来源，从而再次执行一个 `WebClient` 请求。对每个订阅者给出相同结果的来源（`cache()`、热来源）无法被有意义地重试；请改用 `resilient.defer(() -> ...)` 为每次尝试构建它。

`FluxPipelineOperator` 对 `Flux`（`bodyToFlux`）做同样的事，遵循[上文](#flux)的流规则；pipeline 的降级值会变成只含这一个元素的流，带有对冲请求或分类器的 pipeline 无法保护 `Flux`（[`R4C-RT-020`](../errors.md#r4c-rt-020)）：

<!-- snippet: reactor-flux-operator -->
```java
FluxPipelineOperator<String> resilient = FluxPipelineOperator.of(Pipeline.builder(registry, "quotes", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(5)))
        .build());

Flux<String> quotes = webClientStream.transform(resilient);   // an attempt subscribes to webClientStream again
```

## 测试 :id=testing

在 [`ManualClock`](testing.md) 上，延迟、超时和对冲请求在测试推进时钟时发生，由 `StepVerifier` 检查结果：

<!-- snippet: reactor-testing -->
```java
ManualClock clock = new ManualClock();
ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
PriceClient prices = Resilient.of(PriceClient.class, slow, registry);

StepVerifier.create(prices.price("sku-1"))
        .then(() -> clock.advance(Duration.ofSeconds(7)))   // three 2 s timeouts and the delays between them
        .expectNext(BigDecimal.ZERO)                        // the fallback
        .verifyComplete();
```

手动时钟属于本库；`StepVerifier.withVirtualTime` 推进的是 Reactor 的调度器，而不是策略的定时器。

## 限制 :id=limits

- **必须恰好是 `Mono` 或 `Flux`：** 它们的子类、`Mono<? super T>` 或原始类型 `Mono` 都不是响应式方法（[`R4C-ASY-001`](../errors.md#r4c-asy-001)，子类为 `R4C-ASY-002`）。
- **`Flux` 方法**没有对冲请求、没有分类器，并使用默认顺序（[`R4C-ASY-004`](../errors.md#r4c-asy-004)），其重试和降级只在第一个元素之前起作用（[上文](#flux)）。
- **事务：** 带注解类中被重试或对冲的 `Mono` 方法上的响应式 `@Transactional` 会让每次尝试都在同一个事务中运行，Spring、Micronaut 和 Quarkus 会在启动时报告这一点，与其他方法相同（[`R4C-SPR-004`](../errors.md#r4c-spr-004)）。
- **Mutiny 和 RxJava** 尚不支持；请转换为 `CompletionStage`（`uni.subscribeAsCompletionStage()`）。
