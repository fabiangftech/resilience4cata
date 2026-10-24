# resilience4cata

> **Estado: fase 2 (primer código).** `@Retry` funciona de punta a punta; ver [estado de la implementación](#estado-de-la-implementación). Esta página también resume las decisiones de diseño de la fase 1.
>
> English version: [en/README.md](../en/README.md)

resilience4cata es una librería de resiliencia para **Java 21+ puro**: circuit breaker, retry, timeout, bulkhead, rate limiter y fallback, declarados con **anotaciones** que funcionan **sin Spring, CDI ni AspectJ**, y pensada para tener **menos overhead que Resilience4j**.

## Por qué otra librería de resiliencia

1. **Anotaciones sin framework.** Las anotaciones de Resilience4j solo hacen algo a través de Spring AOP, AspectJ o Micronaut. El modo standalone de SmallRye Fault Tolerance es solo programático. Ninguna librería conocida ofrece resiliencia declarativa en Java puro ([01 §1.3, §4.2](research/01-landscape.md)).
2. **Rendimiento medible.** Resilience4j asigna memoria en cada llamada (272 B/op para un circuit breaker solo, según su propio JMH), usa un lock en su ventana deslizante por defecto y compone las políticas con lambdas compartidas que el JIT no puede inlinear ([01 §1.7](research/01-landscape.md), [03 §0](research/03-performance-internals.md)). Polly v8 (.NET) demuestra que se puede llegar a casi cero asignaciones.
3. **Múltiples configuraciones como feature principal**: configuraciones con nombre y herencia, perfiles, anotaciones compuestas propias como `@ExternalCall`, y overrides externos sin recompilar ([04 §6](research/04-api-and-configuration.md)).

## Decisiones tomadas en la fase 1

| Tema | Decisión | Detalle |
|---|---|---|
| Versión de Java | Java 21+, probado en 21 y 25 | [arquitectura](architecture.md#build-y-herramientas) |
| Build | Gradle con un DSL de Groovy mínimo; convention plugins escritos en Java; version catalog. Nada de Kotlin en el proyecto | [arquitectura](architecture.md#build-y-herramientas) |
| Mecanismo de anotaciones | **Procesador de anotaciones en compilación (APT)** que genera código Java. Sin proxies, generación de bytecode ni agentes en `core` | [arquitectura](architecture.md#mecanismo-de-anotaciones) |
| Frameworks | Solo Java puro por ahora | — |
| Módulos | Nombres de una palabra: `annotations`, `core`, `processor`, `config`, `micrometer`, `testkit`, `bom`, más los internos `benchmark`, `stress`, `examples` | [arquitectura](architecture.md#módulos) |
| Dependencias de `core` | Ninguna (solo el JDK) | [arquitectura](architecture.md#módulos) |
| Políticas del MVP | `@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, `@RateLimiter`, `@Fallback` | [features](features.md) |
| Contrato de rendimiento | 0 B/op en el camino exitoso, sin locks en el camino caliente, verificado con JMH en CI | [features](features.md#contrato-de-rendimiento) |
| Documentación | Markdown en `docs/en` y `docs/es`, servido con Docsify. El inglés es la fuente de verdad | [arquitectura](architecture.md#documentación) |
| Módulo `annotations` | Separado de `core` | [arquitectura](architecture.md#módulos) |
| Duraciones | Strings (`"250ms"`, `"2s"`, `"PT2S"`), validados por el procesador | [features](features.md#anotaciones) |
| Build de convenciones de Gradle | Included build llamado `conventions` (respeta la regla de una palabra) | [arquitectura](architecture.md#build-y-herramientas) |
| Procesador | **Obligatorio.** Sin fallback con proxies en runtime. Si falta la clase generada, `Resilient.of` falla al arrancar con un mensaje que explica cómo agregar el procesador. El código que no se puede anotar usa la API programática de `core` | [arquitectura](architecture.md#mecanismo-de-anotaciones) |
| Retención de las anotaciones | `CLASS`: las anotaciones no se cargan en runtime, así `annotations` puede ser una dependencia `compileOnly`. Pasar a `RUNTIME` más adelante es un cambio compatible si algún día se agrega un fallback con proxies | [arquitectura](architecture.md#mecanismo-de-anotaciones) |
| Anotaciones en la interfaz vs la implementación | Con `Resilient.of(interfaz, impl, registry)` solo cuentan las anotaciones de la interfaz: la implementación no se conoce al compilar, así que anotarla es un error de compilación. Cuando se agregue el estilo subclase (`Resilient.create(Impl.class, registry)`), las anotaciones de la implementación **ganan**, atributo por atributo: interfaz < clase < método de la clase | [arquitectura](architecture.md#anotaciones-en-la-interfaz-vs-la-implementación) |
| Auto-invocación | **Se intercepta** en el estilo subclase: `this.otro()` pasa por el método generado, así que se aplican sus políticas. No es posible en el estilo wrapper de interfaz, porque las llamadas dentro de la implementación del usuario nunca llegan al wrapper; queda documentado, y quien lo necesite anota una clase no final | [arquitectura](architecture.md#auto-invocación) |
| Anotaciones de nulabilidad | JSpecify como dependencia real (`api`) de `core`: la única excepción a "cero dependencias". Es un jar de solo anotaciones de pocos KB, y da información de null al IDE, a Kotlin y a NullAway | [arquitectura](architecture.md#módulos) |
| Recarga en caliente | **Ninguna.** La configuración es inmutable una vez construido el registry; para cambiarla hay que reiniciar la aplicación. Así todos los valores de configuración quedan en campos `final` en el camino caliente | [features](features.md#múltiples-configuraciones) |
| Coordenadas | Dominio `resilience4cata.dev`: groupId de Maven `dev.resilience4cata`, paquete raíz `dev.resilience4cata` | [arquitectura](architecture.md#módulos) |

## Estado de la implementación

| Pieza | Estado |
|---|---|
| `@Retry` | **Terminado de punta a punta**: anotación, validación y generación en el procesador, runtime `RetryPolicy`, registry con valores por defecto y configuraciones con nombre, anotaciones compuestas, combinación entre interfaz y método |
| `@CircuitBreaker` | **Terminado de punta a punta**: ventanas por cantidad y por tiempo, máquina de estados sin locks, sondeos en half-open con `maxWaitInHalfOpen`, llamadas lentas, `recordOn`/`ignoreOn`, instancias compartidas por `name`, anidado dentro de `@Retry`, y `forceOpen`/`disable`/`reset` desde `registry.circuitBreaker(id)` |
| `@Timeout` | **Terminado de punta a punta** para métodos síncronos: la llamada sigue en el thread del llamador y un único watchdog compartido la interrumpe cuando se atrasa (resolución: `timeout / 16`, entre 1 y 50 ms, nunca antes de tiempo); la interrupción siempre se limpia después. Se aplica por intento, adentro del circuit breaker |
| `@Bulkhead` | **Terminado de punta a punta**: límite tipo semáforo con `maxConcurrent`; con `maxWait = "0"` un contador sin locks cuyos rechazos solo leen, si no un `Semaphore` no justo que estaciona virtual threads sin fijarlos; instancias compartidas por `name`; capa más interna, así un slot se ocupa solo mientras corre el método y la espera de uno cuenta para `@Timeout` |
| `@RateLimiter` | **Terminado de punta a punta**: `permits` por `period` con GCRA (generic cell rate algorithm) sobre un solo `long`: una ráfaga de hasta `permits` después de un rato sin llamadas, y luego un permiso cada `period / permits`; sin thread de fondo, y un rechazo solo lee. Con `maxWait`, la llamada reserva el próximo permiso y duerme hasta que toca; si la espera se interrumpe, el permiso se devuelve. Instancias compartidas por `name`, inspección con `registry.rateLimiter(id)`. Capa dentro del circuit breaker, así las llamadas que rechaza el breaker no gastan permisos |
| `@Fallback` | **Terminado de punta a punta** para métodos sincrónicos, como capa más externa, así también ve los rechazos (`CallNotPermittedException`, `BulkheadFullException`, `RateLimitExceededException`, `CallTimeoutException`). Dos formas, las dos verificadas al compilar: una clase handler tipada (`FallbackHandler<T>`, con `T` asignable al tipo de retorno, constructor público sin argumentos y una instancia por método) que recibe un `FallbackContext` con la falla, la cantidad de intentos y los argumentos; o métodos hermanos con los parámetros originales más un parámetro de excepción opcional, donde gana el tipo de excepción más específico. Con el wrapper de interfaz, los métodos hermanos tienen que ser métodos `default`, abstractos o `static` de la interfaz, porque la clase generada no puede llamar a los privados. `applyOn`/`skipOn` filtran las fallas (gana `skipOn`). Con `@Retry`, el fallback corre donde el bucle de retry se rinde, así conoce los intentos sin asignar nada en el camino exitoso. Una excepción checked que lanza un handler se relanza si el método la declara; si no, se envuelve en `UndeclaredThrowableException`. Todavía no soportado: métodos genéricos |
| Métodos async | **Terminado** para todas las políticas: un método que devuelve `CompletionStage<T>` o `CompletableFuture<T>` usa el camino async, decidido por el tipo de retorno (sin anotación extra). Una excepción lanzada antes de que el delegado devuelva un stage, o un stage `null`, cuenta como llamada fallida. Los reintentos se programan, nunca duermen: el primer intento corre en el thread del llamador, los siguientes en threads virtuales nuevos. El timeout falla el future con `CallTimeoutException` y cancela el stage atrasado (`Future.cancel`). El bulkhead ocupa su slot hasta que el stage termina. El `maxWait` del rate limiter reserva el permiso y arranca la llamada cuando toca, sin bloquear. Los fallbacks pueden devolver `T` o un stage de `T`. Un solo thread de timer daemon para la librería; todo lo que dispara corre en un thread virtual. Límites: sin comodines (`CompletionStage<? extends T>`) ni tipos de stage propios; con `maxWait > 0` la espera del bulkhead bloquea al llamador, igual que en Resilience4j; cancelar el future devuelto no cancela al delegado |
| `classifier`, clases anotadas | El procesador los rechaza con un error explícito de "todavía no soportado" |
| Configuración externa (módulo `config`), perfiles, `extends` | Sin empezar |
| Registro como procesador incremental de Gradle | Pendiente: los proyectos que usan el procesador recompilan todo |
| Benchmarks JMH | Retry (uno y cuatro servicios), circuit breaker (ventanas por cantidad y por tiempo) , timeout, bulkhead y rate limiter (1 y 4 threads), y métodos async, vs Resilience4j ([benchmarks](benchmarks.md)) |
| jcstress (módulo `stress`) | Sin empezar. Las carreras del circuit breaker están cubiertas con tests unitarios deterministas que reproducen las intercalaciones, verificados por mutación |

## Preguntas abiertas

Ninguna para la fase 1. Las preguntas nuevas que surjan al escribir el primer código se agregan aquí.

## Documentos de investigación

| # | Documento | Tema |
|---|---|---|
| 01 | [Panorama](research/01-landscape.md) | Resilience4j a fondo, competidores, oportunidades |
| 02 | [Mecanismos de anotaciones](research/02-annotation-mechanisms.md) | APT vs proxies vs ByteBuddy vs agentes, con un JMH propio |
| 03 | [Rendimiento interno](research/03-performance-internals.md) | Algoritmos y diseño sin locks por política, metodología de benchmarks |
| 04 | [API y configuración](research/04-api-and-configuration.md) | Niveles de features, anotaciones, modelo de configuración |
| 05 | [Arquitectura del proyecto](research/05-project-architecture.md) | Módulos, Gradle, JPMS, herramientas, docs, versionado |

**Sobre la evidencia.** Cada documento de investigación marca sus afirmaciones: verificadas en código o documentación, medidas, o inferidas. El documento 04 se escribió de memoria, sin revisar las fuentes en internet. Hay que verificar sus referencias antes de apoyarse en ellas, sobre todo los nombres de anotaciones de SmallRye y el orden de ejecución de MicroProfile. Ninguna afirmación de rendimiento sobre resilience4cata vale hasta que existan los benchmarks de la fase 2.
