# 分类器

分类器为 `@Retry` 和 `@CircuitBreaker` 逐次调用地判断一个结果或异常是成功、失败，还是两者都不是。

异常列表（`retryOn`、`recordOn`）只能查看异常。但许多客户端会在结果中报告失败：HTTP 客户端返回状态为 `503` 的响应，作业 API 返回 `Status.FAILED`。分类器用一个类同时覆盖这两种情况。

## 编写分类器 :id=writing-one

分类器实现 `dev.resilience4cata.Classifier<T>`，其中 `T` 是方法的结果类型（或其 `CompletionStage<T>` 中的 `T`）：

<!-- snippet: classifiers-classifier -->
```java
import dev.resilience4cata.Classifier;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

public final class StatusClassifier implements Classifier<Response> {

    @Override
    public Outcome classify(@Nullable Response response, @Nullable Throwable failure) {
        if (failure != null) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
        }
        int status = response.status();
        if (status >= 500 || status == 429) {
            return Outcome.FAILURE;     // retried, and counted by the breaker
        }
        return status == 404 ? Outcome.IGNORE : Outcome.SUCCESS;
    }
}
```

`classify(result, failure)` 恰好有一个有意义的参数：调用正常返回时 `failure` 为 `null`（对于 `void` 方法或 `null` 结果，`result` 为 `null`）。它返回一个 `Outcome`：

| Outcome | `@Retry` | `@CircuitBreaker` |
|---|---|---|
| `SUCCESS` | 停止并返回结果（或重新抛出异常） | 记录为成功（如果慢，也记录为慢调用） |
| `FAILURE` | 若还有剩余尝试次数和时间，则重试 | 记录为失败（如果慢，也记录为慢调用） |
| `IGNORE` | 停止并返回结果（或重新抛出异常） | 完全不记录；半开探测会归还其许可 |

把异常分类为 `SUCCESS` 或 `IGNORE` 并不会吞掉它：异常仍会到达调用方，只是不被重试，熔断器把它计为成功或完全不计。

## 使用分类器 :id=using-it

<!-- snippet: classifiers-usage -->
```java
@Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class)
@CircuitBreaker(name = "orders", classifier = StatusClassifier.class)
Response get(String orderId) throws IOException;
```

- 类在注解中指定，由生成的代码创建：它必须是具体的、非泛型的、顶层的或 `static` 嵌套的、可访问的，具有无参构造函数，并实现 `Classifier<X>`，且方法的结果可赋值给 `X`。注解处理器在编译时检查所有这些（[`R4C-CLS-001`](../errors.md#r4c-cls-001) 至 [`R4C-CLS-010`](../errors.md#r4c-cls-010)）。
- 每个方法创建一个实例。当 `@Retry` 和 `@CircuitBreaker` 指定同一个类时，它们共享该实例。
- 分类器**取代** `retryOn`（在 `@Retry` 上）或 `recordOn`（在 `@CircuitBreaker` 上）；同时设置两者是编译错误（[`R4C-CLS-011`](../errors.md#r4c-cls-011)）。`abortOn` 和 `ignoreOn` 仍然适用，并优先于分类器。打开的熔断器的 `CallNotPermittedException` 总会停止重试，即使分类器对它返回 `FAILURE`。
- 它只能通过注解设置：没有对应的配置键。在 `Pipeline` 上，传入一个实例（见下文）。
- 异步方法以同样的方式分类，使用 stage 的值或其解包后的失败。

生成的代码如何使用它（节选）：

<!-- not-compiled: 生成的代码，节选 -->
```java
public final class OrdersApi$Resilient implements OrdersApi {
    // …
    private final StatusClassifier retryClassifier0;   // one instance per method, shared by both policies
    // …
    @Override
    public Response get(java.lang.String p0) throws java.io.IOException {
        final dev.resilience4cata.generated.RetryPolicy r4c$retry = this.retry0;
        final StatusClassifier r4c$classifier = this.retryClassifier0;
        final long r4c$start = r4c$retry.start();
        int r4c$attempts = 0;
        while (true) {
            final Response r4c$result;
            try {
                r4c$result = this.r4c$circuitBreaker0(p0);
            } catch (dev.resilience4cata.generated.ClassifierFailure r4c$w) {
                // the breaker's classifier threw: end the call with its exception, never retry it
                try {
                    throw r4c$w.failure();
                } catch (java.lang.Throwable r4c$c) {
                    r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);
                    throw r4c$c;
                }
            } catch (java.lang.Throwable r4c$e) {
                // … classify(null, r4c$e), then retry or rethrow
                continue;
            }
            final dev.resilience4cata.Classifier.Outcome r4c$verdict;
            try {
                r4c$verdict = r4c$classifier.classify(r4c$result, null);
            } catch (java.lang.Throwable r4c$c) {
                r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);
                throw r4c$c;
            }
            if (r4c$verdict != dev.resilience4cata.Classifier.Outcome.FAILURE) {
                return r4c$result;
            }
            final long r4c$wait = r4c$retry.onFailedResult(++r4c$attempts, r4c$start);
            if (r4c$wait < 0 || !r4c$retry.pause(r4c$wait)) {
                return r4c$result; // rejected, but no retries left: the last result
            }
        }
    }
    // …
}
```

分类器被直接调用，没有内存分配，也不读取时钟。没有分类器的方法生成与以前相同的代码；对它们来说没有任何变化。

## 结果被拒绝而重试次数用尽时 :id=when-the-retries-run-out-on-a-rejected-result

如果最后一次尝试仍然返回 `FAILURE` 结果，就没有可以重新抛出的异常。由 `onExhaustedResult` 决定接下来的行为：

- `RETURN`（默认值）：把最后的结果返回给调用方，就像没有重试一样。`@Fallback` **不会**运行，因为没有抛出任何异常。
- `THROW`：重试抛出 `dev.resilience4cata.ResultRejectedException`，它会像其他任何失败一样交给 `@Fallback`：

<!-- snippet: classifiers-exhausted -->
```java
@Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class,
        onExhaustedResult = ExhaustedResult.THROW)
@Fallback(method = "unavailable")
Response submit(String orderId) throws IOException;

default Response unavailable(String orderId, ResultRejectedException rejected) {
    Response last = rejected.result(Response.class);   // the last rejected result
    return new Response(503, "gave up after " + rejected.attempts() + " attempts: " + last.body());
}
```

`ResultRejectedException` 携带 `id()`、`attempts()` 以及最后的结果，可通过 `result()`（一个 `Object`）或 `result(Class<R>)`（带类型）获取。`onExhaustedResult` 需要分类器（[`R4C-CLS-012`](../errors.md#r4c-cls-012)），在编译时确定，不属于重试的配置：任何配置键都无法改变它。`maxDuration` 照常适用：因预算在被拒绝的结果上耗尽而放弃时，行为相同。

## 抛出异常的分类器 :id=a-classifier-that-throws

分类器不应该抛出异常。如果抛出了，它的异常就成为调用的失败，同步和异步方法都一样：

- `@CircuitBreaker` 把调用记录为 `FAILURE`，然后抛出分类器的异常。
- `@Retry` 立即停止，发布原因为 `CLASSIFIER_FAILED` 的 `RetryEvent.GaveUp`（以分类器的异常作为 `failure`，以及到目前为止的尝试次数，包括这一次），并抛出该异常。
- 如果存在 `@Fallback`，它会收到该异常。

无论分类器在哪里运行，这都成立。当熔断器的分类器抛出异常时，外层的重试不会把该异常当作方法的失败来分类，也不会重试它，无论重试使用相同的分类器、另一个分类器还是没有分类器：熔断器把异常放在一个内部包装（`dev.resilience4cata.generated.ClassifierFailure`，调用方永远看不到）中交给重试，调用按上述方式结束。调用方或降级方法得到的是分类器的异常本身。尽管如此，请让分类器保持全函数：显式处理 `null` 结果和意外的异常类型。

## 用 `Pipeline` 实现同样的事 :id=same-thing-with-pipeline

<!-- snippet: classifiers-pipeline -->
```java
Pipeline<Response> get = Pipeline.builder(registry, "orders-pipeline", Response.class)
        .retry(r -> r.maxAttempts(3))
        .circuitBreaker()
        .classifier(new StatusClassifier())              // both policies
        .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
        .build();

Response response = get.execute(() -> orders.get("o-1"));
```

`classifier(...)` 同时为重试和熔断器设置分类器；`retryClassifier(...)` 和 `circuitBreakerClassifier(...)` 各设置一个。`onExhaustedResult(Pipeline.ExhaustedResult.THROW)` 需要重试分类器。没有对应的策略时，`build()` 会失败（[`R4C-RT-014`](../errors.md#r4c-rt-014) 至 [`R4C-RT-017`](../errors.md#r4c-rt-017)）。参见 [Pipeline API](pipeline.md)。

## 开销 :id=what-it-costs

> [!MEASURED]
> 在 AWS Graviton3（`c7g.xlarge`，JDK 21）上，经过带分类器的熔断器的成功调用耗时 2.4 ns，不分配内存，1 个线程和 4 个线程均如此；使用 `recordResult` 的 Resilience4j 2.4.0 在 1 个线程上耗时 102 ns、分配 24 B。重试加熔断器加分类器：2.7 ns、0 B，对比 148 ns 和 104 B。分类器为每个策略增加约 0.3 ns。[基准测试：MVP 完成](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)
