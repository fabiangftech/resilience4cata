# 01 — Landscape: Resilience4j and Prior Art

> **Historical.** This research predates the implementation and is kept as written; for what 0.1.0 actually does, see [features](../features.md), [architecture](../architecture.md) and [benchmarks](../benchmarks.md).

> Phase 1 research. No production code. Last updated: 2026-10-23.
> Scope: what exists, how the baseline (Resilience4j) works internally, where its costs come from, and what a new annotation-driven, plain-Java, Java 21+ library can do better.
> Out of scope (covered by sibling documents): the choice of annotation-processing mechanism, detailed concurrency primitive design, the API/config surface, module layout.

## Evidence legend

Every claim is tagged:

| Tag | Meaning |
|---|---|
| **[SRC]** | Verified by reading source code. Resilience4j at `master` commit `b7c0069` (2026-10-07, post-2.4.0); Failsafe at `master` (last commit 2025-12-28); Sentinel `master` via GitHub API. |
| **[BENCH]** | Verified from a published benchmark or a results file in a repository. |
| **[DOC]** | Verified from official documentation, release notes or a project README. |
| **[ISSUE]** | Verified from a GitHub issue/PR (a user report, not necessarily confirmed by maintainers). |
| **[INF]** | Inferred by us (reasoning from code/JVM behaviour, not measured). Must be validated with JMH in a later phase. |

---

## 1. Resilience4j in depth

### 1.1 Status and shape

- Latest release **2.4.0** (2026-03-14); 2.3.0 (2025-01-03); ~10.8k stars; ~348 open issues. [DOC] ([releases](https://github.com/resilience4j/resilience4j/releases))
- 2.4.0 raised the compile baseline from JDK 17 to **JDK 21** "for support virtual thread" (PR #2331); `build.gradle` sets `sourceCompatibility = JavaVersion.VERSION_21`. [DOC][SRC] So "Java 21" is no longer a differentiator by itself.
- Vavr was removed as a dependency in 2.0.0. [DOC] ([RELEASENOTES.adoc](https://github.com/resilience4j/resilience4j/blob/master/RELEASENOTES.adoc))
- Gradle multi-project, ~38 modules: `circuitbreaker`, `retry`, `ratelimiter`, `bulkhead`, `timelimiter`, `cache` (JCache), `hedge` (newer), `circularbuffer`, `consumer`, `core`, `annotations`, `micrometer`, `metrics`, `feign`, `kotlin`, `reactor`, `rxjava2/3`, `vavr`, `spring6`, `spring-boot3`, `spring-boot4`, `micronaut`, `commons-configuration`, `framework-common`, `all`, `bom`, `test`. [SRC]

### 1.2 Features per module

| Module | What it does | Implementation notes |
|---|---|---|
| **CircuitBreaker** | CLOSED / OPEN / HALF_OPEN plus DISABLED, FORCED_OPEN, METRICS_ONLY. Count- or time-based sliding window; failure-rate **and** slow-call-rate thresholds; `minimumNumberOfCalls`; record/ignore exception predicates; result predicate. | State object per state behind `AtomicReference<CircuitBreakerState>`; transitions by CAS. A `ReentrantLock` guards only OPEN→HALF_OPEN. [SRC] |
| **Retry** | max attempts, `IntervalFunction` (fixed, exponential, randomized, custom), retry-on-exception / retry-on-result predicates, sync + async. | A new `ContextImpl` per call, holding one `AtomicInteger` and two `AtomicReference`s (4 objects per call even when the first attempt succeeds); sleeps via a static `Thread::sleep` function. [SRC] |
| **RateLimiter** | `AtomicRateLimiter` (default, cycle-based) and `SemaphoreBasedRateLimiter` (scheduler refresh). | See §1.4. |
| **Bulkhead** | `SemaphoreBulkhead` (`java.util.concurrent.Semaphore`, optional fairness, `maxWaitDuration`) and `FixedThreadPoolBulkhead` (`ThreadPoolExecutor` + `ArrayBlockingQueue`/`SynchronousQueue`, `CompletableFuture.supplyAsync`, `ContextPropagator`). | [SRC] |
| **TimeLimiter** | Timeouts for `Future` (`future.get(timeout)`) and `CompletionStage` (schedules a `TimeoutException` on a `ScheduledExecutorService`). | Cannot interrupt a plain synchronous call: it needs a future, so sync code must be offloaded to another thread. [SRC] |
| **Cache** | Wraps JCache (JSR-107). | Rarely used; thin wrapper. [SRC][INF] |
| **Hedge** | Hedged requests (issue #1454). | Newer module. [SRC] |
| **Fallback** | No core module. Available as `Decorators.withFallback(...)` and, with annotations, a `fallbackMethod = "name"` string resolved by reflection in the Spring/Micronaut integrations. | See §1.3. [SRC] |
| **Registries** | `CircuitBreakerRegistry` etc. with named configs ("default" plus named); `InMemoryRegistryStore` on `ConcurrentHashMap<String, CompletableFuture<E>>`, deliberately avoiding `computeIfAbsent` to prevent virtual-thread pinning. | [SRC] |
| **Events** | `EventProcessor` with `CopyOnWriteArraySet` consumers, plus a per-event-type map. `hasConsumers()` guard. | See §1.5. [SRC] |

### 1.3 How the annotations work, and what plain-Java users get

The `resilience4j-annotations` module contains **only annotation declarations**: `@CircuitBreaker(name, configuration, fallbackMethod)`, `@Retry`, `@RateLimiter`, `@Bulkhead`, `@TimeLimiter`, `@Timer`. It has no processor. [SRC] ([CircuitBreaker.java](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-annotations/src/main/java/io/github/resilience4j/circuitbreaker/annotation/CircuitBreaker.java))

Behaviour is implemented by:

- **Spring** (`resilience4j-spring6`, `spring-boot3/4`): AspectJ-annotated `@Around` aspects run by Spring AOP proxies (`CircuitBreakerAspect`, `RetryAspect`, ...). [SRC]
- **Micronaut** (`resilience4j-micronaut`): Micronaut compile-time AOP `MethodInterceptor`s. [SRC]

**Plain Java gets nothing declarative.** Without Spring or Micronaut, a user must either use the functional decorator API (`CircuitBreaker.decorateSupplier(cb, supplier)`, `Decorators.ofSupplier(..).withRetry(..).withCircuitBreaker(..)`) or set up AspectJ weaving themselves (the Spring aspects are tied to Spring beans: `SpelResolver`, `BeanFactory`, `FallbackExecutor`). [SRC][INF] This is the biggest ergonomic gap for our target audience.

**Per-call work in the Spring aspect.** `CircuitBreakerAspect.circuitBreakerAroundAdvice` does the following on **every** invocation [SRC] ([aspect](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/circuitbreaker/configure/CircuitBreakerAspect.java), [DefaultSpelResolver](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/spelresolver/DefaultSpelResolver.java), [FallbackExecutor](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackExecutor.java), [FallbackMethod](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackMethod.java)):

1. `MethodSignature.getMethod()`, then builds `methodName` by string concatenation (`declaringClass.getName() + "#" + method.getName()`).
2. `spelResolver.resolve(method, args, annotation.name())`: up to four `Pattern.matcher(...).matches()` regex checks on the name, even for a literal name. If it is an expression, it builds a new `MethodBasedEvaluationContext` and **parses the SpEL expression on every call**.
3. Registry lookup `getOrCreateCircuitBreaker(methodName, backend, configKey)`.
4. Allocates a `CheckedSupplier` lambda capturing the join point.
5. `FallbackExecutor.execute` resolves the fallback name through SpEL **again**, then calls `FallbackMethod.create(...)`, which allocates a `MethodMeta`, clones `getParameterTypes()`, does a `ConcurrentHashMap.computeIfAbsent` lookup in a static cache, and allocates a new `FallbackMethod`. The fallback is later invoked via reflective `Method.invoke`.
6. Return type is inspected (`CompletionStage`, Reactor/RxJava extension list) to pick a handler.

Users have reported the aspect being slow (issue [#1377](https://github.com/resilience4j/resilience4j/issues/1377), "CircuitBreaker advice taking 2s", most likely a config/slow-call artefact, unresolved). [ISSUE] There is also an open request to support **meta-annotations** / composed annotations ([#2405](https://github.com/resilience4j/resilience4j/issues/2405), 2026-03). [ISSUE]

**Takeaway:** everything in steps 1–6 except the actual permit/record is invariant per method and can be resolved once, at build time or on first call. [INF]

### 1.4 Core internals

**Circuit breaker state machine** ([CircuitBreakerStateMachine.java](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java)) [SRC]
- `decorateSupplier` calls `acquirePermission()`, reads the start timestamp, runs the supplier, then `onResult(duration, unit, result)` or `onError(...)`. `java.lang.Error` is not handled.
- Timestamps come from `Function<Clock, Long> currentTimestampFunction`, defaulting to `clock -> System.nanoTime()`. The **boxed `Long`** return means a possible allocation per timestamp, two per call, unless escape analysis removes it. [SRC] for the signature, [INF] for the allocation.
- OPEN-state expiry uses `clock.instant().isAfter(...)`, which allocates an `Instant` per rejected call. [SRC]/[INF]
- The record path: `ClosedState.onSuccess` → `CircuitBreakerMetrics.onSuccess` → `Metrics.record(...)` returns a **new `SnapshotImpl`** each call. [SRC]

**Sliding windows** (`resilience4j-core/.../core/metrics`) [SRC]
- `FixedSizeSlidingWindowMetrics` (count-based) and `SlidingTimeWindowMetrics` (time-based): a ring of `Measurement` buckets plus a `TotalAggregation`, all mutations under **one `ReentrantLock`** (it was `synchronized` until 2.3.0, changed to avoid virtual-thread pinning, #2232). Each `record` allocates a `SnapshotImpl`. **This is still the default** (`DEFAULT_SLIDING_WINDOW_SYNCHRONIZATION_STRATEGY = SYNCHRONIZED`).
- Since 2.3.0 there is an opt-in `SlidingWindowSynchronizationStrategy.LOCK_FREE` ([PR #2241](https://github.com/resilience4j/resilience4j/pull/2241), Lincheck-verified), prompted by a user who saw `metrics.record` blocking under load ([#2151](https://github.com/resilience4j/resilience4j/issues/2151)). [ISSUE] The count-based lock-free version is a CAS-linked list of nodes. **Each record copies the tail's `PackedAggregation`, allocates a new `Node`, and allocates a `SnapshotImpl`**, so it is lock-free but not allocation-free, and it retries with `CASBackoffUtil` under contention. [SRC]
- Defect found while reading: the LOCK_FREE time-based window is built with `io.github.resilience4j.core.Clock.SYSTEM`, **ignoring the user-configured `Clock`** (the SYNCHRONIZED path passes `clock`) (`CircuitBreakerMetrics.buildTimeBasedMetrics`). [SRC] This makes tests with a fake clock behave differently between strategies.

**AtomicRateLimiter** ([source](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/AtomicRateLimiter.java)) [SRC]
- Immutable `State(config, activeCycle, activePermissions, nanosToWait)` in an `AtomicReference`. Time is divided into cycles of `limitRefreshPeriod`. Permissions are refilled lazily on access (no background thread), and `activePermissions` may go negative to "reserve" future permits.
- `updateStateWithBackOff`: CAS loop. **Every attempt allocates a new `State`** (failed attempts are garbage); `parkNanos(1)` back-off on CAS failure. Under heavy contention this is a single-hot-word design: all callers CAS one reference. [SRC]; contention behaviour is [INF].
- Waiting is done by `parkNanos` loops on the calling thread.

**Bulkhead**: `java.util.concurrent.Semaphore` (AQS) for the semaphore variant. Fine for low-to-moderate contention; AQS enqueues waiters. [SRC]/[INF]

**Retry**: the per-call `ContextImpl` described above (4 allocations). Per-instance metrics are `LongAdder`s (good). [SRC]

### 1.5 Event publishing

- `EventProcessor.hasConsumers()` checks two collections (`CopyOnWriteArraySet` plus a `ConcurrentHashMap`). [SRC]
- `publishSuccessEvent` / `publishCircuitErrorEvent` check `hasConsumers()` **before** allocating the event. `publishCallNotPermittedEvent`, `publishCircuitIgnoredErrorEvent`, `publishStateTransitionEvent`, `publishResetEvent` and the failure-rate-exceeded events **allocate the event first** (every event's constructor calls `ZonedDateTime.now(...)`) (on the rejection path, which is the hot path when the breaker is OPEN). [SRC] Issue [#1391](https://github.com/resilience4j/resilience4j/issues/1391) (event allocation on success path, with a profiler screenshot) is still open since 2021. [ISSUE]
- **In Spring Boot, events are always on:** `CircuitBreakerConfiguration.registerEventConsumer` attaches a `CircularEventConsumer` (default buffer **100**) to **every** circuit breaker for the actuator endpoints. So in the most common deployment, every call allocates an event object (`CircuitBreakerOnSuccessEvent` with a `ZonedDateTime`, a `Duration`, and a name), plus a circular-buffer insert. [SRC]; the cost magnitude is [INF].

### 1.6 Published numbers

- The repository ships `resilience4j-circuitbreaker/src/jmh/resources/resultsWithGCProfiling.txt` (old run, Parallel GC era): [BENCH]

  | Benchmark | ops/µs | B/op |
  |---|---|---|
  | `directSupplier` (no CB) | 12.26 | ~0 |
  | `protectedSupplier` (CB) | 4.12 | **272** |
  | `protectedSupplierWithOneConsumer` | 4.02 | 284 |

  So historically ~0.16 µs and **272 bytes per call** of overhead for a bare circuit breaker, single-threaded. Current numbers are not published; JMH sources exist for CB, bulkhead, rate limiter and metrics (including `VirtualThreadMetricsBenchmark`). [SRC]
- Issue [#117](https://github.com/resilience4j/resilience4j/issues/117) (2017): a maintainer prototyped a **zero-allocation CircuitBreaker**, which was not adopted because it needed API changes. [ISSUE] This proves the target is achievable, and that API shape (event objects, `Snapshot` return values) is what blocks it.
- Issue [#1513](https://github.com/resilience4j/resilience4j/issues/1513) (bachelor thesis, R4j 1.6.1, Java 11): ~500 ns/op CB overhead in the user's JMH run. [ISSUE] (hardware-dependent).
- Issue [#2023](https://github.com/resilience4j/resilience4j/issues/2023): semaphore vs thread-pool bulkhead behaving unexpectedly under load. [ISSUE]
- Academic: an SBRC paper compares Retry/CB in Polly and Resilience4j and finds "slight to moderate" execution-time impact ([paper](https://sol.sbc.org.br/index.php/sbrc/article/download/21194/21019)). [DOC] (abstract only)
- **No public head-to-head JMH comparison** of Resilience4j vs Failsafe vs SmallRye exists that we could find. [DOC, negative result] We should publish one: it is both a validation tool and a marketing asset.

### 1.7 Where Resilience4j's overhead comes from (summary)

| # | Source | Evidence |
|---|---|---|
| 1 | Per-call SpEL/regex name resolution, string concat, fallback lookup, lambda capture in Spring aspects | [SRC] |
| 2 | Reflective fallback invocation (`Method.invoke`) | [SRC] |
| 3 | Event objects allocated per call whenever any consumer exists (always under Spring Boot); some event types are allocated even with no consumers | [SRC] |
| 4 | `SnapshotImpl` allocated on every `record` (both strategies); lock-free window also allocates `Node` + aggregation copy | [SRC] |
| 5 | Default sliding window serialised under one `ReentrantLock` → contention at high thread counts (#2151) | [SRC][ISSUE] |
| 6 | Boxed `Long` timestamps via `Function<Clock, Long>`; `Instant` in OPEN state | [SRC] / [INF] |
| 7 | Retry allocates 4 objects per call even on first-try success | [SRC] |
| 8 | Rate limiter: single `AtomicReference<State>`, new `State` per CAS attempt | [SRC] |
| 9 | Megamorphic `Supplier` chains when decorators are stacked (`Decorators`), which limits inlining | [INF] |
| 10 | Configuration as large objects with `Predicate`/`Function` fields checked per call (record/ignore exception predicates walk class hierarchies) | [SRC] partial / [INF] |

---

## 2. Competitors and prior art

### 2.1 Failsafe (`dev.failsafe`)

- **Status:** 3.3.2 is the latest tag; last commits Dec 2025 (CI/test only) and before that June 2024. **Effectively in maintenance.** [DOC] ([repo](https://github.com/failsafe-lib/failsafe))
- **Features:** RetryPolicy, CircuitBreaker (count and time based, with thresholding by ratio), RateLimiter (smooth and bursty), Bulkhead, Timeout, Fallback; policy composition `Failsafe.with(fallback, retry, cb, timeout)`; sync, async (`CompletableFuture`), and an `AsyncExecution` style for callback APIs; `Call` for cancellable executions; event listeners per policy. [DOC][SRC]
- **Annotations:** none. [DOC]
- **Performance:** circuit state recording is **`synchronized`** (`CircuitState.recordSuccess/recordFailure`, `ClosedState.checkThreshold`, `HalfOpenState`), and so is `SyncExecutionImpl.preExecute/postExecute`. A new `SyncExecutionImpl` is created per call and a new `ExecutionResult` per attempt. [SRC] On Java 21–23, `synchronized` pins virtual threads ([JEP 491](https://openjdk.org/jeps/491) fixed this only in JDK 24). [DOC]
- **Copy:** the clean composition model (policies are pure config; executors are created per composition; the outermost policy is listed first); `Call`/cancellation; one consistent listener model; zero dependencies.
- **Avoid:** coarse `synchronized`; per-call execution objects; no declarative layer.

### 2.2 SmallRye Fault Tolerance / MicroProfile Fault Tolerance (key reference)

- **Status:** MicroProfile FT spec **4.1** (4.1.2, Mar 2025). SmallRye FT **6.9.x / 6.10.x** (Sep 2026), actively developed; it is the implementation in Quarkus and WildFly. [DOC] ([spec](https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html), [SmallRye](https://github.com/smallrye/smallrye-fault-tolerance))
- **Spec annotations:** `@Retry(maxRetries, delay, maxDuration, jitter, retryOn, abortOn)`, `@Timeout`, `@Fallback(value=Handler.class | fallbackMethod, applyOn, skipOn)`, `@CircuitBreaker(requestVolumeThreshold, failureRatio, successThreshold, delay, failOn, skipOn)`, `@Bulkhead(value, waitingTaskQueue)`, `@Asynchronous`. [DOC]
- **External config (copy this):** every annotation parameter can be overridden through MicroProfile Config, with the most specific key winning: `<class>/<method>/<Annotation>/<param>` > `<class>/<Annotation>/<param>` > `<Annotation>/<param>`. The same applies to `enabled`, plus a global `MP_Fault_Tolerance_NonFallback_Enabled`. [DOC] This is exactly the "defaults + per-method override + external config" model our goals describe, and it is proven in production.
- **Semantics worth copying:** a defined interaction contract (Fallback outermost; each retry attempt passes through the CB; timeout restarts per attempt; CB checked before bulkhead); circuit-breaker state is a singleton per (bean class, method). [DOC]
- **SmallRye extensions:** `@RateLimit`, `@ExponentialBackoff`, `@FibonacciBackoff`, `@CustomBackoff`, `@RetryWhen`, `@BeforeRetry`, Kotlin/Mutiny/RxJava async types. **`@ApplyGuard("name")`** (6.7.0+, earlier `@ApplyFaultTolerance`) applies a named, shared, programmatically built `Guard` to many methods, configurable via `smallrye.faulttolerance."name".retry.max-retries` (6.8.0+). [DOC] ([reusable](https://smallrye.io/docs/smallrye-fault-tolerance/6.8.0/reference/reusable.html))
- **Plain Java:** `smallrye-fault-tolerance-standalone` supports **only the programmatic API** (`Guard`, `TypedGuard`); annotations require CDI interceptors (ArC/Weld). [DOC] ([programmatic API](https://smallrye.io/docs/smallrye-fault-tolerance/6.9.3/reference/programmatic-api.html)) So like Resilience4j, there is no standalone declarative path.
- **Performance:** Quarkus validates annotations and precomputes per-method strategy chains at build time (ArC). [INF from Quarkus architecture; not benchmarked here]
- **Avoid:** `Future` as an async type; `Class<?>[]`-only exception matching; requiring a container.

### 2.3 Netflix Hystrix (historical)

- **Status:** maintenance mode since 2018 (final 1.5.18). Netflix's README points to Resilience4j and to **adaptive concurrency limits**. [DOC] ([README](https://github.com/Netflix/Hystrix))
- **Lessons:**
  - Thread-pool isolation by default was expensive (a context switch per call, thread-local loss), and most users switched to semaphores. Make semaphore/limit isolation the default; thread offload should be opt-in. [DOC/INF]
  - The command-object pattern (`HystrixCommand` subclass per call) is heavy and hurt ergonomics. `@HystrixCommand` (javanica) needed AspectJ/Spring. [DOC]
  - Rolling-window "health counts" over time buckets and request collapsing were good ideas. The RxJava-based internals were costly. [INF]
  - Static, pre-tuned thresholds are brittle. Netflix moved to adaptive limits ([concurrency-limits](https://github.com/Netflix/concurrency-limits), v0.5.4 Dec 2025: Vegas, Gradient2, AIMD). [DOC]

### 2.4 Alibaba Sentinel

- **Status:** 1.8.10 (May 2026), active. [DOC] ([repo](https://github.com/alibaba/Sentinel))
- **Features:** flow control by QPS or concurrent threads, with warm-up and rate-limiting shaping; circuit breaking (slow ratio, error ratio, error count); **hot-parameter** flow control; system adaptive protection (load/CPU/RT); cluster flow control; a dashboard; dynamic rule sources (Nacos, ZooKeeper, Apollo, ...). [DOC]
- **Annotations:** `@SentinelResource(value, blockHandler, fallback, ...)` through `sentinel-annotation-aspectj` (AspectJ) or `sentinel-annotation-cdi-interceptor`. [SRC] Again, no standalone weaving.
- **Internals (copy):** `LeapArray`, a ring of time buckets in an `AtomicReferenceArray<WindowWrap>`, created by CAS, reset under `tryLock()` with `Thread.yield()` fallback. Per-bucket counters are **`LongAdder[]`** (`MetricBucket`), so it is striped and contention-friendly. [SRC]
- **Avoid:** global `Context`/`Entry` thread-local machinery and a slot-chain per resource with string-keyed resources (heavy); rules decoupled from code makes local reasoning hard. [INF]

### 2.5 Spring Retry → Spring Framework 7 resilience

- **Spring Retry is archived**; its README says it is "superseded by Spring Framework 7". Last release 2.0.13 (Jun 2026). [DOC] ([repo](https://github.com/spring-projects/spring-retry))
- **Spring Framework 7.0** core resilience: `@Retryable(includes, excludes, maxAttempts, delay, jitter, multiplier, maxDelay)`, `@ConcurrencyLimit(n)` ("particularly useful with Virtual Threads"), `RetryTemplate`/`RetryPolicy`, enabled with `@EnableResilientMethods`; reactive return types use Reactor retry. **No circuit breaker.** Proxy-based. [DOC] ([blog](https://spring.io/blog/2025/09/09/core-spring-resilience-features))
- **Lessons:** the platform now owns retry and concurrency limiting, so a new library must offer more than that (circuit breaking, rate limiting, timeouts, composition, config, performance). Its `maxAttempts` counts *retries*, not total attempts, unlike Spring Retry; we must define this unambiguously. [DOC]

### 2.6 Polly v8 (.NET), for design ideas

- **Status:** 8.8.0 (Sep 2026), active; part of `Microsoft.Extensions.Resilience`. [DOC] ([repo](https://github.com/App-vNext/Polly))
- **Design:** `ResiliencePipelineBuilder` composes strategies (retry, circuit breaker, timeout, rate limiter, fallback, **hedging**, and **chaos** strategies); `ResiliencePipelineRegistry` with keyed pipelines and dynamic reload; telemetry built in. [DOC]
- **Performance (copy):** v8 was a rewrite focused on allocation. For a 5-strategy pipeline (timeout+rate limiter+retry+CB+timeout): **v7 2.277 µs / 2824 B → v8 2.089 µs / 40 B**. Techniques: `ResilienceContext` pooling, **state-passing static lambdas** (no capture), `Outcome<T>` carrying an exception **without throwing**, switch-expression predicates instead of predicate lists, cached pipelines. [BENCH][DOC] ([performance](https://www.pollydocs.org/advanced/performance.html))
- **Lessons:** proof that a ~99% allocation reduction is achievable for a full pipeline. The per-strategy "result/outcome" type avoids exception-driven control flow.

### 2.7 Newer / other (2024–2026)

| Library | Notes |
|---|---|
| **Helidon 4 Fault Tolerance** | SE programmatic API (`Async`, `Bulkhead`, `CircuitBreaker`, `Fallback`, `Retry`, `Timeout`) built for virtual threads; MP annotations; **Helidon Declarative** (incubating) `@Ft.Retry`, `@Ft.CircuitBreaker`, ... processed by an **annotation processor that generates code at build time, "to avoid reflection and bytecode manipulation at runtime"**. [DOC] ([declarative](https://helidon.io/docs/v4/se/injection/declarative.html), [SE FT](https://helidon.io/docs/v4/se/fault-tolerance.html)) This is the closest prior art to our goal, though it is tied to Helidon Inject. |
| **Micronaut** | `@Retryable`, `@CircuitBreaker` (a variant of retry), and the Resilience4j-Micronaut interceptors; **compile-time AOP** (no runtime proxies/reflection). [DOC] ([javadoc](https://docs.micronaut.io/5.0.1/api/io/micronaut/retry/annotation/package-summary.html)) Tied to the Micronaut DI container. |
| **Netflix concurrency-limits** | Adaptive concurrency limiting (TCP-congestion-style). A strong differentiator idea for a bulkhead "adaptive" mode. [DOC] |
| **Spring Framework 7** | See §2.5. |
| **Resilience4j 2.4 / Hedge** | Catching up on virtual threads (JDK 21 baseline, `ReentrantLock` instead of `synchronized`), lock-free windows opt-in, hedging. [DOC] |

---

## 3. Feature comparison matrix

Legend: ✅ yes · ⚠️ partial / with caveats · ❌ no · — not applicable.

| Capability | Resilience4j 2.4 | Failsafe 3.3 | SmallRye FT 6.x | Hystrix 1.5 | Sentinel 1.8 | Spring FW 7 | Polly 8 (.NET) | Helidon 4 FT |
|---|---|---|---|---|---|---|---|---|
| Circuit breaker | ✅ count+time, slow-call | ✅ count+time | ✅ count (rolling) | ✅ time buckets | ✅ slow/err ratio/count | ❌ | ✅ | ✅ |
| Retry + backoff/jitter | ✅ | ✅ | ✅ (+Fibonacci, custom) | ❌ | ❌ | ✅ | ✅ | ✅ |
| Rate limiter | ✅ | ✅ smooth/bursty | ✅ `@RateLimit` | ❌ | ✅ QPS, warm-up, hot-param | ❌ | ✅ | ❌ |
| Bulkhead semaphore | ✅ | ✅ | ✅ | ✅ | ✅ (thread count) | ✅ `@ConcurrencyLimit` | ✅ (via RateLimiter concurrency) | ✅ |
| Bulkhead thread pool | ✅ | ❌ | ✅ (`@Asynchronous`) | ✅ (default) | ❌ | ❌ | — | ⚠️ |
| Timeout | ⚠️ needs Future/CS | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Fallback | ⚠️ decorator / string method name | ✅ | ✅ handler or method | ✅ | ✅ | ❌ | ✅ | ✅ |
| Hedging | ✅ (new) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Adaptive limits | ❌ (#201 open since 2018) | ❌ | ❌ | ❌ | ⚠️ system protection | ❌ | ❌ | ❌ |
| Annotations | ✅ declared, ❌ processed standalone | ❌ | ✅ (CDI only) | ⚠️ javanica + AspectJ | ✅ (AspectJ/CDI) | ✅ (Spring proxies) | — | ✅ (MP/CDI, or Helidon Declarative) |
| **Annotations in plain Java** | ❌ | ❌ | ❌ | ❌ | ⚠️ manual AspectJ | ❌ | — | ❌ |
| Build-time (no reflection) annotation processing | ⚠️ only via Micronaut | — | ⚠️ Quarkus build-time | ❌ | ❌ | ❌ | — | ✅ (incubating) |
| Named configs + default | ✅ | ❌ (code) | ✅ via `@ApplyGuard` | ✅ (Archaius) | ✅ rules | ❌ | ✅ registry | ✅ |
| Per-method override via external config | ⚠️ by instance name | ❌ | ✅ class/method/annotation/param | ✅ | ✅ (rule sources) | ⚠️ placeholders | ✅ (options reload) | ✅ |
| Dynamic reconfiguration at runtime | ⚠️ partial (RateLimiter limits; registry replace) | ❌ | ⚠️ | ✅ | ✅ | ❌ | ✅ | ⚠️ |
| Event listeners | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ telemetry | ✅ |
| Metrics (Micrometer etc.) | ✅ | ❌ | ✅ (MP Metrics/Micrometer/OTel) | ✅ | ✅ own | ⚠️ | ✅ (.NET metrics) | ✅ |
| Async (`CompletionStage`) | ✅ | ✅ | ✅ | ⚠️ Rx | ⚠️ | ✅ (reactive) | ✅ (Task/ValueTask) | ✅ |
| Reactor / RxJava / Kotlin | ✅ / ✅ / ✅ | ❌ | Mutiny, Rx, Kotlin | Rx | ⚠️ Reactor adapter | Reactor | — | ❌ |
| Virtual-thread friendly | ✅ since 2.3/2.4 | ⚠️ `synchronized` (pins < JDK 24) | ✅ | ❌ | ⚠️ | ✅ | — | ✅ (designed for VT) |
| Zero-allocation hot path | ❌ (272 B/op historical CB) | ❌ | ? | ❌ | ⚠️ | ? | ✅ near (40 B / 5 strategies) | ? |
| Published JMH numbers | ⚠️ old file in repo | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Maintained (2026) | ✅ | ⚠️ maintenance | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |

"?" = not investigated or unknown; to be measured.

---

## 4. Opportunities: where a new library can beat Resilience4j

### 4.1 Performance

1. **Zero-allocation steady-state hot path** (no events consumed, success path). Concretely: no `Snapshot` return values (expose metrics by pull, not per record), primitive `long` timestamps (`System.nanoTime()` directly, a `LongSupplier`-like interface for tests rather than `Function<Clock, Long>`), no per-call context objects for retry when the first attempt succeeds (lazy context), and no `Instant` in OPEN checks (store `long` deadline nanos). Evidence that it is possible: R4j #117 prototype, Polly v8 40 B/5 strategies. [SRC][BENCH] **Target to propose to the perf agent: 0 B/op for CB+Retry+Bulkhead success path, verified with JMH `-prof gc`.**
2. **Contention-friendly metrics by default**: striped/`LongAdder`-style or per-bucket CAS counters (Sentinel `LeapArray` style), instead of a single lock (R4j default) or a CAS-on-one-tail list that allocates per record (R4j LOCK_FREE). [SRC]
3. **Events that cost nothing when nobody listens, and little when someone does**: check before allocating on *all* paths (R4j skips the check for several event types, including call-not-permitted, and each event captures `ZonedDateTime.now()`), and ideally avoid event objects for metrics consumers entirely (counters, not event streams). Don't attach buffers to every instance by default (R4j's Spring Boot integration does). [SRC]
4. **Resolve everything per method once**: names, configs, fallback targets and return-type adapters bound at build time or first call into a final per-method handle, so the per-call work is only permit, invoke and record. Compare R4j's per-call SpEL/regex/reflection (§1.3). [SRC]
5. **Fallback without reflection**: generated direct calls (or `MethodHandle`s bound once) instead of `Method.invoke`, and with a type-checked signature at compile time (R4j only logs "No fallback method match found" at runtime). [SRC]
6. **Monomorphic, fused pipelines**: fuse the common stacks (e.g. Retry(CB(Bulkhead(call)))) into one generated/specialised method rather than nested megamorphic `Supplier` lambdas. [INF], to be confirmed with JMH and JIT logs.
7. **Rate limiter under contention**: avoid allocating a `State` per CAS attempt (pack cycle+permits into a `long`, or use a per-cycle `AtomicLong`). [SRC]/[INF]
8. **Exceptions as values on the rejection path**: preallocated, stackless rejection exceptions (`CallNotPermitted`, bulkhead full), or an `Outcome`-style API for users who want no throw at all (Polly idea). [DOC]/[INF]
9. **No `synchronized` anywhere on hot paths**: Java 21 is our minimum and JEP 491 pinning fixes only arrive in JDK 24. Failsafe is the cautionary example. [SRC][DOC]
10. **Publish a reproducible JMH suite comparing R4j, Failsafe, SmallRye standalone and ours** (1/4/16/64 threads, platform and virtual threads, `-prof gc`). None exists publicly. [DOC, negative result]

### 4.2 Ergonomics

1. **Declarative annotations that work in plain Java**, with no Spring, CDI or AspectJ runtime. Nobody offers this today except Helidon Declarative (container-bound, incubating). This is our primary differentiator. (The mechanism belongs to the annotation-processing agent; build-time generation à la Helidon/Micronaut is the strongest prior art.) [DOC]
2. **MicroProfile-style layered config**: annotation values as defaults, then named profile (`@CircuitBreaker(config = "payments")`), then external override by `class/method/policy/param`, with a pluggable config source (properties, env, system props) and no framework dependency. [DOC]
3. **Named shared policies applied by annotation** (SmallRye `@ApplyGuard`) and **composed meta-annotations** (R4j #2405 is still open). [DOC][ISSUE]
4. **Compile-time validation**: unknown config names, wrong fallback signatures, incompatible policy combinations and invalid thresholds reported as compiler errors rather than runtime warnings. [INF]
5. **A defined, documented policy order** (MicroProfile has one; R4j aspect order is configured by Spring `@Order` properties). [DOC]
6. **Unambiguous semantics**: `maxAttempts` vs `maxRetries` (Spring 7 changed meaning), whether `Error`s are recorded, cause-chain inspection. [DOC]
7. **Honest timeouts for sync code** on virtual threads (R4j TimeLimiter needs a `Future`). [SRC]
8. **Optional adaptive concurrency limit** (Netflix concurrency-limits) as a bulkhead mode: a feature R4j has had requested since 2018 (#201). [ISSUE][DOC]
9. **Test ergonomics**: an injectable clock that is honoured everywhere (R4j LOCK_FREE ignores it). [SRC]

### 4.3 Risks / things not to over-promise

- Resilience4j has a huge ecosystem (Spring Boot starters, Micrometer, Feign, Reactor, Kotlin). We start with none of it; plain-Java-only is a narrower audience. [INF]
- Most real calls are I/O-bound (ms), so sub-µs savings matter mainly for high-QPS services, CPU-bound protection (R4j #1513 streaming case), allocation-sensitive/low-latency systems, and virtual-thread scale. The pitch should be "near-zero overhead, scales with threads" plus "annotations without a framework", not raw speed alone. [INF]
- JIT escape analysis may already remove some of the allocations we list ([INF] items). Measure before claiming.

---

## 5. Recommendations for the next phases

1. Adopt the **MicroProfile FT annotation semantics and config-override hierarchy** as the reference model (adapting names), and add SmallRye-style named policies and meta-annotation composition.
2. Use **build-time generated code** for annotation handling (Helidon/Micronaut prior art); defer the final decision to the annotation-mechanism research.
3. Make the **performance contract explicit and measured**: 0 B/op on success paths, no locks on hot paths, metrics via striped counters, events lazily allocated, and a published comparative JMH suite from day one.
4. Scope v1 to **CircuitBreaker, Retry, Timeout, Bulkhead (semaphore), RateLimiter, Fallback**; consider adaptive limit and hedging as v2 differentiators; skip Cache and thread-pool bulkhead (virtual threads make it less relevant).

## Sources

- Resilience4j: [repo](https://github.com/resilience4j/resilience4j), [release notes](https://github.com/resilience4j/resilience4j/blob/master/RELEASENOTES.adoc), [v2.4.0](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0), issues [#117](https://github.com/resilience4j/resilience4j/issues/117), [#201](https://github.com/resilience4j/resilience4j/issues/201), [#1377](https://github.com/resilience4j/resilience4j/issues/1377), [#1391](https://github.com/resilience4j/resilience4j/issues/1391), [#1513](https://github.com/resilience4j/resilience4j/issues/1513), [#2023](https://github.com/resilience4j/resilience4j/issues/2023), [#2151](https://github.com/resilience4j/resilience4j/issues/2151), [#2224](https://github.com/resilience4j/resilience4j/issues/2224), [#2405](https://github.com/resilience4j/resilience4j/issues/2405), PR [#2241](https://github.com/resilience4j/resilience4j/pull/2241), [JMH results file](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/jmh/resources/resultsWithGCProfiling.txt)
- Failsafe: [repo](https://github.com/failsafe-lib/failsafe), [InfoQ on 3.x](https://www.infoq.com/news/2022/02/failsafe-resilience-3)
- MicroProfile FT 4.1: [spec](https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html); SmallRye FT: [repo](https://github.com/smallrye/smallrye-fault-tolerance), [programmatic API](https://smallrye.io/docs/smallrye-fault-tolerance/6.9.3/reference/programmatic-api.html), [reusable / @ApplyGuard](https://smallrye.io/docs/smallrye-fault-tolerance/6.8.0/reference/reusable.html)
- Hystrix: [repo/README](https://github.com/Netflix/Hystrix); Netflix [concurrency-limits](https://github.com/Netflix/concurrency-limits)
- Sentinel: [repo](https://github.com/alibaba/Sentinel)
- Spring: [Spring Retry (archived)](https://github.com/spring-projects/spring-retry), [Spring Framework 7 resilience blog](https://spring.io/blog/2025/09/09/core-spring-resilience-features), [reference](https://docs.spring.io/spring-framework/reference/core/resilience.html)
- Polly: [repo](https://github.com/App-vNext/Polly), [performance](https://www.pollydocs.org/advanced/performance.html)
- Helidon: [Declarative](https://helidon.io/docs/v4/se/injection/declarative.html), [SE Fault Tolerance](https://helidon.io/docs/v4/se/fault-tolerance.html); Micronaut [retry annotations](https://docs.micronaut.io/5.0.1/api/io/micronaut/retry/annotation/package-summary.html)
- JDK: [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- Academic: [SBRC paper, Polly vs Resilience4j](https://sol.sbc.org.br/index.php/sbrc/article/download/21194/21019)
