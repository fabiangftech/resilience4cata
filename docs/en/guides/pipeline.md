# Pipeline API

`Pipeline` applies the same policies without annotations, for code you cannot annotate or policies you choose at runtime.

## When to use it

| Use annotations when | Use a `Pipeline` when |
|---|---|
| You own the interface or the class | The code is a third-party class, a lambda, or a script |
| The policies are known at compile time | The policies are chosen at runtime |
| You want compile-time checks of every attribute and fallback | Runtime checks when `build()` runs are enough |
| Every nanosecond counts | A few nanoseconds more per call are fine |

## A first pipeline

Build a pipeline once, from the registry, and reuse it. It is immutable and thread-safe.

<!-- snippet: pipeline-minimal -->
```java
Pipeline<String> geocode = Pipeline.builder(registry, "geocoder", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(1)))
        .fallback(failure -> "unknown")
        .build();

String city = geocode.execute(() -> legacyGeocoder.city("10115"));   // "Berlin", after one retry
```

`execute` runs the call through the policies and declares the call's checked exception (here `IOException`), so you handle what the call itself throws. When nothing recovers, it throws the call's last failure or a rejection: `CallNotPermittedException`, `RateLimitExceededException`, `CallTimeoutException` or `BulkheadFullException`.

## Every step

<!-- snippet: pipeline-full -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retry("external", r -> r.maxAttempts(4))
        .rateLimiter("external", rl -> rl.permits(100))
        .build();

Pipeline<Receipt> charge = Pipeline.builder(registry, "payments", Receipt.class)
        .retry("external")                                   // named config
        .circuitBreaker(cb -> cb.failureRateThreshold(25))   // defaults + overrides
        .rateLimiter("external", rl -> rl.permits(50))       // named config + overrides
        .timeout(t -> t.timeout(Duration.ofSeconds(2)))
        .bulkhead()                                          // defaults
        .fallbackHandler(context -> Receipt.pending(context.attempts()))
        .build();

Receipt receipt = charge.execute(() -> client.charge(order));            // throws what charge throws
CompletableFuture<Receipt> later = charge.executeAsync(() -> client.chargeAsync(order));
charge.run(() -> client.cancel(order));                                  // a call with no result
```

- **Policies:** `retry`, `hedge`, `circuitBreaker`, `rateLimiter`, `timeout` and `bulkhead`, each at most once ([`R4C-RT-012`](../errors.md#r4c-rt-012)). Each takes no argument (the defaults), a named config, a `Consumer` of the policy's config builder (overrides on top of the defaults), or both (overrides on top of the named config). The builders have the same attributes as the annotations; for example the timeout's duration is `t.timeout(Duration)`.
- **Order:** the policies nest as with annotations, `Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → Timeout → Bulkhead → call`, whatever order you call the builder methods in. Each retry attempt goes through the breaker and gets its own timeout.
- **Hedge:** `hedge(h -> h.delay(Duration.ofMillis(50)))` starts the call again while the first attempt is slow and keeps the first success ([Hedge](../policies/hedge.md)); only for idempotent calls, and only with `executeAsync`: `execute` and `run` on a pipeline with a hedge throw [`R4C-RT-018`](../errors.md#r4c-rt-018). A pipeline cannot have both a retry and a hedge ([`R4C-RT-019`](../errors.md#r4c-rt-019)).
- **Fallback:** `fallback(failure -> value)` or `fallbackHandler(context -> value)`; the handler's `FallbackContext` gives the failure and `attempts()` (it has no arguments: `argumentCount()` is 0). `fallbackApplyOn(List.of(...))` and `fallbackSkipOn(List.of(...))` choose the failures it handles; without a fallback they are an error ([`R4C-RT-013`](../errors.md#r4c-rt-013)).
- **Async fallback:** `fallbackAsync(...)` and `fallbackAsyncHandler(...)` return a stage, for `executeAsync`. With both kinds, each mode uses its own; with only an async fallback, `execute` and `run` wait for its stage.
- **Classifiers:** `classifier(...)` for both the retry and the breaker, or `retryClassifier(...)` and `circuitBreakerClassifier(...)` separately, plus `onExhaustedResult(Pipeline.ExhaustedResult.THROW)`; see [classifiers](classifiers.md).

Three ways to call:

| Method | For |
|---|---|
| `T execute(PipelineCall<? extends T, X>) throws X` | A call that returns a value |
| `void run(PipelineRunnable<X>) throws X` | A call with no result (a classifier sees `null`; a fallback's result is discarded) |
| `CompletableFuture<T> executeAsync(PipelineCall<? extends CompletionStage<T>, ?>)` | A call that returns a stage: the non-blocking form of every policy, as for [async methods](async.md) |

`build()` checks combinations that cannot work: a classifier without a retry or breaker ([`R4C-RT-014`](../errors.md#r4c-rt-014)), a retry classifier without a retry ([`R4C-RT-015`](../errors.md#r4c-rt-015)), `onExhaustedResult` without a retry classifier ([`R4C-RT-016`](../errors.md#r4c-rt-016)), a breaker classifier without a breaker ([`R4C-RT-017`](../errors.md#r4c-rt-017)), a retry and a hedge together ([`R4C-RT-019`](../errors.md#r4c-rt-019)). A blank id is [`R4C-RT-011`](../errors.md#r4c-rt-011).

## The id is the instance id

The id passed to `Pipeline.builder` names the pipeline's policy instances, as the `name` attribute does for annotations:

<!-- snippet: pipeline-handle -->
```java
Pipeline<String> search = Pipeline.builder(registry, "search", String.class)
        .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2))
        .build();

CircuitBreakerHandle breaker = registry.circuitBreaker("search");   // the pipeline's id is the instance id
breaker.forceOpen();                                                // every call is now rejected
```

So `registry.circuitBreaker(id)`, `rateLimiter(id)` and `bulkhead(id)` find them, events carry the id as `instance()`, and external configuration retunes them with `instances.<id>.*`. Pipelines built with the same id share their instances, and so do annotations whose `name` is that id; they must all resolve to the same configuration, or `build()` fails with [`R4C-REG-008`](../errors.md#r4c-reg-008).

A fallback whose function throws a checked exception that the call does not declare reaches the caller wrapped in `UndeclaredThrowableException`.

## What it costs

The synchronous success path allocates nothing beyond your own lambda. One `Pipeline` class serves every call site, though, so in an application with many pipelines its single call to your lambda becomes megamorphic and slower; generated code has one call site per method and does not have this cost.

> [!MEASURED]
> Retry plus circuit breaker on one thread: 2.9 ns with a `Pipeline`, 2.3 ns with annotations, 175 ns and 288 B with Resilience4j 2.4.0. After five other pipelines have run through the same JVM, the pipeline measures 7.9 ns (AWS Graviton3 `c7g.xlarge`). [Benchmarks](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)

Where you can annotate, annotations stay the fastest option.
