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
| `@CircuitBreaker`, `@Timeout`, `@Bulkhead`, `@RateLimiter`, `@Fallback` | Solo las anotaciones |
| Retornos async (`CompletionStage`), `classifier`, clases anotadas | El procesador los rechaza con un error explícito de "todavía no soportado" |
| Configuración externa (módulo `config`), perfiles, `extends` | Sin empezar |
| Registro como procesador incremental de Gradle | Pendiente: los proyectos que usan el procesador recompilan todo |
| Benchmarks JMH | Retry vs Resilience4j medido con uno y con cuatro servicios, un solo thread ([benchmarks](benchmarks.md)). jcstress sin empezar |

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
