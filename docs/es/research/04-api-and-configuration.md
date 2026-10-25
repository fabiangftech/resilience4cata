# 04 — Conjunto de funcionalidades, API de anotaciones y modelo de configuración

> **Histórico.** Esta investigación es anterior a la implementación y se conserva tal como se escribió; para lo que hace realmente 0.1.0, ver [features](../features.md), [arquitectura](../architecture.md) y [benchmarks](../benchmarks.md).

> Investigación de la fase 1. Estado: **propuesta para discusión**. No hay código de producción; cada fragmento Java
> es un boceto de API.
> Alcance: lo que el usuario ve y escribe. Cómo se aplican las anotaciones (APT vs. proxies vs. bytecode), los detalles
> internos de concurrencia y la organización de módulos se tratan en documentos hermanos; aquí solo se mencionan cuando
> condicionan la API.

## Contenido

1. [Resumen](#1-resumen)
2. [Antecedentes](#2-antecedentes)
3. [Catálogo de funcionalidades y niveles](#3-catálogo-de-funcionalidades-y-niveles)
4. [Diseño de anotaciones](#4-diseño-de-anotaciones)
5. [Orden de ejecución](#5-orden-de-ejecución)
6. [Múltiples configuraciones](#6-múltiples-configuraciones)
7. [Fallbacks y clasificación de excepciones](#7-fallbacks-y-clasificación-de-excepciones)
8. [Asincronía e hilos virtuales](#8-asincronía-e-hilos-virtuales)
9. [Eventos, métricas e inspección de estado](#9-eventos-métricas-e-inspección-de-estado)
10. [Experiencia de desarrollo y pruebas](#10-experiencia-de-desarrollo-y-pruebas)
11. [Preguntas abiertas](#11-preguntas-abiertas)
12. [Fuentes](#12-fuentes)

---

## 1. Resumen

- **Seis políticas en el MVP**: `@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, `@RateLimiter`, `@Fallback`. El
  soporte asíncrono se deduce del tipo de retorno (`CompletionStage`), no de una anotación aparte. **v1** añade
  `@Hedge`, `@AdaptiveLimit`, presupuestos de reintentos, integración con Micrometer/OpenTelemetry, YAML y
  reconfiguración en caliente. **Más adelante**: `@Cached`, tipos reactivos, inyección de fallos e integraciones con
  frameworks.
- **Las anotaciones compuestas son una funcionalidad de primera clase**: un `@ExternalCall` definido por el usuario
  agrupa políticas mediante la meta-anotación `@ResiliencePolicy`. Es la forma principal de tener "múltiples
  configuraciones" en el código.
- **Configuración y estado van por separado.** Una *config* es una plantilla con nombre e inmutable
  (`config = "external"`). Una *instancia* es el objeto con estado (ventana del breaker, permisos del limitador), se
  identifica por `name` y por defecto hay una por método. Resilience4j usa un mismo string para ambas cosas, y eso
  suele confundir.
- **Resolución por capas**, de menor a mayor precedencia: valores por defecto internos → `defaults` → config con
  nombre (que puede hacer `extends` de otra) → capa de perfil → anotación de clase → anotación de método → overrides
  externos por instancia (properties/YAML → variables de entorno → propiedades del sistema) → overrides programáticos.
  Todo se resuelve **una sola vez**, al crear la instancia, en records inmutables. El camino caliente nunca consulta
  la configuración.
- **Fallbacks con tipos seguros**: la forma principal es `@Fallback(UserFallback.class)` con una interfaz genérica
  `FallbackHandler<T>`. Existe una forma por nombre de método por comodidad, y el procesador de anotaciones la verifica en
  compilación.
- **Un solo clasificador para excepciones y resultados**: listas de clases `recordOn`/`ignoreOn`, más un
  `Classifier<T>` opcional que ve tanto el resultado como la excepción.
- **La testabilidad forma parte del diseño**: `Clock`/`Scheduler` inyectables, un `Random` con semilla para el jitter,
  `forceOpen()`, y un módulo `test` con extensión de JUnit 5 y aserciones sobre eventos.
- **Recarga en caliente**: no en el MVP. v1 trae una API explícita `reconfigure(...)` que reemplaza un snapshot
  inmutable (una lectura volatile por llamada), limitada a los parámetros que se pueden cambiar en vivo sin riesgo.

## 2. Antecedentes

| Librería | Estilo de declaración | Fuente de configuración | Fallback | Orden al combinar políticas |
|---|---|---|---|---|
| **Resilience4j** (anotaciones vía Spring AOP / CDI) | `@CircuitBreaker(name, fallbackMethod)`, `@Retry`, `@RateLimiter`, `@Bulkhead(type)`, `@TimeLimiter` | `resilience4j.<policy>.configs.<cfg>.*` + `instances.<name>.baseConfig` | Nombre de método como **string**, resuelto por reflexión según el tipo del parámetro de excepción | Fijo: `Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(fn)))))`, modificable con las propiedades `*AspectOrder` |
| **MicroProfile Fault Tolerance 4.x** | `@Retry`, `@Timeout`, `@CircuitBreaker`, `@Bulkhead`, `@Fallback`, `@Asynchronous` con atributos tipados (`maxRetries`, `delay`+`delayUnit`, `failOn`/`skipOn`, `retryOn`/`abortOn`) | MP Config: `<class>/<method>/<Annotation>/<param>`, `<class>/<Annotation>/<param>`, `<Annotation>/<param>`; `<Annotation>/enabled` | `@Fallback(value = Handler.class)` **o** el string `fallbackMethod` (validado en el despliegue) | Lo fija la especificación: Fallback → Retry → CircuitBreaker → Timeout → Bulkhead (SmallRye añade RateLimit) |
| **SmallRye FT** (implementación de MP FT) | Añade `@RateLimit`, `@ExponentialBackoff`, `@FibonacciBackoff`, `@CustomBackoff`, `@RetryWhen`, `@BeforeRetry` y `@ApplyGuard`/`@ApplyFaultTolerance` (reutilización de un guard con nombre construido por código) | MP Config, más una API programática `Guard` | Igual que MP | Igual que MP |
| **Spring Retry** | `@Retryable(retryFor, noRetryFor, maxAttempts, backoff = @Backoff(...), recover = "...")`, `@CircuitBreaker` | Expresiones SpEL en los atributos (`maxAttemptsExpression`) | `@Recover` emparejado por **firma** (tipo de retorno + excepción + argumentos) | Una política por método |
| **Spring Framework 7** (core) | `@Retryable`, `@ConcurrencyLimit`, `@EnableResilientMethods` | Atributos más placeholders `${…}` | ninguno | n/a |
| **Failsafe** | Sin anotaciones. `Failsafe.with(fallback, retryPolicy, circuitBreaker, timeout).get(...)` | Solo builders | Política `Fallback.of(...)` (tipada) | **El orden de composición es el orden de los argumentos** (exterior → interior) |
| **Polly v8 (.NET)** | `ResiliencePipelineBuilder().AddRetry(...).AddCircuitBreaker(...).AddTimeout(...)` | `ResiliencePipelineRegistry<TKey>`, binding de opciones, **recarga dinámica** mediante `IOptionsMonitor` | Estrategia fallback (tipada, `Outcome<T>`) | Orden de las llamadas `Add*` (la primera es la más exterior); incluye hedging y estrategias de caos |

Lo que tomamos de cada una:

- **MP FT**: atributos tipados (`Class<? extends Throwable>[]`, no expresiones regulares), una clave de configuración
  determinista por clase/método/anotación, un interruptor `enabled`, y que la configuración tenga prioridad sobre las
  anotaciones. Sus puntos débiles son el orden rígido y la falta de configs compartidas con nombre (hasta
  `@ApplyGuard` de SmallRye).
- **Resilience4j**: configs con nombre que heredan con `baseConfig` y comparten instancias entre métodos. Sus puntos
  débiles son los fallbacks por string sin verificación en compilación, que el mismo string sirva de clave de
  configuración y de identidad del estado, y un orden que solo se puede cambiar de forma global.
- **Failsafe / Polly**: el orden se expresa explícitamente en el builder, los fallbacks son tipados y hay un único
  predicado "should handle" que cubre excepciones y resultados (`handleResultIf`, `ShouldHandle`). Polly aporta además
  hedging, registros de pipelines y recarga reemplazando pipelines completos.
- **Spring Retry**: buscar el fallback por firma (`@Recover`) no necesita strings, pero los errores de ambigüedad solo
  aparecen en tiempo de ejecución. Nos quedamos con la idea y llevamos la verificación a la compilación.

## 3. Catálogo de funcionalidades y niveles

| Funcionalidad | Nivel | Notas |
|---|---|---|
| Circuit breaker (ventanas deslizantes por conteo y por tiempo, tasa de llamadas lentas, número de pruebas en half-open) | **MVP** | Mismo modelo que R4j para facilitar la migración. Además `forceOpen`/`disable` para operación y pruebas. |
| Retry (fijo, exponencial, exponencial + jitter full/equal, duración máxima, reintento según resultado) | **MVP** | Jitter activado por defecto ([AWS backoff & jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)). |
| Timeout (síncrono y asíncrono) | **MVP** | En llamadas síncronas interrumpe al llamante; el comportamiento con hilos virtuales queda documentado (§8). |
| Bulkhead (semáforo, máximo concurrente + espera máxima) | **MVP** | Con hilos virtuales, un bulkhead de pool de hilos aporta poco; como mucho, opción en v1. |
| Rate limiter (token bucket/recarga fija, permisos por periodo, espera máxima) | **MVP** | Forma parte del conjunto con el que se compara contra R4j. |
| Fallback (handler tipado / método verificado) | **MVP** | §7. |
| Asincronía: tipos de retorno `CompletionStage`/`CompletableFuture` | **MVP** | Se detecta a partir de la firma del método. |
| Anotaciones compuestas (`@ResiliencePolicy`) | **MVP** | La respuesta principal a "múltiples configuraciones". |
| Configs con nombre, `extends`, perfiles, properties + entorno + propiedades del sistema | **MVP** | §6. |
| API programática con builders (sin anotaciones) | **MVP** | También es la base sobre la que se compilan o delegan las anotaciones. |
| Eventos (tipados, jerarquía sealed), registro de listeners | **MVP** | Sin asignaciones de memoria cuando no hay listeners. |
| Inspección de estado (`registry.snapshot()`) | **MVP** | Records de solo lectura; útiles para endpoints de salud. |
| Pruebas: `Clock`, `Scheduler`, jitter con semilla, estados forzados | **MVP** | §10. |
| Hedging (`@Hedge`: retardo, máximo de intentos paralelos) | **v1** | [The Tail at Scale](https://research.google/pubs/the-tail-at-scale/); hedging de Polly. En v1 no se puede combinar con `@Retry` en el mismo nivel. |
| Límite de concurrencia adaptativo (`@AdaptiveLimit`: AIMD, Vegas, Gradient2) | **v1** | [concurrency-limits](https://github.com/Netflix/concurrency-limits) de Netflix. |
| Presupuesto de reintentos (proporción acotada de reintentos por instancia) | **v1** | Evita tormentas de reintentos ([presupuestos de reintento de Finagle](https://twitter.github.io/finagle/guide/Clients.html#retries)). |
| Integración de métricas: Micrometer, OpenTelemetry | **v1** | Módulos separados; el core solo expone un SPI. |
| Fuente de configuración YAML | **v1** | Módulo aparte, para que el core no dependa de una librería YAML. |
| API de reconfiguración en caliente | **v1** | §6.7. |
| Extensión JUnit 5 + aserciones de eventos estilo AssertJ | **v1** (Clock y forzado de estado en el MVP) | |
| Orden de ejecución configurable | **v1** | El MVP usa el orden por defecto. §5. |
| `@Cached` (caché de resultados con TTL) | **más adelante** | Normalmente Caffeine lo resuelve mejor; solo vale la pena si puede reutilizar la infraestructura de claves e instancias. |
| Tipos reactivos (Reactor/Mutiny), corrutinas de Kotlin | **más adelante** | Módulos adaptadores. |
| Inyección de fallos / caos (latencia/excepciones `@Inject…`, como Simmy de Polly) | **más adelante** | Muy útil en pruebas y en staging. |
| Propagación de deadline / presupuesto de tiempo entre llamadas anidadas | **más adelante** | Se apoya en `ScopedValue` (preview en 21, definitivo en [JDK 25, JEP 506](https://openjdk.org/jeps/506)). |
| Integraciones con Spring/Quarkus/Micronaut | **más adelante** | Fuera de alcance por decisión. |

## 4. Diseño de anotaciones

### 4.1 Principios

1. **Atributos tipados siempre que Java lo permita.** Las listas de excepciones son `Class<? extends Throwable>[]`,
   los enums sustituyen a los flags en string, y los fallbacks son clases.
2. **Cada atributo tiene un centinela de "no definido"** (`-1`, `""`, array vacío, `Inherit.class`). Una anotación no
   puede distinguir entre "no escrito" y "escrito con el valor por defecto", así que un valor por defecto real en el
   atributo pisaría sin avisar a las configs con nombre y rompería la herencia. Los valores por defecto efectivos
   viven en la capa de configuración (§6), no en la anotación.
3. **Las duraciones son strings** con una única gramática estricta (`"250ms"`, `"2s"`, `"1m"`, `"PT2S"`), porque: las
   anotaciones no pueden contener un `Duration`; los pares `valor + unidad` (estilo MP) duplican el número de
   atributos; y la misma gramática se usa en los ficheros de properties. El procesador de anotaciones rechaza los
   literales inválidos en compilación (§10.1).
4. **Se pueden usar en métodos y en tipos.** Una anotación a nivel de tipo se aplica a todos los métodos públicos, y
   una a nivel de método la sobrescribe *atributo por atributo* (no entera).
5. **`config` y `name` significan cosas distintas**:
   - `config` indica de qué plantilla con nombre se parte.
   - `name` indica la *instancia con estado*. Si está vacío, el id de la instancia es
     `<fqcn>#<method>(<erased param types>)`. Dos métodos con el mismo `name` comparten un breaker o limitador. Ambos
     métodos deben resolver exactamente la misma configuración; si no, el arranque falla.
6. **Todas las políticas admiten `enabled`**, que se puede sobrescribir desde fuera (como `<Annotation>/enabled` en
   MP).

### 4.2 Anotaciones propuestas (boceto)

```java
package io.resilience4cata.annotation;   // package name provisional

@Retention(RUNTIME) @Target({METHOD, TYPE, ANNOTATION_TYPE}) @Documented
public @interface CircuitBreaker {
    String name() default "";                  // instance id; "" = per method
    String config() default "";                // named template; "" = "default"
    WindowType windowType() default WindowType.INHERIT;   // COUNT, TIME
    int windowSize() default -1;               // calls or seconds
    int minimumCalls() default -1;
    float failureRateThreshold() default -1f;  // percent, 0 < x <= 100
    float slowCallRateThreshold() default -1f;
    String slowCallDuration() default "";
    String waitInOpen() default "";            // e.g. "30s"
    int halfOpenCalls() default -1;
    Class<? extends Throwable>[] recordOn() default {};
    Class<? extends Throwable>[] ignoreOn() default {};
    Class<? extends Classifier> classifier() default Classifier.Inherit.class;
    String enabled() default "";               // "", "true", "false"  (tri-state)
}

@Retention(RUNTIME) @Target({METHOD, TYPE, ANNOTATION_TYPE}) @Documented
public @interface Retry {
    String name() default "";
    String config() default "";
    int maxAttempts() default -1;              // total attempts, including the first
    String delay() default "";                 // initial delay
    Backoff backoff() default Backoff.INHERIT; // FIXED, EXPONENTIAL
    double multiplier() default -1;
    String maxDelay() default "";
    Jitter jitter() default Jitter.INHERIT;    // NONE, FULL, EQUAL
    String maxDuration() default "";           // overall budget across attempts
    Class<? extends Throwable>[] retryOn() default {};
    Class<? extends Throwable>[] abortOn() default {};
    Class<? extends Classifier> classifier() default Classifier.Inherit.class;
    String enabled() default "";
}

public @interface Timeout {            // @Target/@Retention as above
    String value() default "";         // "2s"; empty = from config
    String config() default "";
    String enabled() default "";
}

public @interface Bulkhead {
    String name() default "";
    String config() default "";
    int maxConcurrent() default -1;
    String maxWait() default "";        // "0" = fail fast
    String enabled() default "";
}

public @interface RateLimiter {
    String name() default "";
    String config() default "";
    int permits() default -1;
    String period() default "";         // e.g. "1s"
    String maxWait() default "";
    String enabled() default "";
}

public @interface Fallback {
    Class<? extends io.resilience4cata.FallbackHandler<?>> value() default Inherit.class; // typed handler
    String method() default "";                    // alternative: sibling method, compile-time checked
    Class<? extends Throwable>[] applyOn() default {Throwable.class};
    Class<? extends Throwable>[] skipOn() default {};
}

// v1
public @interface Hedge { String name() default ""; String config() default "";
                          String delay() default ""; int maxHedged() default -1; }
public @interface AdaptiveLimit { String name() default ""; String config() default "";
                                  LimitAlgorithm algorithm() default LimitAlgorithm.INHERIT; /* AIMD, VEGAS, GRADIENT2 */ }
```

Dos tipos de anotación se dejan fuera **a propósito**:

- `@Asynchronous` (MP): el tipo de retorno ya dice si una llamada es asíncrona. Un marcador aparte que tiene que
  coincidir con el tipo de retorno es redundante y fácil de equivocar. Para ejecutar un método *síncrono* en otro
  executor se usa `@Timeout` con `offload = true` (pregunta abierta §11) o la API programática.
- `@Bulkhead(type = THREADPOOL)` (R4j): con hilos virtuales, el aislamiento por semáforo es la opción por defecto
  adecuada.

### 4.3 Anotaciones compuestas ("meta-anotaciones")

El usuario debería poder dar nombre a un conjunto de políticas una vez y reutilizarlo en todas partes:

```java
@ResiliencePolicy                              // marks a composed annotation; required
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
@Bulkhead(maxConcurrent = 50)
public @interface ExternalCall {}

@ResiliencePolicy
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Retry(maxAttempts = 1)                         // explicit: no retries for writes
@CircuitBreaker(config = "external")
@Timeout("5s")
public @interface NonIdempotentCall {}

@ExternalCall
public class PaymentsClient {
    public Receipt charge(Order o) { ... }                    // @ExternalCall applies

    @NonIdempotentCall                                         // overrides the class-level bundle
    public Receipt refund(Order o) { ... }

    @Timeout("500ms")                                          // overrides one attribute only
    public Status status(String id) { ... }
}
```

Reglas:

- **Profundidad de meta-anotación**: ilimitada, con detección de ciclos. Una anotación compuesta puede estar a su vez
  anotada con otra anotación compuesta.
- **Semántica de fusión**: funciona como la sobrescritura de atributos de Spring, pero más simple. La fusión se hace
  por tipo de política y luego atributo por atributo. La anotación directa gana a la compuesta. El nivel de método
  gana al de clase. Un atributo no definido (centinela) nunca sobrescribe nada.
- **Conflictos**: si dos anotaciones compuestas sobre el mismo elemento dan valores distintos al mismo atributo de una
  política, es un **error de compilación** (APT) o un error de arranque (modo solo runtime). Un comportamiento
  silencioso de "gana la última" no es aceptable.
- Las anotaciones compuestas tienen su propia clave de configuración (`ExternalCall/Timeout/value`), para que los
  operadores puedan ajustar toda la familia sin tocar cada clase.

### 4.4 Interfaces y herencia

- Una anotación en un método de interfaz se aplica a las implementaciones; la de la implementación tiene prioridad.
- `@Inherited` solo se respeta en anotaciones de clase (semántica de Java). La herencia a través de métodos
  sobrescritos se implementa de forma explícita y queda documentada.
- Métodos privados, estáticos y finales: que se puedan soportar o no depende del mecanismo de aplicación (ver la
  investigación de mecanismos). La API debe informar de las ubicaciones no soportadas como **errores**. No hay que
  repetir el comportamiento silencioso de Spring AOP, donde la auto-invocación no pasa por el aspecto.

## 5. Orden de ejecución

### 5.1 Por defecto

De la capa más exterior a la más interior:

```
Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method
```

Razonamiento:

- **Fallback lo más exterior**: ve el resultado final después de que hayan actuado las demás políticas, incluido
  `CallNotPermitted`.
- **Retry por fuera del CircuitBreaker**: el breaker registra cada intento, y un breaker abierto corta los reintentos
  de inmediato (`abortOn` incluye `CallNotPermittedException` por defecto).
- **RateLimiter por dentro del breaker**: las llamadas rechazadas no consumen permisos.
- **Timeout por dentro de Retry**: el timeout se aplica a cada intento. `Retry.maxDuration` cubre la operación
  completa.
- **Bulkhead lo más interior**: el permiso solo se retiene mientras la llamada se ejecuta de verdad, no durante las
  esperas del backoff.

Coincide con MP FT y es compatible con el orden por defecto de R4j (R4j no tiene Fallback como capa).

### 5.2 Orden configurable (v1)

Quien necesite otro orden (por ejemplo, un timeout alrededor de *todo* el bucle de reintentos) puede declararlo
explícitamente. El orden se valida y se guarda por instancia; nunca se decide en cada llamada:

```java
@ResiliencePolicy
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Order({Layer.FALLBACK, Layer.TIMEOUT, Layer.RETRY, Layer.CIRCUIT_BREAKER})  // omitted layers keep their relative default order
@Retry(config = "external") @CircuitBreaker(config = "external") @Timeout("10s")
public @interface WholeOperationDeadline {}
```

Orden global por defecto: `resilience4cata.order=fallback,retry,circuit-breaker,...`. En lugar de un segundo
`@Timeout`, el timeout *por intento* y el de *la operación completa* deberían ser dos atributos distintos:
`@Timeout("500ms")` es por intento y `@Retry(maxDuration = "3s")` es el total. Eso cubre alrededor del 90 % de las
necesidades reales sin `@Order`.

Cualquier orden que cambie la semántica de forma sorprendente (Bulkhead por fuera de Retry, Fallback que no sea la
capa exterior) genera una **advertencia** en el arranque que explica la consecuencia.

## 6. Múltiples configuraciones

### 6.1 Conceptos

| Concepto | Qué es | ¿Mutable? |
|---|---|---|
| **Defaults** | Valores internos de cada política, sobrescribibles bajo `defaults` | No (tras el build) |
| **Config con nombre** | Una plantilla, p. ej. `external`, `internal`, `batch`. Puede hacer `extends` de otra config con nombre | No |
| **Perfil** | Una capa que se activa entera (`prod`, `test`, `eu-west`). Sobrescribe claves de los defaults y de las configs con nombre | No |
| **Instancia** | Objeto con estado (estado del breaker, ventana, permisos) con un id | El estado sí; la config solo con `reconfigure` |
| **Política resuelta** | Record inmutable con todos los valores finales, calculado una vez por instancia | No |

### 6.2 Precedencia (de menor → mayor)

```
 1. built-in defaults
 2. defaults.<policy>.*                       (file/env/…)
 3. configs.<name>.<policy>.*                 (walks the `extends` chain, root first)
 4. profiles.<active>.…                       (applies over 2 and 3, in profile activation order)
 5. composed-annotation attributes            (@ExternalCall)
 6. class-level annotation attributes
 7. method-level annotation attributes
 8. instances.<id>.<policy>.*                 external per-instance override
 9. programmatic override (builder)           highest; for tests and explicit code
```

Dentro de una misma capa externa, la prioridad entre fuentes es la de MP Config: **propiedades del sistema >
variables de entorno > ficheros de la aplicación > valores por defecto del classpath**. Cada fuente se conecta
mediante un SPI pequeño, `ConfigSource`.

La configuración externa (8) tiene prioridad sobre las anotaciones (5–7), como en MP FT. Las anotaciones son los
valores por defecto del desarrollador, y los operadores tienen que poder cambiarlos sin recompilar. Quien quiera un
valor que no se pueda sobrescribir marca la instancia con `locked: true`, o llama a `.locked()` por código, y los
intentos de sobrescribirla se registran en el log.

### 6.3 Formato externo

Properties (core, sin dependencias):

```properties
resilience4cata.profiles.active=prod

resilience4cata.defaults.retry.max-attempts=3
resilience4cata.defaults.retry.jitter=full

resilience4cata.configs.external.circuit-breaker.failure-rate-threshold=50
resilience4cata.configs.external.circuit-breaker.wait-in-open=30s
resilience4cata.configs.external.retry.delay=100ms

resilience4cata.configs.slow-external.extends=external
resilience4cata.configs.slow-external.timeout.value=10s

resilience4cata.profiles.test.configs.external.circuit-breaker.wait-in-open=10ms

# per instance, by explicit name …
resilience4cata.instances.payments.circuit-breaker.minimum-calls=20
# … or by derived id, MP-compatible spelling also accepted
com.acme.PaymentsClient/charge/Timeout/value=3s
```

YAML (módulo `yaml`, v1) es el mismo árbol:

```yaml
resilience4cata:
  defaults:
    retry: { max-attempts: 3, jitter: full }
  configs:
    external:
      circuit-breaker: { failure-rate-threshold: 50, wait-in-open: 30s }
      retry: { delay: 100ms }
    slow-external:
      extends: external
      timeout: { value: 10s }
  profiles:
    test:
      configs:
        external:
          circuit-breaker: { wait-in-open: 10ms }
```

**Las variables de entorno** siguen el mapeo de MP Config: mayúsculas, y cada carácter no alfanumérico pasa a ser `_`,
así que queda `RESILIENCE4CATA_CONFIGS_EXTERNAL_RETRY_DELAY=200ms`. Este mapeo no se puede invertir cuando el nombre
de una config contiene `-` o `_`. De ahí salen dos reglas: los nombres se limitan a `[a-z0-9]+(-[a-z0-9]+)*`, y la
fuente de entorno empareja normalizando las claves *conocidas* en lugar de interpretar los nombres de las variables.

### 6.4 API programática

La configuración por código tiene la misma potencia que los ficheros y las anotaciones. Las anotaciones son azúcar
sintáctico sobre este modelo.

```java
Resilience resilience = Resilience.builder()
    .source(ConfigSources.classpath("resilience4cata.properties"))
    .source(ConfigSources.environment())
    .source(ConfigSources.systemProperties())
    .profiles("prod")
    .defaults(d -> d.retry(r -> r.maxAttempts(3).jitter(Jitter.FULL)))
    .config("external", c -> c
        .circuitBreaker(cb -> cb.failureRateThreshold(50).waitInOpen(Duration.ofSeconds(30)))
        .retry(r -> r.delay(Duration.ofMillis(100)).retryOn(IOException.class)))
    .config("slow-external", c -> c.extendsConfig("external").timeout(Duration.ofSeconds(10)))
    .clock(Clock.system())                        // replaceable in tests
    .listener(event -> log.debug("{}", event))
    .strict(true)                                 // fail on unknown keys/names (default true)
    .build();                                     // all validation happens here

// Without annotations: an explicit, reusable, immutable pipeline
Pipeline<Receipt> charge = resilience.pipeline("payments", Receipt.class)
    .config("external")
    .fallback((Throwable t) -> Receipt.pending())
    .build();
Receipt r = charge.execute(() -> client.charge(order));

// With annotations: the woven/generated object comes from the same Resilience instance
PaymentsClient client = resilience.wrap(PaymentsClient.class, new PaymentsClient());  // mechanism TBD (doc 02)
```

### 6.5 Validación: cuándo

| Verificación | Compilación (APT, si se usa) | `build()` / arranque | Ejecución |
|---|---|---|---|
| Rangos de atributos, sintaxis de duraciones, solapamiento `retryOn ∩ abortOn` | ✔ | ✔ (en modo solo runtime) | |
| Compatibilidad de la firma del fallback | ✔ | ✔ | |
| Conflictos entre anotaciones compuestas, ubicación ilegal (private/final) | ✔ | ✔ | |
| Que exista el nombre de `config` referenciado | ✘ (las configs son externas) | ✔ | |
| Claves desconocidas en ficheros (erratas), con "¿quisiste decir `wait-in-open`?" | | ✔ | |
| Ciclos de `extends`, `name` compartido con configs distintas | | ✔ | |
| Perfil inexistente | | ✔ (advertencia) | |

Regla: **una vez que `build()` retorna, ningún error de configuración puede aparecer durante una llamada.** Las
instancias se pueden crear de forma perezosa (en la primera llamada) para arrancar más rápido. En ese caso la
configuración del método ya se ha validado de antemano durante `build()`, recorriendo los tipos anotados registrados
(el índice generado por el APT los enumera).

### 6.6 Mantener limpio el camino caliente

- La resolución produce `record ResolvedRetry(int maxAttempts, long delayNanos, ...)` y records similares: primitivos,
  campos finales, duraciones ya convertidas a nanos y conjuntos de excepciones precompilados en un clasificador
  (§7.3).
- Cada punto de llamada instrumentado guarda una referencia directa a su `Pipeline` (un campo static final en el
  código generado, o un handle cacheado por método en modo proxy). **No hay búsquedas en mapas por nombre en cada
  llamada.**
- Las búsquedas por nombre (`resilience.circuitBreaker("payments")`) son para administración y pruebas, no para las
  invocaciones.

### 6.7 Recarga en caliente: ¿compensa el coste?

**No en el MVP. En v1, como API explícita y no como vigilante de ficheros.**

- *Coste*: una lectura `volatile`/acquire de un snapshot de configuración por política y por llamada. En x86 es
  barata y en ARM algo más cara. También impide parte del plegado de constantes del JIT, que importa cuando los
  parámetros de la política se tratan como constantes. Las instancias que nunca se reconfiguran no deberían pagar
  **nada**. Hay dos formas posibles de conseguirlo: un flag `reloadable` que elige otra implementación en el build, o
  invalidar con `MutableCallSite`/`SwitchPoint`, que mantiene el camino rápido con constantes plegadas. La
  investigación de concurrencia debería medir estas opciones con benchmarks.
- *Semántica*: algunos parámetros se pueden cambiar sin riesgo (umbrales, retardos, intentos máximos, permisos,
  timeout). Otros cambian la forma del estado (tipo o tamaño de ventana, tamaño del bulkhead con permisos en uso).
  Estos últimos requieren un reinicio de estado documentado o se rechazan.
- *API*:

```java
resilience.reconfigure("payments", c -> c.circuitBreaker(cb -> cb.failureRateThreshold(30)));
// or: resilience.reload()  -> re-reads all sources, validates everything, then swaps atomically, all-or-nothing
```

Vigilar ficheros o configuración remota queda en manos de las integraciones, que llaman a `reload()`. En Polly, el
comportamiento equivalente también lo aporta el host (`IOptionsMonitor`), no la librería central.

## 7. Fallbacks y clasificación de excepciones

### 7.1 Handler de fallback tipado (forma principal)

```java
public interface FallbackHandler<T> {
    T apply(FallbackContext ctx) throws Throwable;   // ctx: failure, attempt count, args, method, instance id
}

public final class UserFallback implements FallbackHandler<User> {
    @Override public User apply(FallbackContext ctx) {
        return ctx.failure() instanceof CallNotPermittedException
            ? User.anonymous()
            : User.cached(ctx.argument(0, String.class));
    }
}

public class UserClient {
    @Retry @CircuitBreaker(config = "external")
    @Fallback(UserFallback.class)
    public User find(String id) { ... }
}
```

El procesador de anotaciones verifica que `T` sea asignable al tipo de retorno del método, o a la `T` dentro de
`CompletionStage<T>` en métodos asíncronos. Los handlers se crean una vez por instancia mediante un `HandlerFactory`,
que por defecto usa el constructor sin argumentos y más adelante se podrá conectar a un contenedor de DI.

### 7.2 Fallback por método hermano (forma de conveniencia, verificada en compilación)

```java
@Fallback(method = "findFallback")
public User find(String id) { ... }

private User findFallback(String id, IOException e) { ... }        // most specific match wins
private User findFallback(String id, Throwable t) { ... }
```

Reglas, que siguen a R4j y a `@Recover` de Spring pero más estrictas:

- Los parámetros deben ser los originales, seguidos opcionalmente de **un** subtipo de `Throwable`.
- El tipo de retorno debe ser asignable al original. En métodos asíncronos puede ser `T` o `CompletionStage<T>`.
- Si hay varias sobrecargas, gana el tipo de excepción más específico. Si ninguna encaja, se relanza la excepción
  original.
- **Todas las verificaciones se hacen en el procesador de anotaciones**, y los errores incluyen sugerencias (§10.1).
  En modo solo runtime se hacen en `build()`.
- El string no se puede evitar: las anotaciones de Java no admiten referencias a métodos. Se consideró generar
  constantes desde el metamodelo del APT (`UserClient_.FIND_FALLBACK`) y se descartó, por problemas con el orden de
  las rondas y por errores confusos cuando el procesador no está activo.

### 7.3 Clasificación de excepciones y resultados

Cada política que registra resultados (CircuitBreaker, Retry, Hedge, AdaptiveLimit) tiene tres entradas:

1. **Listas de permitir/denegar**: `recordOn`/`ignoreOn` (CB), `retryOn`/`abortOn` (Retry). Precedencia: **denegar
   gana a permitir**, y después gana el **tipo más específico** de la jerarquía (semántica de MP FT). Por defecto, el
   CB registra `Exception`, Retry reintenta `Exception`, y ninguno de los dos trata `Error` (¿excepto
   `StackOverflowError`? → pregunta abierta).
2. **Desenvolver**: `CompletionException`, `ExecutionException`, `InvocationTargetException` y
   `UndeclaredThrowableException` se desenvuelven antes de clasificar. Es configurable.
3. **Clasificador** para lo que las listas no pueden expresar, incluidos los resultados:

```java
@FunctionalInterface
public interface Classifier<T> {
    Outcome classify(T result, Throwable failure);   // exactly one of result/failure is meaningful
    enum Outcome { SUCCESS, FAILURE, IGNORE }        // IGNORE = do not count, do not retry
}

public final class HttpStatusClassifier implements Classifier<HttpResponse<?>> {
    public Outcome classify(HttpResponse<?> r, Throwable t) {
        if (t != null) return t instanceof HttpTimeoutException ? Outcome.FAILURE : Outcome.IGNORE;
        int s = r.statusCode();
        return s >= 500 || s == 429 ? Outcome.FAILURE : Outcome.SUCCESS;
    }
}

@Retry(config = "external", classifier = HttpStatusClassifier.class)
@CircuitBreaker(config = "external", classifier = HttpStatusClassifier.class)
public HttpResponse<String> get(URI uri) { ... }
```

- Usar un solo método para resultados y excepciones evita la división de R4j entre `recordExceptions`,
  `recordFailurePredicate` y `recordResultPredicate`, y la confusión sobre cuál tiene precedencia.
- Un *resultado* `FAILURE` se registra o se reintenta. Si se agotan los reintentos, se devuelve el último resultado
  (por defecto) o se lanza `ResultRejectedException`. Se configura con `Retry.onExhaustedResult = RETURN | THROW`.
- **Rendimiento**: las listas se compilan al resolver la configuración en una caché `ClassValue<Outcome>`, de modo que
  clasificar una clase de excepción ya vista es una sola búsqueda. Es más barato que recorrer la lista con
  `isAssignableFrom` en cada fallo.

## 8. Asincronía e hilos virtuales

- **Decide el tipo de retorno**: `CompletionStage<T>`/`CompletableFuture<T>` usa el pipeline asíncrono (reintentos no
  bloqueantes programados en un `Scheduler`, timeouts con una programación al estilo `orTimeout`, permisos del
  bulkhead liberados al completar). Cualquier otro tipo de retorno es síncrono.
- **Lo síncrono sobre hilos virtuales** es el estilo recomendado en Java 21. El backoff de los reintentos duerme el
  hilo virtual, lo cual es barato ([JEP 444](https://openjdk.org/jeps/444)). Los bulkheads de semáforo bloquean hilos
  virtuales en lugar de hilos de plataforma.
- **El timeout síncrono** requiere (a) ejecutar la llamada en otro hilo (virtual) e interrumpirla al vencer, o (b) un
  vigilante que interrumpa al hilo llamante. (b) evita el salto de hilo, pero depende de que el código llamado respete
  las interrupciones. Propuesta: (b) por defecto para lo síncrono, documentado como "cooperativo". La implementación
  la decide la investigación de concurrencia. La concurrencia estructurada ([JEP 505](https://openjdk.org/jeps/505))
  sigue en preview en los JDK recientes, así que la API no debe depender de ella.
- **Los executors** son configurables por config (`async.executor`). Por defecto se usa
  `Executors.newVirtualThreadPerTaskExecutor()` para cualquier trabajo que la propia librería mande a otro hilo.
- **El hedging** (v1) necesita concurrencia, así que siempre ejecuta los intentos en paralelo en otros hilos, también
  en métodos síncronos, y cancela los que pierden.

## 9. Eventos, métricas e inspección de estado

```java
public sealed interface ResilienceEvent permits CircuitBreakerEvent, RetryEvent, TimeoutEvent,
                                                BulkheadEvent, RateLimiterEvent, FallbackEvent {
    String instance(); long nanoTime();
}
public sealed interface CircuitBreakerEvent extends ResilienceEvent {
    record StateTransition(String instance, long nanoTime, State from, State to) implements CircuitBreakerEvent {}
    record CallNotPermitted(String instance, long nanoTime) implements CircuitBreakerEvent {}
    record FailureRateExceeded(String instance, long nanoTime, float rate) implements CircuitBreakerEvent {}
}

resilience.listen(CircuitBreakerEvent.StateTransition.class, e -> alerting.notify(e));
resilience.listen("payments", RetryEvent.class, e -> ...);     // per instance
```

- **Coste cero si no se usan**: cada instancia guarda una referencia `final` al listener, que vale `NOOP` cuando no hay
  nada registrado. Los records de evento solo se crean tras una comprobación `if (listener != NOOP)`. Los eventos de
  éxito por llamada son opcionales (`events.per-call=true`). Los eventos de transición de estado se entregan siempre.
- **Métricas**: el core cuenta los resultados en `LongAdder`s locales a cada instancia, expuestos mediante
  `snapshot()`. Los módulos `micrometer` y `opentelemetry` (v1) registran esos snapshots como gauges o function
  counters, así que el camino caliente no busca medidores.
- **Inspección de estado** (health checks, endpoints de administración):

```java
ResilienceSnapshot s = resilience.snapshot();
for (CircuitBreakerSnapshot cb : s.circuitBreakers()) {
    System.out.printf("%s %s failureRate=%.1f%% calls=%d%n", cb.instance(), cb.state(), cb.failureRate(), cb.bufferedCalls());
}
resilience.circuitBreaker("payments").forceOpen();   // operational controls: forceOpen, forceClosed, disable, reset
```

## 10. Experiencia de desarrollo y pruebas

### 10.1 Mensajes de error

Todos los errores indican **dónde** (clase, método, anotación, atributo, o clave de configuración con su fuente),
**qué** pasa y **cómo arreglarlo**:

```
error: [R4C-FB-002] @Fallback(method = "findFallbak") on com.acme.UserClient.find(String):
       no method named 'findFallbak' found.
       Did you mean 'findFallback'?
       Expected signature: User findFallbak(String id[, <? extends Throwable> e])

error: [R4C-CFG-011] unknown key 'resilience4cata.configs.external.circuit-breaker.wait-in-opne'
       (from classpath:resilience4cata.properties, line 7). Did you mean 'wait-in-open'?

error: [R4C-CMP-004] conflicting values for @Timeout.value on com.acme.PaymentsClient.charge(Order):
       "2s" from @ExternalCall and "5s" from @NonIdempotentCall. Set it explicitly on the method to resolve.
```

- Códigos de error estables (`R4C-…`) con una página de documentación por código, en inglés y en español.
- Un **volcado de diagnóstico**, `resilience.describe("com.acme.PaymentsClient#charge")`, muestra la configuración
  efectiva con el *origen* de cada valor (`waitInOpen=30s ← configs.external (prod profile, env)`). Ayuda mucho con la
  configuración por capas.
- Las verificaciones en compilación están disponibles si se usa el procesador de anotaciones (la decisión sobre el
  mecanismo corresponde al doc 02). Si no, las mismas verificaciones se ejecutan en `build()`, con mensajes idénticos.

### 10.2 Historia de pruebas

El core aporta los puntos de enganche (MVP); el módulo `test` añade utilidades (la extensión de JUnit llega en v1).

```java
var clock = new ManualClock();                       // implements io.resilience4cata.Clock (nanoTime + wall)
var scheduler = new ManualScheduler(clock);          // retries/timeouts advance on clock.advance(...)

Resilience r = Resilience.builder()
    .clock(clock).scheduler(scheduler)
    .random(new SplittableRandom(42))                // deterministic jitter
    .profiles("test")
    .build();

UserClient client = r.wrap(UserClient.class, fakeClient);

fakeClient.failNext(5, new IOException("boom"));
assertThat(client.find("1")).isEqualTo(User.anonymous());

clock.advance(Duration.ofSeconds(30));               // move past waitInOpen without sleeping
assertThat(r.circuitBreaker("payments").state()).isEqualTo(State.HALF_OPEN);

// JUnit 5 (v1)
@ResilienceTest(profiles = "test")
class UserClientTest {
    @Inject ManualClock clock;
    @Inject RecordedEvents events;

    @Test void opensAfterFailures(@ForceState(instance = "payments", state = State.OPEN) CircuitBreakerHandle cb) {
        assertThrows(CallNotPermittedException.class, () -> client.find("1"));
        events.assertThat().containsExactly(CallNotPermitted.class);
    }
}
```

- **Desactivar todo** en pruebas unitarias de lógica de negocio con `resilience4cata.enabled=false`, o con
  `Resilience.disabled()`, que deja pasar todo y solo ejecuta los fallbacks si `fallback.when-disabled=true`.
- **Reinicio entre pruebas**: `resilience.reset()` reinicia el estado de todas las instancias pero conserva la
  configuración.
- **Inyección de fallos** (más adelante), reutilizable en pruebas de integración:
  `@InjectLatency("200ms", rate = 0.1)`.

## 11. Preguntas abiertas

1. ¿Las duraciones deben ser strings (propuesta) o `long` + `TimeUnit` (estilo MP)? Los strings se leen mejor y
   coinciden con el formato de los ficheros; los números tienen la comprobación de tipos de javac incluso sin el
   procesador.
2. ¿El procesador de anotaciones debe ser **obligatorio** u opcional? Si es opcional, todas las verificaciones de
   compilación deben repetirse en `build()`. Depende de la decisión sobre el mecanismo (doc 02).
3. ¿Los `Error` (p. ej. `OutOfMemoryError`) deben saltarse todas las políticas? Propuesta: sí, salvo una lista
   configurable de excepciones.
4. ¿Debería `@Timeout` ofrecer `offload = true` para código que no responde a las interrupciones?
5. ¿Debería la configuración externa poder **añadir** políticas que un método no ha declarado
   (`instances.<id>.retry.enabled=true`)? MP FT solo permite sobrescribir las declaradas. Permitirlo da mucha potencia
   pero hace el código más difícil de razonar, y quita algo de flexibilidad al código generado. Propuesta: no.
6. ¿Mantenemos las claves compatibles con MP (`<class>/<method>/<Annotation>/<param>`) solo como alias, o las
   eliminamos? Ayudan a migrar, pero duplican la documentación.
7. Granularidad por defecto de las instancias: ¿por método (propuesta) o por clase? Compartir por nombre, como en R4j,
   es habitual para "un breaker por servicio remoto". El `name` explícito cubre ese caso.

## 12. Fuentes

- Documentación de Resilience4j: <https://resilience4j.readme.io/docs> — uso con Spring Boot / anotaciones y orden de
  aspectos: <https://resilience4j.readme.io/docs/getting-started-3>; código: <https://github.com/resilience4j/resilience4j>
- Especificación MicroProfile Fault Tolerance 4.1 (anotaciones, claves de configuración, orden de ejecución):
  <https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html>;
  repositorio: <https://github.com/eclipse/microprofile-fault-tolerance>
- MicroProfile Config 3.1, mapeo de variables de entorno:
  <https://download.eclipse.org/microprofile/microprofile-config-3.1/microprofile-config-spec-3.1.html>
- SmallRye Fault Tolerance (extensiones: `@RateLimit`, anotaciones de backoff, `@ApplyGuard`, API programática):
  <https://smallrye.io/docs/smallrye-fault-tolerance/>
- Spring Retry (`@Retryable`, `@Recover`, `@Backoff`): <https://github.com/spring-projects/spring-retry>
- Funcionalidades de resiliencia en el core de Spring Framework 7 (`@Retryable`, `@ConcurrencyLimit`):
  <https://docs.spring.io/spring-framework/reference/core/resilience.html>
- Políticas y composición en Failsafe: <https://failsafe.dev/policies/>, <https://failsafe.dev/>
- Polly v8: pipelines <https://www.pollydocs.org/pipelines/>, hedging <https://www.pollydocs.org/strategies/hedging.html>,
  registro y recarga dinámica <https://www.pollydocs.org/advanced/dependency-injection.html>,
  caos <https://www.pollydocs.org/chaos/>
- Netflix concurrency-limits (límites adaptativos): <https://github.com/Netflix/concurrency-limits>
- J. Dean, L. Barroso, "The Tail at Scale" (peticiones con hedging): <https://research.google/pubs/the-tail-at-scale/>
- M. Brooker, "Exponential Backoff And Jitter": <https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/>
- Presupuestos de reintento en Finagle: <https://twitter.github.io/finagle/guide/Clients.html#retries>
- JEP 444 Virtual Threads: <https://openjdk.org/jeps/444>; JEP 505 Structured Concurrency (preview):
  <https://openjdk.org/jeps/505>; JEP 506 Scoped Values: <https://openjdk.org/jeps/506>
