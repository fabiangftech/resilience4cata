# Quarkus

在 Quarkus 应用中使用 resilience4cata：带注解的 bean 由生成的代码保护，注册表从 `application.properties` 配置，SmallRye Health 和 Micrometer 能够看到它。

扩展 `resilience4cata-quarkus`（自 0.5.0 起提供）是一个常规的 Quarkus 扩展，包含一个 runtime 模块和一个 deployment 模块。它不会在调用路径上添加任何东西：没有 CDI 拦截器。它的构建步骤让注解处理器生成的类成为你的应用得到的 bean，因此一次调用的开销与不使用 Quarkus 时相同，当 bean 是 normal-scoped 时再加上 ArC 的客户端代理。

**要求：** Quarkus 3.40 或更新版本（该扩展针对当前 LTS 线 3.40.1 编译并在其上测试）和 Java 21。

## 安装 :id=install

添加该扩展，并且与任何使用注解的场景一样，添加注解处理器（[安装](../start/installation.md)）。该扩展会带入 `resilience4cata-core` 和 `resilience4cata-config`；Quarkus 会自行找到它的 deployment 模块。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation enforcedPlatform("io.quarkus.platform:quarkus-bom:${quarkusPlatformVersion}")
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.0.0-RC4')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.0.0-RC4')

    implementation 'dev.resilience4cata:resilience4cata-quarkus'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-micrometer'   // optional: metrics, with quarkus-micrometer
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'  // optional: ManualClock
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:${quarkusPlatformVersion}"))
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.0.0-RC4")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-quarkus")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-micrometer")   // optional: metrics, with quarkus-micrometer
    testImplementation("dev.resilience4cata:resilience4cata-testkit")  // optional: ManualClock
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement>, next to the Quarkus BOM -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-quarkus</artifactId>
</dependency>
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-annotations</artifactId>
  <scope>provided</scope>
</dependency>

<!-- in the maven-compiler-plugin configuration -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0-RC4</version>
  </path>
  <!-- annotationProcessorPaths replaces discovery: list the other processors you use too -->
</annotationProcessorPaths>
```

<!-- tabs:end -->

> 没有注解处理器就不会生成任何东西，因此对于每一个类或接口带有 resilience4cata 注解但没有生成类的 bean，构建都会以 [`R4C-QK-008`](../errors.md#r4c-qk-008) 失败，而不是让它在没有保护的情况下运行。`quarkus.resilience4cata.allow-missing-processor=true` 可以关闭它。开启扩展的调试日志（`quarkus.log.category."dev.resilience4cata".level=DEBUG`）后，构建会列出它装配的每个 bean。

生成的类与 bean 一样在应用的索引中查找：包含带注解类的库 jar 需要一个 Jandex 索引（或 `quarkus.index-dependency`），与它的 bean 一样。

## 带注解的类 :id=classes

给 bean 类本身加注解：

<!-- snippet: quarkus-class -->
```java
@ApplicationScoped
public class OrderService {

    @Inject
    OrderRepository orders;

    @Retry(maxAttempts = 3, delay = "100ms")
    @CircuitBreaker(name = "orders")
    public Order find(String id) {
        return orders.load(id);
    }

    public Order findAgain(String id) {
        return find(id); // a call on this: still retried
    }
}
```

注解处理器生成 `OrderService$Resilient extends OrderService`。扩展会否决（veto）`OrderService`，并让 `OrderService$Resilient` 取而代之成为 bean，带有该类的作用域、限定符和构造型（stereotype）。因此：

- `@Inject OrderService` 得到的是生成的类（与任何 `@ApplicationScoped` bean 一样位于 ArC 的客户端代理之后）；限定符、`@Named`、`@Alternative`/`@Priority` 和 `@Startup` 继续有效；
- 对 `this` 的调用（`findAgain` 调用 `find`）会经过策略，这与 SmallRye Fault Tolerance 之类的 CDI 拦截器不同；
- 字段注入、初始化方法、`@PostConstruct` 和观察者方法会被继承；构造函数注入同样可以工作：生成的构造函数接收 `ResilienceRegistry`，后面是原来的参数，并保留它们的注解（[带注解的类](annotated-classes.md#constructor-injection)）。

**normal-scoped 的类需要一个无参构造函数。** `@ApplicationScoped`、`@RequestScoped` 和 `@SessionScoped` bean 通过客户端代理访问，而客户端代理需要一个非 private 的无参构造函数。Quarkus 会给没有这种构造函数的类添加一个，但不会给这种类生成的子类添加，因此构建会以 [`R4C-QK-005`](../errors.md#r4c-qk-005) 失败。请像上面那样使用字段注入，添加一个包级私有的无参构造函数（只有代理使用它），或者把 bean 改为 `@Singleton`。

带有 `@Produces` 方法或字段的类无法被替换（producer 不会被继承）：[`R4C-QK-004`](../errors.md#r4c-qk-004)。

**类之上的拦截器。** CDI 拦截器（`@Transactional`、`@Logged`……）由生成类的一个 ArC 子类应用，因此它们在策略之外运行。对于事务来说，这意味着所有尝试共用一个事务，并且在一次失败的尝试将其标记为 rollback-only 之后，后续成功的尝试无法提交。因此，一个同时被重试或对冲且带 `@Transactional`（其类型会加入或开启事务）的方法会以 [`R4C-QK-002`](../errors.md#r4c-qk-002) **使构建失败**。请改为在接口上声明策略（每次尝试一个事务，见下文），把 `@Transactional` 移到该方法调用的另一个 bean 上，或者如果你想要的就是一个共享的事务，设置 `quarkus.resilience4cata.allow-retry-inside-transaction=true`。

## 带注解的接口 :id=interfaces

<!-- snippet: quarkus-interface -->
```java
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface PaymentsGateway {

    String charge(String order, long cents);
}
```

<!-- snippet: quarkus-interface-bean -->
```java
@ApplicationScoped
public class CardPayments implements PaymentsGateway {

    @Override
    public String charge(String order, long cents) {
        return "receipt-" + order; // a call to the payment provider in a real application
    }
}
```

对于每个实现了带注解接口的 bean，扩展会注册一个具有该接口类型和该 bean 限定符的合成 bean，由 `Resilient.of(PaymentsGateway.class, bean, registry)` 创建。它是一个高优先级的已启用 alternative，因此在注入该接口的任何地方它都会胜出：

<!-- snippet: quarkus-inject-interface -->
```java
@ApplicationScoped
public class Checkout {

    @Inject
    PaymentsGateway payments; // the interface: the wrapper PaymentsGateway$Resilient around CardPayments

    public String pay(String order) {
        return payments.charge(order, 1999);
    }
}
```

- `@Inject CardPayments`（类）仍然得到 bean 本身，没有保护。
- `@ApplicationScoped` bean 的包装器也是 `@ApplicationScoped`，位于它自己的客户端代理之后，因此该接口仍是一个 normal-scoped bean（测试中在接口上使用 `@InjectMock` 可以工作，并会替换包装器，连同策略一起）。request 或 session 作用域 bean 的包装器是一个持有该 bean 客户端代理的 `@Singleton`（这些作用域继续有效）。两者都在启动时创建；`@Dependent` bean 的包装器也是 `@Dependent`。
- bean 的 CDI 拦截器在策略**之内**运行：被重试的 `@Transactional` 方法每次尝试获得一个事务。
- `CardPayments` 内部对 `this` 的调用不受保护（包装器位于对象之外）。
- 实现了两个互不继承的带注解接口的 bean 会以 [`R4C-QK-003`](../errors.md#r4c-qk-003) 使构建失败。producer 方法不会被包装：请自己在其中返回 `Resilient.of(...)`。

## REST 客户端 :id=rest-client

像其他接口一样给 REST 客户端接口加注解：

<!-- snippet: quarkus-rest-client -->
```java
@RegisterRestClient(configKey = "stock")
@Path("/stock")
@Retry(maxAttempts = 3, delay = "50ms")
public interface StockClient {

    @GET
    @Path("/{sku}")
    int available(@PathParam("sku") String sku);
}
```

REST 客户端 bean 带有 `@RestClient` 限定符。扩展会在它外面添加一个 `@Default` bean，因此不带限定符注入该接口得到的是受保护的客户端，而 `@RestClient` 仍然得到原始的客户端：

<!-- snippet: quarkus-inject-rest-client -->
```java
@ApplicationScoped
public class StockReport {

    @Inject
    StockClient stock; // no @RestClient: the protected client

    public String line(String sku) {
        return sku + ": " + stock.available(sku);
    }
}
```

## 响应式 bean :id=reactive

返回 Reactor 的 `Mono` 或 `Flux` 的 bean 方法以同样的方式受到保护，前提是类路径上有 `resilience4cata-reactor`（它不属于 `resilience4cata-all`：[安装](../start/installation.md)）。每次订阅都是经过策略的一次调用；对于 `Flux`，重试和降级只在第一个元素之前起作用，`@Timeout` 是没有元素的时间（[Project Reactor](reactor.md)）。不支持 Mutiny 的 `Uni` 和 `Multi`（[R4C-ASY-002](../errors.md#r4c-asy-002)）：请在实现中进行转换（`uni.subscribeAsCompletionStage()`）。

<!-- snippet: quarkus-reactive -->
```java
@ApplicationScoped
public class PriceService {

    @Inject
    PriceBackend backend;

    @Retry(maxAttempts = 3, delay = "10ms")
    @Timeout("2s")
    public Mono<Integer> price(String sku) {
        return backend.price(sku);
    }

    @Retry(maxAttempts = 3, delay = "10ms")
    @Timeout("2s")                                  // to the first element, and between two
    @Fallback(method = "noTicks")
    public Flux<Integer> ticks(String sku) {
        return backend.ticks(sku);
    }

    protected Flux<Integer> noTicks(String sku) {
        return Flux.empty();
    }
}
```

Quarkus REST 不会订阅 Reactor 的类型：返回 `Mono` 的端点会把该对象作为实体写出，调用永远不会运行。请在资源中转换为 Mutiny；Mutiny 接受 JDK 的 `Flow.Publisher`，由 Reactor 的 `JdkFlowAdapter` 提供：

<!-- snippet: quarkus-reactive-endpoint -->
```java
@GET
@Path("/quotes/{sku}")
@Produces(MediaType.TEXT_PLAIN)
public Uni<String> quote(@PathParam("sku") String sku) {
    return Uni.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(quotes.quote(sku)));
}

@GET
@Path("/ticks/{sku}")
@Produces(MediaType.SERVER_SENT_EVENTS)
public Multi<String> ticks(@PathParam("sku") String sku) {
    return Multi.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(quotes.ticks(sku)));
}
```

- **`Uni` 的失败**与其他失败一样经过你的 `@ServerExceptionMapper`（例如把 `CallTimeoutException` 映射为 504）。server-sent events 流在第一个元素之前就已发送了它的头部，因此失败（即使发生在第一个事件之前）会通过关闭连接来结束该流。
- **断开连接的客户端**会经由每个策略取消调用，在流的第一个事件之前也一样：委托的 `Mono` 或 `Flux` 被取消，舱壁隔离释放它的槽位。

带注解类中被重试或对冲的 `Mono` 或 `Flux` 方法外面的 `jakarta.transaction.Transactional` 会使构建失败，与其他方法相同（[R4C-QK-002](../errors.md#r4c-qk-002)）。

## 配置 :id=configuration

策略读取应用 `resilience4cata.`（或 `r4c.`）下的配置，使用[配置参考](../reference/configuration.md)中的键，camelCase 或 kebab-case 均可：

<!-- snippet: quarkus-properties -->
```properties
resilience4cata.defaults.retry.delay=50ms
resilience4cata.defaults.circuit-breaker.minimum-calls=20
resilience4cata.configs.slow.retry.delay=500ms
resilience4cata.instances.orders.circuit-breaker.failure-rate-threshold=25
resilience4cata.instances."dev.resilience4cata.quarkus.guide.PaymentsGateway#charge(java.lang.String,long)".timeout.value=3s
%dev.resilience4cata.defaults.retry.max-attempts=2
```

- 值来自 SmallRye Config，因此每个 Quarkus 来源都可以使用：`application.properties`、YAML（`quarkus-config-yaml`）、`.env`、系统属性、`-D` 以及表达式（`${…}`）。
- **Profile 遵循 Quarkus**：`%dev.`、`%test.`、`%prod.` 以及你自己的。resilience4cata 自己的 `profiles.<name>.*` 键也继续有效。
- **优先级遵循 Quarkus**：系统属性高于环境变量，环境变量高于 `application.properties`。环境变量使用 `resilience4cata-config` 的 [`R4C_` 或 `RESILIENCE4CATA_` 名称](configuration.md)（SmallRye 无法把它们映射回 camelCase 键）。类路径上的 `resilience4cata.properties` 文件仍然会被读取，优先级低于以上所有来源。
- 包含字母、数字、`_` 和 `-` 以外字符的**实例 id**（派生的 `<type>#<method>(<parameter types>)`）要放在双引号中，如上所示。
- 与 resilience4cata 的其他地方一样，配置只在启动时读取一次：没有重新加载。
- `quarkus.resilience4cata.` 下的键是扩展自己的[开关](#switches)。

**启动时校验。** 注册表在应用启动时创建，因此未知的键或错误的值会终止应用，并一次性列出所有问题，以及每个键被设置的文件和行号：

```text
ERROR [io.quarkus.runtime.Application] Failed to start application
dev.resilience4cata.config.ConfigException: Invalid resilience4cata configuration (1 problem):
  [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.maxAtempts' (application.properties:2). Did you mean 'resilience4cata.defaults.retry.maxAttempts'? ...
```

启动时还会把 `resilience4cata.instances.<id>.*` 键与到目前为止创建的策略实例进行核对（[`R4C-CFG-013`](../errors.md#r4c-cfg-013)）。Quarkus 延迟创建 bean，因此默认是警告（`quarkus.resilience4cata.verify-instances=warn`）：接口的包装器在启动时创建，但带注解的类只在第一次使用时创建，除非它们是 `@Startup` bean。当每个受保护的类都在启动时创建时，请设置为 `fail`。

## Customizer 和监听器 :id=beans

`ResilienceRegistryCustomizer` bean 可以调整注册表 builder，例如在代码中定义一个命名配置：

<!-- snippet: quarkus-customizer -->
```java
@Singleton
public class ResilienceSetup implements ResilienceRegistryCustomizer {

    @Override
    public void customize(ResilienceRegistry.Builder builder) {
        builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
    }
}
```

Customizer 按 `@Priority` 顺序运行，在属性之前，因此属性优先：上面的 `resilience4cata.configs.slow.retry.delay` 会覆盖 customizer 的延迟，并保留它的 `maxAttempts`。

每个 `EventListener` bean 都会按其 bean 类型中的事件类型注册（原始类型的监听器会收到所有事件）：

<!-- snippet: quarkus-listener -->
```java
@Produces
@Singleton
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.id() + " is now " + transition.to());
}
```

`Ticker` bean（testkit 的 `ManualClock`）会成为注册表的时钟。产生了自己的 `ResilienceRegistry` bean 的应用会替换扩展的那个（此时不会应用 customizer、监听器和指标）；`ResilienceConfig` bean 仍然存在，可以用 `config.applyTo(builder)` 构建它。

## 指标和健康检查 :id=observability

- **指标**：有 `quarkus-micrometer`（以及 `quarkus-micrometer-registry-prometheus` 这样的注册表）和 `resilience4cata-micrometer` 时，[Micrometer meter](micrometer.md) 会自动注册并出现在 `/q/metrics` 上。没有 Micrometer 但有 `quarkus-opentelemetry` 和 `resilience4cata-opentelemetry` 时，改为注册 [OpenTelemetry 指标](opentelemetry.md)（两者都存在时优先使用 Micrometer，与 SmallRye Fault Tolerance 的做法相同）。成功的调用不发布事件，因此没有开销。
- **健康检查**：有 `quarkus-smallrye-health` 时，`resilience4cata` 就绪检查（`/q/health/ready`）报告每个熔断器的状态和失败率。熔断器打开时它仍保持 **UP**：打开的熔断器是应用在保护自己免受失败依赖的影响，而不是把它移出负载均衡的理由。把应该让它变为 DOWN 的熔断器列在 `quarkus.resilience4cata.health.down-when-open` 中（`*` 表示全部）。
- 没有 HTTP 端点（在 dev 模式下有 [Dev UI 卡片](#dev-ui)）：如果你想暴露，`registry.snapshot().toMap()` 包含了一切。

## Dev UI :id=dev-ui

在 dev 模式下（`quarkus dev` 或 `./gradlew quarkusDev`），`/q/dev-ui` 上的 Dev UI 有一张 resilience4cata 卡片。它的 *Policies* 页面列出注册表到目前为止创建的每个策略实例，在页面可见时每两秒刷新一次：

| 策略 | 页面显示的内容 |
|---|---|
| 熔断器 | 状态、失败率和慢调用率、窗口中的调用（失败的、慢的） |
| 舱壁隔离 | 并发调用与最大值的对比、等待中的调用 |
| 限流器 | 可用许可与每周期许可数的对比 |
| 自适应限流 | 算法、当前限制及其边界、进行中的调用、最近一个窗口的延迟和长期延迟 |
| 重试、对冲请求 | 最大尝试次数（及延迟），设置了预算时还有预算的调用数以及允许的重试或对冲请求数 |
| 超时 | 超时时间 |

大多数实例在第一次调用其方法时创建（带注解类的实例在创建 bean 时创建），因此策略在被使用之后才会出现。尚无意义的值，例如达到 `minimumCalls` 之前的失败率，显示为短横线。

该页面还可以强制打开、禁用或重置熔断器，与 `registry.circuitBreaker(id)` 的作用相同：便于查看依赖宕机时应用的表现。卡片上的 *Guide* 链接会打开本节。

这一切在 dev 模式之外都不存在：添加卡片及其 JsonRPC 服务的构建步骤只在本地开发时运行，因此测试或生产构建中两者都没有。

## SmallRye Fault Tolerance 与 resilience4cata :id=smallrye

Quarkus 自带容错方案 [SmallRye Fault Tolerance](https://quarkus.io/guides/smallrye-fault-tolerance)（`quarkus-smallrye-fault-tolerance`，MicroProfile Fault Tolerance）。两者可以在同一个应用中工作，但不能用于同一个方法。

当你想要内置的标准选项时，**选择 SmallRye Fault Tolerance**：MicroProfile 可移植性、`@Asynchronous` 和 Mutiny 返回类型、可以在运行时修改的配置，以及无需向构建中添加任何东西。

当你希望同一份代码在 CDI 之外也能工作（纯 Java、无容器的测试、Spring Boot），希望注解和配置在编译时和启动时以带代码的错误进行检查，需要命名配置、组合注解、按实例覆盖、分类器和对冲请求、在带注解的类中保护对 `this` 的调用，或者看重它的[开销](../performance.md)时，**选择 resilience4cata**。

**机制。** SmallRye Fault Tolerance 是一个 CDI 拦截器；resilience4cata 是由扩展变成 bean 的生成代码。两者都不保护非 CDI 创建的 `new CardPayments()`。

**导入。** 两个库都把它们的注解命名为 `@Retry`、`@CircuitBreaker`、`@Timeout`、`@Bulkhead` 和 `@Fallback`，而 IDE 会导入第一个匹配项。同时带有两者注解的方法（或类）在安装了 SmallRye Fault Tolerance 时会以 [`R4C-QK-001`](../errors.md#r4c-qk-001) 使构建失败（否则为警告）。每个模块最好只用一个库，或者使用 `@ExternalCall` 这样的[组合注解](bundles.md)，让这些简单名称根本不出现。

**对应关系。** 默认值也不同：请查看[配置参考](../reference/configuration.md)。

| SmallRye / MicroProfile Fault Tolerance | resilience4cata |
|---|---|
| `@Retry(maxRetries = 3)`（第一次调用之后的重试次数） | `@Retry(maxAttempts = 4)`（尝试次数，包括第一次） |
| `@Retry(delay = 200, delayUnit = ChronoUnit.MILLIS)` | `@Retry(delay = "200ms", backoff = Backoff.FIXED)`（默认退避是指数的） |
| `@Retry(jitter = 200)`（± 一个时长） | `@Retry(jitter = Jitter.FULL)`（`NONE`、`FULL` 或 `EQUAL`，每个延迟的一部分） |
| `@Retry(maxDuration = 180000)` | `@Retry(maxDuration = "3m")` |
| `@Retry(retryOn = …, abortOn = …)` | `@Retry(retryOn = …, abortOn = …)` |
| `@ExponentialBackoff` | `@Retry(backoff = Backoff.EXPONENTIAL, multiplier = 2, maxDelay = "10s")` |
| `@CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5)` | `@CircuitBreaker(windowSize = 20, minimumCalls = 20, failureRateThreshold = 50)`（百分比） |
| `@CircuitBreaker(delay = 5000)` | `@CircuitBreaker(waitInOpen = "5s")` |
| `@CircuitBreaker(successThreshold = 1)` | `@CircuitBreaker(halfOpenCalls = 1)` |
| `@CircuitBreaker(failOn = …, skipOn = …)` | `@CircuitBreaker(recordOn = …, ignoreOn = …)` |
| `@Timeout(value = 2, unit = ChronoUnit.SECONDS)` | `@Timeout("2s")` |
| `@Bulkhead(10)` | `@Bulkhead(maxConcurrent = 10)` |
| 与 `@Asynchronous` 一起使用的 `@Bulkhead(value = 10, waitingTaskQueue = 10)` | `@Bulkhead(maxConcurrent = 10, maxWait = "…")`（等待一段时间，同步或异步） |
| `@RateLimit(value = 100, window = 1, windowUnit = ChronoUnit.SECONDS)` | `@RateLimiter(permits = 100, period = "1s")` |
| `@Fallback(fallbackMethod = "cached")` | `@Fallback(method = "cached")` |
| `@Fallback(MyHandler.class)` | `@Fallback(MyHandler.class)`（一个 `FallbackHandler`） |
| 与 `CompletionStage` 一起使用的 `@Asynchronous` | 返回类型为 `CompletionStage`/`CompletableFuture` 就足够了 |
| `quarkus.fault-tolerance."<id>".retry.max-retries=5` | `resilience4cata.instances.<id>.retry.maxAttempts=6` |
| `quarkus.fault-tolerance.global.retry.max-retries=5` | `resilience4cata.defaults.retry.maxAttempts=6` |

SmallRye 允许在运行时修改值，这一点没有对应物：resilience4cata 在设计上只解析一次配置。

## 开关 :id=switches

| 属性 | 阶段 | 默认值 | 作用 |
|---|---|---|---|
| `quarkus.resilience4cata.beans.enabled` | 构建 | `true` | 让生成的类成为 bean（子类替换带注解的类，包装器代替接口 bean） |
| `quarkus.resilience4cata.beans.exclude` | 构建 | 空 | 不做处理的 bean 类（全限定名） |
| `quarkus.resilience4cata.allow-retry-inside-transaction` | 构建 | `false` | 允许在带注解类的 `@Transactional` 方法上使用 `@Retry`/`@Hedge`，而不是以 R4C-QK-002 失败 |
| `quarkus.resilience4cata.allow-missing-processor` | 构建 | `false` | 即使某个 bean 带有 resilience4cata 注解但没有生成类（其策略不会运行）也照常构建，而不是以 R4C-QK-008 失败 |
| `quarkus.resilience4cata.health.enabled` | 构建 | `true` | 存在 SmallRye Health 时的就绪检查 |
| `quarkus.resilience4cata.metrics.enabled` | 构建 | `true` | 把注册表绑定到 Micrometer 或 OpenTelemetry |
| `quarkus.resilience4cata.verify-instances` | 运行时 | `warn` | 对启动时不匹配任何东西的实例键采取 `fail`、`warn` 或 `off` |
| `quarkus.resilience4cata.health.down-when-open` | 运行时 | 空 | 打开时让就绪检查变为 DOWN 的熔断器 id（或 `*`） |

## 测试 :id=testing

在测试源码中产生一个 `ManualClock` bean：重试暂停、舱壁隔离和限流器的等待以及超时都会跟随它，因此测试从不真正睡眠（[测试指南](testing.md)）。

<!-- snippet: quarkus-test-clock -->
```java
@Singleton
public class TestClock {

    @Produces
    @Singleton
    ManualClock clock() {
        return new ManualClock(); // retry pauses, waits and timeouts follow it instead of the system clock
    }
}
```

<!-- snippet: quarkus-test -->
```java
@Inject
OrderService orders;

@Inject
ManualClock clock;

@Inject
OrderRepository repository; // fails the first loads it is told to

@Test
void retriesWithoutSleeping() {
    repository.failNext(2);
    long before = clock.elapsed().toNanos();

    assertThat(orders.find("42")).isEqualTo(new Order("42"));
    assertThat(clock.elapsed().toNanos()).isGreaterThan(before); // the two pauses, on the manual clock
}
```

normal-scoped bean 以客户端代理的形式注入：请通过方法读取和修改它的状态，永远不要通过它的字段。

## 原生镜像 :id=native

注解处理器为每个生成类写出 GraalVM 可达性元数据，并且 `core` 在运行时初始化它的后台线程，因此原生构建（`-Dquarkus.native.enabled=true`）不需要额外配置：带注解的类由 ArC 生成的代码实例化，接口包装器由 `Resilient.of` 实例化，它通过这些元数据找到它们。配置在运行时读取。本项目的 CI 把本指南的应用构建为原生镜像（GraalVM for JDK 25），并通过 HTTP 进行测试：一个带注解的类、接口和 REST 客户端包装器、就绪检查、指标，以及通过 `Uni` 和 `Multi` 提供的 `Mono` 和 `Flux` 方法（[响应式 bean](#reactive)）。

## 限制 :id=limits

- dev 模式：实时重载是否会重新运行注解处理器尚未检查。如果对注解的修改似乎被忽略了，请重启 dev 模式。
- normal-scoped 的带注解类需要一个无参构造函数（或使用字段注入）；带注解的类不能声明 producer。
- 接口包装器：实现内部对 `this` 的调用不受保护；每个 bean 一个带注解的接口；producer 方法不会被包装。
- 使用带注解的类时，`@Transactional` 这样的 CDI 拦截器在策略之外运行；被重试或对冲的事务方法会使构建失败（[`R4C-QK-002`](../errors.md#r4c-qk-002)），除非显式允许。
- 没有 HTTP 端点（只有 dev 模式下的 [Dev UI 卡片](#dev-ui)），配置没有热重载，没有按调用的指标。
