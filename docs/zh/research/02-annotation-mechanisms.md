# 02 · 注解机制：不依赖框架，把注解变成行为

> **历史文档。** 本研究早于实现，按原样保留；关于库目前的功能，请参阅[功能](../policies/README.md)、[架构](../architecture.md)和[基准测试](../benchmarks.md)。

> **第 1 阶段，仅研究。** 没有生产代码。本文比较一个独立的（不用 Spring、不用 Quarkus、不用 CDI）Java 21+ 库可以用哪些方式把 `@CircuitBreaker`、`@Retry` 等注解变成运行中的行为。最后给出一份按优先级排序的建议，以及面向用户的 API 草图。
>
> **如何解读证据：**
> - **[V] 已验证**：在本研究中对照一手来源核实过（附链接），或由我们自己测量（见 [§3](#3-our-own-measurements-jmh)）。
> - **[I] 推断**：来自工程判断或既有知识，本文未再对照来源复核。请将其视为待第 2 阶段确认的假设。
>
> 西班牙语版本：[`docs/es/research/02-annotation-mechanisms.md`](../../es/research/02-annotation-mechanisms.md)。

---

## 0. 要点速览 :id=_0-tldr

1. **推荐：在编译期进行注解处理（APT），生成普通 Java 源代码**，再配一个小的运行时门面（`Resilient.of(...)`），在创建实例时一次性找到生成的类。每次调用的开销大致等于手写代码：没有反射，没有 `Object[]` 装箱，每个生成的方法只有一个单态调用点。它启动即时，在 GraalVM native-image 中无需配置即可工作，产生可读的堆栈跟踪，并在编译期捕获诸如错误的降级签名或未知配置名之类的错误。
2. **生成代码的形态与机制本身同样重要。** 在我们的 JMH 运行中，当多个服务共享同一路径时，通过共享的、接收 lambda 的 `execute(Supplier)` 调用策略的生成代码，比把策略协议内联进来的生成代码**慢约 2 倍**（4 次调用 14.2 ns 对 7.3 ns）**[V，已测量]**。因此核心模块应当在面向人的函数式 API 之外，为生成的代码提供一个底层的、无内存分配的“许可协议”（acquire，然后 onSuccess 或 onError）。
3. **`java.lang.reflect.Proxy` 每次调用慢 5–15 倍，并且会分配内存**（在我们的运行中每次调用 16–64 B）。它只能处理接口。它只能作为**可选的后备方案**被接受。
4. **运行时的 ByteBuddy 每次调用与生成的源代码一样快**（它产生真正的字节码），但第一个实例耗时**约 200 ms，而生成的源代码只要 0.37 ms** **[V，已测量]**。它还会增加一个 9 MB 的依赖，native-image 也需要额外的构建步骤。它不是默认方案；最多可以支撑一个可选模块。
5. **Java agent 和 AspectJ 织入**每次调用的性能不错，但用户体验最差：JVM 参数、自 JDK 21 起的动态挂载警告、必须跟进每个新 JDK 的织入器，以及非标准的编译器。核心中不采用。
6. **Class-File API 直到 JDK 24 才成为正式功能** **[V]**，所以在我们 Java 21 的基线上，除非使用多版本 JAR，否则无法使用。等基线迁移到 Java 25 LTS 时再考虑。

---

## 1. 问题 :id=_1-the-problem

对于每个带注解的方法，在没有容器的情况下，我们必须：

1. **发现**注解（`@CircuitBreaker("payments")`、`@Retry(maxAttempts = 3)`、`@Fallback(...)`）。
2. **解析配置**：命名配置，然后是全局默认值，然后是来自注解属性的按方法覆盖，然后是外部配置。（优先级规则由 API 与配置设计代理负责。本文只关心解析*何时*发生：构建时、包装时还是调用时。）
3. **构建一个实例**，其方法在用户代码外围运行策略链。
4. **把该实例交给用户**，而没有 DI 容器来注入它。

每次调用的开销由第 3 步决定：包装代码如何产生，以及 JIT 能对它做什么。

### 评估标准 :id=evaluation-criteria

| 标准 | 为什么对我们重要 |
|---|---|
| 每次调用的开销 | 我们的主要目标（“比 Resilience4j 更快”）。涵盖 JIT 内联、超多态调用点、反射、装箱和内存分配。 |
| 启动 / 首次调用开销 | CLI 工具、serverless、测试。 |
| GraalVM native-image | 对库来说越来越被期待。那里不支持在运行时定义类。 |
| 可调试性 | 堆栈跟踪、单步调试、“到底运行的是什么代码？”。 |
| 用户的构建复杂度 | 我们没有框架插件可以把复杂度藏在后面。 |
| 限制 | final 类、private 方法、接口与类、自调用。 |
| 同步 / `CompletableFuture` / 虚拟线程 | 三者都必须能工作。 |

---

## 2. 可选方案 :id=_2-the-options

### 2.1 编译期注解处理（APT）生成源代码 :id=_21-compile-time-annotation-processing-apt-generating-source

**工作原理。** 一个 `javax.annotation.processing.Processor` 在 `javac` 内运行，从 `Element`/`TypeMirror` 模型中读取 `@CircuitBreaker` 及相关注解，并通过 `Filer` 写出新的 `.java` 文件。这些文件在同一次构建中被编译。运行时除了生成的代码之外，什么都不运行。

**已有方案。**
- **Dagger、MapStruct、Immutables**：经典的生成源代码的处理器（不做 AOP）。**[I]**
- **Avaje Inject** 生成 `MyService$Proxy` 类，这些类**继承目标类并重写带注解的方法**。重写的方法运行拦截器，然后调用 `super`。切面通过 `@Aspect`、`AspectProvider<T>` 和 `MethodInterceptor` 声明，`@AOPFallback` 指定一个恢复方法。**[V]**（[avaje.io/inject](https://avaje.io/inject/#aspects)）
- **Micronaut AOP**：默认在编译期生成目标的一个**子类**，并通过 `super` 调用原方法。`@Around(proxyTarget = true)` 让它改为生成一个委托代理。Micronaut 的文档说子类这一默认方式“more efficient”，并且让拦截对类内部发起的调用也生效。**[V]**（[`@Around` javadoc](https://docs.micronaut.io/4.8.2/api/io/micronaut/aop/Around.html)）。Micronaut 还提供了基于这一机制的 `@Retryable` 和 `@CircuitBreaker`，并有一个用于 `CompletionStage` 和响应式返回类型的 `InterceptedMethod` 辅助类。**[I]**（[Micronaut AOP guide](https://docs.micronaut.io/latest/guide/#aop)）

**生成工具。**
- **Palantir JavaPoet**（`com.palantir.javapoet:javapoet`）是仍在维护的分支。它的 README 把 Square 的 JavaPoet 描述为“no longer actively maintained”，并增加了 record 和 sealed 类型的构建器。**[V]**（[palantir/javapoet](https://github.com/palantir/javapoet)、[Square 的弃用说明（镜像）](https://docsearch.algolia.com/mcp/docs/repo/square/javapoet)）
- 另一种选择：无依赖的手写模板。生成的代码小而规整，所以这是现实可行的，并且让处理器保持无依赖。**[I]**
- 处理器 JAR **只在编译期**需要（`annotationProcessor` 配置），所以 JavaPoet 永远不会进入用户的运行时类路径。**[I]**

**Gradle 中的增量处理 [V]**（[Gradle 文档](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing)）：
- 处理器在 `META-INF/gradle/incremental.annotation.processors` 中把自己声明为 `isolating`、`aggregating` 或 `dynamic`。
- **Isolating** 最快。每个生成的文件必须有**恰好一个源元素（originating element）**，且决策只能使用从该元素可达的内容。每个带注解的 `Foo` 生成一个 `Foo$Resilient`，完全符合这一点。
- **Aggregating** 会重新处理所有带注解的文件。如果我们要生成单一的注册文件（例如 `META-INF/services` 文件），就需要它。**设计上的推论：** 通过**命名约定**（`Foo` → `Foo$Resilient`）而不是聚合的注册表来查找生成的类，这样处理器可以保持 *isolating*。
- 其他约束：只使用 `Filer` API，避免 `com.sun.source.util.Trees` 等编译器内部 API，并记住参数名只有在使用 `-parameters` 时才可用。

**JDK 23 改变了 javac 的默认行为 [V]：** 注解处理不再从类路径隐式运行。它需要显式配置，例如 `-processorpath` 或 `-proc:full`。（[JDK 23 release notes](https://www.oracle.com/java/technologies/javase/23-relnote-issues.html)、[JDK-8321314 review](https://mail.openjdk.org/pipermail/compiler-dev/2024-May/026675.html)）。Gradle 的 `annotationProcessor` 配置会传入显式的处理器路径，所以 Gradle 用户应当不受影响 **[I]**。我们的文档仍然必须展示 `annotationProcessor(...)` 这一行，而 Maven 用户需要 `annotationProcessorPaths`。

**处理器可以生成的包装风格：**

| 风格 | 生成的形态 | 优点 | 缺点 |
|---|---|---|---|
| **委托包装器** | `final class Foo$Resilient implements Foo { final Foo delegate; ... }` | 适用于**接口**。可以包装*任何*实例，包括来自其他 DI 容器、第三方工厂或测试替身的实例。从不拦截自调用，行为可预测。 | 多一次对委托对象的接口分派。只能装饰接口方法。 |
| **子类** | `final class Foo$Resilient extends Foo { ... super.call() ... }` | 适用于**具体类**。测得比委托略快（[§3](#3-our-own-measurements-jmh)）。如果需要，可以拦截自调用。 | 类不能是 `final`，方法不能是 `final`、`private` 或 `static`。每个构造函数都必须镜像一份。*用户*必须创建这个子类，而不是 `new Foo(...)`。 |

**APT 的硬性限制 [I，标准 Java 语义]：**
- 它无法修改带注解的类本身；APT 只能*添加*文件。因此它无法装饰 `private`、`final` 或 `static` 方法，生成子类时也无法装饰 final 类。
- 它只能看到在同一次构建中编译的代码：无法装饰第三方 JAR 中的类。这种情况由基于接口的委托包装器或函数式 API 覆盖。
- 它不支持 Kotlin，除非编写 KSP 或 kapt 移植版本。不在范围内。
- 处理器的错误消息*就是*用户体验，所以它们必须非常出色。

**用户如何获得实例。** 选项，从最显式到最方便：
1. `new PaymentClient$Resilient(delegate, registry)`：显式，但用户代码里出现带 `$` 的名字很难看。
2. 生成的静态工厂：`PaymentClientResilience.wrap(delegate, registry)`。它类型安全，且毫无魔法。
3. 运行时门面 `Resilient.of(PaymentClient.class, delegate)`。它通过 `Class.forName` 加 `ClassValue` 缓存**一次性**找到 `PaymentClient$Resilient`，调用只创建一次的生成构造函数或 `MethodHandle`，然后返回实例。**每次调用都没有反射。** 处理器还可以为该构造函数生成 `META-INF/native-image/.../reflect-config.json`，所以 native-image 不需要用户做任何配置。**[I]**

   我们建议把 (3) 作为文档中的入口点，并为完全不想做查找的人提供 (2)。

**评分卡。**

| 标准 | 评估 |
|---|---|
| 每次调用的开销 | **同类最佳。** 它就是普通的 Java 代码：每个生成的方法只有一个单态调用点，因此 JIT 可以内联策略和用户方法。返回类型在静态时已知，所以没有装箱，也没有 `Object[]`。测得 2.4–2.6 ns，直接调用为 1.2 ns（[§3](#3-our-own-measurements-jmh)）。**[V]** |
| 启动 | **最佳。** 在我们的运行中，加载第一个实例约耗时 0.37 ms，仅仅是类加载。**[V]** |
| native-image | **原生支持。** 没有代理，没有运行时字节码。最多需要一个反射构造函数，而我们可以生成相应的元数据。**[I]** |
| 可调试性 | **最佳。** 生成的源代码位于 `build/generated/sources/annotationProcessor`，用户可以打开并单步调试。栈帧形如 `PaymentClient$Resilient.charge(PaymentClient$Resilient.java:41)`。**[I]** |
| 构建复杂度 | 低：一行 `annotationProcessor` 依赖。IDE（IntelliJ、Eclipse）开箱即可运行处理器。**[I]** |
| 同步 / 异步 / VT | 处理器在编译期就能看到**声明的返回类型**，并为同步结果、`void` 以及 `CompletionStage`/`CompletableFuture`（挂接回调而不是阻塞）生成特化代码。它不需要运行时的 `instanceof` 检查。虚拟线程不需要特殊的代码生成：同步代码直接在虚拟线程上阻塞即可。固定（pinning）方面的问题属于核心（见并发代理）。**[I]** |
| 额外好处 | **编译期校验**：缺失的降级方法、不匹配的降级签名或相互冲突的注解都会变成编译错误。Resilience4j 的 Spring 集成在运行时通过反射按名称解析 `fallbackMethod` **[I]**（[resilience4j 源码](https://github.com/resilience4j/resilience4j)）。 |

### 2.2 运行时动态代理（`java.lang.reflect.Proxy`） :id=_22-runtime-dynamic-proxies-javalangreflectproxy

**工作原理。** `Proxy.newProxyInstance(loader, interfaces, handler)` 在运行时生成一个代理类。每次调用都进入 `InvocationHandler.invoke(proxy, Method, Object[] args)`。

**事实：**
- 它**只处理接口**。**[I，JDK API 约定]**
- 自 **JDK 18（JEP 416）**起，`Method.invoke` 基于方法句柄实现。**当 `Method` 是可常量折叠的 `static final` 时快 43–57%**，但**不是时则更慢**（微基准测试中字段访问慢 51–77%）。**[V]**（[JEP 416](https://openjdk.org/jeps/416)）。在代理处理器中，`Method` 是作为*参数*传入的，所以在 `invoke` 调用点通常**不是**常量。这正是糟糕的情况。
- 处理器是**共享的**：同一个 `InvocationHandler.invoke` 方法体及其中的 `Method.invoke`，服务于每个被代理服务的每个方法。在任何真实应用中，这些调用点都会变成**超多态**的，从而阻止内联。**[I，与我们 poly4 的测量结果一致]**
- 参数被装箱进一个 `Object[]`，原始类型被装箱，受检异常被包装在 `InvocationTargetException` 或 `UndeclaredThrowableException` 中传出。**[I]**
- **native-image：** 代理必须在镜像构建时生成。Native Image **只有当接口数组是常量或在同一方法中分配时**，才会自动检测 `newProxyInstance` 调用。否则就需要 JSON 代理配置。**[V]**（[GraalVM Dynamic Proxy 文档](https://www.graalvm.org/jdk17/reference-manual/native-image/dynamic-features/DynamicProxy/)、[元数据参考](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)）。一个代理运行时传入的*用户*接口的库，属于需要配置的情况。
- **MethodHandle 变体：** 在我们的运行中，为每个 `Method` 缓存一个绑定的、展开参数的 `MethodHandle` **并没有**帮助。由于 map 查找和非常量的句柄，它比普通的 `Method.invoke` 更慢（mono 25 ns 对 16 ns，poly 91 ns 对 37 ns）**[V，已测量]**。非常量的 `MethodHandle` 不会被内联，这与 OpenJDK 工程师的说法一致（[mlvm-dev 讨论](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006826.html)）**[V]**。
- **LambdaMetafactory** 可以把一个 `Method` 变成真正的函数式接口实现，lambda 创建后即可避免反射。较早的 JMH 运行显示，在内联成功时它接近直接调用（[Timefold](https://timefold.ai/blog/java-reflection-but-much-faster)、[mlvm-dev](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006827.html)）**[V]**。但它并没有消除共享的、超多态的处理器，所以它改善的是对*目标*的调用，分派问题依然存在。**[I]**

| 标准 | 评估 |
|---|---|
| 每次调用的开销 | **差。** mono 16 ns / poly4 中每次调用约 9 ns，而生成的代码为 2–2.6 ns；每次调用分配 16–64 B。**[V，已测量]** |
| 启动 | 第一个代理实例约 2.4 ms。**[V，已测量]** |
| native-image | 运行时传入的接口需要配置。**[V]** |
| 可调试性 | 一般：`jdk.proxy1.$Proxy7.charge(Unknown Source)`，然后是处理器的栈帧，然后是反射的栈帧，外加被包装的异常。**[I]** |
| 构建复杂度 | 无。 |
| 限制 | 仅限接口。 |

**结论：** 对接口而言，是一个合理的**无需构建配置的后备方案**，例如一个 `dynamic` 附加模块，但绝不能作为默认路径。

### 2.3 运行时字节码生成（ByteBuddy、Class-File API、隐藏类） :id=_23-runtime-bytecode-generation-bytebuddy-class-file-api-hidden-classes

**ByteBuddy。**
- 它在运行时生成真正的字节码，作为子类或接口实现。`Advice` 把 advice 代码*复制*进生成的方法中，而 `MethodDelegation` 产生对拦截器的直接调用。**[V]**（行为由我们的基准测试设计和结果确认）
- 每次调用的性能**非常好**，在我们的运行中与生成源代码的变体一样快甚至略快（mono 2.0 ns，poly4 每次调用约 1.5 ns）。这一优势部分来自基准测试本身：我们的 ByteBuddy 变体从 `static final` 字段读取守卫（被常量折叠），而生成源代码的变体读取的是实例字段。见 [§3](#3-our-own-measurements-jmh)。**[V，已测量 + I 用于解释]**
- **启动代价高：** 在全新的 JVM 中创建第一个实例约需 **200 ms**，大部分时间花在加载和初始化 ByteBuddy 自身上。**[V，已测量]**
- **依赖分量：** `byte-buddy-1.18.3.jar` 有 **9.2 MB** **[V，本地文件]**。可以对它做 shade，但放到每个用户的类路径上仍然太多。
- **跟进 JDK：** Byte Buddy 针对每个 Java class 文件版本发布了一张最低版本对照表。自 1.17.0 起，它使用 Class-File API 来跟进新的 JDK，除非出现新的 class 文件特性。**[V]**（[raphw/byte-buddy README](https://github.com/raphw/byte-buddy)）
- **native-image：** 无法在运行时定义类。受支持的途径是在构建时生成类，或使用 tracing agent 尽力而为的“predefined classes”，而文档说后者“not guaranteed to work”。**[V]**（[GraalVM metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)）。ByteBuddy 有一个**用于构建时转换的 Gradle 插件**（`net.bytebuddy.byte-buddy-gradle-plugin`），那样它就成了一个织入工具（见 §2.4）。**[V]**（[Gradle Plugin Portal](https://plugins.gradle.org/plugin/net.bytebuddy.byte-buddy-gradle-plugin)）
- **可调试性：** 没有源代码。栈帧形如 `PaymentClient$ByteBuddy$a1b2c3.charge(Unknown Source)`，单步进入 advice 也很别扭。**[I]**
- **访问用户类：** 要在用户类的包中继承它，或定义一个 nestmate，需要一个具有相应访问权限的 `Lookup`。在 JPMS 下，这意味着用户的模块必须对我们 `open` 它的包。**[I]**

**`java.lang.classfile` API。**
- 在 JDK 22（JEP 457）和 JDK 23（JEP 466）中为预览，**在 JDK 24（JEP 484）中成为正式功能**。**[V]**（[JDK-8334714 commit](https://gitea.hb.dhbw-stuttgart.de/stan/jdk-24/commit/84ffb64cd73f8af11cf3670c6f19d282c2ac6961)、[jdk-dev targeting 邮件](https://mail.openjdk.org/pipermail/jdk-dev/2024-October/009461.html)、[Inside Java Newscast #81](https://nipafx.dev/inside-java-newscast-81/)）
- 由于我们的最低版本是 Java 21，只能在多版本 JAR 的 `META-INF/versions/24` 层中使用它。它也有与任何运行时生成相同的 native-image 和启动问题。**如果基线迁移到 25（LTS），它就变得有意义**，例如用作一个无依赖的运行时后备方案。**[I]**

**隐藏类（JEP 371，JDK 15）。** 它们通过 `Lookup.defineHiddenClass` 定义。无法按名称发现，可以被卸载，并且可以加入查找类的 nest 来访问私有成员。该 JEP 把 `java.lang.reflect.Proxy` 列为潜在使用者。**[V]**（[JEP 371](https://openjdk.org/jeps/371)）。隐藏类是运行时生成代码的*容器*，而不是生成器，并且带有同样的 native-image 限制。

| 标准 | 评估 |
|---|---|
| 每次调用的开销 | **非常好**（与生成的源代码相当）。**[V]** |
| 启动 | **差**（约 200 ms）。**[V]** |
| native-image | **差**，除非把生成移到构建时。**[V]** |
| 可调试性 | 弱。**[I]** |
| 构建复杂度 | 运行时无；一个 9 MB 的依赖。 |
| 限制 | 与 APT 相同的子类化规则（不能是 final 或 private），外加模块访问问题。 |

### 2.4 Java agent / 加载期织入，以及编译期织入 :id=_24-java-agent--load-time-weaving-and-compile-time-weaving

**Java agent（ByteBuddy agent、AspectJ LTW）。**
- agent 在用户类加载时重写它们。它*能够*插桩 final 类和 private 方法，甚至第三方 JAR。**[I]**
- 用户必须给每个 JVM 加上 `-javaagent:...`：在生产、测试、IDE 和容器中都要加。**[I]**
- 自 **JDK 21（JEP 451）**起，把 agent 加载进正在运行的 JVM（自我挂载）会打印警告，未来的版本将默认禁止（需要 `-XX:+EnableDynamicAgentLoading` 才能启用）。启动时加载的 agent 不受影响。**[V]**（[JEP 451](https://openjdk.org/jeps/451)）。所以一些库用来避免该参数的自我挂载技巧正在被淘汰。
- **native-image：** 运行时没有类加载，因此不适用。**[I]**
- 每次调用的性能不错，因为 advice 可以内联进方法。启动则很糟：织入器在类加载时扫描并转换它们。**[I]**

**编译期织入（AspectJ `ajc`、ByteBuddy Gradle 插件）。**
- AspectJ 的版本号跟随它所支持的 Java 版本：1.9.22 对应 Java 22，1.9.23 对应 Java 23。**[V]**（[1.9.23 notes](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.23.html)、[1.9.22 notes](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.22.html)）。自 1.9.21 起，`ajc` **需要 JDK 17+ 才能运行**。**[V]**（[1.9.21 notes](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.21.html)）。因此每个新 JDK 都可能卡住用户，直到 AspectJ 跟上。**[I]**
- 在 Gradle 中，这需要通过一个第三方插件（`io.freefair.aspectj` / `...post-compile-weaving`），用户反映其文档很少。**[V]**（[Gradle 论坛](https://discuss.gradle.org/t/how-to-use-the-io-freefair-aspectj-post-compile-weaving/27954)）
- 它替换或后处理 `javac` 的输出，与增量编译、Lombok、其他处理器以及 IDE 构建的相互作用很差。**[I]**
- 除非织入器能把 advice 内联，AspectJ 的 `around` advice 可能会分配闭包对象。这是每次调用开销的又一个来源。**[I]**
- 它对 native-image 友好，因为一切都发生在构建时。**[I]**

Resilience4j 的注解正是因为这个原因才依赖 Spring AOP 或 AspectJ：没有框架或织入器，就没有东西来应用它们。**[V]**（[Baeldung](https://www.baeldung.com/spring-boot-resilience4j)、[oneuptime 指南](https://oneuptime.com/blog/post/2026-01-25-circuit-breakers-resilience4j-spring/markdown)）

**结论：** 核心中不采用。这些工具很强大，但对于一个本应轻量的库，它们给用户带来的构建和运行时繁琐程度最高。

### 2.5 混合方案 :id=_25-hybrids

| 混合方案 | 描述 | 评估 |
|---|---|---|
| **A. APT + 函数式核心 + 约定查找** *（推荐）* | 处理器生成 `Foo$Resilient`（子类或委托）。`Resilient.of(...)` 按名称一次性找到它。对于无法加注解的代码，用户也可以直接调用函数式/编程式 API（`breaker.call(() -> ...)`）。 | 默认就是最快的路径，且没有运行时魔法。函数式 API 覆盖第三方代码。 |
| **B. A + 可选的 `dynamic` 模块** | 当找不到生成的类时，一个*需主动启用*的模块对接口退回到 `Proxy`。没有该模块时，`Resilient.of` 会快速失败，并给出类似“did you add the annotation processor?”的消息。 | 有助于原型开发和测试，同时核心保持无反射。绝不能静默地选择这个后备方案，否则用户会在不知情的情况下承受 10 倍的开销。 |
| **C. APT 只生成元数据 + 由运行时引擎读取** | 处理器为每个方法生成描述符（配置名、返回类别、降级句柄），由一个通用的运行时拦截器执行。 | 省去了注解扫描，但重新引入了共享的、超多态的分派路径。可测得地更慢（我们的 `aptLambda` poly4 结果）。不推荐。 |
| **D. APT + 构建时 ByteBuddy 插件** | 适用于需要原地装饰 final 类或 private 方法的用户。 | 以后可以作为附加组件。作为默认方案太重。 |
| **（已否决）Lombok 式的 javac AST 改写** | 在 `javac` 内部修改带注解方法的方法体。 | 它使用不受支持的编译器内部实现，在 JDK 升级时会损坏，并违反 Gradle 的增量处理规则（不能用 `Trees`）。**[Gradle 规则为 V，其余为 I]** |

---

## 3. 我们自己的测量（JMH） :id=_3-our-own-measurements-jmh

我们没有找到这些机制之间公开的、近期的、同等条件下的 JMH 对比。常被引用的那些要么是十年前的，要么不是 JMH（[neoremind/dynamic-proxy](https://github.com/neoremind/dynamic-proxy)，Java 6/7/8 的单次运行计时）。所以我们写了一个**一次性的** JMH 测试框架。它是研究用的脚手架，不是生产代码，保存在仓库之外；关键代码见[附录 A](#appendix-a-benchmark-harness-condensed)。

**环境 [V]：** JMH 1.37，OpenJDK 25.0.3（Microsoft 构建），Apple M1（8 核），macOS 14。平均时间，2 个 fork × 5 × 1 s 测量，之前 3 × 1 s 预热，`-prof gc`。参数是 `100_000`，在 `Integer` 缓存范围之外，所以装箱是真实发生的。“策略”是一个最小的守卫（入口一次 volatile 读，出错时一个计数器），因此**只测量分派开销**，而不是熔断器逻辑。

- **mono**：一种服务类型经过该机制。
- **poly4**：四个不同的服务实现通过同一机制装饰，并依次调用。这更接近有许多被装饰服务的真实应用。数字是**全部四次调用**的时间。

| 变体 | mono ns/op | mono B/op | poly4 ns/op（4 次调用） | poly4 B/op |
|---|---:|---:|---:|---:|
| 直接调用（基线） | 1.18 | 0 | 2.38 | 0 |
| **APT，委托包装器，协议内联** | 2.59 | 0 | 7.29 | 0 |
| **APT，子类，协议内联** | 2.36 | 0 | 6.95 | 0 |
| 调用共享 `execute(Supplier)`（lambda）的 APT 包装器 | 2.47 | 0 | **14.23** | 0 |
| ByteBuddy 子类 + `Advice` | 1.99 | 0 | 6.05 | 0 |
| ByteBuddy 子类 + `MethodDelegation`（`@SuperCall`） | 1.99 | 0 | 5.97 | 0 |
| `Proxy` + 缓存的展开参数 `MethodHandle` | 25.15 | 56 | 91.53 | 224 |
| `Proxy` + `Method.invoke` | 16.34 | 16 | 37.30 | 64 |

每一行的误差都低于 ±1.1 ns。原始 JSON 结果与测试框架一起保存。

| 全新 JVM 中的第一个实例（`SingleShotTime`，10 个 fork） | µs |
|---|---:|
| APT 生成的包装器 | **366 ± 11** |
| `Proxy` | 2,437 ± 389 |
| ByteBuddy `Advice` | 201,397 ± 9,364 |
| ByteBuddy `MethodDelegation` | 202,105 ± 26,270 |

**解读：**
1. **所有产生真正字节码的机制，无论是从源代码还是在运行时，都落在同一区间**：每次调用比直接调用多约 1–1.5 ns。在这些机制之间，机制本身远不如*生成的代码做了什么*重要。**[V]**
2. **共享的、基于 lambda 的分派在多态使用中大约使开销翻倍**（14.2 对 7.3 ns）。在 mono 情况下，JIT 内联了一切，掩盖了问题；这就是单服务微基准测试会误导人的原因。**[V]** 这是支持生成的代码调用**按方法内联的协议**、而不是全部汇集到一个通用 `execute(Supplier)` 的最有力论据。它也暗示了 Resilience4j 的装饰器风格在真实的多态情况下在哪里损失了时间（待竞争者代理确认）。**[I]**
3. ByteBuddy 相对 APT 的微小领先，最可能是这些变体中的 **`static final` 守卫**造成的：JIT 把它当作常量，而 APT 变体读取的是实例字段。只要策略可以绑定到一个 `static final` 字段（例如编译期固定的配置），生成的代码也能获得同样的好处，但按实例的注册表做不到这一点。这值得在第 2 阶段探索。**[I]**
4. **子类略胜委托**（2.36 对 2.59，6.95 对 7.29），因为 `super` 调用是静态绑定的，而委托调用是接口调用。**[V]**
5. 在这个很小的基准测试中，逃逸分析消除了 ByteBuddy 和 lambda 变体中的 `@SuperCall` `Callable` 和装箱。对于 `Proxy`，它*没有*做到。如果方法体更大，或者内联失败，lambda 和 `Callable` 变体也可能开始分配内存。从不创建这些对象的生成代码不依赖这种运气。**[观察结果为 V，推广为 I]**
6. 注意事项：这是单台机器、一个简单的守卫、单线程，多态程度为 4（真实应用的多态程度可能更高）。竞争和策略内部实现是并发代理的主题。第 2 阶段应在 x86 上并使用真实的策略实现重新运行。

---

## 4. 对比矩阵 :id=_4-comparison-matrix

| | APT 源代码生成 | `Proxy` | 运行时 ByteBuddy | Agent / LTW | AspectJ CTW |
|---|---|---|---|---|---|
| 每次调用的开销 | ★★★★★ | ★★ | ★★★★★ | ★★★★ | ★★★★ (I) |
| 每次调用的内存分配 | 无 | 16–64 B | 无* | 无* (I) | 可能有闭包 (I) |
| 启动 | ★★★★★ (0.4 ms) | ★★★★ (2.4 ms) | ★ (200 ms) | ★ (I) | ★★★★★ |
| native-image | ★★★★★ | ★★★（需配置） | ★ | ✗ | ★★★★ |
| 可调试性 | ★★★★★（真实源代码） | ★★ | ★★ | ★★ | ★★★ |
| 用户构建复杂度 | 1 行依赖 | 无 | 无（+9 MB jar） | 处处都要 JVM 参数 | 编译器插件，JDK 滞后 |
| final 类 / private 方法 | ✗（子类）/ 可以（基于接口的委托） | 仅接口 | ✗ | ✓ | ✓ |
| 第三方类 | 通过接口 + 委托 | 接口 | ✗ | ✓ | ✓（二进制织入） |
| 编译期校验 | ✓ | ✗ | ✗ | ✗ | 部分 |
| 同步 / CF / VT | 静态特化 | 运行时检查 | 运行时检查 | 运行时检查 | 运行时检查 (I) |

\*在我们的基准测试中，得益于逃逸分析。

---

## 5. 排序后的建议 :id=_5-ranked-recommendation

1. **APT 源代码生成（混合方案 A）**：`core` 中默认且唯一的机制，外加一个独立的处理器模块。对接口生成委托包装器；对非 final 类生成子类。生成的代码调用一个**无内存分配的、按方法的策略协议**，而不是共享的 lambda 执行器。处理器是 *isolating* 的，实例通过命名约定由 `Resilient.of(...)` 查找。
2. `core` 中的**函数式 / 编程式 API**（不是注解机制，而是给无法加注解的代码使用的出口，例如第三方客户端或 lambda）。
3. **可选的 `Proxy` 后备模块（混合方案 B）**：只能主动启用，只支持接口，并输出一条说明开销的日志警告。
4. **构建时 ByteBuddy 插件（混合方案 D）**：以后可用于原地装饰 final 类或 private 方法。
5. **运行时 ByteBuddy**：不推荐（启动、体积、native-image）。
6. **AspectJ CTW / Java agent / LTW**：不推荐（用户繁琐、JDK 滞后、JEP 451 的方向）。

**对模块划分的建议（供模块架构代理参考）。** 遵循单词命名规则：`core`（策略、注册表、协议、`Resilient` 门面），`processor`（APT，仅编译期），`annotations`（可以合并进 `core`；待定问题），`dynamic`（可选的 Proxy 后备方案），`benchmarks`（JMH）。

---

## 6. 草图：用户代码会是什么样子 :id=_6-sketch-what-user-code-would-look-like

> 仅作示意。注解名、属性名和配置优先级归 API/配置设计代理决定。这个草图只展示**机制**用起来的感觉。

**构建（Gradle Kotlin DSL）：**

```kotlin
dependencies {
    implementation("io.github.resilience4cata:core:0.1.0")
    annotationProcessor("io.github.resilience4cata:processor:0.1.0")
}
```

**用户代码：**

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

**配置和获取实例：**

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

**对于具体的（非 final）类，使用子类风格：**

```java
public class InventoryService {
    @Retry("inventory")
    public int reserve(String sku, int qty) { ... }
}

InventoryService svc = Resilient.create(InventoryService.class, registry); // calls the generated subclass constructor
```

**处理器会生成的内容（仅形态）：**

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

**如何引用命名配置：** 在注解中通过字符串名称（`"payments"`）。处理器校验语法，并且可以选择对照一个编译期列表（例如一个 `@ResilienceConfigs` 常量类）检查名称。名称**在 `Resilient.of` 运行时对照注册表解析一次**，并存放在 `final` 字段中。像 `maxAttempts = 5` 这样的注解属性是编译期常量，作为覆盖值直接写进生成的代码。缺失的名称会在包装时失败，而不是在第一次调用时。

---

## 7. 第 2 阶段的待定问题 :id=_7-open-questions-for-phase-2

1. 在子类风格中，我们是否像 Micronaut 默认那样拦截**自调用**？这样功能更强，但会让从 Spring/Resilience4j 过来的人感到意外。
2. 降级方法应该放在接口上（`default` 方法），还是放在一个单独的 `@FallbackFor` 类上？
3. 当配置在编译期完全已知时，策略能否是 `static final` 的？[§3](#3-our-own-measurements-jmh) 的第 3 点表明会有可测量的收益。
4. 如何在每次调用不做 volatile 读的情况下支持可热重载的配置？（与并发代理协调。）
5. 如何接入返回类型：响应式类型、`Future`、用户自定义类型？由处理器**在编译期**解析的 SPI 会是最好的。
6. `annotations` 和 `core` 应该是分开的模块吗？处理器的用户需要在编译类路径上有注解，而生成的代码在运行时需要 core。

---

## 附录 A：基准测试框架（精简版） :id=appendix-a-benchmark-harness-condensed

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

## 来源 :id=sources

- Gradle：[Incremental annotation processing](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing)
- OpenJDK：[JEP 416, Reimplement Core Reflection with Method Handles](https://openjdk.org/jeps/416) · [JEP 371, Hidden Classes](https://openjdk.org/jeps/371) · [JEP 451, Prepare to Disallow the Dynamic Loading of Agents](https://openjdk.org/jeps/451) · JEP 484 Class-File API：[targeting 邮件](https://mail.openjdk.org/pipermail/jdk-dev/2024-October/009461.html)、[实现提交](https://gitea.hb.dhbw-stuttgart.de/stan/jdk-24/commit/84ffb64cd73f8af11cf3670c6f19d282c2ac6961)、[Inside Java Newscast #81](https://nipafx.dev/inside-java-newscast-81/)
- JDK 23 注解处理默认行为：[Release notes](https://www.oracle.com/java/technologies/javase/23-relnote-issues.html) · [JDK-8321314 review](https://mail.openjdk.org/pipermail/compiler-dev/2024-May/026675.html) · [提案讨论](https://mail.openjdk.org/pipermail/jdk-dev/2024-May/009028.html)
- GraalVM：[Dynamic Proxy in Native Image](https://www.graalvm.org/jdk17/reference-manual/native-image/dynamic-features/DynamicProxy/) · [Reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
- Avaje Inject：[Aspects](https://avaje.io/inject/#aspects)
- Micronaut：[`@Around` javadoc](https://docs.micronaut.io/4.8.2/api/io/micronaut/aop/Around.html) · [AOP guide](https://docs.micronaut.io/latest/guide/#aop)
- Byte Buddy：[README / 版本对照表](https://github.com/raphw/byte-buddy) · [Gradle 插件](https://plugins.gradle.org/plugin/net.bytebuddy.byte-buddy-gradle-plugin)
- AspectJ：[1.9.23](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.23.html) · [1.9.22](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.22.html) · [1.9.21](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.21.html) · [freefair 插件论坛帖子](https://discuss.gradle.org/t/how-to-use-the-io-freefair-aspectj-post-compile-weaving/27954)
- JavaPoet：[palantir/javapoet](https://github.com/palantir/javapoet) · [Square JavaPoet 弃用说明（镜像）](https://docsearch.algolia.com/mcp/docs/repo/square/javapoet)
- 反射 / MethodHandle / LambdaMetafactory 基准测试：[Timefold: Java reflection, but much faster](https://timefold.ai/blog/java-reflection-but-much-faster) · [mlvm-dev: LambdaMetafactory vs MethodHandle](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006826.html) · [后续讨论](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006827.html) · [neoremind/dynamic-proxy（较旧，非 JMH）](https://github.com/neoremind/dynamic-proxy)
- Resilience4j 注解的配置方式（依赖 Spring AOP）：[Baeldung](https://www.baeldung.com/spring-boot-resilience4j) · [oneuptime 指南](https://oneuptime.com/blog/post/2026-01-25-circuit-breakers-resilience4j-spring/markdown) · [resilience4j repo](https://github.com/resilience4j/resilience4j)
