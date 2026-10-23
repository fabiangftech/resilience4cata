# resilience4cata

> **Status: phase 1 (research and design).** There is no code yet. This page summarises the decisions that came out of the research and lists the questions that are still open.
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
| Build | Gradle, Kotlin DSL, convention plugins, version catalog | [architecture](architecture.md#build-and-tooling) |
| Annotation mechanism | **Compile-time annotation processor (APT)** that generates plain Java source. No runtime proxies, bytecode generation or agents in `core` | [architecture](architecture.md#annotation-mechanism) |
| Frameworks | Plain Java only for now | — |
| Modules | Single-word names: `annotations`, `core`, `processor`, `config`, `micrometer`, `testkit`, `bom`, plus internal `benchmark`, `stress`, `examples` | [architecture](architecture.md#modules) |
| `core` dependencies | None (JDK only) | [architecture](architecture.md#modules) |
| MVP policies | `@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, `@RateLimiter`, `@Fallback` | [features](features.md) |
| Performance contract | 0 B/op on success paths, no locks on hot paths, enforced by JMH in CI | [features](features.md#performance-contract) |
| Docs | Plain Markdown in `docs/en` and `docs/es`, served by Docsify. English is the source of truth | [architecture](architecture.md#documentation) |

## Open questions

These need a decision before or during phase 2 (the first code):

1. **Separate `annotations` module, or merged into `core`?** The recommendation is separate, which allows `compileOnly` if retention is `CLASS`.
2. **Annotation retention: `CLASS` or `RUNTIME`?** `RUNTIME` is only needed if a runtime fallback (proxies) or runtime introspection exists.
3. **Is the annotation processor mandatory?** If an optional `dynamic` module with a `Proxy` fallback exists, every compile-time check must also run at startup.
4. **Durations as strings (`"2s"`) or `long` + `TimeUnit`?** The recommendation is strings, validated by the processor.
5. **Name of the Gradle conventions build:** `build-logic` (the usual name) or `conventions` (keeps the single-word rule).
6. **Maven Central namespace and root package** (`io.github.<user>` or your own domain).
7. **Self-invocation** in generated subclasses: intercept it (like Micronaut) or not (like Spring)?
8. **JSpecify:** a real dependency of `core` (recommended), or `compileOnly`?
9. **Hot reload:** out of the MVP; in v1, an explicit `reconfigure()` API only?

## Research documents

| # | Document | Topic |
|---|---|---|
| 01 | [Landscape](research/01-landscape.md) | Resilience4j in depth, competitors, opportunities |
| 02 | [Annotation mechanisms](research/02-annotation-mechanisms.md) | APT vs proxies vs ByteBuddy vs agents, with our own JMH run |
| 03 | [Performance internals](research/03-performance-internals.md) | Algorithms and lock-free design per policy, benchmark methodology |
| 04 | [API and configuration](research/04-api-and-configuration.md) | Feature tiers, annotations, configuration model |
| 05 | [Project architecture](research/05-project-architecture.md) | Modules, Gradle, JPMS, tooling, docs, versioning |

**About the evidence.** Each research document tags its claims: verified in source code or documentation, measured, or inferred. Document 04 was written from prior knowledge, without checking sources online. Its references, especially SmallRye annotation names and MicroProfile execution order, must be checked before they are relied on. No performance claim about resilience4cata is valid until phase 2 benchmarks exist.
