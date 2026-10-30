# Spring Boot

Use resilience4cata in a Spring Boot 4 application: annotated beans are protected by the generated code, the
registry is configured from `application.yml`, and Actuator gets a health indicator, an endpoint and metrics.

The module `resilience4cata-spring` (available from 0.4.0) is a Spring Boot auto-configuration. It adds
nothing to the call path: there are no proxies, aspects or interceptors. A protected bean *is* the class that
the annotation processor generated, so a call costs what it costs without Spring.

**Requirements:** Spring Boot 4.1 or newer (the module is compiled against 4.1.0 and tested on 4.1.1) and
Java 21. Spring Boot 3 is not supported.

## Install :id=install

Add the module and, as for any use of the annotations, the annotation processor
([installation](../start/installation.md)). The module brings `resilience4cata-core` and
`resilience4cata-config`.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.10.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.10.0')

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
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.10.0")
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
    <version>0.10.0</version>
  </path>
  <!-- annotationProcessorPaths replaces discovery: list the other processors you use too -->
  <path>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

> Without the annotation processor nothing is generated and the beans stay unprotected: the annotations have
> `CLASS` retention, so nothing at run time can notice they are there. `GET /actuator/resilience4cata` lists
> the policy instances; an empty list means the processor did not run.

## Annotated classes :id=classes

Annotate the class itself. This is the recommended style in Spring:

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

The processor generates `OrderService$Resilient extends OrderService`, and the integration makes Spring
instantiate it wherever a bean definition names `OrderService` (component scanning, `@Import`,
`registerBean`). So:

- the bean is still an `OrderService`: it is injected by its class, its interfaces, `@Qualifier`, `List<…>`, and
  keeps its name, scope and `@Primary`;
- calls on `this` (`findAgain` calling `find`) go through the policies, unlike Spring AOP and Spring's own
  `@Retryable`;
- constructor injection works as before: the generated constructors take the `ResilienceRegistry` (injected by
  type) followed by the original parameters, with their names and annotations (`@Autowired` on one of several
  constructors, `@Qualifier`, `@Value`; see [annotated classes](annotated-classes.md#constructor-injection)).
  Field and setter injection and `@PostConstruct` are inherited.

A `@Bean` method that returns `new OrderService(...)` would produce an unprotected bean, so the startup fails
with [`R4C-SPR-001`](../errors.md#r4c-spr-001): register the class instead, or return
`new OrderService$Resilient(registry, ...)` from the method.

**Proxies over the class.** The generated class is not `final`, so Spring can still put a class-based proxy
around it (`@Transactional`, `@Cacheable`, `@Async`, scoped beans). The proxy is outside the policies. For
transactions that would mean one transaction for every attempt, and after a failed attempt marks it
rollback-only, a later successful attempt fails to commit with `UnexpectedRollbackException`. So a method that
is both retried or hedged and transactional (`@Transactional` of Spring or Jakarta, with a propagation that
joins or starts a transaction) **stops the startup** with [`R4C-SPR-004`](../errors.md#r4c-spr-004). Fix it
by declaring the policy on an interface the bean implements (the interface wrapper goes outside the
transaction proxy: one transaction per attempt), or by moving `@Transactional` to another bean that the
retried method calls. If one shared transaction is what you want, set
`resilience4cata.spring.allow-retry-inside-transaction=true`. `@CircuitBreaker`, `@Timeout`, `@RateLimiter`
and `@Bulkhead` run the call once and are not checked. The generated override repeats the annotations of the
method it overrides, so a method-level Jakarta `@Transactional` (which Spring reads on the invoked method only)
applies and is checked like Spring's.

## Annotated interfaces :id=interfaces

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

A bean that implements an annotated interface is replaced, once initialized, by
`Resilient.of(StockClient.class, bean, registry)`. Inject the interface:

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

- The bean's type becomes the wrapper, which implements only `StockClient`: injecting `HttpStockClient` fails,
  as with JDK proxies, and the startup report says to inject the interface.
- The wrapper is applied after every other post-processor, so it wraps their proxies: a retried call through a
  `@Transactional` proxy gets a new transaction per attempt.
- Calls inside `HttpStockClient` on `this` are not protected (the wrapper is outside the object).
- A bean that implements two annotated interfaces, neither extending the other, fails the startup with
  [`R4C-SPR-003`](../errors.md#r4c-spr-003). A bean that already is a generated class (a `@Bean` method that
  returns `Resilient.of(...)` itself) is left as it is.
- A bean that a `BeanPostProcessor` of the application depends on is created before the wrapper's post-processor
  exists, so it would stay unwrapped: the startup fails with [`R4C-SPR-005`](../errors.md#r4c-spr-005). Inject it
  into that post-processor through `ObjectProvider<StockClient>` or `@Lazy`. Annotated classes are not affected.

## Reactive beans :id=reactive

Beans whose methods return Reactor's `Mono` or `Flux` are protected the same way, annotated classes and interfaces alike, with `resilience4cata-reactor` on the class path (it is not part of `resilience4cata-all`: [installation](../start/installation.md)). Each subscription is one call through the policies; for a `Flux`, the retry and the fallback act before the first element only, and `@Timeout` is the time without an element ([Project Reactor](reactor.md)).

<!-- snippet: spring-reactive-bean -->
```java
@Retry(name = "ticks", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
@Timeout("300ms")
@Bulkhead(name = "ticks", maxConcurrent = 1)
public Flux<String> ticks(String sku) {
    return backend.ticks(sku);
}
```

In WebFlux, return the bean's `Mono` or `Flux` from the controller as it is; a `Flux` can be streamed as server-sent events:

<!-- snippet: spring-reactive-endpoint -->
```java
@GetMapping(value = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
Flux<String> ticks(@PathVariable String sku) {
    return quotes.ticks(sku);   // the bean's Flux as it is: server-sent events
}
```

- **The Reactor context** that a `WebFilter` writes (tracing, Spring Security's `ReactiveSecurityContextHolder`, a request id) reaches every attempt and the fallback.
- **A failure before the first element** goes through your `@ExceptionHandler`s like any other (a `CallTimeoutException` mapped to 504, say). After the first element of a stream the response has started, so a failure ends the stream instead.
- **A client that disconnects** cancels the call through every policy: the delegate's `Mono` or `Flux` is cancelled and the bulkhead frees its slot.
- **A reactive `@Transactional`** around a retried or hedged `Mono` or `Flux` method of an annotated class fails the startup, as for other methods ([R4C-SPR-004](../errors.md#r4c-spr-004)).

## Configuration :id=configuration

The policies read the application's properties under `resilience4cata`, with the keys of the
[configuration reference](../reference/configuration.md) in kebab-case or camelCase:

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

- **Instance ids** other than letters, digits, `_` and `-` (the derived `<type>#<method>(<parameter types>)`)
  go in brackets, as Spring writes map keys: `"[com.acme.StockClient#available(java.lang.String)]"` in YAML,
  `resilience4cata.instances[com.acme.StockClient#available(java.lang.String)].timeout.value=3s` in
  `.properties`.
- **Lists** (`retry-on`, `abort-on`, `record-on`, `ignore-on`) can be YAML lists or comma-separated values.
- **Placeholders** (`${…}`) are resolved.
- **Precedence is Spring's**: command-line arguments and test properties over system properties over environment
  variables over `application-<profile>.yml` over `application.yml`. Environment variables use the
  [`R4C_` or `RESILIENCE4CATA_` names](configuration.md) of `resilience4cata-config`. A
  `resilience4cata.properties` file on the class path is still read, below all of them.
- **Profiles**: Spring profile documents (`application-prod.yml`, `spring.config.activate.on-profile`) are
  the natural way; resilience4cata's own `profiles.<name>.*` keys keep working too
  (`resilience4cata.profiles.active: ${spring.profiles.active:}` ties them together).
- Configuration is read once, at startup, like everywhere else in resilience4cata: there is no refresh.

**Validation at startup.** An unknown key or a bad value stops the startup with every problem at once, with
the file and line where it was set:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Invalid resilience4cata configuration (1 problem):

    [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts' (class path resource [application.yml] - 4:20). Did you mean 'resilience4cata.defaults.retry.max-attempts'? ...
```

Once the singletons exist, every `resilience4cata.instances.<id>.*` key must match a policy instance, or the
startup fails with [`R4C-CFG-013`](../errors.md#r4c-cfg-013). With lazy beans or pipelines built later, set
`resilience4cata.spring.verify-instances=warn` (the default under `spring.main.lazy-initialization=true`).

The module ships Spring Boot configuration metadata for the `defaults` keys and the switches below, which IDEs
that read it use to complete and document them.

## Customizers and listeners :id=beans

A `ResilienceRegistryCustomizer` bean adjusts the registry builder, for example a named config in code:

<!-- snippet: spring-customizer -->
```java
@Bean
ResilienceRegistryCustomizer slowBackends() {
    return builder -> builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
}
```

Customizers run in `@Order`, before the properties, so the properties win: `configs.slow.retry.delay` above
overrides the customizer's delay and keeps its `maxAttempts`. A `defaults.<policy>.*` key replaces a
customizer's `<policy>Defaults(...)` for that policy.

Every `EventListener` bean is registered for the event type in its generic signature (a raw listener gets every
event):

<!-- snippet: spring-listener -->
```java
@Bean
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.instance() + " is now " + transition.to());
}
```

Listeners run on the thread that caused the event ([events](observability.md#events)). A listener bean must not
depend on a protected bean: the registry needs the listener, and the protected bean needs the registry.

A `Ticker` bean (the testkit's `ManualClock`) becomes the registry's clock. An application that defines its own
`ResilienceRegistry` bean replaces the auto-configured one (customizers and listener beans are then not
applied); the `ResilienceConfig` bean is still there to build it with `config.applyTo(builder)`.

## Metrics, health and the endpoint :id=actuator

- **Metrics**: with `resilience4cata-micrometer` on the class path and a `MeterRegistry` bean (Spring Boot
  Actuator's), the [Micrometer meters](micrometer.md) are registered automatically. Successful calls publish no
  events, so they cost nothing. There are no per-call observations (`ObservationRegistry`): one per call would
  allocate and read the clock on every success.
- **Health**: the `resilience4cata` health indicator reports every circuit breaker (state, failure and slow-call
  rates, calls in the window). It stays **UP** with open breakers: an open breaker is the application protecting
  itself from a failing dependency, not a reason to restart it or take it out of load balancing. List the
  breakers that should turn it DOWN (UNKNOWN while half open) in
  `resilience4cata.spring.health.down-when-open` (`*` for all). It is not part of the liveness or readiness
  groups unless you add it.
- **Endpoint**: `GET /actuator/resilience4cata` returns `registry.snapshot()`, and
  `GET /actuator/resilience4cata/{instance}` the policies of one instance id. It is read-only and keeps no event
  history. Expose it like any endpoint: `management.endpoints.web.exposure.include=health,resilience4cata`.

## Switches :id=switches

| Property | Default | Effect |
|---|---|---|
| `resilience4cata.spring.wrap` | `true` | Replace beans of annotated interfaces by their wrapper |
| `resilience4cata.spring.exclude` | empty | Bean names to leave alone: no subclass, no wrapper, no checks |
| `resilience4cata.spring.allow-retry-inside-transaction` | `false` | Allow `@Retry`/`@Hedge` on transactional methods of annotated classes (one transaction for every attempt) instead of failing with R4C-SPR-004 |
| `resilience4cata.spring.verify-instances` | `fail` (`warn` with lazy initialization) | `fail`, `warn` or `off` for instance keys that match nothing |
| `resilience4cata.spring.health.down-when-open` | empty | Breaker ids (or `*`) that turn the health DOWN while open |
| `resilience4cata.spring.metrics.enabled` | `true` | Register the Micrometer meters |
| `management.health.resilience4cata.enabled` | `true` | The health indicator |

## Tests :id=testing

Declare a `ManualClock` bean: retry pauses, bulkhead and rate limiter waits and timeouts follow it, so a test
never sleeps ([testing guide](testing.md)).

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

Test doubles of protected beans:

- `@MockitoBean` replaces the bean with the bare mock, without the policies, for an annotated interface or class.
- `@MockitoSpyBean` on an annotated interface injects the bare spy too, so `verify(spy)` works; on an annotated
  class the spy keeps the policies.
- A test context shared by several test classes shares one registry, so an open breaker stays open across them:
  reset it with `registry.circuitBreaker(id).reset()`, or use `@DirtiesContext`.

## AOT and native images :id=aot

Spring AOT (the processing step of a native image build, or of an AOT-optimized JVM application) works:

- the swap of annotated classes happens while the bean definitions are processed, so the generated code calls
  `new OrderService$Resilient(...)` directly, and the post-processor that swapped them is left out of the
  optimized context;
- interface beans are still wrapped at run time with `Resilient.of`, which finds `StockClient$Resilient` by
  name. The annotation processor writes GraalVM reachability metadata for every generated class
  (`META-INF/native-image/dev.resilience4cata.generated/…`), so no Spring runtime hints are needed;
- properties are read at run time, but conditions are fixed at build time, as everywhere in Spring AOT:
  `resilience4cata.spring.metrics.enabled`, `management.health.resilience4cata.enabled`, the endpoint's
  exposure, and `resilience4cata.spring.exclude` for annotated classes.

Spring Boot 4 needs GraalVM 25 or newer for native images. The project's build checks AOT processing on the
JVM; native images themselves are not built by its CI.

## Limits :id=limits

- Annotated interfaces change the bean type to the wrapper: inject the interface. Calls on `this` inside the
  implementation are not protected. One annotated interface per bean.
- `@Bean` methods must return the generated class themselves; a full `@Configuration` class with `@Bean`
  methods cannot be annotated ([`R4C-SPR-002`](../errors.md#r4c-spr-002)).
- With annotated classes, class-based proxies such as `@Transactional` run outside the policies; a retried or
  hedged transactional method fails the startup ([`R4C-SPR-004`](../errors.md#r4c-spr-004)) unless allowed. The
  check reads the class files, so a native image skips it: it runs in the JVM build and tests.
- Methods that the constructor calls on `this` run before the policies are set up
  ([annotated classes](annotated-classes.md)).
- No per-call observations, no event history in the endpoint, no hot reload of the configuration.
