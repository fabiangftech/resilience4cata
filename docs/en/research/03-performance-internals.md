# 03 — Performance Internals: Algorithms and Concurrency per Policy

> **Historical.** This research predates the implementation and is kept as written; for what 0.1.0 actually does, see [features](../features.md), [architecture](../architecture.md) and [benchmarks](../benchmarks.md).

> Phase 1 research. No production code. Short snippets are illustrative only. Last updated: 2026-10-23.
> Scope: which algorithm to use for each resilience policy, and how to build it in modern Java (21+) so that the hot path is cheap, allocation-free and scalable under contention. Includes how Resilience4j does it today and what that costs, plus a benchmarking methodology.
> Out of scope (covered by sibling documents): competitor landscape (01), annotation-processing mechanism, API/config surface (04), module layout (05).

## Evidence legend

| Tag | Meaning |
|---|---|
| **[SRC]** | Verified by reading source code. Resilience4j at `master` commit [`b7c0069`](https://github.com/resilience4j/resilience4j/tree/b7c0069802e373ed72dff0b817c270fbb6e966ba) (2026-10-07, after 2.4.0). OpenJDK at tags `jdk-21+35` and `jdk-25+36`. Netty `4.1`. |
| **[DOC]** | Verified from official documentation, a JEP page, a specification or a paper. |
| **[INF]** | Inferred by us from code, JVM behaviour or general knowledge, **not measured**. Must be validated with JMH and/or jcstress in a later phase. |
| **[PROP]** | Our design proposal. It is a hypothesis to benchmark, not a fact. |

Numbers given without **[DOC]** or **[SRC]** are orders of magnitude for reasoning only.

---

## 0. TL;DR

| Policy | Resilience4j today (main costs) | Recommended for resilience4cata |
|---|---|---|
| Circuit breaker | `ReentrantLock` around every recorded outcome (default `SYNCHRONIZED` strategy); a new `Snapshot` per call; `Instant` allocations in time-based windows and the OPEN state; a `LOCK_FREE` option that allocates a node and a stats copy per call. [SRC] | A state word in a `VarHandle` int/long. Count window as a slot array updated with `getAndSet`, plus per-outcome counters. Time window as an epoch-tagged bucket ring. Evaluate thresholds only when a rate can rise. Generation-tagged permits for half-open. Zero allocation on all paths. [PROP] |
| Rate limiter | `AtomicRateLimiter`: a new immutable `State` per CAS attempt, boxed `Supplier<Long>` clock, `parkNanos(1)` back-off, fixed-window bursts at cycle edges. `SemaphoreBasedRateLimiter`: a **fair** semaphore and a scheduler thread per limiter. [SRC] | **GCRA** on a single `long` (theoretical arrival time) with a CAS loop. Token-bucket configuration maps onto it. No allocation, no background thread. [PROP] |
| Bulkhead | `Semaphore.tryAcquire(timeout)`, a capturing lambda per call for events, and a second contended atomic (`grantsInProgress`) on every release. [SRC] | A fail-fast counter (`getAndAdd`) with a slow path that waits on a semaphore only when `maxWait > 0`. With Loom, a thread-pool bulkhead is a niche feature. Adaptive limits are a separate policy. [PROP] |
| Retry | Four allocations per call (`ContextImpl` + 3 atomics) even when the first attempt succeeds; boxing in the interval function. [SRC] | Attempt state in locals (generated code or a loop). Allocate only on failure. Decorrelated jitter with `ThreadLocalRandom`. A retry **budget** as a token bucket on one `long`. [PROP] |
| Timeout | The sync path needs a `Future` (a thread hop). The async path uses `ScheduledExecutorService` per call. [SRC] | A watchdog timer wheel that interrupts the caller (virtual threads make I/O interruptible). Hashed or hierarchical wheel with O(1) cancel. No thread hop. [PROP] |
| Cross-cutting | Some events are allocated before the "has consumers" check; `String.format` per rejection exception; lambda nesting creates megamorphic call sites. [SRC][INF] | Primitive-argument listener callbacks, guarded by a constant-foldable "no listeners" check. Stackless, preallocated rejection exceptions, or a non-throwing result. **Per-call-site generated, straight-line acquire/release code instead of nested lambdas.** One shared `nanoTime` pair per pipeline. [PROP] |

The biggest single lever is probably not any one algorithm but **composition** (§7.4). Resilience4j's decorator lambdas share one bytecode call site per policy type across the whole application, so the JIT sees megamorphic `supplier.get()` calls and cannot inline or escape-analyse across layers. An annotation-driven library can generate monomorphic, straight-line code per annotated method. [INF]

---

## 1. Low-level toolbox (modern Java)

### 1.1 Atomic primitives

- **`VarHandle` vs `Atomic*`.** Both compile to the same CPU instructions (`LOCK CMPXCHG` / `LOCK XADD` on x86; LSE `CAS`/`LDADD` or LL/SC on AArch64). `VarHandle` on a field of the policy object avoids the extra object and pointer hop of a separate `AtomicLong`. It also exposes weaker modes (`getOpaque`, `getAcquire`/`setRelease`, `weakCompareAndSetPlain`), which are cheaper on ARM. Use `static final VarHandle` fields so the JIT treats them as constants. [DOC] ([VarHandle javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/VarHandle.html))
- **`getAndAdd` vs CAS loops.** `getAndAdd` (x86 `LOCK XADD`, ARM `LDADD`) always succeeds in one instruction. A CAS loop can retry many times under contention. Prefer arithmetic that fits `getAndAdd`, and keep CAS for transitions that need "compare" semantics (state machines, GCRA). [INF]
- **`LongAdder` / `Striped64`.** Writes go to per-thread-probe cells, so contended increments scale. Reads (`sum()`) are O(cells) and not atomic snapshots. It is ideal for metrics counters, but wrong for decisions that need a consistent, exact value (e.g. "admit if count < max"). [DOC] ([LongAdder](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/LongAdder.html))
- **Locks.** An uncontended `ReentrantLock` costs about one CAS to lock and one release store to unlock. Under contention it parks threads, and a descheduled lock holder stalls everyone (Resilience4j's own config Javadoc says this, see §2.1). `synchronized` pins virtual threads on JDK 21–23 (fixed in JDK 24 by [JEP 491](https://openjdk.org/jeps/491)). The library must never block while holding a monitor. [DOC]

### 1.2 False sharing and padding

- Two hot, independently written fields on the same 64-byte cache line (128 bytes for adjacent-line prefetch on many Intel CPUs, and the line size on Apple M-series) make cores fight over that line. [INF]
- `@jdk.internal.vm.annotation.Contended` (originally [JEP 142](https://openjdk.org/jeps/142)) only takes effect for user classes when the JVM runs with `-XX:-RestrictContended`, and it lives in an internal package. A library cannot rely on it. [DOC]
- **Manual padding by class hierarchy** (as JCTools and Agrona do: `class Pad0 { long p01..p16; } class Value extends Pad0 { volatile long value; } class Pad1 extends Value { long p17..p32; }`) works because HotSpot lays out superclass fields first. It is the portable technique. [INF]
- **Layout rule for every policy object:** keep read-mostly configuration (thresholds, intervals) and immutable references in one group. Isolate each **independently written** hot word (state, counters, TAT) on its own padded line. Don't pad everything: padding costs memory, which matters with 10k+ named instances (multi-tenant). Make padding a build-time choice for "hot" instances, or pad only the write-hot words. [PROP]

### 1.3 Time

- `System.nanoTime()` cost: on Linux with a TSC clocksource, about **25 ns latency and ~26 ns granularity**, scaling well across threads. On Windows (older measurements) it was ~14 ns latency but **~370 ns granularity**, and it did **not** scale with 32 threads (~15 µs). [DOC] ([Shipilëv, *Nanotrusting the Nanotime*](https://shipilev.net/blog/2014/nanotrusting-nanotime/), JDK 7-era numbers; re-measure on JDK 21/25 and on macOS/AArch64.)
- `Clock.instant()` / `Instant.now()` allocates an `Instant` and reads wall-clock time, which is not monotonic. It is wrong for measuring intervals and wasteful on hot paths. [INF]
- **Cached clock:** a daemon "ticker" thread that writes `nanoTime()` to a `volatile long` every 1 ms. Readers pay one plain load. Costs: a permanent thread, 1 ms resolution, and the risk that the ticker stalls under CPU starvation (time freezes, timeouts never fire). Good for **coarse decisions** (is the OPEN wait over? which time bucket?), but bad for slow-call thresholds below ~10 ms. [INF]
- **Proposal:** a `Ticker` abstraction with three implementations: `nano` (default), `cached(1ms)` (opt-in), and `manual` (tests). One `nanoTime()` pair per **pipeline**, not per policy (§7.5). Skip timing entirely when no policy in the pipeline needs durations (no slow-call threshold, no latency metrics, no adaptive limit). [PROP]

### 1.4 Allocation and the JIT

- C2's escape analysis can remove short-lived allocations, but only when everything is inlined. Megamorphic call sites (more than 2 receiver types), deep inlining (the default `MaxInlineLevel` was raised from 9 in JDK 14 via [JDK-8234863](https://bugs.openjdk.org/browse/JDK-8234863)) and large methods all defeat it. Library code must be **allocation-free by construction**, not "allocation-free if the JIT is kind". [DOC][INF]
- Boxing traps seen in Resilience4j: `Function<Clock, Long>` timestamp, `Supplier<Long>` nano-time, `CheckedConsumer<Long>` sleep, `Function<Long, Long>` backoff. Each `Long` above 127 is a potential allocation. [SRC]
- CI gate: JMH `-prof gc` must report `gc.alloc.rate.norm ≈ 0 B/op` for every happy path and every rejection path (§9). [PROP]

---

## 2. Circuit breaker

### 2.1 How Resilience4j does it [SRC]

Files: [`CircuitBreakerStateMachine`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java), [`CircuitBreakerMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerMetrics.java), [`FixedSizeSlidingWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/FixedSizeSlidingWindowMetrics.java), [`SlidingTimeWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/SlidingTimeWindowMetrics.java), [`LockFreeFixedSizeSlidingWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/LockFreeFixedSizeSlidingWindowMetrics.java), [`CASBackoffUtil`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/CASBackoffUtil.java), [`CircuitBreakerConfig`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/CircuitBreakerConfig.java).

- **State:** `AtomicReference<CircuitBreakerState>`. Each state is an object (`ClosedState`, `OpenState`, `HalfOpenState`...) holding its own `CircuitBreakerMetrics` and `AtomicBoolean`. Transitions use `getAndUpdate(lambda)`, which allocates new state objects (rare, so fine).
- **CLOSED permission:** `isClosed.get()`, so the fast path is cheap.
- **Recording (default strategy `SYNCHRONIZED`):** every `onSuccess`/`onError` takes a `ReentrantLock`, updates a `TotalAggregation` plus one ring bucket, and **allocates a `SnapshotImpl`** that it returns. `CircuitBreakerMetrics.checkIfThresholdsExceeded` then computes failure and slow-call rates on **every** call, including successes.
  - Count-based: an array of `Measurement` objects, head index under the lock.
  - Time-based: one `PartialAggregation` per **second** (`timeWindowSizeInSeconds`), and `clock.instant().getEpochSecond()` on **every record** (an `Instant` allocation unless escape analysis removes it).
- **`LOCK_FREE` strategy** (added in PR [#2241](https://github.com/resilience4j/resilience4j/pull/2241), Dec 2024, opt-in; the default stays `SYNCHRONIZED`): a linked list of immutable nodes. Each `record` **copies** the tail's `PackedAggregation`, **allocates a new `Node`**, CASes it in, then advances head/tail. Contention back-off: `Thread.onSpinWait()` for a few spins, then `LockSupport.parkNanos(...)` with thread-id jitter. The config Javadoc states the trade-off itself: lock-free "allocat[es] more objects", while synchronized means "one thread that acquired the lock and is superseded from the CPU will block all other threads".
- **Timing:** `getCurrentTimestamp()` calls `Function<Clock, Long>` (default `clock -> System.nanoTime()`), so it is **boxed**, twice per call.
- **OPEN state:** `tryAcquirePermission` calls `clock.instant().isAfter(retryAfterWaitDuration)` on **every rejected call** (another `Instant`). The rejection then builds `CallNotPermittedException` with **`String.format`** per call (the stack trace can be disabled via `writableStackTraceEnabled`, but the message is always formatted).
- **Events:** success/error events are guarded by `eventProcessor.hasConsumers()` **before** allocation. But `publishEventIfHasConsumer(new CircuitBreakerOnCallNotPermittedEvent(name))`, `...OnIgnoredErrorEvent`, `...OnResetEvent` and threshold events **allocate the event first** and check afterwards. So every rejected call allocates an event even with no listener (unless escape analysis removes it).
- **Result handling:** `onResult` with a non-null result calls `handlePossibleTransition(Either.left(result))`, which allocates an `Either` per successful non-null call and invokes the `transitionOnResult` function.
- **Half-open:** `AtomicInteger.getAndUpdate(current -> current == 0 ? current : --current)`, a CAS loop through a lambda (non-capturing, so no allocation).
- **Stale results:** outcomes are applied to `stateReference.get()`, the **current** state. So a slow call admitted while CLOSED that finishes during HALF_OPEN is counted as a probe result. [SRC: `onSuccess` → `stateReference.get().onSuccess(...)`] Whether that is a bug is debatable, but it makes half-open decisions noisy. [INF]
- Logging: `LOG.debug(...)` on every success/error path (cheap when disabled, but still a call and a level check). [SRC]

### 2.2 Window algorithms

| Window | Memory | Update cost | Read cost | Notes |
|---|---|---|---|---|
| Count-based ring (last N calls) | O(N) slots | O(1) | O(1) with running counters | Exact "last N". Ordering needs one global sequence counter, so one hot line. |
| Time-based, bucketed (B buckets × resolution) | O(B) | O(1) amortised | O(B) to sum | Approximate at bucket edges. Can be striped. |
| Time-based, exponential decay (EWMA of failure ratio) | O(1) | O(1) with `exp` or a lookup table | O(1) | No window edges. Hard to explain "minimumNumberOfCalls". A candidate for an *adaptive* mode, not the default. [INF] |

#### Count-based, lock-free, allocation-free [PROP]

```java
// Illustrative only.
final class CountWindow {
  static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(int[].class);
  final int[] slots;          // outcome codes: 0 = EMPTY, 1 = OK, 2 = FAIL, 3 = SLOW_OK, 4 = SLOW_FAIL
  final int mask;             // N rounded up to a power of two; or use % N
  volatile long seq;          // padded; the only globally hot word
  // per-outcome counters, each on its own padded line (int fields + VarHandle getAndAdd)

  void record(int outcome) {
    long s = (long) SEQ.getAndAdd(this, 1L);
    int old = (int) SLOTS.getAndSet(slots, (int) (s & mask), outcome);
    if (old != outcome) { dec(old); inc(outcome); }   // the counters mirror the slot contents
  }
}
```

- Per call: one `getAndAdd` on `seq`, one `getAndSet` on a slot, and 0–2 counter `getAndAdd`s. No lock, no allocation, no retry loop, so it is wait-free. [PROP]
- Counter values are eventually equal to the histogram of the slot array. Transiently, a counter can be off by the number of in-flight `record` calls, and can even go to −1 if a slot is overwritten before its writer has incremented. **Clamp to ≥ 0 when reading.** Validate the invariants with [jcstress](https://github.com/openjdk/jcstress). [INF]
- Packing all counters into one `long` (a single `getAndAdd` of a packed signed delta) saves atomics. But a transient negative field borrows from its neighbour and could trigger a spurious OPEN. Only do it with guard bits; measure first. [INF]
- A non-power-of-two N can use `Math.floorMod(s, N)` (a division, a few ns) or round the window up. [INF]

#### Time-based, bucketed ring [PROP]

- B buckets of configurable resolution (e.g. 10 × 1 s, or 20 × 500 ms). Each bucket has `long epoch` plus counters. On record: `tick = now / resolution` (from a cached or the shared pipeline `nanoTime`). If `bucket.epoch != tick`, CAS the epoch and reset the counters. Racing increments at the edge may be lost or land in the reset bucket, a bounded error of about one bucket's worth of in-flight calls. That is acceptable for a statistical policy. [INF]
- No `Instant`, no epoch seconds: use monotonic ticks. [PROP]
- **Striping for extreme contention:** S independent sub-rings selected by thread probe (like `Striped64`), summed only on evaluation. Time windows tolerate this because order doesn't matter. Count windows don't (the "last N" order is global). [INF]

#### Evaluate thresholds only when a rate can rise [PROP]

- On a **fast success** in CLOSED, neither the failure rate nor the slow-call rate can increase. Skip evaluation, which removes the division and comparisons from >99% of calls in a healthy system.
- Evaluate on failure, on a slow success, and **while the window is not yet "warm"** (total < `minimumNumberOfCalls`). Otherwise the success that completes the minimum (e.g. 6 failures out of the first 10 calls) would not open the circuit until the next failure.

### 2.3 State machine [PROP]

- One `volatile int state` (or a `long` packing `state:2 | generation:30 | halfOpenPermits:16`) in a `VarHandle`, plus `long openUntilNanos`.
- **CLOSED fast path:** one acquire/plain load of `state` and a branch. Return a **primitive permit token** (`long` = generation) instead of an object, so the generated code can pass it to `onSuccess(token, nanos)`.
- **OPEN fast path:** load `state`, then load `openUntilNanos` and compare with `now`. A cached ticker is fine here. After expiry, the first thread to CAS OPEN→HALF_OPEN wins. Others re-read. Reject with a **preallocated, stackless** exception or a result code (§7.3). Nothing is allocated.
- **HALF_OPEN:** permits as a counter field. `getAndAdd(-1)`, and undo if it went below 0. **Generation tagging:** outcomes whose token generation ≠ the current generation are dropped (or counted only in metrics), so stale CLOSED-era calls don't decide probes. Optionally cap concurrent probes separately from total probes.
- **Transitions:** CAS on the state word. The winner resets the window (rare path, may allocate or lock) and publishes events.
- **Forced states** (DISABLED, FORCED_OPEN, METRICS_ONLY) are just more state codes.

### 2.4 Slow-call detection

- This needs a start/end duration, so two `nanoTime` calls (~50 ns total on Linux [DOC, see §1.3]). Share them with every other timed policy in the pipeline (§7.5). [PROP]
- If the slow threshold is ≥ 100 ms, a 1 ms cached ticker is precise enough and turns each read into a plain load. Make this a per-policy choice, automatic based on the threshold. [PROP]
- When no slow-call threshold is configured and no duration metrics are on, **don't time the call at all**. Resilience4j always times it. [SRC][PROP]

---

## 3. Rate limiter

### 3.1 Algorithms

| Algorithm | State | Burst behaviour | Lock-free in one word? | Notes |
|---|---|---|---|---|
| Fixed window counter | count + window id | Up to **2× limit** across a window edge | Yes (pack id + count in a `long`) | Simplest; edge bursts. |
| Sliding window log | timestamps of the last L permits | Exact | No (O(L) memory, ring of timestamps) | Precise but memory ∝ limit. |
| Sliding window counter | two adjacent window counts, weighted | Approximate, smooth | Nearly (two words) | Cloudflare/nginx-style approximation. |
| Token bucket | tokens + last refill time | Up to capacity | Yes, via the GCRA equivalence | The classic. |
| Leaky bucket (as a meter) | level + last time | Up to capacity | Yes, same as GCRA | Mathematically equivalent to GCRA. |
| Leaky bucket (as a queue) | a queue of waiters | None (smooths output) | No | This is a scheduler, not a limiter. |
| **GCRA** | one `long` TAT (theoretical arrival time) | Up to configured burst | **Yes, one CAS** | The best fit for an in-process limiter. |

References: [GCRA (Wikipedia)](https://en.wikipedia.org/wiki/Generic_cell_rate_algorithm), [Brandur — Rate limiting, cells, and GCRA](https://brandur.org/rate-limiting). [DOC]

### 3.2 GCRA on one word [PROP]

```java
// Illustrative only. T = emission interval (period / limit), tau = T * burst.
long tryAcquire(int permits, long nowNanos) {
  long inc = T * permits;
  for (;;) {
    long tat = (long) TAT.getVolatile(this);
    long newTat = Math.max(tat, nowNanos) + inc;
    long wait = newTat - nowNanos - tau;          // > 0 means "not yet"
    if (wait > 0) return wait;                    // the caller rejects, or waits ≤ timeout and then calls reserve()
    if (TAT.compareAndSet(this, tat, newTat)) return 0;
    Thread.onSpinWait();
  }
}
```

- One volatile read, one CAS, **no allocation, no background thread**, and correct for any number of permits. A "reservation" variant (Resilience4j's `reservePermission`) CASes in `newTat` even when `wait > 0` (bounded by the timeout) and returns `wait`. [PROP]
- A rejection without reservation is **read-only**: no CAS, so it doesn't bounce the cache line during overload. This matters, because overload is when the limiter is hottest. [INF]
- A token-bucket config (`capacity`, `refillRate`) maps to `T = 1/rate`, `tau = T × (capacity − 1)`. A "limit per period" config maps to `T = period/limit`, `burst = limit`. Rounding: `period/limit` in integer nanos drifts by < 1 ns per permit. Use remainder tracking if exactness matters. [INF]
- **Contention:** every *admitted* call CASes one word. At very high rates on many cores, shard into K sub-limiters of rate/K each, picked by thread probe, with optional borrowing. That trades exactness for scalability, so make it opt-in. [PROP]

### 3.3 How Resilience4j does it [SRC]

[`AtomicRateLimiter`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/AtomicRateLimiter.java) (the default):

- A **fixed-cycle** algorithm: `currentCycle = nanos / limitRefreshPeriod`. Permissions refill to `limitForPeriod` (capped) at each cycle edge, so a client can burst ~2× the limit around an edge. [SRC + INF]
- State is an **immutable `State` object (config, activeCycle, activePermissions, nanosToWait)** in an `AtomicReference`. **Every CAS attempt allocates a new `State`**, on success or failure, including for rejected calls.
- The clock is `Supplier<Long> nanoTimeSupplier` (**boxed**). `config.getTimeoutDuration().toNanos()` and `getLimitRefreshPeriod().toNanos()` are recomputed on every call.
- CAS failure back-off is `LockSupport.parkNanos(1)`. For a platform thread on Linux, the effective minimum sleep is bounded by timer slack (~50 µs by default), which is far more than a retry would cost. [SRC + INF]
- `waitingThreads` is a shared `AtomicInteger`, incremented and decremented around waits.

[`SemaphoreBasedRateLimiter`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/SemaphoreBasedRateLimiter.java):

- `new Semaphore(limit, /* fair */ true)`. A fair AQS semaphore queues every contended acquirer FIFO, which is much slower than the non-fair version under contention. [SRC + INF]
- A `scheduleAtFixedRate` task refreshes permits, using its **own single-thread scheduler per limiter** unless one is supplied. That is one thread per named limiter. The refresh (`limit - availablePermits()` then `release`) is not atomic with concurrent acquires. [SRC + INF]
- `reservePermission` is unsupported. [SRC]

### 3.4 Waiting for a permit

- **Fail fast by default.** If a wait is configured: on virtual threads, `LockSupport.parkNanos(wait)` unmounts and is cheap. On platform threads, it blocks an OS thread, which is the user's choice. [INF]
- With reservation (GCRA reserve), a waiter knows its exact slot, so there are no wake-up thundering herds or re-contention. [PROP]

---

## 4. Bulkhead

### 4.1 Semaphore bulkhead

- **Fail-fast (maxWait = 0), the common case** [PROP]:

  ```java
  if ((int) INFLIGHT.getAndAdd(this, 1) >= max) { INFLIGHT.getAndAdd(this, -1); return REJECTED; }
  // ... call ...
  INFLIGHT.getAndAdd(this, -1);   // + signal a waiter only if waiting is enabled and waiters > 0
  ```

  One `LOCK XADD` to enter and one to exit, wait-free. Concurrency never exceeds `max`. The transient over-count can cause a spurious rejection right at the limit, which is acceptable for a bulkhead. [INF]
- **With waiting (`maxWait > 0`):** fall back to `java.util.concurrent.Semaphore` (non-fair by default; fair as an option), or reuse the counter plus a waiter queue. Only that slow path pays AQS costs. [PROP]

**Resilience4j `SemaphoreBulkhead`** ([source](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/internal/SemaphoreBulkhead.java)) [SRC]:
- `tryAcquirePermission()` calls `semaphore.tryAcquire(maxWait.toMillis(), MILLISECONDS)` even when the wait is 0, and creates a **capturing lambda** `() -> callPermitted ? new ...PermittedEvent : new ...RejectedEvent` per call (an allocation unless escape analysis removes it).
- Every `releasePermission()`/`onComplete()` calls `grantPendingPermissions()`, which does `grantsInProgress.getAndIncrement()` (and later `addAndGet`) on a **second shared atomic**, even when no async waiters exist. So that is two to three contended atomics per call instead of two.
- The async path uses a `ConcurrentLinkedQueue<PendingPermission>` and schedules a timeout task per waiting acquisition.
- Rejection: `BulkheadFullException` with an optional stack trace (configurable). [SRC]

### 4.2 Thread-pool bulkhead vs virtual threads

- Resilience4j's [`FixedThreadPoolBulkhead`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/internal/FixedThreadPoolBulkhead.java) isolates calls in a bounded `ThreadPoolExecutor` and returns `CompletionStage`. Its costs are a thread hop, a queue, and future allocations per call. [SRC + INF]
- [JEP 444](https://openjdk.org/jeps/444) (virtual threads, final in JDK 21): "virtual threads are not expensive so there is never a need to pool them", and "do not be tempted to pool virtual threads in order to limit concurrency"; instead use "constructs specifically designed for that purpose, such as semaphores." [DOC]
- So in a Loom world the semaphore bulkhead is **the** bulkhead. Thread-pool isolation is left with two uses: (a) apps still running blocking code on platform threads that need to keep the caller thread free, and (b) "abandon on timeout" semantics where the caller must return while the work continues elsewhere. Both are niche. Ship it later, as a separate, optional implementation. [INF]

### 4.3 Pinning

- JDK 21–23: blocking inside `synchronized` pins the carrier. **JDK 24+ ([JEP 491](https://openjdk.org/jeps/491))**: monitors no longer pin, and `Object.wait()` unmounts. Pinning remains for native frames: native code calling back into Java that blocks, blocking during class loading or in **class initializers**, and waiting for another thread's class initialization. [DOC]
- Library rules: no `synchronized` around anything that can block. No blocking in static initialisers (e.g. don't start a ticker thread and wait for it in `<clinit>`). Use `j.u.c.locks` or lock-free code. Document that the user's own `synchronized` code pins on JDK 21–23. [PROP]
- Avoid per-call `ThreadLocal` caches: with virtual threads each call often runs on a fresh thread, so caching becomes allocation. JEP 444 explicitly warns against pooling expensive resources in thread locals. Prefer stack locals, or `ScopedValue` for context propagation (final in JDK 25, [JEP 506](https://openjdk.org/jeps/506); not usable in a Java 21 baseline without preview). [DOC][INF]

### 4.4 Structured concurrency (status checked 2026-10-23)

| JDK | JEP | Status |
|---|---|---|
| 21 | [453](https://openjdk.org/jeps/453) | Preview |
| 22–24 | [462](https://openjdk.org/jeps/462), [480](https://openjdk.org/jeps/480), [499](https://openjdk.org/jeps/499) | 2nd–4th preview |
| 25 | [505](https://openjdk.org/jeps/505) | 5th preview: `StructuredTaskScope.open(Joiner, cfg -> cfg.withTimeout(...))` |
| 26 | [525](https://openjdk.org/jeps/525) | 6th preview (Joiner timeout callback) |
| 27 | [533](https://openjdk.org/jeps/533) | 7th preview (joiners throw `ExecutionException`) ([InfoQ](https://www.infoq.com/news/2026/05/jep-533-jdk-27/)) |
| 28 (GA planned March 2027) | [543](https://openjdk.org/jeps/543) | **Final**, "without further change"; status *Integrated* |

[DOC] Implication: a Java 21 baseline jar **cannot** use `StructuredTaskScope` (preview class files are tied to one exact JDK release). Implement timeouts and hedging on plain virtual threads now. Add a multi-release-jar or separate-module adapter for JDK 28+ later. [INF]

---

## 5. Retry

### 5.1 Backoff strategies

| Strategy | Formula | Comment |
|---|---|---|
| Fixed | `base` | Synchronised retry storms. |
| Exponential (capped) | `min(cap, base · m^n)` | Still synchronised. |
| Full jitter | `rand(0, min(cap, base · 2^n))` | Lowest total work in AWS's simulation. |
| Equal jitter | `t/2 + rand(0, t/2)` | Keeps a minimum wait. |
| **Decorrelated jitter** | `sleep = min(cap, rand(base, prev · 3))` | Good spread, stateful (needs `prev`). |

Source: [AWS Architecture Blog — Exponential Backoff And Jitter (Brooker)](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/). [DOC]

Implementation: `ThreadLocalRandom.current().nextLong(lo, hi)` (no contention, no allocation). Compute exponentials by repeated multiplication or a shift with overflow saturation, not `Math.pow` per attempt. Keep everything in `long` nanos, with no `Duration` on the hot path. [PROP]

### 5.2 How Resilience4j does it [SRC]

[`RetryImpl`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-retry/src/main/java/io/github/resilience4j/retry/internal/RetryImpl.java), [`Retry.decorateCheckedSupplier`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-retry/src/main/java/io/github/resilience4j/retry/Retry.java):
- `retry.context()` → `new ContextImpl()` with `new AtomicInteger`, and two `new AtomicReference`: **four allocations per call, even when the first attempt succeeds**.
- `onComplete` increments `LongAdder`s (fine) and wraps values in `Optional` on the retry paths.
- `IntervalBiFunction`/`IntervalFunction` take a boxed `Long`; `sleepFunction` is `CheckedConsumer<Long>` (`Thread::sleep`).
- `onResult` evaluates the result predicate on every call (expected), and `Either.right(result)` allocates on retry-on-result.

### 5.3 Proposal [PROP]

- The **zero-failure path costs one predicate check**: attempt count, last exception and backoff state live in local variables of the generated method (or in a single loop inside a static helper). Allocate nothing until the first failure.
- **Sleeping:** use `Thread.sleep(Duration)` or `LockSupport.parkNanos` on the calling thread. On a virtual thread this unmounts. The JDK schedules the wake-up on an internal `ScheduledThreadPoolExecutor`. JDK 21 has a **single** `UNPARKER`; JDK 25 has an **array** of delayed-task schedulers (`jdk.virtualThreadScheduler.timerQueues`) to reduce contention. [SRC: `VirtualThread.java` at `jdk-21+35` / `jdk-25+36`] For async APIs, use `CompletableFuture.delayedExecutor` or our own timer wheel (§6.3).
- **Retry budgets** (avoid retry storms; a differentiator, since Resilience4j has none [INF]):
  - Google SRE: a per-request limit (≤ 3 attempts) plus a **per-client retry ratio < 10%**. [DOC] ([SRE book — Handling Overload](https://sre.google/sre-book/handling-overload/))
  - gRPC retry throttling: per-target token count starting at `maxTokens`. Each failure −1, each success +`tokenRatio`. Retries are allowed only while tokens > `maxTokens/2`. [DOC] ([gRPC A6](https://github.com/grpc/proposal/blob/master/A6-client-retries.md))
  - Implementation: one `long` in fixed-point milli-tokens, updated with `getAndAdd` (success/failure) and a read (retry decision). Allocation-free, one atomic per call. Share the budget across all retry policies targeting the same dependency. [PROP]
- Retries interact with circuit breakers and rate limiters. Order and budgets must be explicit in the pipeline (see sibling doc 04). [INF]

---

## 6. Timeout / time limiter

### 6.1 The fundamental problem

Java cannot pre-empt a running computation. A timeout can only (a) stop *waiting* for a result running elsewhere, or (b) *interrupt* the thread running it, which only helps if the code reaches an interruptible point. [INF]

### 6.2 Options

| Option | Thread hop | Per-call cost | Cancels the work? |
|---|---|---|---|
| Run in an executor, `future.get(timeout)` (Resilience4j sync `TimeLimiter`) | Yes | Task + future + queue; context switch | Only via `cancel(true)` → interrupt |
| Run in a **new virtual thread**, join with a timeout | Yes (cheap) | VT creation + start + join (~µs) [INF] | Interrupt |
| `StructuredTaskScope` + `withTimeout` | Yes | Scope + subtask | Interrupt (cancels scope) — preview until JDK 28 |
| `CompletableFuture.orTimeout` (async) | No (for already-async work) | `Timeout` + `Canceller` + scheduled task | No (completes exceptionally) |
| **Watchdog on the caller thread** [PROP] | **No** | One timer insert + cancel | Interrupt of the caller |

- **Resilience4j** [SRC] ([`TimeLimiterImpl`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-timelimiter/src/main/java/io/github/resilience4j/timelimiter/internal/TimeLimiterImpl.java)): the sync path is `decorateFutureSupplier(Supplier<Future>)`, so **the user must supply a thread** and the call does a `future.get(timeout.toMillis(), MILLISECONDS)`. The async path uses a `ScheduledExecutorService` timeout per call and `whenComplete` (allocations: the scheduled task, the completion lambda, the dependent future). On timeout it creates a named `TimeoutException`.
- **`CompletableFuture.orTimeout`** [SRC]: JDK 21 schedules on a single static daemon thread (`CompletableFutureDelayScheduler`, a `ScheduledThreadPoolExecutor`). In JDK 25 it is rewritten on top of the new `java.util.concurrent.DelayScheduler` used by `ForkJoinPool`. A `ScheduledThreadPoolExecutor` insert/cancel is O(log n) in a lock-protected heap, which becomes a contention point at high call rates. [INF]
- **Interruptibility with virtual threads:** in JDK 21, interrupting a virtual thread blocked in `Socket` connect/read/write "will cause it to wakeup and close the socket", and the method throws `SocketException`. [SRC: `java/net/Socket.java` Javadoc, `jdk-21+35`] So interrupt-based timeouts are effective for blocking I/O on virtual threads, which the most common timeout targets (HTTP/JDBC over sockets) benefit from. On platform threads, classic `Socket` I/O is not interruptible. [INF]

### 6.3 Watchdog design [PROP]

- The call stays on the caller's thread (no hop, preserving thread-locals, MDC and transactions). Before the call, register `(thread, deadline)` with a timer. After the call, cancel.
- **Race protocol:** a per-call state `RUNNING → DONE` (caller CAS) or `RUNNING → INTERRUPTING → INTERRUPTED` (timer CAS, then `thread.interrupt()`, then a release store). If the caller loses the CAS, it waits (spinning briefly) until `INTERRUPTED` and then clears the interrupt flag with `Thread.interrupted()` before throwing `TimeoutException`. This guarantees no stray interrupt leaks into unrelated later work. Model-check with jcstress.
- **Timer structure:** most timeouts are **cancelled** (the call finishes first), so cancel must be O(1) and cheap.
  - A hashed wheel timer (Netty `HashedWheelTimer`: default tick 100 ms, 512 buckets, MPSC queues for new and cancelled timeouts, one worker thread). [SRC] ([source](https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/HashedWheelTimer.java))
  - A hierarchical timing wheel (Varghese & Lauck 1987; also used in Kafka and Caffeine's `TimerWheel`) handles a wide range of timeouts with bounded buckets. [DOC] ([Varghese & Lauck, *Hashed and Hierarchical Timing Wheels*](https://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf))
  - **Even cheaper "slot scan" variant:** each in-flight timed call claims a slot in a preallocated array (free-list index), writes `deadline` and `thread`, and clears it after the call. One watchdog thread scans the occupied slots every tick (e.g. 1 ms). Insert and cancel are plain stores plus a free-list pop/push; the scan is O(in-flight). Best when concurrency is bounded (and a bulkhead usually bounds it). Benchmark against the wheel.
- The watchdog thread must be a platform daemon thread (it must not depend on the virtual-thread scheduler being free), started lazily but **not** inside `<clinit>` (pinning, §4.3).
- **Async APIs:** for `CompletionStage` methods, use `orTimeout`-like semantics on our own wheel instead of the JDK delayer, to share one timer for the whole library.

---

## 7. Cross-cutting concerns

### 7.1 Events and listeners

- **Rule:** zero allocation and no virtual dispatch when nobody listens. A `static final Listener[] NONE` sentinel, plus `if (listeners != NONE)`, where `listeners` is a `final` field set at build time. If listeners are known at build time, the generated code can omit the call entirely. [PROP]
- **Primitive callbacks instead of event objects:** `interface CircuitBreakerListener { default void onSuccess(long nanos) {} default void onRejected() {} default void onStateChange(int from, int to) {} }`. No allocation even *with* listeners. Event-object adapters (for logging or Micrometer) allocate only inside the adapter. [PROP]
- **Event ordering and exceptions:** a listener throwing must not break the call. Catch, and count in a "listener failures" `LongAdder`. [PROP]
- **Resilience4j** [SRC]: the `EventProcessor` uses a `CopyOnWriteArraySet` per event class name, looked up with `event.getClass().getName()` (a `ConcurrentHashMap` lookup per published event). The guard exists, but several call sites allocate before checking (§2.1). Bulkhead/retry pass capturing `Supplier` lambdas.

### 7.2 Metrics

- Counters: `LongAdder` (or striped padded longs) incremented on the hot path; **pull-based** exposure (a gauge reading `sum()` at scrape time). No Micrometer or registry calls on the hot path. [PROP]
- Latency histograms (only if enabled): fixed log-linear buckets (HdrHistogram-style) in an `AtomicLongArray` or striped arrays. Recording = `numberOfLeadingZeros` + array index + `getAndAdd`. Required for hedging delays (p95) and adaptive limits. [PROP] ([HdrHistogram](https://github.com/HdrHistogram/HdrHistogram))

### 7.3 Exceptions

- `Throwable` construction cost is dominated by `fillInStackTrace`, which is proportional to stack depth and is typically microseconds. The `(message, cause, enableSuppression=false, writableStackTrace=false)` constructor avoids it. [DOC][INF] ([Throwable constructor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html#%3Cinit%3E(java.lang.String,java.lang.Throwable,boolean,boolean)))
- Resilience4j does use that constructor for `CallNotPermittedException`, `BulkheadFullException` and `RequestNotPermitted` (stack trace configurable, default writable). But it builds the message with `String.format` (circuit breaker) or string building **on every rejection**. [SRC]
- **Proposal:** rejection exceptions are **preallocated per policy instance**, stackless, with suppression disabled. The message is computed once ("CircuitBreaker 'x' is OPEN"). The state is read from a field at `getMessage()` time if needed. Debug mode (config flag) creates fresh exceptions with stack traces. Additionally, offer a non-throwing API (`Result`/sealed outcome or `tryCall` returning a status) for callers that treat rejection as normal flow. [PROP]
- **Never** wrap user exceptions on the hot path (no `CompletionException`/`ExecutionException` wrapping in the sync path). Re-throw checked exceptions with a "sneaky throw" in generated code, preserving the declared signature. [PROP]

### 7.4 Composition without nested lambdas or megamorphic sites

**Problem.** `Decorators.ofSupplier(s).withRetry(r).withCircuitBreaker(cb).withBulkhead(b).decorate()` builds a chain of lambdas. Each `CircuitBreaker.decorateCheckedSupplier` lambda body is **one bytecode location for the whole application** [SRC: `CircuitBreaker.java`]. Its `supplier.get()` call site sees every decorated supplier class in the app, so it becomes megamorphic after the third distinct type. Megamorphic sites are compiled as itable/vtable calls, which blocks inlining, so escape analysis can't remove the `Long`, `Either`, `Snapshot` and event allocations. Real applications have many decorated methods, so microbenchmarks with one decorated supplier are *unrealistically* fast for Resilience4j. [INF; validate with `-XX:+PrintInlining` / JITWatch]

**Proposal** [PROP]:
1. **Acquire/release protocol, not wrap-around.** Each policy exposes primitive, non-lambda operations: `long cb.acquire(now)`, `cb.onSuccess(token, nanos)`, `cb.onFailure(token, nanos, Throwable)`, `boolean bh.tryEnter()` and `bh.exit()`, `long rl.tryAcquire(now)`, and so on.
2. **Generated, straight-line code per annotated method** (whatever mechanism sibling research picks: an annotation processor generating subclasses or wrappers). Each method gets its own bytecode, so each call site is **monomorphic** (`final` policy classes, invoked through constant `static final` fields or final instance fields). Shape:

   ```java
   // Generated, illustrative.
   long t0 = TICKER.read();
   if (!BH.tryEnter()) throw BH.rejected();
   try {
     long tok = CB.acquire(t0);          // throws a preallocated rejection if OPEN
     try {
       R r = target.call(args);          // direct invokevirtual on the real class
       long t1 = TICKER.read();
       CB.onSuccess(tok, t1 - t0);
       return r;
     } catch (Throwable e) { CB.onFailure(tok, TICKER.read() - t0, e); throw e; }
   } finally { BH.exit(); }
   ```

3. **Retry as a loop** around the inner block in the same generated method. No lambda, no context object.
4. **Fallback for programmatic (non-annotation) use:** a single `Pipeline` class with a fixed ordering and `final` fields per policy kind (null = absent), executing the same acquire/release sequence through `if (cb != null)` branches. That is one class whose call sites see a single concrete policy class each (policies are `final` classes), so they stay monomorphic. The user's `Callable.call()` remains one megamorphic site, which is unavoidable without generation. [INF]

### 7.5 Shared clock reads

In a stack of N timed policies, Resilience4j reads the clock per policy (the circuit breaker reads it twice, plus the rate limiter, time limiter, and so on). A generated pipeline reads `t0` once before and `t1` once after, and passes durations to everyone. [PROP]

---

## 8. Differentiating features

### 8.1 Hedging

- Send a second request if the first hasn't completed after a delay (typically ~p95 latency), take the first success, and cancel the rest. In *The Tail at Scale*, deferring the hedge until the 95th-percentile expected latency limits extra load to about 5% while cutting the tail substantially. [DOC] ([Dean & Barroso, CACM 2013](https://research.google/pubs/the-tail-at-scale/))
- gRPC hedging: `maxAttempts` (≤ 5), `hedgingDelay`, `nonFatalStatusCodes`; hedges obey retry throttling. [DOC] ([gRPC A6](https://github.com/grpc/proposal/blob/master/A6-client-retries.md))
- Implementation on Loom: the first attempt runs on the caller thread; the hedge starts a virtual thread when the timer (§6.3) fires; first-completion wins via a CAS on a result slot; losers are interrupted. Hedging requires idempotency (an explicit annotation attribute) and must consume the retry budget. The delay comes from a live latency histogram (§7.2) or is fixed. [PROP]
- Resilience4j has a newer `hedge` module (see doc 01). Compare semantics there. [INF]

### 8.2 Adaptive concurrency limits

- [Netflix concurrency-limits](https://github.com/Netflix/concurrency-limits): limit ≈ RPS × latency (Little's law). Algorithms: **Vegas** (queue estimate `L·(1 − minRTT/sampleRTT)`, ±1 against α/β thresholds), **Gradient2** (long-term vs short-term latency EWMA divergence), **AIMD** (loss-based). Limiters: `SimpleLimiter`, partitioned (per-priority share), blocking. [DOC]
- Envoy's adaptive concurrency filter uses a gradient controller with periodic minRTT re-measurement. [DOC] ([Envoy docs](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/adaptive_concurrency_filter))
- **Hot-path design** [PROP]: in-flight counter (as in the bulkhead, §4.1) against a `volatile int limit`; on completion, add the RTT to a per-window accumulator (`LongAdder` sum + count; min-RTT via CAS-min on a `long`). The **thread that crosses the window boundary** (CAS on the window epoch) recomputes `limit`. Everyone else pays only `getAndAdd` ×2 plus a `LongAdder.add`. No background thread.
- This is a clear differentiator: an "adaptive bulkhead" that needs no tuning. [INF]

### 8.3 Load shedding

- Client-side adaptive throttling (Google SRE): reject locally with probability `max(0, (requests − K·accepts) / (requests + 1))`, with K ≈ 2. [DOC (the K and the definitions are quoted on the page; the formula itself is rendered as an image on the [SRE book page](https://sre.google/sre-book/handling-overload/))]. Implementation: two sliding-window counters (§2.2 time ring) plus `ThreadLocalRandom`. [PROP]
- Server-side queue discipline for waiting callers: CoDel-style ("if the queue hasn't been empty for X ms, shed") and **adaptive LIFO** under overload, as described by Facebook. [DOC] ([Maurer, *Fail at Scale*, ACM Queue 2015](https://doi.org/10.1145/2814330); a Java precedent is HBase's [`AdaptiveLifoCoDelCallQueue`](https://hbase.apache.org/2.5/devapidocs/src-html/org/apache/hadoop/hbase/ipc/AdaptiveLifoCoDelCallQueue.html))
- Priority partitions (critical vs batch shares of a limit), as in Netflix's partitioned limiter. [DOC]

---

## 9. Benchmark methodology (JMH vs Resilience4j)

### 9.1 Ground rules

- [JMH](https://github.com/openjdk/jmh) only. Forks ≥ 3, warmup ≥ 5×1 s, measurement ≥ 5×1 s. Fixed `-Xms=-Xmx`, plus `-XX:+AlwaysPreTouch`. Run on **JDK 21 and JDK 25** (the virtual-thread timer and the `CompletableFuture` delayer changed, §5.3/§6.2), on **x86-64 and AArch64** (atomics behave differently). Fix CPU frequency and disable turbo where possible. Publish the JMH JSON and the exact hardware. [PROP]
- Compare against the **latest Resilience4j release (2.4.0)** with *equivalent* configuration: same window type and size, both `SYNCHRONIZED` and `LOCK_FREE` strategies, `writableStackTraceEnabled=false` for rejection benchmarks (fair to them), and events with and without consumers. Reuse their own JMH scenarios (e.g. [`CircuitBreakerBenchmark`](https://github.com/resilience4j/resilience4j/tree/master/resilience4j-circuitbreaker/src/jmh/java/io/github/resilience4j/circuitbreaker)) so no one can claim cherry-picking. [PROP]
- **Baseline**: the undecorated call, so we can report *overhead* = policy − baseline. [PROP]
- **Profile pollution (fairness for both libraries):** before measuring, decorate and exercise ≥ 3–4 different supplier/target classes in the same JVM, so that shared call sites are megamorphic as in real apps. Report both "clean" and "polluted" numbers. [PROP]
- Profilers: `-prof gc` (`gc.alloc.rate.norm` B/op), `-prof perfnorm` (instructions and cache misses per op), `-prof perfasm` or async-profiler for hot spots, JFR `jdk.VirtualThreadPinned` for virtual-thread runs. Use `Blackhole.consumeCPU(n)` to simulate work. [DOC][PROP]
- Correctness: [jcstress](https://github.com/openjdk/jcstress) for every lock-free structure (window counters, state CAS, GCRA, watchdog protocol). A fast but wrong circuit breaker is a bug. [PROP]

### 9.2 Scenarios

| # | Scenario | Modes | Threads | What it shows |
|---|---|---|---|---|
| 1 | Happy path per policy (CB closed, RL under limit, BH under limit, retry succeeds first try, TL fast) | `AverageTime`, ns/op | 1 | Pure overhead, B/op |
| 2 | Same, shared instance | `Throughput` | 1, 2, 4, …, 2× cores | Contention scaling (false sharing, CAS storms, lock convoys) |
| 3 | Rejection path (CB open, RL exhausted, BH full) | `Throughput` + `-prof gc` | 1 and max | Exception and event costs under overload (when it matters most) |
| 4 | Mixed outcomes (1%, 10%, 50% failures, slow calls) | `Throughput` | max | Threshold evaluation and window updates |
| 5 | CB flapping (frequent open/half-open/close) | `Throughput` | max | Transition and probe correctness/cost |
| 6 | Retry with failures, zero backoff | `AverageTime` | 1 | Retry machinery cost per attempt |
| 7 | Full stack Retry→CB→RL→BH→TL vs `Decorators` | `AverageTime`, `Throughput` | 1, max | Composition overhead (§7.4) |
| 8 | Timeout: schedule+cancel per call vs `orTimeout` vs R4j `TimeLimiter` | `Throughput` | 1, max | Timer data-structure cost |
| 9 | 10k virtual threads through BH/RL with waiting | `SampleTime` (p50/p99/p999) | VTs | Fairness, tail latency, pinning |
| 10 | Footprint: 10k named instances | JOL / heap histogram | — | Memory cost of padding and state |
| 11 | Polluted vs clean JIT profile (§9.1) | as #1/#7 | 1 | Realistic vs best-case numbers |

### 9.3 Expected outcome (hypotheses to confirm)

- Single-thread happy path: the advantage comes from allocation removal and fewer clock reads. Expect a modest gain (tens of ns/op) in "clean" profiles and a larger one in "polluted" ones. [INF]
- Contended throughput: the largest gains are expected on the circuit breaker (lock vs wait-free) and the rate limiter (allocation per CAS attempt and `parkNanos` back-off vs a plain CAS). [INF]
- Rejection path: preallocated stackless exceptions plus no eager events should be orders of magnitude cheaper than `String.format` per rejection. [INF]
- We must not claim any number before measuring. All of the above is to be confirmed in phase 2. [PROP]

---

## 10. Recommendations by policy (summary)

1. **Circuit breaker:** a `VarHandle` state word plus generation tokens. Wait-free count window (slot `getAndSet` + counters) and epoch-tagged time buckets. Skip evaluation on fast successes once warm. No timing unless needed. Preallocated rejections.
2. **Rate limiter:** GCRA (one `long`, CAS). A read-only reject path. Token-bucket and per-period configs map onto it. Optional sharding.
3. **Bulkhead:** a fail-fast `getAndAdd` counter. Semaphore only for waiting. Thread-pool isolation later and optional. An adaptive limit as a sibling policy.
4. **Retry:** locals only, allocate on failure. Decorrelated or full jitter with `ThreadLocalRandom`. A token-based retry budget shared per dependency.
5. **Timeout:** a caller-thread watchdog with a strict interrupt protocol. A hashed wheel or slot-scan timer with one daemon platform thread. Async uses the same timer.
6. **Differentiators:** hedging on virtual threads with a histogram-driven delay; Vegas/Gradient adaptive limits; SRE adaptive throttling; retry budgets.
7. **Cross-cutting:** primitive listener callbacks; pull metrics; **generated monomorphic acquire/release code**; one clock pair per pipeline; padding of write-hot words; `0 B/op` as a CI gate.

---

## Sources

- Resilience4j source, `master` @ `b7c0069`: <https://github.com/resilience4j/resilience4j> (files linked inline). Lock-free window PR: <https://github.com/resilience4j/resilience4j/pull/2241>. Releases: <https://github.com/resilience4j/resilience4j/releases>
- OpenJDK `VirtualThread.java`: <https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/VirtualThread.java>, <https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/lang/VirtualThread.java>
- OpenJDK `CompletableFuture.java` (21 vs 25): <https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java>, <https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java>
- OpenJDK `Socket.java` (virtual-thread interrupt semantics): <https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/net/Socket.java>
- JEPs: [444](https://openjdk.org/jeps/444), [491](https://openjdk.org/jeps/491), [505](https://openjdk.org/jeps/505), [506](https://openjdk.org/jeps/506), [525](https://openjdk.org/jeps/525), [533](https://openjdk.org/jeps/533), [543](https://openjdk.org/jeps/543), [142](https://openjdk.org/jeps/142); [JDK-8234863](https://bugs.openjdk.org/browse/JDK-8234863)
- InfoQ on JEP 533 (JDK 27): <https://www.infoq.com/news/2026/05/jep-533-jdk-27/>; on JEP 543 (JDK 28): <https://www.infoq.com/news/2026/10/jep-543-jdk-28/>
- A. Shipilëv, *Nanotrusting the Nanotime*: <https://shipilev.net/blog/2014/nanotrusting-nanotime/>
- M. Brooker, *Exponential Backoff And Jitter*: <https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/>
- Google SRE book, *Handling Overload*: <https://sre.google/sre-book/handling-overload/>
- gRPC A6 client retries and hedging: <https://github.com/grpc/proposal/blob/master/A6-client-retries.md>
- Dean & Barroso, *The Tail at Scale*: <https://research.google/pubs/the-tail-at-scale/>
- Netflix concurrency-limits: <https://github.com/Netflix/concurrency-limits>
- Envoy adaptive concurrency: <https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/adaptive_concurrency_filter>
- B. Maurer, *Fail at Scale*, ACM Queue (2015): <https://doi.org/10.1145/2814330>; HBase `AdaptiveLifoCoDelCallQueue`: <https://hbase.apache.org/2.5/devapidocs/src-html/org/apache/hadoop/hbase/ipc/AdaptiveLifoCoDelCallQueue.html>
- GCRA: <https://en.wikipedia.org/wiki/Generic_cell_rate_algorithm>, <https://brandur.org/rate-limiting>
- Varghese & Lauck, timing wheels: <https://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf>
- Netty `HashedWheelTimer`: <https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/HashedWheelTimer.java>
- JMH: <https://github.com/openjdk/jmh>; jcstress: <https://github.com/openjdk/jcstress>; HdrHistogram: <https://github.com/HdrHistogram/HdrHistogram>
