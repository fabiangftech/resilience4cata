# resilience4cata

> **Status: phase 2 (first code).** The six MVP policies work end to end, sync and async, plus external configuration, classifiers, the programmatic API, events, snapshots and the testkit; see [implementation status](#implementation-status). This page also summarises the design decisions from phase 1.
>
> Versión en español: [es/README.md](../es/README.md)

resilience4cata is a resilience library for **plain Java 21+**: circuit breaker, retry, timeout, bulkhead, rate limiter and fallback, declared with **annotations** that work **without Spring, CDI or AspectJ**, and built to have **lower overhead than Resilience4j**.

## Why another resilience library

1. **Annotations without a framework.** Resilience4j's annotations only do something through Spring AOP, AspectJ or Micronaut. SmallRye Fault Tolerance's standalone mode is programmatic only. No mainstream library offers declarative resilience in plain Java ([01 §1.3, §4.2](research/01-landscape.md)).
2. **Measurable performance.** Resilience4j allocates on every call (272 B/op for a bare circuit breaker in its own JMH suite), takes a lock in its default sliding window, and composes policies through shared lambdas that the JIT cannot inline ([01 §1.7](research/01-landscape.md), [03 §0](research/03-performance-internals.md)). Polly v8 (.NET) shows that near-zero allocation is reachable.
3. **Multiple configurations as a first-class feature**: named configs with inheritance, profiles, user-defined composed annotations such as `@ExternalCall`, and external overrides without recompiling ([04 §6](research/04-api-and-configuration.md)).

## Decisions taken in phase 1

| Topic | Decision | Details |
|---|---|---|
| Java baseline | Java 21+, tested on 21 and 25 | [architecture](architecture.md#build-and-tooling) |
| Build | Gradle with a minimal Groovy DSL; convention plugins written in Java; version catalog. No Kotlin in the project | [architecture](architecture.md#build-and-tooling) |
| Annotation mechanism | **Compile-time annotation processor (APT)** that generates plain Java source. No runtime proxies, bytecode generation or agents in `core` | [architecture](architecture.md#annotation-mechanism) |
| Frameworks | Plain Java only for now | — |
| Modules | Single-word names: `annotations`, `core`, `processor`, `config`, `micrometer`, `testkit`, `bom`, plus internal `benchmark`, `stress`, `examples` | [architecture](architecture.md#modules) |
| `core` dependencies | None (JDK only) | [architecture](architecture.md#modules) |
| MVP policies | `@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, `@RateLimiter`, `@Fallback` | [features](features.md) |
| Performance contract | 0 B/op on success paths, no locks on hot paths, enforced by JMH in CI | [features](features.md#performance-contract) |
| Docs | Plain Markdown in `docs/en` and `docs/es`, served by Docsify. English is the source of truth | [architecture](architecture.md#documentation) |
| `annotations` module | Separate from `core` | [architecture](architecture.md#modules) |
| Durations | Strings (`"250ms"`, `"2s"`, `"PT2S"`), validated by the processor | [features](features.md#annotations) |
| Gradle conventions build | Included build named `conventions` (keeps the single-word rule) | [architecture](architecture.md#build-and-tooling) |
| Processor | **Mandatory.** No runtime proxy fallback. If the generated class is missing, `Resilient.of` fails at startup with a message saying how to add the processor. Code that cannot be annotated uses the programmatic API in `core` | [architecture](architecture.md#annotation-mechanism) |
| Annotation retention | `CLASS`: annotations are not loaded at runtime, so `annotations` can be a `compileOnly` dependency. Moving to `RUNTIME` later is a compatible change if a proxy fallback is ever added | [architecture](architecture.md#annotation-mechanism) |
| Interface vs implementation annotations | With `Resilient.of(interface, impl, registry)` only the interface's annotations count: the implementation is unknown at compile time, so annotating it is a compile error. When the subclass style (`Resilient.create(Impl.class, registry)`) is added, the implementation's annotations **win**, attribute by attribute: interface < class < class method | [architecture](architecture.md#interface-vs-implementation-annotations) |
| Self-invocation | **Intercepted** in the subclass style: `this.other()` goes through the generated override, so its policies apply. Not possible in the interface-wrapper style, where calls inside the user's implementation never reach the wrapper; this is documented, and users who need it annotate a non-final class instead | [architecture](architecture.md#self-invocation) |
| Nullness annotations | JSpecify as a real (`api`) dependency of `core`: the only exception to "zero dependencies". It is an annotations-only jar of a few KB, and it gives IDEs, Kotlin and NullAway nullness information | [architecture](architecture.md#modules) |
| Hot reload | **None.** Configuration is immutable once the registry is built; changing it means restarting the application. This keeps every config value in `final` fields on the hot path | [features](features.md#multiple-configurations) |
| Coordinates | Domain `resilience4cata.dev`: Maven groupId `dev.resilience4cata`, root package `dev.resilience4cata` | [architecture](architecture.md#modules) |

## Implementation status

| Piece | Status |
|---|---|
| `@Retry` | **Done end to end**: annotation, processor validation and generation, `RetryPolicy` runtime, registry with defaults and named configs, composed annotations, type-level and method-level merging |
| `@CircuitBreaker` | **Done end to end**: count- and time-based windows, lock-free state machine, half-open probes with `maxWaitInHalfOpen`, slow calls, `recordOn`/`ignoreOn`, shared instances by `name`, nested inside `@Retry`, and `forceOpen`/`disable`/`reset` through `registry.circuitBreaker(id)` |
| `@Timeout` | **Done end to end** for synchronous methods: the call stays on the caller's thread and one shared watchdog thread interrupts it when it runs late (resolution: `timeout / 16`, 1–50 ms, never early); the interrupt is always cleared afterwards. Applied per attempt, inside the circuit breaker |
| `@Bulkhead` | **Done end to end**: semaphore-style limit with `maxConcurrent`; with `maxWait = "0"` a lock-free counter whose rejections only read, otherwise a non-fair `Semaphore` that parks virtual threads without pinning; shared instances by `name`; innermost layer, so a slot is held only while the method runs and the wait for one counts toward `@Timeout`. Async methods with `maxWait > 0` wait without blocking: they are queued FIFO, handed a released slot directly (the call starts on a new virtual thread), and rejected after `maxWait`; sync and async callers share the slots, with approximate fairness between them |
| `@RateLimiter` | **Done end to end**: `permits` per `period` with GCRA (generic cell rate algorithm) on one `long`: a burst of up to `permits` after a quiet period, then one permit every `period / permits`; no background thread, and a rejection only reads. With `maxWait`, a call reserves the next permit and sleeps until it is due; an interrupted wait gives the permit back. Shared instances by `name`, inspection through `registry.rateLimiter(id)`. Layer inside the circuit breaker, so calls rejected by the breaker use no permit |
| `@Fallback` | **Done end to end** for synchronous methods, as the outermost layer, so it also sees rejections (`CallNotPermittedException`, `BulkheadFullException`, `RateLimitExceededException`, `CallTimeoutException`). Two forms, both checked at compile time: a typed handler class (`FallbackHandler<T>`, `T` assignable to the return type, public no-argument constructor, one instance per method) that receives a `FallbackContext` with the failure, the number of attempts and the arguments; or sibling methods with the original parameters plus an optional exception parameter, the most specific exception type winning. With the interface wrapper, sibling methods must be `default`, abstract or `static` interface methods, because the generated class cannot call private ones. `applyOn`/`skipOn` filter the failures (`skipOn` wins). With `@Retry`, the fallback runs where the retry loop gives up, so it knows the attempts without allocating anything on the success path. A checked exception thrown by a handler is rethrown if the method declares it, otherwise wrapped in `UndeclaredThrowableException`. Not supported yet: generic methods |
| Async methods | **Done** for every policy: a method that returns `CompletionStage<T>` or `CompletableFuture<T>` gets the async pipeline, decided from the return type (no extra annotation). An exception thrown before the delegate returns a stage, or a `null` stage, counts as a failed call. Retries are scheduled, never slept: the first attempt runs on the caller's thread, later ones on new virtual threads. The timeout fails the future with `CallTimeoutException` and cancels the late stage (`Future.cancel`); it runs on a lock-free timer (striped inboxes, one lane per duration, cancel by a state CAS) that never fires early and fires at most about two ticks late (tick = shortest async timeout / 16, 1–50 ms), and a stage that is already complete skips the timer. The bulkhead holds its slot until the stage completes. The rate limiter's `maxWait` reserves the permit and starts the call when it is due, without blocking. Fallbacks may return `T` or a stage of `T`. One daemon timer thread for the library; everything it fires runs on a virtual thread. Limits: no wildcard (`CompletionStage<? extends T>`) or custom stage types; cancelling the returned future does not cancel the delegate |
| `classifier` on `@Retry` / `@CircuitBreaker` | **Done**, sync and async: one `Classifier<T>` decides for results and exceptions (`SUCCESS`, `FAILURE`, `IGNORE`), checked at compile time, one instance per method. It replaces `retryOn`/`recordOn` (combining them is a compile error); `abortOn`/`ignoreOn` still win. A `FAILURE` result is retried and, when attempts run out, returned as is (no `@Fallback`). Methods without a classifier generate the same code as before ([features](features.md#classifiers-results-as-failures)) |
| Programmatic API (`Pipeline`) | **Done**: an immutable pipeline built from a `ResilienceRegistry` for code that cannot be annotated; same policies, order and semantics as generated code, sync and async, with fallback and classifiers; allocation-free on the sync success path ([features](features.md#programmatic-api)) |
| Events and snapshots | **Done**: typed sealed events (breaker transitions and rejections, retries, timeouts, bulkhead and rate limiter rejections, fallbacks), registered on the registry builder, no cost when nobody listens; `registry.snapshot()` and `snapshot()` on the handles return read-only records ([features](features.md#events-and-state-inspection)) |
| Test support (`testkit`) | **Done**: `ManualClock` drives retries, breakers, rate limiters, sync and async timeouts and async delays through `Builder.ticker(...)`; seeded jitter with `Builder.random(...)`. Limit: a sync bulkhead's `maxWait` waits in real time ([features](features.md#testing)) |
| Annotated classes | Rejected by the processor with an explicit "not supported yet" error |
| External configuration (`config` module) | **Done (MVP)**: properties files, classpath resources, environment variables (`R4C_…`) and system properties (`r4c.…`); defaults, named configs, `extends`, profiles; strict validation that reports every problem at once with its source and a "did you mean" ([features](features.md#loading-external-configuration-config-module)). Not yet: `instances.*` per-instance overrides, YAML; a named config cannot be defined both in code and externally |
| Gradle incremental-processing registration | **Done**: the processor is registered as isolating (`META-INF/gradle/incremental.annotation.processors`); each generated class has the wrapped interface as its only originating element, so editing an unrelated file recompiles only that file, and editing a bundle, handler or superinterface reprocesses only the interfaces that use it |
| JMH benchmarks | Retry (one and four services), circuit breaker (count and time windows), timeout, bulkhead and rate limiter (1 and 4 threads), async methods, classifiers and the programmatic `Pipeline`, vs Resilience4j; full base-vs-new runs in parallel on AWS with `infra/bench.sh` ([benchmarks](benchmarks.md)) |
| jcstress (`stress` module) | **Done**: 21 jcstress tests of the lock-free policies (bulkhead counter, rate limiter burst and give-back, circuit breaker transitions and quiet state, timeout/watchdog stamp protocol, breaker transition events published exactly once), mutation-checked (17 of 18 mutants caught; a release-only CAS in the timeout's end-of-call is guarded by a test but not reproduced on this Mac). Run with `./gradlew :stress:jcstress -Pjcstress="-m quick"`; `./gradlew build` only compiles them |

## Open questions

None for phase 1. New questions that come up while writing the first code are added here.

## Research documents

| # | Document | Topic |
|---|---|---|
| 01 | [Landscape](research/01-landscape.md) | Resilience4j in depth, competitors, opportunities |
| 02 | [Annotation mechanisms](research/02-annotation-mechanisms.md) | APT vs proxies vs ByteBuddy vs agents, with our own JMH run |
| 03 | [Performance internals](research/03-performance-internals.md) | Algorithms and lock-free design per policy, benchmark methodology |
| 04 | [API and configuration](research/04-api-and-configuration.md) | Feature tiers, annotations, configuration model |
| 05 | [Project architecture](research/05-project-architecture.md) | Modules, Gradle, JPMS, tooling, docs, versioning |

**About the evidence.** Each research document tags its claims: verified in source code or documentation, measured, or inferred. Document 04 was written from prior knowledge, without checking sources online. Its references, especially SmallRye annotation names and MicroProfile execution order, must be checked before they are relied on. No performance claim about resilience4cata is valid until phase 2 benchmarks exist.
