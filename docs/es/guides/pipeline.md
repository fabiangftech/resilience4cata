# API Pipeline

`Pipeline` aplica las mismas políticas sin anotaciones, para código que no puedes anotar o políticas que eliges en tiempo de ejecución.

## Cuándo usarla

| Usa anotaciones cuando | Usa un `Pipeline` cuando |
|---|---|
| La interfaz o la clase son tuyas | El código es una clase de terceros, una lambda o un script |
| Las políticas se conocen en tiempo de compilación | Las políticas se eligen en tiempo de ejecución |
| Quieres que cada atributo y cada fallback se verifiquen al compilar | Te bastan las verificaciones en tiempo de ejecución cuando corre `build()` |
| Cada nanosegundo cuenta | Unos nanosegundos más por llamada no importan |

## Un primer pipeline

Construye un pipeline una vez, a partir del registry, y reutilízalo. Es inmutable y thread-safe.

<!-- snippet: pipeline-minimal -->
```java
Pipeline<String> geocode = Pipeline.builder(registry, "geocoder", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(1)))
        .fallback(failure -> "unknown")
        .build();

String city = geocode.execute(() -> legacyGeocoder.city("10115"));   // "Berlin", after one retry
```

`execute` ejecuta la llamada a través de las políticas y declara la excepción checked de la llamada (aquí `IOException`), así que manejas lo que la propia llamada lanza. Cuando nada se recupera, lanza la última falla de la llamada o un rechazo: `CallNotPermittedException`, `RateLimitExceededException`, `CallTimeoutException` o `BulkheadFullException`.

## Todos los pasos

<!-- snippet: pipeline-full -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retry("external", r -> r.maxAttempts(4))
        .rateLimiter("external", rl -> rl.permits(100))
        .build();

Pipeline<Receipt> charge = Pipeline.builder(registry, "payments", Receipt.class)
        .retry("external")                                   // named config
        .circuitBreaker(cb -> cb.failureRateThreshold(25))   // defaults + overrides
        .rateLimiter("external", rl -> rl.permits(50))       // named config + overrides
        .timeout(t -> t.timeout(Duration.ofSeconds(2)))
        .bulkhead()                                          // defaults
        .fallbackHandler(context -> Receipt.pending(context.attempts()))
        .build();

Receipt receipt = charge.execute(() -> client.charge(order));            // throws what charge throws
CompletableFuture<Receipt> later = charge.executeAsync(() -> client.chargeAsync(order));
charge.run(() -> client.cancel(order));                                  // a call with no result
```

- **Políticas:** `retry`, `circuitBreaker`, `rateLimiter`, `timeout` y `bulkhead`, cada una a lo sumo una vez ([`R4C-RT-012`](../errors.md#r4c-rt-012)). Cada una acepta ningún argumento (los valores por defecto), una configuración con nombre, un `Consumer` del builder de configuración de la política (overrides sobre los valores por defecto), o ambos (overrides sobre la configuración con nombre). Los builders tienen los mismos atributos que las anotaciones; por ejemplo, la duración del timeout es `t.timeout(Duration)`.
- **Orden:** las políticas se anidan como con anotaciones, `Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → llamada`, sin importar en qué orden llames a los métodos del builder. Cada intento del retry pasa por el breaker y tiene su propio timeout.
- **Fallback:** `fallback(failure -> value)` o `fallbackHandler(context -> value)`; el `FallbackContext` del handler da la falla y `attempts()` (no tiene argumentos: `argumentCount()` es 0). `fallbackApplyOn(List.of(...))` y `fallbackSkipOn(List.of(...))` eligen las fallas que maneja; sin fallback son un error ([`R4C-RT-013`](../errors.md#r4c-rt-013)).
- **Fallback async:** `fallbackAsync(...)` y `fallbackAsyncHandler(...)` devuelven un stage, para `executeAsync`. Con los dos tipos, cada modo usa el suyo; con solo un fallback async, `execute` y `run` esperan su stage.
- **Classifiers:** `classifier(...)` para el retry y el breaker a la vez, o `retryClassifier(...)` y `circuitBreakerClassifier(...)` por separado, más `onExhaustedResult(Pipeline.ExhaustedResult.THROW)`; ver [classifiers](classifiers.md).

Tres formas de llamar:

| Método | Para |
|---|---|
| `T execute(PipelineCall<? extends T, X>) throws X` | Una llamada que devuelve un valor |
| `void run(PipelineRunnable<X>) throws X` | Una llamada sin resultado (un classifier ve `null`; el resultado de un fallback se descarta) |
| `CompletableFuture<T> executeAsync(PipelineCall<? extends CompletionStage<T>, ?>)` | Una llamada que devuelve un stage: la forma no bloqueante de cada política, como en los [métodos async](async.md) |

`build()` verifica las combinaciones que no pueden funcionar: un classifier sin retry ni breaker ([`R4C-RT-014`](../errors.md#r4c-rt-014)), un classifier de retry sin retry ([`R4C-RT-015`](../errors.md#r4c-rt-015)), `onExhaustedResult` sin classifier de retry ([`R4C-RT-016`](../errors.md#r4c-rt-016)), un classifier de breaker sin breaker ([`R4C-RT-017`](../errors.md#r4c-rt-017)). Un id en blanco es [`R4C-RT-011`](../errors.md#r4c-rt-011).

## El id es el id de instancia

El id que se pasa a `Pipeline.builder` nombra las instancias de las políticas del pipeline, como lo hace el atributo `name` en las anotaciones:

<!-- snippet: pipeline-handle -->
```java
Pipeline<String> search = Pipeline.builder(registry, "search", String.class)
        .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2))
        .build();

CircuitBreakerHandle breaker = registry.circuitBreaker("search");   // the pipeline's id is the instance id
breaker.forceOpen();                                                // every call is now rejected
```

Así, `registry.circuitBreaker(id)`, `rateLimiter(id)` y `bulkhead(id)` las encuentran, los eventos llevan el id como `instance()`, y la configuración externa las ajusta con `instances.<id>.*`. Los pipelines construidos con el mismo id comparten sus instancias, y también las anotaciones cuyo `name` es ese id; todos deben resolver a la misma configuración, o `build()` falla con [`R4C-REG-008`](../errors.md#r4c-reg-008).

Un fallback cuya función lanza una excepción checked que la llamada no declara llega a quien llama envuelto en `UndeclaredThrowableException`.

## Cuánto cuesta

El camino exitoso sincrónico no asigna memoria más allá de tu propia lambda. Sin embargo, una sola clase `Pipeline` atiende todos los puntos de llamada, así que en una aplicación con muchos pipelines su única llamada a tu lambda se vuelve megamórfica y más lenta; el código generado tiene un punto de llamada por método y no tiene este costo.

> [!MEASURED]
> Retry más circuit breaker en un thread: 2.9 ns con un `Pipeline`, 2.3 ns con anotaciones, 175 ns y 288 B con Resilience4j 2.4.0. Después de que otros cinco pipelines pasaron por la misma JVM, el pipeline mide 7.9 ns (AWS Graviton3 `c7g.xlarge`). [Benchmarks](../benchmarks.md#cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3)

Donde puedes anotar, las anotaciones siguen siendo la opción más rápida.
