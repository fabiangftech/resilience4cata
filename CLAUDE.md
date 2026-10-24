# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

resilience4cata is an annotation-driven resilience library for plain Java 21+ (no Spring/CDI/AspectJ), designed to have lower overhead than Resilience4j. The project is in **phase 2: first code**. Implemented so far: `@Retry`, `@CircuitBreaker` and `@Timeout` end to end. The other annotations exist in `annotations` but have no runtime or processor support yet.

## How the pieces fit

- Each supported policy is a `Kind` in `ResilienceProcessor` (annotation, generated field prefix, runtime policy class, registry method, config class). Kinds are declared in nesting order, outermost first: `RETRY`, `CIRCUIT_BREAKER`, `TIMEOUT`. `writeMethod` emits one layer per policy present: the public method holds the outermost layer, and each inner layer is a private method `r4c$<field><index>(...)` with the same signature, ending with the delegate call. Each layer builder (`retry`, `circuitBreaker`, `timeout`) receives the inner call as an expression. Adding a policy means a new `Kind` in the right position, its attribute validation in `policyModel`, a layer builder, a `*Config` record + builder, a `*Policy` class in `dev.resilience4cata.generated`, and registry methods.
- `processor` (`ResilienceProcessor`) scans every root element (`getSupportedAnnotationTypes` is `*`, because composed annotations are user-defined). For each interface that uses `@Retry`, directly, at type level or through a `@ResiliencePolicy` bundle, it writes a top-level class named `<binary name>$Resilient` (for example `Repository$Cache$Resilient`) in the same package. The class implements the interface, delegates every method, and holds one `final` `RetryPolicy` field per protected method.
- Attribute merging is in `PolicyAttributes` (one instance per policy annotation): only explicitly set, non-sentinel values count. Precedence is declaring interface < wrapped interface < method; a direct annotation beats bundles; two bundles disagreeing is a compile error. Durations are parsed at compile time (`Durations`) and emitted as `Duration.ofNanos(...)`.
- Types in generated signatures are printed by `TypePrinter`, never `TypeMirror.toString()`, so type-use annotations such as `@Nullable` do not produce invalid source.
- At runtime, `Resilient.of(type, impl, registry)` loads `<type>$Resilient` once by name and calls its `(type, ResilienceRegistry)` constructor. The constructor calls `registry.retryPolicy(instanceId, name, config, overrides)`, which resolves defaults → named config → annotation overrides into an immutable `RetryConfig` and shares instances by `name`.
- The generated retry loop calls `RetryPolicy.start()`, `onFailure(attempts, e, start)` and `pause(delay)` (package `dev.resilience4cata.generated`, exported only for generated code). Nothing is allocated on the success path.
- `CircuitBreakerPolicy` (also the public `CircuitBreakerHandle` returned by `registry.circuitBreaker(id)`, with `forceOpen`/`disable`/`reset`): the state is an immutable `Phase` (`Closed`/`Open`/`HalfOpen`/`ForcedOpen`/`Disabled`) swapped by CAS through a `VarHandle`; objects are allocated only on transitions. Every transition goes through `transition(...)`, which swaps the phase and then clears `quiet`. `Closed` holds a `SlidingWindow`: `CountWindow` (default) or `TimeWindow` (one-second buckets, `LongAdder` counters, never quiet because every success must be counted). `acquire()` returns the phase's generation as the token, and outcomes with a stale token are dropped. `CountWindow` is a slot array updated with `getAndSet` plus counters, and skips rewriting a slot that already holds the same outcome. While closed with a window of only fast successes, the policy's `quiet` field holds the generation and `acquire`/`onSuccess` read only that field (ordering: count a failure before clearing `quiet`; set `quiet` before re-checking the window). Reads of `phase`/`quiet` are opaque, which is safe because everything they reach is `final`.
- Performance rules learned while tuning the breaker (see `docs/en/benchmarks.md`): under contention, writes to shared cache lines cost far more than locks or atomics save, so never write a value that does not change and keep per-call-written fields (the window cursor) apart from read-mostly ones. On ARM, avoid acquire/volatile reads on the hot path where `final`-field publication already suffices. Measure single-thread and multi-thread, success and mixed, after every hot-path change: improvements to one case have regressed another more than once. Rejections throw one preallocated, stackless `CallNotPermittedException` per breaker. Calls are timed only if `slowCallDuration` is set. `RetryConfig.DEFAULTS` aborts on `CallNotPermittedException`, so an open breaker stops retries.
- `TimeoutPolicy` + `Watchdog`: one shared daemon thread publishes a coarse clock and checks, every tick, a per-thread stack of reusable `Scope`s; timed calls read no clock and write only their own scope. A scope's state is a stamp (call number in the high bits, state in the low two) so a watchdog that read an earlier call cannot interrupt the next one (ABA). On the caller side, `RUNNING → IDLE` by CAS; if the watchdog won, wait for `TIMED_OUT`, clear the interrupt, throw `CallTimeoutException`. Tests use a manual `Watchdog(0)` advanced with `tick(nanos)`.
- Races that a stress test cannot reproduce reliably are tested deterministically through package-private test seams that replay the interleaving (`phaseForTest`/`publishQuietForTest`). When adding such a test, check it by mutation: remove the guard it protects and confirm the test fails.
- `System.nanoTime()` costs about 21 ns single-threaded and 160+ ns under contention on macOS (HotSpot keeps it monotonic with a global CAS). Avoid clock reads on hot paths unless the feature needs them (slow-call detection, time windows).
- Not supported yet, and rejected by the processor with an explicit error: async return types (`CompletionStage`), `classifier`, annotations on classes. The processor is not registered as incremental for Gradle yet.

Start with `docs/en/README.md` (decisions and open questions), then `docs/en/features.md` and `docs/en/architecture.md`. The detailed research behind them is in `docs/en/research/01–05`.

## Decisions to respect

- **Java 21 baseline** (`--release 21`), tested on 21 and 25. Do not use APIs that are preview or newer than 21 (e.g. `StructuredTaskScope`, the Class-File API) in published modules.
- **No Kotlin anywhere**, including the build. Gradle uses the Groovy DSL, kept minimal and declarative (`settings.gradle`, short `build.gradle` per module). All build logic goes in convention plugins written in Java under `conventions/src/main/java` (plugin `r4c.java-library`: Java 21 toolchain, `--release 21`, `-Xlint:all -Werror`, JUnit Platform, `resilience4cata-<module>` jar names). Versions live in `gradle/libs.versions.toml`.
- Interfaces referenced by annotations (`Fallback<T>`, `Classifier<T>`) live in `core`; annotation attributes are `Class<?>` and the processor checks the type. This keeps `annotations` `compileOnly`.
- **Coordinates**: groupId `dev.resilience4cata` (the project owns `resilience4cata.dev`); root Java package and JPMS module prefix `dev.resilience4cata`.
- **`annotations` is a separate module from `core`.** Durations in annotations and config are strings (`"250ms"`, `"2s"`, `"PT2S"`), validated by the processor.
- **Modules have single-word names**: `annotations`, `core`, `processor`, `config`, `micrometer`, `testkit`, `bom`, plus unpublished `benchmark`, `stress`, `examples`. Never name a module `test` (clashes with Gradle's `test` task). Published artifactIds get the `resilience4cata-` prefix.
- **Annotations are handled by a compile-time annotation processor (`processor`) that generates plain Java source.** No runtime proxies, bytecode generation, AspectJ or agents in `core`. The processor is **mandatory**: if a generated class is missing, `Resilient.of` must fail fast with a message showing the `annotationProcessor` dependency to add; there is no reflection fallback. Annotations use `CLASS` retention, so `annotations` is a `compileOnly` dependency for users.
- **`core` and `annotations` have zero external dependencies** (JDK only, except JSpecify as an `api` dependency of `core`). `processor` is only ever an `annotationProcessor` dependency. Generated code may only use `core`'s exported generated-code API.
- **Generated code must be straight-line and per method**, calling the allocation-free permit protocol in `core` (acquire / onSuccess / onFailure). Do not route generated code through a shared lambda executor: our JMH run showed it doubles the overhead once several services share it (see `docs/en/research/02-annotation-mechanisms.md` §3).
- **Hot paths**: no allocation on success paths, no locks and no `synchronized` (pinning on JDK 21), one clock-read pair per call, preallocated stackless rejection exceptions, events allocated only when someone listens. Configuration is resolved once at instance creation, never per call, and is immutable afterwards: there is no hot reload or `reconfigure()` API, by decision.
- **Interface vs implementation annotations**: with `Resilient.of(interface, impl, registry)` only interface annotations count (annotating the implementation is a compile error). In the future subclass style, implementation annotations win attribute by attribute: interface < interface method < class < class method.
- **Self-invocation is intercepted** in the generated-subclass style (`this.other()` goes through the override). It cannot be intercepted in the interface-wrapper style; that limit is documented, not worked around.
- Default policy order, outermost first: `Fallback → Retry|Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method`.

## Commands

```bash
./gradlew build                                   # compile, test, javadoc for every module
./gradlew :core:test                              # tests of one module
./gradlew :core:test --tests 'dev.resilience4cata.SomeTest'          # one test class
./gradlew :core:test --tests 'dev.resilience4cata.SomeTest.method'   # one test method
./gradlew :examples:test                          # end-to-end tests against real generated code
./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"   # JMH; options after -Pjmh, results in benchmark/build/jmh/results.json
./gradlew :benchmark:jmh -PjmhJdk=25              # benchmarks on another JDK
```

Benchmarks (`benchmark` module, plugin `r4c.jmh` in `conventions`) use plain JMH (annotation processor + `org.openjdk.jmh.Main`), no third-party Gradle plugin. Every comparison with Resilience4j must use equivalent configuration and document any adjustment made for fairness in the benchmark's Javadoc (for example `resilience4jRetryOnceNoSleep`, which removes Resilience4j's unconditional `Thread.sleep(0)`).

Where tests go:
- `core`: unit tests of policies and config. `RetryPolicy` has a package-private constructor that takes a fake clock and sleeper.
- `processor`: compile Java sources in-memory with the processor via the `Compilation` helper (plain `javax.tools`, no compile-testing library) and assert on errors or generated source. Compilation uses `-Xlint:all -Werror`, so generated code must be warning-free.
- `examples`: annotated interfaces under `src/test/java` are processed by `testAnnotationProcessor project(':processor')`; tests call `Resilient.of(...)` on them. Generated sources land in `examples/build/generated/sources/annotationProcessor/java/test`.

The build runs on any JDK ≥ 17 and compiles with a Java 21 toolchain (auto-detected locally). Configuration cache is on: build logic must stay configuration-cache compatible.

## Documentation rules

- Docs are plain Markdown, **bilingual**: `docs/en/**` and `docs/es/**` must have the same file tree. English is the source of truth; any change to an English doc must be mirrored in the Spanish one. Code and identifiers stay in English in both.
- `docs/` is a Docsify site, not a Gradle module. Sidebars are `docs/en/_sidebar.md` and `docs/es/_sidebar.md`; add new pages there. Keep Markdown tool-neutral (CommonMark/GFM plus Mermaid fences).
- Research docs tag claims as verified, measured or inferred. Keep that discipline, and never state a performance number for resilience4cata that has not been measured.
- Preview the site locally: `python3 -m http.server -d docs 3000` (or `npx docsify-cli serve docs`).
