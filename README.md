# resilience4cata

[![Maven Central](https://img.shields.io/maven-central/v/dev.resilience4cata/resilience4cata-core?label=Maven%20Central)](https://central.sonatype.com/namespace/dev.resilience4cata) [![build](https://github.com/fabiangftech/resilience4cata/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/fabiangftech/resilience4cata/actions/workflows/build.yml) [![Quality gate status](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=fabiangftech_resilience4cata&metric=coverage)](https://sonarcloud.io/summary/new_code?id=fabiangftech_resilience4cata) [![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

**Resilience annotations for plain Java 21+, with no Spring, CDI, AspectJ or agents.** Annotate a method with `@Retry`, `@CircuitBreaker`, `@Timeout`… and an annotation processor generates plain Java at compile time. No proxies, no reflection: a few nanoseconds per call and no allocation on the sync success path.

- **Policies:** retry, hedge, circuit breaker, rate limiter, adaptive limit, timeout, bulkhead, fallback.
- **Call styles:** sync, `CompletionStage`, Reactor `Mono`/`Flux`; on interfaces or on annotated classes.
- **Configuration:** named configs, overrides from properties, environment or system properties.
- **Also:** events, snapshots, Micrometer/OpenTelemetry metrics, a programmatic `Pipeline`, a `testkit` with a manual clock, Spring Boot, Micronaut and Quarkus integrations.

Status: **1.0.0-RC3** on Maven Central, API frozen for 1.0 ([release candidate](docs/en/blog/release-candidate.md)). Docs: **[resilience4cata.dev](https://resilience4cata.dev)** (English and [Spanish](https://resilience4cata.dev/es/)).

## Install

The library plus the annotation processor (required; only on the processor path).

Gradle:

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC3'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC3'
}
```

Maven:

```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>1.0.0-RC3</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0-RC3</version>
  </path>
</annotationProcessorPaths>
```

Kotlin DSL, the BOM, JPMS and Lombok/MapStruct: [installation](docs/en/start/installation.md). Frameworks: [Spring Boot](docs/en/guides/spring.md), [Micronaut](docs/en/guides/micronaut.md), [Reactor](docs/en/guides/reactor.md).

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

The processor generates `PriceClient$Resilient`; a wrong attribute or fallback signature fails the compilation. Annotated classes work too (`Resilient.create(...)`), and then self-calls are protected as well. Full walkthrough: [quickstart](docs/en/start/quickstart.md).

## Performance

JMH against Resilience4j 2.4.0 with equivalent configuration, AWS `c7g.xlarge` (Graviton3), Corretto 21, average time and allocation per call:

| Scenario | Threads | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| Retry, first attempt succeeds | 1 | **1.4 ns**, 0 B | 22.9 ns, 0 B |
| Circuit breaker, all calls succeed | 1 | **1.9 ns**, 0 B | 89.9 ns, 0 B |
| Circuit breaker, all calls succeed | 4 | **1.9 ns**, 0 B | 850 ns, 2 B |
| Circuit breaker, 1 in 10 fails | 4 | **278 ns**, 0 B | 940 ns, 2 B |
| Sync timeout | 1 | **23.6 ns**, 0 B | 8908 ns, 183 B |
| Bulkhead with free slots | 1 | **17.3 ns**, 0 B | 20.1 ns, 0 B |

Not measured yet: x86 and tail latencies. Method, fairness adjustments and all results: [benchmarks](docs/en/benchmarks.md#release-candidate). CI fails a pull request if a sync success or rejection path starts allocating.

## Documentation

- [Quickstart](docs/en/start/quickstart.md) and [how it works](docs/en/start/how-it-works.md)
- [Policies](docs/en/policies/README.md): every annotation, attribute and default
- [Configuration](docs/en/guides/configuration.md): properties, environment variables, system properties
- [Errors](docs/en/errors.md): every `R4C-…` code with the fix

## Build

`./gradlew build` (JDK 17+, compiles with a Java 21 toolchain). `./gradlew publishToMavenLocal` installs the artifacts locally. `./gradlew :site:serve` previews the docs site.

## License

[Apache License 2.0](LICENSE).
