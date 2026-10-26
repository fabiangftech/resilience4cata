# Installation

Add four artifacts to your build: the BOM, `core`, the annotations and the annotation processor. This page covers Gradle and Maven, IDEs, JPMS and other annotation processors.

**Requirements:** Java 21 or newer, both to compile and to run (the jars are built with `--release 21` and tested on Java 21 and 25). Coordinates: groupId `dev.resilience4cata`, version `0.3.0`, on Maven Central.

## The artifacts

| Artifact | What it is | Scope |
|---|---|---|
| `resilience4cata-bom` | Aligns the versions of every artifact below | `platform(...)` (Gradle), `import` (Maven) |
| `resilience4cata-core` | Policies, `ResilienceRegistry`, `Resilient`, `Pipeline`, events, snapshots. Its only dependency is JSpecify (`org.jspecify:jspecify`) | `implementation` (Gradle), default `compile` (Maven) |
| `resilience4cata-annotations` | `@Retry`, `@CircuitBreaker`, `@RateLimiter`, `@Timeout`, `@Bulkhead`, `@Fallback`, `@ResiliencePolicy`. `CLASS` retention, so not needed at runtime | `compileOnly` (Gradle), `provided` (Maven) |
| `resilience4cata-processor` | The annotation processor that generates the code. **Mandatory** when you use the annotations: there is no reflection fallback | `annotationProcessor` (Gradle), `annotationProcessorPaths` (Maven). Never on the runtime class path |
| `resilience4cata-config` | Optional. External configuration: properties files, environment variables, system properties ([configuration guide](../guides/configuration.md)) | `implementation` / default scope |
| `resilience4cata-micrometer` | Optional, from 0.3.0. Micrometer metrics: counters from events, gauges for breakers, bulkheads and rate limiters ([Micrometer guide](../guides/micrometer.md)). Brings in `micrometer-core` | `implementation` / default scope |
| `resilience4cata-spring` | Optional, from 0.4.0. Spring Boot 4.1+ auto-configuration: the registry from `application.yml`, annotated beans protected, health indicator, actuator endpoint, metrics ([Spring Boot guide](../guides/spring.md)). Brings `core` and `config` | `implementation` / default scope |
| `resilience4cata-testkit` | Optional. `ManualClock` and `ResilienceTesting` for deterministic tests ([testing guide](../guides/testing.md)). Does not depend on JUnit | `testImplementation` / `test` |
| `resilience4cata-junit` | Optional, since 0.3.0. JUnit Jupiter extension (`@ResilienceTest`): a fresh `ManualClock` and a registry on it for each test, as parameters ([testing guide](../guides/testing.md#junit)). Brings testkit; needs JUnit 5.10 or newer, JUnit 6 included | `testImplementation` / `test` |

`core` and `processor` must have the same version; the BOM makes sure they do.

## Add the dependencies

Import the BOM first, then add the artifacts without versions.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.3.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.3.0')   // processor path is resolved separately

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
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.3.0")
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
      <version>0.3.0</version>
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
            <version>0.3.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

<!-- tabs:end -->

Why each part is there:

- **Gradle: the BOM on `annotationProcessor` too.** Gradle resolves the processor path separately from the compile class path, so a platform added to `implementation` does not give the processor its version.
- **Maven: the explicit `<version>` in `<path>`.** `maven-compiler-plugin` versions before 3.12.0 do not look at `<dependencyManagement>` for processor paths and fail with "version can neither be null, empty nor blank". From 3.12.0 on, the version can come from the imported BOM; writing it works with every plugin version. Keep it equal to the BOM's.
- **The processor goes on the processor path, not the class path.** Since JDK 23, `javac` no longer runs processors it finds on the class path unless asked to, and the processor has no business being in your runtime artifact.

The processor is registered with Gradle as an *isolating* incremental processor, so editing one file recompiles only what depends on it.

### Annotated types in test sources

If you annotate interfaces or classes under `src/test` (test doubles, test-only clients), the test source set needs the annotations and the processor as well.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    testCompileOnly 'dev.resilience4cata:resilience4cata-annotations'
    testAnnotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.3.0')
    testAnnotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    testCompileOnly("dev.resilience4cata:resilience4cata-annotations")
    testAnnotationProcessor(platform("dev.resilience4cata:resilience4cata-bom:0.3.0"))
    testAnnotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

#### **Maven**

Nothing to add: the `maven-compiler-plugin` configuration applies to `testCompile` too, and `provided` dependencies are on the test class path.

<!-- tabs:end -->

## Spring Boot

For a Spring Boot 4.1+ application, depend on `resilience4cata-spring` instead of `core` (it brings `core` and `config`); the annotations and the processor stay as above. The [Spring Boot guide](../guides/spring.md) covers the rest.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.4.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.4.0')

    implementation 'dev.resilience4cata:resilience4cata-spring'           // brings core and config
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.4.0")
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

## Verify that it works

Annotate one method of an interface (the [quickstart](quickstart.md) has one) and compile. Next to the interface, the processor writes `<Interface>$Resilient.java`:

| Build | Main sources | Test sources |
|---|---|---|
| Gradle | `build/generated/sources/annotationProcessor/java/main/<package>/` | `build/generated/sources/annotationProcessor/java/test/<package>/` |
| Maven | `target/generated-sources/annotations/<package>/` | `target/generated-test-sources/test-annotations/<package>/` |

A nested interface gets its binary name: `Repository.Cache` becomes `Repository$Cache$Resilient`.

If the processor did not run, the first `Resilient.of(...)` fails fast, before any call, with:

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
```

## IDEs

The processor is plain `javac` annotation processing, so an IDE generates the classes as long as annotation processing is on for the project.

**IntelliJ IDEA.** Gradle projects build with Gradle by default (*Settings → Build, Execution, Deployment → Build Tools → Gradle → Build and run using: Gradle*), so the generated classes come from Gradle and nothing else is needed. If you switch that setting to *IntelliJ IDEA*, or for Maven projects, check *Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing*, reload the Maven or Gradle project so the processor path is imported, and rebuild.

**Eclipse.** For Maven projects, m2e configures annotation processing through m2e-apt: enable *Preferences → Maven → Annotation Processing → Automatically configure JDT APT*, or set the property `<m2e.apt.activation>jdt_apt</m2e.apt.activation>` in the POM. For Gradle projects (Buildship), check *Project → Properties → Java Compiler → Annotation Processing* and its *Factory Path*, which must list `resilience4cata-processor` (and its dependency `resilience4cata-annotations`).

**VS Code.** The *Extension Pack for Java* uses the Eclipse compiler underneath, so the Eclipse notes apply: Maven projects pick up `annotationProcessorPaths`, and for Gradle projects the setting `java.import.gradle.annotationProcessing.enabled` must stay on (its default).

If an IDE shows `Resilient.of` failing with `R4C-RT-003` while the command-line build works, the IDE did not run the processor: rebuild the project after enabling it.

## JPMS

In a named module, read `core` (and `config` or `testkit` if you use them), read the annotations only at compile time, and export each package that holds annotated types to `core`, which instantiates the generated classes reflectively:

<!-- not-compiled: a module descriptor; the examples project is not modular -->
```java
module com.acme.shop {
    requires dev.resilience4cata.core;
    requires static dev.resilience4cata.annotations;
    requires dev.resilience4cata.config;              // optional

    exports com.acme.shop.clients to dev.resilience4cata.core;
}
```

Without the qualified export, `Resilient.of` fails with [`R4C-RT-005`](../errors.md#r4c-rt-005), whose message names the line to add. `core` requires JSpecify transitively, so you don't need to declare it.

## With other annotation processors

resilience4cata works next to Lombok, MapStruct and other processors. Two rules:

1. **Every processor goes on the same processor path.** With Maven, once `annotationProcessorPaths` is set, `javac` runs only the processors listed there, so Lombok and MapStruct must be listed too.
2. **Lombok goes first.** If an [annotated class](../guides/annotated-classes.md) uses Lombok to generate the constructor or the methods the subclass overrides, Lombok must run before resilience4cata, or the generated subclass does not see them and fails to compile (for example `constructor StockService in class StockService cannot be applied to given types`). Annotated interfaces don't depend on the order.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    compileOnly 'org.projectlombok:lombok:1.18.38'
    annotationProcessor 'org.projectlombok:lombok:1.18.38'                         // first

    implementation platform('dev.resilience4cata:resilience4cata-bom:0.3.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.3.0')
    implementation 'dev.resilience4cata:resilience4cata-core'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")                        // first

    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.3.0")
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
    <version>0.3.0</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

With MapStruct, add `mapstruct-processor` to the same path; if you also use Lombok, follow MapStruct's own instructions for it (the `lombok-mapstruct-binding` artifact).

### `-Xlint:all -Werror`

Builds that turn every warning into an error work. `javac` warns ("No processor claimed any of these annotations") when an annotation present in the sources is claimed by no processor. When resilience4cata's processor is the only one on the path, it claims every annotation it sees; next to other processors, it claims only its own (`dev.resilience4cata.annotation.*`), so the others still see everything else. If the warning appears anyway, it lists annotations that another processor left unclaimed: add `-Xlint:-processing` to the compiler arguments.

> [!TIP]
> Declare each annotated interface in its own file. An annotated interface written as a second, non-public top-level type in another type's file makes the generated class trigger `javac`'s `auxiliaryclass` lint warning, which `-Werror` turns into an error.

## Native image

GraalVM Native Image needs no configuration of your own; the jars bring it:

- **The processor writes reachability metadata for every generated class**, in the class output next to it: `META-INF/native-image/dev.resilience4cata.generated/<generated class>/reachability-metadata.json`. It registers the class's public constructors for reflection, which is what `Resilient.of` and `Resilient.create` use to find them (once per instance, never per call). Native Image reads every file of that name under `META-INF/native-image` on the class path. For `com.acme.PaymentClient`:

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

  For an interface, the entry applies once the interface is reached (an implementation of it has been initialized, which `Resilient.of` needs anyway). For an annotated class, the condition is `dev.resilience4cata.Resilient`, because `Resilient.create(C.class, ...)` can run before `C` is initialized; the array types of varargs constructors are registered too.
- **`core` initializes its background threads at run time.** The timeout watchdog, the async timeout timer and the async delay timer are started by lazy holder classes; `core` ships `META-INF/native-image/dev.resilience4cata/resilience4cata-core/native-image.properties` with `--initialize-at-run-time` for those holders, so a framework that initializes classes at build time (Quarkus does by default) never puts a started thread in the image. Create the `ResilienceRegistry` and the wrapped services at run time, not in a static initializer that runs during the image build.
- Calling the generated constructor directly (`new StockService$Resilient(registry, warehouse)`) uses no reflection at all.

The metadata uses the single-file format of GraalVM for JDK 23 and later (see [Reachability Metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)). With an older GraalVM, register each `…$Resilient` class with its public constructors in your own `reflect-config.json`. Native builds are not part of the test suite yet.

## Troubleshooting

- [`R4C-RT-003`](../errors.md#r4c-rt-003): no generated class. The processor did not run on the type, or the type has no resilience annotation.
- [`R4C-RT-004`](../errors.md#r4c-rt-004): `core` and `processor` have different versions. Use the BOM.
- [`R4C-RT-005`](../errors.md#r4c-rt-005): a named module does not export the package to `dev.resilience4cata.core`.
- Compile errors start with a code such as `[R4C-ATR-001]`; each one has a section with a wrong example and the fix in the [error reference](../errors.md).
- More symptoms and fixes: [troubleshooting](../guides/troubleshooting.md).
