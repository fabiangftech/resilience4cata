# Hedging cuando los hedges compiten por el mismo servicio

Un hedge recorta la cola de latencia cuando la llamada lenta fue mala suerte, pero cuando todos los intentos esperan en la misma cola puede empeorarla mucho: una simulación de `@Hedge` contra un único servicio compartido, con cargas del 30 % al 95 %.

*5 de noviembre de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor de resilience4cata*

## La pregunta

Después del anuncio de la release candidate, un lector preguntó cómo se comporta el hedging con latencia de cola cuando la segunda llamada compite por el mismo servicio. Es la pregunta correcta. Un hedge manda una segunda petición cuando la primera tarda. Si la primera tarda por mala suerte (una pausa de GC, una caché fría, un disco lento), la segunda suele ganar y la cola se achica. Si la primera tarda porque el servicio está ocupado, la segunda petición lo ocupa más, para todos los que lo llaman.

La [página del hedge](../policies/hedge.md) ya dice que no conviene usarlo contra una dependencia saturada, y que el presupuesto de hedges "limita el daño, no lo deshace". Este artículo le pone números a esa frase.

## Cómo se simuló

La pregunta es sobre colas, no sobre lo que cuesta la política, así que esto no es un benchmark de JMH. JMH mide lo que cuesta el código en lazo cerrado, donde un servicio lento también frena a quienes llaman y esconde justo el colapso del que trata este artículo. En su lugar, una simulación corre el `HedgePolicy` y el `AdaptiveLimitPolicy` de la propia librería, a través de `Pipeline`, sobre el `ManualClock` del testkit, contra un servicio simulado:

- **El servicio:** 16 workers y una sola cola FIFO compartida por todos los intentos de todas las llamadas, así que un hedge espera detrás de los primeros intentos de otros.
- **Tiempo de servicio:** log-normal con mediana de 10 ms; el 2 % de las peticiones tarda entre 100 y 300 ms. Cada petición sortea el suyo, así que un hedge casi nunca cae en el mismo tropiezo.
- **Llegadas:** en lazo abierto (Poisson), a un ritmo que sin hedges tendría a los workers ocupados del 30 % al 95 % del tiempo. Un servicio lento no frena las llegadas, como pasa con usuarios reales.
- **El hedge:** un segundo intento a los 20 ms, un poco por encima del p95 del servicio.
- **Cada punto:** 200.000 llamadas, sin medir el primer 10 %. Dos semillas dieron el mismo panorama; las tablas muestran la semilla 1.

Estas son las configuraciones comparadas:

<!-- snippet: hedge-setups -->
```java
NO_HEDGE("no hedge", null),
HEDGE_NO_BUDGET("hedge, no budget", b -> b.hedge(h -> h.delay(DELAY).budgetPercent(0))),
HEDGE_DEFAULT_BUDGET("hedge, default budget", b -> b.hedge(h -> h.delay(DELAY))),
HEDGE_BUDGET_5("hedge, budget 5 %", b -> b.hedge(h -> h.delay(DELAY).budgetPercent(5))),
HEDGE_BUDGET_ADAPTIVE("hedge, default budget, adaptive limit", b -> b.hedge(h -> h.delay(DELAY)).adaptiveLimit()),
THREE_ATTEMPTS_NO_BUDGET("3 attempts, no budget", b -> b.hedge(h -> h.delay(DELAY).maxAttempts(3).budgetPercent(0)));
```

El presupuesto por defecto permite hedges para el 10 % de las llamadas más 1 por segundo, en 10 segundos. El límite adaptativo usa sus valores por defecto (Gradient2). Lo único que cambia la simulación es si un intento cancelado detiene su trabajo en el servidor.

> [!NOTE]
> Son resultados de un modelo, deterministas para una semilla, no medidas en reloj real. Muestran cómo reaccionan las políticas a la carga; una red, temporizadores y un servidor reales agregan ruido y costos que el modelo deja fuera. El código está en `examples/src/test/java/dev/resilience4cata/examples/hedgesim`, y `./gradlew :examples:hedgeSimulation` escribe el informe completo.

## Cuando el servidor ignora la cancelación

Es el caso habitual de una llamada HTTP: cuando un intento gana, el hedge cancela el otro, pero cancelar solo hace que el cliente deje de esperar. El servidor termina el trabajo igual.

Latencia p99 / p99.9 en ms, según la carga sin hedges:

| Configuración | 50 % | 70 % | 85 % | 95 % |
|---|---:|---:|---:|---:|
| Sin hedge | 200 / 290 | 200 / 290 | 204 / 295 | 237 / 347 |
| Hedge, sin presupuesto | **30 / 36** | colapso | colapso | colapso |
| Hedge, presupuesto por defecto (10 %) | **30 / 36** | 341 / 419 | 504 / 585 | 6.701 / 6.774 |
| Hedge, presupuesto 5 % | **30 / 36** | 149 / 265 | 262 / 364 | 587 / 699 |
| Hedge, presupuesto por defecto, límite adaptativo | **30 / 36** | **56 / 249**, 1,3 % rechazadas | **123 / 294**, 3,4 % rechazadas | **164 / 305**, 7,1 % rechazadas |
| 3 intentos, sin presupuesto | **30 / 36** | colapso | colapso | colapso |

"Colapso" significa que la cola nunca se vacía: cada llamada espera lo suficiente para mandar sus hedges, así que la carga se duplica (se triplica con tres intentos) y se queda ahí. La latencia crece mientras dure la corrida, aquí hasta minutos.

Lo que dicen los números:

- **Con margen de sobra, el hedging cumple.** Hasta el 50 % de carga, el p99.9 baja de 290 ms a 36 ms con un 2,2 % más de peticiones, con o sin presupuesto.
- **Sin presupuesto, colapsa.** Cuando la espera en cola empuja llamadas normales más allá de los 20 ms, también lanzan su hedge, y la carga extra mantiene la cola larga. No se recupera solo: es un estado estable. Con la segunda semilla, tres intentos colapsaron ya al 50 %, después de una ráfaga de llegadas.
- **El presupuesto evita el colapso, pero no el daño.** Las peticiones nunca pasan de 1,10 por llamada. Pero los hedges que se disparan por la cola, no por un tropiezo, gastan el presupuesto y agregan carga donde hay poco margen. Desde el 70 % de carga, la cola con el presupuesto por defecto es peor que sin hedge. Al 95 %, un 10 % más de carga no cabe en un 5 % de margen.
- **Un presupuesto más chico ayuda.** Aquí el 2,2 % de las llamadas necesitaba un hedge, y el 5 % alcanzó. Le va mejor que al 10 % en todas las cargas y supera a no usar hedge al 70 %, pero al 85 % y al 95 % sigue siendo peor que no usar hedge.
- **El límite adaptativo mantiene la cola corta.** Cuando sube la latencia, baja el número de intentos permitidos en vuelo y rechaza los demás, primeros intentos y hedges por igual; un hedge rechazado además frena los siguientes. Al 95 % de carga el p99 es 164 ms en vez de 237 ms sin hedges, pero el 7,1 % de las llamadas se rechaza con `AdaptiveLimitFullException`, que quien llama debe manejar (con un fallback, por ejemplo).

## Cuando la cancelación llega al servidor

Si la cancelación llega al servidor (gRPC la propaga, por ejemplo), un perdedor en cola sale de ella y uno en servicio libera su worker. El panorama cambia por completo:

| Configuración | 50 % | 70 % | 85 % | 95 % |
|---|---:|---:|---:|---:|
| Sin hedge | 200 / 290 | 200 / 290 | 204 / 295 | 237 / 347 |
| Cualquier configuración con hedge de arriba | 30 / 36 | 30 / 36 | 30 / 36 | 31 / 38 |

Todas las configuraciones con hedge dan el mismo resultado, con cualquier carga. Cancelar el intento lento ahorra el resto de su trabajo: al 95 % de carga, los workers están ocupados el 74 % del tiempo en vez del 94 %. Aquí el hedging le quita carga al servicio, porque la mayoría de los hedges corta una petición que habría ocupado su worker entre 100 y 300 ms.

## Qué sacar de esto

- **Averigua si tu cancelación llega al servidor.** Si llega, el hedging es seguro en un rango amplio de cargas. Si no llega, el hedging necesita margen.
- **Deja el presupuesto activado.** Sin él, una ráfaga puede llevar al servicio a un estado del que no sale. Por eso resilience4cata lo activa por defecto.
- **Dimensiona el presupuesto según los hedges que esperas.** Si el 2 % de las llamadas tarda por mala suerte, un presupuesto del 10 % deja un 8 % para hedges causados por la cola, que solo agregan carga. `budgetPercent` es la perilla.
- **Pon un límite adaptativo dentro del hedge** cuando el servicio pueda calentarse. Convierte la sobrecarga en rechazos rápidos en vez de una cola larga.
- **Pon el delay por encima de la latencia que causa la cola por sí sola.** Un delay cercano a la mediana lanza hedges de llamadas normales en cuanto el servicio se ocupa.

## Advertencias

- Un servicio con una cola. Los servicios reales tienen varias réplicas; un hedge enviado a una réplica menos ocupada puede ayudar más que aquí, y un balanceador que lo manda a la misma ocupada, menos.
- Los tropiezos son independientes. Cuando todas las réplicas comparten el mismo camino lento (una base de datos lenta detrás de todas), un hedge espera dos veces y nunca ayuda; la simulación no cubre ese caso.
- Sin red, sin temporizadores reales, sin planificación de hilos. Una confirmación en reloj real, con un generador de carga y un servidor en máquinas separadas, queda como trabajo futuro posible.
- Dos semillas, un modelo de tiempo de servicio, un delay. La forma se mantuvo en las dos semillas, pero los números al 85 % y al 95 % cambiaron mucho entre ellas (hasta tres veces, para el presupuesto del 5 % al 95 %).

## Pruébalo

La simulación corre en alrededor de un minuto:

```bash
./gradlew :examples:hedgeSimulation -Phedge.calls=200000 -Phedge.seed=1
```

Para probar el hedging en tu propio servicio, empieza por la [página del hedge](../policies/hedge.md) y el [límite adaptativo](../policies/adaptive-limit.md). Las preguntas y los resultados de tus propios servicios son bienvenidos en las [discusiones](https://github.com/fabiangftech/resilience4cata/discussions).
