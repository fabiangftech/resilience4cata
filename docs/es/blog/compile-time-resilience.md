# Un circuit breaker de 1.9 ns: resiliencia generada al compilar

La mayoría de las librerías de resiliencia para Java envuelven cada llamada en decoradores en tiempo de ejecución; resilience4cata escribe ese código al compilar, y un circuit breaker sano termina costando unos dos nanosegundos.

*27 de octubre de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor de resilience4cata*

## El problema de los decoradores

Los retries, circuit breakers, timeouts y bulkheads suelen estar en el camino de la llamada como decoradores en tiempo de ejecución. Envuelves un `Supplier` o un `Callable` en una función por política, o dejas que un framework ponga delante de tu bean un proxy que lo hace por ti. Funciona, es flexible, y es como está construido Resilience4j, la librería que usan muchos equipos Java.

También cuesta algo en cada llamada, incluidas las que salen bien, que son casi todas. Cada capa es otra llamada a través de una interfaz funcional, y el estado de la política que hay detrás lo comparten todos los threads. En un benchmark con un solo método decorado, el JIT ve un único tipo en cada punto de llamada y puede inlinear buena parte: es el mejor caso. Un servicio real decora decenas de métodos, y esos puntos de llamada compartidos dejan de ser monomórficos.

Quería saber qué tan baratas podían ser las mismas garantías si el código se escribiera método a método, como lo escribirías a mano si tuvieras la paciencia.

## Que lo escriba el compilador

resilience4cata es un conjunto de anotaciones más un procesador de anotaciones. Anotas una interfaz:

<!-- compare:start -->
<!-- snippet: how-it-works-interface -->
```java
public interface StockClient {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    int stock(String sku, int warehouse);
}
```
<!-- not-compiled: generated code, abridged (package names shortened) -->
```java
@Override
public int stock(String p0, int p1) {
    final RetryPolicy r4c$retry = this.retry0;
    final long r4c$start = r4c$retry.start();
    int r4c$attempts = 0;
    while (true) {
        try {
            return this.r4c$circuitBreaker0(p0, p1);
        } catch (Throwable r4c$e) {
            final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);
            if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {
                throw r4c$e;
            }
        }
    }
}

private int r4c$circuitBreaker0(String p0, int p1) {
    final CircuitBreakerPolicy r4c$cb = this.circuitBreaker0;
    final long r4c$token = r4c$cb.acquire();
    if (r4c$token < 0) {
        throw r4c$cb.rejection();
    }
    final long r4c$started = r4c$cb.startTimer();
    try {
        final int r4c$result = this.delegate.stock(p0, p1);
        r4c$cb.onSuccess(r4c$token, r4c$started);
        return r4c$result;
    } catch (Throwable r4c$e) {
        r4c$cb.onFailure(r4c$token, r4c$started, r4c$e);
        throw r4c$e;
    }
}
```
<!-- compare:end -->

Al compilar, el procesador escribe `StockClient$Resilient`, una clase normal que implementa la interfaz. Cada método protegido se convierte en código en línea recta: un bucle para el retry, un bloque `try` para el breaker, un campo `final` por política. No hay lambdas, ni proxies, ni reflexión en el camino de la llamada, y cada punto de llamada es monomórfico porque cada método tiene el suyo. En ejecución envuelves tu implementación una sola vez:

<!-- not-compiled: a one-line usage example; the quickstart has the full, tested program -->
```java
StockClient client = Resilient.of(StockClient.class, new HttpStockClient(), registry);
```

Las políticas detrás de esos campos siguen un protocolo pequeño (`acquire`, `onSuccess`, `onFailure`) pensado para que el camino de éxito haga lo mínimo:

- **Sin asignar memoria.** Los rechazos lanzan una excepción preasignada y sin stack trace por política, y los eventos solo se crean si alguien escucha.
- **Sin leer el reloj salvo que una función lo necesite.** El breaker solo mide las llamadas si configuras un umbral de llamadas lentas.
- **Sin escrituras compartidas cuando nada cambia.** Mientras un breaker está cerrado y solo ha visto éxitos rápidos, una llamada lee un campo y no escribe nada.

Esa última regla fue la que más aportó. Con contención, escribir una línea de caché que otros núcleos están leyendo cuesta mucho más de lo que ahorra cualquier truco lock-free. En un Apple M1 con cuatro threads, la primera versión del breaker costaba 244 ns por llamada exitosa; saltarse las escrituras mientras la ventana solo tiene éxitos rápidos la bajó a 10 ns, y leer un único campo mientras está sano, a unos 3 ns ([cómo se llegó a ese camino de éxito](../benchmarks.md#circuit-breaker-1-y-4-threads-2026-10-23)).

## Los números

Cada comparación usa configuración equivalente en las dos librerías, y cada ajuste hecho por justicia está escrito en el Javadoc del benchmark.

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, resilience4cata 0.1.0 frente a Resilience4j 2.4.0, tiempo y bytes asignados por llamada. De [MVP completion (2026-10-24, AWS Graviton3)](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3).

| Escenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Retry, el primer intento sale bien | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Retry, falla una vez y luego sale bien | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, todas las llamadas salen bien | 1 | **1.9 ns**, 0 B | 89.2 ns, 0 B |
| Circuit breaker, todas las llamadas salen bien | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Bulkhead lleno (se rechazan todas las llamadas) | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Bulkhead con huecos libres | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |
| Retry + circuit breaker | 1 | **2.3 ns**, 0 B | 175 ns, 288 B |

La tabla también muestra dónde este enfoque no ayuda:

- **Algunos casos son un empate.** Un bulkhead con huecos libres toma y devuelve un permiso con una operación atómica en las dos librerías: 20.5 ns frente a 20.4 ns.
- **La contención sigue costando cientos de nanosegundos.** Con cuatro threads y una llamada de cada diez fallando, el breaker cuesta 274 ns (Resilience4j: 910 ns), porque cada llamada avanza un único cursor compartido de la ventana. Los contadores compartidos cuestan lo mismo al bulkhead y al rate limiter.
- **Las llamadas async asignan memoria.** Cada capa async devuelve un future nuevo en las dos librerías; solo los caminos síncronos no asignan.
- **Algunas filas no comparan lo mismo.** El retry de Resilience4j llama a `Thread.sleep(0)` entre intentos, así que su fila usa una variante sin esa llamada (su mejor caso). Su `TimeLimiter` necesita un `Future`, así que un timeout síncrono implica otro thread; esa comparación está en la [página de rendimiento](../performance.md#headline), con esa salvedad.

¿Importa un nanosegundo en una llamada que pasa por la red? Rara vez por sí solo. Importa cuando la resiliencia envuelve llamadas que suelen ser baratas (una caché, un servicio local, una cola en memoria), cuando el mismo código se ejecuta millones de veces por segundo, o cuando prefieres no pensar si una política es demasiado cara para añadirla.

## Lo que te cuesta

Generar el código al compilar tiene contrapartidas, y son deliberadas:

- **El procesador de anotaciones es obligatorio.** No hay alternativa por reflexión: si falta la clase generada, `Resilient.of` falla de inmediato y dice qué dependencia añadir.
- **Los errores son errores de compilación.** Una duración inválida, un atributo desconocido o un fallback con la firma equivocada rompen el build con un código como `R4C-ATR-001`, documentado en la [referencia de errores](../errors.md).
- **La configuración se resuelve una vez.** Los valores pueden venir de las anotaciones, de archivos properties, de variables de entorno o de la configuración de Spring, Micronaut y Quarkus, pero quedan fijos al construir el registry: no hay recarga en caliente, por [diseño](../design.md).
- **Es joven.** La versión actual es la 0.8; hasta la 1.0 la API todavía puede cambiar.

## Pruébalo

Dos líneas en Gradle:

<!-- not-compiled: build configuration -->
```groovy
implementation 'dev.resilience4cata:resilience4cata-all:0.8.0'
annotationProcessor 'dev.resilience4cata:resilience4cata-processor:0.8.0'
```

Luego, el [inicio rápido](../start/quickstart.md) protege una llamada inestable en unos cinco minutos. Hay integraciones para [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) y [Quarkus](../guides/quarkus.md), un [testkit](../guides/testing.md) con reloj manual, y una [guía para migrar desde Resilience4j](../migrate/from-resilience4j.md). El código tiene licencia Apache-2.0 y está en [GitHub](https://github.com/fabiangftech/resilience4cata); los issues y las discusiones son bienvenidos.
