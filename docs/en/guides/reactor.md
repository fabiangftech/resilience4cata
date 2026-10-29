# Project Reactor

A method that returns Reactor's `Mono<T>` or `Flux<T>` gets the asynchronous form of every policy, once per subscription, with the subscriber's context, and is cancelled through the policies when the subscriber cancels.

<div class="r4c-facts">

| Module | Reactor | Types | Since |
|---|---|---|---|
| `resilience4cata-reactor` | 3.6 or newer | `Mono<T>`, `Flux<T>` | 0.11.0 |

</div>

## Install

Add the module next to the others; the processor needs nothing new.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:0.11.0'
    implementation 'dev.resilience4cata:resilience4cata-reactor:0.11.0'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:0.11.0'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:0.11.0")
    implementation("dev.resilience4cata:resilience4cata-reactor:0.11.0")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:0.11.0")
}
```

#### **Maven**

```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-reactor</artifactId>
  <version>0.11.0</version>
</dependency>
```

<!-- tabs:end -->

The module depends on `resilience4cata-core` and `reactor-core`; your application's own, newer Reactor wins. A `Mono` method compiled without it is the error [`R4C-ASY-003`](../errors.md#r4c-asy-003).

## A Mono interface

The annotations are the same as on any other method; the return type decides.

<!-- snippet: reactor-interface -->
```java
public interface PriceClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @CircuitBreaker(name = "prices")
    @Timeout("2s")
    @Fallback(method = "unknown")
    Mono<BigDecimal> price(String sku);

    default Mono<BigDecimal> unknown(String sku, Throwable failure) {
        return Mono.just(BigDecimal.ZERO);
    }
}
```

<!-- snippet: reactor-call -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
PriceClient prices = Resilient.of(PriceClient.class, http, registry);

Mono<BigDecimal> price = prices.price("sku-1");   // nothing runs yet
BigDecimal value = price.block();                 // subscribes: two failed attempts, then 42
```

Annotated classes work the same way ([annotated classes](annotated-classes.md)), and so do Spring, Micronaut and Quarkus beans: in a WebFlux controller, return the protected bean's `Mono` as it is.

## What a Mono method does

- **Nothing runs before a subscription.** Calling `price("sku-1")` builds a `Mono`; each subscription is one call through the policies, so subscribing twice (or `repeat()`) calls twice.
- **Each attempt calls the method again.** A retry or a hedge calls your implementation for a new `Mono` and subscribes to it, as for a `CompletionStage` method. A method that throws, or returns `null`, is a failed attempt.
- **The subscriber's context reaches every attempt,** and the fallback's `Mono` too, so tracing, Spring Security's `ReactiveSecurityContextHolder` and your own keys work inside the policies:

<!-- snippet: reactor-context -->
```java
Mono<BigDecimal> price = prices.price("sku-1")
        .contextWrite(Context.of("tenant", "acme"));   // the delegate's Mono, and every retry, see it
```

- **An empty `Mono` is a success with no value** (`null` for a classifier), and the result is empty too.
- **Cancelling the subscription cancels the call through every policy:** the current attempt's `Mono` is cancelled, a retry waiting for its delay stops, the breaker records nothing, the bulkhead and adaptive limit free their slot. A client that disconnects from a WebFlux endpoint does this.
- **Failures arrive without demand, values on demand,** as Reactive Streams requires; the generated `Mono` passes the [Reactive Streams TCK](https://github.com/reactive-streams/reactive-streams-jvm/tree/master/tck).
- **No policy blocks the thread,** which BlockHound checks in the build: a rate limiter or a bulkhead with `maxWait` makes the call wait without parking a thread, as for any [async method](async.md).

Which thread emits: the first attempt subscribes on the subscribing thread. Retry delays, hedges, timeouts and rate-limiter waits fire on the library's timer, which hands the work to a new virtual thread, so the value of a later attempt arrives there. Add `publishOn(...)` after the method if the next operators need a particular scheduler.

> [!MEASURED]
> On AWS Graviton3, a `Mono` method with a circuit breaker costs 106 ns per call against 243 ns for resilience4j-reactor 2.4.0's operator (105 vs 1291 ns with four threads), and a retry, breaker, timeout and bulkhead stack 201 ns against 4319 ns, including the 52 ns of subscribing to the `Mono`. See [Performance](../performance.md#reactor).

## Fallbacks

A fallback of a `Mono` method returns the value (`BigDecimal`) or a `Mono` of it (`Mono<BigDecimal>`), as a sibling method or a [`FallbackHandler`](../policies/fallback.md); a `CompletionStage` is not accepted. Its `Mono` is subscribed with the caller's context.

## Flux methods :id=flux

A method that returns `Flux<T>` gets the same policies, adapted to a stream: each subscription is one call, each attempt subscribes to the method's `Flux` again, and its elements reach the subscriber as they arrive, with the subscriber's demand and context.

<!-- snippet: reactor-flux-interface -->
```java
public interface PriceFeed {

    @Retry(maxAttempts = 3, delay = "50ms")
    @Timeout("5s")                       // at most 5 s to the first price, and between prices
    @Fallback(method = "closed")
    Flux<BigDecimal> prices(String sku);

    default Flux<BigDecimal> closed(String sku, Throwable failure) {
        return Flux.empty();
    }
}
```

<!-- snippet: reactor-flux-call -->
```java
PriceFeed feed = Resilient.of(PriceFeed.class, exchange, ResilienceRegistry.builder().build());

feed.prices("sku-1")
        .doOnNext(price -> System.out.println(price))   // two failed attempts, then 41, 42, 43
        .blockLast();
```

- **The first element commits the call.** Before it, a failure is retried or replaced by the fallback, as for a `Mono`. After it, the failure reaches the subscriber as it is: a retry would send the elements again, and a fallback would mix two sources.

<!-- snippet: reactor-flux-committed -->
```java
StepVerifier.create(committed.prices("sku-1"))
        .expectNext(new BigDecimal(41))       // the first element: no retry, no fallback from now on
        .expectErrorMessage("feed broke")     // so the failure reaches the subscriber as it is
        .verify();
```

- **`@Timeout` is the time without an element:** to the first one, between two, and to the end. A stream that keeps emitting never times out, however long it lasts; one that stalls does, before its first element (then it is retried) or after it (the subscriber gets `CallTimeoutException`). Time spent waiting for the subscriber's demand counts, as with Reactor's `timeout` operator.
- **The circuit breaker** records one call per attempt when its stream ends, so a failure after elements is a failure; `slowCallDuration` is the time to the first element. **The adaptive limit** holds a slot while the stream runs and samples the time to its first element.
- **The bulkhead** holds its slot until the stream ends or is cancelled. **The rate limiter** takes one permit per attempt, not per element.
- **A fallback** returns a `Flux<T>`, or one value `T` (a stream of that one element), as a sibling method or a [`FallbackHandler`](../policies/fallback.md).
- **Demand** carries over: the next attempt is asked for everything the subscriber requested, since nothing was emitted before the first element. A non-positive request is reported and ignored, as by Reactor's own operators (Reactor signals rule 3.9's error to any other subscriber itself); the generated `Flux` passes every rule of the Reactive Streams TCK.
- **Cancelling the subscription** cancels the call through every policy; in WebFlux, a client that disconnects from a server-sent events endpoint (`produces = MediaType.TEXT_EVENT_STREAM_VALUE`) returning the bean's `Flux` does this, and the bulkhead frees its slot.
> [!MEASURED]
> On AWS Graviton3, a stream of ten elements through a `Flux` method with `@Timeout` costs 681 ns against 28896 ns with resilience4j-reactor 2.4.0's time limiter (`flux.timeout`, a timer per element), and the retry, breaker, timeout and bulkhead stack 789 ns against 30583 ns; a thousand elements with a timeout take 45 µs against 2.6 ms. A single bulkhead or rate limiter is within the error of Resilience4j's. See [Performance](../performance.md#reactor).

- **Not on `Flux` methods:** `@Hedge` (it would merge two streams), classifiers (one result for many elements: use `retryOn`/`abortOn` and `recordOn`/`ignoreOn`), and a `@PolicyOrder` that changes the order. Each is [`R4C-ASY-004`](../errors.md#r4c-asy-004).

## Code you cannot annotate

`PipelineOperator` applies a [pipeline](pipeline.md) to a `Mono`, for a `WebClient` call or a third-party client:

<!-- snippet: reactor-operator -->
```java
PipelineOperator<String> resilient = PipelineOperator.of(Pipeline.builder(registry, "rates", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(2)))
        .build());

Mono<String> rates = webClientCall.transform(resilient);   // each attempt subscribes to webClientCall again
```

Every attempt subscribes to the source again, which runs a `WebClient` request again. A source that gives the same outcome to every subscriber (`cache()`, a hot source) is not retried meaningfully; build it per attempt with `resilient.defer(() -> ...)` instead.

`FluxPipelineOperator` does the same for a `Flux` (`bodyToFlux`), with the stream rules [above](#flux); the pipeline's fallback value becomes a stream of that one element, and a pipeline with a hedge or a classifier cannot protect a `Flux` ([`R4C-RT-020`](../errors.md#r4c-rt-020)):

<!-- snippet: reactor-flux-operator -->
```java
FluxPipelineOperator<String> resilient = FluxPipelineOperator.of(Pipeline.builder(registry, "quotes", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(5)))
        .build());

Flux<String> quotes = webClientStream.transform(resilient);   // an attempt subscribes to webClientStream again
```

## Testing

On a [`ManualClock`](testing.md), delays, timeouts and hedges happen when the test advances the clock, and `StepVerifier` checks the outcome:

<!-- snippet: reactor-testing -->
```java
ManualClock clock = new ManualClock();
ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
PriceClient prices = Resilient.of(PriceClient.class, slow, registry);

StepVerifier.create(prices.price("sku-1"))
        .then(() -> clock.advance(Duration.ofSeconds(7)))   // three 2 s timeouts and the delays between them
        .expectNext(BigDecimal.ZERO)                        // the fallback
        .verifyComplete();
```

The manual clock is the library's; `StepVerifier.withVirtualTime` moves Reactor's schedulers, not the policies' timers.

## Limits

- **Exactly `Mono` or `Flux`:** a subclass of either, `Mono<? super T>` or a raw `Mono` is not a reactive method ([`R4C-ASY-001`](../errors.md#r4c-asy-001), or `R4C-ASY-002` for a subclass).
- **A `Flux` method** has no hedge, no classifier and the default order ([`R4C-ASY-004`](../errors.md#r4c-asy-004)), and its retry and fallback act before the first element only ([above](#flux)).
- **Transactions:** a reactive `@Transactional` on a retried or hedged `Mono` method of an annotated class would run every attempt in one transaction, which Spring, Micronaut and Quarkus report at startup, as for other methods ([`R4C-SPR-004`](../errors.md#r4c-spr-004)).
- **Mutiny and RxJava** are not supported yet; convert to a `CompletionStage` (`uni.subscribeAsCompletionStage()`).
