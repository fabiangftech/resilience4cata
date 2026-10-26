# Cómo funciona

Un procesador de anotaciones convierte tus anotaciones en Java común al compilar; en ejecución, un `ResilienceRegistry` le da a ese código su configuración y su estado.

## Qué genera el procesador

Por cada interfaz que usa una anotación de resiliencia, el procesador escribe una clase llamada `<Interfaz>$Resilient` en el mismo paquete. Implementa la interfaz, tiene un campo `final` de política por cada método protegido, y llama a tu implementación a través de las políticas con código lineal: un bucle para el retry, un bloque `try` para el breaker, sin lambdas, sin proxies y sin reflexión en el camino de la llamada.

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
public final class StockClient$Resilient implements StockClient {

    private final StockClient delegate;
    private final RetryPolicy retry0;
    private final CircuitBreakerPolicy circuitBreaker0;

    public StockClient$Resilient(StockClient delegate, ResilienceRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        // …
        this.retry0 = registry.retryPolicy("….StockClient#stock(java.lang.String,int)", "", "", r4c$b -> {
            r4c$b.maxAttempts(3);
        });
        this.circuitBreaker0 = registry.circuitBreakerPolicy("….StockClient#stock(java.lang.String,int)", "", "", null);
    }

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
}
```
<!-- compare:end -->

Lo que se desprende de este diseño:

- **Los errores son errores de compilación.** Atributos desconocidos, duraciones inválidas, un fallback con la firma incorrecta, una anotación en un método `static` o `private`: el build falla con un código como `[R4C-ATR-001]` que tiene su propia sección en la [referencia de errores](../errors.md).
- **El procesador es obligatorio.** Si falta la clase generada, `Resilient.of` falla de inmediato con [`R4C-RT-003`](../errors.md#r4c-rt-003), indicando la dependencia que hay que agregar. No hay alternativa por reflexión.
- **El código generado solo puede llamar a la API de `core`**, así que `core` y `processor` deben tener la misma versión (si no, [`R4C-RT-004`](../errors.md#r4c-rt-004); el BOM las mantiene alineadas).
- **El wrapper es thread-safe.** Créalo una vez y compártelo, igual que el registry.

> [!MEASURED]
> Un retry cuyo primer intento tiene éxito cuesta 1.2 ns y no asigna memoria, contra 22.9 ns de Resilience4j 2.4.0 (AWS Graviton3 `c7g.xlarge`, un thread). [Benchmarks](../benchmarks.md#cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3)

## Dos estilos, más una API programática

| Estilo | Tú escribes | Lo obtienes con | Auto-invocación (`this.other()`) |
|---|---|---|---|
| **Interfaz anotada** | Anotaciones en una interfaz; cualquier implementación | `Resilient.of(Type.class, impl, registry)` | No se intercepta: las llamadas de la implementación a sí misma no pasan por las políticas |
| **[Clase anotada](../guides/annotated-classes.md)** | Anotaciones en una clase no final | `Resilient.create(Type.class, registry, constructorArgs...)` | Se intercepta: la subclase generada sobrescribe el método |
| **[`Pipeline`](../guides/pipeline.md)** | Sin anotaciones: un builder | `Pipeline.builder(registry, id, Type.class)…build()` | n/a |

Con el estilo de interfaz solo cuentan las anotaciones de la interfaz. Una clase que implementa una interfaz anotada pero no declara ninguna anotación propia no se procesa; envuélvela con `Resilient.of`. Una clase que declara anotaciones se procesa con el estilo de clase.

## El registry y los ids de instancia

`ResilienceRegistry` guarda las configuraciones (valores por defecto, configuraciones con nombre, overrides por instancia), los listeners de eventos y cada instancia de política con estado. Constrúyelo una vez con `ResilienceRegistry.builder()…build()`; después es inmutable.

Cada método protegido recibe sus propias instancias de política, bajo un **id de instancia**:

- el atributo `name` de la política, si lo tiene (`@CircuitBreaker(name = "prices")` → `prices`); los métodos, pipelines y wrappers que usan el mismo nombre comparten una instancia (una ventana de breaker, un conjunto de permisos), y deben resolver la misma configuración (si no, [`R4C-REG-008`](../errors.md#r4c-reg-008));
- si no, el id derivado `<tipo calificado>#<método>(<tipos de parámetros>)`, con los tipos de los parámetros completamente calificados, borrados y separados por comas sin espacios: `com.acme.StockClient#stock(java.lang.String,int)`. Los tipos anidados usan `.` (`com.acme.Repository.Cache#get(java.lang.String)`). En una clase anotada, el tipo es la clase.

`@Timeout` no tiene `name`: su id es siempre el derivado. `@Fallback` no guarda estado y no tiene handle ni configuración.

El id es cómo encuentras una instancia en ejecución (`registry.circuitBreaker(id)`, `rateLimiter(id)`, `bulkhead(id)`), cómo la nombran los eventos y los snapshots, y cómo la ajusta la configuración externa (`instances.<id>.…`). Este test envuelve `StockClient` y busca su breaker por el id derivado:

<!-- snippet: how-it-works-ids -->
```java
AtomicInteger calls = new AtomicInteger();
StockClient stock = Resilient.of(StockClient.class, (sku, warehouse) -> {
    if (calls.incrementAndGet() == 1) {
        throw new IllegalStateException("warehouse busy");
    }
    return 7;
}, registry);

int count = stock.stock("sku-1", 3);   // the first attempt fails, the retry succeeds

CircuitBreakerSnapshot breaker = registry.circuitBreaker(
        "dev.resilience4cata.examples.docs.howitworks.StockClient#stock(java.lang.String,int)").snapshot();
// breaker.bufferedCalls() == 2 and breaker.failedCalls() == 1: the breaker saw both attempts
```

## De dónde viene la configuración

Cada atributo de anotación tiene un valor "sin definir", así que una anotación solo sobrescribe lo que escribe; los valores por defecto reales viven en el registry. Los valores se resuelven en este orden, de menor a mayor precedencia:

1. Los valores por defecto incorporados (para `@Retry`: 3 intentos, backoff exponencial de 200 ms con jitter completo; cada [página de política](../policies/README.md) los lista).
2. Los valores por defecto del registry: `retryDefaults(...)` y similares, o `defaults.<policy>.*` en la [configuración externa](../guides/configuration.md).
3. La configuración con nombre elegida con `config = "…"`: registrada en código con `retry("external", ...)`, externamente con `configs.external.*`.
4. Las anotaciones: los bundles por debajo de las anotaciones directas, el tipo por debajo del método ([bundles](../guides/bundles.md)).
5. Los overrides por instancia: `retryInstance(id, ...)` y similares, o `instances.<id>.*` externamente.

Todo se resuelve **una vez**, al crear el wrapper, la subclase o el pipeline, en records inmutables; las llamadas nunca consultan la configuración. No hay recarga en caliente: cambiar un valor implica construir un registry nuevo, en la práctica reiniciar la aplicación.

<!-- snippet: how-it-works-registry -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryDefaults(r -> r.delay(Duration.ofMillis(50)))                     // every retry
        .circuitBreakerInstance(
                "dev.resilience4cata.examples.docs.howitworks.StockClient#stock(java.lang.String,int)",
                cb -> cb.minimumCalls(20))                                     // one instance
        .build();
StockClient stock = Resilient.of(StockClient.class, (sku, warehouse) -> 7, registry);   // resolved here
```

Un nombre de configuración desconocido falla en ese momento ([`R4C-REG-004`](../errors.md#r4c-reg-004)), no en la primera llamada.

## Orden de las políticas

Sin importar en qué orden escribas las anotaciones, las políticas se anidan en este orden fijo, de afuera hacia adentro:

```mermaid
flowchart LR
    caller([caller]) --> F[Fallback] --> R[Retry | Hedge] --> CB[CircuitBreaker] --> RL[RateLimiter] --> AL[AdaptiveLimit] --> T[Timeout] --> B[Bulkhead] --> M([your method])
```

- **El fallback es el más externo**, así que ve el resultado final, incluidos los rechazos como un breaker abierto o un bulkhead lleno.
- **Cada intento del retry pasa por el breaker**, que lo registra (el ejemplo de arriba muestra dos llamadas registradas para una llamada reintentada), y un breaker abierto detiene los reintentos: `@Retry` nunca reintenta una `CallNotPermittedException`.
- **Las llamadas que rechaza el breaker no consumen permisos del rate limiter.**
- **El timeout se aplica a cada intento**; `@Retry(maxDuration = …)` limita la operación completa.
- **El slot del bulkhead se ocupa solo mientras corre el método**, no durante el backoff del retry.

El orden todavía no es configurable. La [introducción a las políticas](../policies/README.md) explica cómo interactúan.
