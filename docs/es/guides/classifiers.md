# Classifiers

Un classifier decide, llamada por llamada, si un resultado o una excepción es un éxito, una falla o ninguna de las dos, para `@Retry` y `@CircuitBreaker`.

Las listas de excepciones (`retryOn`, `recordOn`) solo pueden mirar excepciones. Muchos clientes informan las fallas en el resultado: un cliente HTTP devuelve una respuesta con status `503`, una API de jobs devuelve `Status.FAILED`. Un classifier cubre ambos casos, en una sola clase.

## Escribir uno

Un classifier implementa `dev.resilience4cata.Classifier<T>`, donde `T` es el tipo de resultado del método (o el `T` de su `CompletionStage<T>`):

<!-- snippet: classifiers-classifier -->
```java
import dev.resilience4cata.Classifier;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

public final class StatusClassifier implements Classifier<Response> {

    @Override
    public Outcome classify(@Nullable Response response, @Nullable Throwable failure) {
        if (failure != null) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
        }
        int status = response.status();
        if (status >= 500 || status == 429) {
            return Outcome.FAILURE;     // retried, and counted by the breaker
        }
        return status == 404 ? Outcome.IGNORE : Outcome.SUCCESS;
    }
}
```

`classify(result, failure)` recibe exactamente un argumento con sentido: `failure` es `null` cuando la llamada terminó normalmente (y `result` es `null` para un método `void` o un resultado `null`). Devuelve un `Outcome`:

| Outcome | `@Retry` | `@CircuitBreaker` |
|---|---|---|
| `SUCCESS` | Se detiene y devuelve el resultado (o relanza la excepción) | Se registra como éxito (y como lenta si fue lenta) |
| `FAILURE` | Reintenta, si quedan intentos y tiempo | Se registra como falla (y como lenta si fue lenta) |
| `IGNORE` | Se detiene y devuelve el resultado (o relanza la excepción) | No se registra; una prueba de half-open devuelve su permiso |

Clasificar una excepción como `SUCCESS` o `IGNORE` no la silencia: la excepción igual le llega a quien llama; simplemente no se reintenta, y el breaker la cuenta como éxito o no la cuenta.

## Usarlo

<!-- snippet: classifiers-usage -->
```java
@Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class)
@CircuitBreaker(name = "orders", classifier = StatusClassifier.class)
Response get(String orderId) throws IOException;
```

- La clase se nombra en la anotación y la crea el código generado: debe ser concreta, no genérica, de nivel superior o anidada `static`, accesible, con un constructor sin argumentos, e implementar `Classifier<X>` con el resultado del método asignable a `X`. El processor valida todo esto al compilar ([`R4C-CLS-001`](../errors.md#r4c-cls-001) a [`R4C-CLS-010`](../errors.md#r4c-cls-010)).
- Se crea una instancia por método. Cuando `@Retry` y `@CircuitBreaker` nombran la misma clase, comparten esa instancia.
- Un classifier **reemplaza** a `retryOn` (en `@Retry`) o a `recordOn` (en `@CircuitBreaker`); definir ambos es un error de compilación ([`R4C-CLS-011`](../errors.md#r4c-cls-011)). `abortOn` e `ignoreOn` siguen aplicándose y ganan sobre el classifier. La `CallNotPermittedException` de un breaker abierto siempre detiene los reintentos, aunque el classifier devuelva `FAILURE` para ella.
- Es solo de anotación: no hay clave de config para él. En un `Pipeline`, pasa una instancia (abajo).
- Los métodos asincrónicos se clasifican igual, con el valor del stage o su falla desenvuelta.

Qué hace el código generado con él (abreviado):

<!-- not-compiled: generated code, abridged -->
```java
public final class OrdersApi$Resilient implements OrdersApi {
    // …
    private final StatusClassifier retryClassifier0;   // one instance per method, shared by both policies
    // …
    @Override
    public Response get(java.lang.String p0) throws java.io.IOException {
        final dev.resilience4cata.generated.RetryPolicy r4c$retry = this.retry0;
        final StatusClassifier r4c$classifier = this.retryClassifier0;
        final long r4c$start = r4c$retry.start();
        int r4c$attempts = 0;
        while (true) {
            final Response r4c$result;
            try {
                r4c$result = this.r4c$circuitBreaker0(p0);
            } catch (dev.resilience4cata.generated.ClassifierFailure r4c$w) {
                // el classifier del breaker lanzó: termina la llamada con su excepción, sin reintentarla
                try {
                    throw r4c$w.failure();
                } catch (java.lang.Throwable r4c$c) {
                    r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);
                    throw r4c$c;
                }
            } catch (java.lang.Throwable r4c$e) {
                // … classify(null, r4c$e), then retry or rethrow
                continue;
            }
            final dev.resilience4cata.Classifier.Outcome r4c$verdict;
            try {
                r4c$verdict = r4c$classifier.classify(r4c$result, null);
            } catch (java.lang.Throwable r4c$c) {
                r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);
                throw r4c$c;
            }
            if (r4c$verdict != dev.resilience4cata.Classifier.Outcome.FAILURE) {
                return r4c$result;
            }
            final long r4c$wait = r4c$retry.onFailedResult(++r4c$attempts, r4c$start);
            if (r4c$wait < 0 || !r4c$retry.pause(r4c$wait)) {
                return r4c$result; // rejected, but no retries left: the last result
            }
        }
    }
    // …
}
```

El classifier se llama directamente, sin asignar memoria y sin leer el reloj. Los métodos sin classifier generan el mismo código que antes; para ellos no cambia nada.

## Cuando los reintentos se agotan con un resultado rechazado

Si el último intento todavía devolvió un resultado `FAILURE`, no hay excepción que relanzar. `onExhaustedResult` decide qué pasa:

- `RETURN` (por defecto): el último resultado se devuelve a quien llama, como si no hubiera retry. `@Fallback` **no** se ejecuta, porque no se lanzó nada.
- `THROW`: el retry lanza `dev.resilience4cata.ResultRejectedException`, que va a `@Fallback` como cualquier otra falla:

<!-- snippet: classifiers-exhausted -->
```java
@Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class,
        onExhaustedResult = ExhaustedResult.THROW)
@Fallback(method = "unavailable")
Response submit(String orderId) throws IOException;

default Response unavailable(String orderId, ResultRejectedException rejected) {
    Response last = rejected.result(Response.class);   // the last rejected result
    return new Response(503, "gave up after " + rejected.attempts() + " attempts: " + last.body());
}
```

`ResultRejectedException` lleva `id()`, `attempts()` y el último resultado, como `result()` (un `Object`) o `result(Class<R>)` (tipado). `onExhaustedResult` necesita un classifier ([`R4C-CLS-012`](../errors.md#r4c-cls-012)), se decide al compilar y no forma parte de la configuración del retry: ninguna clave de config puede cambiarlo. `maxDuration` se aplica como siempre: rendirse porque se agotó el presupuesto con un resultado rechazado se comporta igual.

## Un classifier que lanza

Un classifier no debería lanzar excepciones. Si lo hace, su excepción se convierte en la falla de la llamada, igual en métodos sync y async:

- `@CircuitBreaker` registra la llamada como `FAILURE` y luego lanza la excepción del classifier.
- `@Retry` se detiene enseguida, publica `RetryEvent.GaveUp` con razón `CLASSIFIER_FAILED` (con la excepción del classifier como `failure`, y los intentos hechos hasta ahora, este incluido) y la lanza.
- `@Fallback`, si está, la recibe.

Esto vale dondequiera que corra el classifier. Cuando lanza el classifier del breaker, el retry que lo rodea no clasifica esa excepción como la falla del método ni la reintenta, tenga el retry el mismo classifier, otro o ninguno: el breaker le pasa la excepción al retry en un envoltorio interno (`dev.resilience4cata.generated.ClassifierFailure`, que quien llama nunca ve), y la llamada termina como arriba. Quien llama, o el fallback, recibe la excepción misma del classifier. Aun así, mantén los classifiers totales: maneja explícitamente los resultados `null` y los tipos de excepción inesperados.

## Lo mismo con `Pipeline`

<!-- snippet: classifiers-pipeline -->
```java
Pipeline<Response> get = Pipeline.builder(registry, "orders-pipeline", Response.class)
        .retry(r -> r.maxAttempts(3))
        .circuitBreaker()
        .classifier(new StatusClassifier())              // both policies
        .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
        .build();

Response response = get.execute(() -> orders.get("o-1"));
```

`classifier(...)` lo define para el retry y el circuit breaker a la vez; `retryClassifier(...)` y `circuitBreakerClassifier(...)` definen uno cada uno. `onExhaustedResult(Pipeline.ExhaustedResult.THROW)` necesita un classifier de retry. Sin la política correspondiente, `build()` falla ([`R4C-RT-014`](../errors.md#r4c-rt-014) a [`R4C-RT-017`](../errors.md#r4c-rt-017)). Ver [API Pipeline](pipeline.md).

## Cuánto cuesta

> [!MEASURED]
> En AWS Graviton3 (`c7g.xlarge`, JDK 21), una llamada exitosa a través de un circuit breaker con classifier cuesta 2,4 ns y no asigna memoria, con 1 thread y con 4; Resilience4j 2.4.0 con `recordResult` tarda 102 ns y 24 B con 1 thread. Retry más circuit breaker más classifier: 2,7 ns, 0 B, contra 148 ns y 104 B. Un classifier agrega unos 0,3 ns por política. [Benchmarks: cierre del MVP](../benchmarks.md#cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3)
