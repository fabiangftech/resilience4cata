# Políticas

resilience4cata tiene seis políticas, una anotación cada una; ponlas en un método o en toda una interfaz, solas o apiladas, y el processor siempre las anida en el mismo orden.

| Política | Anotación | Qué hace | Lanza cuando rechaza |
|---|---|---|---|
| [Fallback](fallback.md) | `@Fallback` | Responde con un resultado alternativo cuando la llamada falla o es rechazada | — |
| [Retry](retry.md) | `@Retry` | Vuelve a llamar después de una falla, con backoff y jitter | — (relanza la última falla) |
| [Circuit breaker](circuit-breaker.md) | `@CircuitBreaker` | Deja de llamar a una dependencia que falla una y otra vez, y luego la prueba | `CallNotPermittedException` |
| [Rate limiter](rate-limiter.md) | `@RateLimiter` | Deja pasar como máximo N llamadas por período | `RateLimitExceededException` |
| [Timeout](timeout.md) | `@Timeout` | Interrumpe una llamada sync, o hace fallar una async, que tarda demasiado | `CallTimeoutException` |
| [Bulkhead](bulkhead.md) | `@Bulkhead` | Limita cuántas llamadas corren al mismo tiempo | `BulkheadFullException` |

Todas las políticas funcionan con métodos sincrónicos y con métodos que devuelven `CompletionStage<T>` o `CompletableFuture<T>` (ver [Métodos async](../guides/async.md)). Las excepciones están en el paquete `dev.resilience4cata`, extienden `RuntimeException`, no tienen stack trace y llevan el `instanceId()` de la política.

## ¿Qué política necesito?

| Problema | Política | Empieza con |
|---|---|---|
| Las llamadas fallan de vez en cuando, y reintentar suele funcionar | Retry | `@Retry(maxAttempts = 3, delay = "200ms")` |
| Una dependencia se cae por minutos, y las llamadas se acumulan esperándola | Circuit breaker (dentro de un retry) | `@CircuitBreaker(name = "…")` |
| Una dependencia se cuelga, y tus threads se cuelgan con ella | Timeout | `@Timeout("2s")` |
| Una API permite 10 requests por segundo | Rate limiter | `@RateLimiter(permits = 10, period = "1s")` |
| Una dependencia lenta agota todos tus threads o conexiones | Bulkhead | `@Bulkhead(maxConcurrent = 10)` |
| Existe una respuesta degradada razonable (caché, valor por defecto, "intenta más tarde") | Fallback | `@Fallback(method = "…")` |
| La falla está en el resultado, no en una excepción (HTTP `503`) | Retry / circuit breaker con un [classifier](../guides/classifiers.md) | `@Retry(classifier = …)` |

Un stack habitual para una llamada remota es un timeout, un circuit breaker y un retry, más un fallback donde exista una respuesta degradada.

## El orden es fijo

De la más externa a la más interna:

```mermaid
flowchart LR
    caller(["quien llama"]) --> F["Fallback"] --> R["Retry"] --> C["CircuitBreaker"] --> L["RateLimiter"] --> T["Timeout"] --> B["Bulkhead"] --> M(["tu método"])
```

El orden en que escribes las anotaciones no importa, y el orden no se puede cambiar. Está elegido para que cada política vea lo que debe ver:

- **Fallback es la más externa**, así que ve el resultado final: la última falla cuando el retry se rinde, y cada rechazo de las políticas de adentro.
- **Retry está fuera del circuit breaker**, así que el breaker registra cada intento, y un breaker abierto detiene los reintentos (abajo).
- **El circuit breaker está fuera del rate limiter**, así que las llamadas que rechaza no gastan permisos.
- **El timeout está dentro del retry**, así que se aplica por intento; `@Retry(maxDuration = …)` acota toda la operación.
- **El bulkhead es la más interna**, así que un slot se ocupa solo mientras el método realmente corre: no durante el backoff de un retry, no mientras se espera un permiso.

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

- `RateLimitExceededException`, `CallTimeoutException` y `BulkheadFullException` las **reintenta** un `@Retry` externo (después de su espera) y las **registra como fallas** un `@CircuitBreaker` externo. Tu propio rate limiter o bulkhead puede entonces abrir tu breaker. Ponlas en el `ignoreOn` del breaker o en el `abortOn` del retry cuando no es lo que quieres.
- Los `Error` no se reintentan ni se registran como fallas del breaker (`retryOn` y `recordOn` valen `Exception` por defecto), pero el `applyOn = Throwable` por defecto del fallback sí los atrapa, salvo que su parámetro de excepción sea más estrecho.

## Uso programático

El código que no se puede anotar obtiene las mismas políticas, en el mismo orden, con un [`Pipeline`](../guides/pipeline.md). Cada página de política muestra su equivalente con pipeline.

## Cuánto cuesta

> [!MEASURED]
> En AWS Graviton3 (`c7g.xlarge`, JDK 21), una llamada exitosa a través de retry, circuit breaker, rate limiter y bulkhead cuesta 61,6 ns con código generado, contra 261 ns y 328 B de Resilience4j 2.4.0; las seis políticas cuestan 76,3 ns (Resilience4j no tiene timeout en el thread de quien llama para comparar). El código generado no asigna memoria en esos caminos. [Benchmarks: cierre del MVP](../benchmarks.md#cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3)
