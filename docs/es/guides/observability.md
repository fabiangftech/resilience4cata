# Observabilidad

Ve lo que hacen las políticas a través de eventos, lee su estado a través de snapshots y handles, y construye métricas, logs y health checks sobre ambos.

resilience4cata 0.1.0 te da dos piezas básicas:

1. **Eventos**: un record por cada cosa que hace una política aparte de dejar pasar una llamada (un retry, una transición del breaker, un rechazo, un timeout, un fallback), entregado a los listeners que registras en el builder del registry.
2. **Snapshots y handles**: el estado actual de cada instancia, leído cuando lo pides.

> [!NOTE]
> No hay módulo de Micrometer, OpenTelemetry ni logging en 0.1.0. La [receta de Micrometer](#micrometer) de abajo es código que pones en tu aplicación; está probada en este repositorio con Micrometer 1.17.1, pero no es un artefacto de la librería.

## Eventos :id=events

Cada evento implementa la interfaz sellada `dev.resilience4cata.event.ResilienceEvent`, con `instance()` (el id de la instancia: el `name`, el id derivado `pkg.Type#method(java.lang.String,int)` o un id de pipeline) y `nanoTime()` (una lectura del reloj del registry: `System.nanoTime()`, o el [reloj manual](testing.md) en los tests). Cada política tiene su propia interfaz sellada de records:

| Política | Record del evento | Campos (después de `instance`, `nanoTime`) | Se publica cuando |
|---|---|---|---|
| Circuit breaker | `CircuitBreakerEvent.StateTransition` | `CircuitBreakerState from`, `CircuitBreakerState to` | el estado cambia, exactamente una vez por cambio, incluidos `forceOpen()`, `disable()` y `reset()` |
| Circuit breaker | `CircuitBreakerEvent.CallNotPermitted` | `CircuitBreakerState state` | una llamada es rechazada (`OPEN`, `FORCED_OPEN`, o `HALF_OPEN` con todas las pruebas tomadas) |
| Retry | `RetryEvent.Retrying` | `int attempt`, `Duration delay`, `@Nullable Throwable failure` | un intento falló y se ejecutará otro después de `delay`; `failure` es `null` cuando un [classifier](classifiers.md) rechazó un resultado |
| Retry | `RetryEvent.GaveUp` | `int attempts`, `RetryEvent.Reason reason`, `@Nullable Throwable failure` | el retry se detiene sin un éxito: `MAX_ATTEMPTS`, `MAX_DURATION`, `NOT_RETRYABLE` (el fallo no está en `retryOn`, o está en `abortOn`, por ejemplo un breaker abierto), `INTERRUPTED`, `CLASSIFIER_FAILED` |
| Rate limiter | `RateLimiterEvent.Rejected` | ninguno | una llamada es rechazada con `RateLimitExceededException` |
| Timeout | `TimeoutEvent.TimedOut` | `Duration timeout` | una llamada excede su timeout |
| Bulkhead | `BulkheadEvent.Rejected` | `int maxConcurrent` | una llamada es rechazada con `BulkheadFullException` |
| Fallback | `FallbackEvent.Applied` | `Throwable failure` | un fallback está por ejecutarse para `failure` |

Un retry con `enabled = false` no publica nada. Un primer fallo que no es reintentable publica `GaveUp` con `attempts = 1` y `NOT_RETRYABLE`.

### Escuchar :id=listening

Los listeners se registran en `ResilienceRegistry.Builder`, y solo ahí: quedan fijos cuando se construye el registry. Escucha un tipo de record, una interfaz de política completa o `ResilienceEvent`; en todas las instancias o en un id de instancia:

<!-- snippet: observability-listen -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        // one record type, on every instance
        .listen(CircuitBreakerEvent.StateTransition.class,
                t -> log.add(t.instance() + ": " + t.from() + " -> " + t.to()))
        // every retry event (Retrying and GaveUp), on one instance only
        .listen("payments", RetryEvent.class, e -> log.add(e.toString()))
        // everything: the hierarchy is sealed, so a switch can be exhaustive
        .listen(ResilienceEvent.class, e -> {
            switch (e) {
                case RetryEvent.GaveUp g -> log.add("gave up after " + g.attempts() + ": " + g.reason());
                default -> { }
            }
        })
        .build();
```

- **Los listeners se ejecutan de forma síncrona** en el thread que causó el evento: el thread del llamador, o un thread virtual de la librería para los timeouts async y los retries async diferidos. Mantenlos rápidos; pasa el trabajo lento (una llamada de red) a tu propio executor.
- **Una excepción lanzada por un listener** no llega a la llamada ni a los demás listeners: va al uncaught exception handler del thread.
- **Orden:** los eventos publicados desde threads distintos pueden llegar desordenados; cada transición de un breaker llega exactamente una vez.
- `EventListener<E>` es una interfaz funcional (`void onEvent(E event)`), así que sirve una lambda o una referencia a método.

### Lo que cuestan los eventos :id=cost

Cada política resuelve sus listeners una sola vez, cuando se crea la instancia, en un campo por tipo de evento. Un tipo de evento que nadie escucha cuesta **un null check, y solo en los caminos que no son de éxito**. Una llamada exitosa no publica nada y no comprueba nada. Cuando alguien escucha, el record del evento se asigna y el reloj se lee solo en ese momento.

**No hay contadores por llamada** (ni "llamadas exitosas" ni totales de latencia), por diseño: un contador actualizado en cada llamada sería una escritura compartida en el hot path. Si necesitas conteos de llamadas o latencia, mídelos en tu propio código alrededor de la llamada, o con las métricas de tu cliente HTTP o de tu framework.

## Snapshots :id=snapshots

`registry.snapshot()` devuelve un `ResilienceSnapshot` con una lista por política, cada una ordenada por id de instancia. Se construye cuando lo llamas, nunca en el camino de la llamada, y puede asignar memoria y recorrer las ventanas de los breakers: llámalo en el momento del scrape o del health check, no por cada request.

<!-- snippet: observability-snapshot -->
```java
ResilienceSnapshot snapshot = registry.snapshot(); // built when asked, never on the call path
for (CircuitBreakerSnapshot b : snapshot.circuitBreakers()) {
    lines.add(b.instance() + " " + b.state() + " failureRate=" + b.failureRate()
            + " buffered=" + b.bufferedCalls() + " failed=" + b.failedCalls() + " slow=" + b.slowCalls());
}
for (BulkheadSnapshot b : snapshot.bulkheads()) {
    lines.add(b.instance() + " in use " + b.concurrentCalls() + "/" + b.maxConcurrent()
            + ", waiting " + b.waitingCalls() + ", free " + b.availableSlots());
}
for (RateLimiterSnapshot r : snapshot.rateLimiters()) {
    lines.add(r.instance() + " permits " + r.availablePermits() + "/" + r.permits());
}
```

| Record | Campos |
|---|---|
| `CircuitBreakerSnapshot` | `instance`, `state`, `failureRate` y `slowCallRate` (porcentaje, o `-1` hasta que la ventana contenga `minimumCalls` llamadas, `halfOpenCalls` mientras está semiabierto; `slowCallRate` también es `-1` cuando la detección de llamadas lentas está desactivada), `bufferedCalls`, `failedCalls`, `slowCalls` (llamadas en la ventana actual: la ventana deslizante mientras está cerrado, las pruebas mientras está semiabierto, ninguna mientras está abierto, forzado a abierto o desactivado) |
| `BulkheadSnapshot` | `instance`, `concurrentCalls`, `waitingCalls`, `maxConcurrent`, y el método `availableSlots()` |
| `RateLimiterSnapshot` | `instance`, `availablePermits`, `permits` |
| `RetrySnapshot` | `instance`, `config` (el `RetryConfig` resuelto) |
| `TimeoutSnapshot` | `instance`, `config` (el `TimeoutConfig` resuelto) |

## Handles :id=handles

Los handles leen una instancia en vivo, y el del breaker además puede cambiar su estado:

| Método | Devuelve |
|---|---|
| `registry.circuitBreaker(id)` | `CircuitBreakerHandle`: `id()`, `state()`, `config()`, `snapshot()`, `forceOpen()`, `disable()`, `reset()` |
| `registry.circuitBreakers()` | todos los handles de breaker, ordenados por id |
| `registry.rateLimiter(id)` | `RateLimiterHandle`: `id()`, `config()`, `availablePermits()`, `snapshot()` |
| `registry.bulkhead(id)` | `BulkheadHandle`: `id()`, `config()`, `concurrentCalls()`, `waitingCalls()`, `snapshot()` |

Una instancia existe solo cuando su interfaz ya fue envuelta (`Resilient.of`, `Resilient.create`) o su `Pipeline` ya fue construido; antes de eso, o para un id que no existe, la búsqueda lanza `IllegalArgumentException` [R4C-REG-009](../errors.md#r4c-reg-009) con el id más cercano. Retry y timeout no tienen handle; su configuración está en el snapshot. No hay método de listado para bulkheads y rate limiters: enuméralos a través de `registry.snapshot()`.

## Receta de Micrometer :id=micrometer

Counters a partir de eventos, gauges a partir de handles. Copia las dos clases en tu aplicación; necesitan `io.micrometer:micrometer-core` (la receta está probada con 1.17.1).

### Nombres de métricas :id=metric-names

Los nombres van en minúsculas y separados por puntos, como recomienda Micrometer; cada meter tiene un tag `instance` (los ids de instancia están fijos en tu código, así que el tag está acotado) y como mucho uno o dos tags más con un conjunto fijo de valores.

| Meter | Tipo | Tags | Origen |
|---|---|---|---|
| `r4c.calls.rejected` | counter | `instance`, `kind` = `circuitbreaker` \| `ratelimiter` \| `bulkhead` | `CallNotPermitted`, `RateLimiterEvent.Rejected`, `BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | counter | `instance`, `from`, `to` (estados en minúsculas) | `StateTransition` |
| `r4c.retry.retries` | counter | `instance` | `Retrying` |
| `r4c.retry.gave.up` | counter | `instance`, `reason` (`Reason` en minúsculas) | `GaveUp` |
| `r4c.timeout.timed.out` | counter | `instance` | `TimedOut` |
| `r4c.fallback.applied` | counter | `instance` | `Applied` |
| `r4c.circuitbreaker.state` | gauge | `instance`, `state`: 1 para el estado actual, 0 para los demás | `state()` |
| `r4c.circuitbreaker.failure.rate`, `r4c.circuitbreaker.slow.call.rate` | gauge (porcentaje) | `instance` | `snapshot()`; NaN hasta que la ventana contenga `minimumCalls` llamadas |
| `r4c.bulkhead.concurrent.calls`, `r4c.bulkhead.waiting.calls`, `r4c.bulkhead.max.concurrent` | gauge | `instance` | `BulkheadHandle` |
| `r4c.ratelimiter.available.permits` | gauge | `instance` | `RateLimiterHandle` |

No agregues la clase ni el mensaje de la excepción como tag: en la práctica no están acotados.

### Counters a partir de eventos :id=counters

<!-- snippet: observability-counters -->
```java
import dev.resilience4cata.event.BulkheadEvent;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.RateLimiterEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.event.TimeoutEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Turns resilience4cata events into Micrometer counters. Your code, not a library module: copy it.
 * Register it with ResilienceRegistry.Builder.listen(ResilienceEvent.class, ...) before build().
 */
public final class ResilienceEventCounters implements EventListener<ResilienceEvent> {

    private final MeterRegistry meters;

    public ResilienceEventCounters(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void onEvent(ResilienceEvent event) {
        switch (event) {
            case CircuitBreakerEvent.StateTransition t ->
                    count("r4c.circuitbreaker.transitions", t, "from", lower(t.from()), "to", lower(t.to()));
            case CircuitBreakerEvent.CallNotPermitted r -> count("r4c.calls.rejected", r, "kind", "circuitbreaker");
            case RateLimiterEvent.Rejected r -> count("r4c.calls.rejected", r, "kind", "ratelimiter");
            case BulkheadEvent.Rejected r -> count("r4c.calls.rejected", r, "kind", "bulkhead");
            case RetryEvent.Retrying r -> count("r4c.retry.retries", r);
            case RetryEvent.GaveUp g -> count("r4c.retry.gave.up", g, "reason", lower(g.reason()));
            case TimeoutEvent.TimedOut t -> count("r4c.timeout.timed.out", t);
            case FallbackEvent.Applied f -> count("r4c.fallback.applied", f);
        }
    }

    private void count(String name, ResilienceEvent event, String... tags) {
        Counter.builder(name).tag("instance", event.instance()).tags(tags).register(meters).increment();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
```

`Counter.builder(…).register(meters)` busca el counter en cada evento (Micrometer devuelve el que ya existe). Eso cuesta una allocation y una búsqueda en un mapa por evento, solo en los caminos de fallo; si tus eventos son muy frecuentes, guarda en caché los counters por instancia y valores de tags.

### Gauges a partir de handles :id=gauges

<!-- snippet: observability-gauges -->
```java
import dev.resilience4cata.BulkheadHandle;
import dev.resilience4cata.BulkheadSnapshot;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.RateLimiterHandle;
import dev.resilience4cata.RateLimiterSnapshot;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.ResilienceSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Micrometer gauges read from the registry's handles when the meters are scraped. Your code, not a
 * library module: copy it. Call bind(...) after wrapping your services: an instance exists only then.
 */
public final class ResilienceGauges {

    private ResilienceGauges() {
    }

    public static void bind(ResilienceRegistry registry, MeterRegistry meters) {
        for (CircuitBreakerHandle breaker : registry.circuitBreakers()) {
            for (CircuitBreakerState state : CircuitBreakerState.values()) { // 1 for the current state, else 0
                Gauge.builder("r4c.circuitbreaker.state", breaker, b -> b.state() == state ? 1 : 0)
                        .tag("instance", breaker.id()).tag("state", state.name().toLowerCase(Locale.ROOT))
                        .strongReference(true).register(meters);
            }
            Gauge.builder("r4c.circuitbreaker.failure.rate", breaker, b -> percent(b.snapshot().failureRate()))
                    .tag("instance", breaker.id()).baseUnit("percent").strongReference(true).register(meters);
            Gauge.builder("r4c.circuitbreaker.slow.call.rate", breaker, b -> percent(b.snapshot().slowCallRate()))
                    .tag("instance", breaker.id()).baseUnit("percent").strongReference(true).register(meters);
        }
        ResilienceSnapshot snapshot = registry.snapshot(); // lists every bulkhead and rate limiter
        for (BulkheadSnapshot b : snapshot.bulkheads()) {
            BulkheadHandle bulkhead = registry.bulkhead(b.instance());
            Gauge.builder("r4c.bulkhead.concurrent.calls", bulkhead, BulkheadHandle::concurrentCalls)
                    .tag("instance", b.instance()).strongReference(true).register(meters);
            Gauge.builder("r4c.bulkhead.waiting.calls", bulkhead, BulkheadHandle::waitingCalls)
                    .tag("instance", b.instance()).strongReference(true).register(meters);
            Gauge.builder("r4c.bulkhead.max.concurrent", bulkhead, h -> h.config().maxConcurrent())
                    .tag("instance", b.instance()).strongReference(true).register(meters);
        }
        for (RateLimiterSnapshot r : snapshot.rateLimiters()) {
            RateLimiterHandle limiter = registry.rateLimiter(r.instance());
            Gauge.builder("r4c.ratelimiter.available.permits", limiter, RateLimiterHandle::availablePermits)
                    .tag("instance", r.instance()).strongReference(true).register(meters);
        }
    }

    /** The snapshot reports -1 until the window holds minimumCalls calls: no value yet. */
    private static double percent(float rate) {
        return rate < 0 ? Double.NaN : rate;
    }
}
```

Los gauges se leen cuando se hace scrape del registry. `strongReference(true)` mantiene vivo el handle aunque nada más lo referencie (Micrometer guarda los objetos de los gauges con referencias débiles por defecto).

### Conexión :id=wiring

El listener debe registrarse antes de `build()`, y los gauges vincularse después de envolver los servicios, porque las instancias existen solo a partir de ese momento. Las instancias creadas más tarde (otra llamada a `Resilient.of`) necesitan otra llamada a `ResilienceGauges.bind(…)`, o bien vincula una sola vez al final de tu conexión.

<!-- snippet: observability-micrometer-wiring -->
```java
MeterRegistry meters = new SimpleMeterRegistry(); // in your app: the registry you already export
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(ResilienceEvent.class, new ResilienceEventCounters(meters))
        .build();
PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
ResilienceGauges.bind(registry, meters); // after wrapping: the instances exist now
```

### Lo que muestra Prometheus :id=prometheus

Con un `PrometheusMeterRegistry`, la convención de nombres de Micrometer convierte los puntos en guiones bajos y agrega `_total` a los counters. Después de que un breaker se abrió, un scrape contiene líneas como estas (resumidas, los valores son un ejemplo):

```text
r4c_retry_retries_total{instance="payments"} 3.0
r4c_retry_gave_up_total{instance="payments",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{instance="payments",reason="not_retryable"} 1.0
r4c_calls_rejected_total{instance="payments",kind="circuitbreaker"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",instance="payments",to="open"} 1.0
r4c_circuitbreaker_state{instance="payments",state="closed"} 0.0
r4c_circuitbreaker_state{instance="payments",state="open"} 1.0
r4c_circuitbreaker_failure_rate_percent{instance="payments"} 100.0
r4c_bulkhead_concurrent_calls{instance="payments"} 0.0
r4c_bulkhead_max_concurrent{instance="payments"} 10.0
r4c_ratelimiter_available_permits{instance="payments"} 96.0
```

### Paneles de Grafana :id=grafana

PromQL para los paneles que necesitan la mayoría de los servicios:

| Panel | Consulta | Notas |
|---|---|---|
| Rechazos por segundo, por política | `sum by (instance, kind) (rate(r4c_calls_rejected_total[5m]))` | serie temporal apilada |
| Breakers abiertos ahora | `sum by (instance) (r4c_circuitbreaker_state{state=~"open\|forced_open"})` | stat o state timeline; alerta cuando sea `> 0` durante unos minutos |
| Transiciones del breaker | `increase(r4c_circuitbreaker_transitions_total{to="open"}[1h])` | un breaker inestable se abre muchas veces |
| Retries por segundo | `sum by (instance) (rate(r4c_retry_retries_total[5m]))` | compáralo con tu tasa de requests |
| Abandonos por motivo | `sum by (instance, reason) (increase(r4c_retry_gave_up_total[1h]))` | `not_retryable` incluye las llamadas detenidas por un breaker abierto |
| Tasa de fallos | `r4c_circuitbreaker_failure_rate_percent` | huecos hasta `minimumCalls` |
| Saturación del bulkhead | `r4c_bulkhead_concurrent_calls / r4c_bulkhead_max_concurrent` | 1 significa lleno; grafica también `r4c_bulkhead_waiting_calls` |
| Timeouts por segundo | `sum by (instance) (rate(r4c_timeout_timed_out_total[5m]))` | |
| Fallbacks por segundo | `sum by (instance) (rate(r4c_fallback_applied_total[5m]))` | la proporción de respuestas degradadas |

## Registrar transiciones en logs :id=logging

Un listener que registra cada cambio de estado del breaker a través de `System.Logger` (que llega a tu backend de logging mediante su puente para el JDK, por ejemplo `slf4j-jdk-platform-logging` de SLF4J):

<!-- snippet: observability-logging -->
```java
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import java.lang.System.Logger.Level;

/** Logs every circuit breaker state change: a warning when a breaker opens, info otherwise. */
public final class BreakerTransitionLogger implements EventListener<CircuitBreakerEvent.StateTransition> {

    private final System.Logger logger;

    public BreakerTransitionLogger(System.Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onEvent(CircuitBreakerEvent.StateTransition t) {
        boolean opened = t.to() == CircuitBreakerState.OPEN || t.to() == CircuitBreakerState.FORCED_OPEN;
        logger.log(opened ? Level.WARNING : Level.INFO, "Circuit breaker {0}: {1} -> {2}",
                t.instance(), t.from(), t.to());
    }
}
```

<!-- snippet: observability-logging-wiring -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(CircuitBreakerEvent.StateTransition.class, new BreakerTransitionLogger(logger))
        .build();
```

Registra en logs las transiciones y los abandonos, no cada `Retrying` ni cada rechazo: durante un incidente pueden llegar al ritmo de tu tráfico. Cuéntalos en su lugar.

## Health checks :id=health

Un check construido sobre el snapshot, para un endpoint de readiness o de diagnóstico:

<!-- snippet: observability-health -->
```java
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import java.util.List;

/** A readiness-style check: degraded while any circuit breaker rejects calls. */
public record ResilienceHealth(boolean healthy, List<String> openBreakers) {

    public static ResilienceHealth of(ResilienceRegistry registry) {
        List<String> open = registry.snapshot().circuitBreakers().stream()
                .filter(b -> b.state() == CircuitBreakerState.OPEN || b.state() == CircuitBreakerState.FORCED_OPEN)
                .map(CircuitBreakerSnapshot::instance)
                .toList();
        return new ResilienceHealth(open.isEmpty(), open);
    }
}
```

<!-- snippet: observability-health-use -->
```java
ResilienceHealth health = ResilienceHealth.of(registry);
if (!health.healthy()) {
    // report "degraded" with health.openBreakers() on your readiness endpoint
}
```

> [!WARNING]
> No conectes un breaker abierto a un probe de **liveness**: reiniciar el servicio no arregla la dependencia que protege el breaker, y todas las instancias se reiniciarían a la vez. Repórtalo como degradado, o en un endpoint de readiness solo si desviar el tráfico realmente ayuda.
