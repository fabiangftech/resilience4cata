# 工作原理

注解处理器在编译时把你的注解变成普通的 Java 代码；在运行时，`ResilienceRegistry` 为这些代码提供配置和状态。

## 处理器生成什么 :id=what-the-processor-generates

对于每个使用韧性注解的接口，处理器会在同一个包中写出一个名为 `<Interface>$Resilient` 的类。它实现该接口，为每个受保护的方法持有一个 `final` 策略字段，并以直线代码的形式经由各策略调用你的实现：重试是一个循环，熔断器是一个 `try` 块，调用路径上没有 lambda、没有代理、没有反射。

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
public final class StockClient$Resilient implements StockClient {

    private final StockClient delegate;
    private final RetryPolicy retry0;
    private final CircuitBreakerPolicy circuitBreaker0;

    public StockClient$Resilient(StockClient delegate, ResilienceRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        // …
        Abi.require(2, StockClient$Resilient.class); // fails at once on a core older than the processor
        this.retry0 = Policies.retry(registry, "….StockClient#stock(java.lang.String,int)", "", "", r4c$b -> {
            r4c$b.maxAttempts(3);
        }, false);
        this.circuitBreaker0 = Policies.circuitBreaker(registry, "….StockClient#stock(java.lang.String,int)", "", "", null, false);
    }

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
}
```
<!-- compare:end -->

这种设计带来的结果：

- **错误是编译错误。** 未知属性、无效时长、签名不对的降级方法、标注在 `static` 或 `private` 方法上的注解：构建会以 `[R4C-ATR-001]` 这样的错误码失败，每个错误码在[错误参考](../errors.md)中都有自己的一节。
- **处理器是必需的。** 如果生成的类缺失，`Resilient.of` 会立即以 [`R4C-RT-003`](../errors.md#r4c-rt-003) 失败，并指出需要添加的依赖。不存在基于反射的后备方案。
- **生成的代码只能调用 `core` 的 API**，因此在同一主版本内，`core` 的版本必须不低于编译该代码的 `processor`：处理器 `1.a` 生成的代码可以在任何 `b >= a` 的 core `1.b` 上运行。在更旧的 core 上，生成的构造函数会立即以 [`R4C-RT-021`](../errors.md#r4c-rt-021) 失败；BOM 会让版本保持一致。
- **包装对象是线程安全的。** 像注册表一样，创建一次并共享它。

> [!MEASURED]
> 第一次尝试即成功的重试耗时 1.4 ns，不分配任何内存，而 Resilience4j 2.4.0 为 22.9 ns（AWS Graviton3 `c7g.xlarge`，单线程，1.0.0-RC1 的代码）。[基准测试：候选版本](../benchmarks.md#release-candidate)

## 两种风格，外加一个编程式 API :id=two-styles-plus-a-programmatic-api

| 风格 | 你编写 | 获取方式 | 自调用（`this.other()`） |
|---|---|---|---|
| **带注解的接口** | 接口上的注解；任意实现 | `Resilient.of(Type.class, impl, registry)` | 不会被拦截：实现对自身的调用会跳过策略 |
| **[带注解的类](../guides/annotated-classes.md)** | 非 final 类上的注解 | `Resilient.create(Type.class, registry, constructorArgs...)` | 会被拦截：生成的子类重写了该方法 |
| **[`Pipeline`](../guides/pipeline.md)** | 无注解：一个 builder | `Pipeline.builder(registry, id, Type.class)…build()` | 不适用 |

在接口风格下，只有接口上的注解才有效。一个实现了带注解接口、但自身没有声明任何注解的类不会被处理；用 `Resilient.of` 包装它。自身声明了注解的类会按类风格处理。

## 注册表与实例 id :id=the-registry-and-instance-ids

`ResilienceRegistry` 保存各项配置（默认值、命名配置、按实例的覆盖）、事件监听器以及所有有状态的策略实例。用 `ResilienceRegistry.builder()…build()` 构建一次；之后它是不可变的。

每个受保护的方法都有自己的策略实例，以一个**实例 id** 标识：

- 如果策略有 `name` 属性，就用它（`@CircuitBreaker(name = "prices")` → `prices`）；使用同一名称的方法、pipeline 和包装对象共享同一个实例（同一个熔断器窗口、同一组许可），并且必须解析为相同的配置（否则报 [`R4C-REG-008`](../errors.md#r4c-reg-008)）；
- 否则使用派生 id `<qualified type>#<method>(<parameter types>)`，参数类型为全限定、已擦除的类型，以逗号分隔且不带空格：`com.acme.StockClient#stock(java.lang.String,int)`。嵌套类型使用 `.`（`com.acme.Repository.Cache#get(java.lang.String)`）。对于带注解的类，类型就是该类。

`@Timeout` 没有 `name`：它的 id 总是派生 id。`@Fallback` 不保存状态，也没有 handle 或配置。

运行时通过 id 找到实例（`registry.circuitBreaker(id)`、`rateLimiter(id)`、`bulkhead(id)`），事件和快照用它来命名实例，外部配置也用它来重新调整实例（`instances.<id>.…`）。下面这个测试包装了 `StockClient`，并通过派生 id 查找它的熔断器：

<!-- snippet: how-it-works-ids -->
```java
AtomicInteger calls = new AtomicInteger();
StockClient stock = Resilient.of(StockClient.class, (sku, warehouse) -> {
    if (calls.incrementAndGet() == 1) {
        throw new IllegalStateException("warehouse busy");
    }
    return 7;
}, registry);

int count = stock.stock("sku-1", 3);   // the first attempt fails, the retry succeeds

CircuitBreakerSnapshot breaker = registry.circuitBreaker(
        "dev.resilience4cata.examples.docs.howitworks.StockClient#stock(java.lang.String,int)").snapshot();
// breaker.bufferedCalls() == 2 and breaker.failedCalls() == 1: the breaker saw both attempts
```

## 配置从哪里来 :id=where-configuration-comes-from

每个注解属性都有一个“未设置”值，因此注解只覆盖它显式写出的内容；真正的默认值保存在注册表中。值按以下顺序解析，优先级从低到高：

1. 内置默认值（对于 `@Retry`：3 次尝试，200 ms 带完全抖动的指数退避；每个[策略页面](../policies/README.md)都列出了它们）。
2. 注册表默认值：`retryDefaults(...)` 等方法，或[外部配置](../guides/configuration.md)中的 `defaults.<policy>.*`。
3. 用 `config = "…"` 选择的命名配置：在代码中用 `retry("external", ...)` 注册，在外部用 `configs.external.*` 注册。
4. 注解：bundle 低于直接注解，类型低于方法（[bundle](../guides/bundles.md)）。
5. 按实例的覆盖：`retryInstance(id, ...)` 等方法，或外部的 `instances.<id>.*`。

一切都只在创建包装对象、子类或 pipeline 时解析**一次**，生成不可变的记录；调用从不查找配置。不支持热重载：更改某个值意味着构建一个新的注册表，实际上就是重启应用。

<!-- snippet: how-it-works-registry -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryDefaults(r -> r.delay(Duration.ofMillis(50)))                     // every retry
        .circuitBreakerInstance(
                "dev.resilience4cata.examples.docs.howitworks.StockClient#stock(java.lang.String,int)",
                cb -> cb.minimumCalls(20))                                     // one instance
        .build();
StockClient stock = Resilient.of(StockClient.class, (sku, warehouse) -> 7, registry);   // resolved here
```

未知的配置名会在那一刻就失败（[`R4C-REG-004`](../errors.md#r4c-reg-004)），而不是在第一次调用时。

## 策略顺序 :id=policy-order

无论你以什么顺序书写注解，策略默认都按以下顺序嵌套，从最外层开始（[`@PolicyOrder`](../policies/README.md#changing-the-order) 可以改变它）：

```mermaid
flowchart LR
    caller([调用方]) --> F[Fallback] --> R[Retry | Hedge] --> CB[CircuitBreaker] --> RL[RateLimiter] --> AL[AdaptiveLimit] --> T[Timeout] --> B[Bulkhead] --> M([你的方法])
```

- **降级在最外层**，因此它看到的是最终结果，包括熔断器打开或舱壁已满之类的拒绝。
- **每次重试尝试都经过熔断器**，熔断器会记录它（上面的例子显示，一次被重试的调用记录了两次调用），而打开的熔断器会停止重试：`@Retry` 从不重试 `CallNotPermittedException`。
- **被熔断器拒绝的调用不会消耗限流器的许可。**
- **超时作用于每一次尝试**；`@Retry(maxDuration = …)` 限制整个操作。
- **舱壁隔离的槽位只在方法运行期间占用**，重试的退避期间不占用。

上面的顺序是默认顺序；`@PolicyOrder` 可以按方法、类型或 bundle 改变它（降级始终在最外层）。[策略概览](../policies/README.md)解释了各策略如何相互作用。
