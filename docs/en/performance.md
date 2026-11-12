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
- **Average time per call, in a closed loop** (JMH `AverageTime`): every thread calls again as soon as the last call returns. That hides tails: under contention the rate limiter parks the threads that lose a race, and with four threads saturating one limiter between 0.1 % and 1 % of the calls wait 110 µs or more (Resilience4j's: 910 µs at p99.9). The bulkhead has no such tail. Percentiles: [benchmarks](benchmarks.md#percentiles).
- **ARM only so far**: Graviton3 and Apple M1. x86-64 has not been measured yet; its atomics are full fences and its clock is cheap, so the order of some results may differ there.

## Headline results :id=headline

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, against Resilience4j 2.4.0, nanoseconds and bytes allocated per call. The retry whose first attempt succeeds, the breaker where every call succeeds and the sync timeout are the code of 1.0.0-RC1, from [the release candidate (2026-11-02)](benchmarks.md#release-candidate); the breaker with failing calls, from [realistic failure rates (2026-11-02)](benchmarks.md#failure-rates); the bulkhead with free slots, the code after 0.13.0, from [slots given back with a volatile add (2026-11-01)](benchmarks.md#release-counters), where Resilience4j measured 20.4 ns on one thread and 348 to 450 ns with four; every other row, resilience4cata 0.1.0, from [MVP completion (2026-10-24, AWS Graviton3)](benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3).

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Retry, first attempt succeeds | 1 | **1.4 ns**, 0 B | 22.9 ns, 0 B |
| Retry, fails once then succeeds (Resilience4j without its `sleep(0)`) | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, every call succeeds | 1 | **1.9 ns**, 0 B | 89.9 ns, 0 B |
| Circuit breaker, every call succeeds | 4 | **1.9 ns**, 0 B | 850 ns, 2 B |
| Circuit breaker, one call in 1 000 fails | 4 | **11.2 ns**, 0 B | 1025 ns, 26 B |
| Circuit breaker, one call in 100 fails | 4 | **65.4 ns**, 0 B | 1006 ns, 22–26 B |
| Circuit breaker, one call in ten fails | 4 | **278 ns**, 0 B | 940 ns, 2 B |
| Rate limiter, permits free | 4 | **187 ns**, 0 B | 275 ns, 40 B |
| Rate limiter, exhausted (every call rejected) | 4 | **34.7 ns**, 0 B | 1599 ns, 505 B |
| Bulkhead, slots free | 1 | **17.3 ns**, 0 B | 20.4 ns, 0 B |
| Bulkhead, slots free | 4 | **195.5 ns**, 0 B | 348–450 ns, 0 B |
| Bulkhead, full (every call rejected) | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Timeout, sync call that finishes in time (Resilience4j: `TimeLimiter` + executor) | 1 | **23.6 ns**, 0 B | 8908 ns, 183 B |
| Async timeout, stage still pending | 1 | **152 ns**, 192 B | 598 ns, 240 B |
| Async timeout, stage still pending | 4 | **369 ns**, 192 B | 1563 ns, 234 B |

Reading the table honestly:

- **The circuit breaker's 1.9 ns holds while its window holds no failure.** Then a call only reads one field. Once the last `windowSize` calls include a failure or a slow call, every call writes the shared window cursor. With four threads that costs 11.2 ns at 0.1 % failures, 65.4 ns at 1 % and 278 ns at 10 %, against about 1 000 ns for Resilience4j at every rate: 91×, 15× and 3.4× rather than 450× ([realistic failure rates](benchmarks.md#failure-rates)).
- **Resilience4j does work per call that resilience4cata does not**: its retry counts every call in its built-in metrics (`LongAdder` counters), and its breaker always times calls. resilience4cata keeps no per-call counter outside the breaker's window ([why there are no success metrics](faq.md#success-metrics)).
- **The bulkhead with free slots on one thread was a tie** (20.5 against 20.4 ns, both one atomic to take and one to give back) until the slot was given back with a volatile add, which measured faster than a release-only one on Graviton3: 17.3 ns.
- **Contended paths cost hundreds of nanoseconds in both libraries.** The breaker's mixed case at four threads (278 ns) is dominated by one shared window cursor that every call advances; the bulkhead and rate limiter at four threads pay for one shared counter, and the rate limiter parks the threads that lose a race on it.
- **The sync timeout is the most expensive sync policy** (23.6 ns on one thread; the 0.1.0 headline measured 22.2 ns, which has not been seen since, see [the release candidate](benchmarks.md#release-candidate)). Resilience4j's comparison is not like for like: its `TimeLimiter` hands the call to another thread, which is where most of its 8.9 µs goes.
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

### Mono and Flux methods (Project Reactor) :id=reactor

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, against resilience4j-reactor 2.4.0's operators with the same settings, every call succeeds. From [Flux methods and already-done stages (2026-10-29, AWS Graviton3)](benchmarks.md#flux-and-done-stages).

`Mono` methods, a value there at subscription unless noted:

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Circuit breaker | 1 | **106 ns**, 176 B | 243 ns, 248 B |
| Circuit breaker | 4 | **105 ns**, 176 B | 1291 ns, 240 B |
| Retry, first attempt succeeds | 1 | **135 ns**, 227 B | 725 ns, 1000 B |
| Timeout | 1 | **105 ns**, 176 B | 3184 ns, 1344 B |
| Timeout, value comes later | 1 | **430 ns**, 480 B | 3686 ns, 1797 B |
| Bulkhead | 1 | **126 ns**, 176 B | 157 ns, 200 B |
| Retry + breaker + timeout + bulkhead | 1 | **201 ns**, 376 B | 4319 ns, 2624 B |

`Flux` methods, one whole stream of ten elements unless noted:

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Circuit breaker | 1 | **195 ns**, 283 B | 272 ns, 216 B |
| Circuit breaker | 4 | **189 ns**, 317 B | 1322 ns, 218 B |
| Retry, first attempt succeeds | 1 | **270 ns**, 392 B | 1197 ns, 1024 B |
| Timeout (restarted by every element) | 1 | **681 ns**, 384 B | 28896 ns, 3983 B |
| Timeout, a thousand elements | 1 | **45 µs**, 384 B | 2632 µs, 241529 B |
| Retry + breaker + timeout + bulkhead | 1 | **789 ns**, 616 B | 30583 ns, 5104 B |

- Subscribing with no policy costs 52 ns and 88 B for a `Mono.just`, 56 ns and 96 B for ten elements, on this machine; every row includes it.
- The closest cases: a bulkhead around a `Mono` with four threads, and around a `Flux` with four threads (755 vs 697 ns) or a rate limiter on one (240 vs 234 ns), within the error. A `Flux` timeout costs about 44 ns per element, with no timer per element; Resilience4j's schedules one per element. Reactive methods allocate, as every async path does; the allocation gate tracks their bytes.

### Many services in one application :id=many-services

> [!MEASURED]
> Apple M1, JDK 21, four unrelated services each with its own retry, one call each per operation. From [Retry, four services](benchmarks.md#retry-four-services-2026-10-23).

| Per call | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| Overhead, first attempt succeeds | **0.6 ns**, 0 B | 21.3 ns, 80 B |
| Overhead, fails once then succeeds | **9.4 ns**, 0 B | 273 ns, 16 B (without its `sleep(0)`) |

With four decorated suppliers sharing Resilience4j's call site, its success path starts allocating 80 B per call; resilience4cata's generated classes each have their own call site and stay at 0 B.

## The zero-allocation gate :id=allocation-gate

Allocation is checked on every pull request. `./gradlew :benchmark:allocationCheck` runs the benchmarks listed in `benchmark/allocation-budgets.properties` with `-prof gc` and fails if one allocates its budget + 1 B per call. **Every sync success and rejection path has a budget of 0 B**: retry, circuit breaker (count and time windows, open), rate limiter, bulkhead, timeout, classifiers, generated code and `Pipeline` (also with a capturing lambda). Async paths have budgets for their futures (48 B for a retry, 24 B for another policy whose stage is already complete). One exception the gate does not see: the first timed call on a new thread registers that thread with the timeout's watchdog, about 270 B, which matters with one new virtual thread per request ([a timeout per new virtual thread](benchmarks.md#review-fixes)). Details in [Allocation gate](benchmarks.md#allocation-gate).

Events allocate only when someone listens: an event type without listeners costs one null check on the failure or rejection path, which measured as 0.3 ns on a full bulkhead.

## The performance contract :id=contract

The targets the project holds itself to, and how each one is checked:

| Target | How it is checked |
|---|---|
| **0 B/op** on the success path of every policy and of a full stack (a thread's first timed call: about 270 B, once) | JMH `-prof gc` in CI: `./gradlew :benchmark:allocationCheck` fails a pull request if a listed benchmark reaches its budget + 1 B/op (0 for every sync path; measured values for async futures) |
| No locks and no `synchronized` on hot paths | code review, JMH contention scenarios |
| Monomorphic, straight-line generated code per method; no shared lambda chains | JMH with a polluted JIT profile, `-XX:+PrintInlining` |
| No clock read on a success path unless a feature needs it (rate limiting, slow-call detection, time windows, the adaptive limit's latency, a retry's `maxDuration`); each such policy reads the clock itself, so a stack of them reads it more than once | code review |
| Preallocated, stackless rejection exceptions; events allocated only if someone listens | JMH rejection scenarios (open breaker, full bulkhead, exhausted rate limiter), gated at 0 B/op in CI |
| Correct lock-free structures | jcstress, in CI on x86 and ARM after every push to main and nightly, and weekly in its longer default mode on ARM |
| Published, reproducible comparison against Resilience4j 2.4.0 | the `benchmark` module, with equivalent Resilience4j configuration and fairness notes ([Benchmarks in depth](benchmarks.md)) |

Most real calls are I/O-bound (milliseconds), so the point is overhead close to zero that holds up with many threads, not raw speed alone.

## What makes it cheap :id=why

- **Generated, per-method, straight-line code**: no shared decorator lambda, so the JIT inlines each call site with one receiver type.
- **Nothing allocated on success**, and rejections throw one preallocated, stackless exception per instance.
- **No locks** on any call path, and atomics chosen by measurement: an acquire compare-and-set instead of a full fence to take a slot took the free bulkhead from 23.2 to 9.3 ns on Apple M1 ([Bulkhead, 1 and 4 threads](benchmarks.md#bulkhead-1-and-4-threads-2026-10-23)), while giving it back with a volatile add measured faster than a release-only add on Graviton3 ([release counters](benchmarks.md#release-counters)).
- **No shared writes while healthy**: a breaker whose window holds only fast successes is checked with one read per call.
- **No clock reads** unless a feature needs them (slow-call detection, time windows, rate limiting, the adaptive limit's latency, a retry's `maxDuration`). Timeouts use a shared watchdog thread instead of reading the clock per call.

## Reproduce it :id=reproduce

From a clone of the repository (JDK 17 or later to run Gradle; it uses a Java 21 toolchain):

```bash
./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"                 # one benchmark class
./gradlew :benchmark:jmh -Pjmh="CircuitBreakerBenchmark -t 4 -prof gc"   # four threads
./gradlew :benchmark:jmh -PjmhJdk=25                                     # every benchmark on another JDK
./gradlew :benchmark:allocationCheck                                     # the allocation gate
```

Results are written to `benchmark/build/jmh/results.json`. The sources are in `benchmark/src/main/java/dev/resilience4cata/benchmark/`, each with the Resilience4j counterpart and the adjustments made for fairness in its Javadoc. Measure on an idle machine, with several forks, and compare only numbers from the same machine.
