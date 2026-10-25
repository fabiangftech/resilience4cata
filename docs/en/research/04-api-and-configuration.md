# 04 — Feature set, annotation API and configuration model

> **Historical.** This research predates the implementation and is kept as written; for what 0.1.0 actually does, see [features](../policies/README.md), [architecture](../architecture.md) and [benchmarks](../benchmarks.md).

> Phase 1 research. Status: **proposal for discussion**. No production code; every Java snippet below is an API sketch.
> Scope: what users see and type. How annotations are woven (APT vs. proxies vs. bytecode), concurrency internals,
> and module layout are covered by sibling documents and are referenced only where they constrain the API.

## Contents

1. [Summary](#1-summary)
2. [Prior art](#2-prior-art)
3. [Feature catalog and tiers](#3-feature-catalog-and-tiers)
4. [Annotation design](#4-annotation-design)
5. [Execution order](#5-execution-order)
6. [Multiple configurations](#6-multiple-configurations)
7. [Fallbacks and exception classification](#7-fallbacks-and-exception-classification)
8. [Async and virtual threads](#8-async-and-virtual-threads)
9. [Events, metrics and state inspection](#9-events-metrics-and-state-inspection)
10. [Developer experience and testing](#10-developer-experience-and-testing)
11. [Open questions](#11-open-questions)
12. [Sources](#12-sources)

---

## 1. Summary

- **Six MVP policies**: `@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, `@RateLimiter`, `@Fallback`. Async support
  comes from the return type (`CompletionStage`), not from a separate annotation. **v1** adds `@Hedge`, `@AdaptiveLimit`,
  retry budgets, Micrometer/OpenTelemetry bindings, YAML and live reconfiguration. **Later**: `@Cached`, reactive types,
  fault injection, framework integrations.
- **Composed annotations are a first-class feature**: a user-defined `@ExternalCall` bundles policies through the
  `@ResiliencePolicy` meta-annotation. This is the main way to have "multiple configurations" in code.
- **Configuration and state are separate.** A *config* is a named, immutable template (`config = "external"`). An
  *instance* is the stateful object (breaker window, limiter permits), identified by `name` and by default one per
  method. Resilience4j uses one string for both; that is a common source of confusion.
- **Layered resolution**, lowest to highest precedence: built-in defaults → `defaults` → named config (which can
  `extends` another) → profile overlay → class annotation → method annotation → external per-instance overrides
  (properties/YAML → environment → system properties) → programmatic overrides.
  Everything is resolved **once**, when the instance is created, into immutable records. The hot path never consults
  configuration.
- **Type-safe fallbacks**: the main form is `@Fallback(UserFallback.class)` with a generic `FallbackHandler<T>` interface. A
  method-name form exists for convenience, and the annotation processor checks it at compile time.
- **One classifier for exceptions and results**: `recordOn`/`ignoreOn` class lists, plus an optional
  `Classifier<T>` that sees both the result and the throwable.
- **Testability is part of the design**: injectable `Clock`/`Scheduler`, a seeded jitter `Random`, `forceOpen()`, and a
  `test` module with a JUnit 5 extension and event assertions.
- **Hot reload**: not in MVP. v1 has an explicit `reconfigure(...)` API that swaps an immutable snapshot (one volatile
  read per call), limited to parameters that are safe to change live.

## 2. Prior art

| Library | Declaration style | Config source | Fallback | Order of combined policies |
|---|---|---|---|---|
| **Resilience4j** (annotations via Spring AOP / CDI) | `@CircuitBreaker(name, fallbackMethod)`, `@Retry`, `@RateLimiter`, `@Bulkhead(type)`, `@TimeLimiter` | `resilience4j.<policy>.configs.<cfg>.*` + `instances.<name>.baseConfig` | **String** method name, resolved by reflection, matched on exception parameter type | Fixed `Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(fn)))))`, changeable through `*AspectOrder` properties |
| **MicroProfile Fault Tolerance 4.x** | `@Retry`, `@Timeout`, `@CircuitBreaker`, `@Bulkhead`, `@Fallback`, `@Asynchronous` with typed attributes (`maxRetries`, `delay`+`delayUnit`, `failOn`/`skipOn`, `retryOn`/`abortOn`) | MP Config: `<class>/<method>/<Annotation>/<param>`, `<class>/<Annotation>/<param>`, `<Annotation>/<param>`; `<Annotation>/enabled` | `@Fallback(value = Handler.class)` **or** `fallbackMethod` string (validated at deployment) | Defined by the spec: Fallback → Retry → CircuitBreaker → Timeout → Bulkhead (SmallRye also adds RateLimit) |
| **SmallRye FT** (MP FT implementation) | Adds `@RateLimit`, `@ExponentialBackoff`, `@FibonacciBackoff`, `@CustomBackoff`, `@RetryWhen`, `@BeforeRetry`, and `@ApplyGuard`/`@ApplyFaultTolerance` (reuse of a programmatically built, named guard) | MP Config, plus a programmatic `Guard` API | Same as MP | Same as MP |
| **Spring Retry** | `@Retryable(retryFor, noRetryFor, maxAttempts, backoff = @Backoff(...), recover = "...")`, `@CircuitBreaker` | SpEL expressions in attributes (`maxAttemptsExpression`) | `@Recover` matched by **signature** (return type + exception + args) | One policy per method |
| **Spring Framework 7** (core) | `@Retryable`, `@ConcurrencyLimit`, `@EnableResilientMethods` | Attributes plus `${…}` placeholders | none | n/a |
| **Failsafe** | No annotations. `Failsafe.with(fallback, retryPolicy, circuitBreaker, timeout).get(...)` | Builders only | `Fallback.of(...)` policy (typed) | **Composition order is the argument order** (outer → inner) |
| **Polly v8 (.NET)** | `ResiliencePipelineBuilder().AddRetry(...).AddCircuitBreaker(...).AddTimeout(...)` | `ResiliencePipelineRegistry<TKey>`, options binding, **dynamic reloads** through `IOptionsMonitor` | Fallback strategy (typed `Outcome<T>`) | Order of `Add*` calls (first = outermost); includes hedging and chaos strategies |

What we take from each:

- **MP FT**: typed attributes (`Class<? extends Throwable>[]`, not regexes), a deterministic config key per
  class/method/annotation, an `enabled` switch, and config overriding annotations. Its downsides are the rigid order
  and no named shared configs (until SmallRye's `@ApplyGuard`).
- **Resilience4j**: named configs with `baseConfig` inheritance and instance sharing across methods. Its downsides are
  string fallbacks with no compile-time check, the same string serving as config key and state identity, and an order
  that can only be changed globally.
- **Failsafe / Polly**: order expressed explicitly in the builder, typed fallbacks, and a single "should handle"
  predicate that covers both exceptions and results (`handleResultIf`, `ShouldHandle`). Polly also contributes hedging,
  pipeline registries, and reload by swapping whole pipelines.
- **Spring Retry**: fallback lookup by signature (`@Recover`) needs no strings, but ambiguity errors only appear at
  runtime. We keep the idea and move the check to compile time.

## 3. Feature catalog and tiers

| Feature | Tier | Notes |
|---|---|---|
| Circuit breaker (count- and time-based sliding windows, slow-call rate, half-open probe count) | **MVP** | Same model as R4j so users can migrate. Also `forceOpen`/`disable` for operations and tests. |
| Retry (fixed, exponential, exponential + full/equal jitter, max duration, result-based retry) | **MVP** | Jitter on by default ([AWS backoff & jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)). |
| Timeout (sync and async) | **MVP** | Sync timeouts interrupt the caller; behaviour on virtual threads is documented (§8). |
| Bulkhead (semaphore, max concurrent + max wait) | **MVP** | A thread-pool bulkhead has little value with virtual threads; at most a v1 option. |
| Rate limiter (token bucket/fixed refresh, permits per period, max wait) | **MVP** | Part of the comparison set against R4j. |
| Fallback (typed handler / checked method) | **MVP** | §7. |
| Async: `CompletionStage`/`CompletableFuture` return types | **MVP** | Detected from the method signature. |
| Composed annotations (`@ResiliencePolicy`) | **MVP** | The main answer to "multiple configurations". |
| Named configs, `extends`, profiles, properties + env + sysprops | **MVP** | §6. |
| Programmatic builder API (no annotations) | **MVP** | Also the foundation that annotations compile or delegate to. |
| Events (typed, sealed hierarchy), listener registration | **MVP** | No allocation when no listener is registered. |
| State inspection (`registry.snapshot()`) | **MVP** | Read-only records; good for health endpoints. |
| Testing: `Clock`, `Scheduler`, seeded jitter, forced states | **MVP** | §10. |
| Hedging (`@Hedge`: delay, max hedged attempts) | **v1** | [The Tail at Scale](https://research.google/pubs/the-tail-at-scale/); Polly hedging. Mutually exclusive with `@Retry` at the same level in v1. |
| Adaptive concurrency limit (`@AdaptiveLimit`: AIMD, Vegas, Gradient2) | **v1** | Netflix [concurrency-limits](https://github.com/Netflix/concurrency-limits). |
| Retry budget (bounded retry ratio per instance) | **v1** | Prevents retry storms ([Finagle retry budgets](https://twitter.github.io/finagle/guide/Clients.html#retries)). |
| Metrics bindings: Micrometer, OpenTelemetry | **v1** | Separate modules; core exposes only an SPI. |
| YAML config source | **v1** | Separate module, so core needs no YAML dependency. |
| Live reconfiguration API | **v1** | §6.7. |
| JUnit 5 extension + AssertJ-style event assertions | **v1** (Clock/forcing in MVP) | |
| Configurable execution order | **v1** | Default order in MVP. §5. |
| `@Cached` (result cache with TTL) | **later** | Usually better served by Caffeine; only worth adding if it can share the key/instance infrastructure. |
| Reactive types (Reactor/Mutiny), Kotlin coroutines | **later** | Adapter modules. |
| Fault injection / chaos (`@Inject…` latency/exception, like Polly's Simmy) | **later** | Very useful in tests and staging. |
| Deadline / time-budget propagation across nested calls | **later** | Builds on `ScopedValue` (preview in 21, final in [JDK 25, JEP 506](https://openjdk.org/jeps/506)). |
| Spring/Quarkus/Micronaut integrations | **later** | Out of scope by decision. |

## 4. Annotation design

### 4.1 Principles

1. **Typed attributes wherever Java allows.** Exception lists are `Class<? extends Throwable>[]`, enums replace string
   flags, and fallbacks are classes.
2. **Every attribute has an "unset" sentinel** (`-1`, `""`, an empty array, `Inherit.class`). An annotation cannot
   tell "not written" apart from "written with the default value", so a real default in the attribute would silently
   override named configs and break inheritance. Effective defaults live in the configuration layer (§6), not in the
   annotation.
3. **Durations are strings** in a single strict grammar (`"250ms"`, `"2s"`, `"1m"`, `"PT2S"`), because:
   annotations cannot hold a `Duration`; `value + unit` pairs (MP style) double the attribute count; and the same
   grammar is used in properties files. The annotation processor rejects bad literals at compile time (§10.1).
4. **Usable on methods and types.** A type-level annotation applies to every public method, and a method-level one
   overrides it *attribute by attribute* (not wholesale).
5. **`config` and `name` mean different things**:
   - `config` selects which named template to start from.
   - `name` selects the *stateful instance*. If empty, the instance id is `<fqcn>#<method>(<erased param types>)`.
     Two methods with the same `name` share one breaker or limiter. The two methods must resolve to identical
     configuration; if they do not, startup fails.
6. **Every policy supports `enabled`**, which can be overridden externally (as in MP's `<Annotation>/enabled`).

### 4.2 Proposed annotations (sketch)

```java
package io.resilience4cata.annotation;   // package name provisional

@Retention(RUNTIME) @Target({METHOD, TYPE, ANNOTATION_TYPE}) @Documented
public @interface CircuitBreaker {
    String name() default "";                  // instance id; "" = per method
    String config() default "";                // named template; "" = "default"
    WindowType windowType() default WindowType.INHERIT;   // COUNT, TIME
    int windowSize() default -1;               // calls or seconds
    int minimumCalls() default -1;
    float failureRateThreshold() default -1f;  // percent, 0 < x <= 100
    float slowCallRateThreshold() default -1f;
    String slowCallDuration() default "";
    String waitInOpen() default "";            // e.g. "30s"
    int halfOpenCalls() default -1;
    Class<? extends Throwable>[] recordOn() default {};
    Class<? extends Throwable>[] ignoreOn() default {};
    Class<? extends Classifier> classifier() default Classifier.Inherit.class;
    String enabled() default "";               // "", "true", "false"  (tri-state)
}

@Retention(RUNTIME) @Target({METHOD, TYPE, ANNOTATION_TYPE}) @Documented
public @interface Retry {
    String name() default "";
    String config() default "";
    int maxAttempts() default -1;              // total attempts, including the first
    String delay() default "";                 // initial delay
    Backoff backoff() default Backoff.INHERIT; // FIXED, EXPONENTIAL
    double multiplier() default -1;
    String maxDelay() default "";
    Jitter jitter() default Jitter.INHERIT;    // NONE, FULL, EQUAL
    String maxDuration() default "";           // overall budget across attempts
    Class<? extends Throwable>[] retryOn() default {};
    Class<? extends Throwable>[] abortOn() default {};
    Class<? extends Classifier> classifier() default Classifier.Inherit.class;
    String enabled() default "";
}

public @interface Timeout {            // @Target/@Retention as above
    String value() default "";         // "2s"; empty = from config
    String config() default "";
    String enabled() default "";
}

public @interface Bulkhead {
    String name() default "";
    String config() default "";
    int maxConcurrent() default -1;
    String maxWait() default "";        // "0" = fail fast
    String enabled() default "";
}

public @interface RateLimiter {
    String name() default "";
    String config() default "";
    int permits() default -1;
    String period() default "";         // e.g. "1s"
    String maxWait() default "";
    String enabled() default "";
}

public @interface Fallback {
    Class<? extends io.resilience4cata.FallbackHandler<?>> value() default Inherit.class; // typed handler
    String method() default "";                    // alternative: sibling method, compile-time checked
    Class<? extends Throwable>[] applyOn() default {Throwable.class};
    Class<? extends Throwable>[] skipOn() default {};
}

// v1
public @interface Hedge { String name() default ""; String config() default "";
                          String delay() default ""; int maxHedged() default -1; }
public @interface AdaptiveLimit { String name() default ""; String config() default "";
                                  LimitAlgorithm algorithm() default LimitAlgorithm.INHERIT; /* AIMD, VEGAS, GRADIENT2 */ }
```

Two kinds of annotation are deliberately **left out**:

- `@Asynchronous` (MP): the return type already says whether a call is async. A separate marker that must agree with
  the return type is redundant and easy to get wrong. To run a *synchronous* method on another executor, use
  `@Timeout` with `offload = true` (open question §11) or the programmatic API.
- `@Bulkhead(type = THREADPOOL)` (R4j): virtual threads make semaphore isolation the right default.

### 4.3 Composed annotations ("meta-annotations")

Users should be able to name a policy bundle once and reuse it everywhere:

```java
@ResiliencePolicy                              // marks a composed annotation; required
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
@Bulkhead(maxConcurrent = 50)
public @interface ExternalCall {}

@ResiliencePolicy
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Retry(maxAttempts = 1)                         // explicit: no retries for writes
@CircuitBreaker(config = "external")
@Timeout("5s")
public @interface NonIdempotentCall {}

@ExternalCall
public class PaymentsClient {
    public Receipt charge(Order o) { ... }                    // @ExternalCall applies

    @NonIdempotentCall                                         // overrides the class-level bundle
    public Receipt refund(Order o) { ... }

    @Timeout("500ms")                                          // overrides one attribute only
    public Status status(String id) { ... }
}
```

Rules:

- **Meta-annotation depth**: unlimited, with cycle detection. A composed annotation may itself be annotated with
  another composed annotation.
- **Merge semantics**: this works like Spring's attribute overriding, but simpler. Merging goes by policy type and then
  attribute by attribute. The direct annotation wins over the composed one. Method level wins over class level. An
  unset attribute (sentinel) never overrides anything.
- **Conflicts**: if two composed annotations on the same element both set the same policy attribute to different
  values, that is a **compile-time error** (APT) or a startup error (runtime-only mode). Silent "last wins" behaviour
  is not acceptable.
- Composed annotations get their own config key (`ExternalCall/Timeout/value`), so operators can retune the whole
  family without touching each class.

### 4.4 Interfaces and inheritance

- An annotation on an interface method applies to implementations; an annotation on the implementation wins.
- `@Inherited` is only honoured for class-level annotations (Java semantics). Inheritance through method overrides is
  implemented explicitly and documented.
- Private, static and final methods: whether they can be supported depends on the weaving mechanism (see the
  mechanism research). The API must report unsupported placements as **errors**. Spring AOP's silent "self-invocation
  is not advised" behaviour must not be repeated.

## 5. Execution order

### 5.1 Default

Outermost to innermost:

```
Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method
```

Reasoning:

- **Fallback outermost**: it sees the final outcome after every other policy has run, including `CallNotPermitted`.
- **Retry outside CircuitBreaker**: each attempt is recorded by the breaker, and an open breaker stops retries at once
  (`abortOn` includes `CallNotPermittedException` by default).
- **RateLimiter inside the breaker**: rejected calls do not use up permits.
- **Timeout inside Retry**: the timeout applies to each attempt. `Retry.maxDuration` covers the whole operation.
- **Bulkhead innermost**: a permit is held only while the call is actually running, not during backoff sleeps.

This matches MP FT and is compatible with R4j's default (R4j does not have Fallback as a layer).

### 5.2 Configurable order (v1)

Users who need a different order (for example a timeout around the *whole* retry loop) can declare it explicitly. The
order is validated and stored per instance; it is never decided per call:

```java
@ResiliencePolicy
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Order({Layer.FALLBACK, Layer.TIMEOUT, Layer.RETRY, Layer.CIRCUIT_BREAKER})  // omitted layers keep their relative default order
@Retry(config = "external") @CircuitBreaker(config = "external") @Timeout("10s")
public @interface WholeOperationDeadline {}
```

Global default order: `resilience4cata.order=fallback,retry,circuit-breaker,...`. Instead of a second `@Timeout`, a
*per-attempt* and a *whole-operation* timeout should be two separate attributes: `@Timeout("500ms")` is per attempt and
`@Retry(maxDuration = "3s")` is the total. That covers about 90% of real needs without `@Order`.

Any order that changes semantics in a surprising way (Bulkhead outside Retry, Fallback not outermost) produces a
startup **warning** that names the consequence.

## 6. Multiple configurations

### 6.1 Concepts

| Concept | What it is | Mutable? |
|---|---|---|
| **Defaults** | Built-in values for each policy, overridable under `defaults` | No (after build) |
| **Named config** | A template, e.g. `external`, `internal`, `batch`. Can `extends` another named config | No |
| **Profile** | An overlay that is switched on as a whole (`prod`, `test`, `eu-west`). It overrides keys of defaults and named configs | No |
| **Instance** | Stateful object (breaker state, window, permits) with an id | State yes, config only through `reconfigure` |
| **Resolved policy** | Immutable record with every value final, computed once per instance | No |

### 6.2 Precedence (lowest → highest)

```
 1. built-in defaults
 2. defaults.<policy>.*                       (file/env/…)
 3. configs.<name>.<policy>.*                 (walks the `extends` chain, root first)
 4. profiles.<active>.…                       (applies over 2 and 3, in profile activation order)
 5. composed-annotation attributes            (@ExternalCall)
 6. class-level annotation attributes
 7. method-level annotation attributes
 8. instances.<id>.<policy>.*                 external per-instance override
 9. programmatic override (builder)           highest; for tests and explicit code
```

Within one external layer, the source priority is the same as in MP Config: **system properties > environment
variables > application files > classpath defaults**. Each source plugs in through a small `ConfigSource` SPI.

External config (8) overrides annotations (5–7), as in MP FT. Annotations are the developer's defaults, and operators
must be able to change them without recompiling. A user who wants a value that cannot be overridden marks the instance
`locked: true`, or calls `.locked()` programmatically, and attempts to override it are logged.

### 6.3 External format

Properties (core, no dependency):

```properties
resilience4cata.profiles.active=prod

resilience4cata.defaults.retry.max-attempts=3
resilience4cata.defaults.retry.jitter=full

resilience4cata.configs.external.circuit-breaker.failure-rate-threshold=50
resilience4cata.configs.external.circuit-breaker.wait-in-open=30s
resilience4cata.configs.external.retry.delay=100ms

resilience4cata.configs.slow-external.extends=external
resilience4cata.configs.slow-external.timeout.value=10s

resilience4cata.profiles.test.configs.external.circuit-breaker.wait-in-open=10ms

# per instance, by explicit name …
resilience4cata.instances.payments.circuit-breaker.minimum-calls=20
# … or by derived id, MP-compatible spelling also accepted
com.acme.PaymentsClient/charge/Timeout/value=3s
```

YAML (`yaml` module, v1) is the same tree:

```yaml
resilience4cata:
  defaults:
    retry: { max-attempts: 3, jitter: full }
  configs:
    external:
      circuit-breaker: { failure-rate-threshold: 50, wait-in-open: 30s }
      retry: { delay: 100ms }
    slow-external:
      extends: external
      timeout: { value: 10s }
  profiles:
    test:
      configs:
        external:
          circuit-breaker: { wait-in-open: 10ms }
```

**Environment variables** use the MP Config mapping: uppercase, and every non-alphanumeric character becomes `_`, so
`RESILIENCE4CATA_CONFIGS_EXTERNAL_RETRY_DELAY=200ms`. This mapping cannot be reversed when a config name contains `-`
or `_`. Two rules follow: names are restricted to `[a-z0-9]+(-[a-z0-9]+)*`, and the env source matches by normalising
*known* keys rather than by parsing environment variable names.

### 6.4 Programmatic API

Programmatic configuration has the same power as files and annotations. Annotations are sugar over this model.

```java
Resilience resilience = Resilience.builder()
    .source(ConfigSources.classpath("resilience4cata.properties"))
    .source(ConfigSources.environment())
    .source(ConfigSources.systemProperties())
    .profiles("prod")
    .defaults(d -> d.retry(r -> r.maxAttempts(3).jitter(Jitter.FULL)))
    .config("external", c -> c
        .circuitBreaker(cb -> cb.failureRateThreshold(50).waitInOpen(Duration.ofSeconds(30)))
        .retry(r -> r.delay(Duration.ofMillis(100)).retryOn(IOException.class)))
    .config("slow-external", c -> c.extendsConfig("external").timeout(Duration.ofSeconds(10)))
    .clock(Clock.system())                        // replaceable in tests
    .listener(event -> log.debug("{}", event))
    .strict(true)                                 // fail on unknown keys/names (default true)
    .build();                                     // all validation happens here

// Without annotations: an explicit, reusable, immutable pipeline
Pipeline<Receipt> charge = resilience.pipeline("payments", Receipt.class)
    .config("external")
    .fallback((Throwable t) -> Receipt.pending())
    .build();
Receipt r = charge.execute(() -> client.charge(order));

// With annotations: the woven/generated object comes from the same Resilience instance
PaymentsClient client = resilience.wrap(PaymentsClient.class, new PaymentsClient());  // mechanism TBD (doc 02)
```

### 6.5 Validation: when

| Check | Compile time (APT, if used) | `build()` / startup | Runtime |
|---|---|---|---|
| Attribute ranges, duration syntax, `retryOn ∩ abortOn` overlap | ✔ | ✔ (for runtime-only mode) | |
| Fallback signature compatibility | ✔ | ✔ | |
| Composed-annotation conflicts, illegal placement (private/final) | ✔ | ✔ | |
| Referenced `config` name exists | ✘ (configs are external) | ✔ | |
| Unknown keys in files (typos), with "did you mean `wait-in-open`?" | | ✔ | |
| `extends` cycles, shared `name` with divergent configs | | ✔ | |
| Profile that does not exist | | ✔ (warning) | |

Rule: **after `build()` returns, no configuration error can surface during a call.** Instances may be created lazily
(first call) for startup speed. In that case the method's configuration has still been validated eagerly during
`build()` by scanning the registered annotated types (the APT index lists them).

### 6.6 Keeping the hot path clean

- Resolution produces `record ResolvedRetry(int maxAttempts, long delayNanos, ...)` and similar records: primitives,
  final fields, durations already converted to nanos, exception sets precompiled into a classifier (§7.3).
- Each woven call site holds a direct reference to its `Pipeline` (a static final field in generated code, or a
  per-method cached handle in proxy mode). There are **no map lookups by name per call**.
- Named lookups (`resilience.circuitBreaker("payments")`) are for management and tests, not for invocations.

### 6.7 Hot reload: worth the cost?

**Not in MVP. In v1 as an explicit API, not a file watcher.**

- *Cost*: one `volatile`/acquire read of a config snapshot per policy per call. That is cheap on x86 and slightly more
  expensive on ARM. It also blocks some JIT constant folding, which matters when policy parameters are otherwise
  treated as constants. Instances that are never reconfigured should pay **nothing**. Possible ways to achieve that:
  a `reloadable` flag that selects a different implementation at build time, or `MutableCallSite`/`SwitchPoint`
  invalidation, which keeps the fast path constant-folded. The concurrency research should benchmark these.
- *Semantics*: some parameters can change safely (thresholds, delays, max attempts, permits, timeout). Others change
  the state shape (window type or size, bulkhead size while permits are held). The latter require a documented state
  reset or are rejected.
- *API*:

```java
resilience.reconfigure("payments", c -> c.circuitBreaker(cb -> cb.failureRateThreshold(30)));
// or: resilience.reload()  -> re-reads all sources, validates everything, then swaps atomically, all-or-nothing
```

Watching files or remote config is left to integrations, which call `reload()`. Polly's equivalent behaviour also
comes from its host (`IOptionsMonitor`), not from the core library.

## 7. Fallbacks and exception classification

### 7.1 Typed fallback handler (primary form)

```java
public interface FallbackHandler<T> {
    T apply(FallbackContext ctx) throws Throwable;   // ctx: failure, attempt count, args, method, instance id
}

public final class UserFallback implements FallbackHandler<User> {
    @Override public User apply(FallbackContext ctx) {
        return ctx.failure() instanceof CallNotPermittedException
            ? User.anonymous()
            : User.cached(ctx.argument(0, String.class));
    }
}

public class UserClient {
    @Retry @CircuitBreaker(config = "external")
    @Fallback(UserFallback.class)
    public User find(String id) { ... }
}
```

The annotation processor checks that `T` is assignable to the method's return type, or to `T` inside
`CompletionStage<T>` for async methods. Handlers are created once per instance through a `HandlerFactory`, which
defaults to the no-arg constructor and can be plugged into DI later.

### 7.2 Sibling-method fallback (convenience form, compile-time checked)

```java
@Fallback(method = "findFallback")
public User find(String id) { ... }

private User findFallback(String id, IOException e) { ... }        // most specific match wins
private User findFallback(String id, Throwable t) { ... }
```

Rules, which follow R4j and Spring `@Recover` and are made strict:

- Parameters must be the original parameters, optionally followed by **one** `Throwable` subtype.
- The return type must be assignable to the original return type. For async methods it can be `T` or
  `CompletionStage<T>`.
- When several overloads exist, the most specific exception type wins. If none matches, the original exception is
  rethrown.
- **All checks run in the annotation processor**, and errors include suggestions (§10.1). In runtime-only mode they run
  at `build()`.
- The string cannot be avoided: Java annotations cannot hold method references. Generating constants from the APT
  metamodel (`UserClient_.FIND_FALLBACK`) was considered and rejected, because of round ordering problems and
  confusing errors when the processor is not active.

### 7.3 Exception and result classification

Each policy that records outcomes (CircuitBreaker, Retry, Hedge, AdaptiveLimit) has three inputs:

1. **Allow/deny lists**: `recordOn`/`ignoreOn` (CB), `retryOn`/`abortOn` (Retry). Precedence: **deny beats allow**,
   then the **most specific type** in the hierarchy wins (MP FT semantics). Defaults: CB records `Exception`, Retry
   retries `Exception`, and neither handles `Error` (except `StackOverflowError`? → open question).
2. **Unwrapping**: `CompletionException`, `ExecutionException`, `InvocationTargetException` and
   `UndeclaredThrowableException` are unwrapped before classification. This is configurable.
3. **Classifier** for anything the lists cannot express, results included:

```java
@FunctionalInterface
public interface Classifier<T> {
    Outcome classify(T result, Throwable failure);   // exactly one of result/failure is meaningful
    enum Outcome { SUCCESS, FAILURE, IGNORE }        // IGNORE = do not count, do not retry
}

public final class HttpStatusClassifier implements Classifier<HttpResponse<?>> {
    public Outcome classify(HttpResponse<?> r, Throwable t) {
        if (t != null) return t instanceof HttpTimeoutException ? Outcome.FAILURE : Outcome.IGNORE;
        int s = r.statusCode();
        return s >= 500 || s == 429 ? Outcome.FAILURE : Outcome.SUCCESS;
    }
}

@Retry(config = "external", classifier = HttpStatusClassifier.class)
@CircuitBreaker(config = "external", classifier = HttpStatusClassifier.class)
public HttpResponse<String> get(URI uri) { ... }
```

- Using a single method for both results and exceptions avoids R4j's split between `recordExceptions`,
  `recordFailurePredicate` and `recordResultPredicate`, and the confusion about precedence among them.
- A `FAILURE` *result* is recorded or retried. If retries are exhausted, the last result is returned (default) or a
  `ResultRejectedException` is thrown. This is configurable through `Retry.onExhaustedResult = RETURN | THROW`.
- **Performance**: lists are compiled at resolution time into a `ClassValue<Outcome>` cache, so classifying a given
  exception class is a single lookup after the first time. That is cheaper than walking `isAssignableFrom` over the
  list on every failure.

## 8. Async and virtual threads

- **Return type decides**: `CompletionStage<T>`/`CompletableFuture<T>` gets the async pipeline (non-blocking retries
  scheduled on a `Scheduler`, timeouts via `orTimeout`-like scheduling, bulkhead permits released on completion).
  Any other return type is sync.
- **Sync on virtual threads** is the recommended Java 21 style. Retry backoff sleeps the virtual thread, which is
  cheap ([JEP 444](https://openjdk.org/jeps/444)). Semaphore bulkheads block virtual threads instead of platform
  threads.
- **Sync timeout** needs either (a) running the call on another (virtual) thread and interrupting it on timeout, or
  (b) a watchdog that interrupts the caller thread. (b) avoids a thread hop but depends on the callee honouring
  interrupts. Proposal: (b) by default for sync, documented as "cooperative". The concurrency research decides the
  implementation. Structured concurrency ([JEP 505](https://openjdk.org/jeps/505)) is still a preview in recent JDKs,
  so the API must not depend on it.
- **Executors** are configurable per config (`async.executor`), with `Executors.newVirtualThreadPerTaskExecutor()` as
  the default for any work the library itself offloads.
- **Hedging** (v1) needs concurrency, so it always offloads hedged attempts, also for sync methods, and cancels the
  losers.

## 9. Events, metrics and state inspection

```java
public sealed interface ResilienceEvent permits CircuitBreakerEvent, RetryEvent, TimeoutEvent,
                                                BulkheadEvent, RateLimiterEvent, FallbackEvent {
    String instance(); long nanoTime();
}
public sealed interface CircuitBreakerEvent extends ResilienceEvent {
    record StateTransition(String instance, long nanoTime, State from, State to) implements CircuitBreakerEvent {}
    record CallNotPermitted(String instance, long nanoTime) implements CircuitBreakerEvent {}
    record FailureRateExceeded(String instance, long nanoTime, float rate) implements CircuitBreakerEvent {}
}

resilience.listen(CircuitBreakerEvent.StateTransition.class, e -> alerting.notify(e));
resilience.listen("payments", RetryEvent.class, e -> ...);     // per instance
```

- **Zero cost when unused**: each instance holds a `final` listener reference that is `NOOP` when nothing is
  registered. Event records are only created behind an `if (listener != NOOP)` check. Per-call success events are
  opt-in (`events.per-call=true`). State-transition events are always delivered.
- **Metrics**: core counts outcomes in instance-local `LongAdder`s, exposed through `snapshot()`. The `micrometer` and
  `opentelemetry` modules (v1) bind the snapshots as gauges or function counters, so the hot path has no meter
  lookups.
- **State inspection** (health checks, admin endpoints):

```java
ResilienceSnapshot s = resilience.snapshot();
for (CircuitBreakerSnapshot cb : s.circuitBreakers()) {
    System.out.printf("%s %s failureRate=%.1f%% calls=%d%n", cb.instance(), cb.state(), cb.failureRate(), cb.bufferedCalls());
}
resilience.circuitBreaker("payments").forceOpen();   // operational controls: forceOpen, forceClosed, disable, reset
```

## 10. Developer experience and testing

### 10.1 Error messages

All errors include **where** (class, method, annotation, attribute, or config key with its source), **what**, and
**how to fix it**:

```
error: [R4C-FB-002] @Fallback(method = "findFallbak") on com.acme.UserClient.find(String):
       no method named 'findFallbak' found.
       Did you mean 'findFallback'?
       Expected signature: User findFallbak(String id[, <? extends Throwable> e])

error: [R4C-CFG-011] unknown key 'resilience4cata.configs.external.circuit-breaker.wait-in-opne'
       (from classpath:resilience4cata.properties, line 7). Did you mean 'wait-in-open'?

error: [R4C-CMP-004] conflicting values for @Timeout.value on com.acme.PaymentsClient.charge(Order):
       "2s" from @ExternalCall and "5s" from @NonIdempotentCall. Set it explicitly on the method to resolve.
```

- Stable error codes (`R4C-…`) with a docs page per code, in English and Spanish.
- A **diagnostic dump**, `resilience.describe("com.acme.PaymentsClient#charge")`, prints the effective configuration
  with the *origin* of every value (`waitInOpen=30s ← configs.external (prod profile, env)`). This helps a lot with
  layered config.
- Compile-time checks are available when the annotation processor is in use (the mechanism decision belongs to
  doc 02). The same checks run at `build()` otherwise, with identical messages.

### 10.2 Testing story

Core provides the hooks (MVP); the `test` module adds conveniences (JUnit extension in v1).

```java
var clock = new ManualClock();                       // implements io.resilience4cata.Clock (nanoTime + wall)
var scheduler = new ManualScheduler(clock);          // retries/timeouts advance on clock.advance(...)

Resilience r = Resilience.builder()
    .clock(clock).scheduler(scheduler)
    .random(new SplittableRandom(42))                // deterministic jitter
    .profiles("test")
    .build();

UserClient client = r.wrap(UserClient.class, fakeClient);

fakeClient.failNext(5, new IOException("boom"));
assertThat(client.find("1")).isEqualTo(User.anonymous());

clock.advance(Duration.ofSeconds(30));               // move past waitInOpen without sleeping
assertThat(r.circuitBreaker("payments").state()).isEqualTo(State.HALF_OPEN);

// JUnit 5 (v1)
@ResilienceTest(profiles = "test")
class UserClientTest {
    @Inject ManualClock clock;
    @Inject RecordedEvents events;

    @Test void opensAfterFailures(@ForceState(instance = "payments", state = State.OPEN) CircuitBreakerHandle cb) {
        assertThrows(CallNotPermittedException.class, () -> client.find("1"));
        events.assertThat().containsExactly(CallNotPermitted.class);
    }
}
```

- **Disable everything** for unit tests of business logic with `resilience4cata.enabled=false`, or
  `Resilience.disabled()`, a pass-through that still runs fallbacks only if `fallback.when-disabled=true`.
- **Reset between tests**: `resilience.reset()` resets every instance's state but keeps the configuration.
- **Fault injection** (later) is reusable in integration tests: `@InjectLatency("200ms", rate = 0.1)`.

## 11. Open questions

1. Should durations be strings (proposed) or `long` + `TimeUnit` (MP style)? Strings are readable and match the file
   format; numbers get type checks from javac even without the processor.
2. Should the annotation processor be **required** or optional? If optional, every compile-time check must be repeated
   at `build()`. This depends on the mechanism decision (doc 02).
3. Should `Error`s (e.g. `OutOfMemoryError`) bypass every policy? Proposal: yes, except a configurable allow-list.
4. Should `@Timeout` offer `offload = true` for code that does not respond to interrupts?
5. Should external config be able to **add** policies a method has not declared
   (`instances.<id>.retry.enabled=true`)? MP FT only allows overriding declared ones. Allowing it is powerful but makes
   code harder to reason about, and it costs some generated-code flexibility. Proposal: no.
6. Do we keep MP-compatible keys (`<class>/<method>/<Annotation>/<param>`) only as aliases, or drop them? They help
   migration but double the documentation.
7. Default instance granularity: per method (proposed) or per class? R4j's per-name sharing is common for "one breaker
   per downstream service". The explicit `name` covers that case.

## 12. Sources

- Resilience4j documentation: <https://resilience4j.readme.io/docs> — Spring Boot / annotation usage and aspect order:
  <https://resilience4j.readme.io/docs/getting-started-3>; source: <https://github.com/resilience4j/resilience4j>
- MicroProfile Fault Tolerance 4.1 specification (annotations, configuration keys, execution order):
  <https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html>;
  repository: <https://github.com/eclipse/microprofile-fault-tolerance>
- MicroProfile Config 3.1, environment variable mapping:
  <https://download.eclipse.org/microprofile/microprofile-config-3.1/microprofile-config-spec-3.1.html>
- SmallRye Fault Tolerance (extensions: `@RateLimit`, backoff annotations, `@ApplyGuard`, programmatic API):
  <https://smallrye.io/docs/smallrye-fault-tolerance/>
- Spring Retry (`@Retryable`, `@Recover`, `@Backoff`): <https://github.com/spring-projects/spring-retry>
- Spring Framework 7 core resilience features (`@Retryable`, `@ConcurrencyLimit`):
  <https://docs.spring.io/spring-framework/reference/core/resilience.html>
- Failsafe policies and composition: <https://failsafe.dev/policies/>, <https://failsafe.dev/>
- Polly v8: pipelines <https://www.pollydocs.org/pipelines/>, hedging <https://www.pollydocs.org/strategies/hedging.html>,
  registry and dynamic reloads <https://www.pollydocs.org/advanced/dependency-injection.html>,
  chaos <https://www.pollydocs.org/chaos/>
- Netflix concurrency-limits (adaptive limits): <https://github.com/Netflix/concurrency-limits>
- J. Dean, L. Barroso, "The Tail at Scale" (hedged requests): <https://research.google/pubs/the-tail-at-scale/>
- M. Brooker, "Exponential Backoff And Jitter": <https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/>
- Finagle retry budgets: <https://twitter.github.io/finagle/guide/Clients.html#retries>
- JEP 444 Virtual Threads: <https://openjdk.org/jeps/444>; JEP 505 Structured Concurrency (preview):
  <https://openjdk.org/jeps/505>; JEP 506 Scoped Values: <https://openjdk.org/jeps/506>
