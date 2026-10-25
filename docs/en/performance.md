# Performance

What resilience4cata costs per call, measured against Resilience4j 2.4.0 with equivalent configuration, and how to reproduce it.

Low overhead is the reason resilience4cata exists: the annotation processor generates straight-line code for each method, so a successful call goes through a few field reads and, for most policies, no allocation, no lock and no clock read. This page summarises the measurements; [Benchmarks in depth](benchmarks.md) has every run, its setup and how each number was reached.

## What is measured, and how fairly :id=method

- **JMH 1.37**, 3 forks × 5 × 1 s warm-up and 5 × 1 s measurement, with `-prof gc` for bytes allocated per call. Plain JMH, no Gradle plugin.
- **Equivalent configuration in both libraries**: same attempts, delays, window sizes, thresholds and limits; no event listeners; failures are preallocated, stackless exceptions so that neither library pays for creating them. Rejection exceptions of Resilience4j get `writableStackTraceEnabled(false)`.
- **Adjustments made for fairness are documented** in each benchmark's Javadoc. The main one: Resilience4j 2.4.0 calls `Thread.sleep` between attempts even with a zero delay, so the retry comparison uses a variant without that sleep (`resilience4jRetryOnceNoSleep`), which is Resilience4j's best case.
- **Differences in behaviour that the numbers include**: Resilience4j's breaker always times calls (resilience4cata only when `slowCallDuration` is set); Resilience4j's `TimeLimiter` needs a `Future`, so a synchronous call must go to another thread (resilience4cata times it out on the caller's thread).
- **Two machines, never mixed**: an AWS `c7g.xlarge` (4 Graviton3 cores, no SMT, fixed clock, Amazon Corretto 21), and an Apple M1 laptop (JDK 21) for earlier rounds. Numbers from different machines are not comparable.
- A benchmark with one decorated method is Resilience4j's best case: the JIT sees one type at every call site. Real applications have many.

## Headline results :id=headline

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, resilience4cata 0.1.0 vs Resilience4j 2.4.0, nanoseconds and bytes allocated per call. From [MVP completion (2026-10-24, AWS Graviton3)](benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3).

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Retry, first attempt succeeds | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Retry, fails once then succeeds (Resilience4j without its `sleep(0)`) | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, every call succeeds | 1 | **1.9 ns**, 0 B | 89.2 ns, 0 B |
| Circuit breaker, every call succeeds | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Circuit breaker, one call in ten fails | 4 | **274 ns**, 0 B | 910 ns, 2 B |
| Rate limiter, permits free | 4 | **187 ns**, 0 B | 275 ns, 40 B |
| Rate limiter, exhausted (every call rejected) | 4 | **34.7 ns**, 0 B | 1599 ns, 505 B |
| Bulkhead, slots free | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |
| Bulkhead, slots free | 4 | **264 ns**, 0 B | 432 ns, 0 B |
| Bulkhead, full (every call rejected) | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Timeout, sync call that finishes in time (Resilience4j: `TimeLimiter` + executor) | 1 | **22.2 ns**, 0 B | 8842 ns, 176 B |
| Async timeout, stage still pending | 1 | **152 ns**, 192 B | 598 ns, 240 B |
| Async timeout, stage still pending | 4 | **369 ns**, 192 B | 1563 ns, 234 B |

Reading the table honestly:

- **Bulkhead with free slots on one thread is a tie** (20.5 vs 20.4 ns): both take and release a permit with one atomic each.
- **Contended paths cost hundreds of nanoseconds in both libraries.** The breaker's mixed case at four threads (274 ns) is dominated by one shared window cursor that every call advances; the bulkhead and rate limiter at four threads pay for one shared counter.
- **The sync timeout is the most expensive sync policy** (22.2 ns on one thread; a later run measured 23.7 ns with no code change, an open layout effect, see [the next round](benchmarks.md#annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3)). Resilience4j's comparison is not like for like: its `TimeLimiter` hands the call to another thread, which is where most of its 8.8 µs goes.
- **Async calls allocate in both libraries**: every layer returns a new future. resilience4cata's async paths are not allocation-free; only the sync ones are.
- **The rate limiter reads the clock once per call**, in both libraries; that read is most of its cost (on Apple Silicon, `System.nanoTime()` alone costs about 21 ns on one thread and 163 ns with four; see [Rate limiter, 1 and 4 threads](benchmarks.md#rate-limiter-1-and-4-threads-2026-10-24)).

### Classifiers, Pipeline and annotated classes :id=more

> [!MEASURED]
> Same machine and round as above ([MVP completion](benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3), [annotated classes](benchmarks.md#annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3)), every call succeeds, 0 B on the resilience4cata side.

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Circuit breaker + result classifier | 1 | **2.4 ns** | 102 ns, 24 B (`recordResult`) |
| Circuit breaker + result classifier | 4 | **2.4 ns** | 902 ns, 18 B |
| Retry + circuit breaker + classifier | 1 | **2.7 ns** | 148 ns, 104 B |
| Retry + circuit breaker, generated code | 1 | **2.3 ns** | 175 ns, 288 B |
| Retry + circuit breaker, programmatic `Pipeline` | 1 | **2.9 ns** | (same Resilience4j stack) |
| Retry, annotated class (subclass) vs interface wrapper | 1 | 1.0 vs 1.2 ns | — |

- A `Pipeline` whose single `call.call()` site has seen five other pipelines measured **7.9 ns** instead of 2.9 ns: with many pipelines in one application that call site turns megamorphic. Generated code has one call site per method and does not pay this.

### Many services in one application :id=many-services

> [!MEASURED]
> Apple M1, JDK 21, four unrelated services each with its own retry, one call each per operation. From [Retry, four services](benchmarks.md#retry-four-services-2026-10-23).

| Per call | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| Overhead, first attempt succeeds | **0.6 ns**, 0 B | 21.3 ns, 80 B |
| Overhead, fails once then succeeds | **9.4 ns**, 0 B | 273 ns, 16 B (without its `sleep(0)`) |

With four decorated suppliers sharing Resilience4j's call site, its success path starts allocating 80 B per call; resilience4cata's generated classes each have their own call site and stay at 0 B.

## The zero-allocation gate :id=allocation-gate

Allocation is checked on every pull request. `./gradlew :benchmark:allocationCheck` runs the benchmarks listed in `benchmark/allocation-budgets.properties` with `-prof gc` and fails if one allocates its budget + 1 B per call. **Every sync success and rejection path has a budget of 0 B**: retry, circuit breaker (count and time windows, open), rate limiter, bulkhead, timeout, classifiers, generated code and `Pipeline` (also with a capturing lambda). Async paths have budgets for their futures (48 B per policy). Details in [Allocation gate](benchmarks.md#allocation-gate).

Events allocate only when someone listens: an event type without listeners costs one null check on the failure or rejection path, which measured as 0.3 ns on a full bulkhead.

## The performance contract :id=contract

The targets the project holds itself to, and how each one is checked:

| Target | How it is checked |
|---|---|
| **0 B/op** on the success path of every policy and of a full stack | JMH `-prof gc` in CI: `./gradlew :benchmark:allocationCheck` fails a pull request if a listed benchmark reaches its budget + 1 B/op (0 for every sync path; measured values for async futures) |
| No locks and no `synchronized` on hot paths | code review, JMH contention scenarios |
| Monomorphic, straight-line generated code per method; no shared lambda chains | JMH with a polluted JIT profile, `-XX:+PrintInlining` |
| One clock-read pair per call, whatever the number of policies | code review |
| Preallocated, stackless rejection exceptions; events allocated only if someone listens | JMH rejection scenarios (open breaker, full bulkhead, exhausted rate limiter), gated at 0 B/op in CI |
| Correct lock-free structures | jcstress, in CI after every push to main and nightly |
| Published, reproducible comparison against Resilience4j 2.4.0 | the `benchmark` module, with equivalent Resilience4j configuration and fairness notes ([Benchmarks in depth](benchmarks.md)) |

Most real calls are I/O-bound (milliseconds), so the point is overhead close to zero that holds up with many threads, not raw speed alone.

## What makes it cheap :id=why

- **Generated, per-method, straight-line code**: no shared decorator lambda, so the JIT inlines each call site with one receiver type.
- **Nothing allocated on success**, and rejections throw one preallocated, stackless exception per instance.
- **No locks** on any call path, and the weakest atomic that is correct: acquire/release instead of full fences took the free bulkhead from 23.2 to 9.3 ns on Apple M1 ([Bulkhead, 1 and 4 threads](benchmarks.md#bulkhead-1-and-4-threads-2026-10-23)).
- **No shared writes while healthy**: a breaker whose window holds only fast successes is checked with one read per call.
- **No clock reads** unless a feature needs them (slow-call detection, time windows, rate limiting). Timeouts use a shared watchdog thread instead of reading the clock per call.

## Reproduce it :id=reproduce

From a clone of the repository (JDK 17 or later to run Gradle; it uses a Java 21 toolchain):

```bash
./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"                 # one benchmark class
./gradlew :benchmark:jmh -Pjmh="CircuitBreakerBenchmark -t 4 -prof gc"   # four threads
./gradlew :benchmark:jmh -PjmhJdk=25                                     # every benchmark on another JDK
./gradlew :benchmark:allocationCheck                                     # the allocation gate
```

Results are written to `benchmark/build/jmh/results.json`. The sources are in `benchmark/src/main/java/dev/resilience4cata/benchmark/`, each with the Resilience4j counterpart and the adjustments made for fairness in its Javadoc. Measure on an idle machine, with several forks, and compare only numbers from the same machine.
