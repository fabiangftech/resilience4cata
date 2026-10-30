# Migrate from Resilience4j

How Resilience4j's registries, configs, decorators and Spring annotations map to resilience4cata, what has no equivalent, and what behaves differently.

## Should you migrate? :id=when

resilience4cata covers eight policies (retry, hedge, circuit breaker, rate limiter, adaptive limit, timeout, bulkhead, fallback) for plain Java 21+, with code generated at compile time. **Stay on Resilience4j** if you need any of these, which resilience4cata does not have yet:

- Spring Boot 3, CDI or AspectJ integration (resilience4cata integrates with Spring Boot 4.1+ only, [below](#spring-boot));
- RxJava or Kotlin coroutine operators (resilience4cata supports `CompletionStage`, `CompletableFuture` and, with `resilience4cata-reactor`, Reactor's `Mono` and `Flux`: [below](#reactor));
- a thread-pool bulkhead, a cache, or a time limiter for arbitrary futures;
- changing a policy's configuration at runtime (resilience4cata resolves configuration once, by design);
- a Java version before 21.

**Consider migrating** if per-call overhead and allocation matter to you (see [Performance](../performance.md)), if you want misconfiguration caught at compile time, or if you prefer plain annotations and generated code you can read over AOP proxies.

## The mental model :id=mental-model

| | Resilience4j | resilience4cata |
|---|---|---|
| Where the policies live | decorators (`Decorators.ofSupplier(…)`, `Retry.decorateSupplier(…)`) or Spring AOP aspects around a bean | a class generated at compile time: `<Interface>$Resilient` wraps your implementation, or `<Class>$Resilient` subclasses your class |
| When misconfiguration is found | at startup or at the first call | most of it when you compile ([error codes](../errors.md)) |
| Order of policies | the order you compose decorators; with Spring, a fixed aspect order (configurable) | by default Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method; `@PolicyOrder` changes it per method, type or bundle |
| Registries | one per policy (`CircuitBreakerRegistry`, `RetryRegistry`, …) | one `ResilienceRegistry` for every policy |
| Instances | looked up by name | one per annotated method by default; methods with the same `name` share one |

## Concept mapping :id=concepts

| Resilience4j | resilience4cata |
|---|---|
| `CircuitBreakerRegistry.of(config)`, `RetryRegistry.of(…)`, … | `ResilienceRegistry.builder()…build()` |
| `CircuitBreakerConfig.custom()…build()` registered as a named config | `registryBuilder.circuitBreaker("name", b -> b…)`, used with `@CircuitBreaker(config = "name")`; or `configs.<name>.circuitBreaker.*` in properties |
| default config (`configs.default` in Spring) | `registryBuilder.circuitBreakerDefaults(b -> …)` or `defaults.circuitBreaker.*` |
| `registry.circuitBreaker("inventory")` + `decorateSupplier` | `@CircuitBreaker(name = "inventory")` on the interface method + `Resilient.of(…)` |
| `Decorators.ofSupplier(…).withRetry(…).withCircuitBreaker(…)` for code you cannot annotate | [`Pipeline.builder(registry, id).retry().circuitBreaker()…build()`](../guides/pipeline.md) |
| Spring `@CircuitBreaker(name = "x", fallbackMethod = "f")` | `@CircuitBreaker(name = "x")` + `@Fallback(method = "f")` |
| Spring `@Retry`, `@RateLimiter`, `@Bulkhead`, `@TimeLimiter` | `@Retry`, `@RateLimiter`, `@Bulkhead`, `@Timeout` (package `dev.resilience4cata.annotation`) |
| `resilience4j.circuitbreaker.instances.x.*` | `instances.x.circuitBreaker.*` ([keys](../reference/configuration.md)) |
| `baseConfig: other` | `configs.<name>.extends=other` |
| `circuitBreaker.getEventPublisher().onStateTransition(…)` | `registryBuilder.listen(CircuitBreakerEvent.StateTransition.class, …)` ([events](../guides/observability.md#events)) |
| `circuitBreaker.getMetrics()` | `registry.circuitBreaker(id).snapshot()` |
| `transitionToForcedOpenState()`, `transitionToDisabledState()`, `reset()` | `forceOpen()`, `disable()`, `reset()` on `CircuitBreakerHandle` |
| `resilience4j-micrometer` (`TaggedCircuitBreakerMetrics`, …) | `resilience4cata-micrometer` ([Micrometer](../guides/micrometer.md)); the meter names differ (`r4c.*`), so dashboards and alerts need new queries |
| `RetryConfig.retryOnResult(…)`, `CircuitBreakerConfig.recordResult(…)` | a [`Classifier`](../guides/classifiers.md) on `@Retry` / `@CircuitBreaker` |
| `failAfterMaxAttempts(true)` (`MaxRetriesExceededException`) | `@Retry(onExhaustedResult = THROW)` (`ResultRejectedException`) |
| `IntervalFunction.ofExponentialBackoff(…)`, `ofExponentialRandomBackoff(…)` | `backoff`, `multiplier`, `maxDelay`, `jitter` |
| `TimeLimiter` + an executor for blocking code | `@Timeout` on the caller's thread |
| `resilience4j-hedge`: `Hedge.of(config).decorateCompletionStage(…)` | [`@Hedge`](../policies/hedge.md) on a method that returns a `CompletableFuture`, or `Pipeline…hedge(…)` with `executeAsync` ([mapping](#map-hedge)) |
| `mono.transformDeferred(RetryOperator.of(retry))` and the other Reactor operators, on a `Mono` | a `Mono` method with the annotations, or `mono.transform(PipelineOperator.of(pipeline))` ([below](#reactor)) |
| the same operators on a `Flux` | a `Flux` method with the annotations, or `flux.transform(FluxPipelineOperator.of(pipeline))` ([below](#reactor)) |
| `ThreadPoolBulkhead`, `Cache`, RxJava/Kotlin operators | no equivalent |

## Configuration mapping :id=config-mapping

Resilience4j builder methods (Spring Boot properties use the same names in kebab-case) and the resilience4cata attribute with the same role. Defaults differ in places; the **Default** columns are each library's own.

### Circuit breaker :id=map-circuit-breaker

| Resilience4j | Default | resilience4cata | Default |
|---|---|---|---|
| `failureRateThreshold` | 50 | `failureRateThreshold` | 50 |
| `slowCallRateThreshold` | 100 | `slowCallRateThreshold` | 100 |
| `slowCallDurationThreshold` | 60 s (calls always timed) | `slowCallDuration` | 0 = off (calls not timed) |
| `slidingWindowType` | `COUNT_BASED` | `windowType` | `COUNT` |
| `slidingWindowSize` | 100 | `windowSize` | 100 |
| `minimumNumberOfCalls` | 100 | `minimumCalls` | 100 |
| `waitDurationInOpenState` | 60 s | `waitInOpen` | 60 s |
| `permittedNumberOfCallsInHalfOpenState` | 10 | `halfOpenCalls` | 10 |
| `maxWaitDurationInHalfOpenState` | 0 | `maxWaitInHalfOpen` | 0 |
| `recordExceptions` / `recordException(predicate)` | all exceptions | `recordOn` (classes) or `classifier` | `Exception` |
| `ignoreExceptions` | none | `ignoreOn` | none |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `false` | no equivalent: half-open on the next call | — |
| `waitIntervalFunctionInOpenState`, `transitionOnResult`, `initialState`, `slidingWindowSynchronizationStrategy` | | no equivalent | — |
| `writableStackTraceEnabled` | `true` | not needed: rejections are always preallocated and stackless | — |

### Retry :id=map-retry

| Resilience4j | Default | resilience4cata | Default |
|---|---|---|---|
| `maxAttempts` | 3 | `maxAttempts` | 3 |
| `waitDuration` | 500 ms, fixed | `delay` (+ `backoff = FIXED, jitter = NONE` for the same behaviour) | 200 ms, exponential, full jitter |
| `intervalFunction(IntervalFunction.ofExponentialBackoff(…))` | | `backoff = EXPONENTIAL`, `multiplier`, `maxDelay` | ×2, max 10 s |
| `IntervalFunction.ofRandomized(…)`, `ofExponentialRandomBackoff(…)` | | `jitter = FULL` or `EQUAL` | `FULL` |
| `retryExceptions` / `retryOnException(predicate)` | all exceptions | `retryOn` or `classifier` | `Exception` |
| `ignoreExceptions` | none | `abortOn` | — (an open breaker is never retried anyway) |
| `retryOnResult(predicate)` | | `classifier` | — |
| `failAfterMaxAttempts` | `false` | `onExhaustedResult = THROW` | `RETURN` |
| no equivalent | | `maxDuration` (time budget for all attempts) | 0 = unlimited |

### Rate limiter, timeout, bulkhead :id=map-others

| Resilience4j | Default | resilience4cata | Default |
|---|---|---|---|
| `RateLimiterConfig.limitForPeriod` | 50 | `@RateLimiter(permits)` | 50 |
| `RateLimiterConfig.limitRefreshPeriod` | 500 ns | `@RateLimiter(period)` | 1 s |
| `RateLimiterConfig.timeoutDuration` | 5 s | `@RateLimiter(maxWait)` | 0 = reject at once |
| `RateLimiterConfig.drainPermissionsOnResult` | | no equivalent | — |
| `TimeLimiterConfig.timeoutDuration` | 1 s | `@Timeout(value)` | 1 s |
| `TimeLimiterConfig.cancelRunningFuture` | `true` | always: an async call's stage is cancelled if it is a `Future` | — |
| `BulkheadConfig.maxConcurrentCalls` | 25 | `@Bulkhead(maxConcurrent)` | 25 |
| `BulkheadConfig.maxWaitDuration` | 0 | `@Bulkhead(maxWait)` | 0 |
| `BulkheadConfig.fairCallHandlingStrategyEnabled` | `true` | no equivalent: the waiting semaphore is not fair | — |

> [!WARNING]
> Three defaults catch people out: resilience4cata's retry uses **exponential backoff with full jitter from 200 ms**, not a fixed 500 ms; its rate limiter **rejects at once** instead of waiting up to 5 s; and its breaker **does not time calls** unless you set `slowCallDuration`. Set them explicitly when you migrate.

### Hedge :id=map-hedge

Resilience4j's `resilience4j-hedge` module (2.x) is programmatic and async only, like ours (no Spring annotation), and starts at most one hedge.

| Resilience4j | resilience4cata | Notes |
|---|---|---|
| `HedgeConfig.Builder.preconfiguredDuration(d)` | `@Hedge(delay = "…")` | Ours defaults to 1 s; `"0"` starts every attempt at once |
| `averagePlusPercentageDuration(…)`, `averagePlusAmountDuration(…)` | no equivalent | Only a fixed delay; set it near the dependency's p95 |
| one hedge, always | `@Hedge(maxAttempts = 2)` (the default) | Up to 10 attempts |
| `withMaxConcurrency(n)` (core size of the hedge's thread pool) | no equivalent | Hedges start on new virtual threads; the [hedge budget](../policies/hedge.md#the-hedge-budget) caps their number instead |
| `withContextPropagators(…)` | no equivalent | Attempts 2+ run without the caller's `ThreadLocal`s |
| `submit(callable, executor)` for blocking code | no equivalent | Sync methods are a compile error ([R4C-HDG-001](../errors.md#r4c-hdg-001)): return a `CompletableFuture` |
| `HedgeOnPrimarySuccessEvent`, `…OnSecondarySuccessEvent`, `…Failure…` | `HedgeEvent.Hedged`, `Won`, `Skipped`, `Failed` | A first attempt that wins before the delay publishes nothing |

The semantics differ: in Resilience4j the first attempt to **complete** wins, so a fast failure of either attempt fails the call; in resilience4cata the first **success** wins, a failure in `hedgeOn` starts the next attempt at once, and the call fails only when every attempt has failed (or one fails with an `abortOn` failure). The loser is cancelled, and the circuit breaker, bulkhead and timeout inside the hedge release what it held.

## Behavioural differences :id=differences

### The order :id=order

Fallback → Retry (or Hedge) → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method by default, whatever the order of the annotations. It matches Resilience4j's default Spring aspect order for the policies both have. Where Resilience4j changes the order globally with `<module>AspectOrder` properties, resilience4cata changes it per method, type or bundle with [`@PolicyOrder`](../policies/README.md#changing-the-order), at compile time; the fallback stays outermost.

### An open breaker stops retries :id=open-breaker

A retry never retries `CallNotPermittedException`, whatever its `abortOn` says, so once the breaker opens, the retry gives up at once instead of retrying into it. In Resilience4j you add that exception to `ignoreExceptions` yourself.

### Errors are not recorded or retried by default :id=errors

`recordOn` and `retryOn` default to `Exception`: an `Error` (for example `OutOfMemoryError`, `AssertionError`) is not retried and counts as a **success** for the breaker. `@Fallback(applyOn)` defaults to `Throwable`, so a fallback does catch `Error`s unless you add them to `skipOn`.

### Timeouts interrupt the caller's thread :id=timeouts

A sync `@Timeout` does not move the call to another thread: it runs where you called it, and a shared watchdog interrupts it when time is up. Code that ignores interrupts runs to completion before the caller gets `CallTimeoutException`. Timeouts are never early and at most about two watchdog ticks late (1–50 ms ticks). See [Timeout](../policies/timeout.md).

### Breakers move to half-open on the next call :id=half-open

There is no timer that moves an open breaker to half-open: the first call after `waitInOpen` does. A snapshot taken in between still says `OPEN`.

### Configuration is resolved once :id=no-reload

Annotation values, named configs and external configuration are combined when the instance is created and never change afterwards. There is no `changeConfig`, no refresh event, no hot reload. Listeners too are fixed when the registry is built.

### Self-invocation in the interface style :id=self-invocation

With `Resilient.of`, a call from your implementation to its own method skips the policies, like a Spring proxy. Annotated classes (the subclass style) do intercept `this.method()`. See [Annotated classes](../guides/annotated-classes.md).

### Instances are per method unless named :id=instances

A Resilience4j instance is shared by everything that uses its name. In resilience4cata each annotated method gets its own instances, with an id such as `com.acme.StockClient#stock(java.lang.String)`; give methods the same `name` to share one (their resolved configurations must then be identical, [R4C-REG-008](../errors.md#r4c-reg-008)). `@Timeout` has no `name`.

### The processor is mandatory :id=processor

Without the annotation processor nothing is generated and `Resilient.of` fails with [R4C-RT-003](../errors.md#r4c-rt-003). There is no reflection or proxy fallback.

## The same service, both ways :id=example

A stock lookup with a retry (3 attempts, 500 ms apart, only on `IOException`), a breaker shared under the name `inventory`, a 2 s timeout and a fallback.

**Resilience4j**, with decorators:

<!-- not-compiled: Resilience4j code -->
```java
CircuitBreaker breaker = CircuitBreaker.of("inventory", CircuitBreakerConfig.custom()
        .failureRateThreshold(50)
        .slidingWindowSize(20)
        .minimumNumberOfCalls(10)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .permittedNumberOfCallsInHalfOpenState(3)
        .build());
Retry retry = Retry.of("inventory", RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(500))
        .retryExceptions(IOException.class)
        .ignoreExceptions(CallNotPermittedException.class)
        .build());
TimeLimiter timeLimiter = TimeLimiter.of(Duration.ofSeconds(2));
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

Supplier<CompletionStage<Integer>> decorated = Decorators
        .ofCompletionStage(() -> CompletableFuture.supplyAsync(() -> client.stock(sku), scheduler)) // a blocking call moved to a thread
        .withTimeLimiter(timeLimiter, scheduler)
        .withCircuitBreaker(breaker)
        .withRetry(retry, scheduler)
        .withFallback(List.of(Exception.class), e -> -1)
        .decorate();
int units = decorated.get().toCompletableFuture().join();
```

**resilience4cata**: the annotations on the interface,

<!-- snippet: migrate-r4c-interface -->
```java
public interface StockClient {

    @Fallback(method = "unknownStock")
    @Retry(name = "inventory", maxAttempts = 3, delay = "500ms", backoff = Backoff.FIXED, jitter = Jitter.NONE,
            retryOn = IOException.class)
    @CircuitBreaker(name = "inventory", failureRateThreshold = 50, windowSize = 20, minimumCalls = 10,
            waitInOpen = "30s", halfOpenCalls = 3)
    @Timeout("2s")
    int stock(String sku) throws IOException;

    default int unknownStock(String sku, Exception failure) {
        return -1;
    }
}
```

and the call site:

<!-- snippet: migrate-r4c-use -->
```java
ResilienceRegistry registry = ResilienceTesting.registry(clock).build(); // ResilienceRegistry.builder() in production
StockClient stock = Resilient.of(StockClient.class, sku -> {
    calls.incrementAndGet();
    throw new IOException("inventory unreachable");
}, registry);

int units = stock.stock("SKU-1"); // three attempts 500 ms apart, then the fallback
```

The synchronous call stays synchronous: there is no executor, because the timeout interrupts the caller's thread instead of moving the call to another one.

**External configuration.** Spring Boot's `application.yml` keys and their resilience4cata equivalents in a `.properties` file (`instances.inventory.*` overrides the annotation for that instance; `defaults.*` only fills in attributes that the annotation does not set):

<!-- snippet: migrate-properties -->
```properties
# resilience4j.circuitbreaker.instances.inventory.sliding-window-size=50
instances.inventory.circuitBreaker.windowSize=50
# resilience4j.circuitbreaker.instances.inventory.wait-duration-in-open-state=10s
instances.inventory.circuitBreaker.waitInOpen=10s
# resilience4j.retry.instances.inventory.max-attempts=5
instances.inventory.retry.maxAttempts=5
# resilience4j.circuitbreaker.configs.default.failure-rate-threshold=40
defaults.circuitBreaker.failureRateThreshold=40
```

Load it with `ResilienceConfig.load()` (as `resilience4cata.properties` on the classpath) or `ResilienceConfig.builder().classpath(…)`, then `config.applyTo(ResilienceRegistry.builder())`. See [Configuration](../guides/configuration.md).

## From resilience4j-spring-boot3 :id=spring-boot

`resilience4cata-spring` replaces `resilience4j-spring-boot3` on Spring Boot 4.1 or newer
([Spring Boot guide](../guides/spring.md)). Drop `spring-boot-starter-aop`: nothing is woven or proxied; the
annotation processor generates the protected classes, and the integration makes Spring instantiate them.

| resilience4j-spring-boot3 | resilience4cata-spring |
|---|---|
| `resilience4j.<module>.instances.<name>.<key>` | `resilience4cata.instances.<name>.<policy>.<attribute>` (`circuit-breaker`, `retry`, `rate-limiter`, `timeout`, `bulkhead`) |
| `resilience4j.<module>.configs.default.<key>` | `resilience4cata.defaults.<policy>.<attribute>` |
| `resilience4j.<module>.configs.<name>.<key>` | `resilience4cata.configs.<name>.<policy>.<attribute>`, used by `@Retry(config = "<name>")` and the like |
| `baseConfig: <name>` | `resilience4cata.configs.<config>.extends: <name>`; an annotation picks its config with `config = "<name>"` |
| `<module>AspectOrder` properties | `@PolicyOrder` on the method, the type or a bundle ([Changing the order](../policies/README.md#changing-the-order)) |
| `CircuitBreakerConfigCustomizer`, `RetryConfigCustomizer`… beans | `ResilienceRegistryCustomizer` beans |
| `RegistryEventConsumer` beans, `getEventPublisher()` | `EventListener<…>` beans |
| `/actuator/circuitbreakers`, `/actuator/retries`, `/actuator/ratelimiters`… | `/actuator/resilience4cata` (every policy, read-only) |
| `/actuator/circuitbreakerevents` and the other event endpoints | none: no event history is kept; use listener beans or metrics |
| health indicators, off by default (`registerHealthIndicator`) | the `resilience4cata` indicator, on but UP with open breakers unless listed in `resilience4cata.spring.health.down-when-open` |
| metrics `resilience4j.circuitbreaker.*`… | metrics `r4c.*` ([Micrometer](../guides/micrometer.md)), with `resilience4cata-micrometer` |
| `fallbackMethod = "f"` resolved by reflection | `@Fallback(method = "f")`, checked at compile time |

The same keys in `application.yml`:

```yaml
# resilience4j.circuitbreaker.instances.inventory.sliding-window-size: 50
# resilience4j.circuitbreaker.configs.default.failure-rate-threshold: 40
# resilience4j.retry.instances.inventory.max-attempts: 5
resilience4cata:
  defaults:
    circuit-breaker:
      failure-rate-threshold: 40
  instances:
    inventory:
      circuit-breaker:
        window-size: 50
      retry:
        max-attempts: 5
```

Typos fail the startup with the file and line of the key, instead of being ignored.

## From resilience4j-reactor :id=reactor

A `Mono` decorated with Resilience4j's operators:

<!-- not-compiled: Resilience4j code -->
```java
Mono<Price> price = webClient.get().uri("/prices/{sku}", sku).retrieve().bodyToMono(Price.class)
        .transformDeferred(TimeLimiterOperator.of(timeLimiter))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .transformDeferred(RetryOperator.of(retry));
```

becomes, with `resilience4cata-reactor`, a `Mono` method with the annotations (`@Retry`, `@CircuitBreaker`, `@Timeout` on `Mono<Price> price(String sku)`), or, for code you cannot annotate, one operator for the whole pipeline: `.transform(PipelineOperator.of(pipeline))`. See [Project Reactor](../guides/reactor.md).

| resilience4j-reactor | resilience4cata-reactor |
|---|---|
| one operator per policy, nested by the order of `transformDeferred` | one annotation per policy, nested in the [default order](#order) or `@PolicyOrder`; one `PipelineOperator` for a pipeline |
| the retry subscribes to the same `Mono` again | a `Mono` method is called again for each attempt (a new `Mono`); `PipelineOperator` subscribes to the same source again, `defer(...)` builds it again |
| `TimeLimiterOperator`: `Mono.timeout` on Reactor's parallel scheduler, armed for every subscription | `@Timeout` on the library's timer, skipped for a value that is there at subscription |
| on a `Flux`, the retry subscribes again after a failure, even when elements were emitted (they come again) | on a `Flux` method, the retry and the fallback act before the first element only; a later failure reaches the subscriber |
| `TimeLimiterOperator` on a `Flux`: `flux.timeout`, a timer scheduled for every element | `@Timeout` restarted by every element without a timer per element (one clock read) |
| `CircuitBreakerOperator` on a `Flux` times the whole stream for slow calls | the time to the first element |

## Step by step :id=steps

1. Add the dependencies, including the processor on the processor path ([Installation](../start/installation.md)); with Spring Boot, `resilience4cata-spring` ([above](#spring-boot)).
2. For each decorated call, put the call behind an interface (or annotate the class), and translate the configuration with the tables above. Set `backoff`, `jitter` and `maxWait` explicitly where the defaults differ.
3. Replace `Decorators`/Spring annotations with resilience4cata annotations and `Resilient.of` (or `Resilient.create` for annotated classes). Calls you cannot put behind an interface go through a [`Pipeline`](../guides/pipeline.md).
4. Move event consumers to `ResilienceRegistry.Builder.listen(…)`, and metrics to the [Micrometer module](../guides/micrometer.md).
5. Replace time-based tests with [`ManualClock`](../guides/testing.md).
6. Build with `-Xlint:all -Werror` if you like: generated code compiles without warnings.
