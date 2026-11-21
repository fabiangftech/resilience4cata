# 快速入门

用大约五分钟，为一个不稳定的调用加上重试 (retry)、熔断器 (circuit breaker)、超时 (timeout) 和降级 (fallback)，并观察它们如何工作。

你需要 Java 21 或更高版本，以及一个 Gradle 或 Maven 项目。下面的内容是一个可运行的程序；其代码由项目的测试编译并运行，因此与所示一致。

## 1. 添加依赖 :id=_1-add-the-dependencies

`resilience4cata-all` 提供了库本身（core、注解和外部配置）；处理器在编译时生成代码，只放在注解处理器路径上。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC4'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC4'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.0.0-RC4")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.0.0-RC4")
}
```

#### **Maven**

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

[安装页面](installation.md)解释了每一行、BOM 和各个独立模块、IDE 以及 JPMS。

## 2. 为接口添加注解 :id=_2-annotate-an-interface

为你所调用的依赖的接口添加注解。下面这个接口最多重试三次，由名为 `prices` 的熔断器保护，两秒后被中断，并且在其他一切都失败时由 `unknownPrice` 给出结果：

<!-- snippet: quickstart-interface -->
```java
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.math.BigDecimal;

public interface PriceClient {

    @Retry(maxAttempts = 3, delay = "100ms")
    @CircuitBreaker(name = "prices")
    @Timeout("2s")
    @Fallback(method = "unknownPrice")
    BigDecimal price(String sku) throws IOException;

    default BigDecimal unknownPrice(String sku, Exception failure) {
        return BigDecimal.ZERO;
    }
}
```

## 3. 实现它 :id=_3-implement-it

任何实现都可以：手写的 HTTP 客户端、生成的客户端或一个 lambda。下面这个替身在第一次调用时失败：

<!-- snippet: quickstart-implementation -->
```java
import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for a real HTTP client: the first call fails, the next ones answer. */
public class FlakyPriceClient implements PriceClient {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public BigDecimal price(String sku) throws IOException {
        if (calls.incrementAndGet() == 1) {
            throw new IOException("connection reset");
        }
        return new BigDecimal("9.99");
    }
}
```

## 4. 包装并调用 :id=_4-wrap-it-and-call-it

`Resilient.of` 把策略套在你的实现外面。`ResilienceRegistry` 只构建一次并共享它：它保存着各项配置以及每个熔断器的状态。

<!-- snippet: quickstart-main -->
```java
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();

        PriceClient prices = Resilient.of(PriceClient.class, new FlakyPriceClient(), registry);
        System.out.println(prices.price("sku-1"));   // 9.99: the first attempt failed, the retry succeeded

        PriceClient down = Resilient.of(PriceClient.class, sku -> {
            throw new IOException("service down");
        }, registry);
        System.out.println(down.price("sku-1"));     // 0: all three attempts failed, the fallback answered

        System.out.println(registry.circuitBreaker("prices").state());   // CLOSED: too few calls to judge yet
    }
}
```

## 5. 运行 :id=_5-run-it

编译并运行 `Main`（在 IDE 中，或使用构建工具的 run 任务）。它会打印：

```text
9.99
0
CLOSED
```

第二次调用最多需要几百毫秒：在两次尝试之间，重试会等待一段低于 100 ms 的随机时间，然后是低于 200 ms 的随机时间（带完全抖动的指数退避）。

## 刚才发生了什么 :id=what-just-happened

- **在编译时**，注解处理器读取了 `PriceClient`，并在它旁边生成了 `PriceClient$Resilient.java`（Gradle 下位于 `build/generated/sources/annotationProcessor/java/main/`，Maven 下位于 `target/generated-sources/annotations/`）。它是普通的 Java 代码：打开它读一读。属性拼写错误、错误的时长或签名不对的降级方法，都会是编译错误。
- **`Resilient.of`** 只加载该类一次，并从注册表创建策略。调用直接经过生成的代码，没有代理，也没有反射。
- **第一次调用**以 `IOException` 失败，于是重试等待后再试一次；第二次尝试返回了 `9.99`。
- **第二个包装对象**总是失败，所以在三次尝试之后，降级方法 `unknownPrice` 返回了 `0`。
- **熔断器**记录了每一次尝试，共五次调用、四次失败，但仍保持 `CLOSED`：默认情况下，只有当窗口中积累了 100 次调用 (`minimumCalls`) 时它才会做出判断。
- 默认情况下，策略按以下顺序嵌套，从最外层开始（[`@PolicyOrder`](../policies/README.md#changing-the-order) 可以改变它）：`Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method`。这就是为什么降级看到的是最终结果，而每次重试尝试都有自己的两秒超时。

## 6. 观察熔断器打开 :id=_6-see-the-breaker-open

使用默认值时，熔断器要等到 100 次调用后才做判断。只针对 `prices` 实例降低这个值，不改动注解，然后再次运行失败的服务：

<!-- snippet: quickstart-breaker-opens -->
```java
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class BreakerOpens {

    public static void main(String[] args) throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .circuitBreakerInstance("prices", b -> b.windowSize(4).minimumCalls(4))   // judge after 4 calls
                .build();

        AtomicInteger calls = new AtomicInteger();
        PriceClient down = Resilient.of(PriceClient.class, sku -> {
            calls.incrementAndGet();
            throw new IOException("service down");
        }, registry);

        down.price("sku-1");   // three attempts, three failures
        down.price("sku-2");   // the fourth failure opens the breaker, which stops the retry
        System.out.println(registry.circuitBreaker("prices").state());   // OPEN

        System.out.println(down.price("sku-3"));   // 0: rejected at once, answered by the fallback
        System.out.println(calls.get());           // 4: the service was not called again
    }
}
```

它会打印：

```text
OPEN
0
4
```

- 第一次调用失败了三次。在第二次调用中，第四次失败填满了大小为 4 的窗口，失败率为 100%，高于默认的 50%，于是熔断器打开。重试等待了它的退避时间，下一次尝试被拒绝；由于重试从不重试拒绝，它放弃了，由降级给出结果。
- 第三次调用立即被拒绝，抛出 `CallNotPermittedException`，没有到达服务，降级再次返回 `0`。经过 `waitInOpen`（默认 60 s）之后，熔断器会放行少量探测调用。
- `circuitBreakerInstance` 是在不修改注解的情况下更改实例的一种方式；属性文件或环境变量也能做到同样的事（[配置](../guides/configuration.md)）。

## 下一步 :id=next-steps

- [HTTP 调用的重试与熔断器](../guides/http-calls.md)：针对真实 HTTP 依赖调优的同一组策略。
- [工作原理](how-it-works.md)：处理器生成了什么、实例 id、配置从哪里来。
- [策略](../policies/README.md)：每种策略的全部属性及其默认值。
- [配置](../guides/configuration.md)：通过属性文件、环境变量或系统属性重新调整同一份代码。
- [测试](../guides/testing.md)：在 `ManualClock` 上运行这个示例，无需真正等待。
- [异步方法](../guides/async.md)：返回 `CompletableFuture`，没有任何阻塞。
