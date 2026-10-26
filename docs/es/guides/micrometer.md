# Micrometer

Exporta lo que hacen las políticas a cualquier backend de Micrometer (Prometheus, Datadog, OTLP, …): counters de reintentos, rechazos, transiciones del breaker, timeouts y fallbacks, y gauges con el estado de cada circuit breaker, bulkhead y rate limiter.

El módulo `resilience4cata-micrometer` (disponible desde 0.3.0) está construido sobre los [eventos y handles](observability.md) de `core`; `core` en sí no depende de Micrometer. Trae `io.micrometer:micrometer-core` como dependencia transitiva (versión 1.17.1, con la que está probado).

## Instalar :id=install

Importa el BOM, como para los otros módulos ([instalación](../start/installation.md)), y agrega el módulo:

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.4.0')
    implementation 'dev.resilience4cata:resilience4cata-micrometer'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation(platform("dev.resilience4cata:resilience4cata-bom:0.4.0"))
    implementation("dev.resilience4cata:resilience4cata-micrometer")
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-micrometer</artifactId>
</dependency>
```

<!-- tabs:end -->

Con JPMS, agrega `requires dev.resilience4cata.micrometer;` (hace `requires transitive` de `dev.resilience4cata.core` y de `micrometer.core`).

## Configurar :id=setup

Tres líneas, alrededor del registry que ya construyes:

<!-- snippet: micrometer-setup -->
```java
MeterRegistry meterRegistry = new SimpleMeterRegistry(); // in your app: the registry you already export
MicrometerMetrics metrics = MicrometerMetrics.create(meterRegistry);
ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // counters, from events
        .build();
metrics.bindTo(registry); // gauges, for every breaker, bulkhead and rate limiter, even those created later
```

- `register(builder)` agrega un listener de eventos al builder y devuelve el builder, para que sigas configurándolo (`.retry(…)`, `.listen(…)`, [configuración externa](configuration.md)) antes de `build()`. Tiene que ejecutarse antes de `build()`: los listeners quedan fijos cuando se construye el registry.
- `bindTo(registry)` registra los gauges de las instancias que existen y de cada instancia que el registry cree después. Las instancias se crean al envolver un servicio (`Resilient.of`, `Resilient.create`) o al construir un `Pipeline`, así que puedes conectar justo después de `build()`, antes de envolver nada.

Los servicios anotados y los [pipelines](pipeline.md) se reportan igual. El tag `instance` es el id de instancia: el `name` de la anotación, el id derivado `pkg.Type#method(java.lang.String)` cuando no hay nombre, o el id del pipeline.

## Métricas :id=metrics

Los nombres y tags son los de [Observabilidad](observability.md#metric-names); la columna de Prometheus muestra cómo los exporta un `PrometheusMeterRegistry` (los puntos pasan a guiones bajos, los counters llevan `_total`, la unidad percent se vuelve el sufijo `_percent`).

| Meter | Tipo | Tags | En Prometheus |
|---|---|---|---|
| `r4c.calls.rejected` | counter | `instance`, `kind` = `circuitbreaker` \| `ratelimiter` \| `bulkhead` | `r4c_calls_rejected_total` |
| `r4c.circuitbreaker.transitions` | counter | `instance`, `from`, `to` | `r4c_circuitbreaker_transitions_total` |
| `r4c.retry.retries` | counter | `instance` | `r4c_retry_retries_total` |
| `r4c.retry.gave.up` | counter | `instance`, `reason` | `r4c_retry_gave_up_total` |
| `r4c.hedge.hedged` | counter | `instance`, `reason` | `r4c_hedge_hedged_total` |
| `r4c.hedge.won` | counter | `instance` | `r4c_hedge_won_total` |
| `r4c.hedge.skipped` | counter | `instance`, `reason` | `r4c_hedge_skipped_total` |
| `r4c.hedge.failed` | counter | `instance` | `r4c_hedge_failed_total` |
| `r4c.timeout.timed.out` | counter | `instance` | `r4c_timeout_timed_out_total` |
| `r4c.fallback.applied` | counter | `instance` | `r4c_fallback_applied_total` |
| `r4c.circuitbreaker.state` | gauge | `instance`, `state` | `r4c_circuitbreaker_state` |
| `r4c.circuitbreaker.failure.rate` | gauge, porcentaje | `instance` | `r4c_circuitbreaker_failure_rate_percent` |
| `r4c.circuitbreaker.slow.call.rate` | gauge, porcentaje | `instance` | `r4c_circuitbreaker_slow_call_rate_percent` |
| `r4c.bulkhead.concurrent.calls` | gauge | `instance` | `r4c_bulkhead_concurrent_calls` |
| `r4c.bulkhead.waiting.calls` | gauge | `instance` | `r4c_bulkhead_waiting_calls` |
| `r4c.bulkhead.max.concurrent` | gauge | `instance` | `r4c_bulkhead_max_concurrent` |
| `r4c.ratelimiter.available.permits` | gauge | `instance` | `r4c_ratelimiter_available_permits` |

Los valores de los tags van en minúsculas: estados `closed`, `open`, `half_open`, `forced_open`, `disabled`; motivos del retry `max_attempts`, `max_duration`, `not_retryable`, `interrupted`, `classifier_failed`, `budget_exhausted`; motivos del hedge `delay`, `failure` (`hedged`) y `budget_exhausted`, `rejected`, `aborted` (`skipped`) (un motivo agregado en una versión posterior aparece solo).

Cuándo aparece cada meter:

- **Los counters** se registran en su primer evento: un servicio que nunca reintentó todavía no tiene serie `r4c_retry_retries_total`. En PromQL, `… or vector(0)` llena el hueco cuando necesitas un cero.
- **Los gauges** se registran al conectar la instancia: las cinco series `state` del circuit breaker (una vale 1, las demás 0), sus dos tasas, los tres gauges del bulkhead y el del rate limiter. Los retries, hedges, timeouts y fallbacks no tienen gauges.
- **Las tasas son NaN** hasta que la ventana del breaker contiene `minimumCalls` llamadas, mientras el breaker está abierto, forzado abierto o desactivado (entonces no hay ventana) y, para la tasa de llamadas lentas, cuando la detección de llamadas lentas está apagada (sin `slowCallDuration`). Prometheus guarda NaN como un valor; los gráficos muestran un hueco.

## Lo que muestra Prometheus :id=prometheus

El `PaymentGateway` de estos ejemplos tiene `@Retry(maxAttempts = 3)`, `@CircuitBreaker(windowSize = 4, minimumCalls = 4)`, un rate limiter de 100 permisos por segundo y un bulkhead de 10, todos con el nombre `payments`. Después de dos llamadas contra un gateway caído (la primera llamada falla tres veces; el cuarto fallo abre el breaker, que rechaza el reintento de la segunda llamada), un scrape contiene estas líneas. Vienen de un scrape real de `PrometheusMeterRegistry` en las pruebas del proyecto, sin las líneas `# HELP` y `# TYPE`:

<!-- snippet: micrometer-prometheus -->
```text
r4c_bulkhead_concurrent_calls{instance="payments"} 0.0
r4c_bulkhead_max_concurrent{instance="payments"} 10.0
r4c_bulkhead_waiting_calls{instance="payments"} 0.0
r4c_calls_rejected_total{instance="payments",kind="circuitbreaker"} 1.0
r4c_circuitbreaker_failure_rate_percent{instance="payments"} NaN
r4c_circuitbreaker_slow_call_rate_percent{instance="payments"} NaN
r4c_circuitbreaker_state{instance="payments",state="closed"} 0.0
r4c_circuitbreaker_state{instance="payments",state="disabled"} 0.0
r4c_circuitbreaker_state{instance="payments",state="forced_open"} 0.0
r4c_circuitbreaker_state{instance="payments",state="half_open"} 0.0
r4c_circuitbreaker_state{instance="payments",state="open"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",instance="payments",to="open"} 1.0
r4c_ratelimiter_available_permits{instance="payments"} 98.0
r4c_retry_gave_up_total{instance="payments",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{instance="payments",reason="not_retryable"} 1.0
r4c_retry_retries_total{instance="payments"} 3.0
```

La tasa de fallos es NaN porque el breaker está abierto. El rate limiter muestra 98 de 100 permisos: cuatro intentos tomaron uno cada uno, y las esperas entre reintentos devolvieron algunos (la prueba corre sobre el [reloj manual](testing.md), así que los números son exactos).

## Paneles de Grafana :id=grafana

PromQL para los paneles que necesitan la mayoría de los servicios:

| Panel | Consulta | Notas |
|---|---|---|
| Rechazos por segundo, por política | `sum by (instance, kind) (rate(r4c_calls_rejected_total[5m]))` | serie temporal apilada |
| Breakers abiertos ahora | `sum by (instance) (r4c_circuitbreaker_state{state=~"open\|forced_open"})` | stat o state timeline; alerta cuando sea `> 0` durante unos minutos |
| Transiciones del breaker | `increase(r4c_circuitbreaker_transitions_total{to="open"}[1h])` | un breaker inestable se abre muchas veces |
| Retries por segundo | `sum by (instance) (rate(r4c_retry_retries_total[5m]))` | compáralo con tu tasa de requests |
| Abandonos por motivo | `sum by (instance, reason) (increase(r4c_retry_gave_up_total[1h]))` | `not_retryable` incluye las llamadas detenidas por un breaker abierto |
| Tasa de fallos | `r4c_circuitbreaker_failure_rate_percent` | huecos hasta `minimumCalls`, y mientras está abierto |
| Saturación del bulkhead | `r4c_bulkhead_concurrent_calls / r4c_bulkhead_max_concurrent` | 1 significa lleno; grafica también `r4c_bulkhead_waiting_calls` |
| Margen del rate limiter | `min by (instance) (r4c_ratelimiter_available_permits)` | cerca de 0 significa que se está frenando a los llamadores |
| Timeouts por segundo | `sum by (instance) (rate(r4c_timeout_timed_out_total[5m]))` | |
| Fallbacks por segundo | `sum by (instance) (rate(r4c_fallback_applied_total[5m]))` | la proporción de respuestas degradadas |

## Cardinalidad :id=cardinality

Cada serie lleva `instance`, y sus otros tags tienen un conjunto fijo de valores, así que el número de series crece con el número de instancias, no con el tráfico:

- un circuit breaker: 7 series de gauges, más hasta 25 series de `transitions` (en la práctica, las pocas transiciones que ocurren) y una de `rejected`;
- un retry: una serie `retries` y una serie `gave.up` por cada motivo que ocurrió;
- un hedge: hasta dos series `hedged`, una `won`, una `failed` y una serie `skipped` por cada motivo que ocurrió;
- un bulkhead: 3 gauges y una `rejected`; un rate limiter: 1 gauge y una `rejected`; un timeout o un fallback: un counter.

Mantén acotados los ids de instancia: vienen de tu código (un `name` de anotación, un método, un id de pipeline), así que lo están, salvo que construyas ids de pipeline con datos de la request. No lo hagas nunca, ni por las métricas ni por las propias políticas, que entonces nunca compartirían estado. Prefiere un `name` compartido por los métodos que llaman a la misma dependencia: una serie por dependencia se lee mejor que una por método. No agregues tú la clase ni el mensaje de la excepción como tag: en la práctica no están acotados.

## Ciclo de vida :id=lifecycle

- **Un `MicrometerMetrics` por `MeterRegistry`.** Para exportar a varios backends, pásale un `CompositeMeterRegistry`. Un mismo `MicrometerMetrics` puede servir a varios registries de resiliencia: llama a `register` en cada builder y a `bindTo` en cada registry. Micrometer identifica un meter solo por nombre y tags, así que si dos registries conectados tienen una instancia con el mismo id, sus counters se suman y se reportan los gauges del registry conectado primero.
- **`bindTo` es idempotente** para el mismo registry, y seguro desde cualquier thread, como todo lo demás en la clase.
- **Nada mantiene vivo un registry descartado.** Los gauges sujetan las políticas de forma débil (lo predeterminado en Micrometer), así que el meter registry nunca mantiene alcanzable un `ResilienceRegistry`; cuando ese registry es recolectado por el GC, sus gauges reportan NaN. Al revés, un registry conectado mantiene alcanzables el `MicrometerMetrics` y su meter registry, que es lo que quieres mientras corre.
- **`close()`** quita del meter registry todos los meters que registró, deja de contar eventos y deja de registrar gauges para instancias nuevas. Los servicios siguen funcionando. Llámalo cuando descartes un registry antes del final de la aplicación (en pruebas, o cuando se reinicia un componente):

<!-- snippet: micrometer-close -->
```java
metrics.close(); // removes every meter it registered; the services keep working
```

## Lo que cuesta :id=cost

- Una **llamada exitosa** no cuesta nada más: no publica ningún evento, y los gauges no están en el camino de la llamada.
- Un **evento** (un reintento, un rechazo, una transición, un timeout, un fallback) cuesta una búsqueda del counter en caché por id de instancia, una lectura de array y el incremento de Micrometer. El primer evento de cada counter lo registra en Micrometer. Aparte del propio record del evento, un evento con el counter en caché no asigna nada (una prueba lo comprueba). Si Micrometer lanza una excepción (un registry que rechaza un meter, por ejemplo), `core` la pasa al uncaught exception handler del thread y la llamada sigue.
- Un **scrape** lee los handles; cada una de las dos tasas toma un snapshot del breaker, que recorre su ventana.
- **Crear una instancia** (al envolver un servicio o construir un pipeline) lista una vez las instancias del registry para registrar los gauges nuevos.

El overhead del módulo todavía no se ha medido.
