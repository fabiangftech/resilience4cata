# Benchmarks

> Measured results only. Every number here comes from a run in this repository; anything else is a hypothesis and belongs in the research documents.

## Retry, single thread (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"` — source: `benchmark/src/main/java/dev/resilience4cata/benchmark/RetryBenchmark.java`.

**Setup:** JMH 1.37, JDK 21.0.10, Apple M1 laptop, 3 forks × 5 × 1 s warmup × 5 × 1 s measurement, `-Xms1g -Xmx1g`. Both libraries: 3 attempts, zero delay, no jitter, every exception retried, no event listeners. The failure is a preallocated, stackless exception, so neither library pays for creating it.

| Scenario | Baseline (no library) | resilience4cata 0.1.0-SNAPSHOT | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| First attempt succeeds | 1.14 ± 0.13 ns | **1.55 ± 0.08 ns**, 0 B/op | 12.61 ± 0.10 ns, 0 B/op |
| Fails once, then succeeds | 3.03 ± 1.43 ns | **7.50 ± 0.75 ns**, 0 B/op | 411.6 ± 15.1 ns, 16 B/op |
| Same, Resilience4j without its `Thread.sleep(0)` | — | — | 38.8 ± 3.6 ns, 16 B/op |

**Overhead over the baseline:**
- Success path: about **0.4 ns** for resilience4cata vs **11.5 ns** for Resilience4j.
- One retry: about **4.5 ns** vs **36 ns** for Resilience4j's retry machinery alone, or **409 ns** as Resilience4j behaves by default.

**Reading these numbers:**
- Resilience4j 2.4.0 calls `Thread.sleep` between attempts even when the delay is zero (verified in its bytecode). That accounts for most of the 411 ns. The `NoSleep` variant removes it with Resilience4j's own `RetryImpl.setSleepFunction`, to isolate the cost of the rest.
- This is a best case for Resilience4j: one decorated supplier, so the JIT sees a single type at every call site and escape analysis removes its per-call objects on the success path. Real applications decorate many methods. The research predicts larger differences with a polluted JIT profile ([03 §7.4](research/03-performance-internals.md)); that scenario is not measured yet.
- Single thread only. Retry has no shared mutable state, so contention is not expected to matter for it; it will for the circuit breaker and rate limiter.
- One laptop, one JDK. Pending: JDK 25, x86-64, polluted profile.

## Retry, four services (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="MultiTypeRetryBenchmark -prof gc"` — source: `benchmark/src/main/java/dev/resilience4cata/benchmark/MultiTypeRetryBenchmark.java`.

Same setup and settings as above, but with four unrelated services (own interface, own implementation class), each called from its own call site, as in a real application. **One operation calls the four services**; the "per call" columns divide by four.

| Scenario | Baseline | resilience4cata | Resilience4j 2.4.0 | Resilience4j, no `Thread.sleep(0)` |
|---|---:|---:|---:|---:|
| First attempt succeeds, per op | 1.87 ± 0.03 ns | **4.39 ± 0.11 ns**, 0 B | 87.1 ± 4.0 ns, 320 B | — |
| ↳ overhead per call | — | **0.6 ns**, 0 B | 21.3 ns, 80 B | — |
| Fails once, then succeeds, per op | 3.57 ± 0.09 ns | **41.1 ± 0.9 ns**, 0 B | 2794 ± 128 ns, 64 B | 1094 ± 13 ns, 64 B |
| ↳ overhead per call | — | **9.4 ns**, 0 B | 698 ns, 16 B | 273 ns, 16 B |

**What changed compared with the single-service run:**
- **Resilience4j starts allocating on the success path: 80 B per call**, where the single-service run showed 0 B. All four decorated suppliers go through the same `supplier.get()` call site inside `Retry.decorateSupplier`. The JIT can no longer inline across it, so escape analysis stops removing Resilience4j's per-call objects. This is the effect predicted in [03 §7.4](research/03-performance-internals.md), now measured.
- **resilience4cata stays at 0 B** and its success-path overhead barely moves (0.4 → 0.6 ns per call): each generated class has its own call site, with a single receiver type.
- Its retry path goes from 4.5 to 9.4 ns per call. Resilience4j's goes from 36 to 273 ns per call without the sleep: a failure that crosses frames the JIT could not inline is much more expensive to unwind.

Still pending: JDK 25, x86-64, more than one thread.

## Circuit breaker, 1 and 4 threads (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="CircuitBreakerBenchmark -t 4 -prof gc"` — source: `benchmark/src/main/java/dev/resilience4cata/benchmark/CircuitBreakerBenchmark.java`.

Same machine and JMH settings as above. Both libraries: count-based window of 100 calls, evaluated after 100 calls, opening at 50% failures, no event listeners. All threads share **one** breaker. Resilience4j is measured with both window strategies: `SYNCHRONIZED` (its default) and `LOCK_FREE`. In the mixed scenario one call in ten fails, so the breaker stays closed while its window records failures. Difference in defaults: Resilience4j always times calls for slow-call detection; resilience4cata does not unless `slowCallDuration` is set.

| Scenario | Threads | Baseline | resilience4cata | R4j `SYNCHRONIZED` | R4j `LOCK_FREE` |
|---|---:|---:|---:|---:|---:|
| Every call succeeds | 1 | 1.10 ns | **2.03 ± 0.03 ns**, 0 B | 66.4 ± 1.7 ns, 16 B | 124 ± 16 ns, 224 B |
| One in ten fails | 1 | 1.91 ns | **11.4 ± 0.3 ns**, 0 B | 95.0 ± 4.6 ns, 7 B | 135 ± 7 ns, 243 B |
| Every call succeeds | 4 | 1.44 ns | **3.16 ± 0.08 ns**, 0 B | 477 ± 41 ns, 1 B | 1074 ± 96 ns, 776 B |
| One in ten fails | 4 | 2.82 ns | **202 ± 21 ns**, 0 B | 601 ± 13 ns, 1 B | 1103 ± 64 ns, 768 B |

(Times per call; B is bytes allocated per call.)

**Overhead over the baseline:** about **0.9 ns** per successful call on one thread (Resilience4j: 65 ns) and **1.7 ns** with four threads sharing the breaker (Resilience4j: 476 ns).

**How the success path got there.** The first working version cost 9.5 ns on one thread and 244 ns with four threads. Each step was measured:

| Change | 1 thread, success | 4 threads, success | 4 threads, mixed |
|---|---:|---:|---:|
| First version: atomic counters in separate objects, acquire reads | 9.5 ns | 244 ns | 500 ns |
| Skip writes when the window holds only fast successes | 9.5 ns | 10.2 ns | 259 ns |
| Opaque instead of acquire reads (safe: everything reached is `final`) | 5.7 ns | — | — |
| Counters as fields of the window, no pointer hops | 4.9 ns | — | — |
| `quiet` field: one read per call while healthy | 2.0 ns | 3.1 ns | 317–351 ns ⚠ |
| Write `quiet` only when it changes; cursor in its own object; no `completed` counter while closed | 2.0 ns | 3.2 ns | 202–230 ns |

- **Under contention, shared writes dominate, not locks.** Writing a cache line that other cores read costs more than a lock-free design saves. The biggest win came from not writing at all when nothing changes.
- **On ARM, acquire reads are not free.** Each one became an `ldar`; five per call cost about 4 ns.
- **Every change was checked in all four scenarios.** Two of them made the mixed four-thread case worse before they were fixed (⚠ row): co-locating the cursor with the counters, and rewriting `quiet` on every failure, both put per-call writes on a line that every call reads.
- **The mixed case with four threads remains the bottleneck** (about 200 ns): every call advances one shared cursor so that the window keeps exactly the last 100 calls. Relaxing that semantics (for example striped windows) could remove it; that is a design decision, not done.
- Resilience4j's `LOCK_FREE` strategy is slower than its default in every scenario here and allocates 200–800 B per call.
- One laptop (Apple M1: 4 performance + 4 efficiency cores), JDK 21. Pending: JDK 25, x86-64, 8+ threads.

## Circuit breaker, time-based window (2026-10-23)

Same benchmark class (`*Time*` methods): a 10-second window, evaluated after 100 calls, 50% threshold; Resilience4j `TIME_BASED` with its default `SYNCHRONIZED` strategy.

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Every call succeeds | 1 | **46.6 ± 0.3 ns**, 0 B | 111.4 ± 0.5 ns, 0 B |
| One in ten fails | 1 | **69 ± 19 ns**, 0 B | 135.3 ± 0.8 ns, 0 B |
| Every call succeeds | 4 | **164 ± 28 ns**, 0 B | 703 ± 52 ns, 41 B |
| One in ten fails | 4 | **191 ± 11 ns**, 0 B | 744 ± 53 ns, 41 B |

**The clock dominates.** A time-based window has to read the clock on every call, and on this machine `System.nanoTime()` alone costs **21 ns on one thread and 163 ns with four threads** (measured separately). On macOS, HotSpot keeps `nanoTime` monotonic across threads with a compare-and-set on one global value, so concurrent callers contend on it; on Linux it is a vDSO call with no shared state. Without the clock, resilience4cata's time window costs roughly 25 ns on one thread and a few nanoseconds with four. Every counter is striped (`LongAdder`) so that successes do not contend; the only allocation is one bucket per second.

Two consequences:
- The count-based window, which reads no clock unless `slowCallDuration` is set, is much cheaper wherever both fit. It is the default.
- A cached, coarse clock (updated by a background thread every millisecond) would remove most of this cost. It is not done: it means a daemon thread per JVM and millisecond resolution.

## Timeout, 1 and 4 threads (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="TimeoutBenchmark -t 4 -prof gc"` — source: `benchmark/src/main/java/dev/resilience4cata/benchmark/TimeoutBenchmark.java`.

Calls that finish in time (trivial work, 1 s timeout). resilience4cata runs the call on the caller's thread and a shared watchdog would interrupt it if it ran late. Resilience4j's `TimeLimiter` needs a `Future`, so a blocking call has to be handed to another thread: that is how it is used for synchronous code, and what is measured (a fixed pool of 4 platform threads).

| Threads | Baseline | resilience4cata | Resilience4j 2.4.0 (`TimeLimiter` + executor) |
|---:|---:|---:|---:|
| 1 | 1.10 ns | **15.6 ± 0.1 ns**, 0 B | 9 225 ± 1 353 ns, 189 B |
| 4 | 1.44 ns | **20.2 ± 2.4 ns**, 0 B | 19 194 ± 3 763 ns, 160 B |

- About **14.5 ns of overhead per call**, roughly 600× less than handing the call to another thread. No clock read and no shared write per call: each thread writes only its own reusable scope.
- This is more than the other policies cost (retry 0.4 ns, circuit breaker 0.9 ns). Likely candidates, not investigated yet: the `ThreadLocal` lookup for the thread's scopes and the compare-and-set when the call ends.
- The price of not reading the clock is precision: the watchdog ticks every `timeout / 16` of the shortest timeout in use (1 to 50 ms) and interrupts a call between its timeout and about two ticks later, never earlier.

## Bulkhead, 1 and 4 threads (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="BulkheadBenchmark -t 4 -prof gc"` — source: `benchmark/src/main/java/dev/resilience4cata/benchmark/BulkheadBenchmark.java`.

All threads share one bulkhead. *Free*: a limit of 1000 that is never reached, no waiting. *Waiting*: the same limit with `maxWait = 100ms`, so resilience4cata uses its `Semaphore` mode. *Full*: a limit of 1 held by the setup, so every call is rejected. Resilience4j: `SemaphoreBulkhead`, same limits, stack traces disabled on its rejection exception.

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Free | 1 | **9.3 ± 0.2 ns**, 0 B | 22.9 ± 0.1 ns, 0 B |
| Free | 4 | **324 ± 15 ns**, 0 B | 445 ± 4 ns, 0 B |
| Waiting mode, free | 1 | 23.2 ± 0.3 ns, 0 B | 23.4 ± 1.2 ns, 0 B |
| Waiting mode, free | 4 | 447 ± 6 ns, 0 B | 451 ± 12 ns, 0 B |
| Full (every call rejected) | 1 | **2.4 ± 0.6 ns**, 0 B | 143 ± 11 ns, 464 B |
| Full (every call rejected) | 4 | **3.3 ± 0.4 ns**, 0 B | 267 ± 41 ns, 464 B |

- **Rejections are 60–80× cheaper**: a full bulkhead only reads its counter (no write, so no contention) and throws a preallocated exception. Under overload, which is when a bulkhead matters, that keeps the rejection path from becoming a bottleneck itself.
- **Free slots: 9.3 ns vs 22.9 ns on one thread.** The first version measured 23.2 ns, the same as Resilience4j: it used full-fence (volatile) atomics. Taking a slot only needs *acquire* and freeing it *release* semantics, the same as a lock; on ARM that turns `casal`/`ldaddal` into cheaper instructions and brought it to 9.3 ns (421 → 324 ns with four threads).
- **With four threads, both libraries pay for contention** (300–450 ns): an exact global limit needs one shared counter written twice per call. Splitting the limit across stripes could reduce it, at the price of a limit that is only enforced per stripe; not done.
- The waiting mode uses the JDK's `Semaphore`, as Resilience4j does, so it costs the same.
- The same weaker-ordering change may apply to the timeout's end-of-call compare-and-set and to the circuit breaker's window updates; not measured yet.

## Rate limiter, 1 and 4 threads (2026-10-24)

`./gradlew :benchmark:jmh -Pjmh="RateLimiterBenchmark -t 4 -prof gc"` — source: `benchmark/src/main/java/dev/resilience4cata/benchmark/RateLimiterBenchmark.java`.

All threads share one limiter, and neither library waits for a permit. *Free*: one billion permits per second, never reached, so every call is let through. *Exhausted*: one permit per hour, taken by the setup, so every call is rejected. Resilience4j: its default `AtomicRateLimiter`, same limits, stack traces disabled on its rejection exception. Both libraries read `System.nanoTime()` once per call.

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Free | 1 | **25.3 ± 0.4 ns**, 0 B | 57.5 ± 5.2 ns, 40 B |
| Free | 4 | **131 ± 1 ns**, 0 B | 206 ± 7 ns, 40 B |
| Exhausted (every call rejected) | 1 | **22.9 ± 0.1 ns**, 0 B | 243 ± 12 ns, 560 B |
| Exhausted (every call rejected) | 4 | **164 ± 1 ns**, 0 B | 1763 ± 60 ns, 524 B |

- **Most of resilience4cata's cost is the clock.** `System.nanoTime()` costs 21 ns on one thread and 163 ns with four on this machine (see the time-window section above); the limiter itself adds about 2–4 ns on one thread. A rejection only reads, so with four threads an exhausted limiter costs exactly what the contended clock costs. On Linux, where `nanoTime` has no shared state, these numbers should be much lower; not measured yet.
- **Rejections are 10× cheaper and allocate nothing**; Resilience4j allocates a new `State` per attempt and ~520 B per rejected call.
- **Contention: the first version lost with four threads, 658 ns vs 214 ns.** Every admitted call writes the one shared `tat`, and callers that lost the compare-and-set retried at once, so the cache line bounced between cores. Changing the atomic (strong, weak, acquire) changed nothing; spinning with `onSpinWait` (once or with exponential backoff) and `Thread.yield()` barely helped (565–665 ns and 414 ns). Parking for 1 ns after a lost compare-and-set, as Resilience4j does, brought it to **131 ns** (351 ns vs Resilience4j's 523 ns with eight threads). The cost moves to the caller that lost the race: on Linux a platform thread's shortest park is about 50 µs of timer slack. It only happens when admitted calls arrive faster than the cache line can move between cores, which is far above any rate a limiter is normally configured for.

## Async methods, 1 thread (2026-10-24)

`./gradlew :benchmark:jmh -Pjmh="AsyncBenchmark -prof gc"` — source: `benchmark/src/main/java/dev/resilience4cata/benchmark/AsyncBenchmark.java`.

Success path only: the delegate returns an already completed `CompletableFuture`, and each benchmark returns the stage the caller gets. Resilience4j uses its `decorateCompletionStage` variants with the same settings; its retry and time limiter get a one-thread `ScheduledExecutorService`. *Stack* nests retry, circuit breaker, timeout and bulkhead in the same order in both libraries. The circuit breaker, rate limiter, timeout and stack rows were rerun with 6 forks because the first run was noisy (±137 ns on resilience4cata's breaker).

| Policy | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| Circuit breaker | **18.8 ± 0.5 ns**, 48 B | 85.8 ± 3.7 ns, 71 B |
| Retry | **19.3 ± 1.6 ns**, 48 B | 28.7 ± 1.4 ns, 48 B |
| Bulkhead | **28.1 ± 0.8 ns**, 48 B | 33.1 ± 9.5 ns, 48 B |
| Rate limiter | **48.0 ± 1.6 ns**, 48 B | 63.6 ± 1.3 ns, 88 B |
| Timeout | **213 ± 19 ns**, 200 B | 3551 ± 1772 ns, 184 B |
| Stack (retry, breaker, timeout, bulkhead) | **344 ± 21 ns**, 362 B | 3600 ± 2205 ns, 352 B |

- **Allocation is about the same as Resilience4j's**: each layer returns a new future (48 B). Async cannot be allocation-free; the sync path is unaffected by this work.
- **The timeout dominates both libraries.** Each call schedules a task on a `ScheduledThreadPoolExecutor` and cancels it when the stage completes: a lock and a heap insert and removal. resilience4cata removes cancelled tasks at once (`setRemoveOnCancelPolicy`). Resilience4j's numbers vary widely between forks (1.8–5.3 µs); not investigated. A timer that does not lock, like the sync watchdog, should bring resilience4cata's 213 ns down; not done yet.
- The rate limiter row includes one `System.nanoTime()` (≈21 ns, see above); the breaker reads no clock unless `slowCallDuration` is set.
