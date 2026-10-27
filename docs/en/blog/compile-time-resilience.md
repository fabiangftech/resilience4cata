# A 1.9 ns circuit breaker: resilience generated at compile time

Most Java resilience libraries wrap each call in decorators at runtime; resilience4cata writes the wrapping code at compile time instead, and a healthy circuit breaker ends up costing about two nanoseconds.

*27 October 2026 · by [fabiangftech](https://github.com/fabiangftech), author of resilience4cata*

## The problem with decorators

Retries, circuit breakers, timeouts and bulkheads usually sit on the call path as runtime decorators. You wrap a `Supplier` or a `Callable` in one function per policy, or let a framework put a proxy in front of your bean that does it for you. It works, it is flexible, and it is how Resilience4j, the library many Java teams use, is built.

It also costs something on every call, including the ones that succeed, which are almost all of them. Each layer is another call through a functional interface, and the policy state behind it is shared by every thread. In a benchmark with one decorated method the JIT sees a single type at each call site and can inline much of it, which is the best case. A real service decorates dozens of methods, and those shared call sites stop being monomorphic.

I wanted to know how cheap the same guarantees could be if the code were written for one method at a time, the way you would write it by hand if you had the patience.

## Let the compiler write it

resilience4cata is a set of annotations plus an annotation processor. You annotate an interface:

<!-- compare:start -->
<!-- snippet: how-it-works-interface -->
```java
public interface StockClient {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    int stock(String sku, int warehouse);
}
```
<!-- not-compiled: generated code, abridged (package names shortened) -->
```java
@Override
public int stock(String p0, int p1) {
    final RetryPolicy r4c$retry = this.retry0;
    final long r4c$start = r4c$retry.start();
    int r4c$attempts = 0;
    while (true) {
        try {
            return this.r4c$circuitBreaker0(p0, p1);
        } catch (Throwable r4c$e) {
            final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);
            if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {
                throw r4c$e;
            }
        }
    }
}

private int r4c$circuitBreaker0(String p0, int p1) {
    final CircuitBreakerPolicy r4c$cb = this.circuitBreaker0;
    final long r4c$token = r4c$cb.acquire();
    if (r4c$token < 0) {
        throw r4c$cb.rejection();
    }
    final long r4c$started = r4c$cb.startTimer();
    try {
        final int r4c$result = this.delegate.stock(p0, p1);
        r4c$cb.onSuccess(r4c$token, r4c$started);
        return r4c$result;
    } catch (Throwable r4c$e) {
        r4c$cb.onFailure(r4c$token, r4c$started, r4c$e);
        throw r4c$e;
    }
}
```
<!-- compare:end -->

At compile time the processor writes `StockClient$Resilient`, a plain class that implements the interface. Each protected method becomes straight-line code: a loop for the retry, a `try` block for the breaker, one `final` field per policy. There are no lambdas, no proxies and no reflection on the call path, and every call site is monomorphic because every method has its own. At runtime you wrap your implementation once:

<!-- not-compiled: a one-line usage example; the quickstart has the full, tested program -->
```java
StockClient client = Resilient.of(StockClient.class, new HttpStockClient(), registry);
```

The policies behind those fields follow a small protocol (`acquire`, `onSuccess`, `onFailure`) designed so that the success path does as little as possible:

- **No allocation.** Rejections throw one preallocated, stackless exception per policy, and events are only created when someone listens.
- **No clock read unless a feature needs it.** The breaker only times calls when you configure a slow-call threshold.
- **No shared write when nothing changes.** While a breaker is closed and has only seen fast successes, a call reads one field and writes nothing.

That last rule did the most. Under contention, writing a cache line that other cores are reading costs far more than any lock-free trick saves. On an Apple M1 with four threads, the breaker's first version cost 244 ns per successful call; skipping writes while the window holds only fast successes brought it to 10 ns, and reading a single field while healthy to about 3 ns ([how the success path got there](../benchmarks.md#circuit-breaker-1-and-4-threads-2026-10-23)).

## The numbers

Every comparison uses equivalent configuration in both libraries, and each adjustment made for fairness is written in the benchmark's Javadoc.

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, resilience4cata 0.1.0 vs Resilience4j 2.4.0, time and bytes allocated per call. From [MVP completion (2026-10-24, AWS Graviton3)](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3).

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Retry, first attempt succeeds | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Retry, fails once then succeeds | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, every call succeeds | 1 | **1.9 ns**, 0 B | 89.2 ns, 0 B |
| Circuit breaker, every call succeeds | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Bulkhead, full (every call rejected) | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Bulkhead, slots free | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |
| Retry + circuit breaker | 1 | **2.3 ns**, 0 B | 175 ns, 288 B |

The table also shows where the approach does not help:

- **Some cases are a tie.** A bulkhead with free slots takes and gives back a permit with one atomic operation in both libraries: 20.5 ns against 20.4 ns.
- **Contention still costs hundreds of nanoseconds.** With four threads and one call in ten failing, the breaker costs 274 ns (Resilience4j: 910 ns), because every call advances one shared window cursor. Shared counters cost the bulkhead and the rate limiter in the same way.
- **Async calls allocate.** Every async layer returns a new future in both libraries; only the sync paths are allocation-free.
- **Some rows are not like for like.** Resilience4j's retry calls `Thread.sleep(0)` between attempts, so its row uses a variant without it (its best case). Its `TimeLimiter` needs a `Future`, so a synchronous timeout means another thread; that comparison is on the [performance page](../performance.md#headline), with that caveat.

Does a nanosecond matter for a call that goes over the network? Rarely on its own. It matters when resilience wraps calls that are usually cheap (a cache, a local service, an in-memory queue), when the same code runs millions of times per second, or when you would rather not think about whether a policy is too expensive to add.

## What it costs you

Compile-time generation has trade-offs, and they are deliberate:

- **The annotation processor is mandatory.** There is no reflection fallback: if the generated class is missing, `Resilient.of` fails at once and names the dependency to add.
- **Mistakes are compile errors.** An invalid duration, an unknown attribute or a fallback with the wrong signature fails the build with a code such as `R4C-ATR-001`, documented in the [error reference](../errors.md).
- **Configuration is resolved once.** Values can come from annotations, properties files, environment variables or Spring, Micronaut and Quarkus configuration, but they are fixed when the registry is built: there is no hot reload, by [design](../design.md).
- **It is young.** The current version is 0.8; until 1.0 the API can still change.

## Try it

Two lines in Gradle:

<!-- not-compiled: build configuration -->
```groovy
implementation 'dev.resilience4cata:resilience4cata-all:0.8.0'
annotationProcessor 'dev.resilience4cata:resilience4cata-processor:0.8.0'
```

Then the [quickstart](../start/quickstart.md) protects a flaky call in about five minutes. There are integrations for [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) and [Quarkus](../guides/quarkus.md), a [testkit](../guides/testing.md) with a manual clock, and a [guide for migrating from Resilience4j](../migrate/from-resilience4j.md). The code is Apache-2.0 licensed on [GitHub](https://github.com/fabiangftech/resilience4cata); issues and discussions are welcome.
