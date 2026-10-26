# Changelog

Notable changes in each release of resilience4cata, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0.0, a minor version may change the API.

## [0.4.0] - 2026-10-25 :id=0.4.0

### Added

- **`@Hedge`: hedged requests** for asynchronous methods and `Pipeline.executeAsync`. The same call is started again when it has produced no result for `delay` (default 1 s), or at once when it fails with a failure in `hedgeOn`; the first success wins and the other attempts are cancelled through every inner policy (the breaker records nothing, the bulkhead frees the slot). `maxAttempts` (2 to 10, default 2), `abortOn`, and a hedge budget on by default (`budgetPercent` 10, `budgetMinHedgesPerSecond` 1, `budgetWindow` 10 s). Takes Retry's place in the order, and cannot be combined with `@Retry`. New `HedgeConfig`, `HedgeCancelledException`, `HedgeEvent` (`Hedged`, `Won`, `Skipped`, `Failed`), `HedgeSnapshot` in `ResilienceSnapshot.hedges()`, `ResilienceRegistry.Builder.hedgeDefaults/hedge/hedgeOverlay/hedgeInstance`, `Pipeline.Builder.hedge(...)`, and config keys `defaults.hedge.*` (and `configs.…`, `instances.…`, environment variables). Only for idempotent calls. New errors [R4C-HDG-001](errors.md#r4c-hdg-001) (sync method), [R4C-HDG-002](errors.md#r4c-hdg-002) (with `@Retry`), [R4C-ATR-008](errors.md#r4c-atr-008) (`maxAttempts`), [R4C-RT-018](errors.md#r4c-rt-018) (`Pipeline.execute` with a hedge) and [R4C-RT-019](errors.md#r4c-rt-019) (retry and hedge in one pipeline); [R4C-ATR-007](errors.md#r4c-atr-007) now also covers the hedge budget. See [Hedge](policies/hedge.md).
- **`resilience4cata-spring`: Spring Boot 4 integration** (Spring Boot 4.1 or newer, one module, no AOP). Annotated classes are instantiated as their generated subclass in place of the class (bean type, name, qualifiers and constructor injection unchanged; calls on `this` intercepted); beans of annotated interfaces are wrapped with `Resilient.of` after every other post-processor; a `ResilienceRegistry` bean (`@ConditionalOnMissingBean`) is configured from the application's `resilience4cata.*` properties in Spring's precedence, with `[…]` instance ids and YAML lists, validated at startup with the file and line of every bad key; `ResilienceRegistryCustomizer`, `EventListener` and `Ticker` beans are applied; Micrometer metrics when `resilience4cata-micrometer` is present; a `resilience4cata` health indicator (UP with open breakers unless listed); a read-only `resilience4cata` actuator endpoint; IDE metadata; failure analyses; works with Spring AOT. New error area `R4C-SPR-001`…`004`; `R4C-SPR-004` stops the startup when a retried or hedged method of an annotated class is also `@Transactional` (every attempt would share one transaction), unless `resilience4cata.spring.allow-retry-inside-transaction=true`. See [Spring Boot](guides/spring.md).
- `resilience4cata-config`: `ResilienceConfig.Builder.precedenceByOrder()` (sources rank by the order they are added, for frameworks with their own precedence) and `properties(description, map, origins)` (where each key was set, for messages).
- **GraalVM native image without configuration.** The processor writes `META-INF/native-image/dev.resilience4cata.generated/<generated class>/reachability-metadata.json` for every generated class (one file per class, so the processor stays isolating), registering its public constructors for `Resilient.of` and `Resilient.create`, conditional on the annotated interface (or on `Resilient` for annotated classes). `core` ships a `native-image.properties` that initializes the holders of its background threads at run time. Needs GraalVM for JDK 23 or later; see [Native image](start/installation.md#native-image).
- Annotated classes: the generated constructors keep the parameter names of the class's constructors and copy the constructor and parameter annotations (retention `CLASS` or `RUNTIME`), so dependency injection containers see `@Inject`/`@Autowired`, `@Qualifier`, `@Named`, `@Value`… on `C$Resilient` too. `@ConstructorProperties` gets the registry's name first. The generated overrides repeat the annotations of the methods they override and of their parameters (except resilience4cata's own annotations and bundles), so annotations read on the invoked method only, such as Jakarta's `@Transactional` under Spring or interceptor bindings, are no longer lost. See [Constructors and dependency injection](guides/annotated-classes.md#constructor-injection).

### Changed

- `ResilienceEvent` permits a new sealed subtype, `HedgeEvent`: a `switch` over `ResilienceEvent` without a `default` branch no longer compiles until it handles `HedgeEvent` (or adds `default`). `ResilienceSnapshot` has a new component, `hedges()`; its five-list constructor is kept. From now on the hierarchy grows in minor versions: keep a `default` branch, or listen to specific record types ([Observability](guides/observability.md#listening)).
- The subclass generated for an annotated class is no longer `final` (`public class C$Resilient extends C`), so frameworks can proxy it (CGLIB for `@Transactional`, scoped proxies). Interface wrappers stay `final`.
- The generated constructors of annotated classes name their parameters after the class's constructor (`registry, warehouse` instead of `registry, p0`); a parameter named `registry` makes the registry parameter `r4c$registry`, and parameters named `java` or `dev` become `r4c$p<index>`. Calls are unaffected.

### Fixed

- **Cancelling an async call now cancels the work inside it.** Cancelling the future returned by a generated async method or by `Pipeline.executeAsync` (or completing it exceptionally with a `CancellationException`) used to stop at the outermost policy: the stage inside kept running, a bulkhead slot stayed taken until that stage ended, a retry could start another attempt, a fallback could run, and the circuit breaker could record the cancellation as a failure. The cancel now goes down through every layer to the method's stage: the retry cancels its backoff and starts no further attempt, the timeout cancels its deadline, the bulkhead frees its slot as soon as the stage ends (at once for a `CompletableFuture`) and a queued call leaves the queue, the breaker records nothing (a half-open permit is given back), the fallback does not run, and a call still waiting for a rate-limiter permit gives the permit back and never starts. No event is published for a cancel. A timeout that expires cancels the inner layers the same way, so a bulkhead inside a timeout frees its slot when the call times out. Each policy returns the same number of objects per call as before. See [Async methods](guides/async.md#cancellation).

## [0.3.0] - 2026-10-25 :id=0.3.0

### Added

- `resilience4cata-opentelemetry`: OpenTelemetry metrics. `OpenTelemetryMetrics.create(openTelemetry)`, `register(builder)` and `bindTo(registry)` report rejections, retries, give-ups, breaker transitions, timeouts and fallbacks as counters fed by events, and breaker, bulkhead and rate-limiter state as asynchronous gauges read from the snapshot at each collection, so instances created later are included. Same metric names as the Micrometer module. Metrics only, no tracing yet. See [OpenTelemetry](guides/opentelemetry.md).
- `resilience4cata-junit`: a JUnit Jupiter extension. `@ResilienceTest` injects a fresh `ManualClock` and a `ResilienceRegistry` that follows it into each test (and its `@BeforeEach`/`@AfterEach` methods), with `seed`, `startNanos` and an opt-in `failOnPendingTasks`; `@ConfigureRegistry` methods customize the builder. Needs JUnit 5.10 or newer, JUnit 6 included. See [Testing](guides/testing.md#junit).
- An async `@Timeout` call that finishes in time now takes its deadline off a `ManualClock` at once, so `pendingTasks()` no longer counts it until the deadline passes.
- **`resilience4cata-micrometer`**: [Micrometer metrics](guides/micrometer.md) in three lines (`MicrometerMetrics.create(meterRegistry)`, `register(builder)`, `bindTo(registry)`): counters from events (rejections, breaker transitions, retries, give-ups, timeouts, fallbacks) and gauges for every circuit breaker, bulkhead and rate limiter, including instances created after binding. Replaces the hand-written recipe of the observability guide.
- `ResilienceRegistry.onInstanceCreated(Runnable)`: runs an action once per new policy instance (when a service is wrapped or a pipeline is built, never during a call), so that metrics bridges can register meters for instances created later ([Observability](guides/observability.md#diy)).
- **Retry budget** for `@Retry`, `RetryConfig` and `Pipeline`: `budgetPercent`, `budgetMinRetriesPerSecond` and `budgetWindow` cap the retries of one retry instance, shared by all its callers, at a reserve per second plus a percentage of its calls in the window, so that an outage does not multiply the load on the failing dependency. `budgetPercent` turns it on, with a default reserve of 10 retries per second. Off by default; without it, generated code and the success path are unchanged. A refused retry publishes `RetryEvent.GaveUp` with the new reason `BUDGET_EXHAUSTED`; `RetrySnapshot.budget()` shows the window. Config keys `defaults.retry.budget…` (and `configs.…`, `instances.…`, environment variables); new processor error [R4C-ATR-007](errors.md#r4c-atr-007). [Retry budget](policies/retry.md#retry-budget)
- Error codes for the test modules: `R4C-TK-001`…`003` (`ManualClock`, `ResilienceTesting`) and `R4C-JU-001`…`003` (the JUnit extension), each documented in the [error reference](errors.md#tests-testkit-r4c-tk) with its fix.
- The Micrometer and OpenTelemetry modules give every metric the same description (Prometheus `# HELP` text), checked by a test.

## [0.2.0] - 2026-10-25 :id=0.2.0

Fixes three behaviour bugs of 0.1.0; one of the fixes changes a default (see Upgrading).

### Upgrading from 0.1.0

- Keep `core` and `processor` on the same version (import the BOM). Code generated by the 0.2.0 processor needs the 0.2.0 `core`; code generated by 0.1.0 still links against the 0.2.0 `core`, which only adds API.
- `abortOn` no longer contains `CallNotPermittedException` by default, because an open circuit breaker now stops the retries on its own. Remove `CallNotPermittedException` from your own `abortOn` lists if you added it only for that; it does no harm if you keep it.
- A call rejected by an open breaker can no longer be retried by any configuration, classifier included.

### Changed

- **An open circuit breaker always stops the retries.** A call rejected with `CallNotPermittedException` is never retried, whatever `abortOn`, `retryOn` or a classifier say, in generated code (sync and async) and in `Pipeline`. In 0.1.0 this came from the default `abortOn`, so setting your own `abortOn` silently dropped it and the retry kept calling an open breaker. `RetryConfig.DEFAULTS.abortOn()` (and `defaults.retry.abortOn`) is now empty; [Retry](policies/retry.md#attributes).

### Fixed

- `FallbackEvent.Applied` is no longer published for a failure that no sibling fallback method takes (for example an `Error` when the only sibling takes `Exception`): such a failure is rethrown, and the event now means that a fallback really ran.
- Error messages R4C-ATR-001 and R4C-CFG-002 state the duration grammar once instead of twice, and R4C-CFG-007 ends its sentence before "Known configs".
- The processor's module descriptor declares `AnnotationClaimer` too, so `-Xlint:processing` stays quiet when the processor runs from the processor module path next to other processors.
- A classifier that throws inside `@CircuitBreaker` (or a `Pipeline`'s breaker) now ends the call at once, sync and async: the retry around it no longer classifies the classifier's exception as the method's failure and retries it (which could open the breaker and surface `CallNotPermittedException` instead). The breaker records one failure, the retry publishes `GaveUp(CLASSIFIER_FAILED)` once, and the fallback or the caller gets the classifier's exception. See [A classifier that throws](guides/classifiers.md#a-classifier-that-throws).

## [0.1.0] - 2026-10-25 :id=0.1.0

First release. Artifacts on Maven Central under the groupId `dev.resilience4cata`: `resilience4cata-annotations`, `-core`, `-processor`, `-config`, `-testkit` and `-bom`. Requires Java 21 or later; tested on Java 21 and 25.

### Added

- **Six policies as annotations**, applied in a fixed order (Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → method): [`@Retry`](policies/retry.md) (fixed or exponential backoff, jitter, time budget), [`@CircuitBreaker`](policies/circuit-breaker.md) (count or time window, slow calls, half-open probes), [`@RateLimiter`](policies/rate-limiter.md) (GCRA, optional wait), [`@Timeout`](policies/timeout.md) (on the caller's thread, interrupt-based), [`@Bulkhead`](policies/bulkhead.md) (semaphore, optional wait) and [`@Fallback`](policies/fallback.md) (sibling method or handler class).
- **Annotation processor** that generates a plain Java class per annotated type: `<Interface>$Resilient` wrapping an implementation (`Resilient.of`), or `<Class>$Resilient` subclassing an [annotated class](guides/annotated-classes.md) (`Resilient.create`). Annotations and their values are checked at compile time; isolating incremental processing in Gradle; works with `-Xlint:all -Werror`.
- **Async methods** returning `CompletionStage<T>` or `CompletableFuture<T>` (also `? extends T`), with non-blocking forms of every policy ([Async methods](guides/async.md)).
- **[Composed annotations](guides/bundles.md)** with `@ResiliencePolicy`, and type-level annotations.
- **[Classifiers](guides/classifiers.md)** that decide from a result or an exception whether a call failed, and `@Retry(onExhaustedResult = THROW)`.
- **[`Pipeline`](guides/pipeline.md)**, a programmatic API with the same policies and order for code you cannot annotate.
- **`ResilienceRegistry`**: defaults, named configs, per-instance overrides, instances shared by `name`, handles for circuit breakers (`forceOpen`, `disable`, `reset`), rate limiters and bulkheads.
- **[Events and snapshots](guides/observability.md)**: listeners registered on the registry builder, read-only snapshots of every instance.
- **`resilience4cata-config`**: [external configuration](guides/configuration.md) from properties files, classpath resources, environment variables and system properties, with profiles, `extends`, per-instance overrides, strict validation and `verifyInstances` ([keys](reference/configuration.md)).
- **`resilience4cata-testkit`**: [`ManualClock` and `ResilienceTesting`](guides/testing.md) for deterministic tests of time-based behaviour.
- **`resilience4cata-bom`** to align the versions of every module.
- **[Error codes](errors.md)** (`R4C-…`) on every compile-time and runtime error, each documented with a fix.
- **Allocation gate** in CI: every sync success and rejection path allocates 0 bytes ([Performance](performance.md)).

### Known limitations

- No Spring, CDI, Micrometer or OpenTelemetry integration modules; no YAML configuration.
- The policy order cannot be changed, and configuration cannot be changed after the registry is built.
- In the interface style, calls an implementation makes to itself are not intercepted.
- Only `CompletionStage<T>` and `CompletableFuture<T>` are supported as async types (no `? super T`, no reactive types).

[0.1.0]: https://github.com/fabiangftech/resilience4cata/releases/tag/v0.1.0
