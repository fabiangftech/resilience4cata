# 安装

两行就足以开始：`resilience4cata-all` 和注解处理器。本页介绍这种配置方式，以及各个独立模块、Gradle 和 Maven、IDE、JPMS 和其他注解处理器。

**要求：** 编译和运行都需要 Java 21 或更高版本（jar 使用 `--release 21` 构建，并在 Java 21 和 25 上测试）。坐标：groupId `dev.resilience4cata`，版本 `1.1.0`，发布在 Maven Central 上（自 1.0.0 起为稳定版本：在 1.x 内保持[兼容](../reference/compatibility.md)；最后一个 0.x 版本是 0.13.0）。

## 构件 :id=the-artifacts

| 构件 | 说明 | 作用域 |
|---|---|---|
| `resilience4cata-bom` | 统一下面所有构件的版本 | `platform(...)`（Gradle）、`import`（Maven） |
| `resilience4cata-all` | 从 0.8.0 起提供。一个依赖即包含 `core`、`annotations` 和 `config`，本身不含代码（[简便方式](#the-short-way)） | `implementation`（Gradle）、默认的 `compile`（Maven） |
| `resilience4cata-core` | 策略、`ResilienceRegistry`、`Resilient`、`Pipeline`、事件、快照。唯一的依赖是 JSpecify（`org.jspecify:jspecify`） | `implementation`（Gradle）、默认的 `compile`（Maven） |
| `resilience4cata-annotations` | `@Retry`、`@Hedge`、`@CircuitBreaker`、`@RateLimiter`、`@AdaptiveLimit`、`@Timeout`、`@Bulkhead`、`@Fallback`、`@ResiliencePolicy`、`@PolicyOrder`。保留策略为 `CLASS`，因此运行时不需要 | `compileOnly`（Gradle）、`provided`（Maven） |
| `resilience4cata-processor` | 生成代码的注解处理器。使用注解时**必须**添加：不存在基于反射的后备方案 | `annotationProcessor`（Gradle）、`annotationProcessorPaths`（Maven）。绝不放在运行时 class path 上 |
| `resilience4cata-config` | 可选。外部配置：属性文件、环境变量、系统属性（[配置指南](../guides/configuration.md)） | `implementation` / 默认作用域 |
| `resilience4cata-micrometer` | 可选，从 0.3.0 起提供。Micrometer 指标：来自事件的计数器，熔断器、舱壁隔离和限流器的 gauge（[Micrometer 指南](../guides/micrometer.md)）。会引入 `micrometer-core` | `implementation` / 默认作用域 |
| `resilience4cata-reactor` | 可选，从 0.11.0 起提供。Project Reactor：返回 `Mono` 或 `Flux` 的带注解方法，以及 `MonoPipelineOperator`/`FluxPipelineOperator`（[Project Reactor 指南](../guides/reactor.md)）。会引入 `reactor-core`（3.6 或更高版本） | `implementation` / 默认作用域 |
| `resilience4cata-spring` | 可选，从 0.4.0 起提供。Spring Boot 4.1+ 自动配置：从 `application.yml` 构建注册表、保护带注解的 bean、健康指示器、actuator 端点、指标（[Spring Boot 指南](../guides/spring.md)）。会引入 `core` 和 `config` | `implementation` / 默认作用域 |
| `resilience4cata-micronaut` | 可选，从 0.5.0 起提供。Micronaut 5.2+ 集成（需要 Java 25，是唯一高于 Java 21 的模块）：从 `application.yml` 构建注册表、保护 `@ResilientBean` 接口 bean 和带注解的类、健康指示器、管理端点、指标（[Micronaut 指南](../guides/micronaut.md)）。会引入 `core` 和 `config` | `implementation` / 默认作用域 |
| `resilience4cata-quarkus` | 可选，从 0.5.0 起提供。Quarkus 3.40+ 扩展：从 `application.properties` 构建注册表、保护带注解的 bean 和 REST 客户端、就绪检查、指标、构建时检查（[Quarkus 指南](../guides/quarkus.md)）。会引入 `core` 和 `config`；Quarkus 在构建时添加 `resilience4cata-quarkus-deployment` | `implementation` / 默认作用域 |
| `resilience4cata-testkit` | 可选。用于确定性测试的 `ManualClock` 和 `ResilienceTesting`（[测试指南](../guides/testing.md)）。不依赖 JUnit | `testImplementation` / `test` |
| `resilience4cata-junit` | 可选，从 0.3.0 起提供。JUnit Jupiter 扩展（`@ResilienceTest`）：为每个测试以参数形式提供一个新的 `ManualClock` 和基于它的注册表（[测试指南](../guides/testing.md#junit)）。会引入 testkit；需要 JUnit 5.10 或更高版本，包括 JUnit 6 | `testImplementation` / `test` |

`core` 的版本必须不低于 `processor`（同一主版本）；BOM 会让它们保持同一版本。

## 简便方式 :id=the-short-way

`resilience4cata-all` 依赖 `core`、`annotations` 和 `config`，所以一个依赖就能提供策略、注解和[外部配置](../guides/configuration.md)。处理器仍然需要单独一行：构建工具只从处理器路径运行注解处理器，因此任何依赖都无法把它带进来。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.1.0'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.1.0'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.1.0")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.1.0")
}
```

#### **Maven**

```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>1.1.0</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.1.0</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

在 Maven 中，`annotationProcessorPaths` 会取代对其他所有处理器的自动发现：如果你使用 Lombok、MapStruct 或其他处理器，也要把它们列在那里（[与其他注解处理器一起使用](#with-other-annotation-processors)）。

两行写同一个版本：`core` 的版本必须不低于 `processor`，保持一致最简单。注解也会进入运行时 class path；它是一个很小的 jar，运行时没有任何代码读取它。若要让注解只在编译时使用，或者不引入 `config`，请按下文逐个添加模块。

## 逐个模块添加 :id=module-by-module

先导入 BOM，然后添加不带版本号的构件。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')   // processor path is resolved separately

    implementation 'dev.resilience4cata:resilience4cata-core'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-config'          // optional
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'     // optional
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-core")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-config")          // optional
    testImplementation("dev.resilience4cata:resilience4cata-testkit")     // optional
}
```

#### **Maven**

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.resilience4cata</groupId>
      <artifactId>resilience4cata-bom</artifactId>
      <version>1.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-core</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-annotations</artifactId>
    <scope>provided</scope>
  </dependency>
  <!-- optional -->
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-config</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-testkit</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.13.0</version>
      <configuration>
        <release>21</release>
        <annotationProcessorPaths>
          <path>
            <groupId>dev.resilience4cata</groupId>
            <artifactId>resilience4cata-processor</artifactId>
            <version>1.1.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

<!-- tabs:end -->

每一部分存在的原因：

- **Gradle：`annotationProcessor` 上也要加 BOM。** Gradle 将处理器路径与编译 class path 分开解析，所以加到 `implementation` 上的 platform 不会为处理器提供版本。
- **Maven：`<path>` 中显式的 `<version>`。** 3.12.0 之前的 `maven-compiler-plugin` 版本在解析处理器路径时不会查看 `<dependencyManagement>`，并以 "version can neither be null, empty nor blank" 失败。从 3.12.0 起，版本可以来自导入的 BOM；直接写出版本则适用于所有插件版本。请让它与 BOM 的版本保持一致。
- **处理器放在处理器路径上，而不是 class path 上。** 从 JDK 23 起，除非明确要求，`javac` 不再运行它在 class path 上发现的处理器；而且处理器本来就不应该出现在你的运行时构件中。

处理器在 Gradle 中注册为 *isolating*（隔离型）增量处理器，因此修改一个文件只会重新编译依赖它的部分。

### 测试源码中的带注解类型 :id=annotated-types-in-test-sources

如果你在 `src/test` 下为接口或类添加注解（测试替身、仅用于测试的客户端），测试源码集也需要注解和处理器。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    testCompileOnly 'dev.resilience4cata:resilience4cata-annotations'
    testAnnotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    testAnnotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    testCompileOnly("dev.resilience4cata:resilience4cata-annotations")
    testAnnotationProcessor(platform("dev.resilience4cata:resilience4cata-bom:1.1.0"))
    testAnnotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

#### **Maven**

无需添加任何内容：`maven-compiler-plugin` 的配置同样适用于 `testCompile`，而 `provided` 依赖也在测试 class path 上。

<!-- tabs:end -->

## Spring Boot

对于 Spring Boot 4.1+ 应用，用 `resilience4cata-spring` 代替 `core` 作为依赖（它会引入 `core` 和 `config`）；注解和处理器与上面相同。其余内容见 [Spring Boot 指南](../guides/spring.md)。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')

    implementation 'dev.resilience4cata:resilience4cata-spring'           // brings core and config
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-spring")           // brings core and config
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-spring</artifactId>
</dependency>
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-annotations</artifactId>
  <scope>provided</scope>
</dependency>
<!-- and resilience4cata-processor in annotationProcessorPaths, as above -->
```

<!-- tabs:end -->

## Micronaut

对于 Micronaut 5.2+ 应用（Java 25），用 `resilience4cata-micronaut` 代替 `core` 作为依赖（它会引入 `core` 和 `config`）；注解和处理器与上面相同，与 Micronaut 自己的处理器并列，顺序任意。编译器选项 `-Ar4c.inject=micronaut` 让 Micronaut 能够创建带注解类的生成子类。其余内容见 [Micronaut 指南](../guides/micronaut.md)。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')

    implementation 'dev.resilience4cata:resilience4cata-micronaut'       // brings core and config
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.add('-Ar4c.inject=micronaut')
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-micronaut")       // brings core and config
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Ar4c.inject=micronaut")
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-micronaut</artifactId>
</dependency>
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-annotations</artifactId>
  <scope>provided</scope>
</dependency>
<!-- and resilience4cata-processor in annotationProcessorPaths, after micronaut-inject-java,
     with <compilerArgs><arg>-Ar4c.inject=micronaut</arg></compilerArgs> -->
```

<!-- tabs:end -->

## Quarkus

对于 Quarkus 3.40+ 应用，用 `resilience4cata-quarkus` 代替 `core` 作为依赖（它会引入 `core` 和 `config`）；注解和处理器与上面相同。[Quarkus 指南](../guides/quarkus.md#install)给出了构建配置片段，并介绍其余内容。

## 验证是否生效 :id=verify-that-it-works

为某个接口的一个方法添加注解（[快速入门](quickstart.md)中就有一个）并编译。处理器会在该接口旁边生成 `<Interface>$Resilient.java`：

| 构建工具 | 主源码 | 测试源码 |
|---|---|---|
| Gradle | `build/generated/sources/annotationProcessor/java/main/<package>/` | `build/generated/sources/annotationProcessor/java/test/<package>/` |
| Maven | `target/generated-sources/annotations/<package>/` | `target/generated-test-sources/test-annotations/<package>/` |

嵌套接口使用它的二进制名称：`Repository.Cache` 会变成 `Repository$Cache$Resilient`。

如果处理器没有运行，第一次 `Resilient.of(...)` 会在任何调用之前立即失败，并给出：

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
With Maven, in the maven-compiler-plugin's <annotationProcessorPaths>:
    <path><groupId>dev.resilience4cata</groupId><artifactId>resilience4cata-processor</artifactId><version>...</version></path>
```

在 Spring Boot、Micronaut 和 Quarkus 中，同样的错误会改为在启动时、任何调用之前报告：Spring Boot 中为 [R4C-SPR-006](../errors.md#r4c-spr-006)，Micronaut 中带注解的类为 [R4C-MN-007](../errors.md#r4c-mn-007)，Quarkus 中为 [R4C-QK-008](../errors.md#r4c-qk-008)（在 Quarkus 中它会让构建失败）。

## IDE :id=ides

处理器就是普通的 `javac` 注解处理，因此只要项目开启了注解处理，IDE 就会生成这些类。

**IntelliJ IDEA。** Gradle 项目默认使用 Gradle 构建（*Settings → Build, Execution, Deployment → Build Tools → Gradle → Build and run using: Gradle*），所以生成的类来自 Gradle，无需其他设置。如果你把该设置切换为 *IntelliJ IDEA*，或者对于 Maven 项目，请勾选 *Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing*，重新加载 Maven 或 Gradle 项目以导入处理器路径，然后重新构建。

**Eclipse。** 对于 Maven 项目，m2e 通过 m2e-apt 配置注解处理：启用 *Preferences → Maven → Annotation Processing → Automatically configure JDT APT*，或在 POM 中设置属性 `<m2e.apt.activation>jdt_apt</m2e.apt.activation>`。对于 Gradle 项目（Buildship），请检查 *Project → Properties → Java Compiler → Annotation Processing* 及其 *Factory Path*，其中必须列出 `resilience4cata-processor`（以及它的依赖 `resilience4cata-annotations`）。

处理器在 Eclipse 编译器 (ECJ) 下生成的代码与在 `javac` 下完全相同：Eclipse、VS Code 以及设置为使用 Eclipse 编译的 IntelliJ IDEA 都使用它。项目的测试会用两者编译数千个随机生成的带注解源文件，并比较处理器写出的内容。

**VS Code。** *Extension Pack for Java* 底层使用 Eclipse 编译器，因此 Eclipse 的说明同样适用：Maven 项目会读取 `annotationProcessorPaths`；对于 Gradle 项目，设置 `java.import.gradle.annotationProcessing.enabled` 必须保持开启（这是它的默认值）。

如果 IDE 中 `Resilient.of` 以 `R4C-RT-003` 失败，而命令行构建正常，说明 IDE 没有运行处理器：开启后重新构建项目。

## JPMS

在命名模块中，读取 `core`（如果使用 `config` 或 `testkit`，也要读取它们），仅在编译时读取注解，并把每个包含带注解类型的包导出给 `core`，因为 `core` 通过反射实例化生成的类：

<!-- not-compiled: 一个模块描述符；examples 项目不是模块化的 -->
```java
module com.acme.shop {
    requires dev.resilience4cata.core;
    requires static dev.resilience4cata.annotations;
    requires dev.resilience4cata.config;              // optional

    exports com.acme.shop.clients to dev.resilience4cata.core;
}
```

使用 `resilience4cata-all` 时，`requires dev.resilience4cata.all;` 可以代替这三个 `requires`：它以传递方式读取 `core`、注解和 `config`。

如果没有限定导出，`Resilient.of` 会以 [`R4C-RT-005`](../errors.md#r4c-rt-005) 失败，其消息会指出需要添加的那一行。`core` 以传递方式 requires JSpecify，所以你不需要声明它。

## 与其他注解处理器一起使用 :id=with-other-annotation-processors

resilience4cata 可以与 Lombok、MapStruct 和其他处理器一起工作。**所有处理器都放在同一条处理器路径上。** 在 Maven 中，一旦设置了 `annotationProcessorPaths`，`javac` 只运行其中列出的处理器，因此 Lombok 和 MapStruct 也必须列出。

处理器的顺序无关紧要。使用 Lombok 的[带注解的类](../guides/annotated-classes.md)总是在 Lombok 修改之后才被处理，无论谁先运行；因此生成的子类会镜像 Lombok 写出的构造函数（`@RequiredArgsConstructor`），类型级策略也会像对待其他方法一样覆盖 Lombok 写出的 getter 和 setter（参见[与 Lombok 一起使用](../guides/annotated-classes.md#with-lombok)）。在 1.0.0 之前，Lombok 必须排在第一位。

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    compileOnly 'org.projectlombok:lombok:1.18.38'
    annotationProcessor 'org.projectlombok:lombok:1.18.38'

    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    implementation 'dev.resilience4cata:resilience4cata-core'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")

    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)
    implementation("dev.resilience4cata:resilience4cata-core")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

#### **Maven**

```xml
<annotationProcessorPaths>
  <path>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.38</version>
  </path>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.1.0</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

使用 MapStruct 时，把 `mapstruct-processor` 加到同一条路径上；如果同时使用 Lombok，请按照 MapStruct 自己的说明处理（`lombok-mapstruct-binding` 构件）。

### `-Xlint:all -Werror`

把所有警告都当作错误的构建可以正常工作。当源码中出现的某个注解没有被任何处理器认领时，`javac` 会发出警告（"No processor claimed any of these annotations"）。当 resilience4cata 的处理器是路径上唯一的处理器时，它会认领它看到的所有注解；与其他处理器并存时，它只认领自己的注解（`dev.resilience4cata.annotation.*`），这样其他处理器仍能看到其余所有注解。如果警告仍然出现，它列出的是其他处理器未认领的注解：在编译器参数中加上 `-Xlint:-processing`。

> [!TIP]
> 把每个带注解的接口声明在它自己的文件中。如果带注解的接口作为另一个类型文件中的第二个非 public 顶层类型来编写，生成的类会触发 `javac` 的 `auxiliaryclass` lint 警告，而 `-Werror` 会把它变成错误。

## 原生镜像 :id=native-image

GraalVM Native Image 不需要你自己做任何配置；jar 已经自带：

- **处理器为每个生成的类写出可达性元数据**，放在类输出目录中、与该类相邻：`META-INF/native-image/dev.resilience4cata.generated/<generated class>/reachability-metadata.json`。它为反射注册该类的 public 构造函数，`Resilient.of` 和 `Resilient.create` 正是用它们来查找构造函数（每个实例一次，从不在每次调用时）。Native Image 会读取 class path 上 `META-INF/native-image` 下所有同名文件。对于 `com.acme.PaymentClient`：

  ```json
  {
    "reflection": [
      {
        "condition": {"typeReached": "com.acme.PaymentClient"},
        "type": "com.acme.PaymentClient$Resilient",
        "allPublicConstructors": true
      }
    ]
  }
  ```

  对于接口，该条目在接口被触达后生效（即它的某个实现已被初始化，而 `Resilient.of` 本来就需要这一点）。对于带注解的类，条件是 `dev.resilience4cata.Resilient`，因为 `Resilient.create(C.class, ...)` 可能在 `C` 初始化之前运行；可变参数构造函数的数组类型也会被注册。
- **`core` 在运行时初始化它的后台线程。** 超时 watchdog、异步超时定时器和异步延迟定时器由延迟加载的 holder 类启动；`core` 自带 `META-INF/native-image/dev.resilience4cata/resilience4cata-core/native-image.properties`，为这些 holder 设置 `--initialize-at-run-time`，这样在构建时初始化类的框架（Quarkus 默认如此）永远不会把已启动的线程放进镜像。请在运行时创建 `ResilienceRegistry` 和被包装的服务，而不是在镜像构建期间运行的静态初始化器中创建。
- 直接调用生成的构造函数（`new StockService$Resilient(registry, warehouse)`）完全不使用反射。
- **在[配置](../guides/configuration.md)中引用的异常类需要你自己注册。** `resilience4cata-config` 用 `Class.forName` 查找写在键中的类（`configs.inventory.retry.retryOn=com.acme.OutOfStockException`），而在原生镜像中它只能找到已为反射注册的类，库无法替你注册你的类。把每个类加到你的应用的 `META-INF/native-image/<group>/<artifact>/reachability-metadata.json` 中；否则配置会以 [R4C-CFG-003](../errors.md#r4c-cfg-003) 失败，其消息会给出需要添加的条目。注解中引用的类不需要任何处理（生成的代码以 `.class` 字面量引用它们）。`resilience4cata.properties` 会自动进入镜像：Native Image 会包含以常量名称查找的资源，`ResilienceConfig.load()` 正是这样做的。

  ```json
  {
    "reflection": [
      {"type": "com.acme.OutOfStockException"}
    ]
  }
  ```

元数据使用 GraalVM for JDK 23 及更高版本的单文件格式（参见 [Reachability Metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)）。使用更旧的 GraalVM 时，请在你自己的 `reflect-config.json` 中注册每个 `…$Resilient` 类及其 public 构造函数。项目的 CI 会为四个应用构建原生镜像并在镜像内运行它们的测试：一个不使用框架的纯 Java 应用（仓库中的 `native/java`：`Resilient.of` 和 `Resilient.create`、来自 `resilience4cata.properties` 的配置、所有策略、`CompletableFuture`、`Mono` 和 `Flux` 方法以及一个 `Pipeline`），以及 [Spring Boot](../guides/spring.md#aot)、[Micronaut](../guides/micronaut.md#native) 和 [Quarkus](../guides/quarkus.md#native) 集成的应用。

## 故障排查 :id=troubleshooting

- [`R4C-RT-003`](../errors.md#r4c-rt-003)：没有生成的类。处理器没有在该类型上运行，或者该类型没有任何韧性注解。同样的错误在 Spring Boot 中是 [R4C-SPR-006](../errors.md#r4c-spr-006)，在 Micronaut 中是 [R4C-MN-007](../errors.md#r4c-mn-007)（带注解的类），在 Quarkus 中是 [R4C-QK-008](../errors.md#r4c-qk-008)（在构建时）。
- [`R4C-RT-004`](../errors.md#r4c-rt-004)：`core` 和 `processor` 的版本不同。请使用 BOM。
- [`R4C-RT-005`](../errors.md#r4c-rt-005)：命名模块没有把包导出给 `dev.resilience4cata.core`。
- 编译错误以 `[R4C-ATR-001]` 这样的错误码开头；每个错误码在[错误参考](../errors.md)中都有一节，包含错误示例和修复方法。
- 更多症状和修复方法：[故障排查](../guides/troubleshooting.md)。
