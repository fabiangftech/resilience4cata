# Features

> Síntesis de [01 Panorama](research/01-landscape.md), [03 Rendimiento interno](research/03-performance-internals.md) y [04 API y configuración](research/04-api-and-configuration.md). Es una propuesta: los nombres y atributos todavía pueden cambiar antes de `1.0`.

## Niveles

| Feature | Nivel | Notas |
|---|---|---|
| Circuit breaker: ventanas por cantidad y por tiempo, tasa de llamadas lentas, sondeo en half-open, `forceOpen` / `disable` | **MVP** | Mismo modelo que Resilience4j, para facilitar la migración |
| Retry: backoff fijo o exponencial, jitter full o equal (activo por defecto), `maxDuration`, retry según el resultado | **MVP** | |
| Timeout, síncrono y asíncrono | **MVP** | Síncrono: la llamada sigue en el thread del llamador y un temporizador la interrumpe |
| Bulkhead (semáforo, rechazo inmediato o con `maxWait`) | **MVP** | Sin bulkhead de pool de threads: los virtual threads lo hacen innecesario |
| Rate limiter | **MVP** | GCRA por dentro, configurado como permisos por período |
| Fallback (clase handler tipada, o un método validado al compilar) | **MVP** | |
| Async según el tipo de retorno (`CompletionStage` / `CompletableFuture`) | **MVP** | Sin anotación `@Asynchronous` |
| Anotaciones compuestas (`@ResiliencePolicy`) | **MVP** | La forma principal de reutilizar configuraciones en el código |
| Configuraciones con nombre, `extends`, perfiles; properties, variables de entorno y propiedades del sistema | **MVP** | |
| API programática (sin anotaciones) | **MVP** | Para código que no se puede anotar |
| Eventos tipados, sin asignar memoria si nadie escucha | **MVP** | |
| Inspección de estado (`registry.snapshot()`) | **MVP** | Records de solo lectura, útiles para health checks |
| Soporte para tests: reloj y scheduler manuales, jitter con semilla, estados forzados | **MVP** | En `testkit` |
| Hedging (`@Hedge`) | v1 | Diferenciador; Resilience4j no lo tiene |
| Límite de concurrencia adaptativo (`@AdaptiveLimit`: AIMD, Vegas, Gradient2) | v1 | Pedido en Resilience4j desde 2018 |
| Presupuesto de reintentos | v1 | Evita tormentas de reintentos |
| Puentes a Micrometer y OpenTelemetry | v1 | Módulos separados |
| Configuración YAML | v1 | Módulo separado, para que `core` siga sin dependencias |
| Orden de ejecución configurable (`@Order`) | v1 | |
| Extensión de JUnit 5 | v1 | |
| `@Cached`, tipos reactivos, corrutinas de Kotlin, inyección de fallos, propagación de deadlines, integración con frameworks | después | |

## Anotaciones

```java
@CircuitBreaker  @Retry  @Timeout  @Bulkhead  @RateLimiter  @Fallback   // MVP
@Hedge  @AdaptiveLimit  @Order                                           // v1
@ResiliencePolicy                                                        // marca anotaciones compuestas
```

Reglas de diseño ([04 §4](research/04-api-and-configuration.md)):

- **`config` y `name` son cosas distintas.** `config` elige una plantilla con nombre. `name` elige la instancia con estado (ventana del breaker, permisos); por defecto hay una instancia por método. Dos métodos con el mismo `name` comparten la instancia y deben resolver a la misma configuración.
- **Cada atributo tiene un valor "sin definir"** (`-1`, `""`, `{}`), así una anotación solo sobrescribe lo que escribe. Los valores por defecto reales viven en la capa de configuración.
- **Tipado donde Java lo permite**: clases de excepción, enums, clases de fallback.
- **Las duraciones son strings** (`"250ms"`, `"2s"`, `"PT2S"`), validados al compilar.
- Las anotaciones van en métodos o tipos. Una anotación de método sobrescribe la de tipo atributo por atributo.
- Las ubicaciones no soportadas (métodos final o privados, según el estilo generado) son **errores de compilación**, nunca se ignoran en silencio.

### Ejemplo

```java
@ResiliencePolicy
@Retention(CLASS) @Target({METHOD, TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
@Bulkhead(maxConcurrent = 50)
public @interface ExternalCall {}

public interface PaymentClient {

    @ExternalCall
    @Fallback(PaymentFallback.class)          // handler tipado: FallbackHandler<Receipt>
    Receipt charge(Order order);

    @ExternalCall
    @Timeout("500ms")                         // sobrescribe un atributo del paquete
    CompletableFuture<Status> status(String id);
}
```

Si dos anotaciones compuestas en el mismo elemento ponen valores distintos en el mismo atributo, el build falla. No hay un "gana el último" silencioso.

## Orden de ejecución

Por defecto, de afuera hacia adentro ([04 §5](research/04-api-and-configuration.md)):

```
Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → método
```

- El fallback va afuera, así ve el resultado final, incluidos los rechazos.
- El breaker registra cada intento del retry, y un breaker abierto corta los reintentos.
- Las llamadas que rechaza el breaker no gastan permisos del rate limiter.
- El timeout aplica por intento; `@Retry(maxDuration)` limita la operación completa.
- El permiso del bulkhead se ocupa solo mientras corre el método, no durante el backoff.

## Múltiples configuraciones

Orden de resolución, de menor a mayor prioridad ([04 §6](research/04-api-and-configuration.md)):

```
1. valores por defecto de la librería
2. defaults.<política>.*
3. configs.<nombre>.*              (siguiendo la cadena de extends)
4. profiles.<activo>.*
5. anotación compuesta             (@ExternalCall)
6. anotación de clase
7. anotación de método
8. instances.<id>.*                (externo, por instancia: archivos < env < propiedades del sistema)
9. override programático
```

- La configuración externa (8) sobrescribe las anotaciones, como en MicroProfile Fault Tolerance: operaciones puede ajustar valores sin recompilar. `locked` lo impide para una instancia.
- Todo se valida y se resuelve **una sola vez**, al crear la instancia, en records inmutables. El camino caliente nunca busca configuración.
- Las claves desconocidas fallan con un mensaje "¿quisiste decir …?".
- **Sin recarga en caliente.** La configuración es inmutable una vez construido el registry; para cambiarla hay que reiniciar la aplicación.

```properties
defaults.retry.maxAttempts=3
configs.external.circuitBreaker.failureRateThreshold=50
configs.external.retry.delay=200ms
configs.batch.extends=external
configs.batch.timeout.value=30s
profiles.test.configs.external.retry.maxAttempts=1
```

## Contrato de rendimiento

Objetivos a verificar con JMH desde la primera versión ([01 §4.1](research/01-landscape.md), [03 §10](research/03-performance-internals.md)):

| Objetivo | Cómo se verifica |
|---|---|
| **0 B/op** en el camino exitoso de cada política y de una cadena completa | JMH `-prof gc`, como control en CI |
| Sin locks ni `synchronized` en el camino caliente | Revisión de código, ArchUnit, escenarios JMH con contención |
| Código generado lineal y monomórfico por método; sin cadenas de lambdas compartidas | JMH con perfil de JIT contaminado, `-XX:+PrintInlining` |
| Un par de lecturas de reloj por llamada, sin importar cuántas políticas haya | Revisión de código |
| Excepciones de rechazo preasignadas y sin stack trace; eventos creados solo si alguien escucha | Escenarios JMH de rechazo |
| Estructuras sin locks correctas | jcstress |
| Comparación publicada y reproducible contra Resilience4j 2.4.0 | 11 escenarios JMH ([03 §9](research/03-performance-internals.md)) |

Ninguno de estos números se ha medido todavía en resilience4cata. La mayoría de las llamadas reales dependen de I/O (milisegundos), así que la propuesta es "overhead casi nulo que escala con los threads, más anotaciones sin framework", no solo velocidad bruta.
