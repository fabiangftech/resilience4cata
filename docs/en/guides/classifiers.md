# Classifiers

A classifier decides, call by call, whether a result or an exception is a success, a failure, or neither, for `@Retry` and `@CircuitBreaker`.

Exception lists (`retryOn`, `recordOn`) can only look at exceptions. Many clients report failures in their result instead: an HTTP client returns a response with status `503`, a job API returns `Status.FAILED`. A classifier covers both, in one class.

## Writing one

A classifier implements `dev.resilience4cata.Classifier<T>`, where `T` is the method's result type (or the `T` of its `CompletionStage<T>`):

<!-- snippet: classifiers-classifier -->
```java
import dev.resilience4cata.Classifier;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

public final class StatusClassifier implements Classifier<Response> {

    @Override
    public Outcome classify(@Nullable Response response, @Nullable Throwable failure) {
        if (failure != null) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
        }
        int status = response.status();
        if (status >= 500 || status == 429) {
            return Outcome.FAILURE;     // retried, and counted by the breaker
        }
        return status == 404 ? Outcome.IGNORE : Outcome.SUCCESS;
    }
}
```

`classify(result, failure)` gets exactly one meaningful argument: `failure` is `null` when the call returned normally (and `result` is `null` for a `void` method or a `null` result). It returns an `Outcome`:

| Outcome | `@Retry` | `@CircuitBreaker` |
|---|---|---|
| `SUCCESS` | Stop and return the result (or rethrow the exception) | Recorded as a success (and as slow if it was slow) |
| `FAILURE` | Retry, if attempts and time are left | Recorded as a failure (and as slow if it was slow) |
| `IGNORE` | Stop and return the result (or rethrow the exception) | Not recorded at all; a half-open probe gives its permit back |

Classifying an exception as `SUCCESS` or `IGNORE` does not swallow it: the exception still reaches the caller, it is just not retried, and the breaker counts it as a success or not at all.

## Using it

<!-- snippet: classifiers-usage -->
```java
@Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class)
@CircuitBreaker(name = "orders", classifier = StatusClassifier.class)
Response get(String orderId) throws IOException;
```

- The class is named in the annotation and created by the generated code: it must be concrete, non-generic, top-level or `static` nested, accessible, with a no-argument constructor, and implement `Classifier<X>` with the method's result assignable to `X`. The processor checks all of this when you compile ([`R4C-CLS-001`](../errors.md#r4c-cls-001) to [`R4C-CLS-010`](../errors.md#r4c-cls-010)).
- One instance is created per method. When `@Retry` and `@CircuitBreaker` name the same class, they share that instance.
- A classifier **replaces** `retryOn` (on `@Retry`) or `recordOn` (on `@CircuitBreaker`); setting both is a compile error ([`R4C-CLS-011`](../errors.md#r4c-cls-011)). `abortOn` and `ignoreOn` still apply, and win over the classifier. An open breaker's `CallNotPermittedException` always stops the retries, even if the classifier returns `FAILURE` for it.
- It is annotation-only: there is no config key for it. On a `Pipeline`, pass an instance (below).
- Asynchronous methods are classified the same way, with the value of the stage or its unwrapped failure.

What the generated code does with it (abridged):

<!-- not-compiled: generated code, abridged -->
```java
public final class OrdersApi$Resilient implements OrdersApi {
    // …
    private final StatusClassifier retryClassifier0;   // one instance per method, shared by both policies
    // …
    @Override
    public Response get(java.lang.String p0) throws java.io.IOException {
        final dev.resilience4cata.generated.RetryPolicy r4c$retry = this.retry0;
        final StatusClassifier r4c$classifier = this.retryClassifier0;
        final long r4c$start = r4c$retry.start();
        int r4c$attempts = 0;
        while (true) {
            final Response r4c$result;
            try {
                r4c$result = this.r4c$circuitBreaker0(p0);
            } catch (dev.resilience4cata.generated.ClassifierFailure r4c$w) {
                // the breaker's classifier threw: end the call with its exception, never retry it
                try {
                    throw r4c$w.failure();
                } catch (java.lang.Throwable r4c$c) {
                    r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);
                    throw r4c$c;
                }
            } catch (java.lang.Throwable r4c$e) {
                // … classify(null, r4c$e), then retry or rethrow
                continue;
            }
            final dev.resilience4cata.Classifier.Outcome r4c$verdict;
            try {
                r4c$verdict = r4c$classifier.classify(r4c$result, null);
            } catch (java.lang.Throwable r4c$c) {
                r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);
                throw r4c$c;
            }
            if (r4c$verdict != dev.resilience4cata.Classifier.Outcome.FAILURE) {
                return r4c$result;
            }
            final long r4c$wait = r4c$retry.onFailedResult(++r4c$attempts, r4c$start);
            if (r4c$wait < 0 || !r4c$retry.pause(r4c$wait)) {
                return r4c$result; // rejected, but no retries left: the last result
            }
        }
    }
    // …
}
```

The classifier is called directly, with no allocation and no clock read. Methods without a classifier generate the same code as before; nothing changes for them.

## When the retries run out on a rejected result

If the last attempt still returned a `FAILURE` result, there is no exception to rethrow. `onExhaustedResult` decides what happens:

- `RETURN` (the default): the last result is returned to the caller, as if there were no retry. `@Fallback` does **not** run, since nothing was thrown.
- `THROW`: the retry throws `dev.resilience4cata.ResultRejectedException`, which goes to `@Fallback` like any other failure:

<!-- snippet: classifiers-exhausted -->
```java
@Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class,
        onExhaustedResult = ExhaustedResult.THROW)
@Fallback(method = "unavailable")
Response submit(String orderId) throws IOException;

default Response unavailable(String orderId, ResultRejectedException rejected) {
    Response last = rejected.result(Response.class);   // the last rejected result
    return new Response(503, "gave up after " + rejected.attempts() + " attempts: " + last.body());
}
```

`ResultRejectedException` carries `instanceId()`, `attempts()` and the last result, as `result()` (an `Object`) or `result(Class<R>)` (typed). `onExhaustedResult` needs a classifier ([`R4C-CLS-012`](../errors.md#r4c-cls-012)), is decided at compile time, and is not part of the retry's configuration: no config key can change it. `maxDuration` applies as usual: giving up because the budget ran out on a rejected result behaves the same way.

## A classifier that throws

A classifier should not throw. If it does, its exception becomes the call's failure, the same for sync and async methods:

- `@CircuitBreaker` records the call as a `FAILURE`, then throws the classifier's exception.
- `@Retry` stops at once, publishes `RetryEvent.GaveUp` with reason `CLASSIFIER_FAILED` (with the classifier's exception as `failure`, and the attempts made so far, this one included), and throws it.
- `@Fallback`, if present, receives it.

This holds wherever the classifier runs. When the breaker's classifier throws, the retry around it does not classify that exception as the method's failure, and does not retry it, whether the retry has the same classifier, another one, or none: the breaker hands the exception to the retry in an internal wrapper (`dev.resilience4cata.generated.ClassifierFailure`, never seen by callers), and the call ends as above. The caller, or the fallback, gets the classifier's exception itself. Still, keep classifiers total: handle `null` results and unexpected exception types explicitly.

## Same thing with `Pipeline`

<!-- snippet: classifiers-pipeline -->
```java
Pipeline<Response> get = Pipeline.builder(registry, "orders-pipeline", Response.class)
        .retry(r -> r.maxAttempts(3))
        .circuitBreaker()
        .classifier(new StatusClassifier())              // both policies
        .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
        .build();

Response response = get.execute(() -> orders.get("o-1"));
```

`classifier(...)` sets it for both the retry and the circuit breaker; `retryClassifier(...)` and `circuitBreakerClassifier(...)` set one each. `onExhaustedResult(Pipeline.ExhaustedResult.THROW)` needs a retry classifier. Without a matching policy, `build()` fails ([`R4C-RT-014`](../errors.md#r4c-rt-014) to [`R4C-RT-017`](../errors.md#r4c-rt-017)). See [Pipeline API](pipeline.md).

## What it costs

> [!MEASURED]
> On AWS Graviton3 (`c7g.xlarge`, JDK 21), a successful call through a circuit breaker with a classifier costs 2.4 ns and allocates nothing, on 1 thread and on 4; Resilience4j 2.4.0 with `recordResult` takes 102 ns and 24 B on 1 thread. Retry plus circuit breaker plus classifier: 2.7 ns, 0 B, against 148 ns and 104 B. A classifier adds about 0.3 ns per policy. [Benchmarks: MVP completion](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)
