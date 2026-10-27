# Métodos async

Un método que devuelve `CompletionStage<T>` o `CompletableFuture<T>` recibe la forma no bloqueante de cada política: ningún thread duerme, espera ni queda estacionado por él.

No hay una anotación `@Asynchronous`: decide el tipo de retorno. Se aplican las mismas anotaciones, atributos y claves de configuración.

## Una interfaz async

<!-- snippet: async-interface -->
```java
public interface TrackingClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @Fallback(method = "unknown")
    CompletableFuture<String> track(String parcel);

    default CompletionStage<String> unknown(String parcel, IOException failure) {
        return CompletableFuture.completedFuture("unknown (" + failure.getMessage() + ")");
    }

    @Timeout("100ms")
    CompletionStage<String> eta(String parcel);
}
```

<!-- snippet: async-call -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
TrackingClient tracking = Resilient.of(TrackingClient.class, http, registry);

CompletableFuture<String> status = tracking.track("p-1");   // returns at once: no thread sleeps between attempts
// status completes with "in transit" after two failed attempts
```

La llamada devuelve un future de inmediato. El primer intento corre en el thread de quien llama; cuando falla, el retry programa el siguiente intento en un timer después de su delay, y ese intento empieza en un thread virtual nuevo. Ningún thread queda bloqueado durante el delay.

## Qué cambia respecto de un método sincrónico

| Política | Método sincrónico | Método async |
|---|---|---|
| Retry | Duerme en el thread de quien llama entre intentos | Programa el siguiente intento; el future devuelto se completa cuando un intento tiene éxito o el retry se rinde |
| [Hedge](../policies/hedge.md) | No soportado (error de compilación [`R4C-HDG-001`](../errors.md#r4c-hdg-001)) | Vuelve a lanzar la llamada tras `delay` sin resultado, o enseguida tras una falla; el primer éxito completa el future y los demás intentos se cancelan |
| Circuit breaker | Registra la llamada cuando devuelve o lanza | Registra la llamada cuando su stage se completa |
| Rate limiter con `maxWait` | Duerme hasta que el permiso esté disponible | Empieza la llamada cuando el permiso está disponible, sin bloquear a quien llama |
| [Adaptive limit](../policies/adaptive-limit.md) | Conserva el lugar mientras el método corre, y mide su latencia | Conserva el lugar hasta que el stage se completa, y mide la latencia hasta esa finalización |
| Timeout | Interrumpe el thread de quien llama al vencer el plazo | Hace fallar el future devuelto con `CallTimeoutException` al vencer el plazo, y cancela el stage tardío si es un `Future` (a través de las políticas internas, que sueltan lo que tienen de inmediato: ver [cancelación](#cancelación)) |
| Bulkhead | Ocupa el lugar mientras corre el método; con `maxWait`, espera en un semáforo | Ocupa el lugar hasta que el stage se completa; con `maxWait`, encola la llamada sin bloquear un thread |
| Fallback | Devuelve un valor | Devuelve un valor o un stage |

Reglas que valen para todas las políticas:

- **Un método que lanza una excepción en lugar de devolver un stage** se trata como un stage fallido: las políticas ven la excepción y, si nada la recupera, el future devuelto falla con ella. Un método que devuelve `null` en lugar de un stage falla de la misma forma con una `NullPointerException`.
- **Las fallas se desenvuelven.** Un stage que falla con `CompletionException` o `ExecutionException` se juzga por su causa, así que `retryOn`, `recordOn`, los classifiers y las firmas de los fallbacks ven tu excepción (`IOException` en el ejemplo), no el envoltorio.
- **Los rechazos hacen fallar el future.** Un breaker abierto, un bulkhead lleno, un adaptive limit lleno o un rate limiter agotado hacen fallar el future devuelto con `CallNotPermittedException`, `BulkheadFullException`, `LimitExceededException` o `RateLimitExceededException`.
- **Los callbacks no corren en el timer.** Los reintentos diferidos, los hedges, las esperas del rate limiter y los timeouts se disparan en un timer de la librería, que pasa el trabajo a un thread virtual nuevo. Por eso los intentos posteriores al primero (un reintento, un hedge) corren sin los `ThreadLocal` ni el MDC de quien llama.

Una llamada que vence su timeout, del mismo test:

<!-- snippet: async-timeout -->
```java
CompletableFuture<String> eta = tracking.eta("p-1").toCompletableFuture();

assertThatThrownBy(() -> eta.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(CallTimeoutException.class);   // after 100 ms; no thread was blocked
```

Cuando haces `join()` o `get()` sobre el future, el JDK envuelve la falla: `join()` lanza `CompletionException` y `get()` lanza `ExecutionException`, con la excepción de la política como causa.

## Cancelación

Cancelar el future que devuelve un método async (con `cancel(...)`, o con `completeExceptionally` y una `CancellationException`) cancela la llamada que hay dentro, a través de todas las capas: cada política pasa la cancelación al stage que está esperando, hasta llegar al stage que devolvió tu método, que se cancela si es un `Future`. Qué hace cada política:

| Política | Al cancelar |
|---|---|
| Retry | No empieza más intentos: un backoff pendiente se cancela, y el intento en curso también |
| Hedge | No empieza más intentos: la espera pendiente se cancela, y todos los intentos en curso también. Un hedge cancela también a sus propios perdedores de la misma forma, con una `HedgeCancelledException` |
| Circuit breaker | No registra nada, como una llamada ignorada: un permiso de half-open se devuelve |
| Rate limiter con `maxWait` | Una llamada que todavía espera su permiso nunca empieza, y el permiso se devuelve (como cuando se interrumpe una espera sincrónica). Una llamada que ya empezó conserva su permiso |
| Adaptive limit | Libera el lugar en cuanto el stage termina, sin muestra: una llamada cancelada no dice nada de la dependencia |
| Timeout | Cancela su plazo: no hay `CallTimeoutException` ni evento `TimedOut` |
| Bulkhead | Libera el lugar en cuanto termina el stage, que para un `CompletableFuture` es de inmediato. Una llamada todavía encolada (`maxWait`) sale de la cola y nunca empieza |
| Fallback | No corre: nadie espera un resultado alternativo |

Una cancelación no publica eventos: ni `GaveUp`, ni `Retry`, ni falla, ni rechazo. Las capas internas ya soltaron lo que tenían antes de que corran los callbacks que adjuntaste al future, así que un callback puede empezar una llamada nueva en el lugar que se liberó.

Para tener en cuenta:

- **La cancelación llega al stage, no al trabajo detrás de él.** Cancelar un `CompletableFuture` lo completa; no interrumpe el código que lo habría completado. El bulkhead libera su lugar cuando termina el stage, así que si tu stage no es un `Future`, o ignora `cancel`, el lugar sigue ocupado (y el breaker registra el resultado tardío) hasta que el stage se complete.
- **Cancela el future que devolvió el método.** Un future derivado de él (`thenApply`, `whenComplete`, `copy()` y similares) es un `CompletableFuture` nuevo: cancelarlo no llega a la llamada, como en el JDK.
- **La cancelación y la finalización compiten de forma segura.** Si la llamada termina en el mismo momento, gana la que llegue primero; los lugares y permisos se liberan exactamente una vez en cualquier caso.
- `Pipeline.executeAsync` se comporta igual.

## Fallbacks

Un fallback hermano de un método async devuelve el tipo del valor `T` o un `CompletionStage<T>` (en el ejemplo, `unknown` devuelve un stage). Una clase handler implementa `FallbackHandler<T>` o `FallbackHandler<CompletionStage<T>>`. El fallback corre cuando el retry se rinde, y también ante rechazos y timeouts, igual que su forma sincrónica. Ver [fallback](../policies/fallback.md).

## Stages con wildcard

El tipo de retorno puede ser `CompletionStage<? extends X>` o `CompletableFuture<? extends X>`; `?` solo significa `? extends Object`. Las políticas trabajan entonces con `X`: los fallbacks devuelven un `X` (o un stage de uno), y los classifiers deben aceptar `X`.

<!-- snippet: async-wildcard -->
```java
public interface ParcelClient {

    @Retry(maxAttempts = 2, delay = "10ms")
    @Fallback(method = "noWeight")
    CompletionStage<? extends Number> weight(String parcel);

    default Integer noWeight(String parcel, Exception failure) {
        return 0;
    }
}
```

## Qué no está soportado

- **Otros tipos de stage son errores de compilación** ([`R4C-ASY-001`](../errors.md#r4c-asy-001)): `CompletionStage` crudo, `? super X`, y los subtipos de `CompletionStage` distintos de `CompletableFuture`.
- **Los tipos reactivos son errores de compilación** ([`R4C-ASY-002`](../errors.md#r4c-asy-002)): `Flow.Publisher` y el `Publisher` de Reactive Streams (`Mono` y `Flux` de Reactor), `Uni` y `Multi` de Mutiny, y los tipos de RxJava. Solo construyen un pipeline que se ejecuta al suscribirse, así que las políticas protegerían su construcción, no el trabajo. Convierte a un `CompletionStage` en la implementación (`uni.subscribeAsCompletionStage()`, `mono.toFuture()`).
- **Otros tipos asíncronos se tratan como sincrónicos.** Un método que devuelve `java.util.concurrent.Future` o cualquier otra cosa se protege como cualquier método sincrónico: las políticas ven solo la llamada que *devuelve* el objeto, no el trabajo que representa.

> [!WARNING]
> Si un método devuelve un `Future`, ni el timeout ni el retry se aplican al resultado. Devuelve un `CompletableFuture`, o envuelve el trabajo en uno.

## Costo

Las llamadas async asignan memoria: cada capa crea futures y pasa por callbacks de `CompletableFuture`, que es el precio de no bloquear threads. Las llamadas sincrónicas no se ven afectadas.

> [!MEASURED]
> Un timeout async con un stage pendiente cuesta 152 ns y 192 B en un thread, contra 598 ns y 240 B de Resilience4j 2.4.0 (AWS Graviton3 `c7g.xlarge`). [Benchmarks](../benchmarks.md#cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3)

## Ver también

- `Pipeline.executeAsync(...)` da el mismo comportamiento sin anotaciones ([Pipeline](pipeline.md)).
- Con un `ManualClock`, los delays y timeouts async corren en el thread que avanza el reloj ([tests](testing.md)).
