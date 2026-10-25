# Registro de cambios

Cambios notables en cada versión de resilience4cata, de la más reciente a la más antigua.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/), y el proyecto usa [Versionado Semántico](https://semver.org/lang/es/spec/v2.0.0.html). Hasta 1.0.0, una versión menor puede cambiar la API.

## [Unreleased] :id=unreleased

### Agregado

- `resilience4cata-opentelemetry`: métricas de OpenTelemetry. `OpenTelemetryMetrics.create(openTelemetry)`, `register(builder)` y `bindTo(registry)` reportan rechazos, retries, abandonos, transiciones del breaker, timeouts y fallbacks como contadores alimentados por eventos, y el estado de breakers, bulkheads y rate limiters como gauges asíncronos leídos del snapshot en cada recolección, así que se incluyen las instancias creadas después. Los mismos nombres de métricas que el módulo de Micrometer. Solo métricas, todavía sin tracing. Consulta [OpenTelemetry](guides/opentelemetry.md).
- `resilience4cata-junit`: una extensión de JUnit Jupiter. `@ResilienceTest` inyecta en cada prueba (y en sus métodos `@BeforeEach`/`@AfterEach`) un `ManualClock` nuevo y un `ResilienceRegistry` que lo sigue, con `seed`, `startNanos` y un `failOnPendingTasks` opcional; los métodos `@ConfigureRegistry` personalizan el builder. Necesita JUnit 5.10 o posterior, JUnit 6 incluido. Consulta [Pruebas](guides/testing.md#junit).
- Una llamada async con `@Timeout` que termina a tiempo ahora quita su plazo de un `ManualClock` de inmediato, así que `pendingTasks()` ya no la cuenta hasta que pasa el plazo.
- **`resilience4cata-micrometer`**: [métricas de Micrometer](guides/micrometer.md) en tres líneas (`MicrometerMetrics.create(meterRegistry)`, `register(builder)`, `bindTo(registry)`): counters a partir de eventos (rechazos, transiciones del breaker, reintentos, abandonos, timeouts, fallbacks) y gauges para cada circuit breaker, bulkhead y rate limiter, incluidas las instancias creadas después de conectar. Reemplaza la receta escrita a mano de la guía de observabilidad.
- `ResilienceRegistry.onInstanceCreated(Runnable)`: ejecuta una acción una vez por cada instancia de política nueva (al envolver un servicio o construir un pipeline, nunca durante una llamada), para que los puentes de métricas registren meters para instancias creadas después ([Observabilidad](guides/observability.md#diy)).
- **Presupuesto de reintentos** para `@Retry`, `RetryConfig` y `Pipeline`: `budgetPercent`, `budgetMinRetriesPerSecond` y `budgetWindow` limitan los reintentos de una instancia de retry, compartidos por todos sus llamadores, a una reserva por segundo más un porcentaje de sus llamadas en la ventana, para que una caída no multiplique la carga sobre la dependencia que falla. `budgetPercent` lo activa, con una reserva por defecto de 10 reintentos por segundo. Apagado por defecto; sin él, el código generado y el camino de éxito no cambian. Un reintento rechazado publica `RetryEvent.GaveUp` con la nueva razón `BUDGET_EXHAUSTED`; `RetrySnapshot.budget()` muestra la ventana. Claves `defaults.retry.budget…` (y `configs.…`, `instances.…`, variables de entorno); nuevo error del procesador [R4C-ATR-007](errors.md#r4c-atr-007). [Presupuesto de reintentos](policies/retry.md#presupuesto-de-reintentos)

## [0.2.0] - 2026-10-25 :id=0.2.0

Corrige tres errores de comportamiento de la 0.1.0; uno de los arreglos cambia un valor por defecto (ver Actualizar).

### Actualizar desde 0.1.0

- Mantén `core` y `processor` en la misma versión (importa el BOM). El código generado por el procesador 0.2.0 necesita el `core` 0.2.0; el código generado por la 0.1.0 sigue enlazando contra el `core` 0.2.0, que solo agrega API.
- `abortOn` ya no contiene `CallNotPermittedException` por defecto, porque ahora un circuit breaker abierto detiene los reintentos por sí solo. Quita `CallNotPermittedException` de tus propias listas `abortOn` si lo agregaste solo para eso; dejarlo no causa problemas.
- Una llamada rechazada por un breaker abierto ya no se puede reintentar con ninguna configuración, ni siquiera con un classifier.

### Cambiado

- **Un circuit breaker abierto siempre detiene los reintentos.** Una llamada rechazada con `CallNotPermittedException` nunca se reintenta, digan lo que digan `abortOn`, `retryOn` o un classifier, en el código generado (sincrónico y async) y en `Pipeline`. En 0.1.0 esto venía del `abortOn` por defecto, así que definir tu propio `abortOn` lo quitaba sin aviso y el retry seguía llamando a un breaker abierto. `RetryConfig.DEFAULTS.abortOn()` (y `defaults.retry.abortOn`) ahora está vacío; [Retry](policies/retry.md#atributos).

### Corregido

- `FallbackEvent.Applied` ya no se publica para una falla que ningún método hermano de fallback recibe (por ejemplo un `Error` cuando el único hermano recibe `Exception`): esa falla se relanza, y el evento ahora significa que un fallback realmente se ejecutó.
- Los mensajes de error R4C-ATR-001 y R4C-CFG-002 indican la gramática de duraciones una vez en lugar de dos, y R4C-CFG-007 cierra la oración antes de "Known configs".
- El descriptor de módulo del procesador declara también `AnnotationClaimer`, así que `-Xlint:processing` no avisa cuando el procesador corre desde el module path de procesadores junto a otros procesadores.
- Un classifier que lanza dentro de `@CircuitBreaker` (o del breaker de un `Pipeline`) ahora termina la llamada enseguida, sync y async: el retry que lo rodea ya no clasifica la excepción del classifier como la falla del método ni la reintenta (lo que podía abrir el breaker y mostrar `CallNotPermittedException` en su lugar). El breaker registra una falla, el retry publica `GaveUp(CLASSIFIER_FAILED)` una vez, y el fallback o quien llama recibe la excepción del classifier. Ver [Un classifier que lanza](guides/classifiers.md#un-classifier-que-lanza).

## [0.1.0] - 2026-10-25 :id=0.1.0

Primera versión. Artefactos en Maven Central bajo el groupId `dev.resilience4cata`: `resilience4cata-annotations`, `-core`, `-processor`, `-config`, `-testkit` y `-bom`. Requiere Java 21 o posterior; probado en Java 21 y 25.

### Agregado

- **Seis políticas como anotaciones**, aplicadas en un orden fijo (Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → método): [`@Retry`](policies/retry.md) (backoff fijo o exponencial, jitter, presupuesto de tiempo), [`@CircuitBreaker`](policies/circuit-breaker.md) (ventana por cantidad o por tiempo, llamadas lentas, sondeos en semiabierto), [`@RateLimiter`](policies/rate-limiter.md) (GCRA, espera opcional), [`@Timeout`](policies/timeout.md) (en el thread de quien llama, basado en interrupciones), [`@Bulkhead`](policies/bulkhead.md) (semáforo, espera opcional) y [`@Fallback`](policies/fallback.md) (método hermano o clase handler).
- **Procesador de anotaciones** que genera una clase Java pura por cada tipo anotado: `<Interface>$Resilient`, que envuelve una implementación (`Resilient.of`), o `<Class>$Resilient`, que extiende una [clase anotada](guides/annotated-classes.md) (`Resilient.create`). Las anotaciones y sus valores se verifican al compilar; procesamiento incremental isolating en Gradle; funciona con `-Xlint:all -Werror`.
- **Métodos async** que devuelven `CompletionStage<T>` o `CompletableFuture<T>` (también `? extends T`), con formas no bloqueantes de cada política ([Métodos async](guides/async.md)).
- **[Anotaciones compuestas](guides/bundles.md)** con `@ResiliencePolicy`, y anotaciones a nivel de tipo.
- **[Classifiers](guides/classifiers.md)** que deciden a partir de un resultado o una excepción si una llamada falló, y `@Retry(onExhaustedResult = THROW)`.
- **[`Pipeline`](guides/pipeline.md)**, una API programática con las mismas políticas y el mismo orden para código que no puedes anotar.
- **`ResilienceRegistry`**: valores predeterminados, configs con nombre, sobrescrituras por instancia, instancias compartidas por `name`, handles para circuit breakers (`forceOpen`, `disable`, `reset`), rate limiters y bulkheads.
- **[Eventos y snapshots](guides/observability.md)**: listeners registrados en el builder del registry, snapshots de solo lectura de cada instancia.
- **`resilience4cata-config`**: [configuración externa](guides/configuration.md) desde archivos properties, recursos del classpath, variables de entorno y propiedades del sistema, con perfiles, `extends`, sobrescrituras por instancia, validación estricta y `verifyInstances` ([claves](reference/configuration.md)).
- **`resilience4cata-testkit`**: [`ManualClock` y `ResilienceTesting`](guides/testing.md) para pruebas deterministas del comportamiento basado en tiempo.
- **`resilience4cata-bom`** para alinear las versiones de todos los módulos.
- **[Códigos de error](errors.md)** (`R4C-…`) en cada error de compilación y de ejecución, cada uno documentado con su solución.
- **Control de asignación de memoria** en CI: cada ruta síncrona de éxito y de rechazo asigna 0 bytes ([Rendimiento](performance.md)).

### Limitaciones conocidas

- No hay módulos de integración con Spring, CDI, Micrometer ni OpenTelemetry; no hay configuración en YAML.
- El orden de las políticas no se puede cambiar, y la configuración no se puede cambiar después de construir el registry.
- En el estilo de interfaz, las llamadas que una implementación se hace a sí misma no se interceptan.
- Solo se admiten `CompletionStage<T>` y `CompletableFuture<T>` como tipos async (no `? super T`, no tipos reactivos).

[0.1.0]: https://github.com/fabiangftech/resilience4cata/releases/tag/v0.1.0
