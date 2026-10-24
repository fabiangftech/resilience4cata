# resilience4cata

> **Status: phase 2 (first code).** `@Retry` works end to end; see [implementation status](#implementation-status). This page also summarises the design decisions from phase 1.
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
| `@Bulkhead`, `@RateLimiter`, `@Fallback` | Annotations only |
| Async (`CompletionStage`) return types, `classifier`, annotated classes | Rejected by the processor with an explicit "not supported yet" error |
| External configuration (`config` module), profiles, `extends` | Not started |
| Gradle incremental-processing registration | Not done: projects that use the processor recompile fully |
| JMH benchmarks | Retry (one and four services), circuit breaker (count and time windows) and timeout (1 and 4 threads) vs Resilience4j ([benchmarks](benchmarks.md)) |
| jcstress (`stress` module) | Not started. Races in the circuit breaker are covered by deterministic unit tests that replay the interleavings, checked by mutation |

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
