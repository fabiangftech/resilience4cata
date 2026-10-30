# Micronaut

Use resilience4cata in a Micronaut 5 application: annotated beans are protected by the generated code, the
registry is configured from `application.yml`, and Micronaut's management module gets a health indicator, an
endpoint and metrics.

The module `resilience4cata-micronaut` (available from 0.5.0) adds nothing to the call path: there is no
`@Around` advice and no interceptor. A protected bean *is* the class that the annotation processor generated, so
a call costs what it costs without Micronaut.

**Requirements:** Micronaut 5.2 or newer (the module is compiled against Micronaut core 5.2.0 and tested on
platform 5.2.2) and **Java 25**, which Micronaut 5 itself requires. This module is the one exception to
resilience4cata's Java 21 baseline: it is compiled with `--release 25`; every other module stays on Java 21.
Micronaut 4 is not supported.

## Install :id=install

Add the module and, as for any use of the annotations, the annotation processor
([installation](../start/installation.md)). The module brings `resilience4cata-core` and
`resilience4cata-config`. The compiler option `-Ar4c.inject=micronaut` makes the generated subclasses of
annotated classes Micronaut beans ([annotated classes](#classes)); it is not needed if you only annotate
interfaces.

<!-- tabs:start -->

#### **Gradle**

```groovy
plugins {
    id 'io.micronaut.application' version '5.0.2' // adds micronaut-inject-java to the processor path
}

dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.10.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.10.0')

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
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.10.0")
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
    <version>0.10.0</version>
  </path>
</annotationProcessorPaths>
<compilerArgs combine.children="append">
  <arg>-Ar4c.inject=micronaut</arg>
</compilerArgs>
```

<!-- tabs:end -->

Without Micronaut's Gradle plugin, put `io.micronaut:micronaut-inject-java` on `annotationProcessor` yourself,
with the Micronaut platform BOM. **The order of the two processors does not matter**: Micronaut's never claims
annotations and ours claims only its own, so each sees everything (the module's build compiles the same sources
with both orders and compares the output). Lombok, if you use it, goes first, as Micronaut's own documentation
says ([with Lombok](annotated-classes.md#with-lombok)).

> Without the annotation processor nothing is generated and the beans stay unprotected: the annotations have
> `CLASS` retention, so nothing at run time can notice they are there. `GET /resilience4cata` lists the policy
> instances; an empty list means the processor did not run.

## Annotated classes :id=classes

Annotate the class itself; this is the recommended style, as calls on `this` are protected too:

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

The processor generates `OrderService$Resilient extends OrderService`. With `-Ar4c.inject=micronaut` it also
repeats the class's own annotations on it (`@Singleton`, qualifiers such as `@Named`, `@Requires`, `@Primary`,
`@Order`…). Micronaut's processor then writes a bean definition for the generated class, the integration leaves out
the one Micronaut compiled for `OrderService` itself, and Micronaut creates `OrderService$Resilient` wherever it
would have created `OrderService`:

- the bean is still an `OrderService`, with its scope, qualifiers and requirements;
- calls on `this` (`findAgain` calling `find`) go through the policies, unlike Micronaut's own `@Retryable`;
- constructor injection works as before: the generated constructors take the `ResilienceRegistry` (injected by
  type) followed by the original parameters, with their names and annotations (`@Inject` on one of several
  constructors, `@Named`, `@Value`; see [annotated classes](annotated-classes.md#constructor-injection)). Field
  and method injection and `@PostConstruct` are inherited.

Without the option, Micronaut would create the unprotected `OrderService`. The module notices it when the bean is
created and fails with [`R4C-MN-004`](../errors.md#r4c-mn-004). (A class whose constructor has `@Inject` is a
special case: the generated constructor repeats `@Inject`, so without the option Micronaut sees two beans, the
class and its subclass, and `R4C-MN-004` reports the first one.)

**Without the option, with a factory.** Leave the class without a scope annotation (and without `@Inject`), so
Micronaut does not create it, and return the generated subclass from a `@Factory` method:

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

Declare the method's return type as the class, not `InvoiceClient$Resilient`: the generated class does not exist
yet when Micronaut's processor first reads the factory. A factory-made bean gets no injection of its own and no
Micronaut AOP; with the option, the class keeps all of Micronaut's features.

**Micronaut AOP on the class.** The generated overrides repeat the annotations of the methods they override, so
around advice (`@Transactional`, `@Cacheable`, your own `@Around` bindings) still applies, and Micronaut's proxy
(an `$Intercepted` subclass of `OrderService$Resilient`) goes **around** the policies: the advice runs once for all
the attempts. For transactions that would mean one transaction for every attempt, and after a failed attempt
marks it rollback-only, a later successful attempt fails to commit. So a method that is both retried or hedged and
`@Transactional` (Micronaut's, or Jakarta's mapped onto it, with a propagation that joins or starts a transaction)
fails with [`R4C-MN-005`](../errors.md#r4c-mn-005) when the bean is created. Fix it by declaring the policy on an
interface the bean implements (the wrapper goes outside the transaction proxy: one transaction per attempt), or by
moving `@Transactional` to another bean that the retried method calls. If one shared transaction is what you want,
set `resilience4cata.micronaut.allow-retry-inside-transaction=true`. `@CircuitBreaker`, `@Timeout`,
`@RateLimiter` and `@Bulkhead` run the call once and are not checked.

## Annotated interfaces :id=interfaces

<!-- snippet: micronaut-interface -->
```java
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface StockClient {

    int available(String sku);
}
```

Mark the implementation with `@ResilientBean`, naming the interface:

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

When Micronaut creates the bean, a `BeanCreatedEventListener` replaces it by
`Resilient.of(StockClient.class, bean, registry)`. Inject the interface:

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

- `@ResilientBean(StockClient.class)` also limits the bean's types to `StockClient` (it sets
  `@Bean(typed = StockClient.class)`), because the wrapper implements only the interface: injecting
  `HttpStockClient` finds no bean. Exposing it as other types fails the startup with
  [`R4C-MN-001`](../errors.md#r4c-mn-001), and naming an interface the bean does not implement with
  [`R4C-MN-002`](../errors.md#r4c-mn-002).
- The wrapper replaces the bean as Micronaut built it, AOP proxy included, so Micronaut's advice on
  `HttpStockClient` runs **inside** the policies: a retried `@Transactional` method gets a new transaction per
  attempt.
- Calls inside `HttpStockClient` on `this` are not protected (the wrapper is outside the object).
- Use `@Singleton`, `@Prototype` or `@Context`. Scopes that Micronaut proxies (`@Refreshable`, `@RequestScope`,
  `@ThreadLocal`) fail the startup with [`R4C-MN-003`](../errors.md#r4c-mn-003).
- Every `@ResilientBean` definition is checked when the context starts, so these mistakes stop the startup even
  for beans created later. Why a marker and no automatic wrapping: it is explicit, and needs no class lookup for
  every bean.

## Reactive beans :id=reactive

Bean methods that return Reactor's `Mono` or `Flux` are protected the same way, with `resilience4cata-reactor` on the class path (it is not part of `resilience4cata-all`: [installation](../start/installation.md)). Each subscription is one call through the policies; for a `Flux`, the retry and the fallback act before the first element only, and `@Timeout` is the time without an element ([Project Reactor](reactor.md)).

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

In a controller, return the bean's `Mono` as it is. Micronaut writes server-sent events from `Event<T>`, so map a `Flux`'s elements to events:

<!-- snippet: micronaut-reactive-endpoint -->
```java
@Get(uri = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM)
Flux<Event<String>> ticks(String sku) {
    return quotes.ticks(sku).map(Event::of);   // Micronaut frames server-sent events from Event<T>
}
```

- **A failure of a `Mono`** goes through the controller's `@Error` handlers like any other (a `CallTimeoutException` mapped to 504, say). A server-sent event stream that fails before its first event is answered with 500 by Micronaut, whatever the handlers; after the first event the response has started, so a failure ends the stream instead.
- **A client that disconnects** cancels a `Mono` call, or a stream that has sent an event, through every policy: the delegate's `Mono` or `Flux` is cancelled and the bulkhead frees its slot. Before a stream's first event Micronaut does not notice the disconnect, so the call goes on until its `@Timeout`: give streaming methods one.

A Micronaut `@Transactional` around a retried `Mono` or `Flux` method of an annotated class fails the startup, as for other methods ([R4C-MN-005](../errors.md#r4c-mn-005)).

## Configuration :id=configuration

The policies read the application's configuration under `resilience4cata`, with the keys of the
[configuration reference](../reference/configuration.md) in kebab-case or camelCase:

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

- **Instance ids** other than letters, digits, `_` and `-` (the derived `<type>#<method>(<parameter types>)`)
  contain dots, which Micronaut would read as nesting: write the id in double quotes inside the YAML key, as
  above (`'"com.acme.StockClient#available(java.lang.String)"'`), or give the policy a `name`.
- **Lists** (`retry-on`, `abort-on`, `record-on`, `ignore-on`) can be YAML lists or comma-separated values.
- **Placeholders** (`${…}`) are resolved.
- **Precedence is Micronaut's**: command line over system properties over environment variables over
  `application-<environment>.yml` over `application.yml`. Environment variables use the
  [`R4C_` or `RESILIENCE4CATA_` names](configuration.md) of `resilience4cata-config`, read as they are rather
  than through Micronaut's mapping. A `resilience4cata.properties` file on the class path is still read, below all
  of them.
- **Environments**: Micronaut's (`application-prod.yml`) are the natural way; resilience4cata's own
  `profiles.<name>.*` keys keep working too, separately.
- Configuration is read once, at startup, like everywhere else in resilience4cata: there is no refresh, and the
  beans are not `@Refreshable`.

**Validation at startup.** The configuration and the registry are `@Context` beans, so an unknown key or a bad
value stops the startup with every problem at once, naming the file where it was set (Micronaut keeps no line
numbers):

```text
Bean definition [dev.resilience4cata.config.ResilienceConfig] could not be loaded: Error instantiating bean of type [dev.resilience4cata.config.ResilienceConfig]

Message: Invalid resilience4cata configuration (2 problems):
  [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts' (application.yml): ... Did you mean 'resilience4cata.defaults.retry.max-attempts'?
  [R4C-CFG-002] invalid value 'soon' for 'resilience4cata.defaults.retry.delay' (application.yml): ...
```

At startup, each `resilience4cata.instances.<id>.*` key must match a policy instance
([`R4C-CFG-013`](../errors.md#r4c-cfg-013)). Micronaut creates singletons lazily by default, so the instances of
most beans do not exist yet then: an unmatched key is only logged as a warning, which may be a false alarm. When
the context creates its singletons eagerly (`eagerInitSingletons(true)`), the instances of singletons exist and an
unmatched key stops the startup, unless the application has protected beans that are not singletons
(`@Prototype`, `@Refreshable`, request scope), whose instances appear when they are created: then it stays a
warning. `resilience4cata.micronaut.verify-instances` (`fail`, `warn`, `off`) overrides all of these.

## Customizing the registry :id=beans

Customize the registry builder the Micronaut way, with a `BeanCreatedEventListener`, for example a named config
in code:

<!-- snippet: micronaut-customizer -->
```java
@Singleton
BeanCreatedEventListener<ResilienceRegistry.Builder> slowBackends() {
    return event -> event.getBean().retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
}
```

Listeners run in `@Order`, before the configuration, so the configuration wins: `configs.slow.retry.delay` above
overrides the listener's delay and keeps its `maxAttempts`. A `defaults.<policy>.*` key replaces a listener's
`<policy>Defaults(...)` for that policy.

Every `EventListener` bean is registered for the event type in its generic signature (a raw listener gets every
event):

<!-- snippet: micronaut-listener -->
```java
@Singleton
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.instance() + " is now " + transition.to());
}
```

Listeners run on the thread that caused the event ([events](observability.md#events)). A listener bean must not
depend on a protected bean: the registry needs the listener, and the protected bean needs the registry.

A `Ticker` bean (the testkit's `ManualClock`) becomes the registry's clock. To build the registry yourself,
declare a bean with `@Replaces(ResilienceRegistry.class)`; the `ResilienceConfig` bean is still there to apply
the configuration with `config.applyTo(builder)`.

## Metrics, health and the endpoint :id=management

- **Metrics**: with `resilience4cata-micrometer` on the class path and a `MeterRegistry` bean (from
  `micronaut-micrometer-core`), the [Micrometer meters](micrometer.md) are registered automatically. Successful
  calls publish no events, so they cost nothing.
- **Health**: with `micronaut-management`, the `resilience4cata` health indicator reports every circuit breaker
  (state, failure and slow-call rates, calls in the window). It stays **UP** with open breakers: an open breaker
  is the application protecting itself from a failing dependency, not a reason to restart it or take it out of
  load balancing. List the breakers that should turn it DOWN (UNKNOWN while half open) in
  `resilience4cata.micronaut.health.down-when-open` (`*` for all). It is neither `@Liveness` nor `@Readiness`, so
  it counts only in `/health`.
- **Endpoint**: `GET /resilience4cata` returns `registry.snapshot()` as JSON, and `GET /resilience4cata/{instance}`
  the policies of one instance id. It is read-only and keeps no event history. Like Micronaut's own endpoints it
  is sensitive by default: configure it with `endpoints.resilience4cata.enabled` and
  `endpoints.resilience4cata.sensitive`.

## Switches :id=switches

| Property | Default | Effect |
|---|---|---|
| `resilience4cata.micronaut.verify-instances` | `warn` (`fail` with eager singletons) | `fail`, `warn` or `off` for instance keys that match no instance at startup |
| `resilience4cata.micronaut.exclude` | empty | Fully qualified class names of beans to leave alone (R4C-MN-004 and R4C-MN-005 are not checked) |
| `resilience4cata.micronaut.allow-retry-inside-transaction` | `false` | Allow `@Retry`/`@Hedge` on transactional methods of annotated classes (one transaction for every attempt) instead of failing with R4C-MN-005 |
| `resilience4cata.micronaut.health.enabled` | `true` | The health indicator |
| `resilience4cata.micronaut.health.down-when-open` | empty | Breaker ids (or `*`) that turn the health DOWN while open |
| `resilience4cata.micronaut.metrics.enabled` | `true` | Register the Micrometer meters |

## Tests :id=testing

Give the test context a `ManualClock` bean: retry pauses, bulkhead and rate limiter waits and timeouts follow it,
so a test never sleeps ([testing guide](testing.md)).

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

The test class is annotated `@MicronautTest` (from `micronaut-test-junit5`).

## Native images :id=native

Nothing to add for resilience4cata:

- the annotation processor writes GraalVM reachability metadata for every generated class
  (`META-INF/native-image/dev.resilience4cata.generated/…`), which covers `Resilient.of` for `@ResilientBean`
  beans;
- with `-Ar4c.inject=micronaut`, Micronaut's own bean definitions create the generated subclasses, without
  reflection;
- the endpoint reads the snapshot records through bean introspections generated when the module was compiled, not
  through reflection.

A GraalVM 25 native image of a small application with an `@ResilientBean` interface, an annotated class, YAML
configuration, the endpoint and Micrometer was built and run by hand while developing the module; the project's CI
does not build native images. Build yours with Micronaut's Gradle or Maven plugin, which also puts
`application.yml` in the image. Exception classes named in external configuration (`retry-on: com.acme.Busy`) are
loaded by name: name them in the annotations instead, or register them for reflection. The
retry-inside-transaction check (`R4C-MN-005`) reads class files, so a native image skips it: it runs in the JVM
build and tests.

## Limits :id=limits

- Micronaut has its own `@Retryable`, `@CircuitBreaker` and `@Fallback` in `io.micronaut.retry.annotation`: check
  the imports, an IDE may pick the wrong one. Do not combine them with resilience4cata's on one method.
- Annotated interfaces: inject the interface; calls on `this` inside the implementation are not protected; one
  annotated interface per `@ResilientBean`.
- Annotated classes need `-Ar4c.inject=micronaut` to be created by Micronaut, or a factory. Only the class's own
  bean definition is left out: subclass beans, beans of the class from `@Factory` methods and a test's `@MockBean`
  stay. `@Factory` classes cannot be annotated.
- Keep policies off configuration classes (`@ConfigurationProperties`, `@EachProperty`). Micronaut's processor writes
  its configuration metadata once per compilation round, and it sees the generated subclass a round later, so it
  warns `Error occurred writing configuration metadata`, which fails a `-Werror` build. Put the configuration in its
  own class and the policies on a bean that receives it: `@EachBean(BackendConfig.class)` gives one protected bean
  per `@EachProperty` entry.
- With annotated classes, Micronaut's AOP runs outside the policies; a retried or hedged transactional method fails
  ([`R4C-MN-005`](../errors.md#r4c-mn-005)) unless allowed.
- Methods that the constructor calls on `this` run before the policies are set up
  ([annotated classes](annotated-classes.md)).
- No event history in the endpoint, no write operations (`forceOpen`, `reset`) in it, no refresh of the
  configuration. Micronaut 4 and Java 21 are not supported by this module.

**Compared with `micronaut-retry` and resilience4j-micronaut.** Both work through Micronaut's `@Around` AOP: every
advised call goes through a method interceptor chain at run time, and the configuration is looked up per method.
resilience4cata's policies are generated code that Micronaut only instantiates. No benchmark of the three inside
Micronaut has been run, so no number is given here; see [performance](../performance.md) for what has been
measured.
