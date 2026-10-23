# 02 · Annotation mechanisms: turning annotations into behavior without a framework

> **Phase 1, research only.** No production code. This document compares the ways a standalone (no Spring, no Quarkus, no CDI) Java 21+ library can turn `@CircuitBreaker`, `@Retry` and similar annotations into running behavior. It ends with a ranked recommendation and a sketch of the user-facing API.
>
> **How to read the evidence:**
> - **[V] Verified**: checked against a primary source during this research (link given), or measured by us (see [§3](#3-our-own-measurements-jmh)).
> - **[I] Inferred**: comes from engineering judgment or prior knowledge and was not re-checked against a source for this document. Treat it as a hypothesis to confirm in phase 2.
>
> Spanish version: [`docs/es/research/02-annotation-mechanisms.md`](../../es/research/02-annotation-mechanisms.md).

---

## 0. TL;DR

1. **Recommended: compile-time annotation processing (APT) that generates plain Java source**, with a small runtime façade (`Resilient.of(...)`) that finds the generated class once, when the instance is created. Per call it costs about as much as hand-written code: no reflection, no `Object[]` boxing, and one monomorphic call site per generated method. It starts up instantly, works in GraalVM native-image without configuration, produces readable stack traces, and catches errors such as a bad fallback signature or an unknown config name at compile time.
2. **The shape of the generated code matters as much as the mechanism.** In our JMH run, generated code that calls the policy through a shared lambda-taking `execute(Supplier)` was **about 2× slower** than generated code with the policy protocol inlined once several services shared the path (14.2 ns vs 7.3 ns for 4 calls) **[V, measured]**. The core module should therefore offer a low-level, allocation-free "permit protocol" (acquire, then onSuccess or onError) for generated code, next to the functional API it offers humans.
3. **`java.lang.reflect.Proxy` is 5–15× slower per call and allocates** (16–64 B per call in our run). It only handles interfaces. It is acceptable only as an **optional fallback**.
4. **ByteBuddy at runtime is as fast per call as generated source** (it emits real bytecode), but the first instance took **about 200 ms vs 0.37 ms** for generated source **[V, measured]**. It also adds a 9 MB dependency, and native-image needs extra build steps. It is not the default; at most it could back an optional module.
5. **Java agents and AspectJ weaving** have good per-call performance but the worst user experience: JVM flags, a dynamic-attach warning since JDK 21, a weaver that must track each new JDK, and a non-standard compiler. Rejected for the core.
6. The **Class-File API is final only in JDK 24** **[V]**, so it is not usable at our Java 21 baseline without a multi-release JAR. Keep it in mind for when the baseline moves to Java 25 LTS.

---

## 1. The problem

For each annotated method we must, without a container:

1. **Discover** the annotations (`@CircuitBreaker("payments")`, `@Retry(maxAttempts = 3)`, `@Fallback(...)`).
2. **Resolve configuration**: a named config, then global defaults, then per-method overrides from annotation attributes, then external config. (The API and config design agent owns the precedence rules. This document only cares *when* resolution happens, at build time, wrap time or call time.)
3. **Build an instance** whose method runs the policy chain around the user's code.
4. **Hand that instance to the user**, with no DI container to inject it.

The per-call cost is decided by step 3: how the wrapping code is produced and what the JIT can do with it.

### Evaluation criteria

| Criterion | Why it matters for us |
|---|---|
| Per-call overhead | Our main goal ("faster than Resilience4j"). Covers JIT inlining, megamorphic call sites, reflection, boxing and allocation. |
| Startup / first-call cost | CLI tools, serverless, tests. |
| GraalVM native-image | Increasingly expected for libraries. Runtime class definition is not supported there. |
| Debuggability | Stack traces, stepping, "what code actually runs?". |
| Build complexity for users | We have no framework plugin to hide complexity behind. |
| Limitations | Final classes, private methods, interfaces vs classes, self-invocation. |
| Sync / `CompletableFuture` / virtual threads | All three have to work. |

---

## 2. The options

### 2.1 Compile-time annotation processing (APT) generating source

**How it works.** A `javax.annotation.processing.Processor` runs inside `javac`, reads `@CircuitBreaker` and related annotations from the `Element`/`TypeMirror` model, and writes new `.java` files through the `Filer`. Those files are compiled in the same build. Nothing runs at runtime except the code that was generated.

**Prior art.**
- **Dagger, MapStruct, Immutables**: classic generate-source processors (no AOP). **[I]**
- **Avaje Inject** generates `MyService$Proxy` classes that **extend the target class and override annotated methods**. The overrides run the interceptors, then call `super`. Aspects are declared with `@Aspect`, `AspectProvider<T>` and `MethodInterceptor`, and `@AOPFallback` names a recovery method. **[V]** ([avaje.io/inject](https://avaje.io/inject/#aspects))
- **Micronaut AOP**: by default it generates a **subclass** of the target at compile time and calls the original through `super`. `@Around(proxyTarget = true)` makes it generate a delegating proxy instead. Micronaut's docs say the subclass default is "more efficient" and lets interception work for calls made from inside the class. **[V]** ([`@Around` javadoc](https://docs.micronaut.io/4.8.2/api/io/micronaut/aop/Around.html)). Micronaut also ships `@Retryable` and `@CircuitBreaker` built on this mechanism, and has an `InterceptedMethod` helper for `CompletionStage` and reactive return types. **[I]** ([Micronaut AOP guide](https://docs.micronaut.io/latest/guide/#aop))

**Generation tooling.**
- **Palantir JavaPoet** (`com.palantir.javapoet:javapoet`) is the maintained fork. Its README describes Square's JavaPoet as "no longer actively maintained" and adds builders for records and sealed types. **[V]** ([palantir/javapoet](https://github.com/palantir/javapoet), [Square deprecation note via mirror](https://docsearch.algolia.com/mcp/docs/repo/square/javapoet))
- Alternative: hand-written templates with no dependency. The generated code is small and regular, so this is realistic and keeps the processor free of dependencies. **[I]**
- The processor JAR is needed **only at compile time** (`annotationProcessor` configuration), so JavaPoet never reaches the user's runtime classpath. **[I]**

**Incremental processing in Gradle [V]** ([Gradle docs](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing)):
- A processor declares itself in `META-INF/gradle/incremental.annotation.processors` as `isolating`, `aggregating` or `dynamic`.
- **Isolating** is the fastest. Each generated file must have **exactly one originating element**, and decisions may only use what is reachable from that element. One `Foo$Resilient` per annotated `Foo` fits this perfectly.
- **Aggregating** reprocesses all annotated files. We would need it if we generated a single registry such as a `META-INF/services` file. **Design consequence:** find generated classes **by naming convention** (`Foo` → `Foo$Resilient`) instead of through an aggregated registry, so the processor can stay *isolating*.
- Other constraints: use only the `Filer` API, avoid compiler-internal APIs such as `com.sun.source.util.Trees`, and remember that parameter names are only available with `-parameters`.

**JDK 23 changed the javac default [V]:** annotation processing no longer runs implicitly from the classpath. It needs explicit configuration such as `-processorpath` or `-proc:full`. ([JDK 23 release notes](https://www.oracle.com/java/technologies/javase/23-relnote-issues.html), [JDK-8321314 review](https://mail.openjdk.org/pipermail/compiler-dev/2024-May/026675.html)). Gradle's `annotationProcessor` configuration passes an explicit processor path, so Gradle users should be unaffected **[I]**. Our docs must still show the `annotationProcessor(...)` line, and Maven users need `annotationProcessorPaths`.

**Wrapper styles the processor can generate:**

| Style | Generated shape | Pros | Cons |
|---|---|---|---|
| **Delegating wrapper** | `final class Foo$Resilient implements Foo { final Foo delegate; ... }` | Works for **interfaces**. Wraps *any* instance, including ones from another DI container, a third-party factory or a test double. Never intercepts self-calls, which is predictable. | One extra interface dispatch to the delegate. Only interface methods can be decorated. |
| **Subclass** | `final class Foo$Resilient extends Foo { ... super.call() ... }` | Works for **concrete classes**. Measured as slightly faster than delegation ([§3](#3-our-own-measurements-jmh)). Can intercept self-invocation if we want it to. | The class must not be `final`, and the methods must not be `final`, `private` or `static`. Each constructor has to be mirrored. The *user* must create the subclass instead of `new Foo(...)`. |

**Hard limits of APT [I, standard Java semantics]:**
- It cannot modify the annotated class itself; APT can only *add* files. So it cannot decorate `private`, `final` or `static` methods, or final classes when generating a subclass.
- It only sees code compiled in the same build: it cannot decorate a third-party JAR's classes. That case is covered by the delegating wrapper over an interface, or by the functional API.
- It does not support Kotlin, unless a KSP or kapt port is written. Out of scope.
- The processor's error messages *are* the user's experience, so they need to be excellent.

**How users get the instance.** Options, from most explicit to most convenient:
1. `new PaymentClient$Resilient(delegate, registry)`: explicit, but a `$` name in user code is ugly.
2. A generated static factory: `PaymentClientResilience.wrap(delegate, registry)`. It is type-safe and has zero magic.
3. A runtime façade, `Resilient.of(PaymentClient.class, delegate)`. It finds `PaymentClient$Resilient` **once** via `Class.forName` plus a `ClassValue` cache, calls a generated constructor or `MethodHandle` that is created once, and returns the instance. **There is no reflection per call.** The processor can also emit `META-INF/native-image/.../reflect-config.json` for that constructor, so native-image needs no configuration from the user. **[I]**

   We recommend offering (3) as the documented entry point and (2) for people who want no lookup at all.

**Scorecard.**

| Criterion | Assessment |
|---|---|
| Per-call overhead | **Best in class.** It is ordinary Java code: one monomorphic call site per generated method, so the JIT can inline the policy and the user method. The return type is known statically, so there is no boxing and no `Object[]`. Measured at 2.4–2.6 ns vs 1.2 ns for a direct call ([§3](#3-our-own-measurements-jmh)). **[V]** |
| Startup | **Best.** Loading the first instance cost about 0.37 ms in our run, which is just class loading. **[V]** |
| native-image | **Native.** No proxies and no runtime bytecode. At most it needs one reflective constructor, and we can generate that metadata. **[I]** |
| Debuggability | **Best.** Generated source sits in `build/generated/sources/annotationProcessor`, so users can open it and step through it. Frames look like `PaymentClient$Resilient.charge(PaymentClient$Resilient.java:41)`. **[I]** |
| Build complexity | Low: one `annotationProcessor` dependency line. IDEs (IntelliJ, Eclipse) run processors out of the box. **[I]** |
| Sync / async / VT | The processor sees the **declared return type** at compile time and emits specialised code for sync results, `void`, and `CompletionStage`/`CompletableFuture` (attaching callbacks instead of blocking). It needs no runtime `instanceof` checks. Virtual threads need no special code generation: sync code simply blocks on a virtual thread. The pinning concerns belong to the core (see the concurrency agent). **[I]** |
| Extra benefit | **Compile-time validation**: a missing fallback method, a mismatched fallback signature or conflicting annotations become compile errors. Resilience4j's Spring integration resolves `fallbackMethod` by name through reflection at runtime **[I]** ([resilience4j source](https://github.com/resilience4j/resilience4j)). |

### 2.2 Runtime dynamic proxies (`java.lang.reflect.Proxy`)

**How it works.** `Proxy.newProxyInstance(loader, interfaces, handler)` spins a proxy class at runtime. Every call goes to `InvocationHandler.invoke(proxy, Method, Object[] args)`.

**Facts:**
- It handles **interfaces only**. **[I, JDK API contract]**
- Since **JDK 18 (JEP 416)**, `Method.invoke` is implemented on method handles. It is **43–57% faster when the `Method` is a constant-foldable `static final`**, but **slower when it is not** (51–77% slower field access in microbenchmarks). **[V]** ([JEP 416](https://openjdk.org/jeps/416)). In a proxy handler the `Method` arrives as an *argument*, so it is generally **not** a constant at the `invoke` site. This is the bad case.
- The handler is **shared**: one `InvocationHandler.invoke` body, and the `Method.invoke` inside it, serve every method of every proxied service. Those sites become **megamorphic** in any real application, which stops inlining. **[I, consistent with our poly4 measurement]**
- Arguments are boxed into an `Object[]`, primitives are boxed, and checked exceptions arrive wrapped in `InvocationTargetException` or `UndeclaredThrowableException`. **[I]**
- **native-image:** proxies have to be generated at image build time. Native Image detects `newProxyInstance` calls automatically **only when the interface array is a constant or is allocated in the same method**. Otherwise you need JSON proxy configuration. **[V]** ([GraalVM Dynamic Proxy docs](https://www.graalvm.org/jdk17/reference-manual/native-image/dynamic-features/DynamicProxy/), [metadata reference](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)). A library that proxies *user* interfaces handed in at runtime falls into the configuration-needed case.
- **MethodHandle variant:** caching a bound, spread `MethodHandle` per `Method` did **not** help in our run. It was slower than plain `Method.invoke` (25 ns vs 16 ns mono, 91 ns vs 37 ns poly) because of the map lookup and the non-constant handle **[V, measured]**. Non-constant `MethodHandle`s do not inline, which matches what OpenJDK engineers have said ([mlvm-dev thread](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006826.html)) **[V]**.
- **LambdaMetafactory** can turn a `Method` into a real functional-interface implementation, avoiding reflection once the lambda is created. Older JMH runs show it close to a direct call when inlining succeeds ([Timefold](https://timefold.ai/blog/java-reflection-but-much-faster), [mlvm-dev](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006827.html)) **[V]**. It does not remove the shared, megamorphic handler, though, so it improves the *target* call and leaves the dispatch problem in place. **[I]**

| Criterion | Assessment |
|---|---|
| Per-call overhead | **Poor.** 16 ns mono / about 9 ns per call in poly4, vs 2–2.6 ns for generated code; 16–64 B allocated per call. **[V, measured]** |
| Startup | First proxy instance about 2.4 ms. **[V, measured]** |
| native-image | Needs configuration for interfaces passed in at runtime. **[V]** |
| Debuggability | Mediocre: `jdk.proxy1.$Proxy7.charge(Unknown Source)`, then handler frames, then reflection frames, plus wrapped exceptions. **[I]** |
| Build complexity | None. |
| Limitations | Interfaces only. |

**Verdict:** a reasonable **zero-build-setup fallback** for interfaces, for example a `dynamic` add-on module, but never the default path.

### 2.3 Runtime bytecode generation (ByteBuddy, Class-File API, hidden classes)

**ByteBuddy.**
- It generates real bytecode at runtime, as subclasses or interface implementations. `Advice` *copies* the advice code into the generated method, while `MethodDelegation` emits a direct call to an interceptor. **[V]** (behaviour confirmed by our benchmark design and results)
- Per call it is **excellent**, as fast as or slightly faster than our generated-source variants in our run (2.0 ns mono, about 1.5 ns per call poly4). Part of that edge comes from the benchmark itself: our ByteBuddy variants read the guard from a `static final` field (constant-folded) and the generated-source variants read an instance field. See [§3](#3-our-own-measurements-jmh). **[V, measured + I for the explanation]**
- **Startup is expensive:** about **200 ms** to create the first instance in a fresh JVM, most of it loading and initialising ByteBuddy itself. **[V, measured]**
- **Dependency weight:** `byte-buddy-1.18.3.jar` is **9.2 MB** **[V, local file]**. Shading it is possible, but it is a lot to put on every user's classpath.
- **JDK tracking:** Byte Buddy publishes a minimum-version table per Java class-file version. Since 1.17.0 it uses the Class-File API to follow new JDKs unless new class-file features appear. **[V]** ([raphw/byte-buddy README](https://github.com/raphw/byte-buddy))
- **native-image:** classes cannot be defined at runtime. The supported routes are generating classes at build time, or the tracing agent's best-effort "predefined classes", which the docs say are "not guaranteed to work". **[V]** ([GraalVM metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)). ByteBuddy has a **Gradle plugin for build-time transformation** (`net.bytebuddy.byte-buddy-gradle-plugin`), which would make it a weaving tool (see §2.4). **[V]** ([Gradle Plugin Portal](https://plugins.gradle.org/plugin/net.bytebuddy.byte-buddy-gradle-plugin))
- **Debuggability:** there is no source. Frames look like `PaymentClient$ByteBuddy$a1b2c3.charge(Unknown Source)`, and stepping into advice is awkward. **[I]**
- **Access to user classes:** to subclass a user class in its package, or to define a nestmate, you need a `Lookup` with the right access. With JPMS that means the user's module has to `open` its package to us. **[I]**

**The `java.lang.classfile` API.**
- Previewed in JDK 22 (JEP 457) and JDK 23 (JEP 466), and **final in JDK 24 (JEP 484)**. **[V]** ([JDK-8334714 commit](https://gitea.hb.dhbw-stuttgart.de/stan/jdk-24/commit/84ffb64cd73f8af11cf3670c6f19d282c2ac6961), [jdk-dev targeting mail](https://mail.openjdk.org/pipermail/jdk-dev/2024-October/009461.html), [Inside Java Newscast #81](https://nipafx.dev/inside-java-newscast-81/))
- Because our minimum is Java 21, we could use it only from a multi-release JAR's `META-INF/versions/24` layer. It also has the same native-image and startup issues as any runtime generation. It **becomes relevant if the baseline moves to 25 (LTS)**, for example for a dependency-free runtime fallback. **[I]**

**Hidden classes (JEP 371, JDK 15).** These are defined with `Lookup.defineHiddenClass`. They cannot be discovered by name, they can be unloaded, and they can join the lookup class's nest to access private members. The JEP names `java.lang.reflect.Proxy` as a candidate user. **[V]** ([JEP 371](https://openjdk.org/jeps/371)). Hidden classes are a *container* for runtime-generated code, not a generator, and they bring the same native-image limits.

| Criterion | Assessment |
|---|---|
| Per-call overhead | **Excellent** (comparable to generated source). **[V]** |
| Startup | **Poor** (about 200 ms). **[V]** |
| native-image | **Poor** unless the generation moves to build time. **[V]** |
| Debuggability | Weak. **[I]** |
| Build complexity | None at runtime; a 9 MB dependency. |
| Limitations | Same subclassing rules as APT (no final or private), plus module access. |

### 2.4 Java agent / load-time weaving, and compile-time weaving

**Java agent (ByteBuddy agent, AspectJ LTW).**
- An agent rewrites user classes as they load. It *can* instrument final classes and private methods, and even third-party JARs. **[I]**
- Users must add `-javaagent:...` to every JVM: in production, tests, the IDE and containers. **[I]**
- Since **JDK 21 (JEP 451)**, loading an agent into a running JVM (self-attach) prints a warning, and a future release will disallow it by default (`-XX:+EnableDynamicAgentLoading` is needed to opt in). Agents loaded at startup are unaffected. **[V]** ([JEP 451](https://openjdk.org/jeps/451)). So the self-attach trick that some libraries use to avoid the flag is on its way out.
- **native-image:** there is no class loading at runtime, so this does not apply. **[I]**
- Per call it is good, because advice can be inlined into the method. Startup is bad: the weaver scans and transforms classes as they load. **[I]**

**Compile-time weaving (AspectJ `ajc`, ByteBuddy Gradle plugin).**
- AspectJ's version number follows the Java version it supports: 1.9.22 for Java 22, 1.9.23 for Java 23. **[V]** ([1.9.23 notes](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.23.html), [1.9.22 notes](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.22.html)). Since 1.9.21, `ajc` needs **JDK 17+ to run**. **[V]** ([1.9.21 notes](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.21.html)). Every new JDK can therefore block users until AspectJ catches up. **[I]**
- In Gradle this goes through a third-party plugin (`io.freefair.aspectj` / `...post-compile-weaving`), whose documentation users report as sparse. **[V]** ([Gradle forum](https://discuss.gradle.org/t/how-to-use-the-io-freefair-aspectj-post-compile-weaving/27954))
- It replaces or post-processes `javac` output, which interacts badly with incremental compilation, Lombok, other processors and IDE builds. **[I]**
- AspectJ `around` advice may allocate closure objects unless the weaver can inline the advice. This is an extra source of per-call cost. **[I]**
- It is native-image friendly, because everything happens at build time. **[I]**

Resilience4j's annotations depend on Spring AOP or AspectJ for exactly this reason: without a framework or weaver there is nothing to apply them. **[V]** ([Baeldung](https://www.baeldung.com/spring-boot-resilience4j), [oneuptime guide](https://oneuptime.com/blog/post/2026-01-25-circuit-breakers-resilience4j-spring/markdown))

**Verdict:** rejected for the core. These tools are powerful, but they impose the most build and runtime ceremony on users of a library that is supposed to be lightweight.

### 2.5 Hybrids

| Hybrid | Description | Assessment |
|---|---|---|
| **A. APT + functional core + convention lookup** *(recommended)* | The processor generates `Foo$Resilient` (subclass or delegate). `Resilient.of(...)` finds it by name, once. Users can also call the functional/programmatic API directly (`breaker.call(() -> ...)`) for code they cannot annotate. | Fastest path by default and no runtime magic. The functional API covers third-party code. |
| **B. A + optional `dynamic` module** | When no generated class is found, an *opt-in* module falls back to `Proxy` for interfaces. Without that module, `Resilient.of` fails fast with a message such as "did you add the annotation processor?". | Helps prototyping and tests, while the core stays reflection-free. The fallback must never be chosen silently, or users would get 10× overhead without knowing. |
| **C. APT generates metadata only + a runtime engine reads it** | The processor emits per-method descriptors (config names, return kind, fallback handles), and a generic runtime interceptor executes them. | Removes the annotation scanning, but brings back a shared, megamorphic dispatch path. Measurably slower (our `aptLambda` poly4 result). Not recommended. |
| **D. APT + build-time ByteBuddy plugin** | For users who need final classes or private methods decorated in place. | Possible later as an add-on. Too heavy for the default. |
| **(Rejected) javac AST rewriting à la Lombok** | Modify the annotated method body inside `javac`. | It uses unsupported compiler internals, breaks on JDK upgrades and violates Gradle's incremental-processing rules (no `Trees`). **[V for the Gradle rule, I otherwise]** |

---

## 3. Our own measurements (JMH)

No public, recent, apples-to-apples JMH comparison of these mechanisms turned up. The often-cited ones are a decade old or not JMH ([neoremind/dynamic-proxy](https://github.com/neoremind/dynamic-proxy), Java 6/7/8 single-run timings). So we wrote a **throwaway** JMH harness. It is research scaffolding, not production code, and is kept outside the repo; the key code is in [Appendix A](#appendix-a-benchmark-harness-condensed).

**Setup [V]:** JMH 1.37, OpenJDK 25.0.3 (Microsoft build), Apple M1 (8 cores), macOS 14. Average time, 2 forks × 5 × 1 s measurement after 3 × 1 s warmup, `-prof gc`. The argument is `100_000`, outside the `Integer` cache, so boxing is real. The "policy" is a minimal guard (one volatile read on entry, a counter on error), so **only dispatch cost is measured**, not circuit-breaker logic.

- **mono**: one service type through the mechanism.
- **poly4**: four different service implementations decorated through the same mechanism and called in a row. This is closer to a real application with many decorated services. The number is the time for **all four calls**.

| Variant | mono ns/op | mono B/op | poly4 ns/op (4 calls) | poly4 B/op |
|---|---:|---:|---:|---:|
| Direct call (baseline) | 1.18 | 0 | 2.38 | 0 |
| **APT, delegating wrapper, protocol inlined** | 2.59 | 0 | 7.29 | 0 |
| **APT, subclass, protocol inlined** | 2.36 | 0 | 6.95 | 0 |
| APT wrapper calling a shared `execute(Supplier)` (lambda) | 2.47 | 0 | **14.23** | 0 |
| ByteBuddy subclass + `Advice` | 1.99 | 0 | 6.05 | 0 |
| ByteBuddy subclass + `MethodDelegation` (`@SuperCall`) | 1.99 | 0 | 5.97 | 0 |
| `Proxy` + cached spread `MethodHandle` | 25.15 | 56 | 91.53 | 224 |
| `Proxy` + `Method.invoke` | 16.34 | 16 | 37.30 | 64 |

Error bars were below ±1.1 ns in every row. Raw JSON results were kept with the harness.

| First instance in a fresh JVM (`SingleShotTime`, 10 forks) | µs |
|---|---:|
| APT-generated wrapper | **366 ± 11** |
| `Proxy` | 2,437 ± 389 |
| ByteBuddy `Advice` | 201,397 ± 9,364 |
| ByteBuddy `MethodDelegation` | 202,105 ± 26,270 |

**Interpretation:**
1. **Every mechanism that produces real bytecode, whether from source or at runtime, lands in the same band**: about 1–1.5 ns per call above a direct call. Among these, the mechanism matters far less than *what the generated code does*. **[V]**
2. **Shared, lambda-based dispatch roughly doubles the overhead in polymorphic use** (14.2 vs 7.3 ns). In the mono case the JIT inlines everything, which hides the problem; that is why single-service microbenchmarks mislead. **[V]** This is the strongest argument for generated code calling a **per-method, inlined protocol** rather than funnelling through one generic `execute(Supplier)`. It also suggests where Resilience4j's decorator style loses time under realistic polymorphism (to be confirmed by the competitor agent). **[I]**
3. ByteBuddy's small lead over APT is most likely the **`static final` guard** in those variants: the JIT treats it as a constant, while the APT variants read an instance field. Generated code could get the same benefit wherever a policy can be bound to a `static final` field, for example config fixed at compile time, but per-instance registries cannot do that. This is worth exploring in phase 2. **[I]**
4. **Subclass beats delegation slightly** (2.36 vs 2.59, 6.95 vs 7.29), because a `super` call is statically bound while a delegate call is an interface call. **[V]**
5. Escape analysis removed the `@SuperCall` `Callable` and the boxing in the ByteBuddy and lambda variants in this tiny benchmark. It *did not* do so for `Proxy`. With larger method bodies, or inlining that fails, the lambda and `Callable` variants may start allocating as well. Generated code that never creates them does not depend on that luck. **[V for the observation, I for the generalisation]**
6. Caveats: this is a single machine, a trivial guard, single-threaded, and the poly level is 4 (a real application may be more polymorphic). Contention and policy internals are the concurrency agent's topic. Phase 2 should re-run this on x86 and with real policy implementations.

---

## 4. Comparison matrix

| | APT source gen | `Proxy` | Runtime ByteBuddy | Agent / LTW | AspectJ CTW |
|---|---|---|---|---|---|
| Per-call overhead | ★★★★★ | ★★ | ★★★★★ | ★★★★ | ★★★★ (I) |
| Allocation per call | none | 16–64 B | none* | none* (I) | possible closures (I) |
| Startup | ★★★★★ (0.4 ms) | ★★★★ (2.4 ms) | ★ (200 ms) | ★ (I) | ★★★★★ |
| native-image | ★★★★★ | ★★★ (config) | ★ | ✗ | ★★★★ |
| Debuggability | ★★★★★ (real source) | ★★ | ★★ | ★★ | ★★★ |
| User build complexity | 1 dependency line | none | none (+9 MB jar) | JVM flag everywhere | compiler plugin, JDK lag |
| Final classes / private methods | ✗ (subclass) / OK (delegate over interface) | interfaces only | ✗ | ✓ | ✓ |
| Third-party classes | via interface + delegate | interfaces | ✗ | ✓ | ✓ (binary weaving) |
| Compile-time validation | ✓ | ✗ | ✗ | ✗ | partial |
| Sync / CF / VT | static specialisation | runtime checks | runtime checks | runtime checks | runtime checks (I) |

\*In our benchmark, thanks to escape analysis.

---

## 5. Ranked recommendation

1. **APT source generation (hybrid A)**: the default and only mechanism in `core`, plus a separate processor module. For interfaces, generate delegating wrappers; for non-final classes, generate subclasses. Generated code calls an **allocation-free, per-method policy protocol**, not a shared lambda executor. The processor is *isolating*, and instances are found by naming convention through `Resilient.of(...)`.
2. **Functional / programmatic API** in `core` (not an annotation mechanism, but the escape hatch for code that cannot be annotated, such as third-party clients or lambdas).
3. **Optional `Proxy` fallback module (hybrid B)**: opt-in only and interfaces only, with a log warning that names the overhead.
4. **Build-time ByteBuddy plugin (hybrid D)**: possible later for in-place decoration of final classes or private methods.
5. **Runtime ByteBuddy**: not recommended (startup, size, native-image).
6. **AspectJ CTW / Java agent / LTW**: not recommended (user ceremony, JDK lag, JEP 451 direction).

**Proposed module implications (for the module-architecture agent).** These follow the single-word naming rule: `core` (policies, registry, protocol, `Resilient` façade), `processor` (APT, compile-time only), `annotations` (could be merged into `core`; open question), `dynamic` (optional Proxy fallback), `benchmarks` (JMH).

---

## 6. Sketch: what user code would look like

> Illustrative only. Annotation names, attribute names and config precedence belong to the API/config design agent. This sketch only shows how the **mechanism** feels.

**Build (Gradle Kotlin DSL):**

```kotlin
dependencies {
    implementation("io.github.resilience4cata:core:0.1.0")
    annotationProcessor("io.github.resilience4cata:processor:0.1.0")
}
```

**User code:**

```java
public interface PaymentClient {

    @CircuitBreaker("payments")                 // named config "payments"
    @Retry(value = "payments", maxAttempts = 5) // named config + per-method override
    @Fallback("chargeOffline")                  // checked at compile time
    Receipt charge(Order order);

    @TimeLimiter("slow-calls")
    @Bulkhead("payments")
    CompletableFuture<Status> status(String id); // async path generated statically

    default Receipt chargeOffline(Order order, Throwable cause) {
        return Receipt.deferred(order);
    }
}

public final class HttpPaymentClient implements PaymentClient { /* plain code */ }
```

**Configuration and getting the instance:**

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .defaults(d -> d.circuitBreaker(cb -> cb.failureRateThreshold(50)))
        .circuitBreaker("payments", cb -> cb.slidingWindowSize(100))
        .retry("payments", r -> r.maxAttempts(3).backoff(Duration.ofMillis(200)))
        .timeLimiter("slow-calls", t -> t.timeout(Duration.ofSeconds(2)))
        .load(ConfigSource.properties("resilience.properties")) // external config
        .build();

// Recommended entry point: one lookup now, zero reflection per call.
PaymentClient client = Resilient.of(PaymentClient.class, new HttpPaymentClient(), registry);

// Or, with no lookup at all, the generated factory:
PaymentClient client2 = PaymentClientResilience.wrap(new HttpPaymentClient(), registry);
```

**For a concrete (non-final) class, the subclass style:**

```java
public class InventoryService {
    @Retry("inventory")
    public int reserve(String sku, int qty) { ... }
}

InventoryService svc = Resilient.create(InventoryService.class, registry); // calls the generated subclass constructor
```

**What the processor would generate (shape only):**

```java
// build/generated/sources/annotationProcessor/java/main/.../PaymentClient$Resilient.java
@Generated("resilience4cata")
final class PaymentClient$Resilient implements PaymentClient {
    private final PaymentClient delegate;
    private final CircuitBreakerPolicy charge$cb;   // resolved ONCE from registry at wrap time
    private final RetryPolicy charge$retry;

    PaymentClient$Resilient(PaymentClient delegate, ResilienceRegistry r) {
        this.delegate = delegate;
        this.charge$cb = r.circuitBreaker("payments");
        this.charge$retry = r.retry("payments").withMaxAttempts(5);
    }

    @Override
    public Receipt charge(Order order) {
        int attempt = 0;
        while (true) {
            long permit = charge$cb.tryAcquire();            // no lambda, no boxing
            if (permit == CircuitBreakerPolicy.REJECTED) {
                return delegate.chargeOffline(order, charge$cb.rejection());
            }
            try {
                Receipt r = delegate.charge(order);          // monomorphic call site
                charge$cb.onSuccess(permit);
                return r;
            } catch (Throwable t) {
                charge$cb.onError(permit, t);
                if (!charge$retry.shouldRetry(++attempt, t)) {
                    return delegate.chargeOffline(order, t);
                }
                charge$retry.pause(attempt);                 // fine on virtual threads
            }
        }
    }
    // status(...) generated with CompletionStage callbacks instead of a loop
}
```

**How named configurations are referenced:** by string name in the annotation (`"payments"`). The processor validates the syntax, and can optionally check names against a compile-time list such as a `@ResilienceConfigs` constants class. Names are **resolved against the registry once, when `Resilient.of` runs**, and stored in `final` fields. Annotation attributes such as `maxAttempts = 5` are compile-time constants baked into the generated code as overrides. A missing name fails at wrap time, not on the first call.

---

## 7. Open questions for phase 2

1. Do we intercept **self-invocation** in the subclass style, as Micronaut does by default? It is more powerful but surprises people coming from Spring/Resilience4j.
2. Should fallbacks live on the interface (`default` methods) or on a separate `@FallbackFor` class?
3. Can policies be `static final` when the config is fully known at compile time? Point 3 of [§3](#3-our-own-measurements-jmh) suggests a measurable gain.
4. How do we support hot-reloadable config without a volatile read on every call? (Coordinate with the concurrency agent.)
5. How do we plug in return types: reactive types, `Future`, user types? An SPI resolved **at compile time** by the processor would be best.
6. Should `annotations` and `core` be separate modules? Users of the processor need the annotations on the compile classpath, and the generated code needs core at runtime.

---

## Appendix A: benchmark harness (condensed)

```java
interface Service { int compute(int x); }

final class Guard {                       // minimal stand-in for a policy
    volatile boolean open; int errors;
    boolean permit() { return !open; }
    void onError() { errors++; }
    <T> T execute(Supplier<T> s) {        // Resilience4j-style functional decoration
        if (!permit()) throw new IllegalStateException();
        try { return s.get(); } catch (RuntimeException e) { onError(); throw e; }
    }
}

// "APT, protocol inlined": what a processor would emit
final class GeneratedWrapper implements Service {
    final Service delegate; final Guard guard;
    public int compute(int x) {
        if (!guard.permit()) throw new IllegalStateException();
        try { return delegate.compute(x); } catch (RuntimeException e) { guard.onError(); throw e; }
    }
}

// "APT, lambda": generated code reusing a shared functional executor
final class LambdaWrapper implements Service {
    final Service delegate; final Guard guard;
    public int compute(int x) { return guard.execute(() -> delegate.compute(x)); }
}

// Proxy + Method.invoke
InvocationHandler h = (p, m, a) -> {
    if (!g.permit()) throw new IllegalStateException();
    try { return m.invoke(target, a); }
    catch (InvocationTargetException e) { g.onError(); throw e.getCause(); }
};

// ByteBuddy Advice (advice code is copied into the generated method)
new ByteBuddy().subclass(ImplA.class).method(named("compute"))
    .intercept(Advice.to(GuardAdvice.class).wrap(SuperMethodCall.INSTANCE))
    .make().load(ImplA.class.getClassLoader()).getLoaded();
```

---

## Sources

- Gradle: [Incremental annotation processing](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing)
- OpenJDK: [JEP 416, Reimplement Core Reflection with Method Handles](https://openjdk.org/jeps/416) · [JEP 371, Hidden Classes](https://openjdk.org/jeps/371) · [JEP 451, Prepare to Disallow the Dynamic Loading of Agents](https://openjdk.org/jeps/451) · JEP 484 Class-File API: [targeting mail](https://mail.openjdk.org/pipermail/jdk-dev/2024-October/009461.html), [implementation commit](https://gitea.hb.dhbw-stuttgart.de/stan/jdk-24/commit/84ffb64cd73f8af11cf3670c6f19d282c2ac6961), [Inside Java Newscast #81](https://nipafx.dev/inside-java-newscast-81/)
- JDK 23 annotation processing default: [Release notes](https://www.oracle.com/java/technologies/javase/23-relnote-issues.html) · [JDK-8321314 review](https://mail.openjdk.org/pipermail/compiler-dev/2024-May/026675.html) · [Proposal thread](https://mail.openjdk.org/pipermail/jdk-dev/2024-May/009028.html)
- GraalVM: [Dynamic Proxy in Native Image](https://www.graalvm.org/jdk17/reference-manual/native-image/dynamic-features/DynamicProxy/) · [Reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
- Avaje Inject: [Aspects](https://avaje.io/inject/#aspects)
- Micronaut: [`@Around` javadoc](https://docs.micronaut.io/4.8.2/api/io/micronaut/aop/Around.html) · [AOP guide](https://docs.micronaut.io/latest/guide/#aop)
- Byte Buddy: [README / version table](https://github.com/raphw/byte-buddy) · [Gradle plugin](https://plugins.gradle.org/plugin/net.bytebuddy.byte-buddy-gradle-plugin)
- AspectJ: [1.9.23](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.23.html) · [1.9.22](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.22.html) · [1.9.21](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.21.html) · [freefair plugin forum thread](https://discuss.gradle.org/t/how-to-use-the-io-freefair-aspectj-post-compile-weaving/27954)
- JavaPoet: [palantir/javapoet](https://github.com/palantir/javapoet) · [Square JavaPoet deprecation (mirror)](https://docsearch.algolia.com/mcp/docs/repo/square/javapoet)
- Reflection / MethodHandle / LambdaMetafactory benchmarks: [Timefold: Java reflection, but much faster](https://timefold.ai/blog/java-reflection-but-much-faster) · [mlvm-dev: LambdaMetafactory vs MethodHandle](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006826.html) · [follow-up](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006827.html) · [neoremind/dynamic-proxy (old, non-JMH)](https://github.com/neoremind/dynamic-proxy)
- Resilience4j annotation setup (Spring AOP dependency): [Baeldung](https://www.baeldung.com/spring-boot-resilience4j) · [oneuptime guide](https://oneuptime.com/blog/post/2026-01-25-circuit-breakers-resilience4j-spring/markdown) · [resilience4j repo](https://github.com/resilience4j/resilience4j)
