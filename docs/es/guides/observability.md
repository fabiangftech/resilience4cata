# Observabilidad

Ve lo que hacen las políticas a través de eventos, lee su estado a través de snapshots y handles, y construye métricas, logs y health checks sobre ambos.

resilience4cata te da dos piezas básicas:

1. **Eventos**: un record por cada cosa que hace una política aparte de dejar pasar una llamada (un retry, una transición del breaker, un rechazo, un timeout, un fallback), entregado a los listeners que registras en el builder del registry.
2. **Snapshots y handles**: el estado actual de cada instancia, leído cuando lo pides.

> [!TIP]
> Para métricas, agrega el [módulo de Micrometer](micrometer.md) o el [módulo de OpenTelemetry](opentelemetry.md): tres líneas de configuración te dan las [métricas de abajo](#metric-names), construidas sobre estas dos piezas. Esta página describe las piezas en sí, para logs, health checks u otra librería de métricas.

## Eventos :id=events

Cada evento implementa la interfaz sellada `dev.resilience4cata.event.ResilienceEvent`, con `instance()` (el id de la instancia: el `name`, el id derivado `pkg.Type#method(java.lang.String,int)` o un id de pipeline) y `nanoTime()` (una lectura del reloj del registry: `System.nanoTime()`, o el [reloj manual](testing.md) en los tests). Cada política tiene su propia interfaz sellada de records:

| Política | Record del evento | Campos (después de `instance`, `nanoTime`) | Se publica cuando |
|---|---|---|---|
| Circuit breaker | `CircuitBreakerEvent.StateTransition` | `CircuitBreakerState from`, `CircuitBreakerState to` | el estado cambia, exactamente una vez por cambio, incluidos `forceOpen()`, `disable()` y `reset()` |
| Circuit breaker | `CircuitBreakerEvent.CallNotPermitted` | `CircuitBreakerState state` | una llamada es rechazada (`OPEN`, `FORCED_OPEN`, o `HALF_OPEN` con todas las pruebas tomadas) |
| Retry | `RetryEvent.Retrying` | `int attempt`, `Duration delay`, `@Nullable Throwable failure` | un intento falló y se ejecutará otro después de `delay`; `failure` es `null` cuando un [classifier](classifiers.md) rechazó un resultado |
| Retry | `RetryEvent.GaveUp` | `int attempts`, `RetryEvent.Reason reason`, `@Nullable Throwable failure` | el retry se detiene sin un éxito: `MAX_ATTEMPTS`, `MAX_DURATION`, `NOT_RETRYABLE` (el fallo no está en `retryOn`, está en `abortOn`, o es la `CallNotPermittedException` de un breaker abierto), `INTERRUPTED`, `CLASSIFIER_FAILED`, `BUDGET_EXHAUSTED` (el [presupuesto de reintentos](../policies/retry.md#presupuesto-de-reintentos) se agotó) |
| Hedge | `HedgeEvent.Hedged` | `int attempt`, `HedgeEvent.Reason reason` | un [hedge](../policies/hedge.md) inició el intento `attempt` (2 o más) junto a los demás: `DELAY` (sin resultado dentro de `delay`) o `FAILURE` (un intento falló con un fallo de `hedgeOn`) |
| Hedge | `HedgeEvent.Won` | `int attempt` | el intento `attempt` (2 o más) tuvo éxito primero y los demás se cancelaron; no se publica cuando gana el primer intento |
| Hedge | `HedgeEvent.Skipped` | `int attempt`, `HedgeEvent.SkipReason reason` | un hedge que habría empezado no empezó: `BUDGET_EXHAUSTED` (el presupuesto de hedges se agotó), `REJECTED` (una política interna rechazó un intento), `ABORTED` (un fallo de `abortOn`, o que no está en `hedgeOn`, terminó la llamada) |
| Hedge | `HedgeEvent.Failed` | `int attempts`, `Throwable failure` | la llamada con hedge falla con `failure` tras `attempts` intentos |
| Rate limiter | `RateLimiterEvent.Rejected` | ninguno | una llamada es rechazada con `RateLimitExceededException` |
| Timeout | `TimeoutEvent.TimedOut` | `Duration timeout` | una llamada excede su timeout |
| Bulkhead | `BulkheadEvent.Rejected` | `int maxConcurrent` | una llamada es rechazada con `BulkheadFullException` |
| Adaptive limit | `AdaptiveLimitEvent.Rejected` | `int limit` | una llamada es rechazada con `LimitExceededException` |
| Adaptive limit | `AdaptiveLimitEvent.LimitChanged` | `int from`, `int to`, `long windowLatencyNanos` | terminó una ventana y el [adaptive limit](../policies/adaptive-limit.md) movió su límite |
| Fallback | `FallbackEvent.Applied` | `Throwable failure` | un fallback está por ejecutarse para `failure`; no se publica cuando la falla se relanza porque ningún fallback la recibe (`skipOn`, o ningún método hermano acepta su tipo) |

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
        // everything: keep a default branch, new policies add event types
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

> [!IMPORTANT]
> **Las versiones nuevas agregan tipos de evento.** La jerarquía es sellada y crece en versiones menores (una política nueva trae su propia interfaz de eventos, como `HedgeEvent` en 0.4.0). Mantén una rama `default` en un `switch` sobre `ResilienceEvent` o sobre la interfaz de una política, o escucha los tipos de record que necesitas (`listen(RetryEvent.GaveUp.class, …)`), que los tipos nuevos nunca afectan. El código compilado contra una versión anterior que recibe un tipo desconocido lanza `MatchException` en el listener; como toda excepción de un listener, va al uncaught exception handler y nunca rompe la llamada.

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
| `AdaptiveLimitSnapshot` | `instance`, `algorithm`, `limit`, `inFlight`, `minLimit`, `maxLimit`, `windows` (terminadas hasta ahora), `windowLatency` (el promedio de la última ventana), `longTermLatency` (el promedio de largo plazo de Gradient2), y el método `availableSlots()` |
| `RateLimiterSnapshot` | `instance`, `availablePermits`, `permits` |
| `RetrySnapshot` | `instance`, `config` (el `RetryConfig` resuelto) |
| `HedgeSnapshot` | `instance`, `config` (el `HedgeConfig` resuelto), `budget` (`calls`, `hedges`, `allowedHedges` en la ventana del presupuesto; `null` sin presupuesto) |
| `TimeoutSnapshot` | `instance`, `config` (el `TimeoutConfig` resuelto) |

## Handles :id=handles

Los handles leen una instancia en vivo, y el del breaker además puede cambiar su estado:

| Método | Devuelve |
|---|---|
| `registry.circuitBreaker(id)` | `CircuitBreakerHandle`: `id()`, `state()`, `config()`, `snapshot()`, `forceOpen()`, `disable()`, `reset()` |
| `registry.circuitBreakers()` | todos los handles de breaker, ordenados por id |
| `registry.rateLimiter(id)` | `RateLimiterHandle`: `id()`, `config()`, `availablePermits()`, `snapshot()` |
| `registry.bulkhead(id)` | `BulkheadHandle`: `id()`, `config()`, `concurrentCalls()`, `waitingCalls()`, `snapshot()` |
| `registry.adaptiveLimit(id)` | `AdaptiveLimitHandle`: `id()`, `config()`, `limit()`, `inFlight()`, `snapshot()` |

Una instancia existe solo cuando su interfaz ya fue envuelta (`Resilient.of`, `Resilient.create`) o su `Pipeline` ya fue construido; antes de eso, o para un id que no existe, la búsqueda lanza `IllegalArgumentException` [R4C-REG-009](../errors.md#r4c-reg-009) con el id más cercano. Retry y timeout no tienen handle; su configuración está en el snapshot. No hay método de listado para bulkheads, rate limiters y adaptive limits: enuméralos a través de `registry.snapshot()`.

## Métricas :id=metrics

El [módulo de Micrometer](micrometer.md) (`resilience4cata-micrometer`) convierte los eventos en counters y los handles en gauges. Su página tiene la configuración, un scrape real de Prometheus, consultas de Grafana y consejos sobre cardinalidad. El [módulo de OpenTelemetry](opentelemetry.md) (`resilience4cata-opentelemetry`) reporta las mismas métricas, con los mismos nombres y etiquetas, a través de la API de métricas de OpenTelemetry, así que los dashboards funcionan con cualquiera de los dos.

### Nombres de métricas :id=metric-names

Los nombres van en minúsculas y separados por puntos, como recomienda Micrometer (Prometheus los muestra como `r4c_retry_retries_total`, etc.); cada meter tiene un tag `instance` (el id de instancia: los ids de instancia están fijos en tu código, así que el tag está acotado) y como mucho uno o dos tags más con un conjunto fijo de valores. Los valores de los tags van en minúsculas.

| Meter | Tipo | Tags | Origen |
|---|---|---|---|
| `r4c.calls.rejected` | counter | `instance`, `kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `CallNotPermitted`, `RateLimiterEvent.Rejected`, `AdaptiveLimitEvent.Rejected`, `BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | counter | `instance`, `from`, `to` (estados en minúsculas) | `StateTransition` |
| `r4c.retry.retries` | counter | `instance` | `Retrying` |
| `r4c.retry.gave.up` | counter | `instance`, `reason` (`Reason` en minúsculas) | `GaveUp` |
| `r4c.hedge.hedged` | counter | `instance`, `reason` = `delay` \| `failure` | `Hedged` |
| `r4c.hedge.won` | counter | `instance` | `Won` |
| `r4c.hedge.skipped` | counter | `instance`, `reason` = `budget_exhausted` \| `rejected` \| `aborted` | `Skipped` |
| `r4c.hedge.failed` | counter | `instance` | `Failed` |
| `r4c.adaptivelimit.limit.changes` | counter | `instance` | `LimitChanged` |
| `r4c.timeout.timed.out` | counter | `instance` | `TimedOut` |
| `r4c.fallback.applied` | counter | `instance` | `Applied` |
| `r4c.circuitbreaker.state` | gauge | `instance`, `state`: 1 para el estado actual, 0 para los demás | `state()` |
| `r4c.circuitbreaker.failure.rate`, `r4c.circuitbreaker.slow.call.rate` | gauge (porcentaje) | `instance` | `snapshot()`; NaN hasta que la ventana contenga `minimumCalls` llamadas |
| `r4c.bulkhead.concurrent.calls`, `r4c.bulkhead.waiting.calls`, `r4c.bulkhead.max.concurrent` | gauge | `instance` | `BulkheadHandle` |
| `r4c.ratelimiter.available.permits` | gauge | `instance` | `RateLimiterHandle` |
| `r4c.adaptivelimit.limit`, `r4c.adaptivelimit.concurrent.calls` | gauge | `instance` | `AdaptiveLimitHandle` |

### Otra librería de métricas :id=diy

Un puente hacia otra librería hace lo mismo que el módulo de Micrometer, con tres piezas de la API de core: un listener para los counters, los handles leídos al recolectar para los gauges, y `registry.onInstanceCreated(…)` para enterarse de las instancias creadas después de conectar el registry (las instancias se crean al envolver un servicio o construir un pipeline, nunca durante una llamada):

<!-- snippet: observability-diy -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        // counters: from events, on the thread that caused them
        .listen(RetryEvent.Retrying.class, e -> retries.merge(e.instance(), 1L, Long::sum))
        .build();
// gauges: one per instance, read at collection time; new instances are announced once each
registry.onInstanceCreated(() -> {
    for (CircuitBreakerHandle breaker : registry.circuitBreakers()) {
        gauges.putIfAbsent(breaker.id(), () -> breaker.state() == CircuitBreakerState.OPEN ? 1 : 0);
    }
});
```

`onInstanceCreated` ejecuta su acción una vez por cada instancia nueva, en el thread que la crea, después de que la instancia es visible en `snapshot()` y en las búsquedas de handles; devuelve un `Registration` cuyo `close()` la detiene. Los bulkheads y los rate limiters no tienen método de lista: búscalos en `registry.snapshot()`. Guarda tus counters en caché por instancia y valores de tags, para que un evento no registre un meter cada vez.

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
