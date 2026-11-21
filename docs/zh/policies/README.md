# 策略

resilience4cata 有八种策略，每种对应一个注解；可以把它们放在方法上或整个接口上，单独使用或叠加使用，注解处理器会按一个默认顺序把它们嵌套起来，`@PolicyOrder` 可以改变这个顺序。

| 策略 | 注解 | 作用 | 拒绝时抛出 |
|---|---|---|---|
| [降级（Fallback）](fallback.md) | `@Fallback` | 调用失败或被拒绝时，返回一个替代结果 | — |
| [重试（Retry）](retry.md) | `@Retry` | 失败后再次调用，带退避和抖动 | —（重新抛出最后一次失败） |
| [对冲请求（Hedge）](hedge.md) | `@Hedge` | 第一次调用较慢时再发起同样的调用，保留最先成功的结果（仅限异步、幂等调用） | —（重新抛出最后一次失败） |
| [熔断器（Circuit breaker）](circuit-breaker.md) | `@CircuitBreaker` | 停止调用持续失败的依赖，之后再试探它 | `CallNotPermittedException` |
| [限流器（Rate limiter）](rate-limiter.md) | `@RateLimiter` | 每个周期最多放行 N 次调用 | `RateLimitExceededException` |
| [自适应限流（Adaptive limit）](adaptive-limit.md) | `@AdaptiveLimit` | 限制同时运行的调用数，并根据调用延迟调整这个上限 | `AdaptiveLimitFullException` |
| [超时（Timeout）](timeout.md) | `@Timeout` | 中断耗时过长的同步调用，或让耗时过长的异步调用失败 | `CallTimeoutException` |
| [舱壁隔离（Bulkhead）](bulkhead.md) | `@Bulkhead` | 限制同时运行的调用数 | `BulkheadFullException` |

除 Hedge 外，每种策略都适用于同步方法，以及返回 `CompletionStage<T>` 或 `CompletableFuture<T>` 的方法（见[异步方法](../guides/async.md)）；Hedge 需要异步方法，并占据 Retry 的位置（两者不能在同一个方法上组合使用）。这些异常位于 `dev.resilience4cata` 包中，继承 `RuntimeException`，没有堆栈跟踪，并携带策略的 `id()`。四种拒绝（`CallNotPermittedException`、`RateLimitExceededException`、`AdaptiveLimitFullException`、`BulkheadFullException`）有共同的父类 `CallRejectedException`：捕获它即可用同一种方式应对所有拒绝，例如返回 `503 Service Unavailable`。每种策略抛出的是同一个共享实例，所以不要按引用比较它们。

## 我需要哪种策略？ :id=which-policy-do-i-need

| 问题 | 策略 | 从这里开始 |
|---|---|---|
| 调用偶尔失败，再试一次通常就成功 | 重试 | `@Retry(maxAttempts = 3, delay = "200ms")` |
| 某个依赖宕机几分钟，调用堆积在那里等待它 | 熔断器（放在重试内部） | `@CircuitBreaker(name = "…")` |
| 某个依赖卡住，你的线程也跟着卡住 | 超时 | `@Timeout("2s")` |
| 某个 API 每秒只允许 10 个请求 | 限流器 | `@RateLimiter(permits = 10, period = "1s")` |
| 一个慢依赖耗尽了你所有的线程或连接 | 舱壁隔离 | `@Bulkhead(maxConcurrent = 10)` |
| 同上，但你不知道它能承受多少并发调用，或者这个数会变化 | 自适应限流 | `@AdaptiveLimit`，内部再加一个 `@Timeout` |
| 对多副本后端的幂等读取，p99 远高于 p50 | 对冲请求 | 在返回 `CompletableFuture` 的方法上使用 `@Hedge(delay = "50ms")` |
| 存在合理的降级结果（缓存、默认值、"稍后再试"） | 降级 | `@Fallback(method = "…")` |
| 失败体现在结果里，而不是异常（HTTP `503`） | 重试 / 熔断器配合[分类器](../guides/classifiers.md) | `@Retry(classifier = …)` |

远程调用的常见组合是超时、熔断器和重试，在有降级结果时再加一个降级。

## 默认顺序 :id=default-order

从最外层开始：

```mermaid
flowchart LR
    caller(["调用方"]) --> F["Fallback"] --> R["Retry | Hedge"] --> C["CircuitBreaker"] --> L["RateLimiter"] --> A["AdaptiveLimit"] --> T["Timeout"] --> B["Bulkhead"] --> M(["你的方法"])
```

注解的书写顺序无关紧要。默认顺序的选择，是为了让每种策略看到它应该看到的东西：

- **降级在最外层**，因此它看到的是最终结果：重试放弃后的最后一次失败，以及内部各策略的每一次拒绝。
- **重试在熔断器外面**，因此每次尝试都会被熔断器记录，而且打开的熔断器会停止重试（见下文）。**对冲请求**处于同一位置，原因也相同：每次对冲尝试都会各自经过熔断器、限流器、超时和舱壁隔离。
- **熔断器在限流器外面**，因此被它拒绝的调用不会消耗许可。
- **自适应限流在限流器内部、超时和舱壁隔离外部**，因此其内部的超时或已满的舱壁就是降低其上限的过载信号。
- **超时在重试内部**，因此它按每次尝试生效；`@Retry(maxDuration = …)` 限制整个操作。
- **舱壁隔离在最内层**，因此只有在方法真正运行时才占用槽位：重试退避期间不占用，等待许可时也不占用。

## 改变顺序 :id=changing-the-order

`@PolicyOrder` 列出要移动的层，从最外层开始。它们会按该顺序一起移动到其中最外层那一层的位置；未列出的层保持默认顺序围绕在它们周围，方法上没有的层会被忽略。下面的例子中，超时包住整个重试循环，所以一秒钟限制的是所有尝试加在一起：

<!-- snippet: policies-order -->
```java
import static dev.resilience4cata.annotation.Layer.RETRY;
import static dev.resilience4cata.annotation.Layer.TIMEOUT;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportApi {

    @PolicyOrder({TIMEOUT, RETRY})                // Fallback -> Timeout -> Retry -> ...: one deadline for every attempt
    @Timeout("1s")
    @Retry(maxAttempts = 10, delay = "300ms", jitter = Jitter.NONE)
    String report(String id);
}
```

常见的改法：

- **`{TIMEOUT, RETRY}`**：整个操作只有一个截止时间，如上所示。默认顺序下每次尝试都有自己的超时（`@Retry(maxDuration = …)` 也能在不调整顺序的情况下限制整个操作）。
- **`{CIRCUIT_BREAKER, RETRY}`**：熔断器把整个操作记录一次，而不是记录每次尝试；打开的熔断器会在重试开始前就拒绝调用。
- **`{BULKHEAD, RETRY}`**：舱壁在整个操作期间（包括退避暂停）都占用一个槽位，因此它限制的是进行中的操作，而不是正在执行的调用。

其他需要知道的：

- **降级始终在最外层**，所以它仍然看到最终结果。只有当重试是紧挨在降级内部的那一层时（默认就是如此），`FallbackContext.attempts()` 才会统计重试的尝试次数；如果中间隔着其他层，它返回 `1`。
- **任何顺序都能编译。** 有些顺序会以值得检查的方式改变语义：限流器在熔断器外面时，会为熔断器拒绝的调用消耗许可；舱壁在超时外面时，会一直占用槽位直到超时触发。
- **放在哪里：** 方法上、类型上（对所有方法生效）或[组合注解](../guides/bundles.md)里；方法上的优先于类型上的，直接注解优先于组合注解中的。同一层列出两次会报 [`R4C-ORD-001`](../errors.md#r4c-ord-001)。
- **它在编译时决定**，和其余生成的代码一样，所以不能来自配置。[`Pipeline`](../guides/pipeline.md) 总是使用默认顺序。

## 组合使用 :id=combining-them

<!-- compare:start -->
<!-- snippet: policies-stack -->
```java
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface CheckoutApi {

    @Bulkhead(maxConcurrent = 20)                       // the order you write them in does not matter:
    @Timeout("2s")                                      // they always nest as
    @RateLimiter(permits = 50, period = "1s")           // Fallback → Retry → CircuitBreaker
    @CircuitBreaker(name = "checkout")                  //   → RateLimiter → Timeout → Bulkhead → method
    @Retry(maxAttempts = 3, delay = "200ms")
    @Fallback(method = "later")
    String placeOrder(String cartId);

    default String later(String cartId, Exception failure) {
        return "queued " + cartId;
    }
}
```
<!-- not-compiled: 生成的代码，有删节 -->
```java
public final class CheckoutApi$Resilient implements CheckoutApi {
    // … one final field per policy, created from the registry in the constructor

    @Override
    public java.lang.String placeOrder(java.lang.String p0) {           // Fallback + Retry
        final dev.resilience4cata.generated.RetryPolicy r4c$retry = this.retry0;
        final long r4c$start = r4c$retry.start();
        int r4c$attempts = 0;
        while (true) {
            try {
                return this.r4c$circuitBreaker0(p0);
            } catch (java.lang.Throwable r4c$e) {
                final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);
                if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {
                    if (!this.fallback0.handles(r4c$e)) {
                        throw r4c$e;
                    }
                    if (r4c$e instanceof java.lang.Exception r4c$f0) {
                        return this.delegate.later(p0, r4c$f0);
                    }
                    throw r4c$e;
                }
            }
        }
    }

    private java.lang.String r4c$circuitBreaker0(java.lang.String p0) {
        // … acquire a permit, then:
            final java.lang.String r4c$result = this.r4c$rateLimiter0(p0);
        // …
    }

    private java.lang.String r4c$rateLimiter0(java.lang.String p0) {
        // … take a permit, then:
        return this.r4c$timeout0(p0);
    }

    private java.lang.String r4c$timeout0(java.lang.String p0) {
        // … start timing, then:
            r4c$result = this.r4c$bulkhead0(p0);
        // …
    }

    private java.lang.String r4c$bulkhead0(java.lang.String p0) {
        // … take a slot, then:
            return this.delegate.placeOrder(p0);
        // …
    }
}
```
<!-- compare:end -->

每种策略都变成一层直线式代码：公共方法承载最外层，每个内层都是一个私有方法，调用下一层，直到你的实现。除了你通过 `name` 共享的东西之外，方法之间不共享任何内容。

- 注解也可以放在接口本身上，对所有方法生效，还可以打包进你自己的注解；见[组合注解](../guides/bundles.md)。
- 每种策略在你包装接口时读取一次配置，依次来自内置默认值、`defaults.*`、命名配置和注解；见[配置](../guides/configuration.md)。
- 相同 `name` 的熔断器、限流器和舱壁共享一个实例，使用该 id 的 pipeline 也是如此。重试和超时在调用之间不保留状态，唯一的例外是带有[重试预算](retry.md#retry-budget)的重试，它以同样的方式共享其预算。

### 打开的熔断器会停止重试 :id=an-open-breaker-stops-the-retries

重试永远不会重试 `CallNotPermittedException`（打开的熔断器抛出的异常），无论其 `abortOn` 怎么设置。所以一旦熔断器打开，重试会在下一次尝试时放弃，而不是等完退避，然后由降级给出结果：

<!-- snippet: policies-open-breaker -->
```java
CheckoutApi checkout = Resilient.of(CheckoutApi.class, cartId -> {
    calls.incrementAndGet();
    return "placed " + cartId;
}, registry);
registry.circuitBreaker("checkout").forceOpen();

assertThat(checkout.placeOrder("cart-1")).isEqualTo("queued cart-1");  // the fallback answered
assertThat(calls).hasValue(0);                  // the breaker rejected the first attempt...
assertThat(clock.elapsed()).isZero();           // ...and the retry did not wait to try again
```

### 拒绝与外层 :id=rejections-and-the-outer-layers

默认情况下，对外层来说，内层策略的拒绝和其他 `Exception` 一样，只有一个例外：

- `RateLimitExceededException`、`AdaptiveLimitFullException`、`CallTimeoutException` 和 `BulkheadFullException` 会被外层的 `@Retry` **重试**（在其延迟之后）。如果这不是你想要的，把它们列入重试的 `abortOn`。
- 外层的 `@CircuitBreaker` 会把 `CallTimeoutException` **记录**为失败，但**永远不会**记录本地限制的拒绝（`BulkheadFullException`、`RateLimitExceededException`、`AdaptiveLimitFullException`），无论其 `recordOn`、`ignoreOn` 或分类器怎么说：舱壁已满是本应用自身的负载，依赖正常时不应因此打开熔断器。
- `Error` 不会被重试（`retryOn` 默认为 `Exception`），但熔断器会把它们记录为失败（`recordOn` 默认为 `Throwable`），降级的默认值 `applyOn = Throwable` 也会捕获它们，除非其异常参数类型更窄。

## 编程式使用 :id=programmatic-use

无法加注解的代码可以通过 [`Pipeline`](../guides/pipeline.md) 以默认顺序获得同样的策略。每个策略页面都展示了对应的 pipeline 写法。

## 开销 :id=what-it-costs

> [!MEASURED]
> 在 AWS Graviton3（`c7g.xlarge`，JDK 21）上，一次成功调用经过重试、熔断器、限流器和舱壁隔离，生成的代码耗时 61.6 ns，而 Resilience4j 2.4.0 为 261 ns 和 328 B；全部六种策略耗时 76.3 ns（Resilience4j 没有调用方线程上的超时可供比较）。在这些路径上，生成的代码不做任何内存分配。[基准测试：MVP 完成](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)

## 术语表 :id=glossary

- **退避与抖动。** 退避是重试之间的等待如何增长（固定或指数）。抖动让每次等待随机化，避免许多调用方在同一时刻重试；默认的 *full jitter*（完全抖动）在零到退避值之间均匀选取等待时间。
- **舱壁隔离（Bulkhead）。** 对同时运行的调用数的限制，使一个慢依赖无法占用所有线程。超出限制的调用会等待（`maxWait`）或被拒绝。
- **熔断器状态。** *关闭（Closed）*：调用通过，并统计其结果。*打开（Open）*：调用立即被拒绝，持续 `waitInOpen`。*半开（Half-open）*：放行少量试探调用（`halfOpenCalls`），由其结果决定熔断器是关闭还是再次打开。
- **派生 id。** 注解没有 `name` 时方法的策略实例 id：`<type>#<method>(<parameter types>)`，例如 `com.acme.CatalogClient#price(java.lang.String,int)`。用于配置键、事件、指标和句柄。
- **对冲请求（Hedge）。** 第一次尝试较慢时再次发起同样的调用，保留最先成功的那个。
- **实例。** 一个带有自身状态的策略对象（熔断器的窗口、舱壁的槽位）。使用相同 `name` 的方法共享一个实例；否则每个方法有自己的实例。
- **注册表（Registry）。** 即 `ResilienceRegistry`：它保存配置并创建实例，每个只创建一次。每个生成的类和 `Pipeline` 都从注册表获取策略。
- **拒绝。** 策略在运行调用之前就拒绝它：熔断器打开、舱壁或自适应限流已满、限流器耗尽。它会抛出 `CallRejectedException`。
- **无堆栈异常。** 创建时不带堆栈跟踪的异常。拒绝是无堆栈且预分配的，所以拒绝一次调用几乎没有开销；请记录它们的消息或 `id()`。
- **直线式代码。** 生成的代码在方法本身中按顺序直接调用每种策略，而不是通过一串共享的 lambda。正是它让调用保持低开销。
- **ABI 检查（`Abi.require`）。** 每个生成的构造函数开头的一次调用：它检查类路径上的 `resilience4cata-core` 至少与生成该类的注解处理器一样新，否则以 [`R4C-RT-021`](../errors.md#r4c-rt-021) 失败。
