# Features

> Synthesis of [01 Landscape](research/01-landscape.md), [03 Performance internals](research/03-performance-internals.md) and [04 API and configuration](research/04-api-and-configuration.md). This is a proposal: names and attributes can still change before `1.0`.

## Tiers

| Feature | Tier | Notes |
|---|---|---|
| Circuit breaker: count- and time-based windows, slow-call rate, half-open probes, `forceOpen` / `disable` | **MVP** | Same model as Resilience4j, to ease migration |
| Retry: fixed / exponential backoff, full or equal jitter (on by default), `maxDuration`, result-based retry | **MVP** | |
| Timeout, sync and async | **MVP** | Sync: the call stays on the caller's thread and a timer interrupts it |
| Bulkhead (semaphore, fail-fast or with `maxWait`) | **MVP** | No thread-pool bulkhead: virtual threads make it unnecessary |
| Rate limiter | **MVP** | GCRA internally, configured as permits per period |
| Fallback (typed handler class, or a method checked at compile time) | **MVP** | |
| Async from the return type (`CompletionStage` / `CompletableFuture`) | **MVP** | No `@Asynchronous` annotation |
| Composed annotations (`@ResiliencePolicy`) | **MVP** | The main way to reuse configurations in code |
| Named configs, `extends`, profiles; properties, environment and system properties | **MVP** | |
| Programmatic API (no annotations) | **MVP** | For code that cannot be annotated |
| Typed events, no allocation when nobody listens | **MVP** | |
| State inspection (`registry.snapshot()`) | **MVP** | Read-only records, useful for health checks |
| Test support: manual clock and scheduler, seeded jitter, forced states | **MVP** | In `testkit` |
| Hedging (`@Hedge`) | v1 | Differentiator; Resilience4j has none |
| Adaptive concurrency limit (`@AdaptiveLimit`: AIMD, Vegas, Gradient2) | v1 | Requested in Resilience4j since 2018 |
| Retry budget | v1 | Prevents retry storms |
| Micrometer and OpenTelemetry bridges | v1 | Separate modules |
| YAML config | v1 | Separate module, so `core` stays dependency-free |
| Configurable execution order (`@Order`) | v1 | |
| JUnit 5 extension | v1 | |
| `@Cached`, reactive types, Kotlin coroutines, fault injection, deadline propagation, framework integrations | later | |

## Annotations

```java
@CircuitBreaker  @Retry  @Timeout  @Bulkhead  @RateLimiter  @Fallback   // MVP
@Hedge  @AdaptiveLimit  @Order                                           // v1
@ResiliencePolicy                                                        // marks composed annotations
```

Design rules ([04 §4](research/04-api-and-configuration.md)):

- **`config` and `name` are different things.** `config` picks a named template. `name` picks the stateful instance (breaker window, permits); by default there is one instance per method. Two methods that share a `name` share the instance, and they must resolve to the same configuration.
- **Every attribute has an "unset" value** (`-1`, `""`, `{}`), so an annotation only overrides what it writes. Real defaults live in the configuration layer.
- **Typed where Java allows**: exception classes, enums, fallback classes.
- **Durations are strings** (`"250ms"`, `"2s"`, `"PT2S"`), checked at compile time.
- Annotations go on methods or types. A method-level annotation overrides a type-level one attribute by attribute.
- Unsupported placements (final or private methods, depending on the generated style) are **compile errors**, never silently ignored.

### Example

```java
@ResiliencePolicy
@Retention(CLASS) @Target({METHOD, TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
@Bulkhead(maxConcurrent = 50)
public @interface ExternalCall {}

public interface PaymentClient {

    @ExternalCall
    @Fallback(PaymentFallback.class)          // typed handler: Fallback<Receipt>
    Receipt charge(Order order);

    @ExternalCall
    @Timeout("500ms")                         // overrides one attribute of the bundle
    CompletableFuture<Status> status(String id);
}
```

If two composed annotations on the same element set the same attribute to different values, the build fails. There is no silent "last one wins".

## Execution order

Default, outermost first ([04 §5](research/04-api-and-configuration.md)):

```
Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method
```

- Fallback is outermost, so it sees the final outcome, including rejections.
- Each retry attempt is recorded by the breaker, and an open breaker stops the retries.
- Calls rejected by the breaker do not use up rate-limiter permits.
- The timeout applies per attempt; `@Retry(maxDuration)` bounds the whole operation.
- The bulkhead permit is held only while the method runs, not during backoff.

## Multiple configurations

Resolution order, lowest to highest precedence ([04 §6](research/04-api-and-configuration.md)):

```
1. built-in defaults
2. defaults.<policy>.*
3. configs.<name>.*               (following the extends chain)
4. profiles.<active>.*
5. composed annotation             (@ExternalCall)
6. class-level annotation
7. method-level annotation
8. instances.<id>.*                (external, per instance: files < env < system properties)
9. programmatic override
```

- External config (8) overrides annotations, as in MicroProfile Fault Tolerance: operators can retune without recompiling. `locked` prevents this for a given instance.
- Everything is validated and resolved **once**, when the instance is created, into immutable records. The hot path never looks up configuration.
- Unknown keys fail with a "did you mean …?" message.
- **No hot reload.** Configuration is immutable once the registry is built; changing it means restarting the application.

```properties
defaults.retry.maxAttempts=3
configs.external.circuitBreaker.failureRateThreshold=50
configs.external.retry.delay=200ms
configs.batch.extends=external
configs.batch.timeout.value=30s
profiles.test.configs.external.retry.maxAttempts=1
```

## Performance contract

Targets to verify with JMH from the first release ([01 §4.1](research/01-landscape.md), [03 §10](research/03-performance-internals.md)):

| Target | How it is checked |
|---|---|
| **0 B/op** on the success path of every policy and of a full stack | JMH `-prof gc`, as a CI gate |
| No locks and no `synchronized` on hot paths | Code review, ArchUnit, JMH contention scenarios |
| Monomorphic, straight-line generated code per method; no shared lambda chains | JMH with a polluted JIT profile, `-XX:+PrintInlining` |
| One clock-read pair per call, whatever the number of policies | Code review |
| Preallocated, stackless rejection exceptions; events allocated only if someone listens | JMH rejection scenarios |
| Correct lock-free structures | jcstress |
| Published, reproducible comparison against Resilience4j 2.4.0 | 11 JMH scenarios ([03 §9](research/03-performance-internals.md)) |

None of these numbers have been measured yet for resilience4cata. Most real calls are I/O-bound (milliseconds), so the pitch is "near-zero overhead that scales with threads, plus annotations without a framework", not raw speed alone.
