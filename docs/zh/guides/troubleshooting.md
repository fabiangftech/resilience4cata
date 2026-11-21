# 故障排查

按症状列出使用 resilience4cata 时常遇到的问题，以及原因和解决方法。

本库报告的每个错误都以 `[R4C-RT-003]` 这样的代码开头；[错误参考](../errors.md)为每个代码提供一节，包含错误示例和修复方法。

## 构建和代码生成 :id=build

### `Resilient.of` 失败："No generated class … found" :id=no-generated-class

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
With Maven, in the maven-compiler-plugin's <annotationProcessorPaths>:
    <path><groupId>dev.resilience4cata</groupId><artifactId>resilience4cata-processor</artifactId><version>...</version></path>
```

编译接口时注解处理器没有运行，或者该接口没有任何弹性注解。注解处理器是必需的：没有反射回退方案。在框架集成中，同样的错误会改为在启动时出现：Spring Boot 中为 [R4C-SPR-006](../errors.md#r4c-spr-006)，Micronaut 中带注解的类为 [R4C-MN-007](../errors.md#r4c-mn-007)，Quarkus 中为 [R4C-QK-008](../errors.md#r4c-qk-008)，它会使构建失败。下面的修复方法是相同的。

- **Gradle：** 添加 `annotationProcessor 'dev.resilience4cata:resilience4cata-processor'`（如果使用 BOM，还要加 `annotationProcessor platform(…bom…)`，因为处理器路径是单独解析的）。对于 `src/test` 下带注解的类型，也要使用 `testAnnotationProcessor` 和 `testCompileOnly`。
- **Maven：** 在 `maven-compiler-plugin` 的 `<annotationProcessorPaths>` 中列出注解处理器。使用 maven-compiler-plugin 3.12.0 或更高版本时，没有 `<version>` 的 `<path>` 会从 `dependencyManagement`（导入的 BOM）获取版本；该插件的旧版本需要显式的 `<version>`。
- **注解处理器只在类路径上：** 从 JDK 23 起，除非明确要求，`javac` 不再运行在类路径上找到的注解处理器。请把它放到处理器路径上。
- **检查：** 构建之后，`build/generated/sources/annotationProcessor/java/main/<package>/<Type>$Resilient.java`（Gradle）或 `target/generated-sources/annotations/…`（Maven）存在。

参见 [R4C-RT-003](../errors.md#r4c-rt-003) 和[安装](../start/installation.md)。

### `[R4C-RT-004]`：生成的类不匹配 :id=version-mismatch

生成的类由一个此 `resilience4cata-core` 无法运行的注解处理器生成：通常是来自较旧、不兼容的处理器（1.0 之前的，或另一个主版本的）的过期生成类。在同一主版本内，core 比处理器新是没问题的；core 比处理器旧则改报 [R4C-RT-021](../errors.md#r4c-rt-021)。请从 BOM 获取所有构件，然后从干净状态重新构建，让旧的生成类消失。参见 [R4C-RT-004](../errors.md#r4c-rt-004)。

### 模块化应用中的 `[R4C-RT-005]` :id=jpms

`core` 通过反射加载 `<Type>$Resilient`，因此你的模块必须向它导出（或开放）该包：`exports com.acme.shop.clients to dev.resilience4cata.core;`。参见 [R4C-RT-005](../errors.md#r4c-rt-005)。

### IDE 不运行注解处理器 :id=ide

在命令行上构建正常，但在 IDE 中 `$Resilient` 类缺失或过期。

- **IntelliJ IDEA：** 对于 Gradle 和 Maven 项目，IDEA 从构建中读取处理器路径。如果使用 IDEA 自己的编译器构建，请启用 *Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing*，或者让 Gradle 构建项目（*Build and run using: Gradle*）。
- **Eclipse：** 必须为项目启用注解处理（Gradle 用 Buildship，Maven 用 m2e 的注解处理支持）。
- 修改注解之后，请重新构建模块：注解处理器在编译带注解的接口时写出生成的源代码。

各 IDE 的说明参见[安装](../start/installation.md)。

### 同一构建中的 Lombok、MapStruct 或其他注解处理器 :id=other-processors

resilience4cata 的注解处理器可以与其他注解处理器一起运行。把它们全部放在同一个处理器路径上：

- **Gradle：** 每个注解处理器一行 `annotationProcessor`（`org.projectlombok:lombok`、`org.mapstruct:mapstruct-processor`、`dev.resilience4cata:resilience4cata-processor`）。
- **Maven：** 一旦设置了 `<annotationProcessorPaths>`，Maven 就只运行其中列出的内容，因此也要在那里列出 Lombok 和 MapStruct。
- 顺序无关紧要：使用 Lombok 的[带注解的类](annotated-classes.md#with-lombok)会在 Lombok 修改它之后被处理。在 1.0.0 之前，如果 resilience4cata 在前，使用 `@RequiredArgsConstructor` 的带注解类会编译失败，报出类似 `constructor … in class … cannot be applied to given types` 的错误；请升级，或者把 Lombok 放在前面。

注解处理器只读取带有弹性注解的接口和类，并且在存在其他注解处理器时只认领它自己的注解，因此不会对它们隐藏注解。

### `-Xlint:all -Werror` 失败，提示 "No processor claimed any of these annotations" :id=xlint-processing

这不是 resilience4cata 本身造成的：单独在处理器路径上时，它认领它看到的注解；与其他注解处理器一起时，它只认领 `dev.resilience4cata.annotation.*`。如果警告列出了其他注解，那是另一个注解处理器没有认领它们；请在编译器参数中添加 `-Xlint:-processing`，或在那个注解处理器中修复。

### `-Werror` 因生成代码中的 `auxiliaryclass` 警告而失败 :id=auxiliary-class

在另一个类型的源文件中声明为第二个非 public 顶层类型的带注解接口（`Clients.java` 中同时有 `public class Clients` 和 `interface PriceClient`）是一个*辅助类*（auxiliary class）。生成的 `PriceClient$Resilient` 位于自己的文件中并引用它，因此 `javac -Xlint:all` 会警告 `auxiliary class PriceClient in Clients.java should not be accessed from outside its own source file`，而 `-Werror` 把它变成错误。请把接口移到它自己的文件（`PriceClient.java`）中，或者把它嵌套为该类的成员类型。

### 来自注解处理器的编译错误 :id=compile-errors

注解处理器在编译时检查注解，并在带注解的元素上报告 `R4C-APT`、`R4C-ATR`、`R4C-CMP`、`R4C-ASY`、`R4C-CLS`、`R4C-FB` 和 `R4C-SUB` 错误。常见的有：

| 症状 | 代码 |
|---|---|
| 注解在 `static` 或 `private` 方法上 | [R4C-APT-002](../errors.md#r4c-apt-002) |
| 注解在 private 接口上 | [R4C-APT-003](../errors.md#r4c-apt-003) |
| `"2 s"` 或 `"1.5s"` 这样的时长 | [R4C-ATR-001](../errors.md#r4c-atr-001) |
| `@Timeout("0")` | [R4C-ATR-002](../errors.md#r4c-atr-002) |
| 异步方法返回的不是 `CompletionStage<T>` / `CompletableFuture<T>`（例如 `? super T`） | [R4C-ASY-001](../errors.md#r4c-asy-001) |
| 降级方法的签名不匹配 | [R4C-FB-003](../errors.md#r4c-fb-003) |
| 带注解的类是 `final` | [R4C-SUB-001](../errors.md#r4c-sub-001) |

## 行为 :id=behaviour

### 我的实现内部的调用没有被重试 :id=self-invocation

使用 `Resilient.of`（接口风格）时，策略位于生成的包装器中，由包装器调用你的实现。当实现调用它自己的某个方法时，该调用不会经过包装器：

<!-- snippet: troubleshooting-self-invocation-api -->
```java
public interface CatalogApi {

    @Retry(maxAttempts = 3, delay = "0")
    int price(String sku);

    int total(List<String> skus); // not annotated
}
```

<!-- snippet: troubleshooting-self-invocation -->
```java
final class HttpCatalog implements CatalogApi {

    @Override
    public int price(String sku) {
        return fetchPrice(sku); // may fail
    }

    @Override
    public int total(List<String> skus) {
        // this.price(...) calls the implementation directly, not the generated wrapper: no retry here
        return skus.stream().mapToInt(this::price).sum();
    }

    private int failuresLeft = 1; // a flaky remote call: fails once

    private int fetchPrice(String sku) {
        if (failuresLeft-- > 0) {
            throw new IllegalStateException("flaky");
        }
        return 10;
    }
}
```

`catalog.price("a")` 会被重试；`catalog.total(List.of("a"))` 在第一个错误时就失败，因为 `total` 在实现上调用了 `this.price`。这是接口风格的特性，不是 bug，也不会被规避。要拦截自调用，请改为给类加注解：生成的子类会覆盖这些方法，因此 `this.price(…)` 会经过策略。参见[带注解的类](annotated-classes.md)。

### 实现类上的注解不起作用 :id=implementation-annotations

`Resilient.of(Interface.class, impl, registry)` 只使用接口（及其父接口）的注解。包装器不读取实现类上的注解。给类加注解会把它变成一个[带注解的类](annotated-classes.md)：注解处理器为它生成一个子类，你用 `Resilient.create(Type.class, registry, args…)` 或 `new Type$Resilient(registry, args…)` 创建它，并且该类必须满足带注解类的规则（不能是 `final`、`sealed`、抽象的……参见 [R4C-SUB-001](../errors.md#r4c-sub-001) 到 [R4C-SUB-008](../errors.md#r4c-sub-008)）。每个类型选择一种风格。

### 熔断器一打开重试就停止 :id=open-breaker-stops-retries

这是默认行为，是有意为之的。重试位于熔断器之外（顺序：Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead），而重试从不重试 `CallNotPermittedException`：一旦熔断器拒绝了一次尝试，再重试也只会再次被拒绝。重试以 `GaveUp(reason = NOT_RETRYABLE)` 放弃，并重新抛出 `CallNotPermittedException`（或运行降级）。

无论 `abortOn` 和 `retryOn` 怎么设置，使用分类器时也一样，这一点都成立；它不是 `abortOn` 的一个条目，因此设置你自己的列表并不会移除它。参见[重试](../policies/retry.md)和[熔断器](../policies/circuit-breaker.md)。

### 熔断器在启动后立即打开 :id=breaker-opens-at-startup

如果依赖是健康的，但熔断器在部署后的最初几秒内打开，请查看服务自身的 CPU。冷启动期间 JIT 编译热点代码，调用比之后慢；在严格的容器 CPU 限制（Kubernetes `limits.cpu`）下，cgroup 配额会在每个 100 ms 周期的一部分时间内冻结整个 JVM。此时调用确实会超过它们的 `@Timeout`，熔断器把每个 `CallTimeoutException` 都算作依赖的失败：它无法区分慢的后端和慢的进程。一旦 `waitInOpen` 过去、服务完成预热，它会再次关闭。

试点（pilot）测量过这一点：限制为半个核心时，Spring Boot、Micronaut 和 Quarkus 服务在第一分钟里有 3 到 36 % 的订单由打开的熔断器应答；限制为 1 个核心时只有 Spring 出现这种情况，为 2.5 %；不加限制时都没有（[基准测试：CPU 限制下的服务](../benchmarks.md#cpu-limit)）。

修复方法：在启动时给服务留出 CPU 余量（更高的限制，或者不设限制，许多团队只设置 `requests`），或者在接收流量之前进行预热（一个等待若干次预热调用的就绪探针）。满的舱壁隔离不会打开熔断器：它的拒绝从不被记录（[熔断器](../policies/circuit-breaker.md#with-other-policies)）。

### 超时没有停止我的代码 :id=timeout-not-interrupting

同步 `@Timeout` 在调用方线程上运行调用，并在时间到时**中断**它。忽略中断的代码会一直运行，直到它自己返回，之后调用方才会收到 `CallTimeoutException`：

- 经典的阻塞式 `java.net.Socket` / `InputStream` 读取，以及基于它们构建的库；
- 从不检查 `Thread.interrupted()` 的忙循环；
- 捕获 `InterruptedException` 后继续执行的代码。

修复方法：设置客户端本身的 I/O 超时（连接和读取超时；`java.net.http.HttpClient`、NIO 通道和大多数现代客户端会响应中断或拥有自己的超时），在长循环中检查 `Thread.interrupted()`，并在捕获 `InterruptedException` 时恢复中断标志。对于异步方法，返回的 stage 会按时以 `CallTimeoutException` 失败，如果调用的 stage 是 `Future` 则会被取消；其背后的工作是否停止取决于那段代码。

超时在设计上也是粗粒度的：从不提前，在进程获得 CPU 时最多延迟约两个 watchdog tick（一个 tick 是正在使用的最短超时 / 16，介于 1 到 50 ms 之间）；在容器 CPU 限制下，被节流的 JVM 只有在再次运行时才会被中断。参见[超时](../policies/timeout.md)。

### 我的外部配置没有生效 :id=config-not-applied

按顺序检查：

1. **该值被优先级更高的东西覆盖了。** 从低到高：内置默认值、`defaults.*`、`configs.<name>.*`、注解属性、`instances.<id>.*`。`defaults.*` 键不会改变注解显式设置的属性；要改变它请使用 `instances.<id>.*`。参见[优先级](../reference/configuration.md#precedence)。
2. **该来源没有被读取。** `ResilienceConfig.load()` 只读取类路径资源 `resilience4cata.properties`、环境变量（`R4C_…`）和系统属性，**且需带有 `r4c.` 或 `resilience4cata.` 前缀**；其他文件需要 `ResilienceConfig.builder().file(…)` 或 `.classpath(…)`。
3. **配置没有应用到注册表。** 用 `config.applyTo(ResilienceRegistry.builder()).build()`（或 `config.toRegistry()`）构建的注册表来包装你的服务。
4. **实例 id 不匹配。** id 不匹配任何东西的 `instances.<id>` 键会被静默忽略，直到你去检查。`config.describe()` 列出每个生效的值及其来源；`config.verifyInstances(registry)` 在包装完服务之后调用，会对不匹配任何实例的 id 报错，并建议最接近的一个：

<!-- snippet: troubleshooting-verify-instances -->
```java
ResilienceConfig config = ResilienceConfig.builder().properties("app.properties", properties).build();
System.out.println(config.describe()); // every effective value and where it comes from
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
Payments payments = Resilient.of(Payments.class, order -> "ok", registry);
config.verifyInstances(registry); // after every service is wrapped: fails on ids that match nothing
```

派生的 id 形如 `com.acme.Api#get(java.lang.String)`，在键中必须加引号：`instances."com.acme.Api#get(java.lang.String)".timeout.value=2s`。参见 [R4C-CFG-013](../errors.md#r4c-cfg-013) 和[配置键](../reference/configuration.md)。

5. **你期望重新加载。** 配置只在构建注册表及其实例时读取一次；没有热重载。

### `registry.circuitBreaker(id)` 抛出 `IllegalArgumentException [R4C-REG-009]` :id=handle-not-found

id 错了，或者实例尚不存在：实例在包装接口（`Resilient.of`、`Resilient.create`）或构建 `Pipeline` 时创建。消息会建议最接近的 id。参见 [R4C-REG-009](../errors.md#r4c-reg-009)。

### 包装时抛出 `IllegalStateException [R4C-REG-008]` :id=shared-name

两个方法共享一个 `name`（或 pipeline id），但解析为不同的配置。共享实例的方法必须在每个属性上一致；给它们不同的名称，或相同的属性。参见 [R4C-REG-008](../errors.md#r4c-reg-008)。
