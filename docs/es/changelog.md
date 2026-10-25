# Registro de cambios

Cambios notables en cada versión de resilience4cata, de la más reciente a la más antigua.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/), y el proyecto usa [Versionado Semántico](https://semver.org/lang/es/spec/v2.0.0.html). Hasta 1.0.0, una versión menor puede cambiar la API.

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
