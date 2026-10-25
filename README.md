# resilience4cata

Annotation-driven resilience for **plain Java 21+**: retry, circuit breaker, rate limiter, timeout, bulkhead and fallback, declared with annotations that work **without Spring, CDI, AspectJ or agents**. A compile-time annotation processor turns them into plain, straight-line Java that calls an allocation-free policy protocol, so the overhead stays in the low nanoseconds and the success path allocates nothing. Sync and async (`CompletionStage`), interfaces and annotated classes, composed annotations, named configurations with external overrides, typed events and snapshots, a programmatic `Pipeline` for code you cannot annotate, and a `testkit` with a manual clock.

> **Status: 0.1.0, not yet published.** The MVP is complete and tested; the first release to Maven Central is being prepared. Until then, build from source (`./gradlew build`, Java 21 toolchain).

## Performance

Measured with JMH against Resilience4j 2.4.0 under equivalent configuration, on AWS `c7g.xlarge` (4 Graviton3 cores, JDK 21), time per call:

| Scenario | Threads | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| Retry, first attempt succeeds | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Circuit breaker, every call succeeds | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Sync timeout | 1 | **22.2 ns**, 0 B | 8842 ns, 176 B |
| Bulkhead with free slots | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |

Not every case is a win (the free bulkhead is a tie), and most real calls take milliseconds, so the point is near-zero overhead that keeps scaling with threads. Methods, all results (also on an Apple M1), regressions and fairness adjustments are in [docs/en/benchmarks.md](docs/en/benchmarks.md). An allocation gate (`./gradlew :benchmark:allocationCheck`, run in CI on every pull request) fails the build if a sync success or rejection path starts allocating.

## Installation

Coordinates: `dev.resilience4cata`, version `0.1.0`. The processor is mandatory and only goes on the annotation processor path; the annotations are compile-only.

Gradle (Groovy DSL):

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.1.0')
    implementation 'dev.resilience4cata:resilience4cata-core'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

Gradle (Kotlin DSL):

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)
    implementation("dev.resilience4cata:resilience4cata-core")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

Maven:

```xml
<dependencies>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-core</artifactId>
    <version>0.1.0</version>
  </dependency>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-annotations</artifactId>
    <version>0.1.0</version>
    <scope>provided</scope>
  </dependency>
</dependencies>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>0.1.0</version>
  </path>
</annotationProcessorPaths>
```

Optional modules: `resilience4cata-config` (properties files, environment variables, system properties) and `resilience4cata-testkit` (manual clock). JPMS, the BOM with Maven and troubleshooting are in [getting started](docs/en/getting-started.md).

## Example

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

ResilienceRegistry registry = ResilienceRegistry.builder().build();
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);

BigDecimal price = prices.price("sku-1");   // retried, timed out after 2 s, guarded by the "prices" breaker
```

The processor generates `PriceClient$Resilient` at compile time; a wrong attribute or fallback signature is a compile error. Non-final classes can be annotated too (`Resilient.create(...)`), and then calls the object makes to itself are protected as well.

## Documentation

The docs are plain Markdown in [`docs/`](docs/), served as a [Docsify](https://docsify.js.org) site (`python3 -m http.server -d docs 3000`, then open http://localhost:3000):

- [Getting started](docs/en/getting-started.md): dependencies, first interface, annotated classes, `Pipeline`, configuration, events, testing
- [Features](docs/en/features.md): every annotation, attribute and rule
- [Architecture](docs/en/architecture.md) and [decisions](docs/en/README.md)
- [Benchmarks](docs/en/benchmarks.md) and the [error reference](docs/en/errors.md)

The documentation is bilingual. English is the source of truth; the Spanish version has the same pages: [documentación en español](docs/es/README.md) ([primeros pasos](docs/es/getting-started.md)).

## License

[Apache License 2.0](LICENSE).
