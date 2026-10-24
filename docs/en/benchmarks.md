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
