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
