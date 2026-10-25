# 01 — Panorama: Resilience4j y antecedentes

> **Histórico.** Esta investigación es anterior a la implementación y se conserva tal como se escribió; para lo que hace hoy la librería, ver [features](../policies/README.md), [arquitectura](../architecture.md) y [benchmarks](../benchmarks.md).

> Investigación de la fase 1. Sin código de producción. Última actualización: 2026-10-23.
> Alcance: qué existe, cómo funciona internamente la referencia (Resilience4j), de dónde vienen sus costes y qué puede hacer mejor una nueva biblioteca basada en anotaciones, en Java puro y para Java 21+.
> Fuera de alcance (lo cubren documentos hermanos): la elección del mecanismo de procesamiento de anotaciones, el diseño detallado de primitivas de concurrencia, la superficie de API/configuración y la estructura de módulos.

## Leyenda de evidencia

Cada afirmación lleva una etiqueta:

| Etiqueta | Significado |
|---|---|
| **[SRC]** | Verificado leyendo el código fuente. Resilience4j en `master`, commit `b7c0069` (2026-10-07, posterior a 2.4.0); Failsafe en `master` (último commit 2025-12-28); Sentinel `master` vía la API de GitHub. |
| **[BENCH]** | Verificado a partir de un benchmark publicado o de un fichero de resultados en un repositorio. |
| **[DOC]** | Verificado en documentación oficial, notas de versión o el README de un proyecto. |
| **[ISSUE]** | Verificado en un issue/PR de GitHub (un informe de usuario, no necesariamente confirmado por los mantenedores). |
| **[INF]** | Inferido por nosotros (razonamiento a partir del código o del comportamiento de la JVM, sin medir). Debe validarse con JMH en una fase posterior. |

---

## 1. Resilience4j en profundidad

### 1.1 Estado y forma

- Última versión **2.4.0** (2026-03-14); 2.3.0 (2025-01-03); ~10,8k estrellas; ~348 issues abiertos. [DOC] ([releases](https://github.com/resilience4j/resilience4j/releases))
- La 2.4.0 subió la base de compilación de JDK 17 a **JDK 21** "para soportar hilos virtuales" (PR #2331); `build.gradle` fija `sourceCompatibility = JavaVersion.VERSION_21`. [DOC][SRC] Por tanto, "Java 21" ya no es por sí solo un factor diferenciador.
- Vavr se eliminó como dependencia en la 2.0.0. [DOC] ([RELEASENOTES.adoc](https://github.com/resilience4j/resilience4j/blob/master/RELEASENOTES.adoc))
- Multiproyecto Gradle con ~38 módulos: `circuitbreaker`, `retry`, `ratelimiter`, `bulkhead`, `timelimiter`, `cache` (JCache), `hedge` (reciente), `circularbuffer`, `consumer`, `core`, `annotations`, `micrometer`, `metrics`, `feign`, `kotlin`, `reactor`, `rxjava2/3`, `vavr`, `spring6`, `spring-boot3`, `spring-boot4`, `micronaut`, `commons-configuration`, `framework-common`, `all`, `bom`, `test`. [SRC]

### 1.2 Funcionalidades por módulo

| Módulo | Qué hace | Notas de implementación |
|---|---|---|
| **CircuitBreaker** | CLOSED / OPEN / HALF_OPEN más DISABLED, FORCED_OPEN y METRICS_ONLY. Ventana deslizante por conteo o por tiempo; umbrales de tasa de fallos **y** de llamadas lentas; `minimumNumberOfCalls`; predicados para registrar o ignorar excepciones; predicado de resultado. | Un objeto por estado detrás de `AtomicReference<CircuitBreakerState>`; transiciones por CAS. Un `ReentrantLock` protege solo la transición OPEN→HALF_OPEN. [SRC] |
| **Retry** | Intentos máximos, `IntervalFunction` (fija, exponencial, aleatorizada, personalizada), predicados de reintento por excepción o por resultado, síncrono y asíncrono. | Un `ContextImpl` nuevo por llamada con un `AtomicInteger` y dos `AtomicReference` (4 objetos por llamada aunque el primer intento tenga éxito); duerme mediante una función estática `Thread::sleep`. [SRC] |
| **RateLimiter** | `AtomicRateLimiter` (por defecto, basado en ciclos) y `SemaphoreBasedRateLimiter` (recarga con un planificador). | Ver §1.4. |
| **Bulkhead** | `SemaphoreBulkhead` (`java.util.concurrent.Semaphore`, equidad opcional, `maxWaitDuration`) y `FixedThreadPoolBulkhead` (`ThreadPoolExecutor` + `ArrayBlockingQueue`/`SynchronousQueue`, `CompletableFuture.supplyAsync`, `ContextPropagator`). | [SRC] |
| **TimeLimiter** | Timeouts para `Future` (`future.get(timeout)`) y `CompletionStage` (programa una `TimeoutException` en un `ScheduledExecutorService`). | No puede interrumpir una llamada síncrona simple: necesita un future, así que el código síncrono debe delegarse a otro hilo. [SRC] |
| **Cache** | Envuelve JCache (JSR-107). | Poco usado; envoltorio fino. [SRC][INF] |
| **Hedge** | Peticiones "hedged" (issue #1454). | Módulo reciente. [SRC] |
| **Fallback** | No hay módulo en el núcleo. Está disponible como `Decorators.withFallback(...)` y, con anotaciones, como un nombre de método `fallbackMethod = "name"` que las integraciones de Spring/Micronaut resuelven por reflexión. | Ver §1.3. [SRC] |
| **Registros** | `CircuitBreakerRegistry`, etc., con configuraciones con nombre ("default" más otras con nombre); `InMemoryRegistryStore` sobre `ConcurrentHashMap<String, CompletableFuture<E>>`, que evita a propósito `computeIfAbsent` para no fijar ("pin") hilos virtuales. | [SRC] |
| **Eventos** | `EventProcessor` con consumidores en `CopyOnWriteArraySet` más un mapa por tipo de evento. Protección mediante `hasConsumers()`. | Ver §1.5. [SRC] |

### 1.3 Cómo funcionan las anotaciones y qué obtiene quien usa Java puro

El módulo `resilience4j-annotations` contiene **solo declaraciones de anotaciones**: `@CircuitBreaker(name, configuration, fallbackMethod)`, `@Retry`, `@RateLimiter`, `@Bulkhead`, `@TimeLimiter`, `@Timer`. No tiene procesador. [SRC] ([CircuitBreaker.java](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-annotations/src/main/java/io/github/resilience4j/circuitbreaker/annotation/CircuitBreaker.java))

El comportamiento lo implementan:

- **Spring** (`resilience4j-spring6`, `spring-boot3/4`): aspectos `@Around` con anotaciones de AspectJ, ejecutados por proxies de Spring AOP (`CircuitBreakerAspect`, `RetryAspect`, ...). [SRC]
- **Micronaut** (`resilience4j-micronaut`): `MethodInterceptor`s de la AOP en tiempo de compilación de Micronaut. [SRC]

**En Java puro no hay nada declarativo.** Sin Spring ni Micronaut, el usuario debe usar la API funcional de decoradores (`CircuitBreaker.decorateSupplier(cb, supplier)`, `Decorators.ofSupplier(..).withRetry(..).withCircuitBreaker(..)`) o configurar por su cuenta el tejido ("weaving") de AspectJ, y los aspectos de Spring dependen de beans de Spring: `SpelResolver`, `BeanFactory`, `FallbackExecutor`. [SRC][INF] Es la mayor carencia ergonómica para nuestro público objetivo.

**Trabajo por llamada en el aspecto de Spring.** `CircuitBreakerAspect.circuitBreakerAroundAdvice` hace lo siguiente en **cada** invocación [SRC] ([aspecto](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/circuitbreaker/configure/CircuitBreakerAspect.java), [DefaultSpelResolver](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/spelresolver/DefaultSpelResolver.java), [FallbackExecutor](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackExecutor.java), [FallbackMethod](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackMethod.java)):

1. `MethodSignature.getMethod()`; después construye `methodName` concatenando cadenas (`declaringClass.getName() + "#" + method.getName()`).
2. `spelResolver.resolve(method, args, annotation.name())`: hasta cuatro comprobaciones con expresiones regulares (`Pattern.matcher(...).matches()`) sobre el nombre, incluso si es un literal. Si es una expresión, crea un `MethodBasedEvaluationContext` nuevo y **parsea la expresión SpEL en cada llamada**.
3. Búsqueda en el registro con `getOrCreateCircuitBreaker(methodName, backend, configKey)`.
4. Reserva una lambda `CheckedSupplier` que captura el join point.
5. `FallbackExecutor.execute` resuelve **otra vez** el nombre del fallback con SpEL y después llama a `FallbackMethod.create(...)`, que crea un `MethodMeta`, clona `getParameterTypes()`, hace un `ConcurrentHashMap.computeIfAbsent` sobre una caché estática y crea un `FallbackMethod` nuevo. El fallback se invoca después mediante `Method.invoke` reflexivo.
6. Inspecciona el tipo de retorno (`CompletionStage`, lista de extensiones Reactor/RxJava) para elegir el manejador.

Hay usuarios que han reportado lentitud en el aspecto (issue [#1377](https://github.com/resilience4j/resilience4j/issues/1377), "CircuitBreaker advice taking 2s", probablemente un efecto de configuración o de llamadas lentas, sin resolver). [ISSUE] También hay una petición abierta para soportar **meta-anotaciones** o anotaciones compuestas ([#2405](https://github.com/resilience4j/resilience4j/issues/2405), 2026-03). [ISSUE]

**Conclusión:** todo lo de los pasos 1–6, salvo el permiso y el registro reales, es invariante por método y puede resolverse una sola vez, en tiempo de compilación o en la primera llamada. [INF]

### 1.4 Detalles internos del núcleo

**Máquina de estados del circuit breaker** ([CircuitBreakerStateMachine.java](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java)) [SRC]
- `decorateSupplier` llama a `acquirePermission()`, lee la marca de tiempo inicial, ejecuta el supplier y luego `onResult(duration, unit, result)` u `onError(...)`. No trata `java.lang.Error`.
- Las marcas de tiempo vienen de `Function<Clock, Long> currentTimestampFunction`, por defecto `clock -> System.nanoTime()`. El retorno **`Long` en caja ("boxed")** implica una posible reserva de memoria por marca de tiempo, dos por llamada, salvo que el análisis de escape la elimine. [SRC] la firma, [INF] la reserva.
- La expiración del estado OPEN usa `clock.instant().isAfter(...)`, que crea un `Instant` por cada llamada rechazada. [SRC]/[INF]
- El camino de registro: `ClosedState.onSuccess` → `CircuitBreakerMetrics.onSuccess` → `Metrics.record(...)` devuelve un **`SnapshotImpl` nuevo** en cada llamada. [SRC]

**Ventanas deslizantes** (`resilience4j-core/.../core/metrics`) [SRC]
- `FixedSizeSlidingWindowMetrics` (por conteo) y `SlidingTimeWindowMetrics` (por tiempo): un anillo de buckets `Measurement` más un `TotalAggregation`, con todas las mutaciones bajo **un único `ReentrantLock`** (era `synchronized` hasta la 2.3.0; se cambió para no fijar hilos virtuales, #2232). Cada `record` crea un `SnapshotImpl`. **Sigue siendo la opción por defecto** (`DEFAULT_SLIDING_WINDOW_SYNCHRONIZATION_STRATEGY = SYNCHRONIZED`).
- Desde la 2.3.0 existe la opción explícita `SlidingWindowSynchronizationStrategy.LOCK_FREE` ([PR #2241](https://github.com/resilience4j/resilience4j/pull/2241), verificada con Lincheck). Surgió de un usuario que vio `metrics.record` bloqueándose bajo carga ([#2151](https://github.com/resilience4j/resilience4j/issues/2151)). [ISSUE] La versión sin bloqueos por conteo es una lista enlazada de nodos actualizada por CAS. **Cada registro copia el `PackedAggregation` del nodo cola, crea un `Node` nuevo y crea un `SnapshotImpl`**: no usa bloqueos, pero sí reserva memoria, y reintenta con `CASBackoffUtil` bajo contención. [SRC]
- Defecto encontrado durante la lectura: la ventana por tiempo LOCK_FREE se construye con `io.github.resilience4j.core.Clock.SYSTEM`, **ignorando el `Clock` configurado por el usuario** (la variante SYNCHRONIZED sí recibe `clock`) (`CircuitBreakerMetrics.buildTimeBasedMetrics`). [SRC] Con un reloj falso, las pruebas se comportan distinto según la estrategia.

**AtomicRateLimiter** ([fuente](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/AtomicRateLimiter.java)) [SRC]
- `State(config, activeCycle, activePermissions, nanosToWait)` inmutable dentro de un `AtomicReference`. El tiempo se divide en ciclos de `limitRefreshPeriod`. Los permisos se recargan de forma perezosa al acceder (sin hilo de fondo), y `activePermissions` puede ser negativo para "reservar" permisos futuros.
- `updateStateWithBackOff`: bucle CAS. **Cada intento crea un `State` nuevo** (los intentos fallidos son basura), con `parkNanos(1)` como espera tras un CAS fallido. Bajo mucha contención es un diseño de una sola palabra caliente: todos los llamadores hacen CAS sobre la misma referencia. [SRC]; el comportamiento bajo contención es [INF].
- La espera se hace con bucles de `parkNanos` en el hilo llamante.

**Bulkhead**: `java.util.concurrent.Semaphore` (AQS) en la variante de semáforo. Sirve para contención baja o moderada; AQS encola a los que esperan. [SRC]/[INF]

**Retry**: el `ContextImpl` por llamada descrito antes (4 reservas). Las métricas por instancia son `LongAdder` (bien). [SRC]

### 1.5 Publicación de eventos

- `EventProcessor.hasConsumers()` comprueba dos colecciones (`CopyOnWriteArraySet` más un `ConcurrentHashMap`). [SRC]
- `publishSuccessEvent` y `publishCircuitErrorEvent` comprueban `hasConsumers()` **antes** de crear el evento. `publishCallNotPermittedEvent`, `publishCircuitIgnoredErrorEvent`, `publishStateTransitionEvent`, `publishResetEvent` y los eventos de umbral de fallos superado **crean primero el evento** (el constructor de cada evento llama a `ZonedDateTime.now(...)`). La llamada rechazada es el camino caliente cuando el breaker está OPEN. [SRC] El issue [#1391](https://github.com/resilience4j/resilience4j/issues/1391) (reserva de eventos en el camino de éxito, con captura de un profiler) sigue abierto desde 2021. [ISSUE]
- **En Spring Boot los eventos siempre están activos:** `CircuitBreakerConfiguration.registerEventConsumer` añade un `CircularEventConsumer` (búfer por defecto **100**) a **todos** los circuit breakers para los endpoints de actuator. Así, en el despliegue más común, cada llamada crea un objeto evento (`CircuitBreakerOnSuccessEvent` con un `ZonedDateTime`, un `Duration` y un nombre) y lo inserta en el búfer circular. [SRC]; la magnitud del coste es [INF].

### 1.6 Cifras publicadas

- El repositorio incluye `resilience4j-circuitbreaker/src/jmh/resources/resultsWithGCProfiling.txt` (ejecución antigua, época de Parallel GC): [BENCH]

  | Benchmark | ops/µs | B/op |
  |---|---|---|
  | `directSupplier` (sin CB) | 12,26 | ~0 |
  | `protectedSupplier` (CB) | 4,12 | **272** |
  | `protectedSupplierWithOneConsumer` | 4,02 | 284 |

  Históricamente, un circuit breaker sin más añadía ~0,16 µs y **272 bytes por llamada** en un solo hilo. No hay cifras actuales publicadas; existen fuentes JMH para CB, bulkhead, rate limiter y métricas (incluido `VirtualThreadMetricsBenchmark`). [SRC]
- Issue [#117](https://github.com/resilience4j/resilience4j/issues/117) (2017): un mantenedor creó un prototipo de **CircuitBreaker sin reservas de memoria**, que no se adoptó porque exigía cambiar la API. [ISSUE] Demuestra que el objetivo es alcanzable y que lo que lo impide es la forma de la API (objetos evento, valores de retorno `Snapshot`).
- Issue [#1513](https://github.com/resilience4j/resilience4j/issues/1513) (tesis de grado, R4j 1.6.1, Java 11): ~500 ns/op de sobrecarga del CB en la ejecución JMH del usuario. [ISSUE] (depende del hardware).
- Issue [#2023](https://github.com/resilience4j/resilience4j/issues/2023): el bulkhead de semáforo y el de pool de hilos se comportan de forma inesperada bajo carga. [ISSUE]
- Académico: un artículo de SBRC compara Retry/CB en Polly y Resilience4j y concluye que el impacto en tiempo de ejecución es "leve a moderado" ([artículo](https://sol.sbc.org.br/index.php/sbrc/article/download/21194/21019)). [DOC] (solo el resumen)
- **No encontramos ninguna comparación JMH pública** entre Resilience4j, Failsafe y SmallRye. [DOC, resultado negativo] Deberíamos publicar una: sirve a la vez para validar y para difundir el proyecto.

### 1.7 De dónde viene la sobrecarga de Resilience4j (resumen)

| # | Origen | Evidencia |
|---|---|---|
| 1 | Resolución de nombres por SpEL/regex en cada llamada, concatenación de cadenas, búsqueda del fallback y captura de lambdas en los aspectos de Spring | [SRC] |
| 2 | Invocación reflexiva del fallback (`Method.invoke`) | [SRC] |
| 3 | Objetos evento creados en cada llamada siempre que haya algún consumidor (siempre en Spring Boot); algunos tipos se crean aunque no haya consumidores | [SRC] |
| 4 | `SnapshotImpl` creado en cada `record` (ambas estrategias); la ventana sin bloqueos además crea un `Node` y una copia de la agregación | [SRC] |
| 5 | La ventana deslizante por defecto se serializa bajo un único `ReentrantLock` → contención con muchos hilos (#2151) | [SRC][ISSUE] |
| 6 | Marcas de tiempo `Long` en caja vía `Function<Clock, Long>`; `Instant` en el estado OPEN | [SRC] / [INF] |
| 7 | Retry crea 4 objetos por llamada aunque el primer intento tenga éxito | [SRC] |
| 8 | Rate limiter: un único `AtomicReference<State>`, un `State` nuevo por intento CAS | [SRC] |
| 9 | Cadenas de `Supplier` megamórficas al apilar decoradores (`Decorators`), lo que limita el inlining | [INF] |
| 10 | Configuración en objetos grandes con campos `Predicate`/`Function` evaluados en cada llamada (los predicados de registrar/ignorar excepciones recorren jerarquías de clases) | [SRC] parcial / [INF] |

---

## 2. Competidores y antecedentes

### 2.1 Failsafe (`dev.failsafe`)

- **Estado:** 3.3.2 es la última etiqueta; los últimos commits son de diciembre de 2025 (solo CI y pruebas) y, antes, de junio de 2024. **En la práctica, en mantenimiento.** [DOC] ([repo](https://github.com/failsafe-lib/failsafe))
- **Funcionalidades:** RetryPolicy, CircuitBreaker (por conteo y por tiempo, con umbral por proporción), RateLimiter (suave y en ráfagas), Bulkhead, Timeout, Fallback; composición de políticas `Failsafe.with(fallback, retry, cb, timeout)`; síncrono, asíncrono (`CompletableFuture`) y un estilo `AsyncExecution` para APIs de callbacks; `Call` para ejecuciones cancelables; listeners de eventos por política. [DOC][SRC]
- **Anotaciones:** ninguna. [DOC]
- **Rendimiento:** el registro del estado del circuito es **`synchronized`** (`CircuitState.recordSuccess/recordFailure`, `ClosedState.checkThreshold`, `HalfOpenState`), igual que `SyncExecutionImpl.preExecute/postExecute`. Se crea un `SyncExecutionImpl` por llamada y un `ExecutionResult` por intento. [SRC] En Java 21–23, `synchronized` fija los hilos virtuales; [JEP 491](https://openjdk.org/jeps/491) no lo resolvió hasta JDK 24. [DOC]
- **Copiar:** el modelo de composición limpio (las políticas son configuración pura, los ejecutores se crean por composición y la política más externa va primero); `Call` y la cancelación; un modelo de listeners coherente; cero dependencias.
- **Evitar:** `synchronized` de grano grueso; objetos de ejecución por llamada; ausencia de capa declarativa.

### 2.2 SmallRye Fault Tolerance / MicroProfile Fault Tolerance (referencia clave)

- **Estado:** especificación MicroProfile FT **4.1** (4.1.2, marzo de 2025). SmallRye FT **6.9.x / 6.10.x** (septiembre de 2026), en desarrollo activo; es la implementación de Quarkus y WildFly. [DOC] ([especificación](https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html), [SmallRye](https://github.com/smallrye/smallrye-fault-tolerance))
- **Anotaciones de la especificación:** `@Retry(maxRetries, delay, maxDuration, jitter, retryOn, abortOn)`, `@Timeout`, `@Fallback(value=Handler.class | fallbackMethod, applyOn, skipOn)`, `@CircuitBreaker(requestVolumeThreshold, failureRatio, successThreshold, delay, failOn, skipOn)`, `@Bulkhead(value, waitingTaskQueue)`, `@Asynchronous`. [DOC]
- **Configuración externa (copiar esto):** cualquier parámetro de una anotación puede sobrescribirse con MicroProfile Config, y gana la clave más específica: `<class>/<method>/<Annotation>/<param>` > `<class>/<Annotation>/<param>` > `<Annotation>/<param>`. Lo mismo vale para `enabled`, más un `MP_Fault_Tolerance_NonFallback_Enabled` global. [DOC] Es justo el modelo "valores por defecto + sobrescritura por método + configuración externa" que describen nuestros objetivos, y está probado en producción.
- **Semántica que conviene copiar:** un contrato de interacción definido (Fallback es lo más externo; cada reintento pasa por el CB; el timeout se reinicia en cada intento; el CB se comprueba antes del bulkhead); el estado del circuit breaker es un singleton por (clase del bean, método). [DOC]
- **Extensiones de SmallRye:** `@RateLimit`, `@ExponentialBackoff`, `@FibonacciBackoff`, `@CustomBackoff`, `@RetryWhen`, `@BeforeRetry`, y tipos asíncronos de Kotlin/Mutiny/RxJava. **`@ApplyGuard("name")`** (6.7.0+, antes `@ApplyFaultTolerance`) aplica a muchos métodos un `Guard` con nombre, compartido y construido programáticamente, configurable con `smallrye.faulttolerance."name".retry.max-retries` (6.8.0+). [DOC] ([reusable](https://smallrye.io/docs/smallrye-fault-tolerance/6.8.0/reference/reusable.html))
- **Java puro:** `smallrye-fault-tolerance-standalone` soporta **solo la API programática** (`Guard`, `TypedGuard`); las anotaciones requieren interceptores CDI (ArC/Weld). [DOC] ([API programática](https://smallrye.io/docs/smallrye-fault-tolerance/6.9.3/reference/programmatic-api.html)) Igual que en Resilience4j, no hay camino declarativo independiente.
- **Rendimiento:** Quarkus valida las anotaciones y precalcula en compilación (ArC) las cadenas de estrategias por método. [INF a partir de la arquitectura de Quarkus; no medido aquí]
- **Evitar:** `Future` como tipo asíncrono; correspondencia de excepciones solo mediante `Class<?>[]`; exigir un contenedor.

### 2.3 Netflix Hystrix (histórico)

- **Estado:** en modo mantenimiento desde 2018 (versión final 1.5.18). El README de Netflix remite a Resilience4j y a los **límites de concurrencia adaptativos**. [DOC] ([README](https://github.com/Netflix/Hystrix))
- **Lecciones:**
  - El aislamiento por pool de hilos por defecto era caro (un cambio de contexto por llamada y la pérdida de thread-locals), y la mayoría de usuarios acabó usando semáforos. El aislamiento por semáforo o límite debe ser el valor por defecto; delegar a otro hilo debe ser opcional. [DOC/INF]
  - El patrón de objeto comando (una subclase de `HystrixCommand` por llamada) es pesado y perjudicaba la ergonomía. `@HystrixCommand` (javanica) necesitaba AspectJ/Spring. [DOC]
  - Los "health counts" de ventana deslizante sobre buckets de tiempo y el colapsado de peticiones eran buenas ideas. Los detalles internos basados en RxJava eran costosos. [INF]
  - Los umbrales estáticos, ajustados de antemano, son frágiles. Netflix pasó a límites adaptativos ([concurrency-limits](https://github.com/Netflix/concurrency-limits), v0.5.4 de diciembre de 2025: Vegas, Gradient2, AIMD). [DOC]

### 2.4 Alibaba Sentinel

- **Estado:** 1.8.10 (mayo de 2026), activo. [DOC] ([repo](https://github.com/alibaba/Sentinel))
- **Funcionalidades:** control de flujo por QPS o por hilos concurrentes, con calentamiento ("warm-up") y modelado de ritmo; corte de circuito (proporción de lentas, proporción de errores, número de errores); control de flujo por **parámetros calientes**; protección adaptativa del sistema (carga/CPU/RT); control de flujo en clúster; panel de control; fuentes de reglas dinámicas (Nacos, ZooKeeper, Apollo, ...). [DOC]
- **Anotaciones:** `@SentinelResource(value, blockHandler, fallback, ...)` mediante `sentinel-annotation-aspectj` (AspectJ) o `sentinel-annotation-cdi-interceptor`. [SRC] Tampoco hay tejido independiente.
- **Detalles internos (copiar):** `LeapArray`, un anillo de buckets de tiempo en un `AtomicReferenceArray<WindowWrap>`, que se crean por CAS y se reinician bajo `tryLock()` con `Thread.yield()` como alternativa. Los contadores por bucket son **`LongAdder[]`** (`MetricBucket`), así que están repartidos y toleran bien la contención. [SRC]
- **Evitar:** la maquinaria global de thread-locals `Context`/`Entry` y una cadena de slots por recurso con recursos identificados por cadenas (pesado); tener las reglas desacopladas del código dificulta razonar localmente. [INF]

### 2.5 Spring Retry → resiliencia en Spring Framework 7

- **Spring Retry está archivado**; su README dice que ha sido "superseded by Spring Framework 7". Última versión 2.0.13 (junio de 2026). [DOC] ([repo](https://github.com/spring-projects/spring-retry))
- **Spring Framework 7.0** trae resiliencia en el núcleo: `@Retryable(includes, excludes, maxAttempts, delay, jitter, multiplier, maxDelay)`, `@ConcurrencyLimit(n)` ("particularly useful with Virtual Threads"), `RetryTemplate`/`RetryPolicy`, todo activado con `@EnableResilientMethods`; los tipos de retorno reactivos usan el retry de Reactor. **No incluye circuit breaker.** Funciona con proxies. [DOC] ([blog](https://spring.io/blog/2025/09/09/core-spring-resilience-features))
- **Lecciones:** la plataforma ya cubre el reintento y la limitación de concurrencia, así que una biblioteca nueva debe ofrecer más (corte de circuito, limitación de tasa, timeouts, composición, configuración, rendimiento). Su `maxAttempts` cuenta *reintentos*, no intentos totales, a diferencia de Spring Retry; tenemos que definir esto sin ambigüedad. [DOC]

### 2.6 Polly v8 (.NET), como fuente de ideas de diseño

- **Estado:** 8.8.0 (septiembre de 2026), activo; forma parte de `Microsoft.Extensions.Resilience`. [DOC] ([repo](https://github.com/App-vNext/Polly))
- **Diseño:** `ResiliencePipelineBuilder` compone estrategias (retry, circuit breaker, timeout, rate limiter, fallback, **hedging** y estrategias de **caos**); `ResiliencePipelineRegistry` con pipelines por clave y recarga dinámica; telemetría integrada. [DOC]
- **Rendimiento (copiar):** la v8 fue una reescritura centrada en las reservas de memoria. Para un pipeline de 5 estrategias (timeout+rate limiter+retry+CB+timeout): **v7 2,277 µs / 2824 B → v8 2,089 µs / 40 B**. Técnicas: pool de `ResilienceContext`, **lambdas estáticas que reciben el estado como argumento** (sin captura), `Outcome<T>` que transporta la excepción **sin lanzarla**, predicados con expresiones switch en lugar de listas de predicados y pipelines en caché. [BENCH][DOC] ([rendimiento](https://www.pollydocs.org/advanced/performance.html))
- **Lecciones:** demuestra que es posible reducir ~99 % las reservas de memoria de un pipeline completo. El tipo "resultado/outcome" por estrategia evita el control de flujo basado en excepciones.

### 2.7 Novedades y otros (2024–2026)

| Biblioteca | Notas |
|---|---|
| **Helidon 4 Fault Tolerance** | API programática SE (`Async`, `Bulkhead`, `CircuitBreaker`, `Fallback`, `Retry`, `Timeout`) pensada para hilos virtuales; anotaciones MP; **Helidon Declarative** (en incubación): `@Ft.Retry`, `@Ft.CircuitBreaker`, ..., procesadas por un **procesador de anotaciones que genera código en compilación "para evitar reflexión y manipulación de bytecode en tiempo de ejecución"**. [DOC] ([declarative](https://helidon.io/docs/v4/se/injection/declarative.html), [SE FT](https://helidon.io/docs/v4/se/fault-tolerance.html)) Es el antecedente más cercano a nuestro objetivo, aunque está ligado a Helidon Inject. |
| **Micronaut** | `@Retryable`, `@CircuitBreaker` (una variante de retry) y los interceptores Resilience4j-Micronaut; **AOP en tiempo de compilación** (sin proxies ni reflexión en ejecución). [DOC] ([javadoc](https://docs.micronaut.io/5.0.1/api/io/micronaut/retry/annotation/package-summary.html)) Ligado al contenedor DI de Micronaut. |
| **Netflix concurrency-limits** | Limitación de concurrencia adaptativa (al estilo del control de congestión TCP). Una idea diferenciadora fuerte para un modo "adaptativo" del bulkhead. [DOC] |
| **Spring Framework 7** | Ver §2.5. |
| **Resilience4j 2.4 / Hedge** | Poniéndose al día con los hilos virtuales (base JDK 21, `ReentrantLock` en lugar de `synchronized`), ventanas sin bloqueos opcionales, hedging. [DOC] |

---

## 3. Matriz comparativa de funcionalidades

Leyenda: ✅ sí · ⚠️ parcial / con matices · ❌ no · — no aplica.

| Capacidad | Resilience4j 2.4 | Failsafe 3.3 | SmallRye FT 6.x | Hystrix 1.5 | Sentinel 1.8 | Spring FW 7 | Polly 8 (.NET) | Helidon 4 FT |
|---|---|---|---|---|---|---|---|---|
| Circuit breaker | ✅ conteo+tiempo, llamadas lentas | ✅ conteo+tiempo | ✅ conteo (ventana) | ✅ buckets de tiempo | ✅ prop. lentas/errores/conteo | ❌ | ✅ | ✅ |
| Retry + backoff/jitter | ✅ | ✅ | ✅ (+Fibonacci, personalizado) | ❌ | ❌ | ✅ | ✅ | ✅ |
| Rate limiter | ✅ | ✅ suave/ráfagas | ✅ `@RateLimit` | ❌ | ✅ QPS, warm-up, parámetros calientes | ❌ | ✅ | ❌ |
| Bulkhead por semáforo | ✅ | ✅ | ✅ | ✅ | ✅ (nº de hilos) | ✅ `@ConcurrencyLimit` | ✅ (vía concurrencia del RateLimiter) | ✅ |
| Bulkhead por pool de hilos | ✅ | ❌ | ✅ (`@Asynchronous`) | ✅ (por defecto) | ❌ | ❌ | — | ⚠️ |
| Timeout | ⚠️ requiere Future/CS | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Fallback | ⚠️ decorador / nombre de método como cadena | ✅ | ✅ handler o método | ✅ | ✅ | ❌ | ✅ | ✅ |
| Hedging | ✅ (nuevo) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Límites adaptativos | ❌ (#201 abierto desde 2018) | ❌ | ❌ | ❌ | ⚠️ protección del sistema | ❌ | ❌ | ❌ |
| Anotaciones | ✅ declaradas, ❌ procesadas sin framework | ❌ | ✅ (solo CDI) | ⚠️ javanica + AspectJ | ✅ (AspectJ/CDI) | ✅ (proxies de Spring) | — | ✅ (MP/CDI o Helidon Declarative) |
| **Anotaciones en Java puro** | ❌ | ❌ | ❌ | ❌ | ⚠️ AspectJ manual | ❌ | — | ❌ |
| Procesamiento de anotaciones en compilación (sin reflexión) | ⚠️ solo vía Micronaut | — | ⚠️ compilación en Quarkus | ❌ | ❌ | ❌ | — | ✅ (en incubación) |
| Configuraciones con nombre + por defecto | ✅ | ❌ (código) | ✅ vía `@ApplyGuard` | ✅ (Archaius) | ✅ reglas | ❌ | ✅ registro | ✅ |
| Sobrescritura por método con configuración externa | ⚠️ por nombre de instancia | ❌ | ✅ clase/método/anotación/parámetro | ✅ | ✅ (fuentes de reglas) | ⚠️ placeholders | ✅ (recarga de opciones) | ✅ |
| Reconfiguración dinámica en ejecución | ⚠️ parcial (límites del RateLimiter; reemplazo en el registro) | ❌ | ⚠️ | ✅ | ✅ | ❌ | ✅ | ⚠️ |
| Listeners de eventos | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ telemetría | ✅ |
| Métricas (Micrometer, etc.) | ✅ | ❌ | ✅ (MP Metrics/Micrometer/OTel) | ✅ | ✅ propias | ⚠️ | ✅ (métricas .NET) | ✅ |
| Asíncrono (`CompletionStage`) | ✅ | ✅ | ✅ | ⚠️ Rx | ⚠️ | ✅ (reactivo) | ✅ (Task/ValueTask) | ✅ |
| Reactor / RxJava / Kotlin | ✅ / ✅ / ✅ | ❌ | Mutiny, Rx, Kotlin | Rx | ⚠️ adaptador Reactor | Reactor | — | ❌ |
| Compatible con hilos virtuales | ✅ desde 2.3/2.4 | ⚠️ `synchronized` (fija hilos en JDK < 24) | ✅ | ❌ | ⚠️ | ✅ | — | ✅ (diseñado para HV) |
| Camino caliente sin reservas de memoria | ❌ (272 B/op histórico en CB) | ❌ | ? | ❌ | ⚠️ | ? | ✅ casi (40 B / 5 estrategias) | ? |
| Cifras JMH publicadas | ⚠️ fichero antiguo en el repo | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Mantenido (2026) | ✅ | ⚠️ mantenimiento | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |

"?" = no investigado o desconocido; pendiente de medir.

---

## 4. Oportunidades: dónde puede superar a Resilience4j una nueva biblioteca

### 4.1 Rendimiento

1. **Camino caliente sin reservas de memoria en régimen estable** (sin consumidores de eventos, camino de éxito). En concreto: sin valores de retorno `Snapshot` (exponer métricas bajo demanda, no en cada registro), marcas de tiempo `long` primitivas (`System.nanoTime()` directamente, con una interfaz tipo `LongSupplier` para pruebas en lugar de `Function<Clock, Long>`), sin objetos de contexto por llamada en retry cuando el primer intento tiene éxito (contexto perezoso), y sin `Instant` en las comprobaciones de OPEN (guardar el plazo en `long` de nanos). Evidencia de que es posible: el prototipo de R4j #117 y los 40 B / 5 estrategias de Polly v8. [SRC][BENCH] **Objetivo a proponer al agente de rendimiento: 0 B/op en el camino de éxito de CB+Retry+Bulkhead, verificado con JMH `-prof gc`.**
2. **Métricas que toleren la contención por defecto**: contadores repartidos tipo `LongAdder` o CAS por bucket (al estilo `LeapArray` de Sentinel), en lugar de un único bloqueo (lo que R4j usa por defecto) o de una lista con CAS sobre una sola cola que reserva memoria en cada registro (LOCK_FREE de R4j). [SRC]
3. **Eventos que no cuesten nada si nadie escucha, y poco si alguien escucha**: comprobar antes de crear el evento en *todos* los caminos (R4j omite la comprobación en varios tipos de evento, incluido call-not-permitted, y cada evento captura `ZonedDateTime.now()`), y, si es posible, evitar objetos evento para los consumidores de métricas (contadores, no flujos de eventos). No adjuntar búferes a cada instancia por defecto (la integración de R4j con Spring Boot lo hace). [SRC]
4. **Resolverlo todo una sola vez por método**: nombres, configuraciones, destinos de fallback y adaptadores de tipo de retorno, ligados en compilación o en la primera llamada a un manejador final por método, de modo que por llamada solo queden permiso, invocación y registro. Compárese con el SpEL/regex/reflexión por llamada de R4j (§1.3). [SRC]
5. **Fallback sin reflexión**: llamadas directas generadas (o `MethodHandle`s ligados una vez) en lugar de `Method.invoke`, con la firma comprobada en compilación (R4j solo registra "No fallback method match found" en ejecución). [SRC]
6. **Pipelines monomórficos y fusionados**: fusionar las pilas comunes (p. ej., Retry(CB(Bulkhead(call)))) en un único método generado o especializado, en lugar de lambdas `Supplier` anidadas y megamórficas. [INF], pendiente de confirmar con JMH y logs del JIT.
7. **Rate limiter bajo contención**: evitar crear un `State` por intento CAS (empaquetar ciclo+permisos en un `long`, o usar un `AtomicLong` por ciclo). [SRC]/[INF]
8. **Excepciones como valores en el camino de rechazo**: excepciones de rechazo preasignadas y sin traza (`CallNotPermitted`, bulkhead lleno), o una API tipo `Outcome` para quien no quiera lanzar nada (idea de Polly). [DOC]/[INF]
9. **Nada de `synchronized` en caminos calientes**: Java 21 es nuestro mínimo y las correcciones de fijado de JEP 491 solo llegan en JDK 24. Failsafe es el ejemplo a evitar. [SRC][DOC]
10. **Publicar una suite JMH reproducible que compare R4j, Failsafe, SmallRye standalone y la nuestra** (1/4/16/64 hilos, hilos de plataforma y virtuales, `-prof gc`). No existe ninguna pública. [DOC, resultado negativo]

### 4.2 Ergonomía

1. **Anotaciones declarativas que funcionen en Java puro**, sin Spring, CDI ni AspectJ en ejecución. Hoy nadie lo ofrece salvo Helidon Declarative (ligado al contenedor y en incubación). Es nuestro diferenciador principal. (El mecanismo corresponde al agente de procesamiento de anotaciones; la generación en compilación al estilo Helidon/Micronaut es el antecedente más sólido.) [DOC]
2. **Configuración por capas al estilo MicroProfile**: los valores de la anotación como valores por defecto, luego un perfil con nombre (`@CircuitBreaker(config = "payments")`) y luego una sobrescritura externa por `class/method/policy/param`, con fuente de configuración enchufable (properties, variables de entorno, propiedades del sistema) y sin dependencia de ningún framework. [DOC]
3. **Políticas compartidas con nombre aplicadas por anotación** (`@ApplyGuard` de SmallRye) y **meta-anotaciones compuestas** (el #2405 de R4j sigue abierto). [DOC][ISSUE]
4. **Validación en compilación**: nombres de configuración desconocidos, firmas de fallback incorrectas, combinaciones de políticas incompatibles y umbrales inválidos como errores de compilación y no como avisos en ejecución. [INF]
5. **Un orden de políticas definido y documentado** (MicroProfile tiene uno; en R4j el orden de los aspectos se configura con propiedades `@Order` de Spring). [DOC]
6. **Semántica sin ambigüedad**: `maxAttempts` frente a `maxRetries` (Spring 7 cambió el significado), si se registran los `Error`, si se inspecciona la cadena de causas. [DOC]
7. **Timeouts reales para código síncrono** sobre hilos virtuales (el TimeLimiter de R4j necesita un `Future`). [SRC]
8. **Límite de concurrencia adaptativo opcional** (Netflix concurrency-limits) como modo del bulkhead: una funcionalidad que se pide en R4j desde 2018 (#201). [ISSUE][DOC]
9. **Ergonomía en pruebas**: un reloj inyectable que se respete en todas partes (LOCK_FREE de R4j lo ignora). [SRC]

### 4.3 Riesgos y cosas que no conviene prometer de más

- Resilience4j tiene un ecosistema enorme (starters de Spring Boot, Micrometer, Feign, Reactor, Kotlin). Empezamos sin nada de eso; centrarse solo en Java puro reduce el público. [INF]
- La mayoría de llamadas reales están limitadas por E/S (ms), así que ahorrar menos de un µs importa sobre todo en servicios con muchas QPS, en la protección de código limitado por CPU (el caso de streaming del #1513 de R4j), en sistemas sensibles a las reservas de memoria o de baja latencia, y a escala con hilos virtuales. El mensaje debería ser "sobrecarga casi nula, escala con los hilos" más "anotaciones sin framework", no solo velocidad bruta. [INF]
- El análisis de escape del JIT puede eliminar ya parte de las reservas que listamos (los puntos [INF]). Hay que medir antes de afirmarlo.

---

## 5. Recomendaciones para las siguientes fases

1. Adoptar como modelo de referencia la **semántica de anotaciones y la jerarquía de sobrescritura de configuración de MicroProfile FT** (adaptando los nombres), y añadir políticas con nombre al estilo SmallRye y composición mediante meta-anotaciones.
2. Usar **código generado en compilación** para tratar las anotaciones (antecedentes: Helidon/Micronaut); la decisión final corresponde a la investigación sobre el mecanismo de anotaciones.
3. Hacer **explícito y medible el contrato de rendimiento**: 0 B/op en caminos de éxito, sin bloqueos en caminos calientes, métricas con contadores repartidos, eventos creados de forma perezosa, y una suite JMH comparativa publicada desde el primer día.
4. Limitar la v1 a **CircuitBreaker, Retry, Timeout, Bulkhead (semáforo), RateLimiter y Fallback**; considerar el límite adaptativo y el hedging como diferenciadores de la v2; descartar Cache y el bulkhead por pool de hilos (los hilos virtuales le restan relevancia).

## Fuentes

- Resilience4j: [repo](https://github.com/resilience4j/resilience4j), [notas de versión](https://github.com/resilience4j/resilience4j/blob/master/RELEASENOTES.adoc), [v2.4.0](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0), issues [#117](https://github.com/resilience4j/resilience4j/issues/117), [#201](https://github.com/resilience4j/resilience4j/issues/201), [#1377](https://github.com/resilience4j/resilience4j/issues/1377), [#1391](https://github.com/resilience4j/resilience4j/issues/1391), [#1513](https://github.com/resilience4j/resilience4j/issues/1513), [#2023](https://github.com/resilience4j/resilience4j/issues/2023), [#2151](https://github.com/resilience4j/resilience4j/issues/2151), [#2224](https://github.com/resilience4j/resilience4j/issues/2224), [#2405](https://github.com/resilience4j/resilience4j/issues/2405), PR [#2241](https://github.com/resilience4j/resilience4j/pull/2241), [fichero de resultados JMH](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/jmh/resources/resultsWithGCProfiling.txt)
- Failsafe: [repo](https://github.com/failsafe-lib/failsafe), [InfoQ sobre 3.x](https://www.infoq.com/news/2022/02/failsafe-resilience-3)
- MicroProfile FT 4.1: [especificación](https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html); SmallRye FT: [repo](https://github.com/smallrye/smallrye-fault-tolerance), [API programática](https://smallrye.io/docs/smallrye-fault-tolerance/6.9.3/reference/programmatic-api.html), [reusable / @ApplyGuard](https://smallrye.io/docs/smallrye-fault-tolerance/6.8.0/reference/reusable.html)
- Hystrix: [repo/README](https://github.com/Netflix/Hystrix); Netflix [concurrency-limits](https://github.com/Netflix/concurrency-limits)
- Sentinel: [repo](https://github.com/alibaba/Sentinel)
- Spring: [Spring Retry (archivado)](https://github.com/spring-projects/spring-retry), [blog de resiliencia de Spring Framework 7](https://spring.io/blog/2025/09/09/core-spring-resilience-features), [referencia](https://docs.spring.io/spring-framework/reference/core/resilience.html)
- Polly: [repo](https://github.com/App-vNext/Polly), [rendimiento](https://www.pollydocs.org/advanced/performance.html)
- Helidon: [Declarative](https://helidon.io/docs/v4/se/injection/declarative.html), [SE Fault Tolerance](https://helidon.io/docs/v4/se/fault-tolerance.html); Micronaut: [anotaciones de retry](https://docs.micronaut.io/5.0.1/api/io/micronaut/retry/annotation/package-summary.html)
- JDK: [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- Académico: [artículo de SBRC, Polly frente a Resilience4j](https://sol.sbc.org.br/index.php/sbrc/article/download/21194/21019)
