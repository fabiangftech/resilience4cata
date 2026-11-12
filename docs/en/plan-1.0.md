# Plan to 1.0

What is left before 1.0.0 is released, from a review of 0.13.0 by four independent reviewers, and what waits until after it.

This page is the working plan: each task says what to change, where, and how to check it, so work can resume from here without any other context. Mark a task done (`[x]`) in the same pull request that does it, and add its pull request number.

## Status

- Versions on Maven Central: 0.13.0, and the release candidates 1.0.0-RC1, 1.0.0-RC2 and 1.0.0-RC3. `main` is 1.0.0-SNAPSHOT.
- The performance plan from the AWS runs is done (pull requests #53–#59, see [benchmarks](benchmarks.md)).
- Javadoc titles carry the artifact name (#60); javadoc.io was synced for all 11 modules.
- Phases 1 to 3 are done (#62 to #65).
- A second review by the same four reviewers (2026-11-02, after #65): the API designer, the performance reviewer and the newcomer say ready for 1.0; the adopter asks for a 1.0.0-RC1 first, and for three things before 1.0 (a loud failure when the processor is missing, one jcstress run on ARM, a security policy). Their small documentation fixes are in phase 4.
- 1.0.0-RC1 is on Maven Central (2026-11-02). `main` is 1.0.0-SNAPSHOT, with the API baseline at 1.0.0-RC1, so the API is frozen: an incompatible change against the release candidate fails the build.
- SonarCloud's main branch was still called `master`, so the analyses of `main` never reached the project overview. It is `main` now; its 21 reliability issues were reviewed (#68), and only the published library is analysed (#69).
- 1.0.0-RC2 (2026-11-04) fails the startup or build when the processor did not run.
- A [third review](#third-review) of RC2 (2026-11-05): three reviewers say ship 1.0.0; its fixes change two behaviours, so a 1.0.0-RC3 comes first.
- 1.0.0-RC3 (2026-11-05) has the third review's fixes. The install snippets now show it (owner's decision).
- `main` takes changes only through pull requests with passing checks (administrators bypass it for the release commits), and every release candidate has a GitHub release; 1.0.0-RC3 is *Latest*, as the install snippets show it (2026-11-06).
- Next: 1.0.0.

## The review

Four reviewers read the repository on 2026-11-02, each from one point of view. None ran the build. Their findings are claims to check, not facts: every task below starts by confirming the finding.

| Reviewer | Verdict |
|---|---|
| A Resilience4j user deciding whether to adopt | Not yet for general production; yes for a pilot on one service. Strong engineering; the risk is the project's age and a single maintainer. |
| An API designer | Close to ready for the freeze; five cheap fixes now that would be permanent later. |
| A performance and concurrency skeptic | Careful engineering, no confirmed bug; the headlines are best cases; x86 never measured; four suspected gaps. |
| A newcomer to resilience libraries | A call protected in about 15 minutes; a few stale or contradictory statements; some pages too internal. |

What they valued, to keep while changing the rest:

- Errors at compile time with stable `R4C-…` codes, each documented with a wrong example and its fix, where Resilience4j resolves `fallbackMethod` by reflection at run time.
- A near-zero cost per call, measured openly: ties and regressions are shown, fairness adjustments are written in each benchmark's Javadoc, Resilience4j gets stackless exceptions and no dead code, and the allocation gate runs in CI.
- Generated code that is plain Java: stack traces go through `X$Resilient`, not proxy chains, and self-invocation is intercepted in the subclass style.
- A serious migration guide (configuration tables, differing defaults, when to stay on Resilience4j) and an explicit compatibility promise backed by japicmp and the generated-code rule.
- An API that can grow: configs are final classes with builders, `ResilienceSnapshot` is a class, handles and `FallbackContext` are library-implemented, rejections share a sealed stackless base, enums document that they grow, `@Internal`/`@Experimental` are enforced, `PipelineCall` keeps a precise checked exception.
- Sound lock-free code, as far as the reviewer read: the breaker's `quiet` handshake, the timeout's `RUNNING → IDLE` acquire CAS, `BulkheadSemaphore`'s release and re-check, the waiters' `count++ → offer → drain` order, the `LinkedFuture` cancel mark, and the retry budget erring towards fewer retries.
- The quickstart (one tested program, its output, "What just happened") and the installation page (BOM on `annotationProcessor`, old Maven, JDK 23 discovery, IDEs, Lombok order). The en/es trees match and the Spanish reads naturally.

## Phase 1: API, before the freeze

One pull request. Everything here changes the public API, which is free before 1.0 and impossible after it. Each change gets a changelog line (en/es) and, where it touches generated code, a check that `examples` still passes.

- [x] **1.1 Nullable type parameters.** Under `@NullMarked`, `Pipeline<T>`, `PipelineCall<T, X>`, `FallbackHandler<T>` and `Classifier<T>` bound `T` to non-null, though `PipelineRunnable extends PipelineCall<@Nullable Void, X>` already breaks that bound and fallbacks of `void` methods return null. Declare `<T extends @Nullable Object>` on all four, and on whatever else takes a user's result type (`AsyncCall`, `Recovery`, `PipelineRunnable`: check). Check: `./gradlew build`; the processor's tests compile generated code with `-Werror`.
  - **Outcome:** (#62) Done: the four types, plus `AsyncCall`, `Recovery` and the policies' `executeAsync`; reactor's `Mono`/`Flux` types stay non-null (Reactor forbids null elements).
- [x] **1.2 Records that grow.** `reference/compatibility.md` says event and snapshot records may get components, but adding one removes the canonical constructor (binary incompatible, japicmp fails). Decide: either constructing events and snapshots is not supported (say so in `compatibility.md` and the records' Javadoc), or every new component keeps the old constructor (say so, and add the rule to CLAUDE.md). The second is friendlier to users' listener tests; prefer it.
  - **Outcome:** (#62) Done: the old constructor is kept (`compatibility.md`, `ResilienceEvent`, CLAUDE.md).
- [x] **1.3 Sealed types that grow.** Add `PolicyConfig` and `CallRejectedException` (their `permits` grow with each new policy) to the "may grow" list in `compatibility.md` and in their Javadoc, as for events: an exhaustive `switch` over them needs a default branch.
  - **Outcome:** (#62) Done (`compatibility.md`, both classes' Javadoc).
- [x] **1.4 One name for a rejection event.** `CircuitBreakerEvent.CallNotPermitted` → `Rejected`, like the bulkhead, rate limiter and adaptive limit events. The exception keeps its name. Also align the annotation enum `LimitAlgorithm` with `AdaptiveLimitConfig.Algorithm` (the other enums, `Backoff`, `Jitter`, `WindowType`, match their config enum). Update the metrics modules, the events table in `guides/observability.md` (en/es) and the snippets under `examples/src/test/.../docs/`.
  - **Outcome:** (#62) Done for the event. `LimitAlgorithm` kept: see [design](design.md#api-decisions).
- [x] **1.5 Constants that inline.** `HedgeConfig.MIN_ATTEMPTS`/`MAX_ATTEMPTS` are compile-time constants: they are copied into callers' class files, so raising the cap of 10 would not reach code compiled earlier. Make them private (the bounds are documented), or not constant.
  - **Outcome:** (#62) Done: private.
- [x] **1.6 Small renames.** `ResilienceRegistry.Builder.listen(String instance, …)` → `id`. `Resilient.create(type, registry, Object... args)`: the arguments may be null, so `@Nullable Object...`. The derived-id format is `<interface>#…` in `ResilienceRegistry` and `ResilienceEvent` but `<type>#…` in the handles: use `<type>` (annotated classes).
  - **Outcome:** (#62) Done.
- [x] **1.7 Decide, do not necessarily change.** Write each decision in `design.md`:
  - `@Timeout` has no `name` (the six others do). Adding it later is additive.
  - Builder list parameters (`retryOn(List<Class<? extends Throwable>>)`) reject `List<Class<? extends Exception>>`; `Collection<? extends Class<? extends Throwable>>` would accept it, but only before 1.0.
  - Argument order: `Resilient.of(type, delegate, registry)`, `create(type, registry, args…)`, `Pipeline.builder(registry, id)`.
  - Micronaut's `ResilienceHealthIndicator` and `ResilienceEndpoint` have public constructors: mark them `@Internal`.
  - `AdaptiveLimitConfig.Builder` tuning setters have one-line Javadoc with no main description: write it.
  - Snapshot shapes differ: retry, timeout and hedge snapshots embed `config`; bulkhead, rate limiter and adaptive limit copy a few fields (`maxConcurrent`, `permits`, `minLimit`/`maxLimit`); the circuit breaker's carries neither. Choose one shape, or write down why they differ.
  - `CircuitBreakerSnapshot` uses `-1` for "no rate yet": keep the sentinel (documented) or plan a `hasRates()` accessor.
  - `Pipeline.Builder.retry(String)` next to `retry(Consumer)` (and the same pairs for every policy): `retry(null)` is ambiguous. Accept it or rename one of the pair.
  - `LockedOverride.policy` is a `String` with a closed set of values: an enum would be safer; the string matches the configuration keys.
  - Two enums named `ExhaustedResult` (`Pipeline.ExhaustedResult` and the annotation's, which also has `INHERIT`): importing both is clumsy. Accept or rename.
  - **Outcome:** (#62) Done: every point decided in [design](design.md#api-decisions); the Micronaut constructors are `@Internal` and all 51 builder setters have a main description.

Check for the whole phase: `./gradlew build :stress:compileJava`, `./gradlew :quarkus:it:check`, `./gradlew :core:apiCompatibility` (the report lists the intended changes and nothing else).

## Phase 2: documentation

One pull request, en and es together. Most of these are stale numbers or sentences that contradict the code.

- [x] **2.1 Stale performance numbers.**
  - The landing pages (`docs/{en,es}/README.md`) label their chart "resilience4cata 0.1.0" and show the bulkhead at 20.5 ns; the root `README.md` too. Current: 17.3 ns (`benchmarks.md#release-counters`).
  - `performance.md`: the headline bulkhead row (20.5 / 264 ns, "tie") predates 17.3 / 195.5 ns; "acquire/release … 23.2 → 9.3 ns" contradicts the volatile `getAndAdd` release; the main table was measured with 0.1.0.
  - `faq.md` "How fast is it?" puts 1.9 ns (4 threads) next to Resilience4j's 89.2 ns (1 thread). Use one row.
  - The root `README.md` cites the breaker with four threads (832 ns for Resilience4j) where the FAQ uses one thread (89 ns): the README, the landing pages, the FAQ and `performance.md` must cite the same rows.
  - Decide whether to rerun the headline table on AWS with the 1.0 code (`infra/bench.sh`), so that every cited number has one source.
  - **Outcome:** (#63) Done: bulkhead rows from [release counters](benchmarks.md#release-counters) (17.3 / 195.5 ns) in `performance.md`, the landing pages (legend and date too) and the README, which now also cites the breaker on one thread. The FAQ sentence was right: the breaker costs 1.9 ns on one and on four threads. Rerunning the headline table with the 1.0 code is left to 2.5's AWS run.
- [x] **2.2 Honest headlines.**
  - The breaker's 1.9 ns with four threads holds only while the window holds no failure (the `quiet` path). From about 1 % failures every call writes the shared cursor: "mixed", 274 vs 910 ns. Say so next to the headline.
  - Say that Resilience4j updates its built-in metrics on every call and resilience4cata keeps no per-call counters, which is part of the difference.
  - The timeout row (22 vs 8842 ns) compares a watchdog with an executor hand-off: move it out of the bold headline or qualify it.
  - "One clock-read pair per call, whatever the number of policies" is false for stacks (rate limiter, adaptive limit, timed breaker, retry with `maxDuration` each read the clock). Correct it.
  - The benchmarks measure average time in a closed loop, which hides tails: say so where the contended rate limiter and bulkhead are cited (their losers park), until the percentile runs exist.
  - x86 has never been timed: say so in `performance.md` ("x86-64 pending" is there; keep it visible next to the headline).
  - The circuit breaker's count window is approximate under contention (a stale opaque `quiet` read can skip recording one success right after a failure; the same-outcome slot skip can reorder writes after the window wraps). Write that in `policies/circuit-breaker.md` and `architecture.md`.
  - **Outcome:** (#63) Done: the breaker's quiet path, Resilience4j's per-call retry counters (checked in its 2.4.0 jar: `LongAdder` fields in `RetryImpl`; not claimed for its breaker), closed-loop averages, ARM only, the clock-read row, and the approximate count window (both races checked in the code). The timeout row was already qualified.
- [x] **2.5 Benchmarks behind the honest headlines.** Add a breaker case at a realistic failure rate (0.1 % and 1 %) next to "success" and "mixed", one and four threads, with Resilience4j at the same rate. Run it on AWS and cite it in 2.2.
  - **Outcome:** (#64) Done: `CircuitBreakerFailureRateBenchmark` (0 B, in the allocation gate), run on 8 Graviton3 machines with `-f 6` together with the success and one-in-ten cases ([realistic failure rates](benchmarks.md#failure-rates)). With four threads: 11.2 ns at 0.1 %, 65.4 ns at 1 %, against about 1 000 ns for Resilience4j. Cited in `performance.md`. The rest of the headline table (measured with 0.1.0) was not rerun: it keeps its source and date, and the rows measured since (bulkhead, breaker) cite their own run.
- [x] **2.3 Contradictions.**
  - `start/how-it-works.md`, "Policy order": "The order is not configurable yet" (wrong since `@PolicyOrder`).
  - Policy pages' fact strips say "x of 7"; there are 8 policies.
  - Root `README.md` "names can still change before 1.0" vs the FAQ "no more API changes planned". Keep the FAQ's.
  - `policies/retry.md` "Small print" cites R4C-ATR-006 for `enabled`: retired.
  - `errors.md` prefix table has no `R4C-ORD` row.
  - `guides/troubleshooting.md` [R4C-RT-004]: rewrite with the current rule (core may be newer than the processor; an older core is RT-021).
  - `start/installation.md` artifact table: the annotations row lacks `@Hedge`, `@AdaptiveLimit`, `@PolicyOrder`.
  - `policies/circuit-breaker.md` generated sample shows `50.0f`; generated code emits `50.0`. Refresh it from `examples/build/generated/...`.
  - The landing chips say "Sync and CompletionStage": add `Mono`/`Flux`.
  - The FAQ roadmap says "YAML configuration: v1" though Spring, Micronaut and Quarkus read YAML already: say what is meant (plain Java YAML files) or drop it.
  - **Outcome:** (#63) Done. "x of 7" was right (Retry and Hedge share one layer): it now says "layer x of 7", and the return types list `Mono` and `Flux`.
- [x] **2.4 Newcomer path.**
  - Quickstart step 6, "See the breaker open": lower `minimumCalls`, show `OPEN`, the `CallNotPermittedException` and the fallback answering (snippet tested in `examples`).
  - A guide "Retry and circuit breaker for an HTTP call": which exceptions to retry or abort on, a `@Timeout` inside, a breaker `name`, sensible numbers, idempotency. New page in both sidebars.
  - A short glossary, as definitions in `policies/README.md` or its own page: jitter and "full jitter", half-open and probes, instance id and derived id, registry, bulkhead, hedge, stackless exception, straight-line code, and the ABI check (`Abi.require` in generated samples).
  - Policy pages: move the generated-code block below "How it behaves" (or collapse it).
  - R4C-RT-003: the message, `errors.md` and installation's "Verify that it works" show only Gradle; add Maven.
  - `guides/spring.md`: the Maven tab adds `spring-boot-configuration-processor` with no version or reason; explain it or drop it. Move maintainer detail (versions tested on 4.1.0 and 4.1.2, AOT internals, the bytecode-reading transaction check) to a final "Under the hood" section. The transactions section (R4C-SPR-004) comes before "Configuration": put "Configuration" first.
  - Sidebar: label the "Project" group as for contributors.
  - `errors.md` is about 3,200 lines on one page: add a hint at the top (search with the code, the prefix table links to each area), or split it per area if the site generator and `ErrorCodeTest` allow it.
  - Maven: `annotationProcessorPaths` replaces the discovery of every other processor (Lombok, MapStruct), so adding ours can silently drop theirs. Check that `start/installation.md` says so clearly next to the Maven snippet, with the fix (list them all).
  - Defaults that surprise someone coming from Resilience4j: one short list in `migrate/from-resilience4j.md` (and linked from the FAQ). Candidates the reviewer named: a retry backs off exponentially with jitter from 200 ms by default; the rate limiter rejects at once (`maxWait = 0`); with annotated classes `@Transactional` is outside the policies, and the startup check refuses it, so the code must be restructured. Also the reviewer's claim that an `Error` counts as a success for the breaker: check it in `CircuitBreakerPolicy` and the generated layers first; if true, document it (and decide in phase 1 whether that is the right behaviour); if false, nothing to do.
  - Metric names: users migrating from Resilience4j must redo dashboards and alerts (`r4c.*`). Add a table from Resilience4j's metric names to ours in the migration page.
  - What Resilience4j has and we do not, in one place in the migration page (some is already there under "when to stay"): thread-pool bulkhead, cache, RxJava, Kotlin, hot reconfiguration (by decision), an automatic open → half-open transition, event endpoints, success and latency metrics, and that hedges do not carry the caller's context to the hedged attempts. Check each against the code before writing it.
  - **Outcome:** (#63) Done, with three things kept on purpose: the generated-code blocks stay beside the snippets (the side-by-side view is the point of the `compare` component, and its pane already scrolls); the Spring guide's details stay where they are (post-processor order, AOT and the native-image limit are user-facing; only the version line was simplified); the transactions paragraph stays under "Annotated classes", where it applies. The `Error` claim is true (`recordOn` defaults to `Exception`), already documented, and now linked from the FAQ.

Check: `./gradlew :examples:test --tests '*DocsTest*' :site:test`, and `./gradlew :site:serve` to look at the changed pages. 2.5 is a benchmark change (its own pull request, with an AWS run).

## Phase 3: suspected bugs

One pull request per confirmed bug. For each: first write a test that reproduces it (deterministic seam or jcstress, never timing), see it fail, then fix, then mutation-check the test. If it does not reproduce, write down why in this page and close the task.

- [x] **3.1 Bulkhead: a free slot with an async waiter left queued.** `BulkheadPolicy.exit` (around line 323): when `hasQueuedThreads()` and `yieldToSync()`, it releases and returns without draining the async waiters. If the sync thread it yielded to then times out, is interrupted, or loses to a barging caller, the slot stays free while an async waiter waits, until the next call; with no traffic that waiter is rejected at `maxWait`. Test through `enqueueForTest`/`drainForTest` and the semaphore. Possible fix: after the yield, drain if the semaphore still has a permit and no queued thread took it. jcstress does not cover the yield-to-sync hand-off today: add a case to `BulkheadWaitersStress` (or a new class) and mutation-check it.
  - **Outcome:** (#65) Confirmed and fixed. The new jcstress case `BulkheadWaitersStress.YieldToSyncThatGivesUp` saw the free slot with a waiter queued once in 1.45 million runs. A sync wait that ends without a slot now drains the async waiters, on the rejection path only. After the fix it passes in default mode (about 2.3 million runs); the mutation check is the run before the fix.
- [x] **3.2 Adaptive limit: inconsistent samples.** `AdaptiveLimitPolicy` (around line 281): `SUM` and `COUNT` of a stripe are separate lossy writes, so a race can keep one and lose the other; a stripe that goes back (a thread preempted between read and write) can make `sum - usedSum` negative, clamped to an RTT of 1 ns, which pushes Gradient2 up. Minimum fix: a negative or zero delta skips the window instead of becoming 1 ns; better, write sum and count consistently (one packed write, or a sequence number). Test with the `(id, config, env, stripes)` constructor and seams; add the stripe race to `AdaptiveLimitStress`, which does not cover it today.
  - **Outcome:** (#65) Confirmed and fixed. `aStripeThatWentBackIsNotReadAsAOneNanosecondWindow` (seam `loseLatencyForTest`) read the window as 1 ns before the fix. A summed count or sum below the used one now drops the window and resyncs. Not added to jcstress: it cannot pause a thread between the lossy read and write.
- [x] **3.3 Rate limiter: a stale `now` after the park.** `RateLimiterPolicy` (around line 134): `now` is read once before the loop; after a lost CAS and the 1 ns park (tens of µs on Linux), the wait is computed from an old time: the caller sleeps too long, or with `maxWait = 0` may be rejected when a permit is due. Check whether re-reading the clock after a park changes the benchmark (measure single and four threads). The same park gives the losing callers a long tail (about 50 µs of timer slack on Linux) that average-time benchmarks do not show: measure it with the percentile runs (after 1.0, or here if 3.3 changes the loop).
  - **Outcome:** (#65) Confirmed by reading; not reproducible by a test, because nothing can run between the read of `tat` and the compare-and-set. Fixed by reading the clock again after the park, on the contention path only. Measured on AWS: four threads, 183.6 against 187.0 ns, so no slower ([review fixes](benchmarks.md#review-fixes)). The tail stays for the percentile runs after 1.0.
- [x] **3.4 Timeout: the user's own interrupt is cleared.** `TimeoutPolicy` (around line 218): after the watchdog interrupts, `Thread.interrupted()` also clears an interrupt the caller had set before. Decide the semantics (keep a prior interrupt: re-set the flag if it was set before the call?) and test it with `Watchdog(0)`.
  - **Outcome:** (#65) Confirmed and fixed. `anInterruptTheCallerAlreadyHadSurvivesTheTimeout` failed before the fix. The watchdog records `callerInterrupted` before its release of `TIMED_OUT`, and the caller then keeps that interrupt. Timeout and watchdog jcstress pass.
- [x] **3.5 Retry budget: unbounded lost update.** `RetryBudget` (around line 125): a thread preempted between its opaque read and write can erase other threads' calls on its stripe; stripes are shared when threads outnumber 2 × CPUs. The direction is safe (fewer retries). Probably document, not fix: write the bound (or its absence) in the class Javadoc and `architecture.md`.
  - **Outcome:** (#65) Documented, not fixed: the RetryBudget Javadoc and `architecture.md` now say that the loss has no fixed bound, only lowers the budget, and happens only when stripes are shared.
- [x] **3.6 Timeout with virtual threads.** Every new thread's first timed call registers a `ThreadScopes` in a shared queue and the watchdog walks every registered thread each tick. With one virtual thread per request that is an allocation per request and a scan proportional to live threads. Benchmark it (a JMH case starting a virtual thread per call); then decide whether to fix or document.
  - **Outcome:** (#65) Measured with the new `VirtualThreadTimeoutBenchmark`: about 270 B per new thread; no time cost with one thread; +0.8 µs (+8.6 %) on a 9.3 µs thread start with four threads starting at once. Documented on the timeout page and in [benchmarks](benchmarks.md#review-fixes), not changed: only a thread's first timed call pays.

## Phase 4: release

- [x] (#66) The second review's documentation fixes: the first timed call of a new thread allocates (`performance.md`), the rate limiter (not the bulkhead) parks, the clock-reading features, quickstart step 6's wording and a link to the HTTP guide, a test for the guide's timeout row, the FAQ's defaults link, the adaptive limit's approximate average, this status.
- [x] (#66) `SECURITY.md`: supported versions and how to report a vulnerability privately.
- [x] One full jcstress run on ARM (AWS Graviton), since CI runs it only on x86.
  - **Outcome:** 2026-11-02, one `c7g.4xlarge` (16 Graviton3 cores), `-m default`, about 40 minutes: 65 tests, 2598 configurations, none failed, no errors. One test is "interesting", as expected: `RetryBudgetStress.CallsAreNeverOvercounted` lost calls in 10.9 % of its samples, never counted one too many. That is the documented loss of task 3.5. We then wrote that it shows far more often on ARM than on x86; CI's later runs on both (4-core runners, quick mode) saw similar rates, 5.5–8.6 % on ARM and 6.3–18.6 % on x86, since losing a call needs no reordering ([blog](blog/jcstress-on-arm-and-x86.md)). The run used a throwaway Terraform configuration outside the repository; running it on a schedule is in "After 1.0".
- [x] Decide whether a missing processor must fail the startup in Spring, Micronaut and Quarkus before 1.0 (owner's decision; the adopter argues that adding it later changes behaviour within the major).
  - **Outcome:** yes, before 1.0.0, in a 1.0.0-RC2. A bean whose class (or a superclass), or an interface it implements, declares policies but has no generated class now fails:
    - the Spring startup with R4C-SPR-006;
    - the creation of a Micronaut bean of an annotated class with R4C-MN-007 (an annotated interface is wrapped only through `@ResilientBean`, which already fails with R4C-RT-003);
    - the Quarkus build with R4C-QK-008.

    Each has a switch to turn it off (`allow-missing-processor`), and excluded beans are not checked. Spring and Micronaut read the class files (`PolicyAnnotations`, one copy in each, kept in sync), so the check does not run in a native image; Quarkus uses the Jandex index. Tests load the annotated fixtures without their `$Resilient`: a class loader that hides it (Spring, Micronaut), or an application root without it (Quarkus). Each check was mutation-checked.
- [x] Decide on a release candidate: `1.0.0-RC1` on Central, one or two weeks of use, then 1.0.0. (Owner's decision.)
  - **Outcome:** yes. The install snippets stayed at 0.13.0 until RC3; since then they show the latest release candidate (owner's decisions).
- [x] Release 1.0.0-RC1 following [releasing](releasing.md).
  - **Outcome:** released 2026-11-02 (commit `release 1.0.0-RC1`, tag `v1.0.0-RC1`, `release.yml` run 41553226849, published in the Central portal by the owner).
    - `apiCompatibility` against 0.13.0 showed only the intended changes: the renamed event, the two Micronaut constructors, and `FluxCall$Attempt` (source only).
    - The generated-code check (`-Pr4c.generatedBy=0.13.0`) does not apply: 0.x to 1.0 crosses a major version, and the tests already use `CircuitBreakerEvent.Rejected`.
    - The landing page's four comparisons were measured again on the release candidate's code ([benchmarks](benchmarks.md#release-candidate), #67).
- [x] After the release candidate: `version=1.0.0-SNAPSHOT`, `r4c.apiBaseline=1.0.0-RC1`, javadoc.io synced and generated for 1.0.0-RC1 in all 11 modules.
- [ ] Release 1.0.0 after one or two weeks of use, unless something has to change:
  - version, tag `v1.0.0`, `release.yml`, Publish in the portal;
  - then the install snippets to 1.0.0 (not the blog), `r4c.apiBaseline=1.0.0`, javadoc.io;
  - from then on, the generated-code check (`-Pr4c.generatedBy=<release>`) is step 1 of every release.

## Third review :id=third-review

The same four reviewers read 1.0.0-RC2 on 2026-11-05. None ran the whole build; the newcomer ran the quickstart against the RC2 jars from Maven Central.

| Reviewer | Verdict |
|---|---|
| An API designer | Ship 1.0.0 with this API; nothing would be a permanent mistake. |
| A performance and concurrency skeptic | Ship 1.0.0: no ordering, lost wake-up, ABA or exactly-once bug found in core; the phase-3 fixes hold. One false positive in the RC2 check. |
| A newcomer | A call protected in about 10 minutes; the quickstart's output matched exactly on RC2. Stale statements about the release candidate. |
| A Resilience4j user deciding whether to adopt | A pilot on two or three services, not production yet: no outside user so far, a single maintainer, and Spring Boot 4.1 only. |

- [x] A type whose annotations reach no method (an empty annotated interface, a class with only final methods) generated nothing and reported nothing, and Spring and Quarkus then reported the processor as missing. Now [R4C-APT-007](errors.md#r4c-apt-007); a bundle with only `@PolicyOrder` no longer counts as a policy in the integrations' checks.
- [x] The breaker counted an `Error` as a success (`recordOn = Exception`), unlike Resilience4j. Owner's decision: `recordOn` defaults to `Throwable`.
- [x] The Quarkus jars had no `Automatic-Module-Name`; the missing-processor messages named the processor without a version.
- [x] Documentation: the landing page, FAQ, installation, troubleshooting, annotated classes, timeout, compatibility and performance pages, and the RC2 blog post (see the changelog's Unreleased entry).
- [x] Release 1.0.0-RC3 with these changes (the breaker default and the new compile error change behaviour), then 1.0.0.
  - **Outcome:** released 2026-11-05 (commit `release 1.0.0-RC3`, tag `v1.0.0-RC3`, `release.yml` run 42052831462, published in the Central portal by the owner). The install snippets show 1.0.0-RC3.

Left for after 1.0 (below): the Spring and Micronaut missing-processor checks in a native image (Spring could run it in its AOT step), `Ticker.onAdvance` without a way to unregister, `onInstanceCreated` without the new instance, no way to stop the shared daemon threads (a leak in a reloadable web application), the watchdog's tick that only shrinks, and the different switch names of the three integrations (deliberate; to be written in [design](design.md)). Outside the code: no outside feedback yet. Branch protection on `main` and GitHub releases for the release candidates were added on 2026-11-06 (step 8 of [releasing](releasing.md#release-steps)).

## After 1.0

Additive, so not blocking the freeze. Roughly in order of value. Items marked *(asked before 1.0)* are ones a reviewer wanted before the release: the owner decides whether to pull them forward.

- Run the Resilience4j comparison on x86 (`c7i`) and publish it next to Graviton; on x86 every atomic is a full fence and `nanoTime` is cheap, so the ARM-tuned choices may rank differently. *(asked before 1.0)*
- ~~jcstress in default mode on an ARM runner (`ubuntu-24.04-arm` or Graviton) on a schedule.~~ Done before 1.0: `stress` runs quick mode on x86 and ARM after every push to main and nightly, and `stress-arm` runs default mode on ARM weekly. The mutants were then rerun on both machines (`stress-mutants`, by hand, quick and default mode): ARM catches nothing that x86 misses. The control and the two store-buffering mutants are caught on both; the five that only weaken an ordering survive on both, and on Apple M1. Two of those five (the bulkhead semaphore's re-check, the waiter count) are ordered anyway by an atomic read-modify-write just before them; the three in `TimeoutPolicy` are beyond what these machines reorder, even with the test added for them (`TimeoutStress.TickSeesWholeCall`). *(asked before 1.0)*
- Percentile and open-loop benchmarks (`SampleTime`) for the contended rate limiter and bulkhead. *(asked before 1.0)*
- ~~A loud failure in Spring, Micronaut and Quarkus when annotated classes have no generated class.~~ Done before 1.0: see phase 4.
- Optional success and latency metrics, measured before adding (per-call shared writes), for parity with Resilience4j dashboards. *(asked before 1.0)*
- Spring Boot 3 support, or a documented reason and migration path. *(asked before 1.0)*
- Project health: a second maintainer with release rights, a security policy (`SECURITY.md`) and a support policy. *(asked before 1.0)*
- Additive API gaps the API reviewer listed:
  - `Ticker.onAdvance` has no way to unregister.
  - Events carry only `nanoTime`, no wall-clock time.
  - `ConfigException.problems()` returns `List<String>`, not structured problems (code, key, origin).
  - Only circuit breakers have a list method on the registry; retry, hedge and timeout have no handles.
- Features Resilience4j has, each to decide (build, or document why not): thread-pool bulkhead, cache, RxJava, Kotlin support, an automatic open → half-open transition, event endpoints, carrying the caller's context into hedged attempts.
- Reassess adoption signals in about six months, as the adopting reviewer would: activity, maintainers, issues closed. A pilot on one high-traffic plain Java service is what they would recommend first.
- From the third review: the missing-processor check in a Spring or Micronaut native image (Spring could run it in its AOT step, on the JVM); `Ticker.onAdvance` returning a way to unregister; `onInstanceCreated` passing the new instance; a way to stop the shared daemon threads, or documentation that `core` belongs on the server's class path in a reloadable web application; the watchdog's tick growing again when no short timeout is left; the three integrations' switch names written down as deliberate in [design](design.md#api-decisions).
