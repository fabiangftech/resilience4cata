# Getting started

> resilience4cata 0.1.0 is being prepared and is **not yet published** to Maven Central. Until it is, build it from source and install it in your local repository, or use the coordinates below once the release is out.

This page takes you from an empty build to a protected service, its configuration, its events and its tests. Every Java snippet on it is compiled and run by `examples/src/test/java/dev/resilience4cata/examples/GuideTest.java`.

## Dependencies

Coordinates: groupId `dev.resilience4cata`, version `0.1.0`. Java 21 or newer.

| Artifact | Scope | What it is |
|---|---|---|
| `resilience4cata-core` | implementation | Policies, registry, `Resilient`, `Pipeline`, events. Depends only on JSpecify |
| `resilience4cata-annotations` | compile only | The annotations. `CLASS` retention, so not needed at runtime |
| `resilience4cata-processor` | annotation processor | Generates the code. **Mandatory** for annotations; never on the runtime class path |
| `resilience4cata-config` | implementation, optional | External configuration: properties files, environment, system properties |
| `resilience4cata-testkit` | test, optional | `ManualClock` and seeded jitter for tests |
| `resilience4cata-bom` | platform | Aligns the versions of all of the above |

**Gradle (Groovy DSL).** The annotation processor path is resolved separately from the compile class path, so it needs the BOM too:

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.1.0')

    implementation 'dev.resilience4cata:resilience4cata-core'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-config'        // optional
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'   // optional
}
```

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-core")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-config")        // optional
    testImplementation("dev.resilience4cata:resilience4cata-testkit")   // optional
}
```

Annotated types in test sources need the test configurations as well: `testCompileOnly` for the annotations and `testAnnotationProcessor` for the processor (plus the BOM on `testAnnotationProcessor`).

The processor is registered with Gradle as an *isolating* incremental processor, so editing one file recompiles only what depends on it.

**Maven.** Import the BOM, add the annotations as `provided`, and put the processor on `annotationProcessorPaths` with an explicit version:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.resilience4cata</groupId>
      <artifactId>resilience4cata-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-core</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-annotations</artifactId>
    <scope>provided</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.13.0</version>
      <configuration>
        <release>21</release>
        <annotationProcessorPaths>
          <path>
            <groupId>dev.resilience4cata</groupId>
            <artifactId>resilience4cata-processor</artifactId>
            <version>0.1.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Add `resilience4cata-config` (default scope) and `resilience4cata-testkit` (`test` scope) the same way if you use them.

### JPMS

In a named module, read `core` (and `config` or `testkit` if you use them), read the annotations only at compile time, and export the packages that hold annotated types to `core`, which instantiates the generated classes:

```java
module com.acme.shop {
    requires dev.resilience4cata.core;
    requires static dev.resilience4cata.annotations;
    requires dev.resilience4cata.config;              // optional

    exports com.acme.shop.clients to dev.resilience4cata.core;
}
```

Without the `exports … to dev.resilience4cata.core`, `Resilient.of` fails with [`R4C-RT-005`](errors.md#r4c-rt-005), whose message names the line to add.

## Your first annotated interface

Annotate the interface of the dependency you call. Here a price lookup is retried up to three times, guarded by a circuit breaker named `prices`, interrupted after two seconds, and answered with a fallback when everything fails:

```java
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

At compile time the processor generates `PriceClient$Resilient`, a plain Java class that implements `PriceClient` and calls your implementation through the policies. Wrap your implementation with it:

```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);

BigDecimal price = prices.price("sku-1");   // retried, timed out after 2 s, guarded by the "prices" breaker
```

- The policies nest in a fixed order, outermost first: `Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → method`. So each attempt is recorded by the breaker and has its own timeout, and the fallback also sees rejections such as an open breaker ([execution order](features.md#execution-order)).
- Durations are strings (`"250ms"`, `"2s"`, `"PT2S"`), checked at compile time. A wrong attribute, an invalid duration or a fallback with the wrong signature is a compile error with a code from the [error reference](errors.md).
- Attributes you don't write come from the registry's defaults (for `@Retry`: 3 attempts, 200 ms exponential backoff with full jitter).
- A method that returns `CompletionStage<T>` or `CompletableFuture<T>` gets the asynchronous version of every policy, without blocking threads ([features](features.md)).
- Only the interface's annotations count; calls your implementation makes to itself don't go through the policies. Annotate a class instead if you need that.

## An annotated class

A non-final class can carry the annotations itself. The processor then generates a subclass, `StockService$Resilient`, that overrides each protected method, so calls the object makes to itself are protected too:

```java
public class StockService {
    private final Warehouse warehouse;

    public StockService(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    @Retry(maxAttempts = 3, delay = "50ms")
    public int stock(String sku) {
        return warehouse.count(sku);
    }

    public int total(List<String> skus) {
        int total = 0;
        for (String sku : skus) {
            total += stock(sku);   // self-invocation: goes through stock()'s retry
        }
        return total;
    }
}
```

`Warehouse` is any interface with `int count(String sku)`. Create the service with the constructor arguments after the registry:

```java
StockService stock = Resilient.create(StockService.class, registry, warehouse);
int total = stock.total(List.of("a", "b"));   // each stock(sku) call is retried
```

`new StockService$Resilient(registry, warehouse)` does the same with compile-time checking. Final, abstract, sealed and inner classes, records and enums are compile errors ([annotated classes](features.md#annotated-classes)).

## The programmatic API

For code you cannot annotate (a third-party class, a lambda), build a `Pipeline` once and reuse it:

```java
Pipeline<String> geocode = Pipeline.builder(registry, "geocoder", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(1)))
        .fallback(failure -> "unknown")
        .build();

String city = geocode.execute(() -> legacyGeocoder.city("10115"));
```

The policies nest in the same order as with annotations, whatever order you call the builder methods in ([programmatic API](features.md#programmatic-api)).

### Which style to use

| Style | Use it when | Self-invocation | Cost |
|---|---|---|---|
| Annotated interface + `Resilient.of` | You own the interface; the implementation may be anything (a generated HTTP client, a lambda) | Not intercepted | Generated straight-line code, the fastest option |
| Annotated class + `Resilient.create` | You own a non-final class and its methods call each other | Intercepted | Same as the interface style |
| `Pipeline` | You cannot annotate the code, or the policies are chosen at runtime | n/a | Slightly more per call; its one call site becomes megamorphic when many pipelines share a JVM ([benchmarks](benchmarks.md)) |

## Named configurations and composed annotations

Real services share settings. Put them in the registry under a name, refer to the name from annotations, and bundle the annotations into one of your own:

```java
@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
public @interface RemoteCall {
}

@RemoteCall
public interface ShippingClient {

    String track(String parcel) throws IOException;

    @Retry(maxAttempts = 1)   // overrides one attribute of the bundle: never retried
    String cancel(String parcel) throws IOException;
}
```

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryDefaults(r -> r.delay(Duration.ofMillis(100)))
        .retry("external", r -> r.maxAttempts(5))
        .circuitBreaker("external", cb -> cb.failureRateThreshold(25).waitInOpen(Duration.ofSeconds(30)))
        .build();
```

- Values resolve, lowest precedence first: built-in defaults, `*Defaults(...)`, the named config, the composed annotation, the type-level annotation, the method-level annotation. An annotation only overrides the attributes it writes.
- `config` picks a template; `name` picks the stateful instance (a breaker's window, a rate limiter's permits). Without a `name`, each method has its own instance. Two methods with the same `name` share it.
- Unknown config names fail when the service is wrapped, not on the first call. Configuration is resolved once, then immutable: there is no hot reload.

## External configuration

With `resilience4cata-config`, operators can retune the same services without recompiling. Put a `resilience4cata.properties` on the class path:

```properties
configs.external.retry.maxAttempts=4
configs.external.circuitBreaker.waitInOpen=10s
instances.prices.circuitBreaker.failureRateThreshold=30
```

and apply it to the registry builder:

```java
ResilienceConfig config = ResilienceConfig.load();   // resilience4cata.properties, R4C_* variables, r4c.* properties
ResilienceRegistry registry = config
        .applyTo(ResilienceRegistry.builder()
                .retry("external", r -> r.maxAttempts(5)))   // the file's maxAttempts=4 wins
        .build();
ShippingClient shipping = Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);
config.verifyInstances(registry);   // fails if an instances.<id> key matches no instance
```

- `defaults.<policy>.*`, `configs.<name>.*` and `configs.<name>.extends` define defaults and named configs; `instances.<id>.*` retunes one instance and beats its annotations; `profiles.<profile>.…` with `profiles.active` switch settings per environment.
- Environment variables: `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS=6`. System properties: `-Dr4c.configs.external.retry.maxAttempts=6`. Sources rank classpath < files < environment < system properties.
- Every problem (unknown key, bad value, cycle in `extends`) is reported at once in one `ConfigException`, with the key's source and line and a "did you mean". Keys and codes are in [features](features.md#loading-external-configuration-config-module) and the [error reference](errors.md).

## Events and snapshots

Listeners are registered on the registry builder, for one event type, a policy's event interface, or `ResilienceEvent` for all of them. Event types nobody listens to cost nothing:

```java
private static final System.Logger LOG = System.getLogger("shop.resilience");
```

```java
LongAdder retries = new LongAdder();
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(CircuitBreakerEvent.StateTransition.class,
                t -> LOG.log(Level.WARNING, "{0}: {1} -> {2}", t.instance(), t.from(), t.to()))
        .listen(RetryEvent.Retrying.class, e -> retries.increment())
        .build();
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);
prices.price("sku-1");   // the first attempt fails, the second succeeds

CircuitBreakerSnapshot breaker = registry.circuitBreaker("prices").snapshot();
ResilienceSnapshot all = registry.snapshot();   // every instance created so far
```

- Listeners run synchronously on the thread that caused the event; keep them fast. A listener that throws does not affect the call.
- `breaker.state()`, `failureRate()`, `bufferedCalls()` and friends are a read-only copy, for health checks and metrics. `registry.circuitBreaker("prices")` also has `forceOpen()`, `disable()` and `reset()`.
- The event list is in [events and state inspection](features.md#events-and-state-inspection).

## Testing with `testkit`

A `ManualClock` replaces the clock, the sleeps and the timers of one registry, so a test with second-long backoffs finishes at once:

```java
ManualClock clock = new ManualClock();
ResilienceRegistry registry = ResilienceTesting.registry(clock).build();   // also seeds the jitter
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);

assertThat(prices.price("sku-1")).isEqualByComparingTo("9.99");   // one retry, no real sleep
assertThat(clock.elapsed()).isBetween(Duration.ZERO, Duration.ofMillis(100));
```

`clock.advance(Duration.ofSeconds(30))` moves time forward (for example past a breaker's `waitInOpen`), and `ResilienceTesting.elapseWaitInOpen(breaker, clock)` does exactly that for one breaker. Details and limits are in [testing](features.md#testing).

## Troubleshooting

**`[R4C-RT-003] No generated class … found.`** The processor did not run on the type (or the type has no resilience annotation). The message reads:

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
```

Add the processor to `annotationProcessor` (Gradle) or `annotationProcessorPaths` (Maven) as shown above. Putting it on the compile class path is not enough: since JDK 23, `javac` no longer runs processors found on the class path unless asked to. There is no reflection fallback, by design.

**`warning: No processor claimed any of these annotations`** with `-Xlint:all -Werror`. Builds with `-Xlint:all -Werror` work: when resilience4cata's processor is the only one on the processor path it claims the annotations it sees, and next to other processors it claims only its own (`dev.resilience4cata.annotation.*`), so they still see everything else. If the warning lists other annotations, another processor left them unclaimed; add `-Xlint:-processing` to the compiler arguments.

**`[R4C-RT-004]`**: `core` and `processor` versions differ. Use the BOM.

**`[R4C-RT-005]`**: a named module does not export the package to `dev.resilience4cata.core`; see [JPMS](#jpms).

Every compile-time (`R4C-APT`, `R4C-ATR`, …), runtime (`R4C-RT`) and configuration (`R4C-CFG`) error has a section with a wrong example and the fix in the [error reference](errors.md).
