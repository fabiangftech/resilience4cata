# Spring Boot

在 Spring Boot 4 应用中使用 resilience4cata：带注解的 bean 由生成的代码保护，注册表从 `application.yml` 配置，Actuator 获得一个健康指示器、一个端点和指标。

模块 `resilience4cata-spring`（自 0.4.0 起提供）是一个 Spring Boot 自动配置。它不会在调用路径上添加任何东西：没有代理、切面或拦截器。受保护的 bean *就是*注解处理器生成的那个类，因此一次调用的开销与不使用 Spring 时相同。

**要求：** Spring Boot 4.1 或更新版本（在最旧的受支持版本 4.1.0 以及最新的 4.1 版本上测试）和 Java 21。不支持 Spring Boot 3。

## 安装 :id=install

添加该模块，并且与任何使用注解的场景一样，添加注解处理器（[安装](../start/installation.md)）。该模块会带入 `resilience4cata-core` 和 `resilience4cata-config`。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')

    implementation 'dev.resilience4cata:resilience4cata-spring'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-micrometer'   // optional: metrics
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'  // optional: ManualClock
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-spring")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-micrometer")   // optional: metrics
    testImplementation("dev.resilience4cata:resilience4cata-testkit")  // optional: ManualClock
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-spring</artifactId>
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
    <version>1.1.0</version>
  </path>
  <!-- annotationProcessorPaths replaces discovery: list the other processors you use too,
       for example Spring Boot's configuration processor if you have @ConfigurationProperties.
       Its version comes from Spring Boot's dependency management (maven-compiler-plugin 3.12+;
       older versions of the plugin need a <version>). -->
  <path>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

> 没有注解处理器就不会生成任何东西，因此启动会以 [`R4C-SPR-006`](../errors.md#r4c-spr-006) 失败：一个类或接口带有 resilience4cata 注解但没有生成类的 bean 将在没有保护的情况下运行。该检查读取 bean 类型的类文件（注解的保留策略是 `CLASS`），因此它不会在原生镜像中运行，因为在那里构建已经编译好了一切。`resilience4cata.spring.allow-missing-processor=true` 可以关闭它。

## 带注解的类 :id=classes

给类本身加注解。这是 Spring 中推荐的风格：

<!-- snippet: spring-class -->
```java
@Service
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

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

注解处理器生成 `OrderService$Resilient extends OrderService`，集成会让 Spring 在任何 bean 定义指向 `OrderService` 的地方（组件扫描、`@Import`、`registerBean`）实例化它。因此：

- 该 bean 仍然是一个 `OrderService`：它可以通过它的类、它的接口、`@Qualifier`、`List<…>` 注入，并保留它的名称、作用域和 `@Primary`（在 request 或 session 作用域中，每个请求或会话获得自己的生成类实例，它们都共享命名的策略）；
- 对 `this` 的调用（`findAgain` 调用 `find`）会经过策略，这与 Spring AOP 和 Spring 自己的 `@Retryable` 不同；
- 构造函数注入照常工作：生成的构造函数接收 `ResilienceRegistry`（按类型注入），后面是原来的参数，并保留它们的名称和注解（多个构造函数之一上的 `@Autowired`、`@Qualifier`、`@Value`；参见[带注解的类](annotated-classes.md#constructor-injection)）。字段和 setter 注入以及 `@PostConstruct` 会被继承。

返回 `new OrderService(...)` 的 `@Bean` 方法会产生一个没有保护的 bean，因此启动会以 [`R4C-SPR-001`](../errors.md#r4c-spr-001) 失败：请改为注册该类，或者在方法中返回 `new OrderService$Resilient(registry, ...)`。

**类之上的代理。** 生成的类不是 `final`，因此 Spring 仍然可以在它外面套一个基于类的代理（`@Transactional`、`@Cacheable`、`@Async`、作用域 bean）。代理位于策略之外。对于事务来说，这意味着所有尝试共用一个事务，并且在一次失败的尝试将其标记为 rollback-only 之后，后续成功的尝试会在提交时以 `UnexpectedRollbackException` 失败。因此，一个同时被重试或对冲且带事务的方法（Spring 或 Jakarta 的 `@Transactional`，其传播方式会加入或开启事务）会以 [`R4C-SPR-004`](../errors.md#r4c-spr-004) **终止启动**。修复方法是在 bean 实现的接口上声明策略（接口包装器位于事务代理之外：每次尝试一个事务），或者把 `@Transactional` 移到被重试方法所调用的另一个 bean 上。如果你想要的就是一个共享的事务，请设置 `resilience4cata.spring.allow-retry-inside-transaction=true`。`@CircuitBreaker`、`@Timeout`、`@RateLimiter` 和 `@Bulkhead` 只运行一次调用，不受检查。生成的覆盖方法会重复它所覆盖方法的注解，因此方法级别的 Jakarta `@Transactional`（Spring 只在被调用的方法上读取它）会生效，并与 Spring 的一样受到检查。

## 带注解的接口 :id=interfaces

<!-- snippet: spring-interface -->
```java
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface StockClient {

    int available(String sku);
}
```

<!-- snippet: spring-interface-bean -->
```java
@Component
public class HttpStockClient implements StockClient {

    @Override
    public int available(String sku) {
        return 42; // an HTTP call in a real application
    }
}
```

实现了带注解接口的 bean 在初始化完成后会被替换为 `Resilient.of(StockClient.class, bean, registry)`。请注入接口：

<!-- snippet: spring-inject-interface -->
```java
@Component
public class StockReport {

    private final StockClient stock; // the interface: the bean is StockClient$Resilient

    public StockReport(StockClient stock) {
        this.stock = stock;
    }

    public String line(String sku) {
        return sku + ": " + stock.available(sku);
    }
}
```

- bean 的类型变成包装器，它只实现 `StockClient`：注入 `HttpStockClient` 会失败，与 JDK 代理一样，启动报告会提示你注入接口。
- 包装器在所有其他后处理器之后应用，因此它包住它们的代理：经过 `@Transactional` 代理的被重试调用，每次尝试都会获得一个新事务。
- `HttpStockClient` 内部对 `this` 的调用不受保护（包装器位于对象之外）。
- request 或 session 作用域的 bean 会被包装一次，包在它的作用域代理外面：每次调用都会到达当前请求或会话的实例。
- 实现了两个互不继承的带注解接口的 bean 会以 [`R4C-SPR-003`](../errors.md#r4c-spr-003) 使启动失败。本身已经是生成类的 bean（`@Bean` 方法自己返回 `Resilient.of(...)`）保持原样。
- 应用的某个 `BeanPostProcessor` 所依赖的 bean 会在包装器的后处理器存在之前创建，因此它会保持未包装的状态：启动会以 [`R4C-SPR-005`](../errors.md#r4c-spr-005) 失败。请通过 `ObjectProvider<StockClient>` 或 `@Lazy` 把它注入到那个后处理器中。带注解的类不受影响。

## 响应式 bean :id=reactive

方法返回 Reactor 的 `Mono` 或 `Flux` 的 bean 以同样的方式受到保护，带注解的类和接口都一样，前提是类路径上有 `resilience4cata-reactor`（它不属于 `resilience4cata-all`：[安装](../start/installation.md)）。每次订阅都是经过策略的一次调用；对于 `Flux`，重试和降级只在第一个元素之前起作用，`@Timeout` 是没有元素的时间（[Project Reactor](reactor.md)）。

<!-- snippet: spring-reactive-bean -->
```java
@Retry(name = "ticks", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
@Timeout("300ms")
@Bulkhead(name = "ticks", maxConcurrent = 1)
public Flux<String> ticks(String sku) {
    return backend.ticks(sku);
}
```

在 WebFlux 中，从控制器直接返回 bean 的 `Mono` 或 `Flux`；`Flux` 可以以 server-sent events 的形式流式传输：

<!-- snippet: spring-reactive-endpoint -->
```java
@GetMapping(value = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
Flux<String> ticks(@PathVariable String sku) {
    return quotes.ticks(sku);   // the bean's Flux as it is: server-sent events
}
```

- `WebFilter` 写入的 **Reactor context**（追踪、Spring Security 的 `ReactiveSecurityContextHolder`、请求 id）会到达每一次尝试和降级。
- **第一个元素之前的失败**与其他失败一样经过你的 `@ExceptionHandler`（例如把 `CallTimeoutException` 映射为 504）。流的第一个元素之后响应已经开始，因此失败会改为结束该流。
- **断开连接的客户端**会经由每个策略取消调用：委托的 `Mono` 或 `Flux` 被取消，舱壁隔离释放它的槽位。
- 带注解类中被重试或对冲的 `Mono` 或 `Flux` 方法外面的**响应式 `@Transactional`** 会使启动失败，与其他方法相同（[R4C-SPR-004](../errors.md#r4c-spr-004)）。

## 配置 :id=configuration

策略读取应用 `resilience4cata` 下的属性，使用[配置参考](../reference/configuration.md)中的键，kebab-case 或 camelCase 均可：

<!-- snippet: spring-yaml -->
```yaml
resilience4cata:
  defaults:
    retry:
      delay: 50ms
    circuit-breaker:
      minimum-calls: 20
  configs:
    slow:
      retry.delay: 500ms
  instances:
    orders:
      circuit-breaker.failure-rate-threshold: 25
    "[dev.resilience4cata.spring.guide.StockClient#available(java.lang.String)]":
      timeout.value: 3s
```

- 包含字母、数字、`_` 和 `-` 以外字符的**实例 id**（派生的 `<type>#<method>(<parameter types>)`）要放在方括号中，就像 Spring 书写 map 键那样：YAML 中为 `"[com.acme.StockClient#available(java.lang.String)]"`，`.properties` 中为 `resilience4cata.instances[com.acme.StockClient#available(java.lang.String)].timeout.value=3s`。
- **列表**（`retry-on`、`abort-on`、`record-on`、`ignore-on`）可以是 YAML 列表或逗号分隔的值。
- **占位符**（`${…}`）会被解析。
- **优先级遵循 Spring**：命令行参数和测试属性高于系统属性，系统属性高于环境变量，环境变量高于 `application-<profile>.yml`，后者高于 `application.yml`。环境变量使用 `resilience4cata-config` 的 [`R4C_` 或 `RESILIENCE4CATA_` 名称](configuration.md)。类路径上的 `resilience4cata.properties` 文件仍然会被读取，优先级低于以上所有来源。
- **Profile**：Spring 的 profile 文档（`application-prod.yml`、`spring.config.activate.on-profile`）是自然的方式；resilience4cata 自己的 `profiles.<name>.*` 键也继续有效（`resilience4cata.profiles.active: ${spring.profiles.active:}` 把两者联系起来）。
- 与 resilience4cata 的其他地方一样，配置只在启动时读取一次：没有刷新。

**启动时校验。** 未知的键或错误的值会终止启动，并一次性列出所有问题，以及它们被设置的文件和行号：

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Invalid resilience4cata configuration (1 problem):

    [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts' (class path resource [application.yml] - 4:20). Did you mean 'resilience4cata.defaults.retry.max-attempts'? ...
```

单例创建完成后，每个 `resilience4cata.instances.<id>.*` 键都必须匹配一个策略实例，否则启动会以 [`R4C-CFG-013`](../errors.md#r4c-cfg-013) 失败。如果有延迟加载的 bean 或之后才构建的 pipeline，请设置 `resilience4cata.spring.verify-instances=warn`（在 `spring.main.lazy-initialization=true` 下的默认值）。

该模块为 `defaults` 键和下面的开关提供 Spring Boot 配置元数据，读取它的 IDE 会用它来补全和说明这些键。

## Customizer 和监听器 :id=beans

`ResilienceRegistryCustomizer` bean 可以调整注册表 builder，例如在代码中定义一个命名配置：

<!-- snippet: spring-customizer -->
```java
@Bean
ResilienceRegistryCustomizer slowBackends() {
    return builder -> builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
}
```

Customizer 按 `@Order` 运行，在属性之前，因此属性优先：上面的 `configs.slow.retry.delay` 会覆盖 customizer 的延迟，并保留它的 `maxAttempts`。`defaults.<policy>.*` 键会替换 customizer 对该策略的 `<policy>Defaults(...)`。

每个 `EventListener` bean 都会按其泛型签名中的事件类型注册（原始类型的监听器会收到所有事件）：

<!-- snippet: spring-listener -->
```java
@Bean
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.id() + " is now " + transition.to());
}
```

监听器在引发事件的线程上运行（[事件](observability.md#events)）。监听器 bean 不得依赖受保护的 bean：注册表需要监听器，而受保护的 bean 需要注册表。

`Ticker` bean（testkit 的 `ManualClock`）会成为注册表的时钟。定义了自己的 `ResilienceRegistry` bean 的应用会替换自动配置的那个（此时不会应用 customizer 和监听器 bean）；`ResilienceConfig` bean 仍然存在，可以用 `config.applyTo(builder)` 构建它。

## 指标、健康检查和端点 :id=actuator

- **指标**：当类路径上有 `resilience4cata-micrometer` 且存在一个 `MeterRegistry` bean（Spring Boot Actuator 的）时，[Micrometer meter](micrometer.md) 会自动注册。成功的调用不发布事件，因此没有开销。没有按调用的 observation（`ObservationRegistry`）：每次调用一个会在每次成功时进行内存分配并读取时钟。
- **健康检查**：`resilience4cata` 健康指示器报告每个熔断器（状态、失败率和慢调用率、窗口中的调用）。熔断器打开时它仍保持 **UP**：打开的熔断器是应用在保护自己免受失败依赖的影响，而不是重启它或把它移出负载均衡的理由。把应该让它变为 DOWN（半开时为 UNKNOWN）的熔断器列在 `resilience4cata.spring.health.down-when-open` 中（`*` 表示全部）。除非你添加它，否则它不属于 liveness 或 readiness 组。
- **端点**：`GET /actuator/resilience4cata` 以 JSON 返回 `registry.snapshot()`（[它的结构](observability.md#snapshots)），`GET /actuator/resilience4cata/{instance}` 返回一个实例 id 的策略。它是只读的，不保留事件历史。像任何端点一样暴露它：`management.endpoints.web.exposure.include=health,resilience4cata`。

### OpenTelemetry :id=opentelemetry

自动配置只绑定 Micrometer。要改为导出 [OpenTelemetry 指标](opentelemetry.md)，请添加 `dev.resilience4cata:resilience4cata-opentelemetry`，并在你的 `OpenTelemetry` bean 旁边添加三个 bean：

<!-- snippet: spring-opentelemetry -->
```java
@Bean
OpenTelemetryMetrics resilienceMetrics(OpenTelemetry openTelemetry) {
    return OpenTelemetryMetrics.create(openTelemetry); // closed with the context
}

@Bean
ResilienceRegistryCustomizer resilienceCounters(OpenTelemetryMetrics metrics) {
    return metrics::register; // the counters' listener, before the registry is built
}

@Bean
SmartInitializingSingleton resilienceGauges(OpenTelemetryMetrics metrics, ResilienceRegistry registry) {
    return () -> metrics.bindTo(registry); // the gauges, read at each collection
}
```

customizer 在注册表构建之前注册计数器的监听器，gauge 在所有单例都存在后绑定，指标 bean 随 context 一起关闭，从而移除 gauge。如果类路径上也有 Micrometer，请设置 `resilience4cata.spring.metrics.enabled=false`，以免事件被统计两次。

## 开关 :id=switches

| 属性 | 默认值 | 作用 |
|---|---|---|
| `resilience4cata.spring.wrap` | `true` | 用包装器替换带注解接口的 bean |
| `resilience4cata.spring.exclude` | 空 | 不做处理的 bean 名称：不生成子类、不包装、不检查 |
| `resilience4cata.spring.allow-retry-inside-transaction` | `false` | 允许在带注解类的事务方法上使用 `@Retry`/`@Hedge`（所有尝试共用一个事务），而不是以 R4C-SPR-004 失败 |
| `resilience4cata.spring.allow-missing-processor` | `false` | 即使某个 bean 带有 resilience4cata 注解但没有生成类（其策略不会运行）也照常启动，而不是以 R4C-SPR-006 失败 |
| `resilience4cata.spring.verify-instances` | `fail`（延迟初始化时为 `warn`） | 对不匹配任何东西的实例键采取 `fail`、`warn` 或 `off` |
| `resilience4cata.spring.health.down-when-open` | 空 | 打开时让健康状态变为 DOWN 的熔断器 id（或 `*`） |
| `resilience4cata.spring.metrics.enabled` | `true` | 注册 Micrometer meter |
| `management.health.resilience4cata.enabled` | `true` | 健康指示器 |

## 测试 :id=testing

声明一个 `ManualClock` bean：重试暂停、舱壁隔离和限流器的等待以及超时都会跟随它，因此测试从不真正睡眠（[测试指南](testing.md)）。

<!-- snippet: spring-test -->
```java
@TestConfiguration
static class TestClock {

    @Bean
    ManualClock clock() {
        return new ManualClock(); // retry pauses, waits and timeouts follow it instead of the system clock
    }
}

@Autowired
OrderService orders;

@Autowired
ManualClock clock;

@Autowired
OrderRepository repository; // fails the first loads it is told to

@Test
void retriesWithoutSleeping() {
    repository.failures.set(2);

    assertThat(orders.find("42")).isEqualTo(new Order("42"));
    assertThat(clock.elapsed()).isPositive(); // the two pauses, taken on the manual clock
}
```

受保护 bean 的测试替身：

- `@MockitoBean` 用纯粹的 mock 替换 bean，不带策略，适用于带注解的接口或类。
- 带注解接口上的 `@MockitoSpyBean` 同样注入纯粹的 spy，因此 `verify(spy)` 可以工作；在带注解的类上，spy 保留策略。
- 被多个测试类共享的测试 context 共享同一个注册表，因此打开的熔断器在它们之间保持打开：用 `registry.circuitBreaker(id).reset()` 重置它，或者使用 `@DirtiesContext`。

## AOT 和原生镜像 :id=aot

Spring AOT（原生镜像构建或 AOT 优化的 JVM 应用的处理步骤）可以工作：

- 带注解类的替换发生在处理 bean 定义时，因此生成的代码直接调用 `new OrderService$Resilient(...)`，执行替换的后处理器会被排除在优化后的 context 之外；
- 接口 bean 仍然在运行时用 `Resilient.of` 包装，它按名称找到 `StockClient$Resilient`。注解处理器为每个生成类写出 GraalVM 可达性元数据（`META-INF/native-image/dev.resilience4cata.generated/…`），因此不需要 Spring 运行时 hint；
- 属性在运行时读取，但条件在构建时就固定了，与 Spring AOT 中的其他地方一样：`resilience4cata.spring.metrics.enabled`、`management.health.resilience4cata.enabled`、端点的暴露，以及针对带注解类的 `resilience4cata.spring.exclude`。

Spring Boot 4 的原生镜像需要 GraalVM 25 或更新版本。本项目的 CI 使用 Spring Boot 和 GraalVM 的 Gradle 插件为一个小应用构建原生镜像（一个带注解的类、一个带降级的包装接口、YAML 配置、Actuator 的健康检查、指标以及通过 Tomcat 提供的 resilience4cata 端点，还有由 Spring MVC 提供的两种风格的 `Mono` 和 `Flux` 方法），并在镜像内运行它的测试。

> [!MEASURED]
> 在该应用的原生镜像中，resilience4cata 增加了 1.9 MB 的镜像大小、11 ms 的启动时间（到第一个被应答的请求为 78 ms 对 67 ms）以及 9–11 MB 的 RSS（[原生镜像占用](../benchmarks.md#native-footprint)）。

## 限制 :id=limits

- 带注解的接口会把 bean 类型变成包装器：请注入接口。实现内部对 `this` 的调用不受保护。每个 bean 一个带注解的接口。
- `@Bean` 方法必须自己返回生成的类；带有 `@Bean` 方法的完整 `@Configuration` 类不能加注解（[`R4C-SPR-002`](../errors.md#r4c-spr-002)）。
- 使用带注解的类时，`@Transactional` 这样的基于类的代理在策略之外运行；被重试或对冲的事务方法会使启动失败（[`R4C-SPR-004`](../errors.md#r4c-spr-004)），除非显式允许。该检查读取类文件，因此原生镜像会跳过它：它在 JVM 构建和测试中运行。
- 构造函数对 `this` 调用的方法在策略设置好之前运行（[带注解的类](annotated-classes.md)）。
- 没有按调用的 observation，端点中没有事件历史，配置没有热重载。
- Spring Boot DevTools 的重启可以工作：每次重启都使用新的重启类加载器中的生成类，库之后运行的工作（重试的下一次尝试、异步超时）也使用那个加载器运行。重启会构建一个新的注册表，因此策略从头开始（熔断器关闭、窗口为空）。对注解的修改会在 DevTools 监视的构建再次运行注解处理器之后生效（Gradle 或 Maven 构建会运行它）。
