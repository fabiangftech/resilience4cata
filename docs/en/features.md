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
    @Fallback(PaymentFallback.class)          // typed handler: FallbackHandler<Receipt>
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

### Loading external configuration (`config` module)

```java
ResilienceRegistry registry = ResilienceConfig.load()   // classpath resilience4cata.properties (if present), env, system properties
        .applyTo(ResilienceRegistry.builder())
        .build();
```

- Keys: `defaults.<policy>.<attribute>`, `configs.<name>.<policy>.<attribute>`, `configs.<name>.extends`, `profiles.<profile>.…`, `profiles.active`. Policies: `retry`, `circuitBreaker`, `rateLimiter`, `timeout`, `bulkhead`; attributes are the annotation's (the timeout is `timeout.value`). camelCase and kebab-case are both accepted; an optional `resilience4cata.` or `r4c.` prefix is allowed and is required for system properties.
- Config and profile names: lowercase letters and digits separated by single hyphens (`slow-external`).
- Environment variables: `R4C_` + the key in uppercase with every non-alphanumeric character as `_`: `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS=5`, `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE=10s`, `R4C_PROFILES_ACTIVE=test`. A variable must map to exactly one key.
- Sources, lowest to highest: classpath resources < files < environment < system properties. Profile keys win over non-profile keys whatever their source.
- Values: durations as in annotations (`250ms`, `2s`, `PT2S`), case-insensitive enums, exception classes by fully qualified name (comma-separated).
- Every problem is reported at once by `build()`, with the key, its source (file and line, environment variable, system property) and the closest valid key, e.g. `[R4C-CFG-001] unknown key 'configs.external.circuit-breaker.wait-in-opne' (file /app/resilience4cata.properties, line 7). Did you mean 'configs.external.circuit-breaker.wait-in-open'?`
- `ResilienceConfig.describe()` lists every effective value and where it comes from.
- A named config must be configured in one place: registering it both programmatically and externally fails. Per-instance overrides (`instances.<id>.*`) are not supported yet.

## Classifiers: results as failures

`@Retry` and `@CircuitBreaker` take a `classifier`: a class implementing `dev.resilience4cata.Classifier<T>` that decides, for each call, whether its result or exception is a `SUCCESS`, a `FAILURE` or something to `IGNORE`.

```java
public final class HttpStatusClassifier implements Classifier<HttpResponse<String>> {
    @Override
    public Outcome classify(@Nullable HttpResponse<String> response, @Nullable Throwable failure) {
        if (failure != null) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
        }
        int status = response.statusCode();
        return status >= 500 || status == 429 ? Outcome.FAILURE : Outcome.SUCCESS;
    }
}

public interface Api {
    @Retry(maxAttempts = 3, classifier = HttpStatusClassifier.class)
    @CircuitBreaker(classifier = HttpStatusClassifier.class)
    HttpResponse<String> get(URI uri) throws IOException;
}
```

- The processor checks the class at compile time: concrete and non-generic, a public no-argument constructor, and a `T` that the method's return type (or the `T` of its `CompletionStage<T>`) is assignable to. One instance is created per method; the retry and the breaker share it when they name the same class.
- The classifier replaces `retryOn` / `recordOn` (setting both is a compile error). `abortOn` / `ignoreOn` still apply first, so an open breaker still stops retries by default.
- **Retry:** only `FAILURE` is retried, whether it is a result or an exception. When the attempts run out on a `FAILURE` result, the last result is returned; `@Fallback` handles exceptions only, so it does not run. `maxDuration` applies as usual.
- **Circuit breaker:** `FAILURE` counts as a failure (and as slow if it was slow), `SUCCESS` as a success. `IGNORE` is not recorded, and a half-open probe gives its permit back.
- A classifier should not throw. If it does, its exception becomes the call's failure: the breaker records a failure, the retry stops, and `@Fallback` gets it.
- Asynchronous methods behave the same way.
- Cost: methods without a classifier generate exactly the same code as before. With one, a successful call makes one direct `classify` call per policy, with no allocation and no clock read.

## Programmatic API

For code that cannot be annotated (third-party classes, lambdas, scripts), build a `Pipeline` from the registry. It is immutable and thread-safe; build it once and reuse it.

```java
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
```

- Every policy is optional, configured from the defaults, a named config, overrides, or both. Policies nest in the same order as with annotations (Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead), whatever order the builder methods are called in.
- The id (`"payments"`) names the policy instances: `registry.circuitBreaker("payments")` returns the breaker. Pipelines with the same id and configuration share instances; the same id with a different configuration is an error.
- `execute` declares the call's checked exception. Once retries give up it throws the call's own failure or a rejection (`CallNotPermittedException`, `RateLimitExceededException`, `CallTimeoutException`, `BulkheadFullException`).
- `classifier(...)` (or `retryClassifier` / `circuitBreakerClassifier`) decides what is retried and recorded, results included, with the same rules as the annotation attribute.
- `fallback(Function<Throwable, T>)` and `fallbackHandler(FallbackHandler<T>)` run for the failures selected by `fallbackApplyOn` / `fallbackSkipOn`.
- `executeAsync` uses each policy's non-blocking async form.
- The synchronous success path allocates nothing beyond your own lambda. Still, one `Pipeline` class serves every call site, so its call to your lambda becomes megamorphic in an application with many pipelines: annotations remain the fastest option where they can be used.
- Limits: no `run(Runnable)` (return `null` from the lambda), and async fallbacks return a value, not a stage.

## Events and state inspection

Policies publish typed events. Listeners are registered on the registry builder, for an event record, a policy's event interface, or `ResilienceEvent` for everything, and optionally for one instance only:

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(CircuitBreakerEvent.StateTransition.class,
                t -> alerts.send(t.instance() + ": " + t.from() + " -> " + t.to()))
        .listen("payments", RetryEvent.class, e -> log.debug("{}", e))
        .listen(ResilienceEvent.class, e -> {
            switch (e) {
                case BulkheadEvent.Rejected r -> metrics.bulkheadRejected(r.instance());
                case FallbackEvent.Applied f -> log.warn("fallback for {}", f.instance(), f.failure());
                default -> { }
            }
        })
        .build();
```

| Event | When |
|---|---|
| `CircuitBreakerEvent.StateTransition(from, to)` | Every state change, published once by the thread that made it |
| `CircuitBreakerEvent.CallNotPermitted(state)` | A call rejected while open, forced open or out of half-open probes |
| `RetryEvent.Retrying(attempt, delay, failure)` | A failed attempt that will be retried |
| `RetryEvent.GaveUp(attempts, reason, failure)` | No more attempts: `MAX_ATTEMPTS`, `MAX_DURATION`, `NOT_RETRYABLE` or `INTERRUPTED` |
| `TimeoutEvent.TimedOut(timeout)` | A call ran past its timeout |
| `BulkheadEvent.Rejected` / `RateLimiterEvent.Rejected` | A call was rejected |
| `FallbackEvent.Applied(failure)` | The fallback runs for a failure |

Every event carries `instance()` (the `name`, or `<interface>#<method>(<parameter types>)`) and `nanoTime()`. Successful calls publish nothing; use the snapshot for that.

- **Zero cost when unused.** Listeners are fixed when the registry is built (there is no registration afterwards, like the rest of the configuration). Each policy looks up its listeners once, at creation, so an event type nobody listens to never allocates, locks or reads a clock.
- **Synchronous.** Listeners run on the thread that caused the event: the caller's thread for synchronous calls, a library virtual thread for asynchronous timeouts and deferred retries. Keep them fast and non-blocking; hand slow work to a queue.
- **Isolated.** A listener that throws does not affect the call or the other listeners: its exception goes to the thread's uncaught exception handler.
- Events published from different threads can arrive out of order; each transition arrives exactly once.
- Known gap: when a classifier itself throws, the retry gives up without publishing `GaveUp`.

`registry.snapshot()` returns read-only records of every instance created so far, for health checks and metrics scrapes. It may allocate and walk the windows, and adds nothing to calls:

```java
ResilienceSnapshot s = registry.snapshot();
for (CircuitBreakerSnapshot cb : s.circuitBreakers()) {
    System.out.printf("%s %s failureRate=%.1f%% calls=%d%n", cb.instance(), cb.state(), cb.failureRate(), cb.bufferedCalls());
}
s.bulkheads().forEach(b -> gauge(b.instance(), b.concurrentCalls(), b.waitingCalls(), b.maxConcurrent()));
s.rateLimiters().forEach(r -> gauge(r.instance(), r.availablePermits()));
```

Rates are percentages, or `-1` until the window holds `minimumCalls` calls (the slow-call rate is also `-1` when slow-call detection is off). Retries and timeouts show their resolved configuration. Single instances have `registry.circuitBreaker(id).snapshot()`, `bulkhead(id).snapshot()` and `rateLimiter(id).snapshot()`.

## Testing

Add `testImplementation("dev.resilience4cata:resilience4cata-testkit")`. A `ManualClock` replaces the system clock and the library's timers in one registry, so tests move time by hand instead of waiting:

```java
ManualClock clock = new ManualClock();
ResilienceRegistry registry = ResilienceTesting.registry(clock).build(); // also seeds jitter with 42
Inventory inventory = Resilient.of(Inventory.class, stub, registry);

inventory.fetch("a");                  // @Retry(delay = "1s"): 3 failures, then success; returns at once
assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(3));

clock.advance(Duration.ofSeconds(30)); // an open breaker with waitInOpen = 30s lets the next call probe
```

- Sleeps (retry backoff, rate limiter waits) advance the clock instead of blocking.
- Breaker waits, slow calls, rate limiter permits and retry `maxDuration` are measured on the clock.
- Async retry backoff, async rate limiter waits, async bulkhead wait limits and async timeouts run on the thread that calls `advance`, before it returns.
- Sync timeouts fire exactly when the clock passes the deadline (no slack). A stub that simulates a slow dependency with `clock.sleep(Duration.ofSeconds(5))` ends with `CallTimeoutException`.
- `ResilienceRegistry.builder().random(new Random(seed))` makes jitter reproducible without the testkit. The generator must be thread-safe (`Random` is, `SplittableRandom` is not).
- `clock.advanceUntilIdle()` runs every pending delayed task; `ResilienceTesting.elapseWaitInOpen(breaker, clock)` moves past a breaker's wait in open.
- The clock is an SPI (`dev.resilience4cata.Ticker`, set with `Builder.ticker(...)`), so other test clocks can plug in.
- Limits: a sync bulkhead with `maxWait` waits in real time. A sleep that times out still advances the clock by its full length. With several threads advancing the same clock, task order is not deterministic.

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

Current measurements are in [benchmarks](benchmarks.md). Most real calls are I/O-bound (milliseconds), so the pitch is "near-zero overhead that scales with threads, plus annotations without a framework", not raw speed alone.
