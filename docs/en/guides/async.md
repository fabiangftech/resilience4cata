# Async methods

A method that returns `CompletionStage<T>` or `CompletableFuture<T>` gets the non-blocking form of every policy: no thread sleeps, waits or is parked on its behalf.

There is no `@Asynchronous` annotation: the return type decides. The same annotations, attributes and configuration keys apply.

## An asynchronous interface

<!-- snippet: async-interface -->
```java
public interface TrackingClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @Fallback(method = "unknown")
    CompletableFuture<String> track(String parcel);

    default CompletionStage<String> unknown(String parcel, IOException failure) {
        return CompletableFuture.completedFuture("unknown (" + failure.getMessage() + ")");
    }

    @Timeout("100ms")
    CompletionStage<String> eta(String parcel);
}
```

<!-- snippet: async-call -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
TrackingClient tracking = Resilient.of(TrackingClient.class, http, registry);

CompletableFuture<String> status = tracking.track("p-1");   // returns at once: no thread sleeps between attempts
// status completes with "in transit" after two failed attempts
```

The call returns a future at once. The first attempt runs on the caller's thread; when it fails, the retry schedules the next attempt after its delay on a timer, and the attempt then starts on a new virtual thread. No thread is blocked during the delay.

## What changes compared with a synchronous method

| Policy | Synchronous method | Asynchronous method |
|---|---|---|
| Retry | Sleeps on the caller's thread between attempts | Schedules the next attempt; the returned future completes when an attempt succeeds or the retry gives up |
| [Hedge](../policies/hedge.md) | Not supported (compile error [`R4C-HDG-001`](../errors.md#r4c-hdg-001)) | Starts the call again after `delay` without a result, or at once after a failure; the first success completes the future and the other attempts are cancelled |
| Circuit breaker | Records the call when it returns or throws | Records the call when its stage completes |
| Rate limiter with `maxWait` | Sleeps until the permit is due | Starts the call when the permit is due, without blocking the caller |
| [Adaptive limit](../policies/adaptive-limit.md) | Holds the slot while the method runs, and measures its latency | Holds the slot until the stage completes, and measures the latency to that completion |
| Timeout | Interrupts the caller's thread at the deadline | Fails the returned future with `CallTimeoutException` at the deadline, and cancels the late stage if it is a `Future` (through the policies inside, which let go at once: see [cancellation](#cancellation)) |
| Bulkhead | Holds the slot while the method runs; with `maxWait`, waits on a semaphore | Holds the slot until the stage completes; with `maxWait`, queues the call without blocking a thread |
| Fallback | Returns a value | Returns a value or a stage |

Rules that hold for every policy:

- **A method that throws instead of returning a stage** is treated like a failed stage: the policies see the exception, and if nothing recovers, the returned future fails with it. A method that returns `null` instead of a stage fails with a `NullPointerException` the same way.
- **Failures are unwrapped.** A stage that fails with `CompletionException` or `ExecutionException` is judged by its cause, so `retryOn`, `recordOn`, classifiers and fallback signatures see your exception (`IOException` in the example), not the wrapper.
- **Rejections fail the future.** An open breaker, a full bulkhead, a full adaptive limit or an exhausted rate limiter fails the returned future with `CallNotPermittedException`, `BulkheadFullException`, `LimitExceededException` or `RateLimitExceededException`.
- **Callbacks don't run on the timer.** Delayed retries, hedges, rate-limiter waits and timeouts fire on a library timer, which hands the work to a new virtual thread. So attempts after the first (a retry, a hedge) run without the caller's `ThreadLocal`s or MDC.

A timed-out call, from the same test:

<!-- snippet: async-timeout -->
```java
CompletableFuture<String> eta = tracking.eta("p-1").toCompletableFuture();

assertThatThrownBy(() -> eta.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(CallTimeoutException.class);   // after 100 ms; no thread was blocked
```

When you `join()` or `get()` the future yourself, the JDK wraps the failure: `join()` throws `CompletionException` and `get()` throws `ExecutionException`, with the policy's exception as the cause.

## Cancellation

Cancelling the future that an async method returns (with `cancel(...)`, or `completeExceptionally` with a `CancellationException`) cancels the call inside it, through every layer: each policy passes the cancel to the stage it is waiting for, down to the stage your method returned, which is cancelled if it is a `Future`. What each policy does:

| Policy | On cancel |
|---|---|
| Retry | Starts no further attempt: a pending backoff is cancelled, and the attempt in flight is cancelled |
| Hedge | Starts no further attempt: the pending delay is cancelled, and every attempt in flight is cancelled. A hedge also cancels its own losers the same way, with a `HedgeCancelledException` |
| Circuit breaker | Records nothing, like an ignored call: a half-open permit is given back |
| Rate limiter with `maxWait` | A call still waiting for its permit never starts, and the permit is given back (as when a synchronous wait is interrupted). A call that already started keeps its permit |
| Adaptive limit | Frees the slot as soon as the stage ends, without a sample: a cancelled call says nothing about the dependency |
| Timeout | Cancels its deadline: no `CallTimeoutException`, no `TimedOut` event |
| Bulkhead | Frees the slot as soon as the stage ends, which for a `CompletableFuture` is at once. A call still queued (`maxWait`) leaves the queue and never starts |
| Fallback | Does not run: nobody waits for an alternative result |

A cancel publishes no event: no `GaveUp`, no `Retry`, no failure, no rejection. The inner layers have let go before the callbacks you attached to the future run, so a callback can start a new call in the slot that was freed.

Things to know:

- **The cancel reaches the stage, not the work behind it.** Cancelling a `CompletableFuture` completes it; it does not interrupt the code that would have completed it. The bulkhead frees its slot when the stage ends, so if your stage is not a `Future`, or ignores `cancel`, the slot stays taken (and the breaker records the late outcome) until the stage completes.
- **Cancel the future the method returned.** A future derived from it (`thenApply`, `whenComplete`, `copy()` and the like) is a new `CompletableFuture`: cancelling it does not reach the call, as in the JDK.
- **Cancel and completion race safely.** If the call completes at the same moment, whichever comes first wins; slots and permits are released exactly once either way.
- `Pipeline.executeAsync` behaves the same.

## Fallbacks

A sibling fallback of an asynchronous method returns either the value type `T` or a `CompletionStage<T>` (in the example, `unknown` returns a stage). A handler class implements `FallbackHandler<T>` or `FallbackHandler<CompletionStage<T>>`. The fallback runs after the retry gives up, and also for rejections and timeouts, like its synchronous form. See [fallback](../policies/fallback.md).

## Wildcard stages

The return type may be `CompletionStage<? extends X>` or `CompletableFuture<? extends X>`; `?` alone means `? extends Object`. The policies then work with `X`: fallbacks return an `X` (or a stage of one), and classifiers must accept `X`.

<!-- snippet: async-wildcard -->
```java
public interface ParcelClient {

    @Retry(maxAttempts = 2, delay = "10ms")
    @Fallback(method = "noWeight")
    CompletionStage<? extends Number> weight(String parcel);

    default Integer noWeight(String parcel, Exception failure) {
        return 0;
    }
}
```

## What is not supported

- **Other stage types are compile errors** ([`R4C-ASY-001`](../errors.md#r4c-asy-001)): raw `CompletionStage`, `? super X`, and subtypes of `CompletionStage` other than `CompletableFuture`.
- **Other asynchronous types are treated as synchronous.** A method returning `java.util.concurrent.Future`, a reactive type or anything else is protected like any synchronous method: the policies see only the call that *returns* the object, not the work it represents.

> [!WARNING]
> If a method returns a `Future` or a reactive type, a timeout or a retry does not apply to the result. Return a `CompletableFuture` instead, or wrap the work in one.

## Cost

Asynchronous calls allocate: each layer creates futures and goes through `CompletableFuture` callbacks, which is the price of not blocking threads. Synchronous calls are unaffected.

> [!MEASURED]
> An asynchronous timeout with a pending stage costs 152 ns and 192 B on one thread, against 598 ns and 240 B for Resilience4j 2.4.0 (AWS Graviton3 `c7g.xlarge`). [Benchmarks](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)

## See also

- `Pipeline.executeAsync(...)` gives the same behaviour without annotations ([Pipeline](pipeline.md)).
- On a `ManualClock`, async delays and timeouts run on the thread that advances the clock ([testing](testing.md)).
