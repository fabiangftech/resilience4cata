# 错误参考

resilience4cata 报告的每个错误都以方括号中的稳定错误码开头，例如：

```text
error: [R4C-FB-002] @Fallback(method = "findFallbak") on find: no method named findFallbak in UserClient. Did you mean findFallback? Expected a default or static method java.lang.String findFallbak(java.lang.String, <a Throwable type> e), where the exception parameter is optional.
```

错误码告诉你本页哪一节解释了该错误。消息的其余部分说明出错的位置（编译器会指向带注解的元素；文本中会给出类、方法和属性的名称）、出了什么问题以及如何修复。

本页很长：可以搜索错误码（站内搜索或浏览器的页内查找），或者从下表中的领域开始。每个错误码都有自己的一节，因此像 `errors.md#r4c-atr-001` 这样的链接会直接打开它。

| 前缀 | 报告者 | 时机 |
|---|---|---|
| `R4C-APT` | 注解处理器 | 注解的放置位置，一般性问题 |
| `R4C-ATR` | 注解处理器 | 属性值 |
| `R4C-CMP` | 注解处理器 | 组合注解（`@ResiliencePolicy` bundle） |
| `R4C-ASY` | 注解处理器 | 异步方法 |
| `R4C-HDG` | 注解处理器 | `@Hedge` |
| `R4C-CLS` | 注解处理器 | `classifier` 类和 `onExhaustedResult` |
| `R4C-FB` | 注解处理器 | `@Fallback` |
| `R4C-SUB` | 注解处理器 | 带注解的类（生成的子类） |
| `R4C-ORD` | 注解处理器 | `@PolicyOrder` |
| `R4C-RT` | 运行时的 `resilience4cata-core` | `Resilient.of`、`Resilient.create`、`Pipeline.Builder` |
| `R4C-REG` | 运行时的 `resilience4cata-core` | `*Config` 构建器、`ResilienceRegistry` 及其构建器：命名配置、实例 |
| `R4C-CFG` | 运行时的 `resilience4cata-config` | 外部配置（`ResilienceConfig`） |
| `R4C-SPR` | 启动时的 `resilience4cata-spring` | Spring Boot 集成：无法按声明方式保护的 bean |
| `R4C-MN` | 启动时或创建 bean 时的 `resilience4cata-micronaut` | Micronaut 集成：无法按声明方式保护的 bean |
| `R4C-QK` | Quarkus 构建应用时的 `resilience4cata-quarkus` | Quarkus 扩展：无法按声明方式保护、或会被保护两次的 bean |
| `R4C-TK` | 测试中的 `resilience4cata-testkit` | `ManualClock`、`ResilienceTesting` |
| `R4C-JU` | 测试中的 `resilience4cata-junit` | JUnit 扩展（`@ResilienceTest`） |

错误码是稳定的：错误码从不重新编号，也不会被赋予新的含义。新错误使用其所属领域的下一个空闲编号；不再报告的错误保留其错误码，并在下文标记为已停用。

注解处理器各错误码的错误示例和修复示例都由构建编译（`processor` 模块中的 `ErrorCodeTest`）：每个错误示例都会报告其错误码，每个修复示例都能无警告地编译。

## 注解处理器：放置位置（R4C-APT） :id=annotation-processor-placement-r4c-apt

### R4C-APT-001

**触发条件：** 弹性注解放在枚举或 record 上，或其某个方法上。接口包装器风格需要一个接口，子类风格必须继承该类型，但枚举和 record 都是 final 的。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public record PriceClient(String baseUrl) {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**修复：** 在接口中声明这些方法，用 record 实现该接口，并用 `Resilient.of(PriceClient.class, implementation, registry)` 包装该实现。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    String price(String sku);
}
```

### R4C-APT-002

**触发条件：** 弹性注解放在 static 或 private 方法上。对这类方法的调用无法被包装器或子类拦截。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    static String price(String sku) {
        return "1";
    }
}
```

**修复：** 给非 private 的实例方法加注解（在接口中是抽象方法或 default 方法；在类中是可覆盖的方法）。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    String price(String sku);
}
```

### R4C-APT-003

**触发条件：** 带注解的接口是 private 的（类的 private 成员）。生成的类 `<Interface>$Resilient` 是一个顶层类，必须实现该接口。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Clients {
    private interface PriceClient {
        @Retry
        String price(String sku);
    }
}
```

**修复：** 把接口改为包私有或 public。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry
        String price(String sku);
    }
}
```

### R4C-APT-004

**触发条件：** 注解带有一个注解处理器不认识的属性，例如 `Unknown attribute @Retry.hedges`。当 `resilience4cata-annotations` 比 `resilience4cata-processor` 新时会出现这种情况。

**修复：** 让所有 resilience4cata 构件使用相同的版本，最好通过 BOM：

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:<version>')
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    implementation 'dev.resilience4cata:resilience4cata-core'
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:<version>')
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

### R4C-APT-005

**触发条件：** 注解处理器无法写出一个生成的源文件，或者它在旁边写出的原生镜像元数据文件（class 输出中的 `META-INF/native-image/dev.resilience4cata.generated/<Interface>$Resilient/reachability-metadata.json`）：编译器的 `Filer` 失败了，例如因为输出目录不可写，或者另一个注解处理器或某个源文件已经定义了 `<Interface>$Resilient`。

**修复：** 检查构建的生成源码目录（Gradle 下为 `build/generated/sources/annotationProcessor`），确保没有手写的类名为 `<Interface>$Resilient`，并在 `./gradlew clean` 之后重新构建。

### R4C-APT-006

**触发条件：** 编译器选项 `-Ar4c.inject` 的值不是 `micronaut`；或者它是 `-Ar4c.inject=micronaut`，但 Micronaut 的 `io.micronaut.context.annotation.Replaces` 不在编译 class path 上，因此带注解类的生成子类无法作为 Micronaut bean 替换该类。该选项每次编译读取一次；此错误没有源码位置。

**错误示例**（使用 `-Ar4c.inject=spring` 编译）：

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class InventoryService {
    @Retry
    public int stock(String sku) {
        return 1;
    }
}
```

**修复：** 在 Micronaut 应用中使用 `-Ar4c.inject=micronaut`，并确保 `io.micronaut:micronaut-inject` 在编译 class path 上（每个 Micronaut 应用都有）；对于任何其他容器都去掉该选项（Spring Boot 不需要它：参见 [Spring Boot 指南](guides/spring.md)）。参见 [Micronaut 指南](guides/micronaut.md#classes)。

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.add('-Ar4c.inject=micronaut')
}
```

### R4C-APT-007

**触发条件：** 某个类型在类型级带有弹性注解，但这些注解没有作用到任何方法，因此不会生成任何东西。例如一个既不声明也不继承任何抽象或 default 方法的接口（类型级注解不会作用到继承它的接口的方法），或者一个所有方法都是 final、static 或 private 的带注解类。没有这个错误的话，注解将不起任何作用，而 Spring Boot、Micronaut 和 Quarkus 集成会把该类型报告为未经注解处理器编译（[R4C-SPR-006](#r4c-spr-006)）。

**错误示例：**

```java
// com/acme/RemoteDefaults.java
package com.acme;

import dev.resilience4cata.annotation.Retry;

@Retry(maxAttempts = 3)
public interface RemoteDefaults {
}

// com/acme/PriceClient.java
package com.acme;

public interface PriceClient extends RemoteDefaults {
    String price(String sku);
}
```

**修复：** 把注解放在声明这些方法的接口上，或者通过在那里使用的 [`@ResiliencePolicy` bundle](guides/bundles.md) 共享它们。对于类，让需要保护的方法可被覆盖。

```java
// com/acme/PriceClient.java
package com.acme;

import dev.resilience4cata.annotation.Retry;

@Retry(maxAttempts = 3)
public interface PriceClient {
    String price(String sku);
}
```

## 注解处理器：属性值（R4C-ATR） :id=annotation-processor-attribute-values-r4c-atr

### R4C-ATR-001

**触发条件：** 某个时长属性（`delay`、`maxDelay`、`maxDuration`、`slowCallDuration`、`waitInOpen`、`maxWaitInHalfOpen`、`maxWait`、`period`、`budgetWindow`、`window`，或 `@Timeout` 的值）不是有效的时长。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(delay = "2 seconds")
    String price(String sku);
}
```

**修复：** 使用数字后跟 `ns`、`us`、`ms`、`s`、`m`、`h` 或 `d`（`"250ms"`、`"2s"`），ISO-8601 时长（`"PT2S"`），或 `"0"`。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(delay = "2s")
    String price(String sku);
}
```

### R4C-ATR-002

**触发条件：** 一个必须大于零的时长为 `"0"`：`@Timeout` 的值，或 `@RateLimiter.period`。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Timeout;

public interface PriceClient {
    @Timeout("0")
    String price(String sku);
}
```

**修复：** 设置一个正的时长。若要关闭该策略，请改用 `enabled = false`（或配置）。

```java
package com.acme;

import dev.resilience4cata.annotation.Timeout;

public interface PriceClient {
    @Timeout("2s")
    String price(String sku);
}
```

### R4C-ATR-003

**触发条件：** 某个计数小于 1：`maxAttempts`、`windowSize`、`minimumCalls`、`halfOpenCalls`、`maxConcurrent`、`permits`、`initialLimit`、`minLimit` 或 `maxLimit`。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(maxAttempts = 0)
    String price(String sku);
}
```

**修复：** 把它设为 1 或更大（`maxAttempts` 包含第一次调用），或者省略它以使用配置中的值。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(maxAttempts = 3)
    String price(String sku);
}
```

### R4C-ATR-004

**触发条件：** `@Retry.multiplier` 小于 1、为无穷大或 NaN。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(multiplier = 0.5)
    String price(String sku);
}
```

**修复：** 使用至少为 1 的有限数（1 表示延迟保持不变）。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(multiplier = 2.0)
    String price(String sku);
}
```

### R4C-ATR-005

**触发条件：** `@CircuitBreaker.failureRateThreshold` 或 `slowCallRateThreshold` 不是大于 0 且不超过 100 的百分比。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;

public interface PriceClient {
    @CircuitBreaker(failureRateThreshold = 150)
    String price(String sku);
}
```

**修复：** 使用 (0, 100] 范围内的值。

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;

public interface PriceClient {
    @CircuitBreaker(failureRateThreshold = 50)
    String price(String sku);
}
```

### R4C-ATR-006

**触发条件：** 已停用；不再报告。`enabled` 曾经是字符串（`""`、`"true"` 或 `"false"`）；从 0.12.0 起它是 `boolean`（`enabled = false`），由编译器检查。

### R4C-ATR-007

**触发条件：** 重试或对冲请求的预算属性超出范围：`budgetPercent` 为负数、无穷大或 NaN，`budgetMinRetriesPerSecond`（`@Retry`）或 `budgetMinHedgesPerSecond`（`@Hedge`）为负数，或者 `budgetWindow` 短于 1 s 或长于 1 h。（根本不是时长的 `budgetWindow` 属于 [R4C-ATR-001](#r4c-atr-001)。）

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(budgetPercent = 20, budgetWindow = "500ms")
    String price(String sku);
}
```

**修复：** 使用 0 或更大的百分比、每秒 0 次或更多的重试保留量，以及介于 `"1s"` 和 `"1h"` 之间的窗口；或者省略该属性以继承它（`budgetWindow` 默认为 `10s`）。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(budgetPercent = 20, budgetMinRetriesPerSecond = 1, budgetWindow = "10s")
    String price(String sku);
}
```

### R4C-ATR-008

**触发条件：** `@Hedge(maxAttempts = …)` 小于 2 或大于 10。`maxAttempts` 统计每一次尝试，包括第一次，因此 1 将永远不会发起对冲；一次调用同时在途超过 10 次尝试会被拒绝。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(maxAttempts = 1, delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

**修复：** 使用 2（一次对冲，默认值）到 10 之间的值，或者如果该调用不应被对冲，就去掉 `@Hedge`。

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(maxAttempts = 2, delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

### R4C-ATR-009

**触发条件：** `@AdaptiveLimit` 的边界顺序不正确（`minLimit` 大于 `maxLimit`，或 `initialLimit` 在两者之外），或者其 `window` 短于 10 ms 或长于 1 h。只比较注解上设置的边界；来自命名配置或外部配置的边界在注册表解析它时检查（[R4C-REG-003](#r4c-reg-003)）。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.AdaptiveLimit;

public interface OrderClient {
    @AdaptiveLimit(minLimit = 50, maxLimit = 20)
    String place(String order);
}
```

**修复：** 保持 `minLimit <= initialLimit <= maxLimit`，并让 `window` 在 `"10ms"` 到 `"1h"` 之间。

```java
package com.acme;

import dev.resilience4cata.annotation.AdaptiveLimit;

public interface OrderClient {
    @AdaptiveLimit(minLimit = 20, maxLimit = 50)
    String place(String order);
}
```

### R4C-ATR-010

**触发条件：** 一个 `@CircuitBreaker` 的窗口属性与其窗口类型不符：`windowSize`（调用次数）与 `windowType = TIME` 一起使用，`windowDuration` 与 `windowType = COUNT` 一起使用，两者出现在同一个注解上，或者 `windowDuration` 不是至少 `1s` 的整数秒。配置和构建器可以同时设置两者，因为默认值同时服务于两种窗口类型：每种窗口都会忽略另一个属性。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.WindowType;

public interface LedgerClient {
    @CircuitBreaker(windowType = WindowType.TIME, windowSize = 60)
    long balance(String account);
}
```

**修复：** 用 `windowDuration` 给时间窗口设置长度，用 `windowSize` 给计数窗口设置调用次数。

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.WindowType;

public interface LedgerClient {
    @CircuitBreaker(windowType = WindowType.TIME, windowDuration = "60s")
    long balance(String account);
}
```

## 注解处理器：组合注解（R4C-CMP） :id=annotation-processor-composed-annotations-r4c-cmp

### R4C-CMP-001

**触发条件：** 某个注解类型带有弹性注解，但没有标注 `@ResiliencePolicy`，因此无论在哪里使用它，注解处理器都会忽略其策略。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Patient {
}
```

**修复：** 添加 `@ResiliencePolicy`。

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Patient {
}
```

### R4C-CMP-002

**触发条件：** 同一元素上的两个组合注解把同一属性设为不同的值，因此哪一个都无法胜出。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public interface PriceClient {
    @Fast
    @Patient
    String price(String sku);
}

@ResiliencePolicy
@Retry(maxAttempts = 2)
@Retention(RetentionPolicy.CLASS)
@interface Fast {
}

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@interface Patient {
}
```

**修复：** 只保留其中一个 bundle，或让它们一致，或者直接在元素上设置该属性，直接设置的属性优先于所有 bundle。

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public interface PriceClient {
    @Patient
    String price(String sku);
}

@ResiliencePolicy
@Retry(maxAttempts = 2)
@Retention(RetentionPolicy.CLASS)
@interface Fast {
}

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@interface Patient {
}
```

## 注解处理器：异步方法（R4C-ASY） :id=annotation-processor-asynchronous-methods-r4c-asy

### R4C-ASY-001

**触发条件：** 带注解的方法返回不受支持的 `CompletionStage` 类型：原始类型、`? super X` 类型实参，或者 `CompletableFuture` 以外的 `CompletionStage` 子类型。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<? super Integer> price(String sku);
}
```

**修复：** 返回 `CompletionStage<T>` 或 `CompletableFuture<T>`，其中 `T` 也可以是 `?` 或 `? extends X`。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<Integer> price(String sku);
}
```

### R4C-ASY-002

**触发条件：** 带注解的方法返回惰性响应式类型：`Flow.Publisher` 或 Reactive Streams `Publisher`（RxJava 的 `Flowable`、Reactor `Flux` 的子类）、Mutiny 的 `Uni` 或 `Multi`，或者 RxJava 的 `Single`、`Maybe`、`Completable` 或 `Observable`。这样的方法只构建一个稍后在被订阅时才运行的管道，因此策略保护的是构建过程而不是实际工作：失败永远不会被重试或超时。Reactor 的 `Mono` 和 `Flux` 在使用 `resilience4cata-reactor` 时受支持（参见 [R4C-ASY-003](#r4c-asy-003)）。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.Flow;

public interface PriceClient {
    @Retry
    Flow.Publisher<Integer> price(String sku);
}
```

**修复：** 返回 `CompletionStage<T>` 或 `CompletableFuture<T>`，在实现中进行转换（`uni.subscribeAsCompletionStage()`），或者配合 `resilience4cata-reactor` 返回 Reactor 的 `Mono<T>` 或 `Flux<T>`，或者保留响应式类型并使用响应式库自己的重试和超时操作符。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<Integer> price(String sku);
}
```

### R4C-ASY-003

**触发条件：** 带注解的方法返回 Reactor 的 `Mono` 或 `Flux`，而 `resilience4cata-reactor` 不在编译 class path 上（对于 `Flux`，需要 0.11.0 或更新版本）。这些方法的生成代码会调用该模块。

**错误示例：** 有 `reactor-core` 但没有 `resilience4cata-reactor`：

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Mono;

public interface PriceClient {
    @Retry
    Mono<Integer> price(String sku);
}
```

**修复：** 把 `dev.resilience4cata:resilience4cata-reactor`（与其他模块版本相同）添加到依赖中，放在 `resilience4cata-core` 旁边。代码保持不变。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Mono;

public interface PriceClient {
    @Retry
    Mono<Integer> price(String sku);
}
```

### R4C-ASY-004

**触发条件：** 一个返回 Reactor `Flux` 的带注解方法带有 `@Hedge`、在 `@Retry` 或 `@CircuitBreaker` 上设置了分类器，或者带有改变其顺序的 `@PolicyOrder`。对冲请求会合并两个流的元素；分类器判断的是一个结果，而一个流有许多个；并且“重试和降级只在流的第一个元素之前起作用”这一规则是针对默认顺序定义的。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import reactor.core.publisher.Flux;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    Flux<Integer> quotes(String sku);
}
```

**修复：** 使用 `@Retry`（用 `retryOn`/`abortOn`，或在熔断器上用 `recordOn`/`ignoreOn`，代替分类器），保持默认顺序，或者在需要对冲请求或分类器的地方返回 `Mono` 或 `CompletionStage`。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Flux;

public interface QuoteClient {
    @Retry
    Flux<Integer> quotes(String sku);
}
```

## 注解处理器：对冲请求（R4C-HDG） :id=annotation-processor-hedges-r4c-hdg

### R4C-HDG-001

**触发条件：** `@Hedge` 作用于同步方法：即不返回 `CompletionStage<T>` 或 `CompletableFuture<T>` 的方法。对冲请求会在第一次尝试仍在运行时再次发起调用，这需要异步调用。类型上或 `@ResiliencePolicy` bundle 中的 `@Hedge` 作用于每个方法，因此该类型的每个同步方法都会被报告。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    String quote(String sku);
}
```

**修复：** 返回 `CompletableFuture`（或 `CompletionStage`），或者从该方法上去掉 `@Hedge`。对于类型上的 `@Hedge`，改为给异步方法而不是类型加注解。

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

### R4C-HDG-002

**触发条件：** `@Hedge` 和 `@Retry` 作用于同一个方法，无论是直接、在类型上，还是通过 `@ResiliencePolicy` bundle。它们在策略顺序中占据相同的位置，而包在对冲请求外面的重试会让尝试次数成倍增加。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    @Retry(maxAttempts = 3)
    CompletableFuture<String> quote(String sku);
}
```

**修复：** 只保留一个。当某次尝试以 `hedgeOn` 中的失败结束时，对冲请求已经会立即发起新的尝试，因此对冲上的 `maxAttempts` 已涵盖简单重试的功能。

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms", maxAttempts = 3)
    CompletableFuture<String> quote(String sku);
}
```

## 注解处理器：策略顺序（R4C-ORD） :id=annotation-processor-policy-order-r4c-ord

### R4C-ORD-001

**触发条件：** `@PolicyOrder` 多次列出同一层（在方法上、其类型上或某个 bundle 中）。每一层在顺序中只有一个位置。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportClient {
    @Retry
    @Timeout("2s")
    @PolicyOrder({Layer.TIMEOUT, Layer.RETRY, Layer.TIMEOUT})
    String report(String id);
}
```

**修复：** 每一层只列一次，最外层在前。

```java
package com.acme;

import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportClient {
    @Retry
    @Timeout("2s")
    @PolicyOrder({Layer.TIMEOUT, Layer.RETRY})
    String report(String id);
}
```

## 注解处理器：分类器（R4C-CLS） :id=annotation-processor-classifiers-r4c-cls

生成的类会为每个方法创建每个 `classifier` 类的一个实例，因此该类必须是具体的、非泛型的类，具有可访问的无参构造函数，并针对方法的结果类型实现 `Classifier<T>`。

### R4C-CLS-001

**触发条件：** 设置了 `classifier`，但 `dev.resilience4cata.Classifier` 不在编译 classpath 上。**错误示例：** 在 classpath 上没有 `resilience4cata-core` 的情况下编译以下代码：

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**修复：** 添加 core 依赖：

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

### R4C-CLS-002

**触发条件：** `classifier` 指定的是接口、注解或枚举，而不是类。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Classifier.class)
    String price(String sku);
}
```

**修复：** 指定一个实现了 `Classifier<T>` 的类。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-003

**触发条件：** 分类器类是抽象的，因此无法被实例化。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    abstract class Retryable implements Classifier<String> {
    }
}
```

**修复：** 使用具体类。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-004

**触发条件：** 分类器类声明了类型参数；生成的代码只能用一个未检查的原始类型来创建它。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable<T> implements Classifier<T> {
        @Override
        public Outcome classify(T result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**修复：** 用一个具体的 `T`（或其父类型，例如 `Classifier<Object>`）实现 `Classifier<T>`。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<Object> {
        @Override
        public Outcome classify(Object result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-005

**触发条件：** 分类器类（或包含它的某个类）是 private 的，或者是另一个包中的包私有类，因此位于接口所在包中的生成类无法创建它。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    private static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**修复：** 把它改为 public，或者放在接口所在的包中并设为包私有。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-006

**触发条件：** 分类器是内部类（非 static 的嵌套类），需要一个外部类的实例。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**修复：** 把它改为 `static`。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-007

**触发条件：** 分类器没有生成的类可以调用的无参构造函数。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        private Retryable() {
        }

        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**修复：** 添加一个 public（或在接口所在包中为包私有）的无参构造函数，或者删除 private 的那个。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-008

**触发条件：** 分类器类没有实现 `dev.resilience4cata.Classifier`。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = String.class)
    String price(String sku);
}
```

**修复：** 指定一个实现了 `Classifier<T>` 的类。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-009

**触发条件：** 分类器实现的是原始类型 `Classifier`，因此无法检查其结果类型。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier {
        @Override
        public Outcome classify(Object result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**修复：** 按照消息的建议，用方法的结果类型实现 `Classifier<T>`。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-010

**触发条件：** 分类器实现了 `Classifier<X>`，但方法的结果（对于异步方法，是 stage 的值）不能赋值给 `X`。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<Integer> {
        @Override
        public Outcome classify(Integer result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**修复：** 针对方法的结果类型或其父类型实现 `Classifier`。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-011

**触发条件：** `@Retry` 同时设置了 `classifier` 和 `retryOn`，或者 `@CircuitBreaker` 同时设置了 `classifier` 和 `recordOn`。分类器决定哪些异常算作失败，因此该列表会被忽略。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

public interface PriceClient {
    @Retry(classifier = Retryable.class, retryOn = IOException.class)
    String price(String sku) throws IOException;

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**修复：** 去掉 `retryOn`（或 `recordOn`），并在分类器中对这些异常返回 `Outcome.FAILURE`。`abortOn` 和 `ignoreOn` 仍然在分类器之前生效。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku) throws IOException;

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
```

### R4C-CLS-012

**触发条件：** `@Retry` 设置了 `onExhaustedResult` 但没有 `classifier`。只有分类器才能拒绝结果，因此该设置永远不会生效。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(onExhaustedResult = ExhaustedResult.THROW)
    String price(String sku);
}
```

**修复：** 添加一个拒绝需要重试的结果的分类器，或者去掉 `onExhaustedResult`。

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = NotEmpty.class, onExhaustedResult = ExhaustedResult.THROW)
    String price(String sku);

    final class NotEmpty implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure != null || result == null || result.isEmpty() ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
```

## 注解处理器：降级（R4C-FB） :id=annotation-processor-fallbacks-r4c-fb

`@Fallback` 要么指定一个处理器类 `@Fallback(MyHandler.class)`，要么指定同级方法 `@Fallback(method = "name")`。处理器类必须满足与分类器相同的规则：具体的、非泛型的、可访问的，有无参构造函数，并针对方法的结果类型实现 `FallbackHandler<T>`。

### R4C-FB-001

**触发条件：** `@Fallback` 既没有设置处理器类也没有设置方法名，或者两者都设置了。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback
    String find(String id);
}
```

**修复：** 只设置其中一个。

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-002

**触发条件：** `@Fallback(method = "...")` 指定了一个在接口（或类）中不存在的方法。消息会给出最接近的已有名称。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallbak")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

**修复：** 改正名称，或者添加该方法。它接受与带注解方法相同的参数，后面可以再跟一个异常参数，并返回相同的类型。

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-003

**触发条件：** 存在与降级同名的方法，但都无法使用。消息会列出每个候选方法及其被拒绝的原因：private（生成的类无法调用它）、返回类型错误、带注解方法没有声明的受检异常、类型参数不同，或者异常参数不是类。参数不同的候选方法不会被列出。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    private String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

**修复：** 在接口中使用 default 或 static 方法；在类中使用非 private 方法。与消息中显示的签名保持一致。

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-004

**触发条件：** `@Fallback` 指定了一个处理器类，但 `dev.resilience4cata.FallbackHandler` 不在编译 classpath 上。

**错误示例：** 在 classpath 上没有 `resilience4cata-core` 的情况下编译以下代码：

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**修复：** 添加 core 依赖：

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

### R4C-FB-005

**触发条件：** `@Fallback` 指定的处理器是接口、注解或枚举，而不是类。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(FallbackHandler.class)
    String find(String id);
}
```

**修复：** 指定一个实现了 `FallbackHandler<T>` 的类。

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-006

**触发条件：** 处理器类是抽象的。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    abstract class Unknown implements FallbackHandler<String> {
    }
}
```

**修复：** 使用具体类。

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-007

**触发条件：** 处理器类声明了类型参数。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown<T> implements FallbackHandler<T> {
        @Override
        public T apply(FallbackContext context) {
            return null;
        }
    }
}
```

**修复：** 用一个具体的 `T` 实现 `FallbackHandler<T>`。对于泛型方法，改用同级方法（参见 [R4C-FB-014](#r4c-fb-014)）。

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-008

**触发条件：** 处理器类（或包含它的某个类）是 private 的，或者是另一个包中的包私有类，因此生成的类无法创建它。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    private static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**修复：** 把它改为 public，或者放在接口所在的包中并设为包私有。

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-009

**触发条件：** 处理器是内部类（非 static 的嵌套类）。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**修复：** 把它改为 `static`。

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-010

**触发条件：** 处理器没有生成的类可以调用的无参构造函数。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        private Unknown() {
        }

        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**修复：** 添加一个 public（或在接口所在包中为包私有）的无参构造函数，或者删除 private 的那个。

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-011

**触发条件：** 处理器类没有实现 `dev.resilience4cata.FallbackHandler`。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(String.class)
    String find(String id);
}
```

**修复：** 指定一个实现了 `FallbackHandler<T>` 的类。

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-012

**触发条件：** 处理器实现的是原始类型 `FallbackHandler`。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler {
        @Override
        public Object apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**修复：** 按照消息的建议，用方法的结果类型实现 `FallbackHandler<T>`。

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-013

**触发条件：** 处理器实现了 `FallbackHandler<X>`，但 `X` 不能赋值给方法的返回类型。对于返回 `T` 的 stage 的异步方法，`X` 可以是 `T` 或 `CompletionStage<T>`。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<Integer> {
        @Override
        public Integer apply(FallbackContext context) {
            return 0;
        }
    }
}
```

**修复：** 针对方法的返回类型实现 `FallbackHandler`（对于基本类型，使用其装箱类型）。

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-014

**触发条件：** 在返回类型依赖于方法类型参数的泛型方法上使用了处理器类。处理器针对某一个 `X` 实现 `FallbackHandler<X>`，在没有未检查转换的情况下，它无法适配所有的实例化。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface Repository {
    @Fallback(Nothing.class)
    <T> T load(Class<T> type, String id);

    final class Nothing implements FallbackHandler<Object> {
        @Override
        public Object apply(FallbackContext context) {
            return null;
        }
    }
}
```

**修复：** 使用声明了相同类型参数的同级降级方法。

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface Repository {
    @Fallback(method = "loadFallback")
    <T> T load(Class<T> type, String id);

    default <T> T loadFallback(Class<T> type, String id, Exception e) {
        return null;
    }
}
```

## 注解处理器：带注解的类（R4C-SUB） :id=annotation-processor-annotated-classes-r4c-sub

带注解的类会得到一个生成的子类 `<Class>$Resilient`，它覆盖带注解的方法，并通过 `Resilient.create(...)` 创建。因此该类必须可被继承，带注解的方法必须可被覆盖。

### R4C-SUB-001

**触发条件：** 带注解的类是 final 的。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**修复：** 去掉 `final`，或者把注解移到接口上，并用 `Resilient.of(...)` 包装实现。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-002

**触发条件：** 带注解的类是 sealed 的，因此生成的子类不是被允许的子类。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public sealed class PriceService permits CachedPriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}

final class CachedPriceService extends PriceService {
}
```

**修复：** 把它改为 non-sealed（或普通类），或者使用接口和 `Resilient.of(...)`。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}

final class CachedPriceService extends PriceService {
}
```

### R4C-SUB-003

**触发条件：** 带注解的类是抽象的。生成的子类必须可被实例化，并且会为每个方法调用 `super`。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public abstract class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**修复：** 给一个具体的子类或接口加注解。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-004

**触发条件：** 带注解的类，或包含它的某个类，是 private 的。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Services {
    private static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

**修复：** 把它改为包私有、protected 或 public。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Services {
    static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

### R4C-SUB-005

**触发条件：** 带注解的类是内部类（非 static 的嵌套类）：它的生成子类将需要一个外部类的实例。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class Services {
    class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

**修复：** 把它改为 static 嵌套类。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class Services {
    static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

### R4C-SUB-006

**触发条件：** 带注解类的所有构造函数都是 private 的，因此生成的子类一个也无法调用。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    private PriceService() {
    }

    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**修复：** 至少让一个构造函数为包私有、protected 或 public。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    protected PriceService() {
    }

    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-007

**触发条件：** 某个带注解的方法（在类、父类或其实现的接口中）是 final 的，因此子类无法覆盖它，其注解会被忽略。

**错误示例：**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public final String price(String sku) {
        return "1";
    }
}
```

**修复：** 去掉 `final`，或者去掉注解。

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-008

**触发条件：** 某个带注解的方法在另一个包的父类中是包私有的。位于带注解类所在包中的生成子类无法覆盖它，因此其注解会被忽略。

**错误示例：**

```java
// com/acme/base/BaseService.java
package com.acme.base;

import dev.resilience4cata.annotation.Retry;

public class BaseService {
    @Retry
    String price(String sku) {
        return "1";
    }
}

// com/acme/PriceService.java
package com.acme;

import com.acme.base.BaseService;
import dev.resilience4cata.annotation.Retry;

public class PriceService extends BaseService {
    @Retry
    public String name() {
        return "prices";
    }
}
```

**修复：** 把该方法改为 protected 或 public。

```java
// com/acme/base/BaseService.java
package com.acme.base;

import dev.resilience4cata.annotation.Retry;

public class BaseService {
    @Retry
    protected String price(String sku) {
        return "1";
    }
}

// com/acme/PriceService.java
package com.acme;

import com.acme.base.BaseService;
import dev.resilience4cata.annotation.Retry;

public class PriceService extends BaseService {
    @Retry
    public String name() {
        return "prices";
    }
}
```

## 运行时：Resilient 与 Pipeline（R4C-RT） :id=runtime-resilient-and-pipeline-r4c-rt

这些错误由 `resilience4cata-core` 在包装服务或构建 pipeline 时抛出。异常类型记录在各个方法上；错误码位于消息开头。

### R4C-RT-001

**触发条件：** 调用 `Resilient.of(type, ...)` 时 `type` 是类而不是接口（`IllegalArgumentException`）。

**错误示例：** `Resilient.of(PriceService.class, new PriceService(), registry)`，其中 `PriceService` 是一个带注解的类。

**修复：** 对于带注解的类，使用 `Resilient.create(PriceService.class, registry)`；`Resilient.of` 接受的是带注解的接口。

### R4C-RT-002

**触发条件：** 传给 `Resilient.of` 的对象没有实现该接口（`IllegalArgumentException`）。这只可能通过原始类型或未检查的强制转换发生。

**修复：** 传入该接口的一个实现。

### R4C-RT-003

**触发条件：** 找不到生成的类 `<type>$Resilient`（`IllegalStateException`）：该类型没有弹性注解，或者编译它时注解处理器没有运行。

**错误示例：** 一个只添加了注解的构建：

```groovy
dependencies {
    compileOnly 'dev.resilience4cata:resilience4cata-annotations:<version>'
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

**修复：** 添加注解处理器，它是必需的（没有反射后备）：

```groovy
dependencies {
    compileOnly 'dev.resilience4cata:resilience4cata-annotations:<version>'
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
}
```

使用 Maven 时：

```xml
<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version><!-- the same version as resilience4cata-core --></version>
  </path>
</annotationProcessorPaths>
```

在 GraalVM 原生镜像中，同样的错误意味着注解处理器运行了，但镜像无法通过反射访问该类：它在 class 文件旁边写出的可达性元数据 `META-INF/native-image/dev.resilience4cata.generated/<type>$Resilient/reachability-metadata.json` 不在镜像构建的 class path 上，或者某个构建步骤（shading、自定义资源过滤器）把它删掉了。此时消息会以一句指出该文件的话结尾。请把注解处理器的输出（包括元数据）保留在 `native-image` 读取的 jar 或目录中；参见[原生镜像](start/installation.md#native-image)。

### R4C-RT-004

**触发条件：** 生成的类没有当前版本 `resilience4cata-core` 所期望的构造函数，或者生成的子类没有继承带注解的类（`IllegalStateException`）。它是由不兼容版本的注解处理器生成的。

**修复：** 让 `resilience4cata-core` 和 `resilience4cata-processor` 使用相同的版本（BOM 会让它们保持一致），然后执行 clean 后重新构建。

### R4C-RT-005

**触发条件：** `resilience4cata-core` 无法访问生成的类（`IllegalStateException`），通常是因为应用是一个命名模块，并且没有向 core 导出该包。

**修复：** 在应用的 `module-info.java` 中添加：

```java
exports com.acme to dev.resilience4cata.core;
```

### R4C-RT-006

**触发条件：** 生成类的构造函数因受检异常而失败，或者无法被实例化（`IllegalStateException`，以该失败作为 cause）。构造函数抛出的非受检异常，例如无效配置，会原样重新抛出。

**修复：** 查看 cause；它通常来自分类器或降级处理器的构造函数。

### R4C-RT-007

**触发条件：** 以接口调用 `Resilient.create(type, ...)`（`IllegalArgumentException`）。

**修复：** 改为包装一个实现：`Resilient.of(PriceClient.class, implementation, registry)`。

### R4C-RT-008

**触发条件：** 以一个不能有生成子类的类型调用 `Resilient.create(type, ...)`：final、abstract、局部类、匿名类、枚举、record、数组或基本类型（`IllegalArgumentException`）。其中大多数情况注解处理器在编译期就会报告（[R4C-SUB-001](#r4c-sub-001) 及之后各项）。

**修复：** 给一个既非 final 也非 abstract 的顶层类或 static 嵌套类加注解，或者给接口加注解并使用 `Resilient.of(...)`。

### R4C-RT-009

**触发条件：** 带注解类的构造函数都不接受传给 `Resilient.create` 的参数（`IllegalArgumentException`）。消息会列出这些构造函数。

**修复：** 传入与某个构造函数匹配的参数，或者直接调用生成的构造函数，由编译器检查：`new PriceService$Resilient(registry, ...)`。

### R4C-RT-010

**触发条件：** 有多个构造函数接受传给 `Resilient.create` 的参数，例如单个 `null`（`IllegalArgumentException`）。

**修复：** 直接调用生成的构造函数，并用强制转换选择重载：`new PriceService$Resilient(registry, (String) null)`。

### R4C-RT-011

**触发条件：** 以空白 id 调用 `Pipeline.builder(registry, id)`（`IllegalArgumentException`）。

**修复：** 传入用来命名 pipeline 策略实例的 id，例如 `"payments"`。

### R4C-RT-012

**触发条件：** `Pipeline.Builder` 的某个部分被设置了两次：策略、分类器、`onExhaustedResult` 或降级（`IllegalStateException`）。

**错误示例：** `Pipeline.builder(registry, "payments").retry().retry("five")`

**修复：** 每个构建器中每个部分只设置一次；把覆盖值传给那一次调用：`retry("five", b -> b.maxAttempts(3))`。

### R4C-RT-013

**触发条件：** 设置了 `fallbackApplyOn` 或 `fallbackSkipOn`，但 pipeline 没有降级（`build()` 抛出 `IllegalStateException`）。

**修复：** 添加降级（`fallback`、`fallbackHandler`、`fallbackAsync`），或者去掉该选择。

### R4C-RT-014

**触发条件：** 设置了 `classifier(...)`，但 pipeline 既没有重试也没有熔断器（`build()` 抛出 `IllegalStateException`）。

**修复：** 添加分类器所针对的策略，或者去掉分类器。

### R4C-RT-015

**触发条件：** 设置了 `retryClassifier(...)`，但 pipeline 没有重试（`build()` 抛出 `IllegalStateException`）。

**修复：** 添加重试，或者去掉重试分类器。

### R4C-RT-016

**触发条件：** 设置了 `onExhaustedResult(...)`，但 pipeline 没有带分类器的重试（`build()` 抛出 `IllegalStateException`）。只有分类器才能拒绝结果。对应的注解错误是 [R4C-CLS-012](#r4c-cls-012)。

**修复：** 添加重试和分类器（`classifier` 或 `retryClassifier`），或者去掉 `onExhaustedResult`。

### R4C-RT-017

**触发条件：** 设置了 `circuitBreakerClassifier(...)`，但 pipeline 没有熔断器（`build()` 抛出 `IllegalStateException`）。

**修复：** 添加熔断器，或者去掉熔断器分类器。

### R4C-RT-018

**触发条件：** 在带有对冲请求的 pipeline 上调用了 `execute(...)` 或 `run(...)`（`IllegalStateException`）。对冲请求会在第一次尝试仍在运行时再次发起调用，这需要一个返回 `CompletionStage` 的调用。

**修复：** 调用 `executeAsync(() -> client.callAsync(...))`，或者从 pipeline 中去掉 `.hedge(...)`。

### R4C-RT-019

**触发条件：** pipeline 同时带有 `.retry(...)` 和 `.hedge(...)`（`build()` 抛出 `IllegalStateException`）。

**修复：** 只保留一个：对冲请求在某次尝试失败时（`hedgeOn`）已经会发起新的尝试，而包在外面的重试会让尝试次数成倍增加。这是 [R4C-HDG-002](#r4c-hdg-002) 在运行时的对应错误。

### R4C-RT-020

**触发条件：** 用带有对冲请求或分类器的 pipeline（`FluxPipelineOperator`）保护一个 `Flux`（`IllegalStateException`，交付给该 `Flux` 的订阅者）。对冲请求会合并两个流的元素，而分类器判断的是一个结果，一个流却有许多个。

**修复：** 去掉 `.hedge(...)` 或分类器，或者为该 `Flux` 构建一个单独的 pipeline。

### R4C-RT-021

**触发条件：** 由注解处理器生成的类（`X$Resilient`）在一个比生成它的 `resilience4cata-processor` 更旧的 `resilience4cata-core`（或者对于 `Mono` 和 `Flux` 方法，`resilience4cata-reactor`）上被构造（`IllegalStateException`，由生成的构造函数抛出）。生成的代码可能会调用当前运行时没有的方法。由注解处理器 `1.a` 生成的代码可以在同一主版本内任何 `b >= a` 的运行时 `1.b` 上运行。这通常发生在一个用较新 resilience4cata 编译的库被某个应用使用，而该应用的依赖树解析出的是较旧的 core 时。

**修复：** 使用至少与注解处理器一样新的运行时：为每个 resilience4cata 构件导入相同版本的 `resilience4cata-bom`，或者检查你的构建解析出的是哪个版本（`./gradlew dependencyInsight --dependency resilience4cata-core`、`mvn dependency:tree`）。

## 运行时：注册表与策略配置（R4C-REG） :id=runtime-registry-and-policy-configuration-r4c-reg

这些错误由 `resilience4cata-core` 在构建策略配置（`RetryConfig.builder()` 及其他 `*Config` 构建器）、构建 `ResilienceRegistry`，以及注册表创建或查找策略实例时抛出：即在包装服务（`Resilient.of`、`Resilient.create`）或构建 pipeline（`Pipeline.Builder.build()`，原样传递这些错误）期间。异常类型记录在各个方法上；错误码位于消息开头。外部配置（`ResilienceConfig`）在值到达注册表之前就会校验它们，并以 [R4C-CFG-006](#r4c-cfg-006) 报告，同时给出设置它们的键。

### R4C-REG-001

**触发条件：** 某个 `*Config` 构建器拒绝了一个值（`build()` 抛出 `IllegalArgumentException`），例如 `maxAttempts` 小于 1、负的时长、超过约 292 年（`Long.MAX_VALUE` 纳秒）的时长、`maxDelay` 短于 `delay`，或者比率阈值不在 `(0, 100]` 之内。消息会给出属性名和值。

**错误示例：** `RetryConfig.builder().maxAttempts(0).build()`

**修复：** 使用消息给出的范围内的值：`RetryConfig.builder().maxAttempts(1).build()`。

### R4C-REG-002

**触发条件：** `ResilienceRegistry.Builder.build()` 发现了无效的默认配置或命名配置（`IllegalArgumentException`，以 [R4C-REG-001](#r4c-reg-001) 错误作为 cause）。消息会给出配置名（或默认值）以及设置它的位置。

**错误示例：**

```java
ResilienceRegistry.builder()
        .retry("slow", r -> r.delay(Duration.ofSeconds(2)).maxDelay(Duration.ofSeconds(1)))
        .build();
```

**修复：** 让该配置的各个值组合起来有效。命名配置从默认值开始（`retryDefaults(...)`），然后应用 `retry("slow", ...)`，再应用 `retryOverlay("slow", ...)`（外部配置中的 `configs.slow.retry.*`）：

```java
ResilienceRegistry.builder()
        .retry("slow", r -> r.delay(Duration.ofSeconds(1)).maxDelay(Duration.ofSeconds(2)))
        .build();
```

### R4C-REG-003

**触发条件：** 某个策略实例的配置在合并了所有作用于它的设置之后无效（`IllegalArgumentException`，以 [R4C-REG-001](#r4c-reg-001) 错误作为 cause）：依次是它的命名配置（或默认值），然后是其注解或 pipeline 步骤中设置的值，然后是它的按实例覆盖（`retryInstance(id, ...)`，外部配置中的 `instances.<id>.*`）。它在创建实例时抛出：由 `Resilient.of`/`Resilient.create` 或 `Pipeline.Builder.build()` 抛出。

**错误示例：** 一个与实例自身的值不相容的按实例覆盖：

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryInstance("payments", r -> r.delay(Duration.ofSeconds(30)))
        .build();
Pipeline.builder(registry, "payments").retry(r -> r.maxDelay(Duration.ofSeconds(20))).build();
```

**修复：** 修改该覆盖，或者修改与之冲突的值，使组合有效，例如 `retryInstance("payments", r -> r.delay(Duration.ofSeconds(10)))`。

### R4C-REG-004

**触发条件：** `config = "name"`（在注解或 pipeline 步骤中），或者 `retryConfig("name")` 及其他 `*Config(String)` 查找，指定了一个注册表中不存在的配置（`IllegalArgumentException`）。消息会列出已知的配置，并给出最接近的一个（"Did you mean …?"）。

**错误示例：** `@Retry(config = "fiev")`，而注册表只注册了 `retry("five", ...)`。

**修复：** 改正名称，或者注册该配置：`ResilienceRegistry.builder().retry("fiev", r -> ...)`，或者外部配置中的 `configs.fiev.retry.*`。

### R4C-REG-005

**触发条件：** 同一个命名配置在一个构建器上被注册了两次，例如两次调用 `retry("five", ...)`（`IllegalArgumentException`）。

**修复：** 只注册一次。若要在别处修改它的部分属性，请使用 overlay，它会叠加在该配置之上：`retryOverlay("five", r -> r.maxAttempts(3))`。

### R4C-REG-006

**触发条件：** 以名称 `""` 或 `"default"` 注册命名配置或 overlay，例如 `retry("default", ...)`（`IllegalArgumentException`）。这两个名称都是保留的：它们表示默认值。

**修复：** 用 `retryDefaults(...)` 修改默认值（外部配置中的 `defaults.retry.*`），或者给该配置换一个名称。

### R4C-REG-007

**触发条件：** 以空的实例 id 调用 `retryInstance("", ...)` 或其他 `*Instance(...)`（`IllegalArgumentException`）。

**修复：** 传入实例的 id：其注解中设置的 `name`、pipeline id，或者派生 id `<type>#<method>(<parameter types>)`，例如 `com.acme.PriceClient#price(java.lang.String)`。

### R4C-REG-008

**触发条件：** 两个调用点通过注解中相同的 `name` 或相同的 pipeline id 共享同一个重试、熔断器、限流器或舱壁隔离实例，但解析出了不同的配置（`IllegalStateException`）。消息会显示两个配置。同名的实例是共享的，因此它们的配置必须一致。

**错误示例：**

```java
interface Inventory {
    @Retry(name = "backend", maxAttempts = 3)
    String stock(String sku);

    @Retry(name = "backend", maxAttempts = 5)
    String reserve(String sku);
}
```

**修复：** 如果这些调用点应该相互独立，就给它们不同的名称；如果它们应该共享同一个实例，就让它们的配置完全相同（例如相同的 `config = "..."`，且没有不同的属性）。

### R4C-REG-009

**触发条件：** 以该注册表中没有任何实例拥有的 id 调用 `registry.circuitBreaker(id)`、`registry.rateLimiter(id)` 或 `registry.bulkhead(id)`（`IllegalArgumentException`）。消息会列出已知的 id，并给出最接近的一个。实例在其接口被包装（`Resilient.of`）、其类被创建（`Resilient.create`）或其 pipeline 被构建时才创建，因此更早地查找也会失败。

**修复：** 使用注解中设置的 `name`（或 pipeline id），或者派生 id `<type>#<method>(<parameter types>)`，并在服务或 pipeline 创建之后再查找。

## 运行时：外部配置（R4C-CFG） :id=runtime-external-configuration-r4c-cfg

`ResilienceConfig.build()` 在一个 `ConfigException` 中一次性报告所有问题；每一行都以其错误码开头，并给出键及其来源（文件和行号、环境变量或系统属性）。

### R4C-CFG-001

**触发条件：** 未知、格式错误或不完整的键：未知的策略或属性、空段、提前结束的键。消息会给出最接近的有效键。带短前缀（`r4c.`、`R4C_`）且第一段与 `defaults`、`configs`、`instances` 或 `profiles` 都不接近的键则会被忽略，视为其他工具的键（[键](reference/configuration.md#key-forms)）。

**错误示例：**

```properties
configs.external.circuit-breaker.wait-in-opne=10s
```

**修复：** 使用消息列出的某种键形式：`defaults.<policy>.<attribute>`、`configs.<name>.<policy>.<attribute>`、`configs.<name>.extends`、`instances.<id>.<policy>.<attribute>`、`profiles.<profile>.…` 或 `profiles.active`。

```properties
configs.external.circuit-breaker.wait-in-open=10s
```

### R4C-CFG-002

**触发条件：** 某个值无法按其属性解析：数字、布尔值、枚举常量或时长。

**错误示例：**

```properties
defaults.retry.delay=fast
```

**修复：** 使用属性类型的值；时长使用与注解相同的语法（`"250ms"`、`"2s"`、`"PT2S"`、`"0"`）。

```properties
defaults.retry.delay=250ms
```

### R4C-CFG-003

**触发条件：** 某个异常列表（`retry-on`、`abort-on`、`record-on`、`ignore-on`）指定了类加载器找不到的类，或者不是 `Throwable` 的类。

**错误示例：**

```properties
defaults.retry.retry-on=java.io.IOExeption
```

**修复：** 使用应用类加载器可见的异常类的全限定名。在 GraalVM 原生镜像中，把你自己的每个类都注册为可反射：消息会显示需要添加的条目（[原生镜像](start/installation.md#native-image)）。

```properties
defaults.retry.retry-on=java.io.IOException
```

### R4C-CFG-004

**触发条件：** 无效的配置名或 profile 名，或者是保留名称（配置的 `default`，profile 的 `active`）。名称使用小写字母和数字，以单个连字符分隔，以便映射到环境变量。

**错误示例：**

```properties
profiles.active=Test
```

**修复：** 重命名，例如 `test`。

```properties
profiles.active=test
```

### R4C-CFG-005

**触发条件：** 同一个键在同一个来源中被设置了两次（例如在一个文件中出现两次，或者以两种拼写出现，如 `max-attempts` 和 `maxAttempts`）。

**修复：** 只保留其中一个。不同的来源可以设置同一个键；由优先级顺序决定。

### R4C-CFG-006

**触发条件：** 各个值单独看都有效，但组合在一起无效，例如 `max-delay` 短于 `delay`，或者 `max-attempts=0`（配置构建器拒绝了它们）。消息会给出涉及的键。

**错误示例：**

```properties
configs.slow.retry.delay=2s
configs.slow.retry.max-delay=1s
```

**修复：** 按照消息的描述，让这些值保持一致。

```properties
configs.slow.retry.delay=1s
configs.slow.retry.max-delay=2s
```

### R4C-CFG-007

**触发条件：** `configs.<name>.extends` 指定了一个在外部配置中没有定义的配置，或者各配置之间的继承形成了循环。

**错误示例：**

```properties
configs.batch.extends=slwo
```
**修复：** 继承一个已有的配置（消息会给出最接近的名称并列出已知的配置），并打破任何循环。

### R4C-CFG-008

**触发条件：** 保留；当前版本不会报告。

### R4C-CFG-009

**触发条件：** 无法读取某个配置来源：不存在的 classpath 资源或文件，或者 I/O 错误。

**修复：** 检查路径，或者在该来源是可选的情况下不要添加它。

### R4C-CFG-010

**触发条件：** 某个环境变量匹配多个键，因为 `_` 同时代表 `.` 和 `-`。

**修复：** 改为在 properties 文件中或作为系统属性设置该键。

### R4C-CFG-011

**触发条件：** 已停用；不再报告。同时在代码中和外部设置的命名配置现在会被合并，以外部值为准。

### R4C-CFG-012

**触发条件：** `instances.` 键中的实例 id 格式错误：包含 `.` 但没有加引号的 id、空 id、未闭合或位置错误的 `"`、引号包住的不只是 id，或者以空白开头或结尾的 id。

**错误示例：**

```properties
instances.com.acme.Api#get(java.lang.String).retry.max-attempts=5
```

**修复：** 给实例 id 加上引号。

```properties
instances."com.acme.Api#get(java.lang.String)".retry.max-attempts=5
```

### R4C-CFG-013

**触发条件：** `config.verifyInstances(registry)` 发现一个 `instances.` 覆盖与注册表中该策略的任何实例都不匹配（由 `verifyInstances` 而不是 `build()` 报告，因为实例只有在服务被包装之后才存在）。消息会给出最接近的 id。

**修复：** 使用已有的实例 id：策略的 `name`，或者在没有 `name` 时使用 `<type>#<method>(<parameter types>)`。

### R4C-CFG-014

**触发条件：** `config.verifyInstances(registry)` 发现一个 `instances.` 覆盖针对的是实例的注解以 `locked = true` 设置的属性（或者以 `.locked()` 构建的 pipeline 在其步骤上设置的属性）。注册表保留了注解的值并忽略了该覆盖；消息会显示该实例使用的配置。

**修复：** 删除该键；或者如果运维人员应该能够修改该值，就从注解中去掉 `locked = true`。参见[锁定实例](guides/configuration.md#locking-an-instance)。

## 运行时：Spring Boot 集成（R4C-SPR） :id=runtime-spring-boot-integration-r4c-spr

`resilience4cata-spring` 在启动时检查 bean，当某个 bean 会悄无声息地失去保护时，以这些错误码让启动失败。Spring Boot 会把消息作为其 "APPLICATION FAILED TO START" 报告的 *Description*，把修复方法作为 *Action* 打印出来。参见 [Spring Boot 指南](guides/spring.md)。

### R4C-SPR-001

**触发条件：** 某个 bean 是带注解类 `C` 的实例，却不是生成的 `C$Resilient` 的实例，因此它的策略永远不会运行：某个 `@Bean` 方法返回了 `new C(...)`，该 bean 是通过 supplier 注册的，或者它是 `C` 的一个自身没有注解的子类（`BeanCreationException`）。

```text
[R4C-SPR-001] Bean 'pricing' is an instance of com.acme.PricingService, which has resilience4cata annotations, but not of the generated com.acme.PricingService$Resilient, so its policies would not run. ...
```

**修复：** 注册类本身（组件扫描、`@Import`），让 Spring 实例化 `C$Resilient`；或者在 `@Bean` 方法中返回 `new C$Resilient(registry, ...)`，并把 `ResilienceRegistry` 作为参数注入；或者给该子类加注解。若有意让该 bean 不受保护，把它的名称列入 `resilience4cata.spring.exclude`。

### R4C-SPR-002

**触发条件：** 一个带有 `@Bean` 方法的 `@Configuration` 类同时带有 resilience4cata 注解。Spring 会为这样的类实例化它自己的 CGLIB 子类，因此无法使用生成的子类。

**修复：** 把带注解的方法移到一个 `@Component` 或 `@Service` 类中，或者用 `@Configuration(proxyBeanMethods = false)` 声明该配置类，Spring 不会为它创建子类。

### R4C-SPR-003

**触发条件：** 某个 bean 实现了两个或更多带注解的接口，且其中没有一个继承了所有其他接口（`BeanCreationException`）。一个接口的生成包装器只实现该接口，因此该 bean 将无法再以其他接口注入。

**修复：** 改为给类加注解（其生成的子类保留所有接口，并且仍然是该 bean 的类型），或者声明一个继承其他所有接口的接口并实现它。若要自己包装该 bean，把它列入 `resilience4cata.spring.exclude`，并定义一个返回 `Resilient.of(...)` 的 `@Bean`。

### R4C-SPR-004

**触发条件：** 某个带注解的类有一个生效了 `@Retry` 或 `@Hedge` 的方法（声明在方法、类、父类、接口或 bundle 上），该方法同时是事务性的：Spring 或 Jakarta 的 `@Transactional`，其传播行为会加入或开启一个事务（`REQUIRED`、`SUPPORTS`、`MANDATORY`、`REQUIRES_NEW`、`NESTED`）（`BeanCreationException`）。事务代理包在生成的子类外面，因此每次尝试都会在同一个事务中运行，而一次失败并将事务标记为 rollback-only 的尝试会让之后成功尝试的提交以 `UnexpectedRollbackException` 失败。

```text
[R4C-SPR-004] Bean 'ledger' (com.acme.Ledger) has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**修复：** 改为在该 bean 实现的接口上声明策略（其包装器位于事务代理外面：每次尝试一个事务）；或者把 `@Transactional` 移到被重试方法调用的另一个 bean 上；或者，如果确实希望所有尝试共享一个事务，设置 `resilience4cata.spring.allow-retry-inside-transaction=true`。`NOT_SUPPORTED` 和 `NEVER` 不会被报告。

### R4C-SPR-005

**触发条件：** 某个 bean 实现了带注解的接口，但 Spring 在 resilience4cata 的后处理器注册之前就创建了它，因此它从未被包装，其策略不会运行。当某个 `BeanPostProcessor` 或 `BeanFactoryPostProcessor` 依赖于某个 bean（该后处理器的构造函数参数或字段）时，Spring 会这么早创建该 bean，并记录该 bean "is not eligible for getting processed by all BeanPostProcessors"（`ResilienceBeanException`，在所有单例创建完成后抛出）。

```text
[R4C-SPR-005] Bean 'stockClient' (com.acme.HttpStockClient) implements com.acme.StockClient, which has resilience4cata annotations, but it was created before resilience4cata's post-processor was registered, ... It was needed by 'auditPostProcessor'. ...
```

**修复：** 以延迟方式把该 bean 注入那个后处理器（`ObjectProvider<StockClient>` 或 `@Lazy`），让它稍后经过所有后处理器再创建；或者改为给类而不是接口加注解（其生成的子类会代替该类被实例化，无论早晚）。若有意让该 bean 不受保护，把它的名称列入 `resilience4cata.spring.exclude`。

### R4C-SPR-006

**触发条件：** 某个 bean 的类、它的某个父类或它实现的某个接口带有 resilience4cata 注解，但注解处理器为它生成的类（`C$Resilient`）不存在：编译该类型时注解处理器没有运行，因此其策略不会运行。这些注解的保留策略是 `CLASS`，所以不会有其他东西注意到这一点（`ResilienceBeanException`，在创建该 bean 时抛出）。该检查读取 class 文件，因此不会在原生镜像中运行。

```text
[R4C-SPR-006] Bean 'prices' is an instance of com.acme.PriceService, which has resilience4cata annotations, but there is no generated com.acme.PriceService$Resilient: the annotation processor did not run when PriceService was compiled, so its policies would not run. ...
```

**修复：** 把 `resilience4cata-processor` 添加到注解处理器路径，然后执行 clean 后重新构建（[安装](start/installation.md)）：Gradle 中使用 `annotationProcessor`（测试源码使用 `testAnnotationProcessor`），Maven 中使用 `maven-compiler-plugin` 的 `annotationProcessorPaths`，其中还必须列出所有其他注解处理器。若有意在没有策略的情况下启动，设置 `resilience4cata.spring.allow-missing-processor=true`，或者把该 bean 列入 `resilience4cata.spring.exclude`。

## 运行时：Micronaut 集成（R4C-MN） :id=runtime-micronaut-integration-r4c-mn

`resilience4cata-micronaut` 检查 bean，当某个 bean 会悄无声息地失去保护时，以这些错误码失败。`@ResilientBean` 相关的错误（001 到 003）会中止启动：每一个标注了 `@ResilientBean` 的 bean 定义都会在上下文启动时、在任何 bean 创建之前被检查。其他错误在 bean 创建时报告——对于 `@Context` bean 是在启动时，对于其他 bean 是在首次使用时（Micronaut 以延迟方式创建 bean）—— 作为 Micronaut 的 `BeanInstantiationException` 内部的 `ResilienceBeanException` 抛出。参见 [Micronaut 指南](guides/micronaut.md)。

### R4C-MN-001

**触发条件：** 标注了 `@ResilientBean(I.class)` 的 bean 以 `I` 之外的类型暴露，例如因为它还声明了 `@Bean(typed = {I.class, Impl.class})`。`@ResilientBean` 把 bean 的类型限制为 `I`，因为替换该 bean 的生成包装器只实现 `I`：以其他类型注入它会失败或绕过策略。

```text
[R4C-MN-001] Bean com.acme.HttpPayments is annotated @ResilientBean(Payments.class) but is exposed as [com.acme.HttpPayments, com.acme.Payments]. ...
```

**修复：** 从该 bean 上去掉 `@Bean(typed = ...)`（或 `@Type`），并注入接口。

### R4C-MN-002

**触发条件：** `@ResilientBean(X.class)` 指定的类型不是接口，或者是该 bean 没有实现的接口，因此没有任何生成的包装器能保护它。

**修复：** 指定该 bean 实现的那个带注解的接口。对于带注解的类，去掉 `@ResilientBean`：使用 `-Ar4c.inject=micronaut` 编译，让 Micronaut 创建其生成的子类，或者在 `@Factory` 方法中返回 `new C$Resilient(registry, ...)`（[带注解的类](guides/micronaut.md#classes)）。

### R4C-MN-003

**触发条件：** 标注了 `@ResilientBean` 的 bean 具有 Micronaut 会为其创建代理的作用域（`@Refreshable`、`@RequestScope`、`@ThreadLocal`、任何 `@ScopedProxy`）。这样的代理按 bean 的类查找其目标，而生成的包装器并不是这个类。

**修复：** 把该 bean 改为 `@Singleton`、`@Prototype` 或 `@Context`，或者让带作用域的 bean 保持不受保护，并把它注入一个实现了带注解接口的单例中。

### R4C-MN-004

**触发条件：** 某个 bean 是带注解类 `C` 的实例，却不是生成的 `C$Resilient` 的实例，因此它的策略永远不会运行：Micronaut 自己创建了 `C`（它带有作用域注解或 `@Inject` 构造函数，而代码在编译时没有使用 `-Ar4c.inject=micronaut`），或者某个 `@Factory` 方法返回了 `new C(...)`。当 Micronaut 根据 `C$Resilient` 自己的定义创建它、而它在编译时没有使用该选项时，也会报告此错误：它的构造函数重复了 `C` 的 `@Inject`，因此它是一个 bean，但没有 `C` 的作用域和限定符（一个 `@Singleton` 会变成每次注入一个新实例）。

```text
[R4C-MN-004] Bean com.acme.InventoryService is an instance of com.acme.InventoryService, which has resilience4cata annotations, but not of the generated InventoryService$Resilient, so its policies would not run. ...
```

**修复：** 使用 `-Ar4c.inject=micronaut` 编译，让 Micronaut 创建 `C$Resilient` 来代替 `C`；或者从 `C` 上去掉作用域注解（以及 `@Inject`），并在一个类型为 `C` 的 `@Factory` 方法中返回 `new C$Resilient(registry, ...)`。若有意让该 bean 不受保护，把该类的名称列入 `resilience4cata.micronaut.exclude`。

### R4C-MN-005

**触发条件：** 一个使用 `-Ar4c.inject=micronaut` 编译的带注解类有一个生效了 `@Retry` 或 `@Hedge` 的方法，该方法同时是 `@Transactional`（Micronaut 的，或映射到它上面的 Jakarta 的），其传播行为会加入或开启一个事务（`REQUIRED`、`SUPPORTS`、`MANDATORY`、`REQUIRES_NEW`、`NESTED`）。生成的子类重复了 `@Transactional`，因此 Micronaut 的事务代理包在它外面：每次尝试都会在同一个事务中运行，而一次失败并将事务标记为 rollback-only 的尝试会让之后成功尝试的提交失败。

```text
[R4C-MN-005] Bean com.acme.Ledger has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**修复：** 在该 bean 实现的接口上声明策略，并给 bean 加上 `@ResilientBean`（包装器位于事务代理外面：每次尝试一个事务）；或者把 `@Transactional` 移到被重试方法调用的另一个 bean 上；或者，如果确实希望所有尝试共享一个事务，设置 `resilience4cata.micronaut.allow-retry-inside-transaction=true`。`NOT_SUPPORTED` 和 `NEVER` 不会被报告。该检查读取生成的 class 文件，因此原生镜像会跳过它。

### R4C-MN-006

**触发条件：** Micronaut 集成的某个开关具有无效值，例如 `resilience4cata.micronaut.verify-instances: strict`。上下文启动失败。

```text
[R4C-MN-006] Invalid value 'strict' for resilience4cata.micronaut.verify-instances. Use fail, warn or off.
```

**修复：** 使用消息列出的某个值。

### R4C-MN-007

**触发条件：** 某个 bean 的类或它的某个父类带有 resilience4cata 注解，但注解处理器为它生成的子类（`C$Resilient`）不存在：编译该类时注解处理器没有运行，因此其策略不会运行。这些注解的保留策略是 `CLASS`，所以不会有其他东西注意到这一点（`ResilienceBeanException`，在创建该 bean 时抛出）。该检查读取 class 文件，因此不会在原生镜像中运行。带注解的接口只通过 `@ResilientBean` 被包装，缺少包装器时它会以 [R4C-RT-003](#r4c-rt-003) 失败。

```text
[R4C-MN-007] Bean com.acme.PriceService has resilience4cata annotations, but there is no generated com.acme.PriceService$Resilient: the annotation processor did not run when PriceService was compiled, so its policies would not run. ...
```

**修复：** 把 `resilience4cata-processor` 添加到注解处理器路径中 `micronaut-inject-java` 之后，并使用 `-Ar4c.inject=micronaut`，然后执行 clean 后重新构建（[Micronaut](guides/micronaut.md)）。若有意在没有策略的情况下启动，设置 `resilience4cata.micronaut.allow-missing-processor=true`，或者把该类列入 `resilience4cata.micronaut.exclude`。

## 构建：Quarkus 扩展（R4C-QK） :id=build-quarkus-extension-r4c-qk

`resilience4cata-quarkus` 在 Quarkus 构建应用时（`quarkusBuild`、dev 模式、每一个 `@QuarkusTest`）检查应用，当某个 bean 不会按声明方式被保护、或会被保护两次时，以这些错误码让构建失败。Quarkus 会把它们打印在其 "deployment problems" 中。参见 [Quarkus 指南](guides/quarkus.md)。

### R4C-QK-001

**触发条件：** 一个受 resilience4cata 保护的方法同时带有 MicroProfile 或 SmallRye Fault Tolerance 注解（`org.eclipse.microprofile.faulttolerance.*`、`io.smallrye.faulttolerance.api.*`），位于方法上或其类上；对于带注解的接口，也包括实现类的方法上。它们的简单名称相同（`@Retry`、`@CircuitBreaker`、`@Timeout`、`@Bulkhead`、`@Fallback`），所以这通常是 import 错了。安装了 `quarkus-smallrye-fault-tolerance` 时两个库都会生效，因此构建失败；没有安装时 Fault Tolerance 注解不起任何作用，同样的消息会作为警告记录。

```text
[R4C-QK-001] Methods protected by resilience4cata also carry MicroProfile Fault Tolerance annotations: com.acme.CatalogClient.fetch(). SmallRye Fault Tolerance would apply its interceptor as well, so both libraries would retry, time out and count the same calls. ...
```

**修复：** 每个方法只用一个库：去掉另一个注解，或者修正 import。参见 [SmallRye Fault Tolerance 与 resilience4cata](guides/quarkus.md#smallrye)。

### R4C-QK-002

**触发条件：** 某个带注解的类有一个生效了 `@Retry` 或 `@Hedge` 的方法，该方法同时是 `@Transactional`（Jakarta Transactions，配合 Narayana），其类型会加入或开启一个事务（`REQUIRED`、`REQUIRES_NEW`、`MANDATORY`、`SUPPORTS`）。事务拦截器包在生成的子类外面，因此每次尝试都会在同一个事务中运行，而一次失败并将事务标记为 rollback-only 的尝试会让之后成功尝试的提交失败。

```text
[R4C-QK-002] Bean com.acme.Ledger has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**修复：** 改为在该 bean 实现的接口上声明策略（其包装器位于事务拦截器外面：每次尝试一个事务）；或者把 `@Transactional` 移到被重试方法调用的另一个 bean 上；或者，如果确实希望所有尝试共享一个事务，设置 `quarkus.resilience4cata.allow-retry-inside-transaction=true`。`NOT_SUPPORTED` 和 `NEVER` 不会被报告。

### R4C-QK-003

**触发条件：** 某个 bean 实现了两个或更多带注解的接口，且其中没有一个继承了所有其他接口。一个接口的生成包装器只实现该接口，因此该 bean 只能通过一个包装器注入。

**修复：** 声明一个继承其他所有接口的接口并实现它，或者改为给类加注解。若要自己包装该 bean，把它的类列入 `quarkus.resilience4cata.beans.exclude`，并在 producer 方法中返回 `Resilient.of(...)`。

### R4C-QK-004

**触发条件：** 某个带注解的类声明了 producer 方法或字段（`@Produces`）。生成的子类代替该类成为 bean，而 CDI producer 不会被继承，因此它们会消失。

**修复：** 把 producer 移到另一个 bean 中，或者把该类列入 `quarkus.resilience4cata.beans.exclude`。

### R4C-QK-005

**触发条件：** 某个带注解的类是 normal scope 的 bean（`@ApplicationScoped`、`@RequestScoped`、`@SessionScoped`），并且没有非 private 的无参构造函数。Quarkus 放在生成子类前面的 client proxy 需要一个这样的构造函数，而只有当该类有这样的构造函数时，Quarkus 才能把它添加到子类中。

```text
[R4C-QK-005] com.acme.OrderService is a normal-scoped bean (@ApplicationScoped) with resilience4cata annotations, but has no non-private constructor without parameters. ...
```

**修复：** 添加一个无参构造函数（包私有即可：只有代理会使用它），或者使用字段注入代替构造函数注入，或者把该 bean 改为 `@Singleton`，它没有 client proxy。

### R4C-QK-006

**触发条件：** 一个受 resilience4cata 保护的 bean 带有查找条件（`@LookupIfProperty`、`@LookupUnlessProperty`）：一个带注解的类，或者一个实现了带注解接口的 bean。应用获得的 bean 是生成的子类或接口包装器，而 Quarkus 只在原始类上读取查找条件，因此无论属性如何设置，该 bean 都仍然可以被解析。

```text
[R4C-QK-006] Beans protected by resilience4cata have lookup conditions (@LookupIfProperty, @LookupUnlessProperty): com.acme.BetaFeature. ...
```

**修复：** 改用构建期条件（`@IfBuildProperty`、`@IfBuildProfile` 及其 `Unless` 形式），它们在带注解的类上有效；或者在查找该 bean 的代码中检查该属性。

### R4C-QK-007

**触发条件：** 某个 Quarkus REST 资源（带 `@Path` 的类，或实现了带 `@Path` 接口的类）是带注解的类。Quarkus REST 自己创建资源类，因此生成的子类永远不会处理这些端点，策略也不会运行。

```text
[R4C-QK-007] REST resource classes (@Path) have resilience4cata annotations: com.acme.OrdersResource. ...
```

**修复：** 把受保护的调用移到资源所注入的某个 bean 中（一个带注解的类，或一个实现了带注解接口的 bean），或者保护资源所调用的 REST client 接口。

### R4C-QK-008

**触发条件：** 某个类 bean 的类、它的某个父类或它实现的某个接口带有 resilience4cata 注解，但注解处理器为它生成的类（`C$Resilient`）不在应用中：编译该类型时注解处理器没有运行，因此其策略不会运行。这些注解的保留策略是 `CLASS`，所以运行时不会有任何东西注意到这一点（`DeploymentException`，在构建期间抛出）。

```text
[R4C-QK-008] Beans have resilience4cata annotations but no generated class: the annotation processor did not run when these types were compiled, so their policies would not run: com.acme.PriceService (no com.acme.PriceService$Resilient). ...
```

**修复：** 把 `resilience4cata-processor` 添加到注解处理器路径，然后执行 clean 后重新构建（[Quarkus](guides/quarkus.md)）。若有意在没有策略的情况下构建，设置 `quarkus.resilience4cata.allow-missing-processor=true`，或者把这些 bean 类列入 `quarkus.resilience4cata.beans.exclude`。

## 测试：testkit（R4C-TK） :id=tests-testkit-r4c-tk

`ManualClock` 和 `ResilienceTesting` 以这些错误码报告测试中的误用。

### R4C-TK-001

**触发条件：** 以负的时长调用 `ManualClock.advance(…)`（`IllegalArgumentException`）。时钟只会向前走。

**修复：** 传入零或更大的时长。若要让测试从另一个时间开始，就用该时间创建时钟：`new ManualClock(startNanos)`，或者在使用 JUnit 扩展时用 `@ResilienceTest(startNanos = …)`。

### R4C-TK-002

**触发条件：** `ManualClock.advanceUntilIdle()` 在连续执行 100,000 个任务后放弃（`IllegalStateException`）：它运行的每个任务都调度了另一个任务，因此时钟永远不会空闲。通常是一个在时间上没有限制、不断失败的重试，或者一个会重新调度自己的测试辅助方法。

**修复：** 改用 `clock.advance(…)` 前进到一个固定时间，或者给重试设置限制（`maxAttempts`、`maxDuration`），使任务能够结束。

### R4C-TK-003

**触发条件：** 对一个不处于 `OPEN` 状态的熔断器调用 `ResilienceTesting.elapseWaitInOpen(breaker, clock)`（`IllegalStateException`）；消息会说明它处于哪个状态。

**修复：** 在熔断器打开之后再调用：检查到目前为止记录的失败是否达到 `minimumCalls` 和 `failureRateThreshold`。若要在没有失败的情况下测试打开状态，使用 `breaker.forceOpen()`。

## 测试：JUnit 扩展（R4C-JU） :id=tests-junit-extension-r4c-ju

`@ResilienceTest`（`ResilienceExtension`）以这些错误码报告误用；JUnit 会把它们显示为测试或测试类的失败。

### R4C-JU-001

**触发条件：** 构造函数或 `@BeforeAll` 方法上有 `ManualClock` 或 `ResilienceRegistry` 参数（`ParameterResolutionException`）。扩展为每个测试创建新的时钟和注册表，因此在第一个测试开始之前还不存在。

**修复：** 把它们作为测试方法或 `@BeforeEach` 方法的参数。

### R4C-JU-002

**触发条件：** 某个 `@ConfigureRegistry` 方法的签名不符合要求（`ExtensionConfigurationException`；JUnit 5 会把它包装在 `ParameterResolutionException` 中）。

**修复：** 把它改为一个非 private 的 `void` 方法，带一个 `ResilienceRegistry.Builder` 参数，static 与否均可：`void configure(ResilienceRegistry.Builder builder)`。

### R4C-JU-003

**触发条件：** 使用 `@ResilienceTest(failOnPendingTasks = true)` 时，某个测试通过了，但在其时钟上留下了任务（`AssertionError`）：一个从未运行的异步重试、等待或超时。消息会列出它们的延迟。

**修复：** 让时钟越过它们（`clock.advanceUntilIdle()`，或者用 `clock.advance(…)` 前进足够远），或者如果有意留下它们，设置 `failOnPendingTasks = false`。
