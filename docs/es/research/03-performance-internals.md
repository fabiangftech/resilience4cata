# 03 — Internos de rendimiento: algoritmos y concurrencia por política

> **Histórico.** Esta investigación es anterior a la implementación y se conserva tal como se escribió; para lo que hace hoy la librería, ver [features](../policies/README.md), [arquitectura](../architecture.md) y [benchmarks](../benchmarks.md).

> Investigación de la fase 1. Sin código de producción. Los fragmentos cortos son solo ilustrativos. Última actualización: 2026-10-23.
> Alcance: qué algoritmo usar para cada política de resiliencia y cómo construirlo en Java moderno (21+) para que la ruta caliente (*hot path*) sea barata, no asigne memoria y escale bajo contención. Incluye cómo lo hace hoy Resilience4j y qué cuesta, además de una metodología de benchmarking.
> Fuera de alcance (cubierto por documentos hermanos): panorama de competidores (01), mecanismo de procesamiento de anotaciones, superficie de API/configuración (04), estructura de módulos (05).

## Leyenda de evidencia

| Etiqueta | Significado |
|---|---|
| **[SRC]** | Verificado leyendo código fuente. Resilience4j en `master`, commit [`b7c0069`](https://github.com/resilience4j/resilience4j/tree/b7c0069802e373ed72dff0b817c270fbb6e966ba) (2026-10-07, posterior a 2.4.0). OpenJDK en los tags `jdk-21+35` y `jdk-25+36`. Netty `4.1`. |
| **[DOC]** | Verificado en documentación oficial, una página de JEP, una especificación o un artículo. |
| **[INF]** | Inferido por nosotros a partir del código, del comportamiento de la JVM o de conocimiento general, **no medido**. Debe validarse con JMH y/o jcstress en una fase posterior. |
| **[PROP]** | Propuesta de diseño nuestra. Es una hipótesis a medir, no un hecho. |

Las cifras sin **[DOC]** ni **[SRC]** son órdenes de magnitud, solo para razonar.

---

## 0. Resumen

| Política | Resilience4j hoy (costes principales) | Recomendación para resilience4cata |
|---|---|---|
| Circuit breaker | `ReentrantLock` alrededor de cada resultado registrado (estrategia por defecto `SYNCHRONIZED`); un `Snapshot` nuevo por llamada; asignaciones de `Instant` en ventanas por tiempo y en el estado OPEN; una opción `LOCK_FREE` que asigna un nodo y una copia de estadísticas por llamada. [SRC] | Una palabra de estado en un `VarHandle` int/long. Ventana por conteo como array de *slots* actualizado con `getAndSet`, más contadores por resultado. Ventana por tiempo como anillo de *buckets* etiquetados por época. Evaluar umbrales solo cuando una tasa puede subir. Permisos etiquetados con generación para half-open. Cero asignaciones en todas las rutas. [PROP] |
| Rate limiter | `AtomicRateLimiter`: un `State` inmutable nuevo por intento de CAS, reloj `Supplier<Long>` con *boxing*, *back-off* con `parkNanos(1)`, ráfagas de ventana fija en los bordes de ciclo. `SemaphoreBasedRateLimiter`: un semáforo **justo** (*fair*) y un hilo planificador por limitador. [SRC] | **GCRA** sobre un único `long` (tiempo teórico de llegada) con un bucle CAS. La configuración de token bucket se traduce a él. Sin asignaciones, sin hilo de fondo. [PROP] |
| Bulkhead | `Semaphore.tryAcquire(timeout)`, una lambda capturadora por llamada para eventos y un segundo atómico disputado (`grantsInProgress`) en cada liberación. [SRC] | Un contador *fail-fast* (`getAndAdd`) con una ruta lenta que espera en un semáforo solo cuando `maxWait > 0`. Con Loom, el bulkhead de pool de hilos es un caso de nicho. Los límites adaptativos son una política aparte. [PROP] |
| Retry | Cuatro asignaciones por llamada (`ContextImpl` + 3 atómicos) incluso cuando el primer intento tiene éxito; *boxing* en la función de intervalo. [SRC] | Estado de intentos en variables locales (código generado o un bucle). Asignar solo ante fallo. *Decorrelated jitter* con `ThreadLocalRandom`. Un **presupuesto** de reintentos como token bucket sobre un `long`. [PROP] |
| Timeout | La ruta síncrona necesita un `Future` (un salto de hilo). La ruta asíncrona usa `ScheduledExecutorService` por llamada. [SRC] | Un temporizador *watchdog* (rueda de tiempo) que interrumpe al hilo llamante (los hilos virtuales hacen la E/S interrumpible). Rueda hash o jerárquica con cancelación O(1). Sin salto de hilo. [PROP] |
| Transversal | Algunos eventos se asignan antes de comprobar si hay consumidores; `String.format` por cada excepción de rechazo; el anidamiento de lambdas crea sitios de llamada megamórficos. [SRC][INF] | Callbacks de *listener* con argumentos primitivos, protegidos por una comprobación "sin listeners" que el JIT pueda plegar como constante. Excepciones de rechazo sin traza y preasignadas, o un resultado sin excepción. **Código acquire/release generado, lineal y por sitio de llamada, en lugar de lambdas anidadas.** Un único par de `nanoTime` por pipeline. [PROP] |

La mayor palanca individual probablemente no sea ningún algoritmo concreto sino la **composición** (§7.4). Las lambdas decoradoras de Resilience4j comparten un único sitio de llamada en bytecode por tipo de política en toda la aplicación, así que el JIT ve llamadas `supplier.get()` megamórficas y no puede hacer *inlining* ni análisis de escape entre capas. Una biblioteca guiada por anotaciones puede generar código monomórfico y lineal por cada método anotado. [INF]

---

## 1. Caja de herramientas de bajo nivel (Java moderno)

### 1.1 Primitivas atómicas

- **`VarHandle` vs `Atomic*`.** Ambos compilan a las mismas instrucciones de CPU (`LOCK CMPXCHG` / `LOCK XADD` en x86; LSE `CAS`/`LDADD` o LL/SC en AArch64). Un `VarHandle` sobre un campo del propio objeto de la política evita el objeto extra y el salto de puntero de un `AtomicLong` separado. También expone modos más débiles (`getOpaque`, `getAcquire`/`setRelease`, `weakCompareAndSetPlain`), más baratos en ARM. Usar campos `static final VarHandle` para que el JIT los trate como constantes. [DOC] ([javadoc de VarHandle](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/VarHandle.html))
- **`getAndAdd` vs bucles CAS.** `getAndAdd` (x86 `LOCK XADD`, ARM `LDADD`) siempre tiene éxito en una instrucción. Un bucle CAS puede reintentar muchas veces bajo contención. Preferir aritmética que encaje en `getAndAdd` y reservar CAS para transiciones que necesitan semántica de "comparar" (máquinas de estado, GCRA). [INF]
- **`LongAdder` / `Striped64`.** Las escrituras van a celdas indexadas por la *probe* del hilo, así que los incrementos disputados escalan. Las lecturas (`sum()`) son O(celdas) y no son instantáneas atómicas. Ideal para contadores de métricas, pero incorrecto para decisiones que necesitan un valor exacto y consistente (p. ej. "admitir si count < max"). [DOC] ([LongAdder](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/LongAdder.html))
- **Locks.** Un `ReentrantLock` sin contención cuesta más o menos un CAS para adquirir y un *store* de liberación para soltar. Con contención aparca hilos, y un poseedor del lock desalojado de la CPU detiene a todos (la propia Javadoc de configuración de Resilience4j lo dice, ver §2.1). `synchronized` fija (*pin*) los hilos virtuales en JDK 21–23 (corregido en JDK 24 por [JEP 491](https://openjdk.org/jeps/491)). La biblioteca nunca debe bloquear mientras tenga un monitor. [DOC]

### 1.2 *False sharing* y relleno (*padding*)

- Dos campos calientes, escritos de forma independiente, en la misma línea de caché de 64 bytes (128 bytes por el *prefetch* de línea adyacente en muchas CPU Intel, y el tamaño de línea en Apple M) hacen que los núcleos se disputen esa línea. [INF]
- `@jdk.internal.vm.annotation.Contended` (originalmente [JEP 142](https://openjdk.org/jeps/142)) solo tiene efecto en clases de usuario cuando la JVM se ejecuta con `-XX:-RestrictContended`, y vive en un paquete interno. Una biblioteca no puede depender de ello. [DOC]
- **El relleno manual por jerarquía de clases** (como hacen JCTools y Agrona: `class Pad0 { long p01..p16; } class Value extends Pad0 { volatile long value; } class Pad1 extends Value { long p17..p32; }`) funciona porque HotSpot coloca primero los campos de la superclase. Es la técnica portable. [INF]
- **Regla de disposición para cada objeto de política:** mantener en un grupo la configuración de lectura frecuente (umbrales, intervalos) y las referencias inmutables. Aislar cada palabra caliente **escrita de forma independiente** (estado, contadores, TAT) en su propia línea con relleno. No rellenar todo: el relleno cuesta memoria, y eso importa con 10k+ instancias con nombre (multi-tenant). Que el relleno sea una opción de construcción para instancias "calientes", o rellenar solo las palabras con escritura caliente. [PROP]

### 1.3 Tiempo

- Coste de `System.nanoTime()`: en Linux con *clocksource* TSC, unos **25 ns de latencia y ~26 ns de granularidad**, y escala bien entre hilos. En Windows (mediciones antiguas) eran ~14 ns de latencia pero **~370 ns de granularidad**, y **no** escalaba con 32 hilos (~15 µs). [DOC] ([Shipilëv, *Nanotrusting the Nanotime*](https://shipilev.net/blog/2014/nanotrusting-nanotime/), cifras de la época de JDK 7; volver a medir en JDK 21/25 y en macOS/AArch64.)
- `Clock.instant()` / `Instant.now()` asigna un `Instant` y lee el reloj de pared, que no es monótono. Es incorrecto para medir intervalos y un desperdicio en rutas calientes. [INF]
- **Reloj cacheado:** un hilo demonio "ticker" que escribe `nanoTime()` en un `volatile long` cada 1 ms. Los lectores pagan una carga simple. Costes: un hilo permanente, resolución de 1 ms y el riesgo de que el ticker se detenga si le falta CPU (el tiempo se congela y los timeouts nunca saltan). Bueno para **decisiones gruesas** (¿terminó la espera de OPEN? ¿qué bucket de tiempo?), pero malo para umbrales de llamada lenta por debajo de ~10 ms. [INF]
- **Propuesta:** una abstracción `Ticker` con tres implementaciones: `nano` (por defecto), `cached(1ms)` (opcional) y `manual` (tests). Un par de `nanoTime()` por **pipeline**, no por política (§7.5). Omitir la medición por completo cuando ninguna política del pipeline necesita duraciones (sin umbral de llamada lenta, sin métricas de latencia, sin límite adaptativo). [PROP]

### 1.4 Asignación de memoria y el JIT

- El análisis de escape de C2 puede eliminar asignaciones de vida corta, pero solo cuando todo está *inlined*. Los sitios de llamada megamórficos (más de 2 tipos receptores), el *inlining* profundo (el valor por defecto de `MaxInlineLevel` subió desde 9 en JDK 14 vía [JDK-8234863](https://bugs.openjdk.org/browse/JDK-8234863)) y los métodos grandes lo anulan. El código de la biblioteca debe estar **libre de asignaciones por construcción**, no "libre de asignaciones si el JIT es amable". [DOC][INF]
- Trampas de *boxing* vistas en Resilience4j: timestamp con `Function<Clock, Long>`, nano-time con `Supplier<Long>`, sleep con `CheckedConsumer<Long>`, backoff con `Function<Long, Long>`. Cada `Long` mayor que 127 es una asignación potencial. [SRC]
- Puerta de CI: JMH `-prof gc` debe reportar `gc.alloc.rate.norm ≈ 0 B/op` para toda ruta feliz y toda ruta de rechazo (§9). [PROP]

---

## 2. Circuit breaker

### 2.1 Cómo lo hace Resilience4j [SRC]

Archivos: [`CircuitBreakerStateMachine`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java), [`CircuitBreakerMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerMetrics.java), [`FixedSizeSlidingWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/FixedSizeSlidingWindowMetrics.java), [`SlidingTimeWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/SlidingTimeWindowMetrics.java), [`LockFreeFixedSizeSlidingWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/LockFreeFixedSizeSlidingWindowMetrics.java), [`CASBackoffUtil`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/CASBackoffUtil.java), [`CircuitBreakerConfig`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/CircuitBreakerConfig.java).

- **Estado:** `AtomicReference<CircuitBreakerState>`. Cada estado es un objeto (`ClosedState`, `OpenState`, `HalfOpenState`...) con su propio `CircuitBreakerMetrics` y `AtomicBoolean`. Las transiciones usan `getAndUpdate(lambda)`, que asigna nuevos objetos de estado (es raro, así que está bien).
- **Permiso en CLOSED:** `isClosed.get()`, así que la ruta rápida es barata.
- **Registro (estrategia por defecto `SYNCHRONIZED`):** cada `onSuccess`/`onError` toma un `ReentrantLock`, actualiza un `TotalAggregation` y un bucket del anillo, y **asigna un `SnapshotImpl`** que devuelve. Luego `CircuitBreakerMetrics.checkIfThresholdsExceeded` calcula las tasas de fallo y de llamadas lentas en **cada** llamada, incluidos los éxitos.
  - Por conteo: un array de objetos `Measurement`, con el índice de cabeza bajo el lock.
  - Por tiempo: un `PartialAggregation` por **segundo** (`timeWindowSizeInSeconds`), y `clock.instant().getEpochSecond()` en **cada registro** (una asignación de `Instant` salvo que el análisis de escape la elimine).
- **Estrategia `LOCK_FREE`** (añadida en el PR [#2241](https://github.com/resilience4j/resilience4j/pull/2241), dic. 2024, opcional; el valor por defecto sigue siendo `SYNCHRONIZED`): una lista enlazada de nodos inmutables. Cada `record` **copia** el `PackedAggregation` de la cola, **asigna un `Node` nuevo**, lo inserta con CAS y luego avanza cabeza y cola. *Back-off* ante contención: unas pocas vueltas de `Thread.onSpinWait()` y luego `LockSupport.parkNanos(...)` con *jitter* según el id del hilo. La Javadoc de la configuración expone ella misma el compromiso: lock-free "allocat[es] more objects", mientras que synchronized significa que "one thread that acquired the lock and is superseded from the CPU will block all other threads".
- **Medición de tiempo:** `getCurrentTimestamp()` llama a `Function<Clock, Long>` (por defecto `clock -> System.nanoTime()`), así que hay **boxing**, dos veces por llamada.
- **Estado OPEN:** `tryAcquirePermission` llama a `clock.instant().isAfter(retryAfterWaitDuration)` en **cada llamada rechazada** (otro `Instant`). Luego el rechazo construye `CallNotPermittedException` con **`String.format`** en cada llamada (la traza puede desactivarse vía `writableStackTraceEnabled`, pero el mensaje siempre se formatea).
- **Eventos:** los eventos de éxito y error se protegen con `eventProcessor.hasConsumers()` **antes** de asignar. Pero `publishEventIfHasConsumer(new CircuitBreakerOnCallNotPermittedEvent(name))`, `...OnIgnoredErrorEvent`, `...OnResetEvent` y los eventos de umbral **asignan primero el evento** y comprueban después. Así que cada llamada rechazada asigna un evento aunque no haya ningún listener (salvo que el análisis de escape lo elimine).
- **Manejo de resultados:** `onResult` con un resultado no nulo llama a `handlePossibleTransition(Either.left(result))`, que asigna un `Either` por cada llamada exitosa con resultado no nulo e invoca la función `transitionOnResult`.
- **Half-open:** `AtomicInteger.getAndUpdate(current -> current == 0 ? current : --current)`, un bucle CAS a través de una lambda (no capturadora, así que sin asignación).
- **Resultados obsoletos:** los resultados se aplican a `stateReference.get()`, el estado **actual**. Así que una llamada lenta admitida en CLOSED que termina durante HALF_OPEN cuenta como resultado de sondeo. [SRC: `onSuccess` → `stateReference.get().onSuccess(...)`] Si eso es un bug es discutible, pero hace ruidosas las decisiones de half-open. [INF]
- *Logging*: `LOG.debug(...)` en cada ruta de éxito y error (barato si está desactivado, pero sigue siendo una llamada y una comprobación de nivel). [SRC]

### 2.2 Algoritmos de ventana

| Ventana | Memoria | Coste de actualización | Coste de lectura | Notas |
|---|---|---|---|---|
| Anillo por conteo (últimas N llamadas) | O(N) slots | O(1) | O(1) con contadores acumulados | "Últimas N" exacto. El orden necesita un único contador de secuencia global, así que hay una línea caliente. |
| Por tiempo, con buckets (B buckets × resolución) | O(B) | O(1) amortizado | O(B) para sumar | Aproximado en los bordes de bucket. Se puede particionar (*striping*). |
| Por tiempo, decaimiento exponencial (EWMA de la tasa de fallo) | O(1) | O(1) con `exp` o una tabla | O(1) | Sin bordes de ventana. Difícil de explicar con "minimumNumberOfCalls". Candidato para un modo *adaptativo*, no por defecto. [INF] |

#### Por conteo, lock-free y sin asignaciones [PROP]

```java
// Illustrative only.
final class CountWindow {
  static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(int[].class);
  final int[] slots;          // outcome codes: 0 = EMPTY, 1 = OK, 2 = FAIL, 3 = SLOW_OK, 4 = SLOW_FAIL
  final int mask;             // N rounded up to a power of two; or use % N
  volatile long seq;          // padded; the only globally hot word
  // per-outcome counters, each on its own padded line (int fields + VarHandle getAndAdd)

  void record(int outcome) {
    long s = (long) SEQ.getAndAdd(this, 1L);
    int old = (int) SLOTS.getAndSet(slots, (int) (s & mask), outcome);
    if (old != outcome) { dec(old); inc(outcome); }   // the counters mirror the slot contents
  }
}
```

- Por llamada: un `getAndAdd` sobre `seq`, un `getAndSet` sobre un slot y 0–2 `getAndAdd` de contadores. Sin lock, sin asignación, sin bucle de reintento, así que es *wait-free*. [PROP]
- Los valores de los contadores acaban igualando el histograma del array de slots. Transitoriamente, un contador puede desviarse en el número de llamadas `record` en curso, e incluso llegar a −1 si un slot se sobrescribe antes de que su escritor haya incrementado. **Acotar a ≥ 0 al leer.** Validar los invariantes con [jcstress](https://github.com/openjdk/jcstress). [INF]
- Empaquetar todos los contadores en un solo `long` (un único `getAndAdd` de un delta con signo empaquetado) ahorra atómicos. Pero un campo transitoriamente negativo toma prestado de su vecino y podría provocar un OPEN espurio. Solo hacerlo con bits de guarda; medir antes. [INF]
- Un N que no sea potencia de dos puede usar `Math.floorMod(s, N)` (una división, unos pocos ns) o redondear la ventana hacia arriba. [INF]

#### Por tiempo, anillo de buckets [PROP]

- B buckets de resolución configurable (p. ej. 10 × 1 s, o 20 × 500 ms). Cada bucket tiene `long epoch` y contadores. Al registrar: `tick = now / resolution` (a partir de un reloj cacheado o del `nanoTime` compartido del pipeline). Si `bucket.epoch != tick`, hacer CAS de la época y reiniciar los contadores. Los incrementos que compiten en el borde pueden perderse o caer en el bucket reiniciado, un error acotado de aproximadamente las llamadas en curso de un bucket. Es aceptable para una política estadística. [INF]
- Sin `Instant` ni segundos de época: usar ticks monótonos. [PROP]
- ***Striping* para contención extrema:** S sub-anillos independientes elegidos por la *probe* del hilo (como `Striped64`), sumados solo al evaluar. Las ventanas por tiempo lo toleran porque el orden no importa. Las de conteo no (el orden de "últimas N" es global). [INF]

#### Evaluar umbrales solo cuando una tasa puede subir [PROP]

- Ante un **éxito rápido** en CLOSED, ni la tasa de fallos ni la de llamadas lentas pueden aumentar. Omitir la evaluación, lo que quita la división y las comparaciones del >99% de las llamadas en un sistema sano.
- Evaluar ante un fallo, ante un éxito lento y **mientras la ventana aún no esté "caliente"** (total < `minimumNumberOfCalls`). Si no, el éxito que completa el mínimo (p. ej. 6 fallos en las primeras 10 llamadas) no abriría el circuito hasta el siguiente fallo.

### 2.3 Máquina de estados [PROP]

- Un `volatile int state` (o un `long` que empaquete `state:2 | generation:30 | halfOpenPermits:16`) en un `VarHandle`, más `long openUntilNanos`.
- **Ruta rápida en CLOSED:** una carga *acquire*/simple de `state` y una bifurcación. Devolver un **token de permiso primitivo** (`long` = generación) en lugar de un objeto, para que el código generado pueda pasarlo a `onSuccess(token, nanos)`.
- **Ruta rápida en OPEN:** cargar `state`, luego cargar `openUntilNanos` y compararlo con `now`. Un ticker cacheado sirve aquí. Al expirar, el primer hilo que haga CAS OPEN→HALF_OPEN gana. Los demás vuelven a leer. Rechazar con una excepción **preasignada y sin traza** o con un código de resultado (§7.3). No se asigna nada.
- **HALF_OPEN:** permisos como un campo contador. `getAndAdd(-1)`, y deshacer si quedó por debajo de 0. **Etiquetado por generación:** los resultados cuya generación de token ≠ la generación actual se descartan (o solo cuentan en métricas), para que las llamadas obsoletas de la era CLOSED no decidan los sondeos. Opcionalmente, limitar los sondeos concurrentes por separado de los sondeos totales.
- **Transiciones:** CAS sobre la palabra de estado. El ganador reinicia la ventana (ruta rara, puede asignar o bloquear) y publica eventos.
- **Estados forzados** (DISABLED, FORCED_OPEN, METRICS_ONLY) son simplemente más códigos de estado.

### 2.4 Detección de llamadas lentas

- Esto necesita una duración inicio/fin, así que dos llamadas a `nanoTime` (~50 ns en total en Linux [DOC, ver §1.3]). Compartirlas con todas las demás políticas temporizadas del pipeline (§7.5). [PROP]
- Si el umbral de lentitud es ≥ 100 ms, un ticker cacheado de 1 ms es lo bastante preciso y convierte cada lectura en una carga simple. Que sea una elección por política, automática según el umbral. [PROP]
- Si no hay umbral de llamada lenta configurado ni métricas de duración activas, **no medir la llamada en absoluto**. Resilience4j siempre la mide. [SRC][PROP]

---

## 3. Rate limiter

### 3.1 Algoritmos

| Algoritmo | Estado | Comportamiento de ráfaga | ¿Lock-free en una palabra? | Notas |
|---|---|---|---|---|
| Contador de ventana fija | contador + id de ventana | Hasta **2× el límite** a través de un borde de ventana | Sí (empaquetar id + contador en un `long`) | El más simple; ráfagas en los bordes. |
| Log de ventana deslizante | timestamps de los últimos L permisos | Exacto | No (memoria O(L), anillo de timestamps) | Preciso, pero la memoria es ∝ al límite. |
| Contador de ventana deslizante | conteos de dos ventanas adyacentes, ponderados | Aproximado, suave | Casi (dos palabras) | Aproximación al estilo Cloudflare/nginx. |
| Token bucket | tokens + último instante de recarga | Hasta la capacidad | Sí, vía la equivalencia con GCRA | El clásico. |
| Leaky bucket (como medidor) | nivel + último instante | Hasta la capacidad | Sí, igual que GCRA | Matemáticamente equivalente a GCRA. |
| Leaky bucket (como cola) | una cola de esperas | Ninguna (suaviza la salida) | No | Es un planificador, no un limitador. |
| **GCRA** | un `long` TAT (tiempo teórico de llegada) | Hasta la ráfaga configurada | **Sí, un CAS** | El mejor encaje para un limitador en proceso. |

Referencias: [GCRA (Wikipedia)](https://en.wikipedia.org/wiki/Generic_cell_rate_algorithm), [Brandur — Rate limiting, cells, and GCRA](https://brandur.org/rate-limiting). [DOC]

### 3.2 GCRA en una palabra [PROP]

```java
// Illustrative only. T = emission interval (period / limit), tau = T * burst.
long tryAcquire(int permits, long nowNanos) {
  long inc = T * permits;
  for (;;) {
    long tat = (long) TAT.getVolatile(this);
    long newTat = Math.max(tat, nowNanos) + inc;
    long wait = newTat - nowNanos - tau;          // > 0 means "not yet"
    if (wait > 0) return wait;                    // the caller rejects, or waits ≤ timeout and then calls reserve()
    if (TAT.compareAndSet(this, tat, newTat)) return 0;
    Thread.onSpinWait();
  }
}
```

- Una lectura volátil, un CAS, **sin asignación, sin hilo de fondo**, y correcto para cualquier número de permisos. Una variante con "reserva" (el `reservePermission` de Resilience4j) inserta `newTat` con CAS aunque `wait > 0` (acotado por el timeout) y devuelve `wait`. [PROP]
- Un rechazo sin reserva es **de solo lectura**: sin CAS, así que no hace rebotar la línea de caché durante la sobrecarga. Esto importa, porque la sobrecarga es cuando el limitador está más caliente. [INF]
- Una configuración de token bucket (`capacity`, `refillRate`) se traduce a `T = 1/rate`, `tau = T × (capacity − 1)`. Una configuración de "límite por periodo" se traduce a `T = period/limit`, `burst = limit`. Redondeo: `period/limit` en nanos enteros deriva < 1 ns por permiso. Llevar el resto si la exactitud importa. [INF]
- **Contención:** cada llamada *admitida* hace CAS sobre una palabra. A tasas muy altas con muchos núcleos, particionar en K sub-limitadores de rate/K cada uno, elegidos por la *probe* del hilo, con préstamo opcional. Eso cambia exactitud por escalabilidad, así que debe ser opcional. [PROP]

### 3.3 Cómo lo hace Resilience4j [SRC]

[`AtomicRateLimiter`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/AtomicRateLimiter.java) (el predeterminado):

- Un algoritmo de **ciclo fijo**: `currentCycle = nanos / limitRefreshPeriod`. Los permisos se recargan hasta `limitForPeriod` (con tope) en cada borde de ciclo, así que un cliente puede hacer una ráfaga de ~2× el límite alrededor de un borde. [SRC + INF]
- El estado es un **objeto inmutable `State` (config, activeCycle, activePermissions, nanosToWait)** en un `AtomicReference`. **Cada intento de CAS asigna un `State` nuevo**, tenga éxito o falle, incluso para llamadas rechazadas.
- El reloj es `Supplier<Long> nanoTimeSupplier` (**con boxing**). `config.getTimeoutDuration().toNanos()` y `getLimitRefreshPeriod().toNanos()` se recalculan en cada llamada.
- El *back-off* ante fallo de CAS es `LockSupport.parkNanos(1)`. Para un hilo de plataforma en Linux, el sueño mínimo efectivo está acotado por el *timer slack* (~50 µs por defecto), mucho más de lo que costaría un reintento. [SRC + INF]
- `waitingThreads` es un `AtomicInteger` compartido, incrementado y decrementado alrededor de las esperas.

[`SemaphoreBasedRateLimiter`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/SemaphoreBasedRateLimiter.java):

- `new Semaphore(limit, /* fair */ true)`. Un semáforo AQS justo encola en FIFO a cada adquiriente disputado, lo que es mucho más lento que la versión no justa bajo contención. [SRC + INF]
- Una tarea `scheduleAtFixedRate` recarga los permisos, usando su **propio planificador de un hilo por limitador** salvo que se proporcione uno. Eso es un hilo por limitador con nombre. La recarga (`limit - availablePermits()` y luego `release`) no es atómica respecto a las adquisiciones concurrentes. [SRC + INF]
- `reservePermission` no está soportado. [SRC]

### 3.4 Esperar un permiso

- **Fallar rápido por defecto.** Si se configura una espera: en hilos virtuales, `LockSupport.parkNanos(wait)` desmonta el hilo y es barato. En hilos de plataforma, bloquea un hilo del SO, que es decisión del usuario. [INF]
- Con reserva (GCRA *reserve*), quien espera conoce su turno exacto, así que no hay estampidas al despertar ni nueva contención. [PROP]

---

## 4. Bulkhead

### 4.1 Bulkhead de semáforo

- ***Fail-fast* (maxWait = 0), el caso común** [PROP]:

  ```java
  if ((int) INFLIGHT.getAndAdd(this, 1) >= max) { INFLIGHT.getAndAdd(this, -1); return REJECTED; }
  // ... call ...
  INFLIGHT.getAndAdd(this, -1);   // + signal a waiter only if waiting is enabled and waiters > 0
  ```

  Un `LOCK XADD` para entrar y otro para salir, *wait-free*. La concurrencia nunca supera `max`. El exceso transitorio del contador puede causar un rechazo espurio justo en el límite, lo cual es aceptable para un bulkhead. [INF]
- **Con espera (`maxWait > 0`):** recurrir a `java.util.concurrent.Semaphore` (no justo por defecto; justo como opción), o reutilizar el contador más una cola de esperas. Solo esa ruta lenta paga los costes de AQS. [PROP]

**`SemaphoreBulkhead` de Resilience4j** ([código](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/internal/SemaphoreBulkhead.java)) [SRC]:
- `tryAcquirePermission()` llama a `semaphore.tryAcquire(maxWait.toMillis(), MILLISECONDS)` incluso con espera 0, y crea una **lambda capturadora** `() -> callPermitted ? new ...PermittedEvent : new ...RejectedEvent` por llamada (una asignación salvo que el análisis de escape la elimine).
- Cada `releasePermission()`/`onComplete()` llama a `grantPendingPermissions()`, que hace `grantsInProgress.getAndIncrement()` (y después `addAndGet`) sobre un **segundo atómico compartido**, aunque no haya esperas asíncronas. Eso son dos o tres atómicos disputados por llamada en lugar de dos.
- La ruta asíncrona usa una `ConcurrentLinkedQueue<PendingPermission>` y programa una tarea de timeout por cada adquisición en espera.
- Rechazo: `BulkheadFullException` con traza opcional (configurable). [SRC]

### 4.2 Bulkhead de pool de hilos vs hilos virtuales

- El [`FixedThreadPoolBulkhead`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/internal/FixedThreadPoolBulkhead.java) de Resilience4j aísla las llamadas en un `ThreadPoolExecutor` acotado y devuelve `CompletionStage`. Sus costes son un salto de hilo, una cola y asignaciones de futures por llamada. [SRC + INF]
- [JEP 444](https://openjdk.org/jeps/444) (hilos virtuales, final en JDK 21): "virtual threads are not expensive so there is never a need to pool them" y "do not be tempted to pool virtual threads in order to limit concurrency"; en su lugar, usar "constructs specifically designed for that purpose, such as semaphores." [DOC]
- Así que en un mundo con Loom el bulkhead de semáforo es **el** bulkhead. Al aislamiento por pool de hilos le quedan dos usos: (a) aplicaciones que aún ejecutan código bloqueante en hilos de plataforma y necesitan dejar libre el hilo llamante, y (b) semántica de "abandonar ante timeout", donde el llamante debe volver mientras el trabajo sigue en otro sitio. Ambos son nichos. Ofrecerlo más adelante, como implementación separada y opcional. [INF]

### 4.3 *Pinning*

- JDK 21–23: bloquear dentro de `synchronized` fija el hilo portador (*carrier*). **JDK 24+ ([JEP 491](https://openjdk.org/jeps/491))**: los monitores ya no fijan, y `Object.wait()` desmonta. El *pinning* persiste con *frames* nativos: código nativo que vuelve a llamar a Java y bloquea, bloqueo durante la carga de clases o en **inicializadores de clase**, y la espera a que otro hilo termine de inicializar una clase. [DOC]
- Reglas de la biblioteca: nada de `synchronized` alrededor de algo que pueda bloquear. Nada de bloqueos en inicializadores estáticos (p. ej. no arrancar un hilo ticker y esperarlo en `<clinit>`). Usar `j.u.c.locks` o código lock-free. Documentar que el propio código `synchronized` del usuario fija hilos en JDK 21–23. [PROP]
- Evitar cachés `ThreadLocal` por llamada: con hilos virtuales, cada llamada suele ejecutarse en un hilo nuevo, así que cachear se convierte en asignar. JEP 444 advierte explícitamente contra agrupar recursos costosos en *thread locals*. Preferir variables locales de pila, o `ScopedValue` para propagar contexto (final en JDK 25, [JEP 506](https://openjdk.org/jeps/506); no utilizable con una base Java 21 sin *preview*). [DOC][INF]

### 4.4 Concurrencia estructurada (estado revisado el 2026-10-23)

| JDK | JEP | Estado |
|---|---|---|
| 21 | [453](https://openjdk.org/jeps/453) | Preview |
| 22–24 | [462](https://openjdk.org/jeps/462), [480](https://openjdk.org/jeps/480), [499](https://openjdk.org/jeps/499) | 2.ª–4.ª preview |
| 25 | [505](https://openjdk.org/jeps/505) | 5.ª preview: `StructuredTaskScope.open(Joiner, cfg -> cfg.withTimeout(...))` |
| 26 | [525](https://openjdk.org/jeps/525) | 6.ª preview (callback de timeout en Joiner) |
| 27 | [533](https://openjdk.org/jeps/533) | 7.ª preview (los joiners lanzan `ExecutionException`) ([InfoQ](https://www.infoq.com/news/2026/05/jep-533-jdk-27/)) |
| 28 (GA prevista en marzo de 2027) | [543](https://openjdk.org/jeps/543) | **Final**, "without further change"; estado *Integrated* |

[DOC] Implicación: un jar con base Java 21 **no puede** usar `StructuredTaskScope` (los class files *preview* están atados a una versión exacta del JDK). Implementar timeouts y *hedging* sobre hilos virtuales simples ahora. Añadir más adelante un adaptador para JDK 28+ como multi-release jar o módulo separado. [INF]

---

## 5. Retry

### 5.1 Estrategias de backoff

| Estrategia | Fórmula | Comentario |
|---|---|---|
| Fija | `base` | Tormentas de reintentos sincronizadas. |
| Exponencial (con tope) | `min(cap, base · m^n)` | Sigue sincronizada. |
| *Full jitter* | `rand(0, min(cap, base · 2^n))` | El menor trabajo total en la simulación de AWS. |
| *Equal jitter* | `t/2 + rand(0, t/2)` | Mantiene una espera mínima. |
| ***Decorrelated jitter*** | `sleep = min(cap, rand(base, prev · 3))` | Buena dispersión, con estado (necesita `prev`). |

Fuente: [AWS Architecture Blog — Exponential Backoff And Jitter (Brooker)](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/). [DOC]

Implementación: `ThreadLocalRandom.current().nextLong(lo, hi)` (sin contención, sin asignación). Calcular exponenciales por multiplicación repetida o desplazamiento con saturación ante desbordamiento, no `Math.pow` por intento. Todo en nanos `long`, sin `Duration` en la ruta caliente. [PROP]

### 5.2 Cómo lo hace Resilience4j [SRC]

[`RetryImpl`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-retry/src/main/java/io/github/resilience4j/retry/internal/RetryImpl.java), [`Retry.decorateCheckedSupplier`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-retry/src/main/java/io/github/resilience4j/retry/Retry.java):
- `retry.context()` → `new ContextImpl()` con `new AtomicInteger` y dos `new AtomicReference`: **cuatro asignaciones por llamada, incluso cuando el primer intento tiene éxito**.
- `onComplete` incrementa `LongAdder`s (está bien) y envuelve valores en `Optional` en las rutas de reintento.
- `IntervalBiFunction`/`IntervalFunction` reciben un `Long` con boxing; `sleepFunction` es `CheckedConsumer<Long>` (`Thread::sleep`).
- `onResult` evalúa el predicado de resultado en cada llamada (es lo esperado), y `Either.right(result)` asigna en el reintento por resultado.

### 5.3 Propuesta [PROP]

- **La ruta sin fallos cuesta una comprobación de predicado**: el número de intentos, la última excepción y el estado de backoff viven en variables locales del método generado (o en un único bucle dentro de un helper estático). No se asigna nada hasta el primer fallo.
- **Dormir:** usar `Thread.sleep(Duration)` o `LockSupport.parkNanos` en el hilo llamante. En un hilo virtual esto lo desmonta. El JDK programa el despertar en un `ScheduledThreadPoolExecutor` interno. JDK 21 tiene un **único** `UNPARKER`; JDK 25 tiene un **array** de planificadores de tareas diferidas (`jdk.virtualThreadScheduler.timerQueues`) para reducir la contención. [SRC: `VirtualThread.java` en `jdk-21+35` / `jdk-25+36`] Para APIs asíncronas, usar `CompletableFuture.delayedExecutor` o nuestra propia rueda de tiempo (§6.3).
- **Presupuestos de reintento** (evitan tormentas de reintentos; un diferenciador, ya que Resilience4j no tiene ninguno [INF]):
  - Google SRE: un límite por petición (≤ 3 intentos) más una **proporción de reintentos por cliente < 10%**. [DOC] ([libro SRE — Handling Overload](https://sre.google/sre-book/handling-overload/))
  - *Retry throttling* de gRPC: un contador de tokens por destino que empieza en `maxTokens`. Cada fallo −1, cada éxito +`tokenRatio`. Los reintentos solo se permiten mientras tokens > `maxTokens/2`. [DOC] ([gRPC A6](https://github.com/grpc/proposal/blob/master/A6-client-retries.md))
  - Implementación: un `long` en mili-tokens de punto fijo, actualizado con `getAndAdd` (éxito/fallo) y una lectura (decisión de reintento). Sin asignaciones, un atómico por llamada. Compartir el presupuesto entre todas las políticas de retry que apuntan a la misma dependencia. [PROP]
- Los reintentos interactúan con circuit breakers y rate limiters. El orden y los presupuestos deben ser explícitos en el pipeline (ver el documento hermano 04). [INF]

---

## 6. Timeout / time limiter

### 6.1 El problema fundamental

Java no puede expropiar (*pre-empt*) un cálculo en ejecución. Un timeout solo puede (a) dejar de *esperar* un resultado que se ejecuta en otro sitio, o (b) *interrumpir* el hilo que lo ejecuta, lo que solo ayuda si el código llega a un punto interrumpible. [INF]

### 6.2 Opciones

| Opción | Salto de hilo | Coste por llamada | ¿Cancela el trabajo? |
|---|---|---|---|
| Ejecutar en un executor, `future.get(timeout)` (`TimeLimiter` síncrono de Resilience4j) | Sí | Tarea + future + cola; cambio de contexto | Solo vía `cancel(true)` → interrupción |
| Ejecutar en un **nuevo hilo virtual**, `join` con timeout | Sí (barato) | Creación + arranque + join del HV (~µs) [INF] | Interrupción |
| `StructuredTaskScope` + `withTimeout` | Sí | Scope + subtarea | Interrupción (cancela el scope); *preview* hasta JDK 28 |
| `CompletableFuture.orTimeout` (asíncrono) | No (para trabajo que ya es asíncrono) | `Timeout` + `Canceller` + tarea programada | No (completa con excepción) |
| ***Watchdog* en el hilo llamante** [PROP] | **No** | Una inserción + cancelación en el temporizador | Interrupción del llamante |

- **Resilience4j** [SRC] ([`TimeLimiterImpl`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-timelimiter/src/main/java/io/github/resilience4j/timelimiter/internal/TimeLimiterImpl.java)): la ruta síncrona es `decorateFutureSupplier(Supplier<Future>)`, así que **el usuario debe aportar un hilo** y la llamada hace `future.get(timeout.toMillis(), MILLISECONDS)`. La ruta asíncrona usa un timeout de `ScheduledExecutorService` por llamada y `whenComplete` (asignaciones: la tarea programada, la lambda de finalización y el future dependiente). Ante el timeout crea una `TimeoutException` con nombre.
- **`CompletableFuture.orTimeout`** [SRC]: JDK 21 programa en un único hilo demonio estático (`CompletableFutureDelayScheduler`, un `ScheduledThreadPoolExecutor`). En JDK 25 está reescrito sobre el nuevo `java.util.concurrent.DelayScheduler` que usa `ForkJoinPool`. Una inserción/cancelación en `ScheduledThreadPoolExecutor` es O(log n) en un *heap* protegido por lock, que se convierte en punto de contención con tasas de llamada altas. [INF]
- **Interrumpibilidad con hilos virtuales:** en JDK 21, interrumpir un hilo virtual bloqueado en connect/read/write de `Socket` "will cause it to wakeup and close the socket", y el método lanza `SocketException`. [SRC: Javadoc de `java/net/Socket.java`, `jdk-21+35`] Así que los timeouts basados en interrupción son efectivos para E/S bloqueante en hilos virtuales, de lo que se benefician los objetivos de timeout más comunes (HTTP/JDBC sobre sockets). En hilos de plataforma, la E/S clásica de `Socket` no es interrumpible. [INF]

### 6.3 Diseño del *watchdog* [PROP]

- La llamada se queda en el hilo del llamante (sin salto, preservando *thread-locals*, MDC y transacciones). Antes de la llamada, registrar `(thread, deadline)` en un temporizador. Después de la llamada, cancelar.
- **Protocolo de carrera:** un estado por llamada `RUNNING → DONE` (CAS del llamante) o `RUNNING → INTERRUPTING → INTERRUPTED` (CAS del temporizador, luego `thread.interrupt()`, luego un *store* de liberación). Si el llamante pierde el CAS, espera (girando brevemente) hasta `INTERRUPTED` y entonces limpia el flag de interrupción con `Thread.interrupted()` antes de lanzar `TimeoutException`. Esto garantiza que ninguna interrupción perdida se filtre a trabajo posterior no relacionado. Verificar el modelo con jcstress.
- **Estructura del temporizador:** la mayoría de los timeouts se **cancelan** (la llamada termina antes), así que cancelar debe ser O(1) y barato.
  - Una rueda de tiempo hash (`HashedWheelTimer` de Netty: tick por defecto de 100 ms, 512 buckets, colas MPSC para timeouts nuevos y cancelados, un hilo trabajador). [SRC] ([código](https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/HashedWheelTimer.java))
  - Una rueda de tiempo jerárquica (Varghese y Lauck 1987; también usada en Kafka y en el `TimerWheel` de Caffeine) cubre un amplio rango de timeouts con buckets acotados. [DOC] ([Varghese y Lauck, *Hashed and Hierarchical Timing Wheels*](https://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf))
  - **Variante aún más barata de "barrido de slots":** cada llamada temporizada en curso reclama un slot en un array preasignado (índice de una lista libre), escribe `deadline` y `thread`, y lo limpia tras la llamada. Un único hilo *watchdog* recorre los slots ocupados en cada tick (p. ej. 1 ms). Insertar y cancelar son *stores* simples más un pop/push en la lista libre; el barrido es O(en curso). Es lo mejor cuando la concurrencia está acotada (y un bulkhead suele acotarla). Medir contra la rueda.
- El hilo *watchdog* debe ser un hilo demonio de plataforma (no debe depender de que el planificador de hilos virtuales esté libre), arrancado de forma perezosa pero **no** dentro de `<clinit>` (*pinning*, §4.3).
- **APIs asíncronas:** para métodos con `CompletionStage`, usar semántica tipo `orTimeout` sobre nuestra propia rueda en lugar del *delayer* del JDK, para compartir un único temporizador en toda la biblioteca.

---

## 7. Aspectos transversales

### 7.1 Eventos y listeners

- **Regla:** cero asignaciones y ningún despacho virtual cuando nadie escucha. Un centinela `static final Listener[] NONE`, más `if (listeners != NONE)`, donde `listeners` es un campo `final` fijado en la construcción. Si los listeners se conocen al construir, el código generado puede omitir la llamada por completo. [PROP]
- **Callbacks primitivos en lugar de objetos evento:** `interface CircuitBreakerListener { default void onSuccess(long nanos) {} default void onRejected() {} default void onStateChange(int from, int to) {} }`. Sin asignaciones incluso *con* listeners. Los adaptadores a objetos evento (para logging o Micrometer) asignan solo dentro del adaptador. [PROP]
- **Orden de eventos y excepciones:** un listener que lanza no debe romper la llamada. Capturar y contar en un `LongAdder` de "fallos de listener". [PROP]
- **Resilience4j** [SRC]: el `EventProcessor` usa un `CopyOnWriteArraySet` por nombre de clase de evento, buscado con `event.getClass().getName()` (una búsqueda en `ConcurrentHashMap` por evento publicado). La protección existe, pero varios sitios asignan antes de comprobar (§2.1). Bulkhead y retry pasan lambdas `Supplier` capturadoras.

### 7.2 Métricas

- Contadores: `LongAdder` (o *longs* particionados con relleno) incrementados en la ruta caliente; exposición **pull** (un *gauge* que lee `sum()` en el momento del *scrape*). Nada de llamadas a Micrometer ni a un registro en la ruta caliente. [PROP]
- Histogramas de latencia (solo si están activados): buckets log-lineales fijos (estilo HdrHistogram) en un `AtomicLongArray` o arrays particionados. Registrar = `numberOfLeadingZeros` + índice del array + `getAndAdd`. Necesario para los retardos de *hedging* (p95) y los límites adaptativos. [PROP] ([HdrHistogram](https://github.com/HdrHistogram/HdrHistogram))

### 7.3 Excepciones

- El coste de construir un `Throwable` lo domina `fillInStackTrace`, que es proporcional a la profundidad de pila y suele ser de microsegundos. El constructor `(message, cause, enableSuppression=false, writableStackTrace=false)` lo evita. [DOC][INF] ([constructor de Throwable](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html#%3Cinit%3E(java.lang.String,java.lang.Throwable,boolean,boolean)))
- Resilience4j sí usa ese constructor para `CallNotPermittedException`, `BulkheadFullException` y `RequestNotPermitted` (traza configurable, escribible por defecto). Pero construye el mensaje con `String.format` (circuit breaker) o concatenación **en cada rechazo**. [SRC]
- **Propuesta:** las excepciones de rechazo se **preasignan por instancia de política**, sin traza y con la supresión desactivada. El mensaje se calcula una vez ("CircuitBreaker 'x' is OPEN"). El estado se lee de un campo al llamar a `getMessage()` si hace falta. Un modo de depuración (flag de configuración) crea excepciones nuevas con traza. Además, ofrecer una API sin excepciones (`Result`/resultado *sealed* o un `tryCall` que devuelve un estado) para los llamantes que tratan el rechazo como flujo normal. [PROP]
- **Nunca** envolver excepciones del usuario en la ruta caliente (nada de envolver en `CompletionException`/`ExecutionException` en la ruta síncrona). Relanzar las excepciones comprobadas con un *sneaky throw* en el código generado, preservando la firma declarada. [PROP]

### 7.4 Composición sin lambdas anidadas ni sitios megamórficos

**Problema.** `Decorators.ofSupplier(s).withRetry(r).withCircuitBreaker(cb).withBulkhead(b).decorate()` construye una cadena de lambdas. El cuerpo de cada lambda de `CircuitBreaker.decorateCheckedSupplier` es **una única ubicación de bytecode para toda la aplicación** [SRC: `CircuitBreaker.java`]. Su sitio de llamada `supplier.get()` ve todas las clases *supplier* decoradas de la aplicación, así que se vuelve megamórfico a partir del tercer tipo distinto. Los sitios megamórficos se compilan como llamadas itable/vtable, lo que bloquea el *inlining*, así que el análisis de escape no puede eliminar las asignaciones de `Long`, `Either`, `Snapshot` y eventos. Las aplicaciones reales tienen muchos métodos decorados, así que los microbenchmarks con un solo *supplier* decorado son *irrealmente* rápidos para Resilience4j. [INF; validar con `-XX:+PrintInlining` / JITWatch]

**Propuesta** [PROP]:
1. **Protocolo acquire/release, no envoltura.** Cada política expone operaciones primitivas, sin lambdas: `long cb.acquire(now)`, `cb.onSuccess(token, nanos)`, `cb.onFailure(token, nanos, Throwable)`, `boolean bh.tryEnter()` y `bh.exit()`, `long rl.tryAcquire(now)`, etc.
2. **Código generado y lineal por cada método anotado** (con el mecanismo que elija la investigación hermana: un procesador de anotaciones que genere subclases o envoltorios). Cada método tiene su propio bytecode, así que cada sitio de llamada es **monomórfico** (clases de política `final`, invocadas a través de campos constantes `static final` o campos de instancia finales). Forma:

   ```java
   // Generated, illustrative.
   long t0 = TICKER.read();
   if (!BH.tryEnter()) throw BH.rejected();
   try {
     long tok = CB.acquire(t0);          // throws a preallocated rejection if OPEN
     try {
       R r = target.call(args);          // direct invokevirtual on the real class
       long t1 = TICKER.read();
       CB.onSuccess(tok, t1 - t0);
       return r;
     } catch (Throwable e) { CB.onFailure(tok, TICKER.read() - t0, e); throw e; }
   } finally { BH.exit(); }
   ```

3. **Retry como bucle** alrededor del bloque interior, en el mismo método generado. Sin lambda, sin objeto de contexto.
4. **Alternativa para uso programático (sin anotaciones):** una única clase `Pipeline` con orden fijo y campos `final` por tipo de política (null = ausente), que ejecuta la misma secuencia acquire/release mediante ramas `if (cb != null)`. Es una sola clase cuyos sitios de llamada ven cada uno una única clase concreta de política (las políticas son clases `final`), así que siguen siendo monomórficos. El `Callable.call()` del usuario sigue siendo un sitio megamórfico, algo inevitable sin generación. [INF]

### 7.5 Lecturas de reloj compartidas

En una pila de N políticas temporizadas, Resilience4j lee el reloj por política (el circuit breaker lo lee dos veces, más el rate limiter, el time limiter, etc.). Un pipeline generado lee `t0` una vez antes y `t1` una vez después, y pasa las duraciones a todos. [PROP]

---

## 8. Funcionalidades diferenciadoras

### 8.1 *Hedging*

- Enviar una segunda petición si la primera no ha terminado tras un retardo (típicamente ~la latencia p95), quedarse con el primer éxito y cancelar el resto. En *The Tail at Scale*, aplazar la petición de cobertura hasta la latencia esperada del percentil 95 limita la carga extra a alrededor del 5% y reduce sustancialmente la cola. [DOC] ([Dean y Barroso, CACM 2013](https://research.google/pubs/the-tail-at-scale/))
- *Hedging* en gRPC: `maxAttempts` (≤ 5), `hedgingDelay`, `nonFatalStatusCodes`; las coberturas obedecen al *retry throttling*. [DOC] ([gRPC A6](https://github.com/grpc/proposal/blob/master/A6-client-retries.md))
- Implementación sobre Loom: el primer intento se ejecuta en el hilo llamante; la cobertura arranca un hilo virtual cuando salta el temporizador (§6.3); gana la primera finalización vía CAS sobre un slot de resultado; los perdedores se interrumpen. El *hedging* requiere idempotencia (un atributo explícito de la anotación) y debe consumir el presupuesto de reintentos. El retardo sale de un histograma de latencia en vivo (§7.2) o es fijo. [PROP]
- Resilience4j tiene un módulo `hedge` más reciente (ver doc 01). Comparar semánticas ahí. [INF]

### 8.2 Límites de concurrencia adaptativos

- [Netflix concurrency-limits](https://github.com/Netflix/concurrency-limits): límite ≈ RPS × latencia (ley de Little). Algoritmos: **Vegas** (estimación de cola `L·(1 − minRTT/sampleRTT)`, ±1 frente a los umbrales α/β), **Gradient2** (divergencia entre EWMA de latencia a largo y a corto plazo), **AIMD** (basado en pérdidas). Limitadores: `SimpleLimiter`, particionado (cuota por prioridad), bloqueante. [DOC]
- El filtro de concurrencia adaptativa de Envoy usa un controlador de gradiente con re-medición periódica de minRTT. [DOC] ([documentación de Envoy](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/adaptive_concurrency_filter))
- **Diseño de la ruta caliente** [PROP]: contador en curso (como en el bulkhead, §4.1) frente a un `volatile int limit`; al terminar, sumar el RTT a un acumulador por ventana (suma + conteo con `LongAdder`; min-RTT mediante CAS-mínimo sobre un `long`). El **hilo que cruza el borde de la ventana** (CAS sobre la época de ventana) recalcula `limit`. Los demás solo pagan `getAndAdd` ×2 más un `LongAdder.add`. Sin hilo de fondo.
- Es un diferenciador claro: un "bulkhead adaptativo" que no necesita ajuste manual. [INF]

### 8.3 *Load shedding* (descarte de carga)

- *Throttling* adaptativo en el cliente (Google SRE): rechazar localmente con probabilidad `max(0, (requests − K·accepts) / (requests + 1))`, con K ≈ 2. [DOC (K y las definiciones están citadas en la página; la fórmula en sí se muestra como imagen en la [página del libro SRE](https://sre.google/sre-book/handling-overload/))]. Implementación: dos contadores de ventana deslizante (anillo por tiempo de §2.2) más `ThreadLocalRandom`. [PROP]
- Disciplina de cola en el servidor para los llamantes en espera: estilo CoDel ("si la cola no ha estado vacía en X ms, descartar") y **LIFO adaptativo** bajo sobrecarga, según lo describe Facebook. [DOC] ([Maurer, *Fail at Scale*, ACM Queue 2015](https://doi.org/10.1145/2814330); un precedente en Java es el [`AdaptiveLifoCoDelCallQueue`](https://hbase.apache.org/2.5/devapidocs/src-html/org/apache/hadoop/hbase/ipc/AdaptiveLifoCoDelCallQueue.html) de HBase)
- Particiones por prioridad (cuotas de un límite para crítico frente a batch), como en el limitador particionado de Netflix. [DOC]

---

## 9. Metodología de benchmarking (JMH vs Resilience4j)

### 9.1 Reglas básicas

- Solo [JMH](https://github.com/openjdk/jmh). Forks ≥ 3, calentamiento ≥ 5×1 s, medición ≥ 5×1 s. `-Xms=-Xmx` fijos, más `-XX:+AlwaysPreTouch`. Ejecutar en **JDK 21 y JDK 25** (el temporizador de hilos virtuales y el *delayer* de `CompletableFuture` cambiaron, §5.3/§6.2), en **x86-64 y AArch64** (los atómicos se comportan distinto). Fijar la frecuencia de CPU y desactivar el turbo cuando sea posible. Publicar el JSON de JMH y el hardware exacto. [PROP]
- Comparar con la **última versión de Resilience4j (2.4.0)** con configuración *equivalente*: mismo tipo y tamaño de ventana, ambas estrategias `SYNCHRONIZED` y `LOCK_FREE`, `writableStackTraceEnabled=false` en los benchmarks de rechazo (justo para ellos), y eventos con y sin consumidores. Reutilizar sus propios escenarios JMH (p. ej. [`CircuitBreakerBenchmark`](https://github.com/resilience4j/resilience4j/tree/master/resilience4j-circuitbreaker/src/jmh/java/io/github/resilience4j/circuitbreaker)) para que nadie pueda alegar que elegimos a conveniencia. [PROP]
- **Línea base**: la llamada sin decorar, para poder reportar el *sobrecoste* = política − línea base. [PROP]
- **Contaminación de perfiles (justicia para ambas bibliotecas):** antes de medir, decorar y ejercitar ≥ 3–4 clases distintas de *supplier*/destino en la misma JVM, para que los sitios de llamada compartidos sean megamórficos como en aplicaciones reales. Reportar números "limpios" y "contaminados". [PROP]
- Perfiladores: `-prof gc` (`gc.alloc.rate.norm` B/op), `-prof perfnorm` (instrucciones y fallos de caché por operación), `-prof perfasm` o async-profiler para puntos calientes, JFR `jdk.VirtualThreadPinned` en ejecuciones con hilos virtuales. Usar `Blackhole.consumeCPU(n)` para simular trabajo. [DOC][PROP]
- Corrección: [jcstress](https://github.com/openjdk/jcstress) para cada estructura lock-free (contadores de ventana, CAS de estado, GCRA, protocolo del *watchdog*). Un circuit breaker rápido pero incorrecto es un bug. [PROP]

### 9.2 Escenarios

| # | Escenario | Modos | Hilos | Qué muestra |
|---|---|---|---|---|
| 1 | Ruta feliz por política (CB cerrado, RL bajo el límite, BH bajo el límite, retry con éxito al primer intento, TL rápido) | `AverageTime`, ns/op | 1 | Sobrecoste puro, B/op |
| 2 | Igual, instancia compartida | `Throughput` | 1, 2, 4, …, 2× núcleos | Escalado bajo contención (*false sharing*, tormentas de CAS, convoyes de locks) |
| 3 | Ruta de rechazo (CB abierto, RL agotado, BH lleno) | `Throughput` + `-prof gc` | 1 y máx. | Costes de excepciones y eventos bajo sobrecarga (cuando más importa) |
| 4 | Resultados mixtos (1%, 10%, 50% de fallos, llamadas lentas) | `Throughput` | máx. | Evaluación de umbrales y actualización de ventanas |
| 5 | CB oscilante (open/half-open/close frecuentes) | `Throughput` | máx. | Corrección y coste de transiciones y sondeos |
| 6 | Retry con fallos, backoff cero | `AverageTime` | 1 | Coste de la maquinaria de retry por intento |
| 7 | Pila completa Retry→CB→RL→BH→TL vs `Decorators` | `AverageTime`, `Throughput` | 1, máx. | Sobrecoste de composición (§7.4) |
| 8 | Timeout: programar+cancelar por llamada vs `orTimeout` vs `TimeLimiter` de R4j | `Throughput` | 1, máx. | Coste de la estructura del temporizador |
| 9 | 10k hilos virtuales a través de BH/RL con espera | `SampleTime` (p50/p99/p999) | HV | Justicia, latencia de cola, *pinning* |
| 10 | Huella: 10k instancias con nombre | JOL / histograma del heap | — | Coste en memoria del relleno y del estado |
| 11 | Perfil JIT contaminado vs limpio (§9.1) | como #1/#7 | 1 | Números realistas vs en el mejor caso |

### 9.3 Resultado esperado (hipótesis a confirmar)

- Ruta feliz con un hilo: la ventaja viene de eliminar asignaciones y de leer menos el reloj. Se espera una mejora modesta (decenas de ns/op) con perfiles "limpios" y una mayor con perfiles "contaminados". [INF]
- *Throughput* bajo contención: las mayores mejoras se esperan en el circuit breaker (lock vs *wait-free*) y en el rate limiter (asignación por intento de CAS y *back-off* con `parkNanos` frente a un CAS simple). [INF]
- Ruta de rechazo: excepciones preasignadas sin traza y sin eventos anticipados deberían ser órdenes de magnitud más baratas que un `String.format` por rechazo. [INF]
- No debemos afirmar ninguna cifra antes de medir. Todo lo anterior debe confirmarse en la fase 2. [PROP]

---

## 10. Recomendaciones por política (resumen)

1. **Circuit breaker:** una palabra de estado en `VarHandle` más tokens de generación. Ventana por conteo *wait-free* (`getAndSet` de slot + contadores) y buckets de tiempo etiquetados por época. Omitir la evaluación en éxitos rápidos una vez caliente. Sin medición de tiempo salvo que haga falta. Rechazos preasignados.
2. **Rate limiter:** GCRA (un `long`, CAS). Una ruta de rechazo de solo lectura. Las configuraciones de token bucket y por periodo se traducen a él. Particionado opcional.
3. **Bulkhead:** un contador *fail-fast* con `getAndAdd`. Semáforo solo para esperar. El aislamiento por pool de hilos, más adelante y opcional. Un límite adaptativo como política hermana.
4. **Retry:** solo variables locales, asignar ante fallo. *Decorrelated* o *full jitter* con `ThreadLocalRandom`. Un presupuesto de reintentos basado en tokens, compartido por dependencia.
5. **Timeout:** un *watchdog* en el hilo llamante con un protocolo de interrupción estricto. Un temporizador de rueda hash o de barrido de slots con un único hilo demonio de plataforma. Lo asíncrono usa el mismo temporizador.
6. **Diferenciadores:** *hedging* sobre hilos virtuales con retardo guiado por histograma; límites adaptativos Vegas/Gradient; *throttling* adaptativo de SRE; presupuestos de reintento.
7. **Transversal:** callbacks de listener primitivos; métricas *pull*; **código acquire/release monomórfico generado**; un par de lecturas de reloj por pipeline; relleno de las palabras con escritura caliente; `0 B/op` como puerta de CI.

---

## Fuentes

- Código de Resilience4j, `master` @ `b7c0069`: <https://github.com/resilience4j/resilience4j> (archivos enlazados en el texto). PR de la ventana lock-free: <https://github.com/resilience4j/resilience4j/pull/2241>. Versiones: <https://github.com/resilience4j/resilience4j/releases>
- OpenJDK `VirtualThread.java`: <https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/VirtualThread.java>, <https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/lang/VirtualThread.java>
- OpenJDK `CompletableFuture.java` (21 vs 25): <https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java>, <https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java>
- OpenJDK `Socket.java` (semántica de interrupción con hilos virtuales): <https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/net/Socket.java>
- JEPs: [444](https://openjdk.org/jeps/444), [491](https://openjdk.org/jeps/491), [505](https://openjdk.org/jeps/505), [506](https://openjdk.org/jeps/506), [525](https://openjdk.org/jeps/525), [533](https://openjdk.org/jeps/533), [543](https://openjdk.org/jeps/543), [142](https://openjdk.org/jeps/142); [JDK-8234863](https://bugs.openjdk.org/browse/JDK-8234863)
- InfoQ sobre JEP 533 (JDK 27): <https://www.infoq.com/news/2026/05/jep-533-jdk-27/>; sobre JEP 543 (JDK 28): <https://www.infoq.com/news/2026/10/jep-543-jdk-28/>
- A. Shipilëv, *Nanotrusting the Nanotime*: <https://shipilev.net/blog/2014/nanotrusting-nanotime/>
- M. Brooker, *Exponential Backoff And Jitter*: <https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/>
- Libro SRE de Google, *Handling Overload*: <https://sre.google/sre-book/handling-overload/>
- gRPC A6, reintentos de cliente y *hedging*: <https://github.com/grpc/proposal/blob/master/A6-client-retries.md>
- Dean y Barroso, *The Tail at Scale*: <https://research.google/pubs/the-tail-at-scale/>
- Netflix concurrency-limits: <https://github.com/Netflix/concurrency-limits>
- Concurrencia adaptativa de Envoy: <https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/adaptive_concurrency_filter>
- B. Maurer, *Fail at Scale*, ACM Queue (2015): <https://doi.org/10.1145/2814330>; `AdaptiveLifoCoDelCallQueue` de HBase: <https://hbase.apache.org/2.5/devapidocs/src-html/org/apache/hadoop/hbase/ipc/AdaptiveLifoCoDelCallQueue.html>
- GCRA: <https://en.wikipedia.org/wiki/Generic_cell_rate_algorithm>, <https://brandur.org/rate-limiting>
- Varghese y Lauck, ruedas de tiempo: <https://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf>
- `HashedWheelTimer` de Netty: <https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/HashedWheelTimer.java>
- JMH: <https://github.com/openjdk/jmh>; jcstress: <https://github.com/openjdk/jcstress>; HdrHistogram: <https://github.com/HdrHistogram/HdrHistogram>
