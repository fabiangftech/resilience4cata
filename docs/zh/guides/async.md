# 异步方法

返回 `CompletionStage<T>` 或 `CompletableFuture<T>` 的方法会获得每种策略的非阻塞形式：不会有任何线程为它休眠、等待或被挂起。

没有 `@Asynchronous` 注解：由返回类型决定。适用相同的注解、属性和配置键。

## 一个异步接口 :id=an-asynchronous-interface

<!-- snippet: async-interface -->
```java
public interface TrackingClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @Fallback(method = "unknown")
    CompletableFuture<String> track(String parcel);

    default CompletionStage<String> unknown(String parcel, IOException failure) {
        return CompletableFuture.completedFuture("unknown (" + failure.getMessage() + ")");
    }

    @Timeout("100ms")
    CompletionStage<String> eta(String parcel);
}
```

<!-- snippet: async-call -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
TrackingClient tracking = Resilient.of(TrackingClient.class, http, registry);

CompletableFuture<String> status = tracking.track("p-1");   // returns at once: no thread sleeps between attempts
// status completes with "in transit" after two failed attempts
```

调用会立即返回一个 future。第一次尝试在调用方线程上运行；失败后，重试（retry）会在计时器上按延迟安排下一次尝试，随后该尝试在一个新的虚拟线程上开始。延迟期间没有线程被阻塞。

## 与同步方法相比有何不同 :id=what-changes-compared-with-a-synchronous-method

| 策略 | 同步方法 | 异步方法 |
|---|---|---|
| 重试 | 在两次尝试之间让调用方线程休眠 | 安排下一次尝试；当某次尝试成功或重试放弃时，返回的 future 完成 |
| [对冲请求](../policies/hedge.md) | 不支持（编译错误 [`R4C-HDG-001`](../errors.md#r4c-hdg-001)） | 在 `delay` 内没有结果时再次发起调用，失败后则立即发起；第一个成功的结果完成 future，其余尝试被取消 |
| 熔断器 | 在调用返回或抛出异常时记录调用 | 在其 stage 完成时记录调用 |
| 带 `maxWait` 的限流器 | 休眠直到许可到期 | 许可到期时开始调用，不阻塞调用方 |
| [自适应限流](../policies/adaptive-limit.md) | 在方法运行期间占用槽位，并测量其延迟 | 占用槽位直到 stage 完成，并测量到该完成为止的延迟 |
| 超时 | 在截止时间中断调用方线程 | 在截止时间以 `CallTimeoutException` 使返回的 future 失败，并在迟到的 stage 是 `Future` 时取消它（经过内部的各个策略，它们会立即释放：见[取消](#cancellation)） |
| 舱壁隔离 | 在方法运行期间占用槽位；带 `maxWait` 时在信号量上等待 | 占用槽位直到 stage 完成；带 `maxWait` 时将调用排队，不阻塞任何线程 |
| 降级 | 返回一个值 | 返回一个值或一个 stage |

对每种策略都成立的规则：

- **抛出异常而不是返回 stage 的方法** 被视为失败的 stage：策略会看到该异常，如果没有任何策略恢复，返回的 future 就以它失败。返回 `null` 而不是 stage 的方法同样会以 `NullPointerException` 失败。
- **失败会被解包。** 以 `CompletionException` 或 `ExecutionException` 失败的 stage 按其 cause 判断，因此 `retryOn`、`recordOn`、分类器和降级方法签名看到的是你的异常（示例中的 `IOException`），而不是包装异常。
- **拒绝会使 future 失败。** 打开的熔断器、已满的舱壁、已满的自适应限流或耗尽的限流器会分别以 `CallNotPermittedException`、`BulkheadFullException`、`AdaptiveLimitFullException` 或 `RateLimitExceededException` 使返回的 future 失败。
- **回调不在计时器上运行。** 延迟的重试、对冲请求、限流器等待和超时在库的计时器上触发，计时器把工作交给一个新的虚拟线程；排队的舱壁调用获得槽位时也是如此。该线程拥有调用方的上下文类加载器（发起调用的线程的那个），没有 `ThreadLocal`、MDC 或继承的线程本地值：绝不会是另一个请求的。请显式传递一次尝试所需的内容（对于 `Mono` 和 `Flux` 方法，Reactor 上下文会到达每一次尝试）。

一个超时的调用，来自同一个测试：

<!-- snippet: async-timeout -->
```java
CompletableFuture<String> eta = tracking.eta("p-1").toCompletableFuture();

assertThatThrownBy(() -> eta.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(CallTimeoutException.class);   // after 100 ms; no thread was blocked
```

当你自己对 future 调用 `join()` 或 `get()` 时，JDK 会包装失败：`join()` 抛出 `CompletionException`，`get()` 抛出 `ExecutionException`，策略的异常作为 cause。

## 取消 :id=cancellation

取消异步方法返回的 future（使用 `cancel(...)`，或以 `CancellationException` 调用 `completeExceptionally`）会穿过每一层取消其中的调用：每个策略把取消传给它正在等待的 stage，一直到你的方法返回的 stage，如果它是 `Future` 就会被取消。各策略的行为：

| 策略 | 取消时 |
|---|---|
| 重试 | 不再开始新的尝试：待执行的退避被取消，进行中的尝试被取消 |
| 对冲请求 | 不再开始新的尝试：待执行的延迟被取消，所有进行中的尝试都被取消。对冲请求也以同样的方式取消自己的落败者，使用 `HedgeCancelledException` |
| 熔断器 | 不记录任何内容，就像被忽略的调用：半开许可被归还 |
| 带 `maxWait` 的限流器 | 仍在等待许可的调用永远不会开始，许可被归还（与同步等待被中断时一样）。已经开始的调用保留其许可 |
| 自适应限流 | stage 一结束就释放槽位，不产生样本：被取消的调用不能说明依赖的任何情况 |
| 超时 | 取消其截止时间：没有 `CallTimeoutException`，没有 `TimedOut` 事件 |
| 舱壁隔离 | stage 一结束就释放槽位，对 `CompletableFuture` 来说就是立即释放。仍在排队（`maxWait`）的调用离开队列，永远不会开始 |
| 降级 | 不运行：没有人在等待替代结果 |

取消不发布任何事件：没有 `GaveUp`，没有 `Retry`，没有失败，没有拒绝。在你附加到 future 上的回调运行之前，内层已经释放，因此回调可以在被释放的槽位中开始一个新调用。

需要了解的事项：

- **取消到达的是 stage，而不是它背后的工作。** 取消 `CompletableFuture` 会完成它；它不会中断本来要完成它的代码。舱壁在 stage 结束时释放槽位，因此如果你的 stage 不是 `Future`，或者忽略 `cancel`，槽位会一直被占用（熔断器也会记录迟到的结果），直到 stage 完成。
- **取消方法返回的那个 future。** 从它派生的 future（`thenApply`、`whenComplete`、`copy()` 等）是一个新的 `CompletableFuture`：取消它不会到达调用，这与 JDK 一致。
- **取消与完成的竞争是安全的。** 如果调用在同一时刻完成，先到者获胜；无论哪种情况，槽位和许可都恰好释放一次。
- `Pipeline.executeAsync` 的行为相同。

## 降级 :id=fallbacks

异步方法的兄弟降级方法返回值类型 `T` 或 `CompletionStage<T>`（示例中 `unknown` 返回一个 stage）。处理器类实现 `FallbackHandler<T>` 或 `FallbackHandler<CompletionStage<T>>`。降级在重试放弃后运行，也会在拒绝和超时时运行，与其同步形式一样。参见[降级](../policies/fallback.md)。

## 通配符 stage :id=wildcard-stages

返回类型可以是 `CompletionStage<? extends X>` 或 `CompletableFuture<? extends X>`；单独的 `?` 表示 `? extends Object`。此时策略以 `X` 工作：降级方法返回 `X`（或它的 stage），分类器必须接受 `X`。

<!-- snippet: async-wildcard -->
```java
public interface ParcelClient {

    @Retry(maxAttempts = 2, delay = "10ms")
    @Fallback(method = "noWeight")
    CompletionStage<? extends Number> weight(String parcel);

    default Integer noWeight(String parcel, Exception failure) {
        return 0;
    }
}
```

## 不支持的内容 :id=what-is-not-supported

- **其他 stage 类型是编译错误**（[`R4C-ASY-001`](../errors.md#r4c-asy-001)）：原始类型 `CompletionStage`、`? super X`，以及除 `CompletableFuture` 之外的 `CompletionStage` 子类型。
- **Reactor 的 `Mono` 和 `Flux` 通过 `resilience4cata-reactor` 得到支持**：参见 [Project Reactor](reactor.md)。**其他响应式类型是编译错误**（[`R4C-ASY-002`](../errors.md#r4c-asy-002)）：`Flow.Publisher` 及其他 Reactive Streams `Publisher`（RxJava 的 `Flowable`）、Mutiny 的 `Uni` 和 `Multi`，以及 RxJava 的类型。它们只是构建一个在订阅时才运行的管道，因此策略保护的是构建过程，而不是工作本身。请在实现中转换为 `CompletionStage`（`uni.subscribeAsCompletionStage()`），或转换为 `Mono` 或 `Flux`。
- **其他异步类型被当作同步处理。** 返回 `java.util.concurrent.Future` 或其他任何类型的方法会像任何同步方法一样受到保护：策略只看到*返回*该对象的调用，而看不到它所代表的工作。

> [!WARNING]
> 如果方法返回 `Future`，超时或重试不会作用于结果。请改为返回 `CompletableFuture`，或把工作包装在其中。

## 开销 :id=cost

异步调用会分配内存：每一层都会创建 future 并经过 `CompletableFuture` 回调，这是不阻塞线程的代价。同步调用不受影响。

> [!MEASURED]
> 带有挂起 stage 的异步超时在单线程上耗时 152 ns、分配 192 B，而 Resilience4j 2.4.0 为 598 ns 和 240 B（AWS Graviton3 `c7g.xlarge`）。[基准测试](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)

## 另请参阅 :id=see-also

- `Pipeline.executeAsync(...)` 在不使用注解的情况下提供相同的行为（[Pipeline](pipeline.md)）。
- 在 `ManualClock` 上，异步延迟和超时在推进时钟的线程上运行（[测试](testing.md)）。
