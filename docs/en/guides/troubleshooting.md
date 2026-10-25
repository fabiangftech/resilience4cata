# Troubleshooting

Problems people hit with resilience4cata, by symptom, with the cause and the fix.

Every error the library reports starts with a code such as `[R4C-RT-003]`; the [error reference](../errors.md) has a section for each one, with a wrong example and the fix.

## Build and code generation :id=build

### `Resilient.of` fails: "No generated class … found" :id=no-generated-class

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
```

The processor did not run when the interface was compiled, or the interface has no resilience annotation. The processor is mandatory: there is no reflection fallback.

- **Gradle:** add `annotationProcessor 'dev.resilience4cata:resilience4cata-processor'` (and `annotationProcessor platform(…bom…)` if you use the BOM, because the processor path is resolved separately). For annotated types under `src/test`, use `testAnnotationProcessor` and `testCompileOnly` too.
- **Maven:** list the processor in `maven-compiler-plugin`'s `<annotationProcessorPaths>`. With maven-compiler-plugin 3.12.0 or later, a `<path>` without `<version>` takes its version from `dependencyManagement` (the imported BOM); older versions of the plugin need an explicit `<version>`.
- **Processor only on the class path:** since JDK 23, `javac` no longer runs processors found on the class path unless asked to. Put it on the processor path.
- **Check:** after a build, `build/generated/sources/annotationProcessor/java/main/<package>/<Type>$Resilient.java` (Gradle) or `target/generated-sources/annotations/…` (Maven) exists.

See [R4C-RT-003](../errors.md#r4c-rt-003) and [Installation](../start/installation.md).

### `[R4C-RT-004]`: the generated class does not match :id=version-mismatch

`resilience4cata-core` and `resilience4cata-processor` have different versions. Take both from the BOM, or give them the same version. Then rebuild cleanly so the old generated classes go away. See [R4C-RT-004](../errors.md#r4c-rt-004).

### `[R4C-RT-005]` in a modular application :id=jpms

`core` loads `<Type>$Resilient` reflectively, so your module must export (or open) the package to it: `exports com.acme.shop.clients to dev.resilience4cata.core;`. See [R4C-RT-005](../errors.md#r4c-rt-005).

### The IDE does not run the processor :id=ide

The build works on the command line, but in the IDE `$Resilient` classes are missing or stale.

- **IntelliJ IDEA:** for Gradle and Maven projects, IDEA reads the processor path from the build. If you build with IDEA's own compiler, enable *Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing*, or let Gradle build the project (*Build and run using: Gradle*).
- **Eclipse:** annotation processing must be enabled for the project (Buildship for Gradle, m2e's annotation processing support for Maven).
- After changing annotations, rebuild the module: the processor writes the generated source when the annotated interface is compiled.

See [Installation](../start/installation.md) for each IDE.

### Lombok, MapStruct or other processors in the same build :id=other-processors

resilience4cata's processor can run next to other annotation processors. Put all of them on the same processor path:

- **Gradle:** one `annotationProcessor` line per processor (`org.projectlombok:lombok`, `org.mapstruct:mapstruct-processor`, `dev.resilience4cata:resilience4cata-processor`).
- **Maven:** once `<annotationProcessorPaths>` is set, Maven runs only what it lists, so list Lombok and MapStruct there too.
- **Put Lombok before resilience4cata**, in Gradle's `annotationProcessor` lines and in Maven's `<annotationProcessorPaths>`. The generated subclass of an [annotated class](annotated-classes.md) mirrors the class's constructors, so it must see the constructors that Lombok adds. With resilience4cata first, an annotated class that uses `@RequiredArgsConstructor` (or another Lombok constructor) fails to compile with an error like `constructor … in class … cannot be applied to given types`.

The processor only reads the interfaces and classes that carry resilience annotations and claims only its own annotations when other processors are present, so it does not hide annotations from them.

### `-Xlint:all -Werror` fails with "No processor claimed any of these annotations" :id=xlint-processing

resilience4cata itself does not cause it: alone on the processor path it claims the annotations it sees, and next to other processors it claims only `dev.resilience4cata.annotation.*`. If the warning lists other annotations, another processor left them unclaimed; add `-Xlint:-processing` to the compiler arguments, or fix it in that processor.

### `-Werror` fails with an `auxiliaryclass` warning in generated code :id=auxiliary-class

An annotated interface declared as a second, non-public top-level type in another type's source file (`Clients.java` holding `public class Clients` and `interface PriceClient`) is an *auxiliary class*. The generated `PriceClient$Resilient` lives in its own file and refers to it, so `javac -Xlint:all` warns `auxiliary class PriceClient in Clients.java should not be accessed from outside its own source file`, and `-Werror` turns that into an error. Move the interface to its own file (`PriceClient.java`), or nest it as a member type of the class.

### Compile errors from the processor :id=compile-errors

The processor checks annotations at compile time and reports `R4C-APT`, `R4C-ATR`, `R4C-CMP`, `R4C-ASY`, `R4C-CLS`, `R4C-FB` and `R4C-SUB` errors on the annotated element. Common ones:

| Symptom | Code |
|---|---|
| annotation on a `static` or `private` method | [R4C-APT-002](../errors.md#r4c-apt-002) |
| annotation on a private interface | [R4C-APT-003](../errors.md#r4c-apt-003) |
| a duration such as `"2 s"` or `"1.5s"` | [R4C-ATR-001](../errors.md#r4c-atr-001) |
| `@Timeout("0")` | [R4C-ATR-002](../errors.md#r4c-atr-002) |
| an async method returning something other than `CompletionStage<T>` / `CompletableFuture<T>` (for example `? super T`) | [R4C-ASY-001](../errors.md#r4c-asy-001) |
| the fallback method's signature does not fit | [R4C-FB-003](../errors.md#r4c-fb-003) |
| an annotated class that is `final` | [R4C-SUB-001](../errors.md#r4c-sub-001) |

## Behaviour :id=behaviour

### A call inside my implementation is not retried :id=self-invocation

With `Resilient.of` (the interface style), the policies are in the generated wrapper, which calls your implementation. When the implementation calls one of its own methods, the call does not go through the wrapper:

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

`catalog.price("a")` is retried; `catalog.total(List.of("a"))` fails on the first error, because `total` calls `this.price` on the implementation. This is a property of the interface style, not a bug, and it is not worked around. To intercept self-calls, annotate a class instead: the generated subclass overrides the methods, so `this.price(…)` goes through the policies. See [Annotated classes](annotated-classes.md).

### Annotations on my implementation class have no effect :id=implementation-annotations

`Resilient.of(Interface.class, impl, registry)` uses only the annotations of the interface (and its superinterfaces). Annotations on the implementation class are not read by the wrapper. Annotating the class turns it into an [annotated class](annotated-classes.md): the processor generates a subclass for it, which you create with `Resilient.create(Type.class, registry, args…)` or `new Type$Resilient(registry, args…)`, and the class must then meet the rules for annotated classes (not `final`, not `sealed`, not abstract… see [R4C-SUB-001](../errors.md#r4c-sub-001) to [R4C-SUB-008](../errors.md#r4c-sub-008)). Pick one style per type.

### Retries stop as soon as the breaker opens :id=open-breaker-stops-retries

This is the default, on purpose. The retry is outside the circuit breaker (order: Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead), and `RetryConfig.DEFAULTS.abortOn` is `[CallNotPermittedException]`: once the breaker rejects an attempt, retrying would only be rejected again. The retry gives up with `GaveUp(reason = NOT_RETRYABLE)` and rethrows the `CallNotPermittedException` (or the fallback runs).

If you set `abortOn` yourself, keep `CallNotPermittedException` in the list unless you really want to retry into an open breaker. See [Retry](../policies/retry.md) and [Circuit breaker](../policies/circuit-breaker.md).

### A timeout does not stop my code :id=timeout-not-interrupting

A sync `@Timeout` runs the call on the caller's thread and **interrupts** it when the time is up. Code that ignores interrupts keeps running until it returns on its own, and only then does the caller get `CallTimeoutException`:

- classic blocking `java.net.Socket` / `InputStream` reads, and libraries built on them;
- busy loops that never check `Thread.interrupted()`;
- code that catches `InterruptedException` and carries on.

Fixes: set the I/O timeouts of the client itself (connect and read timeouts; `java.net.http.HttpClient`, NIO channels and most modern clients respond to interrupts or have their own timeouts), check `Thread.interrupted()` in long loops, and restore the interrupt flag when you catch `InterruptedException`. For async methods, the returned stage fails with `CallTimeoutException` on time, and the call's stage is cancelled if it is a `Future`; whether the work behind it stops depends on that code.

Timeouts are also coarse by design: never early, at most about two watchdog ticks late (a tick is the shortest timeout in use / 16, between 1 and 50 ms). See [Timeout](../policies/timeout.md).

### My external configuration is not applied :id=config-not-applied

Check, in order:

1. **The value is overridden by something with higher precedence.** Lowest first: built-in defaults, `defaults.*`, `configs.<name>.*`, annotation attributes, `instances.<id>.*`. A `defaults.*` key does not change an attribute that the annotation sets explicitly; use `instances.<id>.*` for that. See [precedence](../reference/configuration.md#precedence).
2. **The source is not read.** `ResilienceConfig.load()` reads only the classpath resource `resilience4cata.properties`, environment variables (`R4C_…`) and system properties **with the `r4c.` or `resilience4cata.` prefix**; other files need `ResilienceConfig.builder().file(…)` or `.classpath(…)`.
3. **The configuration is not applied to the registry.** Wrap your services with the registry built by `config.applyTo(ResilienceRegistry.builder()).build()` (or `config.toRegistry()`).
4. **The instance id does not match.** An `instances.<id>` key whose id matches nothing is silently unused until you ask. `config.describe()` lists every effective value with its source; `config.verifyInstances(registry)`, called after wrapping your services, fails on ids that match no instance and suggests the closest one:

<!-- snippet: troubleshooting-verify-instances -->
```java
ResilienceConfig config = ResilienceConfig.builder().properties("app.properties", properties).build();
System.out.println(config.describe()); // every effective value and where it comes from
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
Payments payments = Resilient.of(Payments.class, order -> "ok", registry);
config.verifyInstances(registry); // after every service is wrapped: fails on ids that match nothing
```

Derived ids look like `com.acme.Api#get(java.lang.String)` and must be quoted in keys: `instances."com.acme.Api#get(java.lang.String)".timeout.value=2s`. See [R4C-CFG-013](../errors.md#r4c-cfg-013) and [Configuration keys](../reference/configuration.md).

5. **You expected a reload.** Configuration is read once, when the registry and its instances are built; there is no hot reload.

### `IllegalArgumentException [R4C-REG-009]` from `registry.circuitBreaker(id)` :id=handle-not-found

The id is wrong, or the instance does not exist yet: instances are created when the interface is wrapped (`Resilient.of`, `Resilient.create`) or the `Pipeline` built. The message suggests the closest id. See [R4C-REG-009](../errors.md#r4c-reg-009).

### `IllegalStateException [R4C-REG-008]` when wrapping :id=shared-name

Two methods share a `name` (or a pipeline id) but resolve to different configurations. Methods that share an instance must agree on every attribute; give them different names, or the same attributes. See [R4C-REG-008](../errors.md#r4c-reg-008).
