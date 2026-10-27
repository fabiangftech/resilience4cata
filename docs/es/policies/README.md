# Políticas

resilience4cata tiene ocho políticas, una anotación cada una; ponlas en un método o en toda una interfaz, solas o apiladas, y el processor las anida en un orden por defecto, que `@PolicyOrder` puede cambiar.

| Política | Anotación | Qué hace | Lanza cuando rechaza |
|---|---|---|---|
| [Fallback](fallback.md) | `@Fallback` | Responde con un resultado alternativo cuando la llamada falla o es rechazada | — |
| [Retry](retry.md) | `@Retry` | Vuelve a llamar después de una falla, con backoff y jitter | — (relanza la última falla) |
| [Hedge](hedge.md) | `@Hedge` | Vuelve a lanzar la misma llamada mientras la primera va lenta, y se queda con el primer éxito (solo async y llamadas idempotentes) | — (relanza la última falla) |
| [Circuit breaker](circuit-breaker.md) | `@CircuitBreaker` | Deja de llamar a una dependencia que falla una y otra vez, y luego la prueba | `CallNotPermittedException` |
| [Rate limiter](rate-limiter.md) | `@RateLimiter` | Deja pasar como máximo N llamadas por período | `RateLimitExceededException` |
| [Adaptive limit](adaptive-limit.md) | `@AdaptiveLimit` | Limita cuántas llamadas corren al mismo tiempo, con un límite que ajusta según su latencia | `LimitExceededException` |
| [Timeout](timeout.md) | `@Timeout` | Interrumpe una llamada sync, o hace fallar una async, que tarda demasiado | `CallTimeoutException` |
| [Bulkhead](bulkhead.md) | `@Bulkhead` | Limita cuántas llamadas corren al mismo tiempo | `BulkheadFullException` |

Todas las políticas salvo Hedge funcionan con métodos sincrónicos y con métodos que devuelven `CompletionStage<T>` o `CompletableFuture<T>` (ver [Métodos async](../guides/async.md)); Hedge necesita un método asincrónico, y ocupa el lugar de Retry (las dos no se pueden combinar en un método). Las excepciones están en el paquete `dev.resilience4cata`, extienden `RuntimeException`, no tienen stack trace y llevan el `instanceId()` de la política.

## ¿Qué política necesito?

| Problema | Política | Empieza con |
|---|---|---|
| Las llamadas fallan de vez en cuando, y reintentar suele funcionar | Retry | `@Retry(maxAttempts = 3, delay = "200ms")` |
| Una dependencia se cae por minutos, y las llamadas se acumulan esperándola | Circuit breaker (dentro de un retry) | `@CircuitBreaker(name = "…")` |
| Una dependencia se cuelga, y tus threads se cuelgan con ella | Timeout | `@Timeout("2s")` |
| Una API permite 10 requests por segundo | Rate limiter | `@RateLimiter(permits = 10, period = "1s")` |
| Una dependencia lenta agota todos tus threads o conexiones | Bulkhead | `@Bulkhead(maxConcurrent = 10)` |
| Lo mismo, pero no sabes cuántas llamadas concurrentes aguanta, o eso cambia | Adaptive limit | `@AdaptiveLimit` con un `@Timeout` adentro |
| El p99 está muy por encima del p50 en una lectura idempotente de backends replicados | Hedge | `@Hedge(delay = "50ms")` en un método que devuelve `CompletableFuture` |
| Existe una respuesta degradada razonable (caché, valor por defecto, "intenta más tarde") | Fallback | `@Fallback(method = "…")` |
| La falla está en el resultado, no en una excepción (HTTP `503`) | Retry / circuit breaker con un [classifier](../guides/classifiers.md) | `@Retry(classifier = …)` |

Un stack habitual para una llamada remota es un timeout, un circuit breaker y un retry, más un fallback donde exista una respuesta degradada.

## El orden por defecto :id=default-order

De la más externa a la más interna:

```mermaid
flowchart LR
    caller(["quien llama"]) --> F["Fallback"] --> R["Retry | Hedge"] --> C["CircuitBreaker"] --> L["RateLimiter"] --> A["AdaptiveLimit"] --> T["Timeout"] --> B["Bulkhead"] --> M(["tu método"])
```

El orden en que escribes las anotaciones no importa. El orden por defecto está elegido para que cada política vea lo que debe ver:

- **Fallback es la más externa**, así que ve el resultado final: la última falla cuando el retry se rinde, y cada rechazo de las políticas de adentro.
- **Retry está fuera del circuit breaker**, así que el breaker registra cada intento, y un breaker abierto detiene los reintentos (abajo). **Hedge** ocupa el mismo lugar, por las mismas razones: cada intento cubierto pasa por su cuenta por el breaker, el limiter, el timeout y el bulkhead.
- **El circuit breaker está fuera del rate limiter**, así que las llamadas que rechaza no gastan permisos.
- **El adaptive limit está dentro del rate limiter y fuera del timeout y del bulkhead**, así que un timeout o un bulkhead lleno adentro es la señal de sobrecarga que baja su límite.
- **El timeout está dentro del retry**, así que se aplica por intento; `@Retry(maxDuration = …)` acota toda la operación.
- **El bulkhead es la más interna**, así que un slot se ocupa solo mientras el método realmente corre: no durante el backoff de un retry, no mientras se espera un permiso.

## Cambiar el orden :id=changing-the-order

`@PolicyOrder` nombra las capas que se mueven, de la más exterior a la más interior. Se mueven juntas, en ese orden, al
lugar de la más exterior de ellas; las capas que no nombras conservan su orden por defecto alrededor, y las que el
método no tiene se ignoran. Aquí el timeout envuelve todo el bucle del retry, así que un segundo acota todos los
intentos juntos:

<!-- snippet: policies-order -->
```java
import static dev.resilience4cata.annotation.Layer.RETRY;
import static dev.resilience4cata.annotation.Layer.TIMEOUT;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportApi {

    @PolicyOrder({TIMEOUT, RETRY})                // Fallback -> Timeout -> Retry -> ...: one deadline for every attempt
    @Timeout("1s")
    @Retry(maxAttempts = 10, delay = "300ms", jitter = Jitter.NONE)
    String report(String id);
}
```

Los cambios habituales:

- **`{TIMEOUT, RETRY}`**: un único plazo para toda la operación, como arriba. Con el orden por defecto cada intento
  tiene su propio timeout (un `@Retry(maxDuration = …)` también acota toda la operación, sin reordenar).
- **`{CIRCUIT_BREAKER, RETRY}`**: el breaker registra toda la operación una vez, no cada intento, y un breaker abierto
  rechaza la llamada antes de que empiece el retry.
- **`{BULKHEAD, RETRY}`**: el bulkhead ocupa un slot durante toda la operación, pausas del backoff incluidas, así que
  limita operaciones en curso en lugar de llamadas en vuelo.

Lo que conviene saber:

- **El fallback siempre es la capa más exterior**, así que sigue viendo el resultado final.
  `FallbackContext.attempts()` cuenta los intentos del retry solo mientras el retry es la capa justo dentro del
  fallback, como por defecto; con otra capa en medio informa `1`.
- **Cualquier orden compila.** Algunos cambian la semántica de formas que vale la pena revisar: un rate limiter fuera
  del breaker gasta permisos en llamadas que el breaker rechaza, y un bulkhead fuera de un timeout ocupa su slot hasta
  que el timeout salta.
- **Dónde va:** en un método, en un tipo (para todos sus métodos) o en un [bundle](../guides/bundles.md); el del método
  gana al del tipo, y una anotación directa al de un bundle. Nombrar una capa dos veces es
  [`R4C-ORD-001`](../errors.md#r4c-ord-001).
- **Se decide al compilar**, como el resto del código generado, así que no puede venir de la configuración. Un
  [`Pipeline`](../guides/pipeline.md) siempre usa el orden por defecto.

## Combinarlas

<!-- compare:start -->
<!-- snippet: policies-stack -->
```java
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface CheckoutApi {

    @Bulkhead(maxConcurrent = 20)                       // the order you write them in does not matter:
    @Timeout("2s")                                      // they always nest as
    @RateLimiter(permits = 50, period = "1s")           // Fallback → Retry → CircuitBreaker
    @CircuitBreaker(name = "checkout")                  //   → RateLimiter → Timeout → Bulkhead → method
    @Retry(maxAttempts = 3, delay = "200ms")
    @Fallback(method = "later")
    String placeOrder(String cartId);

    default String later(String cartId, Exception failure) {
        return "queued " + cartId;
    }
}
```
<!-- not-compiled: generated code, abridged -->
```java
public final class CheckoutApi$Resilient implements CheckoutApi {
    // … one final field per policy, created from the registry in the constructor

    @Override
    public java.lang.String placeOrder(java.lang.String p0) {           // Fallback + Retry
        final dev.resilience4cata.generated.RetryPolicy r4c$retry = this.retry0;
        final long r4c$start = r4c$retry.start();
        int r4c$attempts = 0;
        while (true) {
            try {
                return this.r4c$circuitBreaker0(p0);
            } catch (java.lang.Throwable r4c$e) {
                final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);
                if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {
                    if (!this.fallback0.handles(r4c$e)) {
                        throw r4c$e;
                    }
                    if (r4c$e instanceof java.lang.Exception r4c$f0) {
                        return this.delegate.later(p0, r4c$f0);
                    }
                    throw r4c$e;
                }
            }
        }
    }

    private java.lang.String r4c$circuitBreaker0(java.lang.String p0) {
        // … acquire a permit, then:
            final java.lang.String r4c$result = this.r4c$rateLimiter0(p0);
        // …
    }

    private java.lang.String r4c$rateLimiter0(java.lang.String p0) {
        // … take a permit, then:
        return this.r4c$timeout0(p0);
    }

    private java.lang.String r4c$timeout0(java.lang.String p0) {
        // … start timing, then:
            r4c$result = this.r4c$bulkhead0(p0);
        // …
    }

    private java.lang.String r4c$bulkhead0(java.lang.String p0) {
        // … take a slot, then:
            return this.delegate.placeOrder(p0);
        // …
    }
}
```
<!-- compare:end -->

Cada política se convierte en una capa de código lineal: el método público tiene la capa más externa, y cada capa interna es un método privado que llama a la siguiente, hasta tu implementación. Entre métodos no se comparte nada salvo lo que compartes por `name`.

- Las anotaciones también pueden ir en la interfaz misma, para todos sus métodos, y agruparse en anotaciones propias; ver [Bundles](../guides/bundles.md).
- Cada política lee su configuración una sola vez, al envolver la interfaz, de los valores por defecto incorporados, `defaults.*`, las configuraciones con nombre y la anotación, en ese orden; ver [Configuración](../guides/configuration.md).
- Breakers, rate limiters y bulkheads con el mismo `name` comparten una instancia, y también los pipelines con ese id. Los retries y timeouts no guardan estado entre llamadas, salvo un retry con [presupuesto de reintentos](retry.md#presupuesto-de-reintentos), que comparte su presupuesto de la misma forma.

### Un breaker abierto detiene los reintentos

Un retry nunca reintenta `CallNotPermittedException`, la excepción que lanza un circuit breaker abierto, diga lo que diga su `abortOn`. Así, una vez que el breaker abre, el retry se rinde en el siguiente intento en lugar de esperar todo su backoff, y responde el fallback:

<!-- snippet: policies-open-breaker -->
```java
CheckoutApi checkout = Resilient.of(CheckoutApi.class, cartId -> {
    calls.incrementAndGet();
    return "placed " + cartId;
}, registry);
registry.circuitBreaker("checkout").forceOpen();

assertThat(checkout.placeOrder("cart-1")).isEqualTo("queued cart-1");  // the fallback answered
assertThat(calls).hasValue(0);                  // the breaker rejected the first attempt...
assertThat(clock.elapsed()).isZero();           // ...and the retry did not wait to try again
```

### Los rechazos son fallas para las capas externas

Un rechazo de una política interna es, por defecto, una `Exception` como cualquier otra para las capas que están afuera:

- `RateLimitExceededException`, `LimitExceededException`, `CallTimeoutException` y `BulkheadFullException` las **reintenta** un `@Retry` externo (después de su espera) y las **registra como fallas** un `@CircuitBreaker` externo. Tu propio rate limiter, adaptive limit o bulkhead puede entonces abrir tu breaker. Ponlas en el `ignoreOn` del breaker o en el `abortOn` del retry cuando no es lo que quieres.
- Los `Error` no se reintentan ni se registran como fallas del breaker (`retryOn` y `recordOn` valen `Exception` por defecto), pero el `applyOn = Throwable` por defecto del fallback sí los atrapa, salvo que su parámetro de excepción sea más estrecho.

## Uso programático

El código que no se puede anotar obtiene las mismas políticas, en el orden por defecto, con un [`Pipeline`](../guides/pipeline.md). Cada página de política muestra su equivalente con pipeline.

## Cuánto cuesta

> [!MEASURED]
> En AWS Graviton3 (`c7g.xlarge`, JDK 21), una llamada exitosa a través de retry, circuit breaker, rate limiter y bulkhead cuesta 61,6 ns con código generado, contra 261 ns y 328 B de Resilience4j 2.4.0; las seis políticas cuestan 76,3 ns (Resilience4j no tiene timeout en el thread de quien llama para comparar). El código generado no asigna memoria en esos caminos. [Benchmarks: cierre del MVP](../benchmarks.md#cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3)
