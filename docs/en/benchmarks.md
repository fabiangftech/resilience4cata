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

## Timer rewrite and async bulkhead wait (2026-10-24)

Same machine and settings. Run after the parallel work on the async timer (`TimeoutTimer`), the sync timeout protocol and the async bulkhead wait was integrated, one benchmark at a time on an idle machine.

**Async timeout.** `*TimeoutPending` returns a future that is still pending, which the benchmark completes right after the call, so the timer's schedule and cancel are measured; `baselinePending` is the same without any library (14.5 ns, 24 B). Resilience4j gets a remove-on-cancel scheduler there. `*Timeout` returns an already completed future: resilience4cata recognises it and skips the timer (it cannot time out).

| Scenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Pending stage | 1 | **127 ± 10 ns**, 192 B | 257 ± 11 ns, 234 B |
| Pending stage | 4 | **510 ± 35 ns**, 192 B | 928 ± 60 ns, 234 B |
| Completed stage | 1 | **7.1 ± 0.2 ns**, 24 B (was 213 ns) | 3795 ± 3163 ns, 181 B |
| Completed stage | 4 | **11.2 ± 0.6 ns**, 24 B | 3173 ± 1003 ns, 180 B |

- The lock-free timer halves the cost of a timed async call against Resilience4j's scheduler, even when Resilience4j is given remove-on-cancel. With four threads both pay for shared state: resilience4cata's per-thread-id inbox stripes and the result future's completion.
- Behaviour change: async timeouts are now tick-based like sync ones (never early, at most about two ticks late, tick = shortest async timeout / 16 within 1–50 ms), where the `ScheduledThreadPoolExecutor` was sub-millisecond precise.

**Sync timeout** (`TimeoutBenchmark`), after removing one ThreadLocal lookup per call and using an acquire CAS at the end of the call:

| Threads | Before | After | Resilience4j 2.4.0 |
|---:|---:|---:|---:|
| 1 | 15.6 ns | **13.1 ± 0.1 ns** | 9039 ± 439 ns |
| 4 | 20.2 ns | **15.9 ± 0.7 ns** | 18423 ± 771 ns |

The overhead over the baseline drops from about 14.5 to 12 ns on one thread. A release-only CAS would be cheaper still but allows an early interrupt (see the comment in `TimeoutPolicy.end`), so it is not used.

**Bulkhead, sync path after the async wait queue** (`BulkheadBenchmark`): `exit()` with `maxWait > 0` now does one more volatile read, to see whether async callers are queued. No measurable cost: *Waiting* 23.1 ± 0.05 ns (was 23.2) on one thread and 442 ± 3 ns (was 447) on four; *Free* 9.2 ns and *Full* 2.2 ns, unchanged.

## Parallel runs on AWS (`infra/`)

`infra/bench.sh <base-rev> <new-rev> [include-regex]` runs every benchmark on temporary EC2 machines in parallel and compares two git revisions. A full run (150 benchmark/thread-count units, 283 JMH runs) takes about 20 minutes end to end instead of about 3 hours on one machine, and costs well under US$1.

- **Setup.** Each revision is compiled locally in a fresh clone; its benchmark runtime classpath is packaged and uploaded to a per-run S3 bucket, so the machines only install a JDK (Amazon Corretto 21) and run `org.openjdk.jmh.Main`. Terraform (`infra/main.tf`) creates a throwaway VPC, the bucket, an instance role limited to that bucket, and the machines; `bench.sh` destroys everything at the end, also on failure.
- **Fairness.** A unit is one benchmark method at one thread count. Its base and new runs execute back to back on the same machine (alternating which goes first), so every base/new comparison is made on the same hardware. Units are spread longest-first over the least-loaded machine (`infra/plan.py`).
- **Hardware.** `c7g.xlarge`: 4 Graviton3 cores without SMT and with a fixed clock (no turbo), which keeps machines interchangeable: every machine also runs one calibration benchmark (`BulkheadBenchmark.resilience4cataFree`, 1 thread), and across 19 machines it measured 20.45–20.47 ns (0.1% spread). JMH settings are the benchmarks' own (3 forks × 5 × 1 s warm-up and measurement, `-prof gc`).
- **Account limits.** This account allows only 3 spot instances at a time (separately from its 96-vCPU spot quota) and 64 on-demand vCPUs, so a run uses 3 spot and 16 on-demand machines of the same type (`MACHINES`, `SPOT_MACHINES`).
- Graviton numbers are not comparable with the Apple Silicon numbers in the sections above; compare base and new within one run.

## MVP completion: classifier, pipeline, events, testkit (2026-10-24, AWS Graviton3)

Base: commit `3bc496f`. New: the integrated work of the classifier, programmatic `Pipeline`, events and snapshots, and testkit (`PolicyEnvironment`). Measured with `infra/bench.sh` as described above. Two regressions found in the first run were fixed and measured again; the table shows the final numbers.

**No regression check** (resilience4cata, ns/op, all 0 B/op on sync paths):

| Benchmark | Threads | Base | New | Change |
|---|---:|---:|---:|---:|
| Retry, success | 1 | 1.4 ± 0.0 | 1.2 ± 0.1 | −11% |
| Retry, success | 4 | 1.3 ± 0.0 | 1.4 ± 0.1 | +3% |
| Retry, one retry | 1 | 7.3 ± 0.1 | 6.5 ± 0.1 | −11% |
| Retry, one retry | 4 | 7.3 ± 0.1 | 6.6 ± 0.1 | −10% |
| Retry, four services, one retry | 1 | 38.4 ± 0.1 | 38.8 ± 0.1 | +1% |
| Retry, four services, one retry | 4 | 39.2 ± 0.6 | 37.6 ± 1.2 | −4% |
| Circuit breaker, success | 1 | 1.9 ± 0.0 | 1.9 ± 0.0 | 0% |
| Circuit breaker, success | 4 | 1.9 ± 0.0 | 1.9 ± 0.0 | 0% |
| Circuit breaker, mixed | 1 | 17.5 ± 0.1 | 17.2 ± 0.0 | −2% |
| Circuit breaker, mixed | 4 | 282 ± 10 | 274 ± 29 | −3% |
| Circuit breaker (time window), mixed | 4 | 126 ± 9 | 130 ± 10 | +3% |
| Rate limiter, free | 1 | 42.2 ± 0.1 | 41.8 ± 0.1 | −1% |
| Rate limiter, free | 4 | 188 ± 2 | 187 ± 2 | 0% |
| Rate limiter, exhausted | 4 | 35.0 ± 0.3 | 34.7 ± 0.2 | −1% |
| Bulkhead, free | 1 | 20.5 ± 0.0 | 20.5 ± 0.0 | 0% |
| Bulkhead, full (rejecting) | 1 | 2.1 ± 0.1 | 2.3 ± 0.0 | **+12%** |
| Bulkhead, full (rejecting) | 4 | 2.0 ± 0.0 | 2.3 ± 0.0 | **+15%** |
| Bulkhead, waiting mode | 1 | 18.9 ± 0.0 | 18.9 ± 0.0 | 0% |
| Bulkhead, waiting mode | 4 | 359 ± 36 | 338 ± 38 | −6% |
| Timeout (sync) | 1 | 22.4 ± 0.2 | 22.2 ± 0.2 | −1% |
| Timeout (sync) | 4 | 23.5 ± 0.3 | 23.5 ± 0.5 | 0% |
| Async circuit breaker | 1 | 24.4 ± 0.1 | 24.4 ± 0.1 | 0% |
| Async retry | 1 | 24.1 ± 0.1 | 24.1 ± 0.1 | 0% |
| Async rate limiter | 4 | 275 ± 1 | 273 ± 6 | −1% |
| Async timeout, pending stage | 1 | 152 ± 17 | 152 ± 12 | 0% |
| Async stack (five policies) | 1 | 122 ± 0.3 | 122 ± 0.2 | 0% |

- **Bulkhead rejections cost 0.3 ns more** (also measured on Apple Silicon: 2.06 → 2.36 ns). A full bulkhead now checks the `final` event-listener field before returning `false`: the price of rejection events. Success paths have no such check.
- **Fixed: contended retry failures.** The first run showed *four services, one retry* at 4 threads going from 37 to 57 ± 22 ns, with forks split between ~40 and 60–85 ns. `RetryPolicy` had gained a second `ClassValue` (for classifier verdicts) that was never queried without a classifier. Every `ClassValue` shares the exception class's small lookup cache, and in some JVMs the extra one made lookups collide and fall back to `ClassValue`'s slower path, which writes to that shared cache. One `ClassValue` per policy, returning a three-way verdict, restored uniform forks (39.2 → 37.6 ns).
- **Fixed: sync timeout, one thread** (22.4 → 23.8 ns, reproduced twice). The timed-out branch of `TimeoutPolicy.end` had grown by the event call, which affects how the JIT inlines the per-call method. Moving that branch into its own method brought it back to 22.2 ns.
- In the first run, *Bulkhead, waiting mode* at 4 threads showed 366 → 452 ns; a repeat run gave 359 → 338 ns, so that difference was noise between JVM runs, not a regression.

**Against Resilience4j 2.4.0 on Graviton3** (new revision, ns/op and B/op):

| Benchmark | Threads | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| Retry, success | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Retry, one retry (Resilience4j without its `sleep(0)`) | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, success (Resilience4j: count window, default `SYNCHRONIZED` strategy) | 1 | **1.9 ns**, 0 B | 89.2 ns, 0 B |
| Circuit breaker, success | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Circuit breaker, mixed | 4 | **274 ns**, 0 B | 910 ns, 2 B |
| Rate limiter, free | 4 | **187 ns**, 0 B | 275 ns, 40 B |
| Rate limiter, exhausted | 4 | **34.7 ns**, 0 B | 1599 ns, 505 B |
| Bulkhead, free | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |
| Bulkhead, free | 4 | **264 ns**, 0 B | 432 ns, 0 B |
| Bulkhead, full | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Timeout (sync) | 1 | **22.2 ns**, 0 B | 8842 ns, 176 B |
| Async timeout, pending stage | 1 | **152 ns**, 192 B | 598 ns, 240 B |
| Async timeout, pending stage | 4 | **369 ns**, 192 B | 1563 ns, 234 B |

**Classifier** (`ClassifierBenchmark`: a result-based classifier on the protected call, all calls succeed):

| Benchmark | Threads | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| Circuit breaker, no classifier | 1 | 2.1 ns, 0 B | — |
| Circuit breaker + classifier | 1 | **2.4 ns**, 0 B | 102 ns, 24 B (`recordResult`) |
| Circuit breaker + classifier | 4 | **2.4 ns**, 0 B | 902 ns, 18 B |
| Retry + circuit breaker + classifier | 1 | **2.7 ns**, 0 B | 148 ns, 104 B (`retryOnResult` + `recordResult`) |
| Retry + circuit breaker + classifier | 4 | **2.8 ns**, 0 B | 1274 ns, 90 B |

A classifier costs about 0.3 ns per policy on a successful call: one direct, monomorphic `classify` call.

**Programmatic `Pipeline`** (`PipelineBenchmark`, ns/op; generated code and pipeline allocate 0 B):

| Policies | Threads | Generated (annotations) | `Pipeline` | Resilience4j |
|---|---:|---:|---:|---:|
| Retry + circuit breaker | 1 | 2.3 | 2.9 | 175, 288 B |
| Retry + circuit breaker | 4 | 2.4 | 3.0 | 1647, 830 B |
| Retry, breaker, rate limiter, bulkhead | 1 | 61.6 | 62.3 | 261, 328 B |
| Retry, breaker, rate limiter, bulkhead | 4 | 318 | 294 | 1234, 350 B |
| All six policies | 1 | 76.3 | 80.3 | — |
| Capturing lambda (retry + breaker) | 1 | 2.4 | 2.8 | 190, 336 B |

- The pipeline adds about 0.5 ns over generated code on these stacks and stays allocation-free, also with a lambda that captures an argument on every call (escape analysis removes it).
- `pipelinePolluted` (the same pipeline after five other pipelines ran through `Pipeline.execute`) measures 7.9 ns on one thread against 2.9 ns unpolluted: the single `call.call()` site inside `Pipeline` turns megamorphic in an application with many pipelines. Generated code has one call site per method and does not have this cost.
- Resilience4j has no synchronous caller-thread timeout, so there is no Resilience4j column for six policies.

## Annotated classes, generics, config overrides, ticker bulkhead (2026-10-24, AWS Graviton3)

Base: commit `53a6651`. New: annotated classes (subclass style), `@Fallback` on generic methods and wildcard async stages, `onExhaustedResult` / `GaveUp` on classifier failure / `Pipeline.run` and async pipeline fallbacks, `config` per-instance overrides, and the sync bulkhead's `maxWait` on a manual ticker. Same method as the previous section (`infra/bench.sh`, 19 `c7g.xlarge`, 160 units, 329 runs; calibration 20.51–20.54 ns, 0.1% spread).

**No regression check** (resilience4cata, ns/op, 1 thread unless noted; all 0 B/op on sync paths):

| Benchmark | Base | New | Change |
|---|---:|---:|---:|
| Retry, success | 1.3 ± 0.0 | 1.3 ± 0.0 | +2% |
| Retry, one retry | 6.5 ± 0.0 | 6.5 ± 0.0 | +1% |
| Retry, four services, one retry (4 threads) | 37.8 ± 0.9 | 37.7 ± 0.5 | 0% |
| Circuit breaker, success | 1.8 ± 0.0 | 1.9 ± 0.0 | +1% |
| Circuit breaker, mixed | 17.2 ± 0.0 | 17.2 ± 0.0 | 0% |
| Rate limiter, free | 41.7 ± 0.1 | 41.7 ± 0.1 | 0% |
| Rate limiter, exhausted | 34.2 ± 0.1 | 34.6 ± 0.1 | +1% |
| Bulkhead, free | 20.5 ± 0.0 | 20.5 ± 0.0 | 0% |
| Bulkhead, full | 2.3 ± 0.0 | 2.3 ± 0.0 | −1% |
| Bulkhead, waiting mode | 18.9 ± 0.0 | 18.9 ± 0.0 | 0% |
| Classifier on retry + breaker | 2.8 ± 0.0 | 2.7 ± 0.0 | −3% |
| Async circuit breaker | 24.4 ± 0.1 | 24.4 ± 0.1 | 0% |
| Async timeout, pending stage | 149 ± 12 | 149 ± 12 | 0% |
| Async stack (five policies) | 122 ± 0.2 | 122 ± 0.2 | 0% |
| Timeout (sync) | 22.1 ± 0.2 | 23.7 ± 0.1 | **+7%** |
| Timeout (sync), 4 threads | 23.6 ± 0.2 | 23.4 ± 0.3 | −1% |

- Everything at one thread is within ±2% except the sync timeout. At four threads the differences go both ways within their error bars (for example *Bulkhead, free* 258 → 228 ± 32 ns, *Circuit breaker, mixed* 257 → 271 ± 22 ns).
- **Open: sync timeout +1.5 ns on one thread.** It is stable (6 forks: 22.1–22.4 vs 23.6–23.8 ns, also with `-XX:-TieredCompilation`: 22.3–22.5 vs 23.6–23.8 ns), but the code it runs did not change: the generated `TimedService$Resilient`, `TimeoutPolicy` and `Watchdog` are identical, the policy's resolved state is identical, and the watchdog's `ThreadLocal` lands in its home slot in both builds. Four threads show no difference. The previous section's "fix" for the same number (moving the timed-out branch out of `end`) may have been the same layout effect going the other way. Next step: `-prof perfasm` on the machines (needs `perf` and `hsdis`) to compare the compiled code.
- The classified retry layer gained a `try`/`catch` around `classify` (for `GaveUp` on a throwing classifier); the classifier benchmarks show no cost.

**New in this round** (ns/op, 0 B/op):

| Benchmark | Threads | Result |
|---|---:|---:|
| Retry, success: annotated class (subclass) vs interface wrapper | 1 | **1.0** vs 1.2 |
| Retry, one retry: annotated class vs interface wrapper | 1 | **5.6** vs 6.5 |
| Retry, one retry: annotated class vs interface wrapper | 4 | **5.5** vs 6.6 |
| `Pipeline.run` (void call), retry + circuit breaker | 1 | 3.0 (`execute`: 2.9) |

- The subclass style is slightly faster than the wrapper: the override calls `super.method()` directly instead of going through a `delegate` field, one load and one interface call fewer.
- `Pipeline.run` costs the same as `execute` and allocates nothing.
- Not measured, by design: config per-instance overrides (resolved once at creation) and the bulkhead's ticker mode (test clocks only; the default path adds one `final` boolean read after a failed `tryAcquire`, and *Bulkhead, waiting mode* is unchanged).

## Retry budget, 0.3.0 (2026-10-25, AWS Graviton3)

Base: `6d82305` (0.2.0). New: the 0.3.0 integration (retry budget, Micrometer, OpenTelemetry and JUnit modules, `onInstanceCreated`). Same method as above (`infra/bench.sh`, `c7g.xlarge`; calibration 20.48–20.53 ns, 0.2% spread). Every sync path stays at 0 B/op.

**No regression without a budget** (ns/op, 1 / 4 threads):

| Benchmark | Base | New |
|---|---:|---:|
| `RetryBenchmark.resilience4cataSuccess` | 1.4 / 1.3 | 1.2 / 1.3 |
| `MultiTypeRetryBenchmark.resilience4cataSuccess` | 4.3 / 4.3 | 3.9 / 4.0 |
| `PipelineBenchmark.pipelineRetryCircuitBreaker` | 2.9 / 2.9 | 2.9 / 2.9 |
| `RetryBenchmark.resilience4cataRetryOnce` | 6.7 / 6.5 | 6.8 / 6.7 |
| `SubclassBenchmark.wrapperRetryOnce` | 6.6 / 6.5 | 6.8 / 6.8 |

A retried call costs 0.1–0.3 ns more (the null check of the budget on the failure path); successes are unchanged.

**The budget's per-call counting** (`RetryBudgetBenchmark`, `budgetPercent = 1000` so every retry is granted; ns/op, 1 / 4 threads; without a budget: 1.3 / 1.3 success, 6.9 / 7.1 retried):

| How a call is counted | Success | Retried once |
|---|---:|---:|
| Compare-and-set loop on the thread's stripe (`threadId & mask`) | 9.7 / 9.8 | 64 / 380 |
| One atomic add (`getAndAddRelease`, LDADD) on a hashed stripe | 12.0 / 12.0 | 73 / 400 |
| **Plain read-and-write on a hashed stripe (shipped)** | **3.5 / 3.6** | **62 / 301** |

Two lessons. A compare-and-set, even uncontended on a line no other thread writes, cost about 8 ns per call on Graviton3, and the single-instruction atomic add more; the shipped counter is lossy instead (two threads that share a stripe and race can lose a call, which only lowers the budget). And taking the stripe from the low bits of the thread id put every JMH worker on one stripe (their ids were multiples of the stripe count): the compare-and-set version paid for it in contention (380 ns), the lossy one lost so many calls that the budget refused retries even with ten times the needed margin. Mixing the id first (Fibonacci hashing) fixed both. With 4 threads, a retried call is dominated by the shared retry counter and the clock read, on the failure path only.

## Hedge and async cancellation, 0.4.0 (2026-10-25, AWS Graviton3)

Base: `4fc079a` (0.3.0 plus docs). New: the 0.4.0 integration (downward cancellation in every async policy, `@Hedge`, generated subclasses no longer `final`). Same method as above (`infra/bench.sh`, `c7g.xlarge`; calibration 20.51–20.54 ns).

**Async cancellation.** The first version linked each policy's future to its inner stage with a release store in the constructor. On Graviton3 C2 compiled that into two extra full barriers (`dmb ish`) per policy layer, about 5 ns each (async circuit breaker 24.4 → 29.1 ns); on Apple Silicon the same barriers were within noise. A plain store is enough (the future is always handed on with a happens-before) and brings the cost down to about 0.5 ns per layer (ns/op, 1 / 4 threads; allocation unchanged):

| Benchmark | Base | Release store | Plain store (shipped) |
|---|---:|---:|---:|
| `AsyncBenchmark.resilience4cataCircuitBreaker` | 24.4 / 24.9 | 29.1 / 29.9 | 24.9 / 25.6 |
| `AsyncBenchmark.resilience4cataRetry` | 24.1 / 24.6 | 28.9 / 29.5 | 24.7 / 25.3 |
| `AsyncBenchmark.resilience4cataBulkhead` | 41.8 / 392 | 46.2 / 503 | 42.8 / 418 |
| `AsyncBenchmark.resilience4cataRateLimiter` | 66.0 / 275 | 71.2 / 294 | 66.9 / 278 |
| `AsyncBenchmark.resilience4cataStack` | 121.5 / 573 | 139.3 / 554 | 123.5 / 558 |

A refactor during the same change also moved the bulkhead's async callback into a shared helper; the extra call level stopped C2 from inlining the stacked path, and escape analysis no longer removed one object (the four-policy stack went from 200 to 224 B/op). The allocation gate caught it; the callback is back in one method.

**Hedge** (new, no base; ns/op and B/op, 1 / 4 threads). Resilience4j 2.4.0's `resilience4j-hedge` with the same settings (see `HedgeBenchmark`'s Javadoc for the adjustments):

| Benchmark | resilience4cata | Resilience4j 2.4.0 | Hand-written `CompletableFuture` |
|---|---:|---:|---:|
| first attempt already complete | 9.4 / 9.7 ns, 24 B | — | — |
| first attempt wins before the delay | 154 / 267 ns, 216 B | 3601 / 5577 ns, 1561 B | 649 / 1968 ns, 297 B |
| same, no hedge budget | 150 / 268 ns, 216 B | | |
| the hedge fires (1 ms delay; µs) | 2114 / 2116 µs | 1067 / 1070 µs | |

When the hedge fires, the time is the delay plus each library's timer precision: ours works in ticks of a sixteenth of the delay, at least 1 ms, and fires up to about two ticks late, so a 1 ms delay hedges after about 2 ms; Resilience4j's scheduled executor fires on time but leaves every cancelled delay queued, which is where most of its cost on the common path goes. Generated subclasses without `final` changed nothing measurable (`SubclassBenchmark` within noise).

## Adaptive limit, 0.6.0 (2026-10-26, AWS Graviton3)

Base: `67e1ebd` (0.5.0 plus docs). New: the `@AdaptiveLimit` integration. Same method as above (`infra/bench.sh`, `c7g.xlarge`, 3 forks; calibration 20.47–20.50 ns on 19 machines). `AdaptiveLimitBenchmark` is new, so it has no base; ns/op and B/op, 1 / 4 threads, every thread on the same limiter (fairness adjustments in the benchmark's Javadoc: same limits, Netflix's default tolerance and windows, and `minRttThreshold(0)` so that Netflix records every sample, as `@AdaptiveLimit` does):

| Benchmark | ns/op, 1 / 4 threads | B/op, 1 / 4 threads |
|---|---:|---:|
| `@AdaptiveLimit` (Gradient2, limit never reached) | 86.3 / 380 | 0 / 0 |
| same, async on a completed stage | 108.7 / 565 | 48 / 48 |
| same, every call rejected (limit full) | 2.1 / 2.1 | 0 / 0 |
| fixed `@Bulkhead` with the same limit | 20.5 / 284 | 0 / 0 |
| Netflix concurrency-limits 0.5.4, `SimpleLimiter` + windowed `Gradient2Limit` | 118.0 / 1087 | 40 / 95 |
| Netflix, every call rejected | 3.0 / 2.9 | 0 / 0 |

- **The clock is the difference with the fixed bulkhead.** An adaptive limit must time every call, so it reads `System.nanoTime()` twice. On this machine one read costs about 33 ns (the exhausted rate limiter, which reads it once and does little else, takes 34.6 ns), so the two reads explain the 66 ns between `@AdaptiveLimit` and `@Bulkhead` on one thread; the limiter's own work (the slot, the sample on this thread's stripe, the window check) is in the noise of that.
- **Against Netflix's limiter:** 27% faster on one thread and 2.9 times faster with four, without allocating. Netflix allocates the `Optional` and listener of every call, and its windowed sample is shared by every thread, where `@AdaptiveLimit` writes each sample to the calling thread's stripe and lets only the thread that ends the window read them all.
- **Rejections only read:** 2.1 ns whatever the number of threads.

The rest of the suite did not move, except `Pipeline`: a pipeline built without `adaptiveLimit` now checks one more null field per call, +0.2 ns (`pipelineRetryCircuitBreaker` 3.0 → 3.2 ns; generated code is unchanged). Four 4-thread benchmarks moved by 15–48% in the first run (`CircuitBreakerBenchmark.resilience4cataMixed`, `PipelineBenchmark.pipelineFour`, `BulkheadBenchmark.resilience4cataWaiting`, `HedgeBenchmark.resilience4cataPending`) although their code did not change; with 6 forks each the base and the new ranges overlap completely (−7% to +5%), so they were noise.

## Reactor support and the async rate limiter, 0.11.0 (2026-10-27, AWS Graviton3)

Base: `9a26494` (0.10.0 plus docs). New: `resilience4cata-reactor` and the asynchronous rate limiter that no longer parks. Same method as above (`infra/bench.sh`, `c7g.xlarge`, 3 forks; calibration 20.45–20.48 ns on 19 machines).

**`Mono` methods against resilience4j-reactor 2.4.0.** `ReactorBenchmark` is new, so it has no base. Each benchmark builds the protected `Mono` and subscribes to it; Resilience4j uses its Reactor operators with `transformDeferred` and the settings of `AsyncBenchmark` (fairness notes in the benchmark's Javadoc). The source is `Mono.just` (the value is there at subscription), or for `*Pending` a `Sinks.One` filled right after subscribing, so that every layer waits. ns/op and B/op, 1 / 4 threads; bytes are the typical fork (see the note below):

| Benchmark | resilience4cata, ns/op | Resilience4j, ns/op | resilience4cata, B/op | Resilience4j, B/op |
|---|---:|---:|---:|---:|
| no policy (`baseline`) | 52.4 / 53.1 | — | 88 | — |
| circuit breaker | **130.2 / 118.8** | 246.4 / 1378.7 | 200 | 248 |
| retry, first attempt succeeds | **127.8 / 139.0** | 726.2 / 734.1 | 200 | 1000 |
| timeout | **105.2 / 105.3** | 3165.5 / 6229.7 | 176 | 1344 |
| bulkhead | **144.1 / 621.7** | 157.5 / 683.7 | 200 | 200 |
| rate limiter | **166.5 / 351.6** | 184.2 / 659.3 | 200 | 240 / 195 |
| retry + breaker + timeout + bulkhead | **227.3 / 587.7** | 4337.8 / 5598.1 | 368 | 2624 |
| same stack, `PipelineOperator` | 251.0 / 606.2 | (the same Resilience4j stack) | 448 | — |
| no policy, pending (`baselinePending`) | 152.7 / 154.3 | — | 168 | — |
| timeout, pending | **441.0 / 560.5** | 3725.1 / 6332.8 | 480 | 1787 |
| stack, pending | **703.1 / 935.5** | 4394.8 / 6513.9 | 912 | 2704 |

- **Every pair is faster, on one thread and four.** The gaps: 1.9× (breaker, one thread) to 11.6× (breaker, four threads), 5.7× for the retry, 19× for the stack on one thread. The bulkhead is the closest: 9% on one thread, and within the error with four (621.7 ± 107.6 vs 683.7 ± 38.0), where both pay for one shared counter.
- **The timeout:** a value that is there at subscription cannot miss its deadline, so `@Timeout` arms no timer for it (105 ns); Resilience4j's `TimeLimiterOperator` is `Mono.timeout`, which schedules a task on Reactor's parallel scheduler for every subscription. With a pending value both arm a timer, and resilience4cata stays 8.4× (one thread) and 11.3× (four) faster.
- **Bytes per call, beyond the 88 B of subscribing:** one policy around a constant `Mono` costs 112 B (the method's `Mono` and its lambda, the subscription that delivers the value, the call lambda, the policy's future, the constant's completed future); its value is read without subscribing, as Reactor's own operators do. These objects are scalar-replaced in some forks and not in others: the same benchmark measured 200 B in one fork and 224–280 B in another, so the allocation gate's budgets are the highest fork, not these typical values.

**The asynchronous rate limiter no longer parks.** `RateLimiterPolicy.reserve` used to park for 1 ns after losing the CAS on its `tat`, like the synchronous `tryAcquire`. An asynchronous caller may be an event loop thread, which must never park, and BlockHound reported it (`MonoBlockHoundTest` in examples), so `reserve` now retries the CAS at once (`Thread.onSpinWait()` is reported by BlockHound too). `AsyncBenchmark.resilience4cataRateLimiter`, base → new: 67.1 → 66.9 ns on one thread, 277.7 ± 0.6 → 297.2 ± 28.1 ns with four (+7%); 48 B either way. The other `AsyncBenchmark` methods measured did not move (circuit breaker 25.0 → 24.9 / 25.6 → 25.6, stack 123.6 → 123.4 / 589.0 → 558.0). On Apple M1 (JDK 21, 4 forks) the same change cost much more under contention, 264.5 ± 52.2 → 661.0 ± 29.0 ns with four threads, where parking also took the losers away from macOS's contended `System.nanoTime()`; an exponential `onSpinWait` backoff (up to 64 spins) measured the same 659.9 ± 16.0 ns there and was rejected. The synchronous path still parks.

## Flux methods and already-done stages, 0.11.0 (2026-10-29, AWS Graviton3) :id=flux-and-done-stages

Base: `4649484` (`Flux` support before this tuning). New: `53cba0a`: elements of an attempt without a timeout go to the subscriber as they are, a cheaper serializer when a timeout listens, no attempt boundary without a retry or a fallback, the arbiter's fast paths, and the already-done shortcut of the circuit breaker, bulkhead, rate limiter and adaptive limit. Same method as above (`infra/bench.sh`, `c7g.xlarge`, 3 forks; calibration 20.51–20.54 ns on 19 machines).

**`Flux` methods against resilience4j-reactor 2.4.0.** `FluxBenchmark` is new, so it has no base. Each benchmark builds the protected `Flux`, subscribes, and every element reaches a `Blackhole`; one operation is one whole stream. Resilience4j uses its Reactor operators with `transformDeferred` and the settings of `ReactorBenchmark`; on a `Flux` its time limiter is `flux.timeout`, a deadline restarted by every element like `@Timeout` (fairness notes in the benchmark's Javadoc). Ten elements there at subscription unless noted; `pending`: ten elements emitted right after subscribing, so that every layer waits and both timeouts arm a timer; `1000 elements`: a thousand elements there at subscription. ns/op 1 / 4 threads, B/op of the typical fork on one thread:

| Benchmark | resilience4cata, ns/op | Resilience4j, ns/op | resilience4cata, B/op | Resilience4j, B/op |
|---|---:|---:|---:|---:|
| no policy (`baseline`) | 56.0 / 57.3 | — | 96 | — |
| circuit breaker | **195.1 / 189.3** | 272.3 / 1322.0 | 283 | 216 |
| retry, first attempt succeeds | **270.3 / 215.8** | 1196.8 / 1257.1 | 392 | 1024 |
| timeout | **680.5 / 688.4** | 28896.1 / 63439.9 | 384 | 3983 |
| bulkhead | **212.3** / 754.9 | 244.6 / **697.2** | 288 | 219 |
| rate limiter | 240.1 / **354.1** | **234.3** / 885.4 | 283 | 248 |
| retry + breaker + timeout + bulkhead | **788.8 / 984.3** | 30583.2 / 63403.4 | 616 | 5104 |
| same stack, `FluxPipelineOperator` | 824.4 / 1040.0 | (the same Resilience4j stack) | 752 | — |
| no policy, pending (`baselinePending`) | 1088.3 / 1004.5 | — | 1248 | — |
| timeout, pending | **1842.8 / 2135.3** | 30396.9 / 50648.3 | 1656 | 5390 |
| stack, pending | **2157.6 / 2703.1** | 31580.6 / 55194.6 | 2224 | 6264 |
| no policy, 1000 elements (`baselineLong`) | 1043.2 / 1042.6 | — | 96 | — |
| timeout, 1000 elements | **45057.8 / 45107.2** | 2632248.7 / 5172988.8 | 384 | 241529 |
| stack, 1000 elements | **46189.5 / 46545.0** | 2822600.4 / 5586559.5 | 579 | 242903 |

- **The timeout is where the gap is:** Reactor's `flux.timeout` schedules a task on the parallel scheduler for every element (about 2.6 µs and 240 B per element here); `@Timeout` reads the clock and writes one field per element and moves no timer (about 44 ns per element, 0 B), so a thousand elements take 45 µs against 2.6 ms. With ten elements: 42× on one thread, 92× with four; the stack 39× and 64×.
- **The circuit breaker** is 1.4× faster on one thread and 7× with four, where Resilience4j's breaker contends. **The retry** is 4.4× and 5.8× faster: Resilience4j's `RetryOperator` builds a retry context and resubscription machinery per subscription.
- **Two cases are within the error:** the bulkhead with four threads (754.9 ± 36.7 vs 697.2 ± 41.8, both paying for one shared counter, as for `Mono` methods) and the rate limiter on one thread (240.1 ± 9.7 vs 234.3 ± 0.9). The bulkhead on one thread and the rate limiter with four are faster (1.2× and 2.5×).
- **Bytes:** a protected stream allocates its `Flux`, the call's subscription, the attempt and its subscriber, and the layers' lambdas, about 190 B beyond subscribing, once per stream; its elements allocate nothing. Resilience4j allocates less for a single breaker, bulkhead or rate limiter (one subscriber each) and far more as soon as a retry or a timeout is involved.

**Tuning, measured on Apple M1 while it was done (JDK 21, 2 forks, ten elements):** the first version lost the single policies (circuit breaker 428 ns against 148 ns). Two CASes per element to serialize elements against the end cost about 8.5 ns per element; the store-buffering pair that replaced them, about 7–11 ns (its volatile read after a volatile write waits for the store buffer), so elements of an attempt without a timeout, whose end can only come from its own stream, skip it altogether (circuit breaker 217 → 146 ns there). The already-done shortcut and the boundary that is skipped without a retry took the rest.

**The already-done shortcut in the existing benchmarks**, base → new, ns/op 1 / 4 threads (bytes on one thread):

| Benchmark | Base | New |
|---|---:|---:|
| `AsyncBenchmark` circuit breaker | 24.9 / 25.5 (48 B) | **9.7 / 9.2** (24 B) |
| `AsyncBenchmark` bulkhead | 42.7 / 396.1 (48 B) | **28.4 / 312.1** (24 B) |
| `AsyncBenchmark` rate limiter | 67.1 / 283.8 (48 B) | **52.5** / 289.6 (24 B) |
| `AsyncBenchmark` stack | 123.4 / 586.2 (200 B) | **91.2 / 474.5** (152 B) |
| `AdaptiveLimitBenchmark` async | 108.6 / 505.1 (48 B) | **94.7** / 499.8 (24 B) |
| `ReactorBenchmark` circuit breaker | 129.3 / 121.7 | **105.7 / 105.3** (176 B) |
| `ReactorBenchmark` bulkhead | 140.8 / 514.5 | **126.4** / 525.3 (176 B) |
| `ReactorBenchmark` rate limiter | 166.7 / 360.7 | **146.5** / 377.6 (176 B) |
| `ReactorBenchmark` stack | 228.5 / 567.4 | **201.4 / 540.8** (376 B) |

A stage that is already complete when the call returns (a cached value, `Mono.just`, a stream that ended during its subscription) is now recorded at once and answered with a completed copy, as the timeout already did, instead of a linked future, a lambda and `whenComplete`'s dependent future. The retry, the timeout, the hedge, pending stages and every synchronous path did not move beyond the error (`PipelineBenchmark`, `HedgeBenchmark`, `AsyncBenchmark.resilience4cataRetry`/`Timeout*`, `ReactorBenchmark.*Pending`: within ±9%, all inside their error bars). Resilience4j's numbers in `ReactorBenchmark` did not move either (circuit breaker 243.3 / 1290.7, stack 4318.7 / 6671.2), so against resilience4j-reactor the `Mono` circuit breaker is now 2.3× faster on one thread and 12.3× with four.

## Allocation gate

Timing on shared CI runners is noise, but bytes allocated per operation are not, so CI gates on them. `./gradlew :benchmark:allocationCheck` runs every benchmark listed in `benchmark/allocation-budgets.properties` with short settings (`-f 1 -wi 3 -w 200ms -i 3 -r 200ms -t 1 -prof gc`, about 4 minutes) and fails if one allocates its budget + 1 B/op or more, or is missing from the results. It prints a table and writes `benchmark/build/jmh/allocation-report.md`; the `alloc-gate` workflow runs it on every PR. Adding a benchmark is one line: `<BenchmarkClass>.<method> = <bytes per op>`.

Budgets: 0 B/op for every sync success and rejection path (retry, circuit breaker count/time/open, rate limiter free/exhausted, bulkhead free/full/waiting, timeout, classifiers, generated code and `Pipeline` including `run` and a capturing lambda, subclass and wrapper). Async paths allocate their futures: 48 B for the retry; since 0.11.0, 24 B (a completed copy) for the circuit breaker, bulkhead, rate limiter, adaptive limit and timeout on a completed stage ([above](#flux-and-done-stages)), and 152 B for the four-policy stack (200 B before); and 200 B for a timeout on a pending stage (measured 191–192 B; the timer thread's shared work varies by a few bytes, and 8 B of slack is still less than one object per call). Measured locally on JDK 21 (Apple M1), 2026-10-24. `CircuitBreakerBenchmark.*Open` (breaker forced open, every call rejected) was added for the gate; Resilience4j gets `writableStackTraceEnabled(false)` there, for fairness.
