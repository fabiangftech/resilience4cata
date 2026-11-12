# Probar código lock-free también en ARM, no solo en x86

Las pruebas de concurrencia de resilience4cata ahora corren en ARM además de en x86 después de cada merge: las 65 pasan en ambas, y la única carrera que siguen encontrando resultó ser igual de frecuente en x86 que en ARM.

*10 de noviembre de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor de resilience4cata*

## Por qué x86 solo no bastaba

Las políticas guardan su estado en estructuras lock-free: un circuit breaker cambia de fase con un compare-and-set, un bulkhead cuenta slots en un contador atómico, el presupuesto de reintentos cuenta llamadas en contadores repartidos en franjas y con pérdida a propósito. Cada una usa el orden de memoria más débil que sigue siendo correcto, porque en ARM una barrera completa cuesta dos o tres veces más que un acceso acquire o uno plano ([benchmarks](../benchmarks.md)).

[jcstress](https://github.com/openjdk/jcstress) es cómo comprobamos que esos órdenes son correctos: cada test ejecuta unas pocas acciones en varios hilos millones de veces, en muchas ubicaciones de hilos, y falla ante cualquier resultado marcado como prohibido. La biblioteca tiene 65.

Hasta ahora la CI solo los corría en x86. x86 es TSO (total store order): mantiene casi todos los pares de accesos a memoria en el orden del programa, y el único reordenamiento que permite es que una lectura posterior se adelante a una escritura anterior. Así que si alguien debilita por error una lectura volatile a una plana, x86 se sigue comportando como si estuviera ordenada, y el test no puede fallar. ARM reordena con mucha más libertad, y además es la arquitectura sobre la que se afinó la biblioteca. La única corrida en ARM hasta ahora fue a mano, en Graviton3, para la release candidate.

## Qué corre ahora la CI

| Workflow | Cuándo | Modo | Máquinas |
|---|---|---|---|
| `stress` | cada push a `main`, y cada noche | quick | x86 y ARM en paralelo |
| `stress-arm` | cada semana, y a mano | por defecto (más forks, corridas más largas) | ARM |

Los dos corren en los runners de GitHub, gratis para repositorios públicos. La corrida semanal tiene su propio workflow para que un push a `main`, que reinicia `stress`, no pueda cancelar una corrida de varias horas.

## Los resultados

Tres corridas de `stress` desde el cambio: el pull request, el merge y la noche siguiente. Cada corrida en ambas máquinas:

| | x86 (`ubuntu-24.04`) | ARM (`ubuntu-24.04-arm`) |
|---|---|---|
| Topología de CPU que ve jcstress | 2 núcleos × 2 hilos de hardware | 4 núcleos × 1 hilo |
| Configuraciones por corrida | 2322 | 1050 |
| Fallidos, errores leves, errores graves | 0, 0, 0 | 0, 0, 0 |
| Duración del job | 59–66 min | 22–24 min |
| Ejecuciones del test "interesting" | ~206 millones por corrida | ~73 millones por corrida |

El número de configuraciones difiere porque jcstress prueba los actores en distintas ubicaciones sobre la topología de CPU, y con dos hilos de hardware por núcleo hay más ubicaciones que probar (deducido de las líneas de topología de los logs, no verificado en el código de jcstress). Eso explica también la mayor parte de la diferencia de tiempo: x86 corre más del doble de configuraciones.

## El único resultado interesante

Un test sale como "interesting" en todas las corridas, en ambas máquinas: `RetryBudgetStress.CallsAreNeverOvercounted`. Es a propósito. El [presupuesto de reintentos](../policies/retry.md#presupuesto-de-reintentos) cuenta cada llamada para permitir reintentos en proporción al tráfico, y cuenta con una lectura y una escritura planas sobre un contador repartido en franjas en lugar de una suma atómica: 3,5 ns por llamada frente a 9,7 ns de un compare-and-set en Graviton3 ([Presupuesto de reintentos, 0.3.0](../benchmarks.md#presupuesto-de-reintentos-030-2026-10-25-aws-graviton3)). El precio es que dos hilos que compiten en una franja pueden perder una llamada. Una llamada perdida hace el presupuesto un poco más estricto, nunca más permisivo.

El test pone dos hilos en una franja, y cada uno cuenta dos llamadas. Cuatro llamadas es el resultado normal; dos o tres es la llamada perdida, permitida y reportada como interesante; cualquier cosa por encima de cuatro, o un reintento contado, estaría prohibida y haría fallar el build. Nunca se vio.

| Corrida | x86: ejecuciones que perdieron una llamada | ARM: ejecuciones que perdieron una llamada |
|---|---:|---:|
| Pull request | 6,7 % | 5,5 % |
| Merge | 6,3 % | 7,6 % |
| Noche siguiente | 18,6 % | 8,6 % |

Esto corrige algo que escribimos antes. Después de la corrida en Graviton3 (el 10,9 % de las ejecuciones perdió una llamada), el [plan hacia 1.0](../plan-1.0.md) decía que la pérdida se veía mucho más en ARM que en x86. Estas corridas no lo muestran: las tasas se solapan, y la más alta es de x86. Tiene sentido si se mira qué necesita la carrera. Perder una llamada no requiere ningún reordenamiento, solo que los dos hilos lean el contador antes de que alguno lo escriba, y TSO no lo impide. La tasa depende de cuánto se solapan los dos hilos, y eso cambia con la máquina, la ubicación y el ruido de un runner compartido.

## Lo que esto todavía no muestra

- **Que el job de ARM detectaría un orden roto.** Una corrida en verde no es una prueba. Cada test de carreras de la biblioteca se comprobó por mutación (debilitar la protección y confirmar que el test falla), pero esos mutantes corrieron en x86 y en Apple M1, no en estos runners. Repetirlos en ARM es el siguiente paso, y sigue abierto en el [plan](../plan-1.0.md#después-de-10). *Actualización, 12 de noviembre de 2026: hecho. ARM no detectó nada que x86 no detectara: los mutantes que se detectan en una máquina se detectan en ambas, y los que sobreviven sobreviven en ambas (detalles en el [plan](../plan-1.0.md#después-de-10)).*
- **Cuánto tarda el modo por defecto semanal.** Por las corridas quick, probablemente una o dos horas en cuatro núcleos ARM; el límite es de casi seis. Lo dirá la primera corrida programada.
- **Nada sobre velocidad.** Son pruebas de corrección. Las cifras de rendimiento siguen siendo solo de ARM (Graviton3 y Apple M1); x86 [todavía no se ha medido](../performance.md).

## Córrelas tú

```bash
./gradlew :stress:jcstress -Pjcstress="-m quick"                     # lo que corre la CI en cada push
./gradlew :stress:jcstress                                           # modo por defecto, como la corrida semanal en ARM
./gradlew :stress:jcstress -Pjcstress="-m quick -t RetryBudget"      # un grupo
```

El reporte queda en `stress/build/jcstress/results/index.html`. En una máquina ARM (Apple Silicon, Graviton, Ampere) el mismo comando prueba los órdenes que x86 oculta.
