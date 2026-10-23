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
