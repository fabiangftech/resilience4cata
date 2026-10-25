# FAQ

Short answers to the questions people ask first, with links to the details.

## General :id=general

### What is resilience4cata? :id=what

An annotation-driven resilience library for plain Java 21+: retry, circuit breaker, rate limiter, timeout, bulkhead and fallback. An annotation processor turns the annotations into ordinary Java source at compile time, so there are no proxies, no bytecode generation and no agents at runtime. See [How it works](start/how-it-works.md).

### Which Java versions are supported? :id=java

Java 21 or later, at compile time and at runtime. The library is compiled with `--release 21` and tested on Java 21 and 25.

### Does it work with Spring, Quarkus, Micronaut or Jakarta CDI? :id=frameworks

It does not integrate with any of them, and it does not need to: generated classes are plain Java, so you create the wrapped object yourself (`Resilient.of(…)`) and register it as a bean the way your framework does it. There is no auto-configuration, no AOP and no annotation scanning at runtime.

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

No. It is always Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → method, whatever the order of the annotations. So every attempt of a retry is recorded by the breaker and gets its own timeout, and an open breaker stops the retries. See [Policies](policies/README.md).

### Why is my method not retried when the implementation calls it? :id=self-invocation

In the interface style, the policies live in the generated wrapper; a call from the implementation to itself does not go through it. See [Troubleshooting](guides/troubleshooting.md#self-invocation).

### Does it support reactive types (`Mono`, `Flux`, `Single`)? :id=reactive

No. Async methods must return `CompletionStage<T>` or `CompletableFuture<T>`. Any other return type is treated as synchronous: the policies protect only the call that returns the object, not the work it represents. See [Async methods](guides/async.md).

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

From events (counters) and snapshots (gauges). There is no Micrometer module yet, but the [observability guide](guides/observability.md#micrometer) has a tested recipe you can copy.

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

Hedging, adaptive concurrency limits, retry budgets, caching and thread-pool bulkheads; reactive types; `? super` async types; changing the policy order; runtime reconfiguration; framework integration modules. See [Design decisions](design.md) for what is planned and what is excluded by decision.

### What is on the roadmap? :id=roadmap

Planned, not promised; names and scope can change:

| Feature | Tier | Notes |
|---|---|---|
| Hedging (`@Hedge`) | v1 | Resilience4j has none |
| Adaptive concurrency limit (`@AdaptiveLimit`: AIMD, Vegas, Gradient2) | v1 | |
| Retry budget | v1 | prevents retry storms |
| Micrometer and OpenTelemetry bridges | v1 | separate modules |
| YAML configuration | v1 | separate module, so `core` stays dependency-free |
| Configurable execution order (`@Order`) | v1 | |
| JUnit 5 extension | v1 | |
| `locked` attribute (an instance that external configuration cannot override) | v1 | |
| `@Cached`, reactive types, Kotlin coroutines, fault injection, deadline propagation, framework integrations | later | |

Until then, the [observability recipe](guides/observability.md#micrometer) covers metrics and [testkit](guides/testing.md) covers tests.

### Where do I report a bug? :id=bugs

On the [GitHub issue tracker](https://github.com/fabiangftech/resilience4cata/issues). Include the `R4C-…` code if there is one, the annotation, and the versions of Java and resilience4cata.
