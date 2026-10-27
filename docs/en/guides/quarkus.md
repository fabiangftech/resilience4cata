# Quarkus

Use resilience4cata in a Quarkus application: annotated beans are protected by the generated code, the registry
is configured from `application.properties`, and SmallRye Health and Micrometer see it.

The extension `resilience4cata-quarkus` (available from 0.5.0) is a regular Quarkus extension, with a runtime and
a deployment module. It adds nothing to the call path: there are no CDI interceptors. Its build steps make the
classes that the annotation processor generated the beans your application gets, so a call costs what it costs
without Quarkus, plus ArC's client proxy when the bean is normal-scoped.

**Requirements:** Quarkus 3.40 or newer (the extension is compiled against and tested on 3.40.1, the current LTS
line) and Java 21.

## Install :id=install

Add the extension and, as for any use of the annotations, the annotation processor
([installation](../start/installation.md)). The extension brings `resilience4cata-core` and
`resilience4cata-config`; Quarkus finds its deployment module by itself.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation enforcedPlatform("io.quarkus.platform:quarkus-bom:${quarkusPlatformVersion}")
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.8.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.8.0')

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
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.8.0")
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
    <version>0.8.0</version>
  </path>
  <!-- annotationProcessorPaths replaces discovery: list the other processors you use too -->
</annotationProcessorPaths>
```

<!-- tabs:end -->

> Without the annotation processor nothing is generated and the beans stay unprotected: the annotations have
> `CLASS` retention, so nothing at run time can notice they are there. With the extension's debug logging
> (`quarkus.log.category."dev.resilience4cata".level=DEBUG`) the build lists every bean it wires.

Generated classes are found in the application's index, like beans: a library jar with annotated classes needs a
Jandex index (or `quarkus.index-dependency`), as for its beans.

## Annotated classes :id=classes

Annotate the bean class itself:

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

The processor generates `OrderService$Resilient extends OrderService`. The extension vetoes `OrderService` and
makes `OrderService$Resilient` the bean in its place, with the class's scope, qualifiers and stereotypes. So:

- `@Inject OrderService` gets the generated class (behind ArC's client proxy, as any `@ApplicationScoped` bean);
  qualifiers, `@Named`, `@Alternative`/`@Priority` and `@Startup` keep working;
- calls on `this` (`findAgain` calling `find`) go through the policies, unlike CDI interceptors such as SmallRye
  Fault Tolerance's;
- field injection, initializer methods, `@PostConstruct` and observer methods are inherited; constructor injection
  works as well: the generated constructors take the `ResilienceRegistry` followed by the original parameters,
  with their annotations ([annotated classes](annotated-classes.md#constructor-injection)).

**Normal-scoped classes need a constructor without parameters.** `@ApplicationScoped`, `@RequestScoped` and
`@SessionScoped` beans are reached through a client proxy, which needs a non-private constructor without
parameters. Quarkus adds one to a class that has none, but not to the generated subclass of a class that has none,
so the build fails with [`R4C-QK-005`](../errors.md#r4c-qk-005). Use field injection as above, add a
package-private constructor without parameters (only the proxy uses it), or make the bean `@Singleton`.

A class with `@Produces` methods or fields cannot be replaced (producers are not inherited):
[`R4C-QK-004`](../errors.md#r4c-qk-004).

**Interceptors over the class.** CDI interceptors (`@Transactional`, `@Logged`, …) are applied by an ArC subclass
of the generated class, so they run outside the policies. For transactions that would mean one transaction for
every attempt, and after a failed attempt marks it rollback-only, a later successful attempt cannot commit. So a
method that is both retried or hedged and `@Transactional` (with a type that joins or starts a transaction) **fails
the build** with [`R4C-QK-002`](../errors.md#r4c-qk-002). Declare the policy on an interface instead (one
transaction per attempt, see below), move `@Transactional` to another bean that the method calls, or set
`quarkus.resilience4cata.allow-retry-inside-transaction=true` if one shared transaction is what you want.

## Annotated interfaces :id=interfaces

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

For every bean that implements an annotated interface, the extension registers a synthetic bean of the interface's
types, with the bean's qualifiers, created by `Resilient.of(PaymentsGateway.class, bean, registry)`. It is an
enabled alternative with a high priority, so it wins wherever the interface is injected:

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

- `@Inject CardPayments` (the class) still gets the bean itself, unprotected.
- The wrapper of an `@ApplicationScoped` bean is `@ApplicationScoped` too, behind its own client proxy, so the
  interface stays a normal-scoped bean (a test's `@InjectMock` on the interface works, and replaces the wrapper,
  policies included). The wrapper of a request- or session-scoped bean is a `@Singleton` that holds the bean's
  client proxy (those scopes keep working). Both are created at startup; the wrapper of a `@Dependent` bean is
  `@Dependent` too.
- CDI interceptors of the bean run **inside** the policies: a retried `@Transactional` method gets one
  transaction per attempt.
- Calls inside `CardPayments` on `this` are not protected (the wrapper is outside the object).
- A bean that implements two annotated interfaces, neither extending the other, fails the build with
  [`R4C-QK-003`](../errors.md#r4c-qk-003). Producer methods are not wrapped: return `Resilient.of(...)` from them
  yourself.

## REST clients :id=rest-client

Annotate a REST client interface like any other:

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

The REST client bean has the `@RestClient` qualifier. The extension adds a `@Default` bean around it, so injecting
the interface without a qualifier gets the protected client, and `@RestClient` still gets the raw one:

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

## Configuration :id=configuration

The policies read the application's configuration under `resilience4cata.` (or `r4c.`), with the keys of the
[configuration reference](../reference/configuration.md) in camelCase or kebab-case:

<!-- snippet: quarkus-properties -->
```properties
resilience4cata.defaults.retry.delay=50ms
resilience4cata.defaults.circuit-breaker.minimum-calls=20
resilience4cata.configs.slow.retry.delay=500ms
resilience4cata.instances.orders.circuit-breaker.failure-rate-threshold=25
resilience4cata.instances."dev.resilience4cata.quarkus.guide.PaymentsGateway#charge(java.lang.String,long)".timeout.value=3s
%dev.resilience4cata.defaults.retry.max-attempts=2
```

- The values come from SmallRye Config, so every Quarkus source works: `application.properties`, YAML
  (`quarkus-config-yaml`), `.env`, system properties, `-D`, and expressions (`${…}`).
- **Profiles are Quarkus's**: `%dev.`, `%test.`, `%prod.` and your own. resilience4cata's own `profiles.<name>.*`
  keys keep working too.
- **Precedence is Quarkus's**: system properties over environment variables over `application.properties`.
  Environment variables use the [`R4C_` or `RESILIENCE4CATA_` names](configuration.md) of
  `resilience4cata-config` (SmallRye cannot map them back to camelCase keys). A `resilience4cata.properties` file on
  the class path is still read, below all of them.
- **Instance ids** other than letters, digits, `_` and `-` (the derived `<type>#<method>(<parameter types>)`) go in
  double quotes, as above.
- Configuration is read once, at startup, like everywhere else in resilience4cata: there is no reload.
- Keys under `quarkus.resilience4cata.` are the extension's own [switches](#switches).

**Validation at startup.** The registry is created when the application starts, so an unknown key or a bad value
stops it with every problem at once, and the file and line where each key was set:

```text
ERROR [io.quarkus.runtime.Application] Failed to start application
dev.resilience4cata.config.ConfigException: Invalid resilience4cata configuration (1 problem):
  [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.maxAtempts' (application.properties:2). Did you mean 'resilience4cata.defaults.retry.maxAttempts'? ...
```

At startup the `resilience4cata.instances.<id>.*` keys are also checked against the policy instances created so
far ([`R4C-CFG-013`](../errors.md#r4c-cfg-013)). Quarkus creates beans lazily, so the default is a warning
(`quarkus.resilience4cata.verify-instances=warn`): the wrappers of interfaces are created at startup, but annotated
classes only when first used, unless they are `@Startup` beans. Set `fail` when every protected class is created at
startup.

## Customizers and listeners :id=beans

A `ResilienceRegistryCustomizer` bean adjusts the registry builder, for example a named config in code:

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

Customizers run in `@Priority` order, before the properties, so the properties win:
`resilience4cata.configs.slow.retry.delay` above overrides the customizer's delay and keeps its `maxAttempts`.

Every `EventListener` bean is registered for the event type in its bean types (a raw listener gets every event):

<!-- snippet: quarkus-listener -->
```java
@Produces
@Singleton
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.instance() + " is now " + transition.to());
}
```

A `Ticker` bean (the testkit's `ManualClock`) becomes the registry's clock. An application that produces its own
`ResilienceRegistry` bean replaces the extension's (customizers, listeners and metrics are then not applied); the
`ResilienceConfig` bean is still there to build it with `config.applyTo(builder)`.

## Metrics and health :id=observability

- **Metrics**: with `quarkus-micrometer` (and a registry such as `quarkus-micrometer-registry-prometheus`) and
  `resilience4cata-micrometer`, the [Micrometer meters](micrometer.md) are registered automatically and appear on
  `/q/metrics`. Without Micrometer but with `quarkus-opentelemetry` and `resilience4cata-opentelemetry`, the
  [OpenTelemetry metrics](opentelemetry.md) are registered instead (Micrometer is preferred when both are there, as
  SmallRye Fault Tolerance does). Successful calls publish no events, so they cost nothing.
- **Health**: with `quarkus-smallrye-health`, the `resilience4cata` readiness check (`/q/health/ready`) reports
  the state and failure rate of every circuit breaker. It stays **UP** with open breakers: an open breaker is the
  application protecting itself from a failing dependency, not a reason to take it out of load balancing. List the
  breakers that should turn it DOWN in `quarkus.resilience4cata.health.down-when-open` (`*` for all).
- There is no Dev UI card or endpoint yet: `registry.snapshot()` has everything, if you want to expose it.

## Dev UI :id=dev-ui

In dev mode (`quarkus dev`, or `./gradlew quarkusDev`), the Dev UI at `/q/dev-ui` has a resilience4cata card. Its
*Policies* page lists every policy instance the registry has created so far, refreshed every two seconds while the
page is visible:

| Policy | What the page shows |
|---|---|
| Circuit breakers | state, failure and slow-call rates, calls in the window (failed, slow) |
| Bulkheads | concurrent calls against the maximum, waiting calls |
| Rate limiters | available permits against the permits per period |
| Adaptive limits | algorithm, current limit and its bounds, calls in flight, latency of the last window and long-term latency |
| Retries, hedges | maximum attempts (and delay), the budget's calls and allowed retries or hedges, when a budget is set |
| Timeouts | the timeout |

Most instances are created on the first call to their method (an annotated class's when the bean is created), so a
policy appears once it has been used. A value with no meaning yet, such as a failure rate before `minimumCalls`, is
shown as a dash.

The page can also force a circuit breaker open, disable it or reset it, as `registry.circuitBreaker(id)` does: handy
to see how the application behaves with a dependency down. The card's *Guide* link opens this section.

None of this exists outside dev mode: the build steps that add the card and its JsonRPC service run only in local
development, so a test or production build has neither.

## SmallRye Fault Tolerance vs resilience4cata :id=smallrye

Quarkus ships its own fault tolerance, [SmallRye Fault Tolerance](https://quarkus.io/guides/smallrye-fault-tolerance)
(`quarkus-smallrye-fault-tolerance`, MicroProfile Fault Tolerance). Both work in the same application, but not on
the same method.

**Choose SmallRye Fault Tolerance** when you want the built-in, standard option: MicroProfile portability,
`@Asynchronous` and Mutiny return types, configuration that can be changed at run time, and nothing to add to the
build.

**Choose resilience4cata** when you want the same code to work outside CDI (plain Java, tests without a container,
Spring Boot), annotations and configuration checked at compile time and startup with coded errors, named configs,
bundles, per-instance overrides, classifiers and hedging, calls on `this` protected in annotated classes, or its
[overhead](../performance.md).

**Mechanism.** SmallRye Fault Tolerance is a CDI interceptor; resilience4cata is generated code that the extension
makes the bean. Neither protects a `new CardPayments()` that CDI did not create.

**Imports.** Both libraries name their annotations `@Retry`, `@CircuitBreaker`, `@Timeout`, `@Bulkhead` and
`@Fallback`, and IDEs import the first match. A method (or class) that carries annotations of both fails the build
with [`R4C-QK-001`](../errors.md#r4c-qk-001) when SmallRye Fault Tolerance is installed (a warning otherwise).
Prefer one library per module, or a [bundle](bundles.md) such as `@ExternalCall` so that the simple names do not
appear at all.

**Mapping.** The defaults differ too: check the [configuration reference](../reference/configuration.md).

| SmallRye / MicroProfile Fault Tolerance | resilience4cata |
|---|---|
| `@Retry(maxRetries = 3)` (retries after the first call) | `@Retry(maxAttempts = 4)` (attempts, the first included) |
| `@Retry(delay = 200, delayUnit = ChronoUnit.MILLIS)` | `@Retry(delay = "200ms", backoff = Backoff.FIXED)` (the default backoff is exponential) |
| `@Retry(jitter = 200)` (± a duration) | `@Retry(jitter = Jitter.FULL)` (`NONE`, `FULL` or `EQUAL`, a share of each delay) |
| `@Retry(maxDuration = 180000)` | `@Retry(maxDuration = "3m")` |
| `@Retry(retryOn = …, abortOn = …)` | `@Retry(retryOn = …, abortOn = …)` |
| `@ExponentialBackoff` | `@Retry(backoff = Backoff.EXPONENTIAL, multiplier = 2, maxDelay = "10s")` |
| `@CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5)` | `@CircuitBreaker(windowSize = 20, minimumCalls = 20, failureRateThreshold = 50)` (a percentage) |
| `@CircuitBreaker(delay = 5000)` | `@CircuitBreaker(waitInOpen = "5s")` |
| `@CircuitBreaker(successThreshold = 1)` | `@CircuitBreaker(halfOpenCalls = 1)` |
| `@CircuitBreaker(failOn = …, skipOn = …)` | `@CircuitBreaker(recordOn = …, ignoreOn = …)` |
| `@Timeout(value = 2, unit = ChronoUnit.SECONDS)` | `@Timeout("2s")` |
| `@Bulkhead(10)` | `@Bulkhead(maxConcurrent = 10)` |
| `@Bulkhead(value = 10, waitingTaskQueue = 10)` with `@Asynchronous` | `@Bulkhead(maxConcurrent = 10, maxWait = "…")` (waits for a time, sync or async) |
| `@RateLimit(value = 100, window = 1, windowUnit = ChronoUnit.SECONDS)` | `@RateLimiter(permits = 100, period = "1s")` |
| `@Fallback(fallbackMethod = "cached")` | `@Fallback(method = "cached")` |
| `@Fallback(MyHandler.class)` | `@Fallback(MyHandler.class)` (a `FallbackHandler`) |
| `@Asynchronous` with `CompletionStage` | a `CompletionStage`/`CompletableFuture` return type is enough |
| `quarkus.fault-tolerance."<id>".retry.max-retries=5` | `resilience4cata.instances.<id>.retry.maxAttempts=6` |
| `quarkus.fault-tolerance.global.retry.max-retries=5` | `resilience4cata.defaults.retry.maxAttempts=6` |

Changing a value at run time, which SmallRye allows, has no counterpart: resilience4cata resolves configuration once,
by design.

## Switches :id=switches

| Property | Phase | Default | Effect |
|---|---|---|---|
| `quarkus.resilience4cata.beans.enabled` | build | `true` | Make the generated classes the beans (subclasses replace annotated classes, wrappers stand for interface beans) |
| `quarkus.resilience4cata.beans.exclude` | build | empty | Bean classes (fully qualified) to leave alone |
| `quarkus.resilience4cata.allow-retry-inside-transaction` | build | `false` | Allow `@Retry`/`@Hedge` on `@Transactional` methods of annotated classes instead of failing with R4C-QK-002 |
| `quarkus.resilience4cata.health.enabled` | build | `true` | The readiness check, when SmallRye Health is present |
| `quarkus.resilience4cata.metrics.enabled` | build | `true` | Bind the registry to Micrometer or OpenTelemetry |
| `quarkus.resilience4cata.verify-instances` | run time | `warn` | `fail`, `warn` or `off` for instance keys that match nothing at startup |
| `quarkus.resilience4cata.health.down-when-open` | run time | empty | Breaker ids (or `*`) that turn the readiness check DOWN while open |

## Tests :id=testing

Produce a `ManualClock` bean in your test sources: retry pauses, bulkhead and rate limiter waits and timeouts follow
it, so a test never sleeps ([testing guide](testing.md)).

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

A normal-scoped bean is injected as a client proxy: read and change its state through methods, never through its
fields.

## Native images :id=native

The processor writes GraalVM reachability metadata for every generated class, and `core` initializes its
background threads at run time, so a native build (`-Dquarkus.native.enabled=true`) needs no extra configuration:
annotated classes are instantiated by ArC's generated code, interface wrappers by `Resilient.of`, which finds them
through that metadata. Configuration is read at run time. The project's CI does not build native images; the
[changelog](../changelog.md) records what was checked by hand.

## Limits :id=limits

- Dev mode: whether a live reload reruns the annotation processor has not been checked yet. If a change to the
  annotations seems ignored, restart dev mode.
- Normal-scoped annotated classes need a constructor without parameters (or field injection); annotated classes
  cannot declare producers.
- Interface wrappers: calls on `this` inside the implementation are not protected; one annotated interface per bean;
  producer methods are not wrapped.
- With annotated classes, CDI interceptors such as `@Transactional` run outside the policies; a retried or hedged
  transactional method fails the build ([`R4C-QK-002`](../errors.md#r4c-qk-002)) unless allowed.
- No Dev UI card, no endpoint, no hot reload of the configuration, no per-call metrics.
