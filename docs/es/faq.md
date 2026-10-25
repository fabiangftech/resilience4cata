# Preguntas frecuentes

Respuestas breves a las preguntas que la gente hace primero, con enlaces a los detalles.

## General :id=general

### ¿Qué es resilience4cata? :id=what

Una biblioteca de resiliencia basada en anotaciones para Java 21+ puro: retry, circuit breaker, rate limiter, timeout, bulkhead y fallback. Un procesador de anotaciones convierte las anotaciones en código fuente Java común en tiempo de compilación, así que no hay proxies, ni generación de bytecode, ni agentes en tiempo de ejecución. Consulta [Cómo funciona](start/how-it-works.md).

### ¿Qué versiones de Java se admiten? :id=java

Java 21 o posterior, al compilar y en tiempo de ejecución. La biblioteca se compila con `--release 21` y se prueba en Java 21 y 25.

### ¿Funciona con Spring, Quarkus, Micronaut o Jakarta CDI? :id=frameworks

No se integra con ninguno de ellos, y no le hace falta: las clases generadas son Java puro, así que creas tú mismo el objeto envuelto (`Resilient.of(…)`) y lo registras como bean de la forma en que lo haga tu framework. No hay autoconfiguración, ni AOP, ni escaneo de anotaciones en tiempo de ejecución.

### ¿Es un fork de Resilience4j? :id=fork

No. Cubre políticas similares con un diseño distinto: código generado en tiempo de compilación en lugar de decoradores, un solo registry para todas las políticas y configuración resuelta una sola vez. Sus benchmarks se comparan con Resilience4j 2.4.0. Consulta [Migrar desde Resilience4j](migrate/from-resilience4j.md).

### ¿De qué depende? :id=dependencies

`core` y `annotations` solo dependen del JDK, más las anotaciones de [JSpecify](https://jspecify.dev/) (`org.jspecify:jspecify`) en el caso de `core`. El procesador solo está en el processor path, y las anotaciones son `compileOnly`: nada más termina en tu class path de ejecución.

## Uso :id=using

### ¿Tengo que usar el procesador de anotaciones? :id=processor

Sí, si usas anotaciones: el procesador es obligatorio y no hay alternativa por reflexión. Si no se ejecutó, `Resilient.of` falla con [R4C-RT-003](errors.md#r4c-rt-003), cuyo mensaje muestra la dependencia que debes agregar. Sin anotaciones, la API [`Pipeline`](guides/pipeline.md) no necesita procesador.

### ¿Interfaz o clase anotada? :id=style

Anota una **interfaz** cuando es tuya y la implementación puede ser cualquier cosa (un cliente HTTP, una lambda, un stub de prueba). Anota una **clase** cuando no hay interfaz, o cuando las llamadas que la clase se hace a sí misma también deben pasar por las políticas. Consulta [Clases anotadas](guides/annotated-classes.md).

### ¿Puedo cambiar el orden de las políticas? :id=order

No. Siempre es Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → método, sin importar el orden de las anotaciones. Así, el breaker registra cada intento de un retry, cada intento tiene su propio timeout y un breaker abierto detiene los retries. Consulta [Políticas](policies/README.md).

### ¿Por qué no se reintenta mi método cuando la implementación lo llama? :id=self-invocation

En el estilo de interfaz, las políticas viven en el wrapper generado; una llamada de la implementación a sí misma no pasa por él. Consulta [Solución de problemas](guides/troubleshooting.md#self-invocation).

### ¿Admite tipos reactivos (`Mono`, `Flux`, `Single`)? :id=reactive

No. Los métodos async deben devolver `CompletionStage<T>` o `CompletableFuture<T>`. Cualquier otro tipo de retorno se trata como síncrono: las políticas solo protegen la llamada que devuelve el objeto, no el trabajo que representa. Consulta [Métodos async](guides/async.md).

### ¿Funciona con virtual threads? :id=virtual-threads

Sí. Las rutas de llamada no toman locks ni usan `synchronized`, así que no fijan un virtual thread a su carrier. Los callbacks diferidos de la propia biblioteca (retries async, timeouts async) se ejecutan en virtual threads nuevos.

### ¿Puedo cambiar una configuración en tiempo de ejecución? :id=reload

No, por diseño. Las anotaciones, las configs con nombre y la configuración externa se resuelven una sola vez, cuando se crea cada instancia. Para reajustar en producción, cambia la configuración externa (por ejemplo `instances.<id>.*`) y reinicia. Para forzar el estado de un breaker, usa su handle: `registry.circuitBreaker(id).forceOpen()`, `disable()`, `reset()`.

### ¿Cómo lo configuro desde un archivo o variables de entorno? :id=external-config

Agrega `resilience4cata-config` y construye el registry a partir de `ResilienceConfig.load()`. Las claves tienen la forma `defaults.retry.maxAttempts=5` o `R4C_DEFAULTS_RETRY_MAXATTEMPTS=5`. Consulta [Configuración](guides/configuration.md) y la [referencia de claves](reference/configuration.md).

### ¿Qué es un id de instancia? :id=instance-id

Cada método anotado obtiene sus propias instancias de políticas, con el id `<type>#<method>(<parameter types>)`, por ejemplo `com.acme.CatalogClient#price(java.lang.String,int)`. Un atributo `name` lo reemplaza, y los métodos con el mismo `name` comparten una instancia. Los ids los usan los handles, los eventos, los snapshots y las claves `instances.<id>.*`.

## Operación :id=operations

### ¿Cómo obtengo métricas? :id=metrics

A partir de los eventos (contadores) y los snapshots (gauges). No hay módulo de Micrometer en 0.1.0, pero la [guía de observabilidad](guides/observability.md#micrometer) tiene una receta probada que puedes copiar.

### ¿Cuenta las llamadas exitosas o mide la latencia? :id=success-metrics

No. Las llamadas exitosas no publican ningún evento ni actualizan ningún contador: un contador en cada llamada sería una escritura en memoria compartida en la ruta crítica. Mide la cantidad de llamadas y la latencia en tu propio código o con las métricas de tu framework.

### ¿Cómo pruebo código que espera segundos entre retries? :id=testing

Con `resilience4cata-testkit`: un `ManualClock` hace que las pausas, las esperas y los timeouts sigan un reloj que la prueba avanza, así que las demoras de segundos no consumen tiempo real. Consulta [Pruebas](guides/testing.md).

### ¿Por qué mi timeout no detiene la llamada? :id=timeout

Un timeout síncrono interrumpe el thread de quien llama; el código que ignora las interrupciones (E/S de sockets bloqueante clásica, bucles de espera activa) se ejecuta hasta que retorna. Configura también los timeouts de E/S del propio cliente. Consulta [Solución de problemas](guides/troubleshooting.md#timeout-not-interrupting).

### ¿Qué tan rápido es? :id=performance

En AWS Graviton3, un retry cuyo primer intento tiene éxito cuesta 1.2 ns y un circuit breaker 1.9 ns por llamada, con 0 bytes asignados, frente a 22.9 ns y 89.2 ns de Resilience4j 2.4.0 ([medido](benchmarks.md#cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3)). Algunos casos son empates, y las rutas con contención cuestan cientos de nanosegundos en ambas bibliotecas. Consulta [Rendimiento](performance.md).

## Proyecto :id=project

### ¿Está listo para producción? :id=production

0.1.0 es la primera versión. Cada política tiene pruebas unitarias y de extremo a extremo, las lock-free también pruebas de concurrencia (jcstress), y la asignación de memoria se controla en CI, pero la API todavía puede cambiar antes de 1.0.0. Consulta el [Registro de cambios](changelog.md).

### ¿Qué no se admite todavía? :id=not-supported

Hedging, límites de concurrencia adaptativos, presupuestos de retry, caché y bulkheads con pool de threads; tipos reactivos; tipos async `? super`; cambiar el orden de las políticas; reconfiguración en tiempo de ejecución; módulos de integración con frameworks. Consulta [Decisiones de diseño](design.md) para ver qué está planeado y qué está excluido por decisión.

### ¿Qué hay en la hoja de ruta? :id=roadmap

Planificado, no prometido; los nombres y el alcance pueden cambiar:

| Funcionalidad | Nivel | Notas |
|---|---|---|
| Hedging (`@Hedge`) | v1 | Resilience4j no lo tiene |
| Límite de concurrencia adaptativo (`@AdaptiveLimit`: AIMD, Vegas, Gradient2) | v1 | |
| Presupuesto de retries | v1 | evita tormentas de retries |
| Puentes a Micrometer y OpenTelemetry | v1 | módulos separados |
| Configuración YAML | v1 | módulo separado, para que `core` siga sin dependencias |
| Orden de ejecución configurable (`@Order`) | v1 | |
| Extensión de JUnit 5 | v1 | |
| Atributo `locked` (una instancia que la configuración externa no puede sobrescribir) | v1 | |
| `@Cached`, tipos reactivos, corrutinas de Kotlin, inyección de fallos, propagación de deadlines, integraciones con frameworks | más adelante | |

Mientras tanto, la [receta de observabilidad](guides/observability.md#micrometer) cubre las métricas y [testkit](guides/testing.md) cubre las pruebas.

### ¿Dónde reporto un bug? :id=bugs

En el [issue tracker de GitHub](https://github.com/fabiangftech/resilience4cata/issues). Incluye el código `R4C-…` si lo hay, la anotación y las versiones de Java y de resilience4cata.
