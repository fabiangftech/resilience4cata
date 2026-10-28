# Rendimiento

Lo que cuesta resilience4cata por llamada, medido contra Resilience4j 2.4.0 con configuración equivalente, y cómo reproducirlo.

El bajo overhead es la razón de ser de resilience4cata: el annotation processor genera código lineal para cada método, así que una llamada exitosa pasa por unas pocas lecturas de campos y, en la mayoría de las políticas, sin allocation, sin lock y sin lectura del reloj. Esta página resume las mediciones; [Benchmarks en detalle](benchmarks.md) tiene cada ejecución, su configuración y cómo se llegó a cada número.

## Qué se mide, y con qué justicia :id=method

- **JMH 1.37**, 3 forks × 5 × 1 s de calentamiento y 5 × 1 s de medición, con `-prof gc` para los bytes asignados por llamada. JMH a secas, sin plugin de Gradle.
- **Configuración equivalente en ambas librerías**: mismos intentos, delays, tamaños de ventana, umbrales y límites; sin event listeners; los fallos son excepciones preasignadas y sin stack trace, para que ninguna de las dos librerías pague por crearlas. Las excepciones de rechazo de Resilience4j llevan `writableStackTraceEnabled(false)`.
- **Los ajustes hechos por justicia están documentados** en el Javadoc de cada benchmark. El principal: Resilience4j 2.4.0 llama a `Thread.sleep` entre intentos incluso con un delay de cero, así que la comparación de retry usa una variante sin ese sleep (`resilience4jRetryOnceNoSleep`), que es el mejor caso de Resilience4j.
- **Diferencias de comportamiento que los números incluyen**: el breaker de Resilience4j siempre mide el tiempo de las llamadas (resilience4cata solo cuando se configura `slowCallDuration`); el `TimeLimiter` de Resilience4j necesita un `Future`, así que una llamada síncrona tiene que ir a otro thread (resilience4cata le aplica el timeout en el thread del llamador).
- **Dos máquinas, nunca mezcladas**: una AWS `c7g.xlarge` (4 núcleos Graviton3, sin SMT, reloj fijo, Amazon Corretto 21), y una laptop Apple M1 (JDK 21) para rondas anteriores. Los números de máquinas distintas no son comparables.
- Un benchmark con un solo método decorado es el mejor caso de Resilience4j: el JIT ve un solo tipo en cada call site. Las aplicaciones reales tienen muchos.

## Resultados principales :id=headline

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, resilience4cata 0.1.0 vs Resilience4j 2.4.0, nanosegundos y bytes asignados por llamada. De [Cierre del MVP (2026-10-24, AWS Graviton3)](benchmarks.md#cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3).

| Escenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Retry, el primer intento tiene éxito | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Retry, falla una vez y luego tiene éxito (Resilience4j sin su `sleep(0)`) | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, todas las llamadas tienen éxito | 1 | **1.9 ns**, 0 B | 89.2 ns, 0 B |
| Circuit breaker, todas las llamadas tienen éxito | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Circuit breaker, una de cada diez llamadas falla | 4 | **274 ns**, 0 B | 910 ns, 2 B |
| Rate limiter, permisos libres | 4 | **187 ns**, 0 B | 275 ns, 40 B |
| Rate limiter, agotado (todas las llamadas rechazadas) | 4 | **34.7 ns**, 0 B | 1599 ns, 505 B |
| Bulkhead, slots libres | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |
| Bulkhead, slots libres | 4 | **264 ns**, 0 B | 432 ns, 0 B |
| Bulkhead, lleno (todas las llamadas rechazadas) | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Timeout, llamada síncrona que termina a tiempo (Resilience4j: `TimeLimiter` + executor) | 1 | **22.2 ns**, 0 B | 8842 ns, 176 B |
| Timeout async, stage todavía pendiente | 1 | **152 ns**, 192 B | 598 ns, 240 B |
| Timeout async, stage todavía pendiente | 4 | **369 ns**, 192 B | 1563 ns, 234 B |

Leyendo la tabla con honestidad:

- **El bulkhead con slots libres en un thread es un empate** (20.5 vs 20.4 ns): ambos toman y liberan un permiso con un atómico cada uno.
- **Los caminos con contención cuestan cientos de nanosegundos en ambas librerías.** El caso mixto del breaker con cuatro threads (274 ns) está dominado por un único cursor de ventana compartido que cada llamada avanza; el bulkhead y el rate limiter con cuatro threads pagan por un contador compartido.
- **El timeout síncrono es la política síncrona más cara** (22.2 ns en un thread; una ejecución posterior midió 23.7 ns sin cambios en el código, un efecto de layout aún abierto, ver [la siguiente ronda](benchmarks.md#clases-anotadas-genéricos-overrides-de-config-bulkhead-con-ticker-2026-10-24-aws-graviton3)). La comparación con Resilience4j no es equivalente: su `TimeLimiter` pasa la llamada a otro thread, que es donde se van la mayoría de sus 8.8 µs.
- **Las llamadas async asignan memoria en ambas librerías**: cada capa devuelve un future nuevo. Los caminos async de resilience4cata no están libres de allocations; solo los síncronos.
- **El rate limiter lee el reloj una vez por llamada**, en ambas librerías; esa lectura es la mayor parte de su costo (en Apple Silicon, `System.nanoTime()` por sí solo cuesta unos 21 ns en un thread y 163 ns con cuatro; ver [Rate limiter, 1 y 4 threads](benchmarks.md#rate-limiter-1-y-4-threads-2026-10-24)).

### Classifiers, Pipeline y clases anotadas :id=more

> [!MEASURED]
> Misma máquina y ronda que arriba ([Cierre del MVP](benchmarks.md#cierre-del-mvp-classifier-pipeline-eventos-testkit-2026-10-24-aws-graviton3), [clases anotadas](benchmarks.md#clases-anotadas-genéricos-overrides-de-config-bulkhead-con-ticker-2026-10-24-aws-graviton3)), todas las llamadas tienen éxito, 0 B del lado de resilience4cata.

| Escenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Circuit breaker + classifier de resultados | 1 | **2.4 ns** | 102 ns, 24 B (`recordResult`) |
| Circuit breaker + classifier de resultados | 4 | **2.4 ns** | 902 ns, 18 B |
| Retry + circuit breaker + classifier | 1 | **2.7 ns** | 148 ns, 104 B |
| Retry + circuit breaker, código generado | 1 | **2.3 ns** | 175 ns, 288 B |
| Retry + circuit breaker, `Pipeline` programático | 1 | **2.9 ns** | (el mismo stack de Resilience4j) |
| Retry, clase anotada (subclase) vs wrapper de interfaz | 1 | 1.0 vs 1.2 ns | — |

- Un `Pipeline` cuyo único call site `call.call()` ya vio otros cinco pipelines midió **7.9 ns** en lugar de 2.9 ns: con muchos pipelines en una aplicación, ese call site se vuelve megamórfico. El código generado tiene un call site por método y no paga esto.

### Métodos Mono (Project Reactor) :id=reactor

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, contra los operadores de resilience4j-reactor 2.4.0 con la misma configuración, todas las llamadas funcionan. De [Soporte de Reactor (2026-10-27, AWS Graviton3)](benchmarks.md#soporte-de-reactor-y-el-rate-limiter-async-0110-2026-10-27-aws-graviton3).

| Escenario | Hilos | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Circuit breaker | 1 | **130 ns**, 200 B | 246 ns, 248 B |
| Circuit breaker | 4 | **119 ns**, 200 B | 1379 ns, 234 B |
| Retry, el primer intento funciona | 1 | **128 ns**, 200 B | 726 ns, 1000 B |
| Timeout, el valor ya está | 1 | **105 ns**, 176 B | 3166 ns, 1344 B |
| Timeout, el valor llega después | 1 | **441 ns**, 480 B | 3725 ns, 1787 B |
| Bulkhead | 1 | **144 ns**, 200 B | 158 ns, 200 B |
| Retry + breaker + timeout + bulkhead | 1 | **227 ns**, 368 B | 4338 ns, 2624 B |

- Suscribirse a un `Mono.just` sin políticas cuesta 52 ns y 88 B en esta máquina; cada fila lo incluye.
- El bulkhead es el más ajustado: 9% con un hilo, dentro del error con cuatro. Los métodos `Mono` asignan memoria, como todo camino async; el gate de allocations controla sus bytes.

### Muchos servicios en una aplicación :id=many-services

> [!MEASURED]
> Apple M1, JDK 21, cuatro servicios no relacionados, cada uno con su propio retry, una llamada a cada uno por operación. De [Retry, cuatro servicios](benchmarks.md#retry-cuatro-servicios-2026-10-23).

| Por llamada | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| Overhead, el primer intento tiene éxito | **0.6 ns**, 0 B | 21.3 ns, 80 B |
| Overhead, falla una vez y luego tiene éxito | **9.4 ns**, 0 B | 273 ns, 16 B (sin su `sleep(0)`) |

Con cuatro suppliers decorados compartiendo el call site de Resilience4j, su camino de éxito empieza a asignar 80 B por llamada; las clases generadas de resilience4cata tienen cada una su propio call site y se mantienen en 0 B.

## El gate de cero allocations :id=allocation-gate

Las allocations se comprueban en cada pull request. `./gradlew :benchmark:allocationCheck` ejecuta los benchmarks listados en `benchmark/allocation-budgets.properties` con `-prof gc` y falla si alguno asigna su presupuesto + 1 B por llamada. **Todo camino síncrono de éxito y de rechazo tiene un presupuesto de 0 B**: retry, circuit breaker (ventanas por conteo y por tiempo, abierto), rate limiter, bulkhead, timeout, classifiers, código generado y `Pipeline` (también con una lambda que captura). Los caminos async tienen presupuestos para sus futures (48 B por política). Detalles en [Gate de allocations](benchmarks.md#gate-de-allocations).

Los eventos asignan memoria solo cuando alguien escucha: un tipo de evento sin listeners cuesta un null check en el camino de fallo o de rechazo, que se midió en 0.3 ns en un bulkhead lleno.

## El contrato de rendimiento :id=contract

Los objetivos que el proyecto se impone, y cómo se comprueba cada uno:

| Objetivo | Cómo se comprueba |
|---|---|
| **0 B/op** en el camino de éxito de cada política y de una pila completa | JMH `-prof gc` en CI: `./gradlew :benchmark:allocationCheck` hace fallar un pull request si un benchmark listado alcanza su presupuesto + 1 B/op (0 para todo camino síncrono; valores medidos para los futures async) |
| Sin locks ni `synchronized` en los caminos calientes | revisión de código, escenarios de contención en JMH |
| Código generado monomórfico y en línea recta por método; sin cadenas de lambdas compartidas | JMH con un perfil de JIT contaminado, `-XX:+PrintInlining` |
| Un par de lecturas de reloj por llamada, sin importar el número de políticas | revisión de código |
| Excepciones de rechazo preasignadas y sin stack trace; eventos asignados solo si alguien escucha | escenarios de rechazo en JMH (breaker abierto, bulkhead lleno, rate limiter agotado), con gate de 0 B/op en CI |
| Estructuras lock-free correctas | jcstress, en CI después de cada push a main y cada noche |
| Comparación publicada y reproducible con Resilience4j 2.4.0 | el módulo `benchmark`, con configuración equivalente de Resilience4j y notas de equidad ([Benchmarks en detalle](benchmarks.md)) |

La mayoría de las llamadas reales están limitadas por I/O (milisegundos), así que lo importante es un overhead cercano a cero que se mantiene con muchos threads, no solo la velocidad bruta.

## Qué lo hace barato :id=why

- **Código generado, por método y lineal**: sin una lambda decoradora compartida, así que el JIT hace inline de cada call site con un solo tipo de receptor.
- **Nada asignado en caso de éxito**, y los rechazos lanzan una única excepción preasignada y sin stack trace por instancia.
- **Sin locks** en ningún camino de llamada, y el atómico más débil que sea correcto: acquire/release en lugar de fences completos llevó el bulkhead libre de 23.2 a 9.3 ns en Apple M1 ([Bulkhead, 1 y 4 threads](benchmarks.md#bulkhead-1-y-4-threads-2026-10-23)).
- **Sin escrituras compartidas mientras todo está sano**: un breaker cuya ventana contiene solo éxitos rápidos se comprueba con una lectura por llamada.
- **Sin lecturas del reloj** a menos que una funcionalidad las necesite (detección de llamadas lentas, ventanas por tiempo, rate limiting). Los timeouts usan un thread watchdog compartido en lugar de leer el reloj en cada llamada.

## Reprodúcelo :id=reproduce

Desde un clon del repositorio (JDK 17 o posterior para ejecutar Gradle; usa un toolchain de Java 21):

```bash
./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"                 # one benchmark class
./gradlew :benchmark:jmh -Pjmh="CircuitBreakerBenchmark -t 4 -prof gc"   # four threads
./gradlew :benchmark:jmh -PjmhJdk=25                                     # every benchmark on another JDK
./gradlew :benchmark:allocationCheck                                     # the allocation gate
```

Los resultados se escriben en `benchmark/build/jmh/results.json`. Las fuentes están en `benchmark/src/main/java/dev/resilience4cata/benchmark/`, cada una con su contraparte de Resilience4j y los ajustes hechos por justicia en su Javadoc. Mide en una máquina sin carga, con varios forks, y compara solo números de la misma máquina.
