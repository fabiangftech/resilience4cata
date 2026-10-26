# Instalación

Agrega cuatro artefactos a tu build: el BOM, `core`, las anotaciones y el procesador de anotaciones. Esta página cubre Gradle y Maven, los IDE, JPMS y otros procesadores de anotaciones.

**Requisitos:** Java 21 o posterior, tanto para compilar como para ejecutar (los jars se compilan con `--release 21` y se prueban en Java 21 y 25). Coordenadas: groupId `dev.resilience4cata`, versión `0.6.0`, en Maven Central.

## Los artefactos

| Artefacto | Qué es | Scope |
|---|---|---|
| `resilience4cata-bom` | Alinea las versiones de todos los artefactos de abajo | `platform(...)` (Gradle), `import` (Maven) |
| `resilience4cata-core` | Políticas, `ResilienceRegistry`, `Resilient`, `Pipeline`, eventos, snapshots. Su única dependencia es JSpecify (`org.jspecify:jspecify`) | `implementation` (Gradle), `compile` por defecto (Maven) |
| `resilience4cata-annotations` | `@Retry`, `@CircuitBreaker`, `@RateLimiter`, `@Timeout`, `@Bulkhead`, `@Fallback`, `@ResiliencePolicy`. Retención `CLASS`, así que no hacen falta en tiempo de ejecución | `compileOnly` (Gradle), `provided` (Maven) |
| `resilience4cata-processor` | El procesador de anotaciones que genera el código. **Obligatorio** cuando usas las anotaciones: no hay alternativa por reflexión | `annotationProcessor` (Gradle), `annotationProcessorPaths` (Maven). Nunca en el class path de ejecución |
| `resilience4cata-config` | Opcional. Configuración externa: archivos properties, variables de entorno, propiedades del sistema ([guía de configuración](../guides/configuration.md)) | `implementation` / scope por defecto |
| `resilience4cata-micrometer` | Opcional, desde 0.3.0. Métricas de Micrometer: counters a partir de eventos, gauges para breakers, bulkheads y rate limiters ([guía de Micrometer](../guides/micrometer.md)). Trae `micrometer-core` | `implementation` / scope por defecto |
| `resilience4cata-spring` | Opcional, desde 0.4.0. Autoconfiguración para Spring Boot 4.1+: el registry desde `application.yml`, beans anotados protegidos, indicador de salud, endpoint de actuator, métricas ([guía de Spring Boot](../guides/spring.md)). Trae `core` y `config` | `implementation` / scope por defecto |
| `resilience4cata-micronaut` | Opcional, desde 0.5.0. Integración con Micronaut 5.2+ (necesita Java 25, el único módulo por encima de Java 21): el registry desde `application.yml`, beans de interfaces `@ResilientBean` y clases anotadas protegidos, indicador de salud, endpoint de gestión, métricas ([guía de Micronaut](../guides/micronaut.md)). Trae `core` y `config` | `implementation` / scope por defecto |
| `resilience4cata-quarkus` | Opcional, desde 0.5.0. Extensión para Quarkus 3.40+: el registry desde `application.properties`, beans anotados y clientes REST protegidos, comprobación de preparación, métricas, comprobaciones al construir ([guía de Quarkus](../guides/quarkus.md)). Trae `core` y `config`; Quarkus añade `resilience4cata-quarkus-deployment` al construir | `implementation` / scope por defecto |
| `resilience4cata-testkit` | Opcional. `ManualClock` y `ResilienceTesting` para tests deterministas ([guía de tests](../guides/testing.md)). No depende de JUnit | `testImplementation` / `test` |
| `resilience4cata-junit` | Opcional, desde 0.3.0. Extensión de JUnit Jupiter (`@ResilienceTest`): un `ManualClock` nuevo y un registry sobre él para cada test, como parámetros ([guía de tests](../guides/testing.md#junit)). Trae testkit; necesita JUnit 5.10 o posterior, JUnit 6 incluido | `testImplementation` / `test` |

`core` y `processor` deben tener la misma versión; el BOM se encarga de eso.

## Agrega las dependencias

Importa primero el BOM y después agrega los artefactos sin versión.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.6.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.6.0')   // processor path is resolved separately

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
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.6.0")
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
      <version>0.6.0</version>
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
            <version>0.6.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

<!-- tabs:end -->

Por qué está cada parte:

- **Gradle: el BOM también en `annotationProcessor`.** Gradle resuelve el processor path aparte del class path de compilación, así que una platform agregada a `implementation` no le da su versión al procesador.
- **Maven: la `<version>` explícita en `<path>`.** Las versiones de `maven-compiler-plugin` anteriores a 3.12.0 no miran `<dependencyManagement>` para los processor paths y fallan con "version can neither be null, empty nor blank". Desde 3.12.0 la versión puede venir del BOM importado; escribirla funciona con cualquier versión del plugin. Mantenla igual a la del BOM.
- **El procesador va en el processor path, no en el class path.** Desde JDK 23, `javac` ya no ejecuta los procesadores que encuentra en el class path salvo que se lo pidas, y el procesador no tiene nada que hacer en tu artefacto de ejecución.

El procesador está registrado en Gradle como procesador incremental *isolating*, así que editar un archivo recompila solo lo que depende de él.

### Tipos anotados en las fuentes de test

Si anotas interfaces o clases en `src/test` (dobles de test, clientes solo para tests), el source set de test también necesita las anotaciones y el procesador.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    testCompileOnly 'dev.resilience4cata:resilience4cata-annotations'
    testAnnotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.6.0')
    testAnnotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    testCompileOnly("dev.resilience4cata:resilience4cata-annotations")
    testAnnotationProcessor(platform("dev.resilience4cata:resilience4cata-bom:0.6.0"))
    testAnnotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

#### **Maven**

No hay nada que agregar: la configuración de `maven-compiler-plugin` también se aplica a `testCompile`, y las dependencias `provided` están en el class path de test.

<!-- tabs:end -->

## Spring Boot

En una aplicación Spring Boot 4.1+, depende de `resilience4cata-spring` en lugar de `core` (trae `core` y `config`); las anotaciones y el procesador quedan como arriba. La [guía de Spring Boot](../guides/spring.md) cubre el resto.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.6.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.6.0')

    implementation 'dev.resilience4cata:resilience4cata-spring'           // brings core and config
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.6.0")
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

En una aplicación Micronaut 5.2+ (Java 25), depende de `resilience4cata-micronaut` en lugar de `core` (trae `core`
y `config`); las anotaciones y el procesador quedan como arriba, junto al procesador del propio Micronaut, en
cualquier orden. La opción del compilador `-Ar4c.inject=micronaut` permite que Micronaut cree las subclases
generadas de las clases anotadas. La [guía de Micronaut](../guides/micronaut.md) cubre el resto.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.6.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.6.0')

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
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.6.0")
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

En una aplicación Quarkus 3.40+, depende de `resilience4cata-quarkus` en lugar de `core` (trae `core` y `config`); las anotaciones y el procesador quedan como arriba. La [guía de Quarkus](../guides/quarkus.md#install) tiene los fragmentos de construcción y cubre el resto.

## Verifica que funciona

Anota un método de una interfaz (el [inicio rápido](quickstart.md) tiene una) y compila. Junto a la interfaz, el procesador escribe `<Interfaz>$Resilient.java`:

| Build | Fuentes principales | Fuentes de test |
|---|---|---|
| Gradle | `build/generated/sources/annotationProcessor/java/main/<paquete>/` | `build/generated/sources/annotationProcessor/java/test/<paquete>/` |
| Maven | `target/generated-sources/annotations/<paquete>/` | `target/generated-test-sources/test-annotations/<paquete>/` |

Una interfaz anidada recibe su nombre binario: `Repository.Cache` se convierte en `Repository$Cache$Resilient`.

Si el procesador no se ejecutó, el primer `Resilient.of(...)` falla de inmediato, antes de cualquier llamada, con:

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
```

## IDE

El procesador es procesamiento de anotaciones estándar de `javac`, así que un IDE genera las clases siempre que el procesamiento de anotaciones esté activo en el proyecto.

**IntelliJ IDEA.** Los proyectos Gradle compilan con Gradle por defecto (*Settings → Build, Execution, Deployment → Build Tools → Gradle → Build and run using: Gradle*), así que las clases generadas vienen de Gradle y no hace falta nada más. Si cambias esa opción a *IntelliJ IDEA*, o en proyectos Maven, marca *Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing*, recarga el proyecto Maven o Gradle para que se importe el processor path, y recompila.

**Eclipse.** En proyectos Maven, m2e configura el procesamiento de anotaciones a través de m2e-apt: activa *Preferences → Maven → Annotation Processing → Automatically configure JDT APT*, o define la propiedad `<m2e.apt.activation>jdt_apt</m2e.apt.activation>` en el POM. En proyectos Gradle (Buildship), revisa *Project → Properties → Java Compiler → Annotation Processing* y su *Factory Path*, que debe incluir `resilience4cata-processor` (y su dependencia `resilience4cata-annotations`).

**VS Code.** El *Extension Pack for Java* usa por debajo el compilador de Eclipse, así que valen las notas de Eclipse: los proyectos Maven toman `annotationProcessorPaths`, y en proyectos Gradle la opción `java.import.gradle.annotationProcessing.enabled` debe quedar activada (es su valor por defecto).

Si en un IDE `Resilient.of` falla con `R4C-RT-003` mientras el build de línea de comandos funciona, el IDE no ejecutó el procesador: recompila el proyecto después de activarlo.

## JPMS

En un módulo con nombre, lee `core` (y `config` o `testkit` si los usas), lee las anotaciones solo en compilación, y exporta a `core` cada paquete que tenga tipos anotados, porque `core` instancia las clases generadas por reflexión:

<!-- not-compiled: a module descriptor; the examples project is not modular -->
```java
module com.acme.shop {
    requires dev.resilience4cata.core;
    requires static dev.resilience4cata.annotations;
    requires dev.resilience4cata.config;              // optional

    exports com.acme.shop.clients to dev.resilience4cata.core;
}
```

Sin el export calificado, `Resilient.of` falla con [`R4C-RT-005`](../errors.md#r4c-rt-005), cuyo mensaje indica la línea que hay que agregar. `core` requiere JSpecify de forma transitiva, así que no necesitas declararlo.

## Con otros procesadores de anotaciones

resilience4cata funciona junto a Lombok, MapStruct y otros procesadores. Dos reglas:

1. **Todos los procesadores van en el mismo processor path.** Con Maven, una vez definido `annotationProcessorPaths`, `javac` ejecuta solo los procesadores que figuran ahí, así que Lombok y MapStruct también deben figurar.
2. **Lombok va primero.** Si una [clase anotada](../guides/annotated-classes.md) usa Lombok para generar el constructor o los métodos que sobrescribe la subclase, Lombok debe ejecutarse antes que resilience4cata; si no, la subclase generada no los ve y no compila (por ejemplo `constructor StockService in class StockService cannot be applied to given types`). Las interfaces anotadas no dependen del orden.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    compileOnly 'org.projectlombok:lombok:1.18.38'
    annotationProcessor 'org.projectlombok:lombok:1.18.38'                         // first

    implementation platform('dev.resilience4cata:resilience4cata-bom:0.6.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.6.0')
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

    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.6.0")
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
    <version>0.6.0</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

Con MapStruct, agrega `mapstruct-processor` al mismo path; si además usas Lombok, sigue las instrucciones propias de MapStruct para eso (el artefacto `lombok-mapstruct-binding`).

### `-Xlint:all -Werror`

Los builds que convierten cada advertencia en error funcionan. `javac` advierte ("No processor claimed any of these annotations") cuando ningún procesador reclama una anotación presente en las fuentes. Cuando el procesador de resilience4cata es el único en el path, reclama todas las anotaciones que ve; junto a otros procesadores, reclama solo las suyas (`dev.resilience4cata.annotation.*`), así que los demás siguen viendo todo lo demás. Si la advertencia aparece de todos modos, lista anotaciones que otro procesador dejó sin reclamar: agrega `-Xlint:-processing` a los argumentos del compilador.

> [!TIP]
> Declara cada interfaz anotada en su propio archivo. Una interfaz anotada escrita como segundo tipo de nivel superior, no público, en el archivo de otro tipo hace que la clase generada dispare la advertencia de lint `auxiliaryclass` de `javac`, que `-Werror` convierte en error.

## Imagen nativa

GraalVM Native Image no necesita configuración tuya; la traen los jars:

- **El procesador escribe metadatos de alcanzabilidad para cada clase generada**, en la salida de clases junto a ella: `META-INF/native-image/dev.resilience4cata.generated/<clase generada>/reachability-metadata.json`. Registra los constructores públicos de la clase para reflexión, que es lo que usan `Resilient.of` y `Resilient.create` para encontrarlos (una vez por instancia, nunca por llamada). Native Image lee todos los archivos con ese nombre bajo `META-INF/native-image` en el class path. Para `com.acme.PaymentClient`:

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

  Para una interfaz, la entrada aplica una vez que la interfaz es alcanzada (se inicializó una implementación suya, que `Resilient.of` necesita de todos modos). Para una clase anotada, la condición es `dev.resilience4cata.Resilient`, porque `Resilient.create(C.class, ...)` puede ejecutarse antes de que `C` se inicialice; también se registran los tipos array de los constructores varargs.
- **`core` inicializa sus hilos de fondo en tiempo de ejecución.** El watchdog de timeouts, el temporizador de timeouts async y el temporizador de esperas async los arrancan clases holder perezosas; `core` trae `META-INF/native-image/dev.resilience4cata/resilience4cata-core/native-image.properties` con `--initialize-at-run-time` para esos holders, así que un framework que inicializa clases en tiempo de build (Quarkus lo hace por defecto) nunca deja un hilo arrancado en la imagen. Crea el `ResilienceRegistry` y los servicios envueltos en tiempo de ejecución, no en un inicializador estático que corra durante el build de la imagen.
- Llamar directamente al constructor generado (`new StockService$Resilient(registry, warehouse)`) no usa reflexión.

Los metadatos usan el formato de un solo archivo de GraalVM for JDK 23 y posteriores (ver [Reachability Metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)). Con un GraalVM anterior, registra cada clase `…$Resilient` con sus constructores públicos en tu propio `reflect-config.json`. Los builds nativos todavía no forman parte de la suite de tests.

## Solución de problemas

- [`R4C-RT-003`](../errors.md#r4c-rt-003): no hay clase generada. El procesador no se ejecutó sobre el tipo, o el tipo no tiene ninguna anotación de resiliencia.
- [`R4C-RT-004`](../errors.md#r4c-rt-004): `core` y `processor` tienen versiones distintas. Usa el BOM.
- [`R4C-RT-005`](../errors.md#r4c-rt-005): un módulo con nombre no exporta el paquete a `dev.resilience4cata.core`.
- Los errores de compilación empiezan con un código como `[R4C-ATR-001]`; cada uno tiene una sección con un ejemplo incorrecto y la corrección en la [referencia de errores](../errors.md).
- Más síntomas y soluciones: [solución de problemas](../guides/troubleshooting.md).
