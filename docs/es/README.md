# resilience4cata

> **Estado: fase 1 (investigación y diseño).** Todavía no hay código. Esta página resume las decisiones que salieron de la investigación y lista las preguntas que siguen abiertas.
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
| Build | Gradle con Kotlin DSL, convention plugins, version catalog | [arquitectura](architecture.md#build-y-herramientas) |
| Mecanismo de anotaciones | **Procesador de anotaciones en compilación (APT)** que genera código Java. Sin proxies, generación de bytecode ni agentes en `core` | [arquitectura](architecture.md#mecanismo-de-anotaciones) |
| Frameworks | Solo Java puro por ahora | — |
| Módulos | Nombres de una palabra: `annotations`, `core`, `processor`, `config`, `micrometer`, `testkit`, `bom`, más los internos `benchmark`, `stress`, `examples` | [arquitectura](architecture.md#módulos) |
| Dependencias de `core` | Ninguna (solo el JDK) | [arquitectura](architecture.md#módulos) |
| Políticas del MVP | `@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, `@RateLimiter`, `@Fallback` | [features](features.md) |
| Contrato de rendimiento | 0 B/op en el camino exitoso, sin locks en el camino caliente, verificado con JMH en CI | [features](features.md#contrato-de-rendimiento) |
| Documentación | Markdown en `docs/en` y `docs/es`, servido con Docsify. El inglés es la fuente de verdad | [arquitectura](architecture.md#documentación) |

## Preguntas abiertas

Hay que decidirlas antes o durante la fase 2 (el primer código):

1. **¿Módulo `annotations` separado, o dentro de `core`?** Se recomienda separado, lo que permite `compileOnly` si la retención es `CLASS`.
2. **Retención de las anotaciones: `CLASS` o `RUNTIME`?** `RUNTIME` solo hace falta si existe un fallback en runtime (proxies) o introspección en runtime.
3. **¿El procesador de anotaciones es obligatorio?** Si existe un módulo opcional `dynamic` con fallback a `Proxy`, todas las validaciones de compilación también deben correr al arrancar.
4. **¿Duraciones como strings (`"2s"`) o `long` + `TimeUnit`?** Se recomiendan strings, validados por el procesador.
5. **Nombre del build de convenciones de Gradle:** `build-logic` (el nombre habitual) o `conventions` (respeta la regla de una palabra).
6. **Namespace en Maven Central y paquete raíz** (`io.github.<usuario>` o un dominio propio).
7. **Auto-invocación** en las subclases generadas: ¿interceptarla (como Micronaut) o no (como Spring)?
8. **JSpecify:** ¿dependencia real de `core` (recomendado) o `compileOnly`?
9. **Recarga en caliente:** fuera del MVP; ¿en v1 solo una API explícita `reconfigure()`?

## Documentos de investigación

| # | Documento | Tema |
|---|---|---|
| 01 | [Panorama](research/01-landscape.md) | Resilience4j a fondo, competidores, oportunidades |
| 02 | [Mecanismos de anotaciones](research/02-annotation-mechanisms.md) | APT vs proxies vs ByteBuddy vs agentes, con un JMH propio |
| 03 | [Rendimiento interno](research/03-performance-internals.md) | Algoritmos y diseño sin locks por política, metodología de benchmarks |
| 04 | [API y configuración](research/04-api-and-configuration.md) | Niveles de features, anotaciones, modelo de configuración |
| 05 | [Arquitectura del proyecto](research/05-project-architecture.md) | Módulos, Gradle, JPMS, herramientas, docs, versionado |

**Sobre la evidencia.** Cada documento de investigación marca sus afirmaciones: verificadas en código o documentación, medidas, o inferidas. El documento 04 se escribió de memoria, sin revisar las fuentes en internet. Hay que verificar sus referencias antes de apoyarse en ellas, sobre todo los nombres de anotaciones de SmallRye y el orden de ejecución de MicroProfile. Ninguna afirmación de rendimiento sobre resilience4cata vale hasta que existan los benchmarks de la fase 2.
