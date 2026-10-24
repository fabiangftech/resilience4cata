# Benchmarks

> Solo resultados medidos. Cada número de esta página viene de una corrida en este repositorio; lo demás es una hipótesis y va en los documentos de investigación.

## Retry, un solo thread (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"` — código: `benchmark/src/main/java/dev/resilience4cata/benchmark/RetryBenchmark.java`.

**Configuración:** JMH 1.37, JDK 21.0.10, notebook Apple M1, 3 forks × 5 × 1 s de warmup × 5 × 1 s de medición, `-Xms1g -Xmx1g`. Ambas librerías: 3 intentos, espera cero, sin jitter, se reintenta toda excepción, sin listeners de eventos. El fallo es una excepción preasignada y sin stack trace, así que ninguna librería paga por crearla.

| Escenario | Base (sin librería) | resilience4cata 0.1.0-SNAPSHOT | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| El primer intento funciona | 1,14 ± 0,13 ns | **1,55 ± 0,08 ns**, 0 B/op | 12,61 ± 0,10 ns, 0 B/op |
| Falla una vez y después funciona | 3,03 ± 1,43 ns | **7,50 ± 0,75 ns**, 0 B/op | 411,6 ± 15,1 ns, 16 B/op |
| Igual, Resilience4j sin su `Thread.sleep(0)` | — | — | 38,8 ± 3,6 ns, 16 B/op |

**Overhead sobre la base:**
- Camino exitoso: unos **0,4 ns** para resilience4cata contra **11,5 ns** para Resilience4j.
- Un reintento: unos **4,5 ns** contra **36 ns** de la maquinaria de retry de Resilience4j sola, o **409 ns** con el comportamiento por defecto de Resilience4j.

**Cómo leer estos números:**
- Resilience4j 2.4.0 llama a `Thread.sleep` entre intentos aunque la espera sea cero (verificado en su bytecode). Eso explica la mayor parte de los 411 ns. La variante `NoSleep` lo quita con el propio `RetryImpl.setSleepFunction` de Resilience4j, para aislar el costo del resto.
- Es el mejor caso para Resilience4j: un solo supplier decorado, así el JIT ve un único tipo en cada llamada y el escape analysis elimina sus objetos por llamada en el camino exitoso. Las aplicaciones reales decoran muchos métodos. La investigación predice diferencias mayores con un perfil de JIT contaminado ([03 §7.4](research/03-performance-internals.md)); ese escenario todavía no está medido.
- Solo un thread. El retry no tiene estado mutable compartido, así que no se espera que la contención importe; sí importará para el circuit breaker y el rate limiter.
- Una notebook, un JDK. Pendiente: JDK 25, x86-64, perfil contaminado.

## Retry, cuatro servicios (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="MultiTypeRetryBenchmark -prof gc"` — código: `benchmark/src/main/java/dev/resilience4cata/benchmark/MultiTypeRetryBenchmark.java`.

Misma configuración que arriba, pero con cuatro servicios sin relación entre sí (interfaz propia, clase de implementación propia), cada uno llamado desde su propio punto del código, como en una aplicación real. **Una operación llama a los cuatro servicios**; las filas "por llamada" dividen por cuatro.

| Escenario | Base | resilience4cata | Resilience4j 2.4.0 | Resilience4j, sin `Thread.sleep(0)` |
|---|---:|---:|---:|---:|
| El primer intento funciona, por op | 1,87 ± 0,03 ns | **4,39 ± 0,11 ns**, 0 B | 87,1 ± 4,0 ns, 320 B | — |
| ↳ overhead por llamada | — | **0,6 ns**, 0 B | 21,3 ns, 80 B | — |
| Falla una vez y después funciona, por op | 3,57 ± 0,09 ns | **41,1 ± 0,9 ns**, 0 B | 2794 ± 128 ns, 64 B | 1094 ± 13 ns, 64 B |
| ↳ overhead por llamada | — | **9,4 ns**, 0 B | 698 ns, 16 B | 273 ns, 16 B |

**Qué cambió respecto de la corrida con un solo servicio:**
- **Resilience4j empieza a asignar memoria en el camino exitoso: 80 B por llamada**, donde con un solo servicio marcaba 0 B. Los cuatro suppliers decorados pasan por el mismo `supplier.get()` dentro de `Retry.decorateSupplier`. El JIT ya no puede hacer inlining a través de esa llamada, así que el escape analysis deja de eliminar los objetos que Resilience4j crea en cada llamada. Es el efecto que predecía [03 §7.4](research/03-performance-internals.md), ahora medido.
- **resilience4cata sigue en 0 B** y su overhead en el camino exitoso casi no cambia (de 0,4 a 0,6 ns por llamada): cada clase generada tiene su propia llamada, con un solo tipo de receptor.
- Su camino de reintento pasa de 4,5 a 9,4 ns por llamada. El de Resilience4j pasa de 36 a 273 ns por llamada sin el sleep: un fallo que atraviesa llamadas que el JIT no pudo inlinear es mucho más caro de desenrollar.

Pendiente: JDK 25, x86-64, más de un thread.

## Circuit breaker, 1 y 4 threads (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="CircuitBreakerBenchmark -t 4 -prof gc"` — código: `benchmark/src/main/java/dev/resilience4cata/benchmark/CircuitBreakerBenchmark.java`.

Misma máquina y configuración de JMH que arriba. Ambas librerías: ventana por cantidad de 100 llamadas, evaluada después de 100 llamadas, abre con 50% de fallos, sin listeners de eventos. Todos los threads comparten **un** breaker. Resilience4j se mide con sus dos estrategias de ventana: `SYNCHRONIZED` (la de por defecto) y `LOCK_FREE`. En el escenario mixto falla una de cada diez llamadas, así el breaker sigue cerrado mientras su ventana registra fallos. Diferencia en los valores por defecto: Resilience4j siempre mide el tiempo de las llamadas para detectar llamadas lentas; resilience4cata no lo hace salvo que se configure `slowCallDuration`.

| Escenario | Threads | Base | resilience4cata | R4j `SYNCHRONIZED` | R4j `LOCK_FREE` |
|---|---:|---:|---:|---:|---:|
| Todas funcionan | 1 | 1,10 ns | **2,03 ± 0,03 ns**, 0 B | 66,4 ± 1,7 ns, 16 B | 124 ± 16 ns, 224 B |
| Falla una de cada diez | 1 | 1,91 ns | **11,4 ± 0,3 ns**, 0 B | 95,0 ± 4,6 ns, 7 B | 135 ± 7 ns, 243 B |
| Todas funcionan | 4 | 1,44 ns | **3,16 ± 0,08 ns**, 0 B | 477 ± 41 ns, 1 B | 1074 ± 96 ns, 776 B |
| Falla una de cada diez | 4 | 2,82 ns | **202 ± 21 ns**, 0 B | 601 ± 13 ns, 1 B | 1103 ± 64 ns, 768 B |

(Tiempos por llamada; B son bytes asignados por llamada.)

**Overhead sobre la base:** unos **0,9 ns** por llamada exitosa con un thread (Resilience4j: 65 ns) y **1,7 ns** con cuatro threads compartiendo el breaker (Resilience4j: 476 ns).

**Cómo se llegó a ese camino exitoso.** La primera versión que funcionaba costaba 9,5 ns con un thread y 244 ns con cuatro. Cada paso se midió:

| Cambio | 1 thread, éxito | 4 threads, éxito | 4 threads, mixto |
|---|---:|---:|---:|
| Primera versión: contadores atómicos en objetos separados, lecturas acquire | 9,5 ns | 244 ns | 500 ns |
| No escribir cuando la ventana solo tiene éxitos rápidos | 9,5 ns | 10,2 ns | 259 ns |
| Lecturas opaque en vez de acquire (seguro: todo lo que se alcanza es `final`) | 5,7 ns | — | — |
| Contadores como campos de la ventana, sin saltos de punteros | 4,9 ns | — | — |
| Campo `quiet`: una sola lectura por llamada mientras está sano | 2,0 ns | 3,1 ns | 317–351 ns ⚠ |
| Escribir `quiet` solo cuando cambia; cursor en su propio objeto; sin contador `completed` mientras está cerrado | 2,0 ns | 3,2 ns | 202–230 ns |

- **Con contención, lo que domina son las escrituras compartidas, no los locks.** Escribir una línea de caché que otros núcleos leen cuesta más de lo que ahorra un diseño sin locks. La mayor ganancia vino de no escribir nada cuando nada cambia.
- **En ARM, las lecturas acquire no son gratis.** Cada una se compiló a `ldar`; cinco por llamada costaban unos 4 ns.
- **Cada cambio se verificó en los cuatro escenarios.** Dos de ellos empeoraron el caso mixto con cuatro threads antes de corregirse (fila ⚠): poner el cursor junto a los contadores, y reescribir `quiet` en cada fallo. Ambos ponían escrituras por llamada en una línea que todas las llamadas leen.
- **El caso mixto con cuatro threads sigue siendo el cuello de botella** (unos 200 ns): cada llamada avanza un cursor compartido para que la ventana guarde exactamente las últimas 100 llamadas. Relajar esa semántica (por ejemplo con ventanas por franjas) podría eliminarlo; es una decisión de diseño, no está hecha.
- La estrategia `LOCK_FREE` de Resilience4j es más lenta que su opción por defecto en todos estos escenarios y asigna entre 200 y 800 B por llamada.
- Una notebook (Apple M1: 4 núcleos de rendimiento + 4 de eficiencia), JDK 21. Pendiente: JDK 25, x86-64, 8 o más threads.

## Circuit breaker, ventana por tiempo (2026-10-23)

Misma clase de benchmark (métodos `*Time*`): ventana de 10 segundos, evaluada después de 100 llamadas, umbral de 50%; Resilience4j `TIME_BASED` con su estrategia por defecto `SYNCHRONIZED`.

| Escenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Todas funcionan | 1 | **46,6 ± 0,3 ns**, 0 B | 111,4 ± 0,5 ns, 0 B |
| Falla una de cada diez | 1 | **69 ± 19 ns**, 0 B | 135,3 ± 0,8 ns, 0 B |
| Todas funcionan | 4 | **164 ± 28 ns**, 0 B | 703 ± 52 ns, 41 B |
| Falla una de cada diez | 4 | **191 ± 11 ns**, 0 B | 744 ± 53 ns, 41 B |

**El reloj domina.** Una ventana por tiempo tiene que leer el reloj en cada llamada, y en esta máquina `System.nanoTime()` cuesta por sí solo **21 ns con un thread y 163 ns con cuatro** (medido aparte). En macOS, HotSpot mantiene `nanoTime` monótono entre threads con un compare-and-set sobre un valor global, así que las llamadas concurrentes compiten por él; en Linux es una llamada vDSO sin estado compartido. Sin el reloj, la ventana por tiempo de resilience4cata cuesta unos 25 ns con un thread y unos pocos nanosegundos con cuatro. Todos los contadores están repartidos (`LongAdder`) para que los éxitos no compitan; la única asignación es un bucket por segundo.

Dos consecuencias:
- La ventana por cantidad, que no lee el reloj salvo que se configure `slowCallDuration`, es mucho más barata cuando sirven las dos. Es la opción por defecto.
- Un reloj aproximado en caché (actualizado por un thread de fondo cada milisegundo) eliminaría la mayor parte de este costo. No está hecho: implica un thread daemon por JVM y resolución de milisegundos.

## Timeout, 1 y 4 threads (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="TimeoutBenchmark -t 4 -prof gc"` — código: `benchmark/src/main/java/dev/resilience4cata/benchmark/TimeoutBenchmark.java`.

Llamadas que terminan a tiempo (trabajo trivial, timeout de 1 s). resilience4cata ejecuta la llamada en el thread del llamador y un watchdog compartido la interrumpiría si se atrasara. El `TimeLimiter` de Resilience4j necesita un `Future`, así que una llamada bloqueante tiene que pasarse a otro thread: así se usa con código síncrono, y eso es lo que se mide (un pool fijo de 4 platform threads).

| Threads | Base | resilience4cata | Resilience4j 2.4.0 (`TimeLimiter` + executor) |
|---:|---:|---:|---:|
| 1 | 1,10 ns | **15,6 ± 0,1 ns**, 0 B | 9 225 ± 1 353 ns, 189 B |
| 4 | 1,44 ns | **20,2 ± 2,4 ns**, 0 B | 19 194 ± 3 763 ns, 160 B |

- Unos **14,5 ns de overhead por llamada**, unas 600 veces menos que pasar la llamada a otro thread. Sin leer el reloj y sin escrituras compartidas por llamada: cada thread escribe solo en su propio scope reutilizable.
- Es más de lo que cuestan las otras políticas (retry 0,4 ns, circuit breaker 0,9 ns). Candidatos probables, todavía sin investigar: la búsqueda en el `ThreadLocal` de los scopes del thread y el compare-and-set al terminar la llamada.
- El precio de no leer el reloj es la precisión: el watchdog revisa cada `timeout / 16` del timeout más corto en uso (entre 1 y 50 ms) e interrumpe una llamada entre su timeout y unos dos ticks después, nunca antes.
