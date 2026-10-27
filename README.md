# resilience4cata

[![Maven Central](https://img.shields.io/maven-central/v/dev.resilience4cata/resilience4cata-core?label=Maven%20Central)](https://central.sonatype.com/namespace/dev.resilience4cata) [![build](https://github.com/fabiangftech/resilience4cata/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/fabiangftech/resilience4cata/actions/workflows/build.yml) [![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

[![Quality gate status](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=coverage)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Maintainability issues](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=software_quality_maintainability_issues)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Reliability issues](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=software_quality_reliability_issues)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Security issues](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=software_quality_security_issues)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata)

Annotation-driven resilience for **plain Java 21+**: retry, hedging, circuit breaker, rate limiter, adaptive concurrency limit, timeout, bulkhead and fallback, declared with annotations that work **without Spring, CDI, AspectJ or agents**. A compile-time annotation processor turns them into plain, straight-line Java that calls an allocation-free policy protocol, so the overhead stays in the low nanoseconds and the success path allocates nothing.

Sync and async (`CompletionStage`), interfaces and annotated classes, composed annotations, named configurations with external overrides, typed events and snapshots, a programmatic `Pipeline` for code you cannot annotate, and a `testkit` with a manual clock.

**Documentation: [resilience4cata.dev](https://resilience4cata.dev)** (English and Spanish). Status: 0.9.0 on Maven Central; names can still change before 1.0.

## Install

Java 21 or newer. Two lines: `resilience4cata-all` (core, the annotations and external configuration) and the annotation processor, which is mandatory and goes only on the annotation processor path.

Gradle:

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:0.9.0'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:0.9.0'
}
```

Maven:

```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>0.9.0</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>0.9.0</version>
  </path>
</annotationProcessorPaths>
```

The Gradle Kotlin DSL, the BOM and the individual modules (to keep the annotations compile-only), `testkit`, IDEs, JPMS and other annotation processors (Lombok, MapStruct) are covered in [installation](docs/en/start/installation.md). For Spring Boot 4.1+, `resilience4cata-spring` configures the registry from `application.yml` and protects annotated beans without AOP ([Spring Boot guide](docs/en/guides/spring.md)). For Micronaut 5.2+ (Java 25), `resilience4cata-micronaut` does the same ([Micronaut guide](docs/en/guides/micronaut.md)).

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
```

```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);

BigDecimal price = prices.price("sku-1");   // retried, timed out after 2 s, guarded by the "prices" breaker
```

The processor generates `PriceClient$Resilient` at compile time; a wrong attribute or fallback signature is a compile error. Non-final classes can be annotated too (`Resilient.create(...)`), and then calls the object makes to itself are protected as well. The [quickstart](docs/en/start/quickstart.md) runs this example end to end in five minutes.

## Performance

Measured with JMH against Resilience4j 2.4.0 under equivalent configuration, on AWS `c7g.xlarge` (4 Graviton3 cores, Amazon Corretto 21), time and allocation per call:

| Scenario | Threads | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| Retry, first attempt succeeds | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Circuit breaker, every call succeeds | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Sync timeout | 1 | **22.2 ns**, 0 B | 8842 ns, 176 B |
| Bulkhead with free slots | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |

Not every case is a win (the free bulkhead is a tie), and most real calls take milliseconds, so the point is near-zero overhead that keeps scaling with threads. The sync timeout measured 23.7 ns in a later run, a difference still under investigation. Methods, every result (also on an Apple M1), regressions and fairness adjustments are in [benchmarks](docs/en/benchmarks.md). An allocation gate (`./gradlew :benchmark:allocationCheck`, run in CI on every pull request) fails the build if a sync success or rejection path starts allocating.

## Documentation

The site at [resilience4cata.dev](https://resilience4cata.dev) is the Markdown in [`docs/`](docs/) (a [Docsify](https://docsify.js.org) site, pre-rendered for search engines by the `site` module; preview it with `./gradlew :site:serve`). Good places to start:

- [Installation](docs/en/start/installation.md), [quickstart](docs/en/start/quickstart.md) and [how it works](docs/en/start/how-it-works.md)
- [Policies](docs/en/policies/README.md): every annotation, attribute and default
- [Configuration](docs/en/guides/configuration.md): properties files, environment variables, system properties
- [Error reference](docs/en/errors.md): every `R4C-…` code with a wrong example and the fix

The documentation is bilingual. English is the source of truth; the Spanish version has the same pages: [documentación en español](https://resilience4cata.dev/es/) ([inicio rápido](docs/es/start/quickstart.md)).

## Building from source

`./gradlew build` compiles, tests and builds the Javadoc of every module (the build runs on JDK 17 or newer and compiles with a Java 21 toolchain). `./gradlew publishToMavenLocal` installs the artifacts in your local Maven repository.

## License

[Apache License 2.0](LICENSE).
