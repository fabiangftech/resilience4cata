# Micronaut

在 Micronaut 5 应用中使用 resilience4cata：带注解的 bean 由生成的代码保护，注册表从 `application.yml` 配置，Micronaut 的 management 模块获得健康指示器、端点和指标。

`resilience4cata-micronaut` 模块（自 0.5.0 起可用）不会在调用路径上增加任何东西：没有 `@Around` 通知，也没有拦截器。受保护的 bean *就是*注解处理器生成的类，因此一次调用的开销与不使用 Micronaut 时相同。

**要求：**Micronaut 5.2 或更高版本（该模块针对 Micronaut core 5.2.0 编译，其测试同时在 core 5.2.0 和 platform 5.2.2 上运行），以及 Micronaut 5 本身所要求的 **Java 25**。该模块是 resilience4cata 的 Java 21 基线的唯一例外：它使用 `--release 25` 编译；其他所有模块仍保持在 Java 21。不支持 Micronaut 4。

## 安装 :id=install

添加该模块，并且与任何使用注解的场景一样，添加注解处理器（[安装](../start/installation.md)）。该模块会引入 `resilience4cata-core` 和 `resilience4cata-config`。编译器选项 `-Ar4c.inject=micronaut` 使注解类生成的子类成为 Micronaut bean（[注解类](#classes)）；如果你只为接口添加注解，则不需要它。

<!-- tabs:start -->

#### **Gradle**

```groovy
plugins {
    id 'io.micronaut.application' version '5.0.2' // adds micronaut-inject-java to the processor path
}

dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.0.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.0.0')

    implementation 'dev.resilience4cata:resilience4cata-micronaut'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-micrometer'   // optional: metrics
    implementation 'io.micronaut:micronaut-management'                // optional: health and endpoint
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'  // optional: ManualClock
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.add('-Ar4c.inject=micronaut') // annotated classes become Micronaut beans
}
```

#### **Gradle (Kotlin)**

```kotlin
plugins {
    id("io.micronaut.application") version "5.0.2" // adds micronaut-inject-java to the processor path
}

dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.0.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-micronaut")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-micrometer")   // optional: metrics
    implementation("io.micronaut:micronaut-management")                // optional: health and endpoint
    testImplementation("dev.resilience4cata:resilience4cata-testkit")  // optional: ManualClock
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Ar4c.inject=micronaut") // annotated classes become Micronaut beans
}
```

#### **Maven**

```xml
<!-- with micronaut-parent as the parent, and resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-micronaut</artifactId>
</dependency>
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-annotations</artifactId>
  <scope>provided</scope>
</dependency>

<!-- in the maven-compiler-plugin configuration -->
<annotationProcessorPaths combine.children="append">
  <!-- micronaut-parent already lists micronaut-inject-java: append ours -->
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0</version>
  </path>
</annotationProcessorPaths>
<compilerArgs combine.children="append">
  <arg>-Ar4c.inject=micronaut</arg>
</compilerArgs>
```

<!-- tabs:end -->

不使用 Micronaut 的 Gradle 插件时，请自行把 `io.micronaut:micronaut-inject-java` 连同 Micronaut platform BOM 放到 `annotationProcessor` 上。**两个处理器的顺序无关紧要**：Micronaut 的处理器从不声明（claim）注解，而我们的只声明自己的注解，因此每个处理器都能看到所有内容（该模块的构建会以两种顺序编译相同的源代码并比较输出）。如果你使用 Lombok，它要放在最前面，正如 Micronaut 自己的文档所述（[与 Lombok 一起使用](annotated-classes.md#with-lombok)）。

> 没有注解处理器就不会生成任何内容，因此创建注解类的 bean 会以 [`R4C-MN-007`](../errors.md#r4c-mn-007) 失败，`@ResilientBean` 则以 [`R4C-RT-003`](../errors.md#r4c-rt-003) 失败，而不是在没有保护的情况下运行它们。该检查读取 class 文件（注解的保留策略为 `CLASS`），因此不会在原生镜像中运行。`resilience4cata.micronaut.allow-missing-processor=true` 可以关闭它。

## 注解类 :id=classes

直接为类本身添加注解；这是推荐的风格，因为对 `this` 的调用也会受到保护：

<!-- snippet: micronaut-class -->
```java
@Singleton
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

处理器生成 `OrderService$Resilient extends OrderService`。使用 `-Ar4c.inject=micronaut` 时，它还会在生成的类上重复该类自身的注解（`@Singleton`、`@Named` 等限定符、`@Requires`、`@Primary`、`@Order` 等）。随后 Micronaut 的处理器为生成的类写出一个 bean 定义，集成会排除 Micronaut 为 `OrderService` 本身编译的那个定义，于是在本来会创建 `OrderService` 的地方，Micronaut 都会创建 `OrderService$Resilient`：

- 该 bean 仍然是一个 `OrderService`，具有其作用域、限定符和条件要求（使用 `@RequestScope` 或 `@Refreshable` 时，每个请求或每次刷新都会得到生成类的一个独立实例，它们共享命名的策略）；
- 对 `this` 的调用（`findAgain` 调用 `find`）会经过策略，这与 Micronaut 自己的 `@Retryable` 不同；
- 构造函数注入与以前一样工作：生成的构造函数先接收 `ResilienceRegistry`（按类型注入），后面是原来的参数，保留其名称和注解（多个构造函数之一上的 `@Inject`、`@Named`、`@Value`；参见[注解类](annotated-classes.md#constructor-injection)）。字段注入、方法注入以及 `@PostConstruct` 会被继承。

没有该选项时，Micronaut 会创建未受保护的 `OrderService`。模块会在创建 bean 时发现这一点，并以 [`R4C-MN-004`](../errors.md#r4c-mn-004) 失败。（构造函数带有 `@Inject` 的类是一个特例：生成的构造函数会重复 `@Inject`，因此没有该选项时 Micronaut 会看到两个 bean，即该类及其子类，`R4C-MN-004` 会报告第一个。）

**不使用该选项，改用工厂。** 让类不带作用域注解（也不带 `@Inject`），这样 Micronaut 就不会创建它，然后从 `@Factory` 方法返回生成的子类：

<!-- snippet: micronaut-factory-class -->
```java
public class InvoiceClient { // no scope annotation: Micronaut does not create it itself

    private final String baseUrl;

    public InvoiceClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Retry(maxAttempts = 2)
    public String fetch(String number) {
        return baseUrl + "/invoices/" + number; // an HTTP call in a real application
    }
}
```

<!-- snippet: micronaut-factory -->
```java
@Factory
public class InvoiceFactory {

    @Singleton
    InvoiceClient invoiceClient(ResilienceRegistry registry, @Value("${invoices.url}") String url) {
        return new InvoiceClient$Resilient(registry, url); // typed InvoiceClient, the generated subclass inside
    }
}
```

把方法的返回类型声明为该类，而不是 `InvoiceClient$Resilient`：Micronaut 的处理器第一次读取工厂时，生成的类还不存在。由工厂创建的 bean 不会获得自己的注入，也没有 Micronaut AOP；使用该选项时，类会保留 Micronaut 的全部特性。

**类上的 Micronaut AOP。** 生成的覆写方法会重复被覆写方法的注解，因此环绕通知（`@Transactional`、`@Cacheable`、你自己的 `@Around` 绑定）仍然生效，而 Micronaut 的代理（`OrderService$Resilient` 的一个 `$Intercepted` 子类）位于策略的**外层**：通知对所有尝试只运行一次。对于事务，这意味着所有尝试共用一个事务，而在某次失败的尝试将其标记为仅回滚之后，后续成功的尝试也无法提交。因此，同时被重试或对冲、又带有 `@Transactional`（Micronaut 的，或映射到它上面的 Jakarta 的，且传播行为会加入或开启事务）的方法，会在创建 bean 时以 [`R4C-MN-005`](../errors.md#r4c-mn-005) 失败。修复方法：在 bean 所实现的接口上声明策略（包装器位于事务代理之外：每次尝试一个事务），或者把 `@Transactional` 移到被重试方法所调用的另一个 bean 上。如果你想要的正是一个共享事务，请设置 `resilience4cata.micronaut.allow-retry-inside-transaction=true`。`@CircuitBreaker`、`@Timeout`、`@RateLimiter` 和 `@Bulkhead` 只执行一次调用，不会被检查。

## 注解接口 :id=interfaces

<!-- snippet: micronaut-interface -->
```java
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface StockClient {

    int available(String sku);
}
```

用 `@ResilientBean` 标记实现，并指明接口：

<!-- snippet: micronaut-interface-bean -->
```java
@Singleton
@ResilientBean(StockClient.class) // the bean is replaced by StockClient$Resilient
public class HttpStockClient implements StockClient {

    @Override
    public int available(String sku) {
        return 42; // an HTTP call in a real application
    }
}
```

当 Micronaut 创建该 bean 时，一个 `BeanCreatedEventListener` 会将其替换为 `Resilient.of(StockClient.class, bean, registry)`。注入接口：

<!-- snippet: micronaut-inject-interface -->
```java
@Singleton
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

- `@ResilientBean(StockClient.class)` 还会把 bean 的类型限制为 `StockClient`（它设置了 `@Bean(typed = StockClient.class)`），因为包装器只实现该接口：注入 `HttpStockClient` 找不到任何 bean。把它暴露为其他类型会使启动以 [`R4C-MN-001`](../errors.md#r4c-mn-001) 失败，指定 bean 未实现的接口则以 [`R4C-MN-002`](../errors.md#r4c-mn-002) 失败。
- 包装器替换的是 Micronaut 构建好的 bean（包括 AOP 代理），因此 `HttpStockClient` 上的 Micronaut 通知在策略的**内层**运行：被重试的 `@Transactional` 方法每次尝试都会得到一个新事务。
- `HttpStockClient` 内部对 `this` 的调用不受保护（包装器位于对象之外）。
- 使用 `@Singleton`、`@Prototype` 或 `@Context`。Micronaut 会代理的作用域（`@Refreshable`、`@RequestScope`、`@ThreadLocal`）会使启动以 [`R4C-MN-003`](../errors.md#r4c-mn-003) 失败。
- 每个 `@ResilientBean` 定义都会在上下文启动时检查，因此即使是稍后才创建的 bean，这些错误也会阻止启动。为什么使用标记而不是自动包装：它是显式的，而且不需要为每个 bean 查找类。

## 响应式 bean :id=reactive

返回 Reactor 的 `Mono` 或 `Flux` 的 bean 方法以同样的方式受到保护，只需类路径上有 `resilience4cata-reactor`（它不属于 `resilience4cata-all`：[安装](../start/installation.md)）。每次订阅都是一次经过策略的调用；对于 `Flux`，重试和降级只在第一个元素之前起作用，`@Timeout` 是没有元素的时间（[Project Reactor](reactor.md)）。

<!-- snippet: micronaut-reactive -->
```java
@Singleton
public class PriceService {

    private final PriceBackend backend;

    public PriceService(PriceBackend backend) {
        this.backend = backend;
    }

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

在控制器中，原样返回 bean 的 `Mono`。Micronaut 根据 `Event<T>` 写出服务器发送事件（server-sent events），因此要把 `Flux` 的元素映射为事件：

<!-- snippet: micronaut-reactive-endpoint -->
```java
@Get(uri = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM)
Flux<Event<String>> ticks(String sku) {
    return quotes.ticks(sku).map(Event::of);   // Micronaut frames server-sent events from Event<T>
}
```

- **`Mono` 的失败** 与其他失败一样经过控制器的 `@Error` 处理器（例如把 `CallTimeoutException` 映射为 504）。在第一个事件之前就失败的服务器发送事件流，无论处理器如何，Micronaut 都以 500 回应；第一个事件之后响应已经开始，因此失败会结束该流。
- **断开连接的客户端** 会经过每个策略取消 `Mono` 调用，或已发送过事件的流：委托对象的 `Mono` 或 `Flux` 被取消，舱壁释放其槽位。在流的第一个事件之前，Micronaut 不会察觉断开连接，因此调用会一直持续到它的 `@Timeout`：请为流式方法设置一个超时。

注解类中被重试的 `Mono` 或 `Flux` 方法外层如果有 Micronaut 的 `@Transactional`，会使启动失败，与其他方法一样（[R4C-MN-005](../errors.md#r4c-mn-005)）。

## 配置 :id=configuration

策略读取应用配置中 `resilience4cata` 下的内容，键与[配置参考](../reference/configuration.md)中的相同，可用 kebab-case 或 camelCase：

<!-- snippet: micronaut-yaml -->
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
    '"dev.resilience4cata.micronaut.guide.StockClient#available(java.lang.String)"':
      timeout.value: 3s
```

- 包含字母、数字、`_` 和 `-` 以外字符的**实例 id**（派生的 `<type>#<method>(<parameter types>)`）中有点号，Micronaut 会把它解读为嵌套：请如上所示在 YAML 键中把 id 写在双引号内（`'"com.acme.StockClient#available(java.lang.String)"'`），或者为策略指定一个 `name`。
- **列表**（`retry-on`、`abort-on`、`record-on`、`ignore-on`）可以是 YAML 列表，也可以是以逗号分隔的值。
- **占位符**（`${…}`）会被解析。
- **优先级遵循 Micronaut 的规则**：命令行高于系统属性，高于环境变量，高于 `application-<environment>.yml`，高于 `application.yml`。环境变量使用 `resilience4cata-config` 的 [`R4C_` 或 `RESILIENCE4CATA_` 名称](configuration.md)，按原样读取，而不经过 Micronaut 的映射。类路径上的 `resilience4cata.properties` 文件仍然会被读取，优先级低于以上所有来源。
- **环境**：Micronaut 的环境（`application-prod.yml`）是自然的方式；resilience4cata 自己的 `profiles.<name>.*` 键也仍然有效，二者相互独立。
- 与 resilience4cata 的其他所有地方一样，配置在启动时读取一次：没有刷新，bean 也不是 `@Refreshable`。

**启动时校验。** 配置和注册表都是 `@Context` bean，因此未知的键或错误的值会阻止启动，并一次列出所有问题，指明设置它的文件（Micronaut 不保留行号）：

```text
Bean definition [dev.resilience4cata.config.ResilienceConfig] could not be loaded: Error instantiating bean of type [dev.resilience4cata.config.ResilienceConfig]

Message: Invalid resilience4cata configuration (2 problems):
  [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts' (application.yml): ... Did you mean 'resilience4cata.defaults.retry.max-attempts'?
  [R4C-CFG-002] invalid value 'soon' for 'resilience4cata.defaults.retry.delay' (application.yml): ...
```

启动时，每个 `resilience4cata.instances.<id>.*` 键都必须匹配一个策略实例（[`R4C-CFG-013`](../errors.md#r4c-cfg-013)）。Micronaut 默认延迟创建单例，因此此时大多数 bean 的实例还不存在：未匹配的键只会被记录为警告，这可能是误报。当上下文急切地创建单例时（`eagerInitSingletons(true)`），单例的实例已经存在，未匹配的键会阻止启动，除非应用中有不是单例的受保护 bean（`@Prototype`、`@Refreshable`、请求作用域），它们的实例在创建时才出现：这种情况下仍然只是警告。`resilience4cata.micronaut.verify-instances`（`fail`、`warn`、`off`）会覆盖以上所有行为。

## 自定义注册表 :id=beans

以 Micronaut 的方式自定义注册表构建器，即使用 `BeanCreatedEventListener`，例如在代码中定义一个命名配置：

<!-- snippet: micronaut-customizer -->
```java
@Singleton
BeanCreatedEventListener<ResilienceRegistry.Builder> slowBackends() {
    return event -> event.getBean().retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
}
```

监听器按 `@Order` 运行，并且在配置之前运行，因此配置胜出：上面的 `configs.slow.retry.delay` 覆盖监听器的延迟，并保留其 `maxAttempts`。`defaults.<policy>.*` 键会替换监听器为该策略设置的 `<policy>Defaults(...)`。

每个 `EventListener` bean 都按其泛型签名中的事件类型注册（原始类型的监听器接收所有事件）：

<!-- snippet: micronaut-listener -->
```java
@Singleton
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.id() + " is now " + transition.to());
}
```

监听器在引起事件的线程上运行（[事件](observability.md#events)）。监听器 bean 不得依赖受保护的 bean：注册表需要监听器，而受保护的 bean 需要注册表。

`Ticker` bean（testkit 的 `ManualClock`）会成为注册表的时钟。若要自己构建注册表，请声明一个带有 `@Replaces(ResilienceRegistry.class)` 的 bean；`ResilienceConfig` bean 仍然存在，可通过 `config.applyTo(builder)` 应用配置。

## 指标、健康检查与端点 :id=management

- **指标**：当类路径上有 `resilience4cata-micrometer` 且存在 `MeterRegistry` bean（来自 `micronaut-micrometer-core`）时，[Micrometer meter](micrometer.md) 会自动注册。成功的调用不发布事件，因此没有任何开销。
- **健康检查**：使用 `micronaut-management` 时，`resilience4cata` 健康指示器会报告每个熔断器（状态、失败率和慢调用比率、窗口中的调用）。熔断器打开时它仍保持 **UP**：打开的熔断器是应用在保护自己免受故障依赖的影响，而不是重启应用或将其移出负载均衡的理由。在 `resilience4cata.micronaut.health.down-when-open` 中列出应使其变为 DOWN（半开时为 UNKNOWN）的熔断器（`*` 表示全部）。它既不是 `@Liveness` 也不是 `@Readiness`，因此只计入 `/health`。
- **端点**：`GET /resilience4cata` 以 JSON 返回 `registry.snapshot()`（[其结构](observability.md#snapshots)），`GET /resilience4cata/{instance}` 返回某一个实例 id 的策略。它是只读的，不保留事件历史。与 Micronaut 自己的端点一样，它默认是敏感的：通过 `endpoints.resilience4cata.enabled` 和 `endpoints.resilience4cata.sensitive` 进行配置。

### OpenTelemetry :id=opentelemetry

该模块只绑定 Micrometer。若要改为导出 [OpenTelemetry 指标](opentelemetry.md)，请添加 `dev.resilience4cata:resilience4cata-opentelemetry`，并在你的 `OpenTelemetry` bean 旁边的 `@Factory` 中添加以下 bean：

<!-- snippet: micronaut-opentelemetry -->
```java
@Singleton
@Bean(preDestroy = "close")
OpenTelemetryMetrics resilienceMetrics(OpenTelemetry openTelemetry) {
    return OpenTelemetryMetrics.create(openTelemetry);
}

@Singleton
BeanCreatedEventListener<ResilienceRegistry.Builder> resilienceCounters(OpenTelemetryMetrics metrics) {
    return event -> metrics.register(event.getBean()); // the counters' listener, before the registry is built
}

@Singleton
BeanCreatedEventListener<ResilienceRegistry> resilienceGauges(OpenTelemetryMetrics metrics) {
    return event -> {
        metrics.bindTo(event.getBean()); // the gauges, read at each collection
        return event.getBean();
    };
}
```

第一个监听器在注册表构建之前把计数器的监听器注册到构建器上，第二个在注册表创建时绑定仪表（gauge），`preDestroy = "close"` 在上下文停止时移除仪表。如果类路径上同时有 Micrometer，请设置 `resilience4cata.micronaut.metrics.enabled=false`，以免事件被计数两次。

## 开关 :id=switches

| 属性 | 默认值 | 作用 |
|---|---|---|
| `resilience4cata.micronaut.verify-instances` | `warn`（急切创建单例时为 `fail`） | 对启动时不匹配任何实例的实例键采取 `fail`、`warn` 或 `off` |
| `resilience4cata.micronaut.exclude` | 空 | 不予处理的 bean 的全限定类名：不包装，也不检查 R4C-MN-004 和 R4C-MN-005。与 Spring 和 Quarkus 的 `exclude` 不同，当注解类使用 `-Ar4c.inject=micronaut` 编译时，它无法阻止该类被其生成的子类替换（Micronaut 在读取配置之前就选定了 bean 定义）。若要在所有框架中让某个类不带策略运行，请关闭这些策略：`resilience4cata.instances.<id>.<policy>.enabled=false` |
| `resilience4cata.micronaut.allow-retry-inside-transaction` | `false` | 允许在注解类的事务方法上使用 `@Retry`/`@Hedge`（所有尝试共用一个事务），而不是以 R4C-MN-005 失败 |
| `resilience4cata.micronaut.allow-missing-processor` | `false` | 为没有生成子类的注解类创建 bean（其策略不会运行），而不是以 R4C-MN-007 失败 |
| `resilience4cata.micronaut.health.enabled` | `true` | 健康指示器 |
| `resilience4cata.micronaut.health.down-when-open` | 空 | 打开时使健康状态变为 DOWN 的熔断器 id（或 `*`） |
| `resilience4cata.micronaut.metrics.enabled` | `true` | 注册 Micrometer meter |

## 测试 :id=testing

为测试上下文提供一个 `ManualClock` bean：重试暂停、舱壁和限流器的等待以及超时都跟随它，因此测试永远不会休眠（[测试指南](testing.md)）。

<!-- snippet: micronaut-test-clock -->
```java
@Factory
public class Clocks {

    @Singleton
    ManualClock manualClock() {
        return new ManualClock(); // retry pauses, waits and timeouts follow it instead of the system clock
    }
}
```

<!-- snippet: micronaut-test -->
```java
@Inject
OrderService orders;

@Inject
ManualClock clock; // the @Factory above, in src/test

@Inject
OrderRepository repository; // fails the first loads it is told to

@Test
void retriesWithoutSleeping() {
    repository.failures.set(2);

    assertThat(orders.find("42")).isEqualTo(new Order("42"));
    assertThat(clock.elapsed()).isPositive(); // the two pauses, taken on the manual clock
}
```

测试类带有 `@MicronautTest` 注解（来自 `micronaut-test-junit5`）。

## 原生镜像 :id=native

对于 resilience4cata 无需添加任何内容：

- 注解处理器为每个生成的类写出 GraalVM 可达性元数据（`META-INF/native-image/dev.resilience4cata.generated/…`），这覆盖了 `@ResilientBean` bean 所用的 `Resilient.of`；
- 使用 `-Ar4c.inject=micronaut` 时，Micronaut 自己的 bean 定义会创建生成的子类，无需反射；
- 端点提供 `ResilienceSnapshot.toMap()`，core 构建它时不使用反射。

项目的 CI 使用 GraalVM 的 Gradle 插件和 Micronaut 的 `micronaut-graal` 注解处理器，为一个小型应用（一个注解类、一个带降级的 `@ResilientBean` 接口、YAML 配置、健康检查、Micrometer 指标、通过 Netty 提供的端点，以及两种风格的 `Mono` 和 `Flux` 方法）构建 GraalVM 25 原生镜像，并在镜像内运行其测试。请使用 Micronaut 的 Gradle 或 Maven 插件构建你的镜像，它们也会把 `application.yml` 放入镜像。外部配置中指定的异常类（`retry-on: com.acme.Busy`）是按名称加载的：请改为在注解中指定它们，或为它们注册反射。事务内重试检查（`R4C-MN-005`）读取 class 文件，因此原生镜像会跳过它：它在 JVM 构建和测试中运行。原生镜像中的服务器发送事件需要一个 Netty 元数据所缺少的反射条目，这与 resilience4cata 无关：在应用注册该类的已声明方法（其 `reachability-metadata.json` 中的 `"allDeclaredMethods": true`）之前，第一个分块响应以 `Can't find '[touch]' in io.netty.util.ReferenceCountUtil` 失败。

> [!MEASURED]
> 在该应用的原生镜像中，resilience4cata 使镜像增加 1.9 MB，启动时间增加 5 ms（到第一个被应答的请求为 32 ms 对比 27 ms），RSS 增加 3–5 MB（[原生镜像占用](../benchmarks.md#native-footprint)）。

## 限制 :id=limits

- Micronaut 在 `io.micronaut.retry.annotation` 中有自己的 `@Retryable`、`@CircuitBreaker` 和 `@Fallback`：请检查导入，IDE 可能会选错。不要在同一个方法上把它们与 resilience4cata 的注解组合使用。
- 注解接口：注入接口；实现内部对 `this` 的调用不受保护；每个 `@ResilientBean` 一个注解接口。
- 注解类需要 `-Ar4c.inject=micronaut` 才能由 Micronaut 创建，或者使用工厂。只有该类自身的 bean 定义会被排除：子类 bean、`@Factory` 方法创建的该类 bean 以及测试的 `@MockBean` 都会保留。`@Factory` 类不能添加注解。
- 不要在配置类（`@ConfigurationProperties`、`@EachProperty`）上使用策略。Micronaut 的处理器每轮编译写出一次配置元数据，而它在下一轮才看到生成的子类，因此会警告 `Error occurred writing configuration metadata`，这会使 `-Werror` 构建失败。把配置放在单独的类中，把策略放在接收该配置的 bean 上：`@EachBean(BackendConfig.class)` 为每个 `@EachProperty` 条目提供一个受保护的 bean。
- 使用注解类时，Micronaut 的 AOP 在策略之外运行；被重试或对冲的事务方法会失败（[`R4C-MN-005`](../errors.md#r4c-mn-005)），除非明确允许。
- 构造函数在 `this` 上调用的方法会在策略设置好之前运行（[注解类](annotated-classes.md)）。
- 端点中没有事件历史，也没有写操作（`forceOpen`、`reset`），配置也不能刷新。该模块不支持 Micronaut 4 和 Java 21。

**与 `micronaut-retry` 和 resilience4j-micronaut 的比较。** 两者都通过 Micronaut 的 `@Around` AOP 工作：每个被通知的调用在运行时都要经过一条方法拦截器链，并且按方法查找配置。resilience4cata 的策略是生成的代码，Micronaut 只负责实例化它们。尚未在 Micronaut 中对这三者运行过基准测试，因此这里不给出数字；已测量的内容请参见[性能](../performance.md)。
