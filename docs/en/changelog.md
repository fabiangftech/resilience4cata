# Changelog

Notable changes in each release of resilience4cata, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0.0, a minor version may change the API.

## [Unreleased] :id=unreleased

### Changed

- **An open circuit breaker always stops the retries.** A call rejected with `CallNotPermittedException` is never retried, whatever `abortOn`, `retryOn` or a classifier say, in generated code (sync and async) and in `Pipeline`. In 0.1.0 this came from the default `abortOn`, so setting your own `abortOn` silently dropped it and the retry kept calling an open breaker. `RetryConfig.DEFAULTS.abortOn()` (and `defaults.retry.abortOn`) is now empty; [Retry](policies/retry.md#attributes).

### Fixed

- `FallbackEvent.Applied` is no longer published for a failure that no sibling fallback method takes (for example an `Error` when the only sibling takes `Exception`): such a failure is rethrown, and the event now means that a fallback really ran.
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
