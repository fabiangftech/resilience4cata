# 02 · Mecanismos de anotación: convertir anotaciones en comportamiento sin un framework

> **Histórico.** Esta investigación es anterior a la implementación y se conserva tal como se escribió; para lo que hace realmente 0.1.0, ver [features](../features.md), [arquitectura](../architecture.md) y [benchmarks](../benchmarks.md).

> **Fase 1, solo investigación.** Sin código de producción. Este documento compara las formas en que una librería Java 21+ independiente (sin Spring, sin Quarkus, sin CDI) puede convertir `@CircuitBreaker`, `@Retry` y anotaciones similares en comportamiento en ejecución. Termina con una recomendación priorizada y un boceto de la API que vería el usuario.
>
> **Cómo leer la evidencia:**
> - **[V] Verificado**: contrastado con una fuente primaria durante esta investigación (con enlace), o medido por nosotros (ver [§3](#3-mediciones-propias-jmh)).
> - **[I] Inferido**: viene del criterio de ingeniería o de conocimiento previo y no se volvió a contrastar con una fuente para este documento. Debe tratarse como una hipótesis a confirmar en la fase 2.
>
> Versión en inglés: [`docs/en/research/02-annotation-mechanisms.md`](../../en/research/02-annotation-mechanisms.md).

---

## 0. Resumen

1. **Recomendado: procesamiento de anotaciones en tiempo de compilación (APT) que genera código fuente Java plano**, con una pequeña fachada en runtime (`Resilient.of(...)`) que localiza la clase generada una sola vez, al crear la instancia. Por llamada cuesta casi lo mismo que el código escrito a mano: sin reflexión, sin empaquetar argumentos en `Object[]` y con un único call site monomórfico por método generado. Arranca al instante, funciona en GraalVM native-image sin configuración, produce stack traces legibles y detecta en compilación errores como una firma de fallback incorrecta o un nombre de configuración desconocido.
2. **La forma del código generado importa tanto como el mecanismo.** En nuestro JMH, el código generado que llama a la política a través de un `execute(Supplier)` compartido que recibe una lambda fue **unas 2× más lento** que el código generado con el protocolo de la política inlineado, en cuanto varios servicios compartían esa ruta (14,2 ns frente a 7,3 ns para 4 llamadas) **[V, medido]**. Por eso el módulo core debe ofrecer, junto a la API funcional pensada para personas, un "protocolo de permiso" de bajo nivel y sin asignaciones (adquirir y luego onSuccess u onError) para el código generado.
3. **`java.lang.reflect.Proxy` es de 5 a 15× más lento por llamada y asigna memoria** (16–64 B por llamada en nuestra prueba). Solo sirve para interfaces. Solo es aceptable como **fallback opcional**.
4. **ByteBuddy en runtime es tan rápido por llamada como el código fuente generado** (emite bytecode real), pero la primera instancia tardó **unos 200 ms frente a 0,37 ms** del código generado **[V, medido]**. Además añade una dependencia de 9 MB, y native-image necesita pasos de build adicionales. No es la opción por defecto; como mucho podría respaldar un módulo opcional.
5. **Los Java agents y el weaving de AspectJ** tienen buen rendimiento por llamada pero la peor experiencia de usuario: flags de JVM, un aviso por attach dinámico desde JDK 21, un weaver que tiene que seguir cada JDK nuevo y un compilador no estándar. Se descartan para el core.
6. La **Class-File API solo es final desde JDK 24** **[V]**, así que no se puede usar con nuestra base Java 21 sin un JAR multi-release. Conviene tenerla presente para cuando la base pase a Java 25 LTS.

---

## 1. El problema

Para cada método anotado debemos, sin contenedor:

1. **Descubrir** las anotaciones (`@CircuitBreaker("payments")`, `@Retry(maxAttempts = 3)`, `@Fallback(...)`).
2. **Resolver la configuración**: una config con nombre, luego los valores globales por defecto, luego los overrides por método que vienen de los atributos de la anotación y luego la config externa. (El agente de diseño de API y configuración decide las reglas de precedencia. A este documento solo le importa *cuándo* se resuelve: en build, al envolver o en cada llamada.)
3. **Construir una instancia** cuyo método ejecute la cadena de políticas alrededor del código del usuario.
4. **Entregar esa instancia al usuario**, sin un contenedor DI que la inyecte.

El coste por llamada lo decide el paso 3: cómo se produce el código envolvente y qué puede hacer el JIT con él.

### Criterios de evaluación

| Criterio | Por qué nos importa |
|---|---|
| Overhead por llamada | Es nuestro objetivo principal ("más rápido que Resilience4j"). Incluye inlining del JIT, call sites megamórficos, reflexión, boxing y asignaciones. |
| Coste de arranque / primera llamada | CLIs, serverless, tests. |
| GraalVM native-image | Cada vez más esperado en una librería. Allí no se pueden definir clases en runtime. |
| Depurabilidad | Stack traces, ejecución paso a paso, "¿qué código se ejecuta realmente?". |
| Complejidad de build para el usuario | No tenemos un plugin de framework detrás del que esconderla. |
| Limitaciones | Clases final, métodos privados, interfaces frente a clases, autoinvocación. |
| Síncrono / `CompletableFuture` / hilos virtuales | Los tres tienen que funcionar. |

---

## 2. Las opciones

### 2.1 Procesamiento de anotaciones en compilación (APT) generando código fuente

**Cómo funciona.** Un `javax.annotation.processing.Processor` se ejecuta dentro de `javac`, lee `@CircuitBreaker` y las anotaciones relacionadas a través del modelo `Element`/`TypeMirror` y escribe nuevos `.java` mediante el `Filer`. Esos archivos se compilan en el mismo build. En runtime no se ejecuta nada salvo el código que se generó.

**Precedentes.**
- **Dagger, MapStruct, Immutables**: procesadores clásicos que generan código fuente (sin AOP). **[I]**
- **Avaje Inject** genera clases `MyService$Proxy` que **extienden la clase objetivo y sobrescriben los métodos anotados**. Las sobrescrituras ejecutan los interceptores y luego llaman a `super`. Los aspectos se declaran con `@Aspect`, `AspectProvider<T>` y `MethodInterceptor`, y `@AOPFallback` indica un método de recuperación. **[V]** ([avaje.io/inject](https://avaje.io/inject/#aspects))
- **Micronaut AOP**: por defecto genera una **subclase** del objetivo en compilación y llama al original con `super`. `@Around(proxyTarget = true)` hace que genere en su lugar un proxy delegador. La documentación de Micronaut dice que la subclase por defecto es "más eficiente" y permite que la interceptación funcione en llamadas hechas desde dentro de la propia clase. **[V]** ([javadoc de `@Around`](https://docs.micronaut.io/4.8.2/api/io/micronaut/aop/Around.html)). Micronaut también incluye `@Retryable` y `@CircuitBreaker` construidos sobre este mecanismo, y un helper `InterceptedMethod` para tipos de retorno `CompletionStage` y reactivos. **[I]** ([guía AOP de Micronaut](https://docs.micronaut.io/latest/guide/#aop))

**Herramientas de generación.**
- **Palantir JavaPoet** (`com.palantir.javapoet:javapoet`) es el fork mantenido. Su README describe el JavaPoet de Square como "no longer actively maintained" y añade builders para records y tipos sealed. **[V]** ([palantir/javapoet](https://github.com/palantir/javapoet), [nota de deprecación de Square vía mirror](https://docsearch.algolia.com/mcp/docs/repo/square/javapoet))
- Alternativa: plantillas escritas a mano, sin dependencias. El código generado es pequeño y regular, así que es realista y mantiene el procesador libre de dependencias. **[I]**
- El JAR del procesador solo se necesita **en compilación** (configuración `annotationProcessor`), así que JavaPoet nunca llega al classpath de runtime del usuario. **[I]**

**Procesamiento incremental en Gradle [V]** ([documentación de Gradle](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing)):
- Un procesador se declara en `META-INF/gradle/incremental.annotation.processors` como `isolating`, `aggregating` o `dynamic`.
- **Isolating** es el más rápido. Cada archivo generado debe tener **exactamente un elemento de origen**, y las decisiones solo pueden basarse en lo alcanzable desde ese elemento. Un `Foo$Resilient` por cada `Foo` anotado encaja perfectamente.
- **Aggregating** reprocesa todos los archivos anotados. Lo necesitaríamos si generáramos un único registro, por ejemplo un archivo `META-INF/services`. **Consecuencia de diseño:** localizar las clases generadas **por convención de nombres** (`Foo` → `Foo$Resilient`) en vez de mediante un registro agregado, para que el procesador pueda seguir siendo *isolating*.
- Otras restricciones: usar solo la API `Filer`, evitar APIs internas del compilador como `com.sun.source.util.Trees`, y tener en cuenta que los nombres de parámetros solo están disponibles con `-parameters`.

**JDK 23 cambió el comportamiento por defecto de javac [V]:** el procesamiento de anotaciones ya no se ejecuta implícitamente desde el classpath. Necesita configuración explícita, como `-processorpath` o `-proc:full`. ([notas de JDK 23](https://www.oracle.com/java/technologies/javase/23-relnote-issues.html), [revisión de JDK-8321314](https://mail.openjdk.org/pipermail/compiler-dev/2024-May/026675.html)). La configuración `annotationProcessor` de Gradle pasa un processor path explícito, así que a los usuarios de Gradle no debería afectarles **[I]**. Aun así, nuestra documentación debe mostrar la línea `annotationProcessor(...)`, y los usuarios de Maven necesitan `annotationProcessorPaths`.

**Estilos de envoltorio que puede generar el procesador:**

| Estilo | Forma generada | Ventajas | Inconvenientes |
|---|---|---|---|
| **Envoltorio delegador** | `final class Foo$Resilient implements Foo { final Foo delegate; ... }` | Funciona con **interfaces**. Envuelve *cualquier* instancia, incluidas las que vienen de otro contenedor DI, de una factoría de terceros o de un doble de test. Nunca intercepta autollamadas, lo cual es predecible. | Un dispatch de interfaz extra hacia el delegado. Solo se pueden decorar métodos de la interfaz. |
| **Subclase** | `final class Foo$Resilient extends Foo { ... super.call() ... }` | Funciona con **clases concretas**. Medida como ligeramente más rápida que la delegación ([§3](#3-mediciones-propias-jmh)). Puede interceptar la autoinvocación si queremos. | La clase no puede ser `final`, y los métodos no pueden ser `final`, `private` ni `static`. Hay que replicar cada constructor. El *usuario* tiene que crear la subclase en vez de `new Foo(...)`. |

**Límites duros de APT [I, semántica estándar de Java]:**
- No puede modificar la propia clase anotada; APT solo puede *añadir* archivos. Por eso no puede decorar métodos `private`, `final` o `static`, ni clases final cuando genera una subclase.
- Solo ve el código compilado en el mismo build: no puede decorar clases de un JAR de terceros. Ese caso se cubre con el envoltorio delegador sobre una interfaz o con la API funcional.
- No soporta Kotlin salvo que se escriba un port a KSP o kapt. Fuera de alcance.
- Los mensajes de error del procesador *son* la experiencia del usuario, así que tienen que ser excelentes.

**Cómo obtiene el usuario la instancia.** Opciones, de la más explícita a la más cómoda:
1. `new PaymentClient$Resilient(delegate, registry)`: explícita, pero un nombre con `$` en el código del usuario queda feo.
2. Una factoría estática generada: `PaymentClientResilience.wrap(delegate, registry)`. Es type-safe y no tiene ninguna magia.
3. Una fachada en runtime, `Resilient.of(PaymentClient.class, delegate)`. Localiza `PaymentClient$Resilient` **una vez** con `Class.forName` y una caché `ClassValue`, invoca un constructor generado o un `MethodHandle` creado una sola vez, y devuelve la instancia. **No hay reflexión por llamada.** El procesador también puede emitir `META-INF/native-image/.../reflect-config.json` para ese constructor, de modo que native-image no necesita configuración por parte del usuario. **[I]**

   Recomendamos ofrecer (3) como punto de entrada documentado y (2) para quien no quiera ninguna búsqueda.

**Valoración.**

| Criterio | Valoración |
|---|---|
| Overhead por llamada | **El mejor.** Es código Java normal: un call site monomórfico por método generado, así que el JIT puede inlinear la política y el método del usuario. El tipo de retorno se conoce estáticamente, así que no hay boxing ni `Object[]`. Medido en 2,4–2,6 ns frente a 1,2 ns de una llamada directa ([§3](#3-mediciones-propias-jmh)). **[V]** |
| Arranque | **El mejor.** Cargar la primera instancia costó unos 0,37 ms en nuestra prueba, que es solo carga de clases. **[V]** |
| native-image | **Nativo.** Ni proxies ni bytecode en runtime. Como mucho necesita un constructor reflexivo, y podemos generar esos metadatos. **[I]** |
| Depurabilidad | **La mejor.** El código generado queda en `build/generated/sources/annotationProcessor`, así que el usuario puede abrirlo y depurarlo paso a paso. Los frames se ven como `PaymentClient$Resilient.charge(PaymentClient$Resilient.java:41)`. **[I]** |
| Complejidad de build | Baja: una línea de dependencia `annotationProcessor`. Los IDE (IntelliJ, Eclipse) ejecutan procesadores sin configuración. **[I]** |
| Sync / async / hilos virtuales | El procesador ve el **tipo de retorno declarado** en compilación y emite código especializado para resultados síncronos, `void` y `CompletionStage`/`CompletableFuture` (encadenando callbacks en vez de bloquear). No necesita comprobaciones `instanceof` en runtime. Los hilos virtuales no requieren generación especial: el código síncrono simplemente bloquea sobre un hilo virtual. Los problemas de pinning le tocan al core (ver el agente de concurrencia). **[I]** |
| Beneficio extra | **Validación en compilación**: un método de fallback inexistente, una firma de fallback incompatible o anotaciones en conflicto pasan a ser errores de compilación. La integración de Resilience4j con Spring resuelve `fallbackMethod` por nombre mediante reflexión en runtime **[I]** ([código de resilience4j](https://github.com/resilience4j/resilience4j)). |

### 2.2 Proxies dinámicos en runtime (`java.lang.reflect.Proxy`)

**Cómo funciona.** `Proxy.newProxyInstance(loader, interfaces, handler)` genera una clase proxy en runtime. Cada llamada va a `InvocationHandler.invoke(proxy, Method, Object[] args)`.

**Hechos:**
- Solo admite **interfaces**. **[I, contrato de la API del JDK]**
- Desde **JDK 18 (JEP 416)**, `Method.invoke` está implementado sobre method handles. Es **un 43–57 % más rápido cuando el `Method` es un `static final` que se puede plegar como constante**, pero **más lento cuando no lo es** (acceso a campos un 51–77 % más lento en microbenchmarks). **[V]** ([JEP 416](https://openjdk.org/jeps/416)). En un handler de proxy, el `Method` llega como *argumento*, así que en general **no** es constante en el sitio de `invoke`. Es el caso malo.
- El handler es **compartido**: un único cuerpo de `InvocationHandler.invoke`, y el `Method.invoke` que contiene, sirven a todos los métodos de todos los servicios con proxy. En cualquier aplicación real esos sitios se vuelven **megamórficos**, lo que impide el inlining. **[I, coherente con nuestra medición poly4]**
- Los argumentos se empaquetan en un `Object[]`, los primitivos se boxean y las excepciones checked llegan envueltas en `InvocationTargetException` o `UndeclaredThrowableException`. **[I]**
- **native-image:** los proxies tienen que generarse al construir la imagen. Native Image detecta automáticamente las llamadas a `newProxyInstance` **solo cuando el array de interfaces es una constante o se crea en el mismo método**. Si no, hace falta configuración JSON de proxies. **[V]** ([documentación de Dynamic Proxy de GraalVM](https://www.graalvm.org/jdk17/reference-manual/native-image/dynamic-features/DynamicProxy/), [referencia de metadatos](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)). Una librería que crea proxies para interfaces *del usuario* recibidas en runtime cae en el caso que necesita configuración.
- **Variante con MethodHandle:** cachear un `MethodHandle` enlazado y "spread" por cada `Method` **no** ayudó en nuestra prueba. Fue más lento que `Method.invoke` a secas (25 ns frente a 16 ns en mono, 91 ns frente a 37 ns en poly) por la búsqueda en el mapa y el handle no constante **[V, medido]**. Los `MethodHandle` no constantes no se inlinean, lo que coincide con lo que han dicho ingenieros de OpenJDK ([hilo mlvm-dev](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006826.html)) **[V]**.
- **LambdaMetafactory** puede convertir un `Method` en una implementación real de interfaz funcional y evitar la reflexión una vez creada la lambda. JMH antiguos la sitúan cerca de una llamada directa cuando el inlining funciona ([Timefold](https://timefold.ai/blog/java-reflection-but-much-faster), [mlvm-dev](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006827.html)) **[V]**. Pero no elimina el handler compartido y megamórfico, así que mejora la llamada al *destino* y deja intacto el problema de despacho. **[I]**

| Criterio | Valoración |
|---|---|
| Overhead por llamada | **Malo.** 16 ns en mono / unos 9 ns por llamada en poly4, frente a 2–2,6 ns del código generado; 16–64 B asignados por llamada. **[V, medido]** |
| Arranque | Primera instancia de proxy en unos 2,4 ms. **[V, medido]** |
| native-image | Necesita configuración para interfaces recibidas en runtime. **[V]** |
| Depurabilidad | Regular: `jdk.proxy1.$Proxy7.charge(Unknown Source)`, luego frames del handler, luego frames de reflexión, y excepciones envueltas. **[I]** |
| Complejidad de build | Ninguna. |
| Limitaciones | Solo interfaces. |

**Veredicto:** un **fallback razonable sin configuración de build** para interfaces, por ejemplo en un módulo opcional `dynamic`, pero nunca la ruta por defecto.

### 2.3 Generación de bytecode en runtime (ByteBuddy, Class-File API, hidden classes)

**ByteBuddy.**
- Genera bytecode real en runtime, como subclases o implementaciones de interfaz. `Advice` *copia* el código del advice dentro del método generado, mientras que `MethodDelegation` emite una llamada directa a un interceptor. **[V]** (comportamiento confirmado por el diseño y los resultados de nuestro benchmark)
- Por llamada es **excelente**, igual o algo más rápido que nuestras variantes de código generado en nuestra prueba (2,0 ns en mono, unos 1,5 ns por llamada en poly4). Parte de esa ventaja viene del propio benchmark: nuestras variantes ByteBuddy leen el guard de un campo `static final` (plegado como constante) y las de código generado leen un campo de instancia. Ver [§3](#3-mediciones-propias-jmh). **[V, medido + I para la explicación]**
- **El arranque es caro:** unos **200 ms** para crear la primera instancia en una JVM nueva, la mayor parte en cargar e inicializar el propio ByteBuddy. **[V, medido]**
- **Peso de la dependencia:** `byte-buddy-1.18.3.jar` pesa **9,2 MB** **[V, archivo local]**. Se puede hacer shading, pero es mucho para meterlo en el classpath de cada usuario.
- **Seguimiento del JDK:** Byte Buddy publica una tabla de versión mínima por cada versión de class-file de Java. Desde 1.17.0 usa la Class-File API para seguir los JDK nuevos salvo que aparezcan nuevas características del formato de clase. **[V]** ([README de raphw/byte-buddy](https://github.com/raphw/byte-buddy))
- **native-image:** no se pueden definir clases en runtime. Las vías admitidas son generar las clases en build, o las "predefined classes" del agente de trazado, que según la documentación son best-effort y "not guaranteed to work". **[V]** ([metadatos de GraalVM](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)). ByteBuddy tiene un **plugin de Gradle para transformar en build** (`net.bytebuddy.byte-buddy-gradle-plugin`), lo que lo convertiría en una herramienta de weaving (ver §2.4). **[V]** ([Gradle Plugin Portal](https://plugins.gradle.org/plugin/net.bytebuddy.byte-buddy-gradle-plugin))
- **Depurabilidad:** no hay código fuente. Los frames se ven como `PaymentClient$ByteBuddy$a1b2c3.charge(Unknown Source)`, y entrar paso a paso en el advice es incómodo. **[I]**
- **Acceso a clases del usuario:** para hacer una subclase de una clase del usuario en su paquete, o para definir un nestmate, hace falta un `Lookup` con el acceso adecuado. Con JPMS eso significa que el módulo del usuario tiene que hacer `open` de su paquete para nosotros. **[I]**

**La API `java.lang.classfile`.**
- Preview en JDK 22 (JEP 457) y JDK 23 (JEP 466), y **final en JDK 24 (JEP 484)**. **[V]** ([commit JDK-8334714](https://gitea.hb.dhbw-stuttgart.de/stan/jdk-24/commit/84ffb64cd73f8af11cf3670c6f19d282c2ac6961), [correo de jdk-dev](https://mail.openjdk.org/pipermail/jdk-dev/2024-October/009461.html), [Inside Java Newscast #81](https://nipafx.dev/inside-java-newscast-81/))
- Como nuestro mínimo es Java 21, solo podríamos usarla desde la capa `META-INF/versions/24` de un JAR multi-release. Además tiene los mismos problemas de native-image y arranque que cualquier generación en runtime. **Se vuelve relevante si la base sube a 25 (LTS)**, por ejemplo para un fallback en runtime sin dependencias. **[I]**

**Hidden classes (JEP 371, JDK 15).** Se definen con `Lookup.defineHiddenClass`. No se pueden descubrir por nombre, se pueden descargar y pueden unirse al nest de la clase del lookup para acceder a miembros privados. La JEP menciona `java.lang.reflect.Proxy` como posible usuario. **[V]** ([JEP 371](https://openjdk.org/jeps/371)). Las hidden classes son un *contenedor* para código generado en runtime, no un generador, y arrastran los mismos límites de native-image.

| Criterio | Valoración |
|---|---|
| Overhead por llamada | **Excelente** (comparable al código fuente generado). **[V]** |
| Arranque | **Malo** (unos 200 ms). **[V]** |
| native-image | **Malo** salvo que la generación pase a hacerse en build. **[V]** |
| Depurabilidad | Pobre. **[I]** |
| Complejidad de build | Ninguna en runtime; una dependencia de 9 MB. |
| Limitaciones | Las mismas reglas de subclase que APT (nada final ni privado), más el acceso entre módulos. |

### 2.4 Java agent / weaving en carga, y weaving en compilación

**Java agent (agente ByteBuddy, AspectJ LTW).**
- Un agente reescribe las clases del usuario al cargarlas. *Puede* instrumentar clases final y métodos privados, e incluso JARs de terceros. **[I]**
- El usuario tiene que añadir `-javaagent:...` a cada JVM: en producción, en tests, en el IDE y en los contenedores. **[I]**
- Desde **JDK 21 (JEP 451)**, cargar un agente en una JVM ya en marcha (self-attach) imprime un aviso, y una versión futura lo deshabilitará por defecto (habrá que activarlo con `-XX:+EnableDynamicAgentLoading`). Los agentes cargados al arrancar no se ven afectados. **[V]** ([JEP 451](https://openjdk.org/jeps/451)). Así que el truco del self-attach que usan algunas librerías para evitar el flag tiene los días contados.
- **native-image:** no hay carga de clases en runtime, así que no aplica. **[I]**
- Por llamada es bueno, porque el advice puede inlinearse en el método. El arranque es malo: el weaver escanea y transforma las clases a medida que se cargan. **[I]**

**Weaving en compilación (AspectJ `ajc`, plugin de Gradle de ByteBuddy).**
- El número de versión de AspectJ sigue a la versión de Java que soporta: 1.9.22 para Java 22, 1.9.23 para Java 23. **[V]** ([notas 1.9.23](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.23.html), [notas 1.9.22](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.22.html)). Desde 1.9.21, `ajc` necesita **JDK 17+ para ejecutarse**. **[V]** ([notas 1.9.21](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.21.html)). Por tanto, cada JDK nuevo puede bloquear a los usuarios hasta que AspectJ se ponga al día. **[I]**
- En Gradle pasa por un plugin de terceros (`io.freefair.aspectj` / `...post-compile-weaving`), cuya documentación los usuarios describen como escasa. **[V]** ([foro de Gradle](https://discuss.gradle.org/t/how-to-use-the-io-freefair-aspectj-post-compile-weaving/27954))
- Sustituye o post-procesa la salida de `javac`, lo que interactúa mal con la compilación incremental, Lombok, otros procesadores y los builds del IDE. **[I]**
- El advice `around` de AspectJ puede asignar objetos closure salvo que el weaver consiga inlinear el advice. Es una fuente adicional de coste por llamada. **[I]**
- Es compatible con native-image, porque todo ocurre en build. **[I]**

Las anotaciones de Resilience4j dependen de Spring AOP o AspectJ justamente por esto: sin framework ni weaver no hay nada que las aplique. **[V]** ([Baeldung](https://www.baeldung.com/spring-boot-resilience4j), [guía de oneuptime](https://oneuptime.com/blog/post/2026-01-25-circuit-breakers-resilience4j-spring/markdown))

**Veredicto:** descartado para el core. Son herramientas potentes, pero imponen la mayor ceremonia de build y runtime a los usuarios de una librería que se supone ligera.

### 2.5 Híbridos

| Híbrido | Descripción | Valoración |
|---|---|---|
| **A. APT + core funcional + búsqueda por convención** *(recomendado)* | El procesador genera `Foo$Resilient` (subclase o delegado). `Resilient.of(...)` lo localiza por nombre, una vez. El usuario también puede llamar directamente a la API funcional/programática (`breaker.call(() -> ...)`) para código que no puede anotar. | La ruta más rápida por defecto y sin magia en runtime. La API funcional cubre el código de terceros. |
| **B. A + módulo opcional `dynamic`** | Si no se encuentra la clase generada, un módulo *opt-in* recurre a `Proxy` para interfaces. Sin ese módulo, `Resilient.of` falla rápido con un mensaje como "¿has añadido el annotation processor?". | Útil para prototipos y tests, mientras el core sigue sin reflexión. El fallback nunca debe elegirse en silencio, o el usuario pagaría 10× de overhead sin saberlo. |
| **C. APT genera solo metadatos + un motor en runtime los lee** | El procesador emite descriptores por método (nombres de config, tipo de retorno, handles de fallback) y un interceptor genérico en runtime los ejecuta. | Elimina el escaneo de anotaciones, pero recupera una ruta de despacho compartida y megamórfica. Medible como más lento (nuestro resultado `aptLambda` en poly4). No recomendado. |
| **D. APT + plugin ByteBuddy en build** | Para usuarios que necesiten decorar in situ clases final o métodos privados. | Posible más adelante como complemento. Demasiado pesado como opción por defecto. |
| **(Descartado) reescritura del AST de javac al estilo Lombok** | Modificar el cuerpo del método anotado dentro de `javac`. | Usa internals del compilador no soportados, se rompe al actualizar el JDK y viola las reglas de procesamiento incremental de Gradle (nada de `Trees`). **[V para la regla de Gradle, I en lo demás]** |

---

## 3. Mediciones propias (JMH)

No encontramos ninguna comparación JMH pública, reciente y equivalente de estos mecanismos. Las que más se citan tienen una década o no son JMH ([neoremind/dynamic-proxy](https://github.com/neoremind/dynamic-proxy), mediciones de una sola ejecución en Java 6/7/8). Así que escribimos un harness JMH **desechable**. Es andamiaje de investigación, no código de producción, y se guarda fuera del repo; el código clave está en el [Apéndice A](#apéndice-a-harness-del-benchmark-condensado).

**Configuración [V]:** JMH 1.37, OpenJDK 25.0.3 (build de Microsoft), Apple M1 (8 núcleos), macOS 14. Tiempo medio, 2 forks × 5 × 1 s de medición tras 3 × 1 s de calentamiento, `-prof gc`. El argumento es `100_000`, fuera de la caché de `Integer`, para que el boxing sea real. La "política" es un guard mínimo (una lectura volatile a la entrada y un contador en caso de error), así que **solo se mide el coste de despacho**, no la lógica del circuit breaker.

- **mono**: un único tipo de servicio a través del mecanismo.
- **poly4**: cuatro implementaciones de servicio distintas decoradas con el mismo mecanismo y llamadas seguidas. Se parece más a una aplicación real con muchos servicios decorados. La cifra es el tiempo de **las cuatro llamadas**.

| Variante | mono ns/op | mono B/op | poly4 ns/op (4 llamadas) | poly4 B/op |
|---|---:|---:|---:|---:|
| Llamada directa (referencia) | 1,18 | 0 | 2,38 | 0 |
| **APT, envoltorio delegador, protocolo inlineado** | 2,59 | 0 | 7,29 | 0 |
| **APT, subclase, protocolo inlineado** | 2,36 | 0 | 6,95 | 0 |
| Envoltorio APT que llama a un `execute(Supplier)` compartido (lambda) | 2,47 | 0 | **14,23** | 0 |
| Subclase ByteBuddy + `Advice` | 1,99 | 0 | 6,05 | 0 |
| Subclase ByteBuddy + `MethodDelegation` (`@SuperCall`) | 1,99 | 0 | 5,97 | 0 |
| `Proxy` + `MethodHandle` spread cacheado | 25,15 | 56 | 91,53 | 224 |
| `Proxy` + `Method.invoke` | 16,34 | 16 | 37,30 | 64 |

Las barras de error fueron inferiores a ±1,1 ns en todas las filas. Los resultados JSON en bruto se guardaron junto al harness.

| Primera instancia en una JVM nueva (`SingleShotTime`, 10 forks) | µs |
|---|---:|
| Envoltorio generado por APT | **366 ± 11** |
| `Proxy` | 2.437 ± 389 |
| ByteBuddy `Advice` | 201.397 ± 9.364 |
| ByteBuddy `MethodDelegation` | 202.105 ± 26.270 |

**Interpretación:**
1. **Todos los mecanismos que producen bytecode real, ya sea desde código fuente o en runtime, caen en la misma franja**: aproximadamente 1–1,5 ns por llamada por encima de una llamada directa. Entre ellos, el mecanismo importa mucho menos que *lo que hace el código generado*. **[V]**
2. **El despacho compartido basado en lambdas casi duplica el overhead en uso polimórfico** (14,2 frente a 7,3 ns). En el caso mono el JIT lo inlinea todo y el problema no se ve; por eso los microbenchmarks con un solo servicio engañan. **[V]** Es el argumento más sólido para que el código generado llame a un **protocolo por método, inlineado**, en lugar de canalizarlo todo por un `execute(Supplier)` genérico. También apunta a dónde podría estar perdiendo tiempo el estilo de decoradores de Resilience4j bajo un polimorfismo realista (a confirmar por el agente de competencia). **[I]**
3. La pequeña ventaja de ByteBuddy sobre APT se debe muy probablemente al **guard `static final`** de esas variantes: el JIT lo trata como constante, mientras que las variantes APT leen un campo de instancia. El código generado podría obtener el mismo beneficio allí donde una política pueda ligarse a un campo `static final`, por ejemplo con config fijada en compilación, pero los registros por instancia no lo permiten. Vale la pena explorarlo en la fase 2. **[I]**
4. **La subclase gana ligeramente a la delegación** (2,36 frente a 2,59; 6,95 frente a 7,29), porque una llamada a `super` se enlaza estáticamente y una llamada al delegado es una llamada de interfaz. **[V]**
5. En este benchmark diminuto, el escape analysis eliminó el `Callable` de `@SuperCall` y el boxing de las variantes ByteBuddy y lambda. Con `Proxy` *no* lo consiguió. Con cuerpos de método más grandes, o si el inlining falla, las variantes con lambda y `Callable` también pueden empezar a asignar memoria. El código generado que nunca las crea no depende de esa suerte. **[V para la observación, I para la generalización]**
6. Salvedades: es una sola máquina, con un guard trivial, en un solo hilo, y el nivel de polimorfismo es 4 (una aplicación real puede tener más). La contención y los detalles internos de las políticas son tema del agente de concurrencia. La fase 2 debería repetir esto en x86 y con implementaciones reales de las políticas.

---

## 4. Matriz comparativa

| | Generación APT | `Proxy` | ByteBuddy en runtime | Agent / LTW | AspectJ CTW |
|---|---|---|---|---|---|
| Overhead por llamada | ★★★★★ | ★★ | ★★★★★ | ★★★★ | ★★★★ (I) |
| Asignación por llamada | ninguna | 16–64 B | ninguna* | ninguna* (I) | posibles closures (I) |
| Arranque | ★★★★★ (0,4 ms) | ★★★★ (2,4 ms) | ★ (200 ms) | ★ (I) | ★★★★★ |
| native-image | ★★★★★ | ★★★ (config) | ★ | ✗ | ★★★★ |
| Depurabilidad | ★★★★★ (código real) | ★★ | ★★ | ★★ | ★★★ |
| Complejidad de build para el usuario | 1 línea de dependencia | ninguna | ninguna (+ jar de 9 MB) | flag de JVM en todas partes | plugin de compilador, retraso con cada JDK |
| Clases final / métodos privados | ✗ (subclase) / OK (delegado sobre interfaz) | solo interfaces | ✗ | ✓ | ✓ |
| Clases de terceros | vía interfaz + delegado | interfaces | ✗ | ✓ | ✓ (weaving binario) |
| Validación en compilación | ✓ | ✗ | ✗ | ✗ | parcial |
| Sync / CF / hilos virtuales | especialización estática | comprobaciones en runtime | comprobaciones en runtime | comprobaciones en runtime | comprobaciones en runtime (I) |

\*En nuestro benchmark, gracias al escape analysis.

---

## 5. Recomendación priorizada

1. **Generación de código fuente con APT (híbrido A)**: el mecanismo por defecto y único en `core`, más un módulo de procesador aparte. Para interfaces, generar envoltorios delegadores; para clases no final, generar subclases. El código generado llama a un **protocolo de política por método y sin asignaciones**, no a un ejecutor lambda compartido. El procesador es *isolating*, y las instancias se localizan por convención de nombres mediante `Resilient.of(...)`.
2. **API funcional / programática** en `core` (no es un mecanismo de anotaciones, sino la vía de escape para código que no se puede anotar, como clientes de terceros o lambdas).
3. **Módulo opcional de fallback con `Proxy` (híbrido B)**: solo opt-in y solo interfaces, con un aviso en el log que indique el overhead.
4. **Plugin ByteBuddy en build (híbrido D)**: posible más adelante para decorar in situ clases final o métodos privados.
5. **ByteBuddy en runtime**: no recomendado (arranque, tamaño, native-image).
6. **AspectJ CTW / Java agent / LTW**: no recomendado (ceremonia para el usuario, retraso frente al JDK, dirección de JEP 451).

**Implicaciones de módulos propuestas (para el agente de arquitectura de módulos).** Siguen la regla de nombres de una sola palabra: `core` (políticas, registro, protocolo, fachada `Resilient`), `processor` (APT, solo en compilación), `annotations` (se podría fusionar con `core`; pregunta abierta), `dynamic` (fallback opcional con Proxy), `benchmarks` (JMH).

---

## 6. Boceto: cómo se vería el código del usuario

> Solo ilustrativo. Los nombres de anotaciones, de atributos y la precedencia de configuración son cosa del agente de diseño de API/configuración. Este boceto solo muestra cómo se siente el **mecanismo**.

**Build (Gradle Kotlin DSL):**

```kotlin
dependencies {
    implementation("io.github.resilience4cata:core:0.1.0")
    annotationProcessor("io.github.resilience4cata:processor:0.1.0")
}
```

**Código del usuario:**

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

**Configuración y obtención de la instancia:**

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

**Para una clase concreta (no final), el estilo subclase:**

```java
public class InventoryService {
    @Retry("inventory")
    public int reserve(String sku, int qty) { ... }
}

InventoryService svc = Resilient.create(InventoryService.class, registry); // calls the generated subclass constructor
```

**Lo que generaría el procesador (solo la forma):**

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

**Cómo se referencian las configuraciones con nombre:** por un nombre de tipo string en la anotación (`"payments"`). El procesador valida la sintaxis y, opcionalmente, puede comprobar los nombres contra una lista conocida en compilación, como una clase de constantes `@ResilienceConfigs`. Los nombres se **resuelven contra el registro una sola vez, cuando se ejecuta `Resilient.of`**, y se guardan en campos `final`. Los atributos de la anotación como `maxAttempts = 5` son constantes de compilación que quedan fijadas en el código generado como overrides. Un nombre inexistente falla al envolver, no en la primera llamada.

---

## 7. Preguntas abiertas para la fase 2

1. ¿Interceptamos la **autoinvocación** en el estilo subclase, como hace Micronaut por defecto? Es más potente, pero sorprende a quien viene de Spring/Resilience4j.
2. ¿Los fallbacks deben vivir en la interfaz (métodos `default`) o en una clase `@FallbackFor` aparte?
3. ¿Pueden las políticas ser `static final` cuando la config se conoce entera en compilación? El punto 3 de [§3](#3-mediciones-propias-jmh) sugiere una ganancia medible.
4. ¿Cómo soportamos configuración recargable en caliente sin una lectura volatile en cada llamada? (Coordinar con el agente de concurrencia.)
5. ¿Cómo añadimos soporte para otros tipos de retorno: tipos reactivos, `Future`, tipos del usuario? Lo ideal sería una SPI que el procesador resuelva **en compilación**.
6. ¿Deben ser `annotations` y `core` módulos separados? Quien use el procesador necesita las anotaciones en el classpath de compilación, y el código generado necesita core en runtime.

---

## Apéndice A: harness del benchmark (condensado)

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

## Fuentes

- Gradle: [Procesamiento incremental de anotaciones](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing)
- OpenJDK: [JEP 416, Reimplement Core Reflection with Method Handles](https://openjdk.org/jeps/416) · [JEP 371, Hidden Classes](https://openjdk.org/jeps/371) · [JEP 451, Prepare to Disallow the Dynamic Loading of Agents](https://openjdk.org/jeps/451) · JEP 484 Class-File API: [correo de targeting](https://mail.openjdk.org/pipermail/jdk-dev/2024-October/009461.html), [commit de implementación](https://gitea.hb.dhbw-stuttgart.de/stan/jdk-24/commit/84ffb64cd73f8af11cf3670c6f19d282c2ac6961), [Inside Java Newscast #81](https://nipafx.dev/inside-java-newscast-81/)
- Cambio por defecto del procesamiento de anotaciones en JDK 23: [Notas de la versión](https://www.oracle.com/java/technologies/javase/23-relnote-issues.html) · [Revisión de JDK-8321314](https://mail.openjdk.org/pipermail/compiler-dev/2024-May/026675.html) · [Hilo de la propuesta](https://mail.openjdk.org/pipermail/jdk-dev/2024-May/009028.html)
- GraalVM: [Dynamic Proxy en Native Image](https://www.graalvm.org/jdk17/reference-manual/native-image/dynamic-features/DynamicProxy/) · [Metadatos de alcanzabilidad](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
- Avaje Inject: [Aspectos](https://avaje.io/inject/#aspects)
- Micronaut: [javadoc de `@Around`](https://docs.micronaut.io/4.8.2/api/io/micronaut/aop/Around.html) · [Guía AOP](https://docs.micronaut.io/latest/guide/#aop)
- Byte Buddy: [README / tabla de versiones](https://github.com/raphw/byte-buddy) · [Plugin de Gradle](https://plugins.gradle.org/plugin/net.bytebuddy.byte-buddy-gradle-plugin)
- AspectJ: [1.9.23](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.23.html) · [1.9.22](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.22.html) · [1.9.21](https://eclipse.dev/aspectj/doc/latest/release/README-1.9.21.html) · [hilo del foro sobre el plugin freefair](https://discuss.gradle.org/t/how-to-use-the-io-freefair-aspectj-post-compile-weaving/27954)
- JavaPoet: [palantir/javapoet](https://github.com/palantir/javapoet) · [Deprecación de Square JavaPoet (mirror)](https://docsearch.algolia.com/mcp/docs/repo/square/javapoet)
- Benchmarks de reflexión / MethodHandle / LambdaMetafactory: [Timefold: Java reflection, but much faster](https://timefold.ai/blog/java-reflection-but-much-faster) · [mlvm-dev: LambdaMetafactory vs MethodHandle](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006826.html) · [continuación](https://mail.openjdk.org/pipermail/mlvm-dev/2018-February/006827.html) · [neoremind/dynamic-proxy (antiguo, sin JMH)](https://github.com/neoremind/dynamic-proxy)
- Configuración de anotaciones de Resilience4j (dependencia de Spring AOP): [Baeldung](https://www.baeldung.com/spring-boot-resilience4j) · [guía de oneuptime](https://oneuptime.com/blog/post/2026-01-25-circuit-breakers-resilience4j-spring/markdown) · [repositorio de resilience4j](https://github.com/resilience4j/resilience4j)
