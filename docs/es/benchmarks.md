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

## Bulkhead, 1 y 4 threads (2026-10-23)

`./gradlew :benchmark:jmh -Pjmh="BulkheadBenchmark -t 4 -prof gc"` — código: `benchmark/src/main/java/dev/resilience4cata/benchmark/BulkheadBenchmark.java`.

Todos los threads comparten un bulkhead. *Libre*: un límite de 1000 que nunca se alcanza, sin espera. *Modo con espera*: el mismo límite con `maxWait = 100ms`, así resilience4cata usa su modo `Semaphore`. *Lleno*: un límite de 1 ocupado por el setup, así se rechazan todas las llamadas. Resilience4j: `SemaphoreBulkhead`, mismos límites, stack traces desactivados en su excepción de rechazo.

| Escenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Libre | 1 | **9,3 ± 0,2 ns**, 0 B | 22,9 ± 0,1 ns, 0 B |
| Libre | 4 | **324 ± 15 ns**, 0 B | 445 ± 4 ns, 0 B |
| Modo con espera, libre | 1 | 23,2 ± 0,3 ns, 0 B | 23,4 ± 1,2 ns, 0 B |
| Modo con espera, libre | 4 | 447 ± 6 ns, 0 B | 451 ± 12 ns, 0 B |
| Lleno (se rechaza todo) | 1 | **2,4 ± 0,6 ns**, 0 B | 143 ± 11 ns, 464 B |
| Lleno (se rechaza todo) | 4 | **3,3 ± 0,4 ns**, 0 B | 267 ± 41 ns, 464 B |

- **Los rechazos son entre 60 y 80 veces más baratos**: un bulkhead lleno solo lee su contador (sin escribir, así que sin contención) y lanza una excepción preasignada. Con sobrecarga, que es cuando un bulkhead importa, eso evita que el camino de rechazo se vuelva un cuello de botella.
- **Con espacio libre: 9,3 ns contra 22,9 ns con un thread.** La primera versión medía 23,2 ns, igual que Resilience4j: usaba atómicos con barrera completa (volatile). Tomar un slot solo necesita semántica *acquire* y liberarlo *release*, igual que un lock; en ARM eso convierte `casal`/`ldaddal` en instrucciones más baratas y lo bajó a 9,3 ns (de 421 a 324 ns con cuatro threads).
- **Con cuatro threads, ambas librerías pagan la contención** (300–450 ns): un límite global exacto necesita un contador compartido que se escribe dos veces por llamada. Repartir el límite en franjas podría reducirla, a cambio de un límite que solo se respeta por franja; no está hecho.
- El modo con espera usa el `Semaphore` del JDK, como Resilience4j, así que cuesta lo mismo.
- El mismo cambio a un orden más débil podría aplicarse al compare-and-set de fin de llamada del timeout y a las actualizaciones de la ventana del circuit breaker; todavía no se midió.

## Rate limiter, 1 y 4 threads (2026-10-24)

`./gradlew :benchmark:jmh -Pjmh="RateLimiterBenchmark -t 4 -prof gc"` — fuente: `benchmark/src/main/java/dev/resilience4cata/benchmark/RateLimiterBenchmark.java`.

Todos los threads comparten un limiter, y ninguna de las dos librerías espera un permiso. *Free*: mil millones de permisos por segundo, que nunca se alcanzan, así que pasan todas las llamadas. *Exhausted*: un permiso por hora, que toma el setup, así que todas las llamadas se rechazan. Resilience4j: su `AtomicRateLimiter` por defecto, con los mismos límites y sin stack trace en su excepción de rechazo. Las dos librerías leen `System.nanoTime()` una vez por llamada.

| Escenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Free | 1 | **25,3 ± 0,4 ns**, 0 B | 57,5 ± 5,2 ns, 40 B |
| Free | 4 | **131 ± 1 ns**, 0 B | 206 ± 7 ns, 40 B |
| Exhausted (todo rechazado) | 1 | **22,9 ± 0,1 ns**, 0 B | 243 ± 12 ns, 560 B |
| Exhausted (todo rechazado) | 4 | **164 ± 1 ns**, 0 B | 1763 ± 60 ns, 524 B |

- **Casi todo el costo de resilience4cata es el reloj.** `System.nanoTime()` cuesta 21 ns con un thread y 163 ns con cuatro en esta máquina (ver la sección de la ventana por tiempo); el limiter agrega unos 2–4 ns con un thread. Un rechazo solo lee, así que con cuatro threads un limiter agotado cuesta exactamente lo que cuesta el reloj con contención. En Linux, donde `nanoTime` no tiene estado compartido, estos números deberían ser mucho menores; todavía no está medido.
- **Los rechazos son 10 veces más baratos y no asignan memoria**; Resilience4j crea un `State` nuevo por intento y ~520 B por llamada rechazada.
- **Contención: la primera versión perdía con cuatro threads, 658 ns contra 214 ns.** Cada llamada admitida escribe el único `tat` compartido, y quien perdía el compare-and-set reintentaba de inmediato, así que la línea de caché rebotaba entre núcleos. Cambiar la operación atómica (fuerte, débil, acquire) no cambió nada; esperar activamente con `onSpinWait` (una vez o con retroceso exponencial) y `Thread.yield()` casi no ayudó (565–665 ns y 414 ns). Estacionar el thread 1 ns después de perder el compare-and-set, como hace Resilience4j, lo bajó a **131 ns** (351 ns contra 523 ns de Resilience4j con ocho threads). El costo pasa a la llamada que perdió la carrera: en Linux, el estacionamiento más corto de un thread de plataforma dura unos 50 µs por el *timer slack*. Solo ocurre cuando las llamadas admitidas llegan más rápido de lo que la línea de caché puede moverse entre núcleos, muy por encima de cualquier tasa con la que normalmente se configura un limiter.

## Métodos async, 1 thread (2026-10-24)

`./gradlew :benchmark:jmh -Pjmh="AsyncBenchmark -prof gc"` — fuente: `benchmark/src/main/java/dev/resilience4cata/benchmark/AsyncBenchmark.java`.

Solo el camino exitoso: el delegado devuelve un `CompletableFuture` ya completado, y cada benchmark devuelve el stage que recibe el llamador. Resilience4j usa sus variantes `decorateCompletionStage` con la misma configuración; su retry y su time limiter reciben un `ScheduledExecutorService` de un thread. *Stack* anida retry, circuit breaker, timeout y bulkhead en el mismo orden en las dos librerías. Las filas de circuit breaker, rate limiter, timeout y stack se repitieron con 6 forks porque la primera corrida tuvo mucho ruido (±137 ns en el breaker de resilience4cata).

| Política | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| Circuit breaker | **18,8 ± 0,5 ns**, 48 B | 85,8 ± 3,7 ns, 71 B |
| Retry | **19,3 ± 1,6 ns**, 48 B | 28,7 ± 1,4 ns, 48 B |
| Bulkhead | **28,1 ± 0,8 ns**, 48 B | 33,1 ± 9,5 ns, 48 B |
| Rate limiter | **48,0 ± 1,6 ns**, 48 B | 63,6 ± 1,3 ns, 88 B |
| Timeout | **213 ± 19 ns**, 200 B | 3551 ± 1772 ns, 184 B |
| Stack (retry, breaker, timeout, bulkhead) | **344 ± 21 ns**, 362 B | 3600 ± 2205 ns, 352 B |

- **La asignación de memoria es más o menos la misma que la de Resilience4j**: cada capa devuelve un future nuevo (48 B). Async no puede ser libre de asignaciones; el camino sincrónico no cambió con este trabajo.
- **El timeout domina en las dos librerías.** Cada llamada programa una tarea en un `ScheduledThreadPoolExecutor` y la cancela cuando el stage termina: un lock más una inserción y un borrado en un heap. resilience4cata saca las tareas canceladas de inmediato (`setRemoveOnCancelPolicy`). Los números de Resilience4j varían mucho entre forks (1,8–5,3 µs); no lo investigué. Un timer sin lock, como el watchdog sincrónico, debería bajar los 213 ns de resilience4cata; todavía no está hecho.
- La fila del rate limiter incluye un `System.nanoTime()` (≈21 ns, ver arriba); el breaker no lee el reloj salvo que se configure `slowCallDuration`.

## Timer nuevo y espera async del bulkhead (2026-10-24)

Misma máquina y configuración. Se corrió después de integrar el trabajo en paralelo sobre el timer async (`TimeoutTimer`), el protocolo del timeout sincrónico y la espera async del bulkhead, un benchmark a la vez con la máquina libre.

**Timeout async.** `*TimeoutPending` devuelve un future todavía pendiente, que el benchmark completa justo después de la llamada, así se miden la programación y la cancelación del timer; `baselinePending` es lo mismo sin ninguna librería (14,5 ns, 24 B). Ahí Resilience4j recibe un scheduler con remove-on-cancel. `*Timeout` devuelve un future ya completado: resilience4cata lo reconoce y no usa el timer (no puede vencer).

| Escenario | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Stage pendiente | 1 | **127 ± 10 ns**, 192 B | 257 ± 11 ns, 234 B |
| Stage pendiente | 4 | **510 ± 35 ns**, 192 B | 928 ± 60 ns, 234 B |
| Stage completado | 1 | **7,1 ± 0,2 ns**, 24 B (antes 213 ns) | 3795 ± 3163 ns, 181 B |
| Stage completado | 4 | **11,2 ± 0,6 ns**, 24 B | 3173 ± 1003 ns, 180 B |

- El timer sin lock cuesta la mitad que el scheduler de Resilience4j en una llamada async con timeout, incluso dándole a Resilience4j remove-on-cancel. Con cuatro threads las dos librerías pagan estado compartido: las franjas de buzones por thread de resilience4cata y la completación del future del resultado.
- Cambio de comportamiento: los timeouts async ahora funcionan por ticks como los sincrónicos (nunca antes de tiempo, como mucho unos dos ticks tarde, tick = timeout async más corto / 16, entre 1 y 50 ms), mientras que el `ScheduledThreadPoolExecutor` tenía precisión de menos de un milisegundo.

**Timeout sincrónico** (`TimeoutBenchmark`), después de sacar una búsqueda de ThreadLocal por llamada y usar un CAS acquire al terminar la llamada:

| Threads | Antes | Después | Resilience4j 2.4.0 |
|---:|---:|---:|---:|
| 1 | 15,6 ns | **13,1 ± 0,1 ns** | 9039 ± 439 ns |
| 4 | 20,2 ns | **15,9 ± 0,7 ns** | 18423 ± 771 ns |

El costo sobre la línea base baja de unos 14,5 a 12 ns con un thread. Un CAS solo release sería todavía más barato, pero permite una interrupción antes de tiempo (ver el comentario en `TimeoutPolicy.end`), así que no se usa.

**Bulkhead, camino sincrónico después de la cola de espera async** (`BulkheadBenchmark`): `exit()` con `maxWait > 0` hace ahora una lectura volatile más, para ver si hay llamadas async en cola. Sin costo medible: *Waiting* 23,1 ± 0,05 ns (antes 23,2) con un thread y 442 ± 3 ns (antes 447) con cuatro; *Free* 9,2 ns y *Full* 2,2 ns, sin cambios.

## Corridas en paralelo en AWS (`infra/`)

`infra/bench.sh <rev-base> <rev-nueva> [regex-de-inclusión]` corre todos los benchmarks en máquinas EC2 temporales, en paralelo, y compara dos revisiones de git. Una corrida completa (150 unidades de benchmark por cantidad de threads, 283 ejecuciones de JMH) tarda unos 20 minutos de punta a punta, en lugar de unas 3 horas en una sola máquina, y cuesta bastante menos de US$1.

- **Preparación.** Cada revisión se compila en local en un clon limpio; el classpath de ejecución de sus benchmarks se empaqueta y se sube a un bucket S3 propio de la corrida, así las máquinas solo instalan un JDK (Amazon Corretto 21) y ejecutan `org.openjdk.jmh.Main`. Terraform (`infra/main.tf`) crea una VPC descartable, el bucket, un rol de instancia limitado a ese bucket, y las máquinas; `bench.sh` destruye todo al final, también si algo falla.
- **Comparación justa.** Una unidad es un método de benchmark con una cantidad de threads. Sus ejecuciones base y nueva corren seguidas en la misma máquina (alternando cuál va primero), así cada comparación base/nueva se hace sobre el mismo hardware. Las unidades se reparten de la más larga a la más corta, cada una a la máquina menos cargada (`infra/plan.py`).
- **Hardware.** `c7g.xlarge`: 4 cores Graviton3 sin SMT y con frecuencia fija (sin turbo), lo que hace intercambiables a las máquinas: cada una corre además un benchmark de calibración (`BulkheadBenchmark.resilience4cataFree`, 1 thread), y en 19 máquinas midió entre 20,45 y 20,47 ns (0,1 % de dispersión). La configuración de JMH es la de los propios benchmarks (3 forks × 5 × 1 s de calentamiento y de medición, `-prof gc`).
- **Límites de la cuenta.** Esta cuenta permite solo 3 instancias spot a la vez (aparte de su cuota de 96 vCPU spot) y 64 vCPU on-demand, así que una corrida usa 3 máquinas spot y 16 on-demand del mismo tipo (`MACHINES`, `SPOT_MACHINES`).
- Los números de Graviton no se comparan con los de Apple Silicon de las secciones anteriores; se compara base contra nueva dentro de una misma corrida.

## Cierre del MVP: classifier, pipeline, eventos, testkit (2026-10-24, AWS Graviton3)

Base: commit `3bc496f`. Nueva: el trabajo integrado del classifier, el `Pipeline` programático, eventos y snapshots, y el testkit (`PolicyEnvironment`). Medido con `infra/bench.sh` como se describe arriba. Dos regresiones que aparecieron en la primera corrida se corrigieron y se volvieron a medir; la tabla muestra los números finales.

**Chequeo de regresiones** (resilience4cata, ns/op; todo 0 B/op en los caminos sincrónicos):

| Benchmark | Threads | Base | Nueva | Cambio |
|---|---:|---:|---:|---:|
| Retry, éxito | 1 | 1.4 ± 0.0 | 1.2 ± 0.1 | −11% |
| Retry, éxito | 4 | 1.3 ± 0.0 | 1.4 ± 0.1 | +3% |
| Retry, un reintento | 1 | 7.3 ± 0.1 | 6.5 ± 0.1 | −11% |
| Retry, un reintento | 4 | 7.3 ± 0.1 | 6.6 ± 0.1 | −10% |
| Retry, cuatro servicios, un reintento | 1 | 38.4 ± 0.1 | 38.8 ± 0.1 | +1% |
| Retry, cuatro servicios, un reintento | 4 | 39.2 ± 0.6 | 37.6 ± 1.2 | −4% |
| Circuit breaker, éxito | 1 | 1.9 ± 0.0 | 1.9 ± 0.0 | 0% |
| Circuit breaker, éxito | 4 | 1.9 ± 0.0 | 1.9 ± 0.0 | 0% |
| Circuit breaker, mixto | 1 | 17.5 ± 0.1 | 17.2 ± 0.0 | −2% |
| Circuit breaker, mixto | 4 | 282 ± 10 | 274 ± 29 | −3% |
| Circuit breaker (ventana por tiempo), mixto | 4 | 126 ± 9 | 130 ± 10 | +3% |
| Rate limiter, libre | 1 | 42.2 ± 0.1 | 41.8 ± 0.1 | −1% |
| Rate limiter, libre | 4 | 188 ± 2 | 187 ± 2 | 0% |
| Rate limiter, agotado | 4 | 35.0 ± 0.3 | 34.7 ± 0.2 | −1% |
| Bulkhead, libre | 1 | 20.5 ± 0.0 | 20.5 ± 0.0 | 0% |
| Bulkhead, lleno (rechazando) | 1 | 2.1 ± 0.1 | 2.3 ± 0.0 | **+12%** |
| Bulkhead, lleno (rechazando) | 4 | 2.0 ± 0.0 | 2.3 ± 0.0 | **+15%** |
| Bulkhead, modo con espera | 1 | 18.9 ± 0.0 | 18.9 ± 0.0 | 0% |
| Bulkhead, modo con espera | 4 | 359 ± 36 | 338 ± 38 | −6% |
| Timeout (sincrónico) | 1 | 22.4 ± 0.2 | 22.2 ± 0.2 | −1% |
| Timeout (sincrónico) | 4 | 23.5 ± 0.3 | 23.5 ± 0.5 | 0% |
| Circuit breaker async | 1 | 24.4 ± 0.1 | 24.4 ± 0.1 | 0% |
| Retry async | 1 | 24.1 ± 0.1 | 24.1 ± 0.1 | 0% |
| Rate limiter async | 4 | 275 ± 1 | 273 ± 6 | −1% |
| Timeout async, stage pendiente | 1 | 152 ± 17 | 152 ± 12 | 0% |
| Stack async (cinco políticas) | 1 | 122 ± 0.3 | 122 ± 0.2 | 0% |

- **Los rechazos del bulkhead cuestan 0,3 ns más** (medido también en Apple Silicon: 2,06 → 2,36 ns). Un bulkhead lleno ahora revisa el campo `final` del listener de eventos antes de devolver `false`: es el precio de los eventos de rechazo. Los caminos exitosos no tienen esa revisión.
- **Corregido: fallas de retry bajo contención.** La primera corrida mostró *cuatro servicios, un reintento* con 4 threads pasando de 37 a 57 ± 22 ns, con forks repartidos entre ~40 y 60–85 ns. `RetryPolicy` había sumado un segundo `ClassValue` (para el veredicto del classifier) que sin classifier nunca se consultaba. Todos los `ClassValue` comparten la pequeña caché de búsqueda de la clase de la excepción, y en algunas JVM el `ClassValue` extra hacía que las búsquedas chocaran y cayeran al camino lento de `ClassValue`, que escribe en esa caché compartida. Un solo `ClassValue` por política, que devuelve un veredicto de tres valores, devolvió forks parejos (39,2 → 37,6 ns).
- **Corregido: timeout sincrónico, un thread** (22,4 → 23,8 ns, reproducido dos veces). La rama de timeout de `TimeoutPolicy.end` había crecido con la llamada del evento, lo que afecta cómo el JIT inlinea el método que corre en cada llamada. Mover esa rama a su propio método la dejó otra vez en 22,2 ns.
- En la primera corrida, *Bulkhead, modo con espera* con 4 threads dio 366 → 452 ns; una repetición dio 359 → 338 ns, así que esa diferencia era ruido entre ejecuciones de la JVM, no una regresión.

**Contra Resilience4j 2.4.0 en Graviton3** (revisión nueva, ns/op y B/op):

| Benchmark | Threads | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| Retry, éxito | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Retry, un reintento (Resilience4j sin su `sleep(0)`) | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, éxito (Resilience4j: ventana por cantidad, estrategia `SYNCHRONIZED` por defecto) | 1 | **1.9 ns**, 0 B | 89.2 ns, 0 B |
| Circuit breaker, éxito | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Circuit breaker, mixto | 4 | **274 ns**, 0 B | 910 ns, 2 B |
| Rate limiter, libre | 4 | **187 ns**, 0 B | 275 ns, 40 B |
| Rate limiter, agotado | 4 | **34.7 ns**, 0 B | 1599 ns, 505 B |
| Bulkhead, libre | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |
| Bulkhead, libre | 4 | **264 ns**, 0 B | 432 ns, 0 B |
| Bulkhead, lleno | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Timeout (sincrónico) | 1 | **22.2 ns**, 0 B | 8842 ns, 176 B |
| Timeout async, stage pendiente | 1 | **152 ns**, 192 B | 598 ns, 240 B |
| Timeout async, stage pendiente | 4 | **369 ns**, 192 B | 1563 ns, 234 B |

**Classifier** (`ClassifierBenchmark`: un classifier por resultado sobre la llamada protegida; todas las llamadas son exitosas):

| Benchmark | Threads | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| Circuit breaker, sin classifier | 1 | 2.1 ns, 0 B | — |
| Circuit breaker + classifier | 1 | **2.4 ns**, 0 B | 102 ns, 24 B (`recordResult`) |
| Circuit breaker + classifier | 4 | **2.4 ns**, 0 B | 902 ns, 18 B |
| Retry + circuit breaker + classifier | 1 | **2.7 ns**, 0 B | 148 ns, 104 B (`retryOnResult` + `recordResult`) |
| Retry + circuit breaker + classifier | 4 | **2.8 ns**, 0 B | 1274 ns, 90 B |

Un classifier cuesta unos 0,3 ns por política en una llamada exitosa: una llamada directa y monomórfica a `classify`.

**`Pipeline` programático** (`PipelineBenchmark`, ns/op; el código generado y el pipeline asignan 0 B):

| Políticas | Threads | Generado (anotaciones) | `Pipeline` | Resilience4j |
|---|---:|---:|---:|---:|
| Retry + circuit breaker | 1 | 2.3 | 2.9 | 175, 288 B |
| Retry + circuit breaker | 4 | 2.4 | 3.0 | 1647, 830 B |
| Retry, breaker, rate limiter, bulkhead | 1 | 61.6 | 62.3 | 261, 328 B |
| Retry, breaker, rate limiter, bulkhead | 4 | 318 | 294 | 1234, 350 B |
| Las seis políticas | 1 | 76.3 | 80.3 | — |
| Lambda que captura (retry + breaker) | 1 | 2.4 | 2.8 | 190, 336 B |

- El pipeline agrega unos 0,5 ns sobre el código generado en estos stacks y sigue sin asignar memoria, también con una lambda que captura un argumento en cada llamada (el escape analysis la elimina).
- `pipelinePolluted` (el mismo pipeline después de que otros cinco pipelines pasaron por `Pipeline.execute`) mide 7,9 ns con un thread contra 2,9 ns sin contaminar: el único punto de llamada `call.call()` dentro de `Pipeline` se vuelve megamórfico en una aplicación con muchos pipelines. El código generado tiene un punto de llamada por método y no paga ese costo.
- Resilience4j no tiene un timeout sincrónico en el thread del llamador, así que no hay columna de Resilience4j para seis políticas.

## Clases anotadas, genéricos, overrides de config, bulkhead con ticker (2026-10-24, AWS Graviton3)

Base: commit `53a6651`. Nueva: clases anotadas (estilo subclase), `@Fallback` en métodos genéricos y stages async con comodín, `onExhaustedResult` / `GaveUp` cuando falla el classifier / `Pipeline.run` y fallbacks async del pipeline, overrides por instancia en `config`, y el `maxWait` del bulkhead sincrónico con un ticker manual. Mismo método que la sección anterior (`infra/bench.sh`, 19 `c7g.xlarge`, 160 unidades, 329 ejecuciones; calibración 20,51–20,54 ns, 0,1 % de dispersión).

**Chequeo de regresiones** (resilience4cata, ns/op, 1 thread salvo que se indique; todo 0 B/op en los caminos sincrónicos):

| Benchmark | Base | Nueva | Cambio |
|---|---:|---:|---:|
| Retry, éxito | 1.3 ± 0.0 | 1.3 ± 0.0 | +2% |
| Retry, un reintento | 6.5 ± 0.0 | 6.5 ± 0.0 | +1% |
| Retry, cuatro servicios, un reintento (4 threads) | 37.8 ± 0.9 | 37.7 ± 0.5 | 0% |
| Circuit breaker, éxito | 1.8 ± 0.0 | 1.9 ± 0.0 | +1% |
| Circuit breaker, mixto | 17.2 ± 0.0 | 17.2 ± 0.0 | 0% |
| Rate limiter, libre | 41.7 ± 0.1 | 41.7 ± 0.1 | 0% |
| Rate limiter, agotado | 34.2 ± 0.1 | 34.6 ± 0.1 | +1% |
| Bulkhead, libre | 20.5 ± 0.0 | 20.5 ± 0.0 | 0% |
| Bulkhead, lleno | 2.3 ± 0.0 | 2.3 ± 0.0 | −1% |
| Bulkhead, modo con espera | 18.9 ± 0.0 | 18.9 ± 0.0 | 0% |
| Classifier en retry + breaker | 2.8 ± 0.0 | 2.7 ± 0.0 | −3% |
| Circuit breaker async | 24.4 ± 0.1 | 24.4 ± 0.1 | 0% |
| Timeout async, stage pendiente | 149 ± 12 | 149 ± 12 | 0% |
| Stack async (cinco políticas) | 122 ± 0.2 | 122 ± 0.2 | 0% |
| Timeout (sincrónico) | 22.1 ± 0.2 | 23.7 ± 0.1 | **+7%** |
| Timeout (sincrónico), 4 threads | 23.6 ± 0.2 | 23.4 ± 0.3 | −1% |

- Con un thread todo queda dentro de ±2 %, salvo el timeout sincrónico. Con cuatro threads las diferencias van en las dos direcciones dentro de su margen de error (por ejemplo *Bulkhead, libre* 258 → 228 ± 32 ns, *Circuit breaker, mixto* 257 → 271 ± 22 ns).
- **Pendiente: timeout sincrónico +1,5 ns con un thread.** Es estable (6 forks: 22,1–22,4 contra 23,6–23,8 ns, también con `-XX:-TieredCompilation`: 22,3–22,5 contra 23,6–23,8 ns), pero el código que ejecuta no cambió: el `TimedService$Resilient` generado, `TimeoutPolicy` y `Watchdog` son idénticos, el estado resuelto de la política es idéntico, y el `ThreadLocal` del watchdog cae en su slot directo en los dos builds. Con cuatro threads no hay diferencia. La "corrección" de la sección anterior para el mismo número (sacar la rama de timeout de `end`) puede haber sido el mismo efecto de layout en sentido contrario. Paso siguiente: `-prof perfasm` en las máquinas (necesita `perf` y `hsdis`) para comparar el código compilado.
- La capa de retry con classifier ganó un `try`/`catch` alrededor de `classify` (para el `GaveUp` cuando el classifier lanza una excepción); los benchmarks del classifier no muestran costo.

**Nuevo en esta ronda** (ns/op, 0 B/op):

| Benchmark | Threads | Resultado |
|---|---:|---:|
| Retry, éxito: clase anotada (subclase) vs wrapper de interfaz | 1 | **1.0** vs 1.2 |
| Retry, un reintento: clase anotada vs wrapper de interfaz | 1 | **5.6** vs 6.5 |
| Retry, un reintento: clase anotada vs wrapper de interfaz | 4 | **5.5** vs 6.6 |
| `Pipeline.run` (llamada void), retry + circuit breaker | 1 | 3.0 (`execute`: 2.9) |

- El estilo subclase es un poco más rápido que el wrapper: el override llama directo a `super.metodo()` en vez de pasar por un campo `delegate`, una carga y una llamada de interfaz menos.
- `Pipeline.run` cuesta lo mismo que `execute` y no asigna memoria.
- Sin medir, a propósito: los overrides por instancia de config (se resuelven una vez al crear la instancia) y el modo ticker del bulkhead (solo relojes de test; el camino por defecto suma la lectura de un `boolean` `final` después de un `tryAcquire` fallido, y *Bulkhead, modo con espera* no cambia).

## Presupuesto de reintentos, 0.3.0 (2026-10-25, AWS Graviton3)

Base: `6d82305` (0.2.0). Nueva: la integración de 0.3.0 (presupuesto de reintentos, módulos de Micrometer, OpenTelemetry y JUnit, `onInstanceCreated`). Mismo método que arriba (`infra/bench.sh`, `c7g.xlarge`; calibración 20,48–20,53 ns, 0,2 % de dispersión). Todos los caminos sincrónicos siguen en 0 B/op.

**Sin regresión cuando no hay presupuesto** (ns/op, 1 / 4 threads):

| Benchmark | Base | Nueva |
|---|---:|---:|
| `RetryBenchmark.resilience4cataSuccess` | 1,4 / 1,3 | 1,2 / 1,3 |
| `MultiTypeRetryBenchmark.resilience4cataSuccess` | 4,3 / 4,3 | 3,9 / 4,0 |
| `PipelineBenchmark.pipelineRetryCircuitBreaker` | 2,9 / 2,9 | 2,9 / 2,9 |
| `RetryBenchmark.resilience4cataRetryOnce` | 6,7 / 6,5 | 6,8 / 6,7 |
| `SubclassBenchmark.wrapperRetryOnce` | 6,6 / 6,5 | 6,8 / 6,8 |

Una llamada reintentada cuesta 0,1–0,3 ns más (la comprobación de si hay presupuesto, en el camino de falla); los éxitos no cambian.

**Cómo el presupuesto cuenta cada llamada** (`RetryBudgetBenchmark`, `budgetPercent = 1000` para que todo reintento se conceda; ns/op, 1 / 4 threads; sin presupuesto: 1,3 / 1,3 con éxito, 6,9 / 7,1 reintentada):

| Cómo se cuenta una llamada | Éxito | Reintentada una vez |
|---|---:|---:|
| Bucle de compare-and-set sobre la parte del thread (`threadId & mask`) | 9,7 / 9,8 | 64 / 380 |
| Una suma atómica (`getAndAddRelease`, LDADD) sobre una parte elegida por hash | 12,0 / 12,0 | 73 / 400 |
| **Lectura y escritura simples sobre una parte elegida por hash (la publicada)** | **3,5 / 3,6** | **62 / 301** |

Dos lecciones. Un compare-and-set, aun sin contención sobre una línea que ningún otro thread escribe, costó unos 8 ns por llamada en Graviton3, y la suma atómica de una instrucción más; el contador publicado es en cambio aproximado (dos threads que comparten parte y compiten pueden perder una llamada, lo que solo reduce el presupuesto). Y elegir la parte con los bits bajos del id del thread puso a todos los workers de JMH en una sola parte (sus ids eran múltiplos de la cantidad de partes): la versión con compare-and-set lo pagó en contención (380 ns), y la aproximada perdió tantas llamadas que el presupuesto rechazaba reintentos aun con diez veces el margen necesario. Mezclar el id primero (hash de Fibonacci) arregló ambas cosas. Con 4 threads, una llamada reintentada está dominada por el contador compartido de reintentos y la lectura del reloj, solo en el camino de falla.

## Hedge y cancelación async, 0.4.0 (2026-10-25, AWS Graviton3)

Base: `4fc079a` (0.3.0 más docs). Nueva: la integración de 0.4.0 (cancelación hacia adentro en toda política async, `@Hedge`, subclases generadas que ya no son `final`). Mismo método que arriba (`infra/bench.sh`, `c7g.xlarge`; calibración 20,51–20,54 ns).

**Cancelación async.** La primera versión enlazaba el future de cada política con su stage interno mediante un store con release en el constructor. En Graviton3, C2 lo compiló como dos barreras completas extra (`dmb ish`) por capa de política, unos 5 ns cada una (circuit breaker async 24,4 → 29,1 ns); en Apple Silicon esas mismas barreras quedaban dentro del ruido. Un store plano alcanza (el future siempre se entrega con una relación happens-before) y baja el costo a unos 0,5 ns por capa (ns/op, 1 / 4 threads; asignaciones sin cambios):

| Benchmark | Base | Store con release | Store plano (publicado) |
|---|---:|---:|---:|
| `AsyncBenchmark.resilience4cataCircuitBreaker` | 24,4 / 24,9 | 29,1 / 29,9 | 24,9 / 25,6 |
| `AsyncBenchmark.resilience4cataRetry` | 24,1 / 24,6 | 28,9 / 29,5 | 24,7 / 25,3 |
| `AsyncBenchmark.resilience4cataBulkhead` | 41,8 / 392 | 46,2 / 503 | 42,8 / 418 |
| `AsyncBenchmark.resilience4cataRateLimiter` | 66,0 / 275 | 71,2 / 294 | 66,9 / 278 |
| `AsyncBenchmark.resilience4cataStack` | 121,5 / 573 | 139,3 / 554 | 123,5 / 558 |

Un refactor del mismo cambio también movió el callback async del bulkhead a un helper compartido; el nivel extra de llamada impidió que C2 inlineara el camino apilado, y el escape analysis dejó de eliminar un objeto (la pila de cuatro políticas pasó de 200 a 224 B/op). El gate de allocations lo detectó; el callback volvió a un solo método.

**Hedge** (nuevo, sin base; ns/op y B/op, 1 / 4 threads). `resilience4j-hedge` de Resilience4j 2.4.0 con la misma configuración (los ajustes están en el Javadoc de `HedgeBenchmark`):

| Benchmark | resilience4cata | Resilience4j 2.4.0 | Hedge a mano con `CompletableFuture` |
|---|---:|---:|---:|
| primer intento ya completado | 9,4 / 9,7 ns, 24 B | — | — |
| el primer intento gana antes de la espera | 154 / 267 ns, 216 B | 3601 / 5577 ns, 1561 B | 649 / 1968 ns, 297 B |
| igual, sin presupuesto de hedges | 150 / 268 ns, 216 B | | |
| el hedge salta (espera de 1 ms; µs) | 2114 / 2116 µs | 1067 / 1070 µs | |

Cuando el hedge salta, el tiempo es la espera más la precisión del timer de cada librería: el nuestro trabaja en ticks de un dieciseisavo de la espera, al menos 1 ms, y salta hasta unos dos ticks tarde, así que una espera de 1 ms hace el hedge después de unos 2 ms; el executor programado de Resilience4j salta a tiempo pero deja encolada cada espera cancelada, que es donde se va la mayor parte de su costo en el camino común. Las subclases generadas sin `final` no cambiaron nada medible (`SubclassBenchmark` dentro del ruido).

## Gate de allocations

En los runners compartidos de CI los tiempos son ruido, pero los bytes asignados por operación no, así que CI decide con ellos. `./gradlew :benchmark:allocationCheck` corre cada benchmark listado en `benchmark/allocation-budgets.properties` con ajustes cortos (`-f 1 -wi 3 -w 200ms -i 3 -r 200ms -t 1 -prof gc`, unos 4 minutos) y falla si alguno asigna su presupuesto + 1 B/op o más, o si falta en los resultados. Imprime una tabla y escribe `benchmark/build/jmh/allocation-report.md`; el workflow `alloc-gate` lo corre en cada PR. Agregar un benchmark es una línea: `<ClaseBenchmark>.<método> = <bytes por op>`.

Presupuestos: 0 B/op en todo camino sincrónico de éxito y de rechazo (retry, circuit breaker por cantidad/tiempo/abierto, rate limiter libre/agotado, bulkhead libre/lleno/con espera, timeout, classifiers, código generado y `Pipeline`, incluidos `run` y una lambda que captura, subclase y wrapper). Los caminos async asignan sus futures: 48 B por política, 24 B para un timeout sobre un stage ya completado, 200 B para el stack de cuatro políticas y 200 B para un timeout sobre un stage pendiente (medido 191–192 B; el trabajo compartido del thread del timer varía unos pocos bytes, y 8 B de margen siguen siendo menos que un objeto por llamada). Medido en local con JDK 21 (Apple M1), 2026-10-24. `CircuitBreakerBenchmark.*Open` (breaker forzado a abierto, todas las llamadas rechazadas) se agregó para el gate; ahí Resilience4j usa `writableStackTraceEnabled(false)`, para que la comparación sea justa.
