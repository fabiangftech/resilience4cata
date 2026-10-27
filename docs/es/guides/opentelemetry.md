# OpenTelemetry

Exporta lo que hacen las políticas como métricas de OpenTelemetry: rechazos, retries, transiciones del breaker, timeouts y fallbacks como contadores, y el estado de breakers, bulkheads y rate limiters como gauges.

`resilience4cata-opentelemetry` se apoya en los [eventos y snapshots](observability.md) de `core`: un listener convierte los eventos en contadores, y gauges asíncronos leen el snapshot del registry cada vez que el SDK recolecta. Reporta los mismos nombres de métricas y atributos que el módulo de Micrometer, así que los dashboards y las alertas funcionan con cualquiera de los dos.

Esta versión tiene **solo métricas**. Todavía no hay spans de tracing.

## Dependencia :id=dependency

El módulo depende de `resilience4cata-core` y de la API de OpenTelemetry (`io.opentelemetry:opentelemetry-api`, 1.67.0), nada más. Tú pones el SDK y el exportador que uses: el módulo nunca los configura.

<!-- tabs:start -->
#### **Gradle**
```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.9.0')
    implementation 'dev.resilience4cata:resilience4cata-opentelemetry'    // version from the BOM

    implementation platform('io.opentelemetry:opentelemetry-bom:1.67.0')  // your SDK and exporter
    implementation 'io.opentelemetry:opentelemetry-sdk'
}
```
#### **Gradle (Kotlin)**
```kotlin
dependencies {
    implementation(platform("dev.resilience4cata:resilience4cata-bom:0.9.0"))
    implementation("dev.resilience4cata:resilience4cata-opentelemetry")    // version from the BOM

    implementation(platform("io.opentelemetry:opentelemetry-bom:1.67.0"))  // your SDK and exporter
    implementation("io.opentelemetry:opentelemetry-sdk")
}
```
#### **Maven**
```xml
<dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-opentelemetry</artifactId>
    <!-- version from the imported resilience4cata-bom -->
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <!-- version from the imported opentelemetry-bom -->
</dependency>
```
<!-- tabs:end -->

El módulo está probado con OpenTelemetry 1.67.0. OpenTelemetry mantiene su API 1.x estable compatible hacia atrás, así que un SDK 1.x más nuevo también debería funcionar (no probado). Con JPMS, agrega `requires dev.resilience4cata.opentelemetry;`. El jar de la API de OpenTelemetry es un módulo automático (`io.opentelemetry.api`), requerido de forma transitiva.

## Configuración :id=setup

Tres pasos: crea las métricas sobre tu instancia de `OpenTelemetry`, registra su listener en el builder del registry y vincula el registry para los gauges.

<!-- snippet: opentelemetry-wiring -->
```java
OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(openTelemetry); // your SDK instance
ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // adds the counters' listener
        .build();
metrics.bindTo(registry); // gauges, read at each collection: later instances are included
PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
```

- `register(builder)` debe ejecutarse **antes de `build()`**: los listeners quedan fijos al construir el registry. Devuelve el mismo builder, así que el resto de tu configuración (`retry(...)`, `ticker(...)`, `ResilienceConfig.applyTo(...)`) se encadena como siempre.
- `bindTo(registry)` puede ejecutarse en cualquier momento. Las instancias de las políticas se crean de forma diferida, cuando se envuelve una interfaz o se construye un pipeline; los gauges leen `registry.snapshot()` en cada recolección, así que las instancias creadas después de `bindTo` aparecen en la siguiente sin ninguna llamada extra.
- `create(MeterProvider)` hace lo mismo que `create(OpenTelemetry)` cuando solo tienes el meter provider. `GlobalOpenTelemetry.get()` también funciona, si tu aplicación (o el agente de Java) lo configura.

## Métricas :id=metrics

Cada métrica tiene un atributo `instance`: el id de la instancia de la política (el `name` de la anotación, el id derivado `pkg.Type#method(...)` o el id del pipeline). Los ids de instancia están fijos en tu código, así que el atributo es acotado. Los valores de los atributos van en minúsculas.

| Nombre | Instrumento de OpenTelemetry | Unidad | Atributos | Origen |
|---|---|---|---|---|
| `r4c.calls.rejected` | `LongCounter` | `{call}` | `instance`, `kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `CircuitBreakerEvent.CallNotPermitted`, `RateLimiterEvent.Rejected`, `AdaptiveLimitEvent.Rejected`, `BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | `LongCounter` | `{transition}` | `instance`, `from`, `to` (`closed`, `open`, `half_open`, `forced_open`, `disabled`) | `CircuitBreakerEvent.StateTransition` |
| `r4c.retry.retries` | `LongCounter` | `{retry}` | `instance` | `RetryEvent.Retrying` |
| `r4c.retry.gave.up` | `LongCounter` | `{call}` | `instance`, `reason` (`max_attempts`, `max_duration`, `not_retryable`, `interrupted`, `classifier_failed`, `budget_exhausted`) | `RetryEvent.GaveUp` |
| `r4c.hedge.hedged` | `LongCounter` | `{attempt}` | `instance`, `reason` (`delay`, `failure`) | `HedgeEvent.Hedged` |
| `r4c.hedge.won` | `LongCounter` | `{call}` | `instance` | `HedgeEvent.Won` |
| `r4c.hedge.skipped` | `LongCounter` | `{attempt}` | `instance`, `reason` (`budget_exhausted`, `rejected`, `aborted`) | `HedgeEvent.Skipped` |
| `r4c.hedge.failed` | `LongCounter` | `{call}` | `instance` | `HedgeEvent.Failed` |
| `r4c.adaptivelimit.limit.changes` | `LongCounter` | `{window}` | `instance` | `AdaptiveLimitEvent.LimitChanged` |
| `r4c.timeout.timed.out` | `LongCounter` | `{call}` | `instance` | `TimeoutEvent.TimedOut` |
| `r4c.fallback.applied` | `LongCounter` | `{call}` | `instance` | `FallbackEvent.Applied` |
| `r4c.circuitbreaker.state` | `ObservableLongGauge` | ninguna | `instance`, `state`: 1 para el estado actual, 0 para los otros cuatro | `state` del snapshot |
| `r4c.circuitbreaker.failure.rate` | `ObservableDoubleGauge` | `%` | `instance` | `failureRate` del snapshot |
| `r4c.circuitbreaker.slow.call.rate` | `ObservableDoubleGauge` | `%` | `instance` | `slowCallRate` del snapshot |
| `r4c.bulkhead.concurrent.calls` | `ObservableLongGauge` | `{call}` | `instance` | `concurrentCalls` del snapshot |
| `r4c.bulkhead.waiting.calls` | `ObservableLongGauge` | `{call}` | `instance` | `waitingCalls` del snapshot |
| `r4c.bulkhead.max.concurrent` | `ObservableLongGauge` | `{call}` | `instance` | `maxConcurrent` del snapshot |
| `r4c.ratelimiter.available.permits` | `ObservableLongGauge` | `{permit}` | `instance` | `availablePermits` del snapshot |
| `r4c.adaptivelimit.limit` | `ObservableLongGauge` | `{call}` | `instance` | `limit` del snapshot |
| `r4c.adaptivelimit.concurrent.calls` | `ObservableLongGauge` | `{call}` | `instance` | `inFlight` del snapshot |

- **Las tasas son porcentajes** (de 0 a 100) con unidad `%`, como en el snapshot y en el módulo de Micrometer, no proporciones (de 0 a 1) con unidad `1`.
- **Una tasa no tiene punto hasta que significa algo.** El snapshot reporta `-1` hasta que la ventana del breaker contiene `minimumCalls` llamadas (`halfOpenCalls` mientras está semiabierto), mientras el breaker está abierto, forzado abierto o deshabilitado (no hay ventana) y, para `slow.call.rate`, cuando la detección de llamadas lentas está desactivada. Los puntos de OpenTelemetry no pueden ser NaN, así que el módulo no registra ningún punto para esa instancia en esa recolección: la serie tiene un hueco.
- **Los gauges del bulkhead son gauges**, no `ObservableUpDownCounter`, para que todos los backends vean el mismo tipo que con el módulo de Micrometer.
- `reason` sigue a `RetryEvent.Reason`: un motivo agregado en una versión posterior aparece como un nuevo valor del atributo sin ningún cambio en el módulo.
- El instrumentation scope es `dev.resilience4cata`, con la versión del módulo como versión del scope.

No agregues la clase ni el mensaje de la excepción como atributo: en la práctica no están acotados. Las instancias de retry y timeout no tienen gauges: su estado es su configuración.

## Lo que cuesta :id=cost

- **Las llamadas exitosas no cuestan nada.** Las políticas no publican ningún evento en caso de éxito, y el listener es el único código que este módulo agrega a un camino de llamada.
- **Cada evento cuesta un `add` en un contador.** El listener encuentra los `Attributes` del evento en una caché (construidos una vez por instancia y valores de atributos, ya que los atributos de OpenTelemetry son inmutables), así que no asigna nada aparte del propio record del evento; después, el `add` del SDK hace su trabajo de agregación. Los eventos se publican solo en rechazos, retries, abandonos, transiciones, timeouts y fallbacks.
- **Los gauges no cuestan nada entre recolecciones.** Un solo batch callback construye un `registry.snapshot()` por recolección, que recorre las ventanas de los breakers, al intervalo del reader.
- **El listener nunca lanza excepciones**: un fallo dentro de OpenTelemetry se registra en `DEBUG` mediante `System.Logger` y la llamada continúa.

Aún no medido: el costo de un evento con la agregación del SDK.

## Exportar :id=exporting

El módulo solo crea instrumentos; tu SDK decide adónde van. Dos configuraciones habituales:

### Prometheus :id=prometheus

El exportador de Prometheus del SDK (`io.opentelemetry:opentelemetry-exporter-prometheus`, publicado con una versión `-alpha`) sirve `/metrics` desde su propio servidor HTTP. Esta configuración está probada en este repositorio:

<!-- snippet: opentelemetry-prometheus -->
```java
PrometheusHttpServer prometheus = PrometheusHttpServer.builder().setPort(port).build(); // 9464 by default
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(prometheus).build())
        .build();
```

Después de que un breaker se abrió, un scrape contiene líneas como estas (de la prueba; abreviado, sin las líneas `# HELP`/`# TYPE` ni la etiqueta `otel_scope_version`):

```text
r4c_retry_retries_total{instance="payments",otel_scope_name="dev.resilience4cata"} 3.0
r4c_retry_gave_up_total{instance="payments",otel_scope_name="dev.resilience4cata",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{instance="payments",otel_scope_name="dev.resilience4cata",reason="not_retryable"} 1.0
r4c_calls_rejected_total{instance="payments",kind="circuitbreaker",otel_scope_name="dev.resilience4cata"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",instance="payments",otel_scope_name="dev.resilience4cata",to="open"} 1.0
r4c_circuitbreaker_state{instance="payments",otel_scope_name="dev.resilience4cata",state="closed"} 0.0
r4c_circuitbreaker_state{instance="payments",otel_scope_name="dev.resilience4cata",state="open"} 1.0
r4c_bulkhead_max_concurrent{instance="payments",otel_scope_name="dev.resilience4cata"} 10.0
r4c_ratelimiter_available_permits{instance="payments",otel_scope_name="dev.resilience4cata"} 100.0
```

El exportador convierte los puntos en guiones bajos, agrega `_total` a los contadores y `_percent` a las tasas (`r4c_circuitbreaker_failure_rate_percent`), y descarta las unidades del estilo `{call}`: los **nombres son los mismos que con el registry de Prometheus de Micrometer**, así que los [paneles PromQL](micrometer.md#grafana) de la guía de observabilidad funcionan sin cambios. El exportador agrega las etiquetas `otel_scope_name` y `otel_scope_version` (`setOtelScopeLabelsEnabled(false)` las quita) y una serie `target_info`.

### OTLP :id=otlp

Para enviar las métricas a un OpenTelemetry Collector o a un backend OTLP, registra un reader periódico con el exportador OTLP (`io.opentelemetry:opentelemetry-exporter-otlp`). Ilustrativo, no probado en este repositorio:

<!-- not-compiled: illustrative, needs opentelemetry-exporter-otlp and a collector -->
```java
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(
                                OtlpGrpcMetricExporter.builder().setEndpoint("http://localhost:4317").build())
                        .setInterval(Duration.ofSeconds(30))
                        .build())
                .build())
        .build();
```

Con el módulo de autoconfiguración del SDK o con el agente de Java de OpenTelemetry, el SDK se configura con variables de entorno `OTEL_*`, y `OpenTelemetryMetrics.create(GlobalOpenTelemetry.get())` lo usa.

## Los mismos nombres que Micrometer :id=naming

El módulo de Micrometer (`resilience4cata-micrometer`) implementa la misma especificación: los mismos nombres de métricas, claves y valores de atributos, y unidades, con las tasas en porcentaje. Cambiar de uno a otro, o tener servicios con ambos, conserva tus dashboards y alertas. Las únicas diferencias vienen de los exportadores (las etiquetas `otel_scope_*` extra y `target_info` de arriba).

## Cerrar :id=closing

<!-- snippet: opentelemetry-close -->
```java
metrics.close(); // at shutdown: removes the gauge callbacks, and the counters stop counting
```

`OpenTelemetryMetrics` es `AutoCloseable`. Cerrarlo quita el batch callback de cada registry vinculado; el listener sigue en el registry (los listeners no se pueden quitar) pero ya no registra nada. `close()` es idempotente, y `bindTo` lanza `IllegalStateException` después de cerrar. Cerrar el SDK (`openTelemetry.close()`) sigue siendo tarea tuya.

Un mismo `OpenTelemetryMetrics` puede vincular varios registries, pero si sus ids de instancia se solapan, los gauges reportan dos veces los mismos conjuntos de atributos: usa uno por registry, o ids distintos.

## Tracing :id=tracing

No está en esta versión. Los spans para una librería de resiliencia requieren decisiones que las métricas no: si un retry es un span o uno por intento, cómo marcan el span un fallback o un breaker abierto, y el costo de un span en los caminos de éxito, que los eventos nunca tocan. Una versión posterior podría empezar sin ese costo agregando span events desde el mismo listener (`retry`, `circuit_breaker.transition`, `fallback`) al span actual del thread, y dejar la creación de spans a tu instrumentación.
