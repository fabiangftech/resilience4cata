# 组合注解

组合注解（bundle）是你自己定义的、携带弹性注解的注解，这样团队只需写一次 `@RemoteCall`，而不必在每个方法上重复四个注解。

## 定义组合注解 :id=define-a-bundle

用 `@ResiliencePolicy` 标记该注解，赋予它 `CLASS` 保留策略以及 `METHOD` 和 `TYPE` 目标，再把策略注解放在它上面：

<!-- snippet: bundles-annotation -->
```java
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
public @interface RemoteCall {
}
```

如果没有 `@ResiliencePolicy`，注解处理器会忽略该注解上的策略，因此它会报告 [`R4C-CMP-001`](../errors.md#r4c-cmp-001)。

## 使用组合注解 :id=use-it

在类型上，组合注解（与任何弹性注解一样）适用于每个方法；在方法上，只适用于该方法。直接写在元素上的注解会逐个属性地优先于组合注解：

<!-- snippet: bundles-usage -->
```java
@RemoteCall
public interface ShippingClient {

    String track(String parcel) throws IOException;

    @Retry(maxAttempts = 1)   // overrides one attribute of the bundle: never retried
    String cancel(String parcel) throws IOException;
}
```

`track` 获得整个组合注解。`cancel` 同样获得组合注解，只是 `maxAttempts` 由它自己的 `@Retry` 设置为 1；它的 `config = "external"` 仍然来自组合注解。

## 组合注解与命名配置 :id=bundles-and-named-configs

使用 `config = "…"` 而不是字面值的组合注解可以把数字留在代码之外：由注册表或[外部配置](configuration.md)定义 `external` 的含义，运维人员无需重新编译即可修改它。

<!-- snippet: bundles-registry -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryDefaults(r -> r.delay(Duration.ofMillis(100)))
        .retry("external", r -> r.maxAttempts(5))
        .circuitBreaker("external", cb -> cb.failureRateThreshold(25).waitInOpen(Duration.ofSeconds(30)))
        .build();
```

使用这个注册表，`track` 最多尝试 5 次，间隔 100 ms（抖动之前），其熔断器在失败率达到 25% 时打开。注册表不认识的配置名会在包装接口时失败（[`R4C-REG-004`](../errors.md#r4c-reg-004)），而不是在第一次调用时。

## 组合注解的组合 :id=bundles-of-bundles

组合注解可以携带其他组合注解。它自己的策略注解会逐个属性地优先于它继承来的注解：

<!-- snippet: bundles-nested -->
```java
@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@RemoteCall
@Retry(maxAttempts = 5)   // beats RemoteCall's retry attributes, keeps its config = "external"
public @interface Idempotent {
}
```

带有 `@Idempotent` 注解的方法获得 `RemoteCall` 的熔断器和两秒超时，以及一个 `config = "external"`、尝试 5 次的重试。

## 优先级 :id=precedence

对于每个属性，优先级从低到高：

1. 内置默认值和注册表默认值，然后是用 `config = "…"` 选择的命名配置（[配置从何而来](../start/how-it-works.md#where-configuration-comes-from)）。
2. 类型级注解：先是声明该方法的接口的（当它是父接口时），然后是你所包装的接口的。
3. 方法级注解。

在同一个元素上，直接注解优先于所有组合注解，组合注解优先于它所携带的组合注解。方法级的组合注解优先于类型级注解（即使是直接注解），因为方法是更具体的元素。对于[注解类](annotated-classes.md#annotations-on-the-class-its-superclasses-and-its-interfaces)，类及其父类在接口的层级之上再加上自己的层级。

按实例的覆盖（`instances.<id>.*` 和 `retryInstance(...)`）在所有这些之后应用。

## 两个相互冲突的组合注解 :id=two-bundles-that-disagree

如果同一元素上的两个组合注解把同一属性设为不同的值，二者都无法胜出，因此构建会以 [`R4C-CMP-002`](../errors.md#r4c-cmp-002) 失败。不存在悄无声息的"后者胜出"：

<!-- not-compiled: 有意的编译错误（R4C-CMP-002） -->
```java
public interface PriceClient {
    @Fast       // @Retry(maxAttempts = 2)
    @Patient    // @Retry(maxAttempts = 5)
    String price(String sku);
}
```

保留其中一个，让它们一致，或者直接在方法上写出该属性，它会优先于两者。
