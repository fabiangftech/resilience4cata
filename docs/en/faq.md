# FAQ

Short answers to the questions people ask first, with links to the details.

## General :id=general

### What is resilience4cata? :id=what

An annotation-driven resilience library for plain Java 21+: retry, circuit breaker, rate limiter, timeout, bulkhead and fallback. An annotation processor turns the annotations into ordinary Java source at compile time, so there are no proxies, no bytecode generation and no agents at runtime. See [How it works](start/how-it-works.md).

### Which Java versions are supported? :id=java

Java 21 or later, at compile time and at runtime. The library is compiled with `--release 21` and tested on Java 21 and 25.

### Does it work with Spring, Quarkus, Micronaut or Jakarta CDI? :id=frameworks

| Framework | Integration |
|---|---|
| Spring Boot 4.1+ | `resilience4cata-spring` (from 0.4.0): annotated classes are instantiated as their generated subclass, beans of annotated interfaces are wrapped, the registry is configured from `application.yml`, plus health, an actuator endpoint and metrics. No AOP. [Spring Boot guide](guides/spring.md) |
| Spring Boot 3 | not supported |
| Micronaut 5.2+ (Java 25) | `resilience4cata-micronaut` (from 0.5.0): beans of annotated interfaces marked `@ResilientBean` are wrapped, annotated classes compiled with `-Ar4c.inject=micronaut` are created as their generated subclass, the registry is configured from `application.yml`, plus health, a management endpoint and metrics. No AOP. [Micronaut guide](guides/micronaut.md) |
| Micronaut 4 | not supported |
| Quarkus 3.40+ | `resilience4cata-quarkus` (from 0.5.0), a Quarkus extension: annotated classes become their generated subclass as the bean, beans of annotated interfaces and REST clients are injected through their wrapper, the registry is configured from `application.properties`, plus a readiness check, metrics and build-time checks (including a clash with SmallRye Fault Tolerance). No interceptors. [Quarkus guide](guides/quarkus.md), with [SmallRye Fault Tolerance vs resilience4cata](guides/quarkus.md#smallrye) |
| Jakarta CDI | none yet, and none needed: generated classes are plain Java, so create the object yourself (`Resilient.of(…)` or `new C$Resilient(registry, …)`) and register it as a bean the way your framework does it. The generated constructors of annotated classes copy `@Inject` and qualifiers, so a container can also instantiate `C$Resilient` directly |

There is no runtime proxy, no AOP and no annotation scanning at runtime in any case.

### Is it a fork of Resilience4j? :id=fork

No. It covers similar policies with a different design: code generated at compile time instead of decorators, one registry for all policies, configuration resolved once. Its benchmarks compare against Resilience4j 2.4.0. See [Migrate from Resilience4j](migrate/from-resilience4j.md).

### What does it depend on? :id=dependencies

`core` and `annotations` depend only on the JDK, plus [JSpecify](https://jspecify.dev/) annotations (`org.jspecify:jspecify`) for `core`. The processor is only on the processor path, and the annotations are `compileOnly`: nothing else ends up on your runtime class path.

## Using it :id=using

### Do I have to use the annotation processor? :id=processor

Yes, with annotations: the processor is mandatory and there is no reflection fallback. If it did not run, `Resilient.of` fails with [R4C-RT-003](errors.md#r4c-rt-003), whose message shows the dependency to add. Without annotations, the [`Pipeline`](guides/pipeline.md) API needs no processor.

### Interface or annotated class? :id=style

Annotate an **interface** when you own it and the implementation may be anything (an HTTP client, a lambda, a test stub). Annotate a **class** when there is no interface, or when calls the class makes to itself must also go through the policies. See [Annotated classes](guides/annotated-classes.md).

### Can I change the order of the policies? :id=order

Yes, with `@PolicyOrder` on a method, a type or a bundle. By default it is Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method, whatever the order of the annotations, so every attempt of a retry is recorded by the breaker and gets its own timeout, and an open breaker stops the retries. `@PolicyOrder({TIMEOUT, RETRY})`, for example, puts one timeout around the whole retry loop. The fallback is always outermost, and the order is fixed at compile time. See [Changing the order](policies/README.md#changing-the-order).

### Why is my method not retried when the implementation calls it? :id=self-invocation

In the interface style, the policies live in the generated wrapper; a call from the implementation to itself does not go through it. See [Troubleshooting](guides/troubleshooting.md#self-invocation).

### Does it support reactive types (`Mono`, `Flux`, `Single`)? :id=reactive

Reactor's `Mono`, yes, with `resilience4cata-reactor`: every policy applies once per subscription, with the subscriber's context, and is cancelled with the subscription. See [Project Reactor](guides/reactor.md). Other reactive types (`Flux`, Mutiny's `Uni` and `Multi`, RxJava's types) are not supported yet: with resilience annotations they are a compile error, [R4C-ASY-002](errors.md#r4c-asy-002), because the policies would protect only building the pipeline, not the work. Convert to a `CompletionStage` or a `Mono` in the implementation. See [Async methods](guides/async.md).

### Does it work with virtual threads? :id=virtual-threads

Yes. Call paths take no locks and use no `synchronized`, so they do not pin a virtual thread to its carrier. The library's own delayed callbacks (async retries, async timeouts) run on new virtual threads.

### Can I change a configuration at runtime? :id=reload

No, by design. Annotations, named configs and external configuration are resolved once, when each instance is created. To retune in production, change the external configuration (for example `instances.<id>.*`) and restart. To force a breaker's state, use its handle: `registry.circuitBreaker(id).forceOpen()`, `disable()`, `reset()`.

### How do I configure it from a file or environment variables? :id=external-config

Add `resilience4cata-config` and build the registry from `ResilienceConfig.load()`. Keys look like `defaults.retry.maxAttempts=5` or `R4C_DEFAULTS_RETRY_MAXATTEMPTS=5`. See [Configuration](guides/configuration.md) and the [key reference](reference/configuration.md).

### What is an instance id? :id=instance-id

Each annotated method gets its own policy instances, with the id `<type>#<method>(<parameter types>)`, for example `com.acme.CatalogClient#price(java.lang.String,int)`. A `name` attribute replaces it, and methods with the same `name` share one instance. Ids are used by handles, events, snapshots and `instances.<id>.*` keys.

## Operations :id=operations

### How do I get metrics? :id=metrics

With the [Micrometer module](guides/micrometer.md) (`resilience4cata-micrometer`): three lines of setup give you counters from events and gauges for every circuit breaker, bulkhead and rate limiter. For another metrics library, build the same thing on [events and handles](guides/observability.md#diy).

### Does it count successful calls or measure latency? :id=success-metrics

No. Successful calls publish no event and update no counter: a counter on every call would be a write to shared memory on the hot path. Measure call counts and latency in your own code or with your framework's metrics.

### How do I test code that waits seconds between retries? :id=testing

With `resilience4cata-testkit`: a `ManualClock` makes sleeps, waits and timeouts follow a clock the test advances, so second-long delays take no real time. See [Testing](guides/testing.md).

### Why does my timeout not stop the call? :id=timeout

A sync timeout interrupts the caller's thread; code that ignores interrupts (classic blocking socket I/O, busy loops) runs until it returns. Set the client's own I/O timeouts as well. See [Troubleshooting](guides/troubleshooting.md#timeout-not-interrupting).

### How fast is it? :id=performance

On AWS Graviton3, a retry whose first attempt succeeds costs 1.2 ns and a circuit breaker 1.9 ns per call, with 0 bytes allocated, against 22.9 ns and 89.2 ns for Resilience4j 2.4.0 ([measured](benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)). Some cases are ties, and contended paths cost hundreds of nanoseconds in both libraries. See [Performance](performance.md).

## Project :id=project

### Is it production-ready? :id=production

0.1.0 is the first release. Every policy has unit and end-to-end tests, the lock-free ones also concurrency tests (jcstress), and allocation is gated in CI, but the API may still change before 1.0.0. See the [Changelog](changelog.md).

### What is not supported yet? :id=not-supported

Caching and thread-pool bulkheads; the Vegas algorithm and waiting (`maxWait`) for adaptive limits; synchronous hedging (`@Hedge` works on async methods); `Flux`, Mutiny and RxJava types (Reactor's `Mono` is supported); `? super` async types; runtime reconfiguration; integration modules for frameworks other than Spring Boot, Micronaut and Quarkus. See [Design decisions](design.md) for what is planned and what is excluded by decision.

### What is on the roadmap? :id=roadmap

Planned, not promised; names and scope can change:

| Feature | Tier | Notes |
|---|---|---|
| Hedging (`@Hedge`) | done in 0.4.0 | async methods and `Pipeline.executeAsync` ([Hedge](policies/hedge.md)); synchronous hedging later. Resilience4j has `resilience4j-hedge` (async, programmatic only) |
| Adaptive concurrency limit (`@AdaptiveLimit`) | done in 0.6.0 | Gradient2 and AIMD, sync and async ([Adaptive limit](policies/adaptive-limit.md)); Vegas and waiting later. Resilience4j has none |
| OpenTelemetry tracing | later | metrics are in `resilience4cata-opentelemetry` since 0.3.0 ([guide](guides/opentelemetry.md)) |
| YAML configuration | v1 | separate module, so `core` stays dependency-free |
| Configurable execution order (`@PolicyOrder`) | done in 0.10.0 | annotations only; a `Pipeline` keeps the default order ([Changing the order](policies/README.md#changing-the-order)) |
| `locked` attribute (annotation values that external configuration cannot override) | done in 0.7.0 | `locked = true` on the annotation, `Pipeline.Builder.locked()` ([Locking an instance](guides/configuration.md#locking-an-instance)) |
| `@Cached`, `Flux`, Mutiny and RxJava, Kotlin coroutines, fault injection, deadline propagation | later | |

Until then, [testkit](guides/testing.md) covers tests.

### Where do I report a bug? :id=bugs

On the [GitHub issue tracker](https://github.com/fabiangftech/resilience4cata/issues). Include the `R4C-…` code if there is one, the annotation, and the versions of Java and resilience4cata.
