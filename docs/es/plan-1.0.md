# Plan hacia 1.0

Lo que falta antes de publicar 1.0.0, según una revisión de 0.13.0 hecha por cuatro revisores independientes, y lo que espera hasta después.

Esta página es el plan de trabajo: cada tarea dice qué cambiar, dónde y cómo comprobarlo, para poder retomar el trabajo desde aquí sin más contexto. Marca una tarea como hecha (`[x]`) en el mismo pull request que la hace, y añade su número.

## Estado

- Versiones en Maven Central: 0.13.0, y los release candidates 1.0.0-RC1, 1.0.0-RC2 y 1.0.0-RC3. `main` es 1.0.0-SNAPSHOT.
- El plan de rendimiento de las corridas en AWS está terminado (pull requests #53–#59, ver [benchmarks](benchmarks.md)).
- Los títulos del Javadoc llevan el nombre del artefacto (#60); javadoc.io se sincronizó para los 11 módulos.
- Las fases 1 a 3 están terminadas (#62 a #65).
- Una segunda revisión de los mismos cuatro revisores (2026-11-02, después de #65): el diseñador de APIs, el revisor de rendimiento y quien empieza dicen que está listo para 1.0; quien evalúa adoptarla pide primero una 1.0.0-RC1, y tres cosas antes de 1.0 (un fallo visible si falta el procesador, una corrida de jcstress en ARM, una política de seguridad). Sus arreglos pequeños de documentación están en la fase 4.
- 1.0.0-RC1 está en Maven Central (2026-11-02). `main` es 1.0.0-SNAPSHOT, con la base de API en 1.0.0-RC1, así que la API queda congelada: un cambio incompatible frente a la release candidate hace fallar el build.
- La rama principal de SonarCloud seguía llamándose `master`, así que los análisis de `main` nunca llegaban al resumen del proyecto. Ahora es `main`; sus 21 issues de fiabilidad se revisaron (#68), y solo se analiza la librería publicada (#69).
- 1.0.0-RC2 (2026-11-04) hace fallar el arranque o el build cuando el procesador no corrió.
- Una [tercera revisión](#third-review) de RC2 (2026-11-05): tres revisores dicen sacar 1.0.0; sus arreglos cambian dos comportamientos, así que antes viene una 1.0.0-RC3.
- 1.0.0-RC3 (2026-11-05) trae los arreglos de la tercera revisión. Los snippets de instalación ahora la muestran (decisión del dueño).
- `main` solo acepta cambios por pull requests con los checks en verde (los administradores lo saltan para los commits de release), y cada release candidate tiene su release de GitHub; 1.0.0-RC3 es *Latest*, como lo muestran los snippets de instalación (2026-11-06).
- Siguiente: 1.0.0.

## La revisión

Cuatro revisores leyeron el repositorio el 2026-11-02, cada uno desde un punto de vista. Ninguno ejecutó el build. Sus hallazgos son afirmaciones por comprobar, no hechos: cada tarea de abajo empieza confirmando el hallazgo.

| Revisor | Veredicto |
|---|---|
| Un usuario de Resilience4j que decide si adoptarla | Todavía no para producción en general; sí para un piloto en un servicio. Ingeniería sólida; el riesgo es la edad del proyecto y un solo mantenedor. |
| Un diseñador de APIs | Casi lista para congelarse; cinco arreglos baratos ahora que serían permanentes después. |
| Un escéptico de rendimiento y concurrencia | Ingeniería cuidadosa, ningún bug confirmado; los titulares son el mejor caso; x86 nunca se midió; cuatro sospechas. |
| Alguien nuevo en librerías de resiliencia | Una llamada protegida en unos 15 minutos; algunas frases viejas o contradictorias; algunas páginas demasiado internas. |

Lo que valoraron, para conservarlo mientras se cambia lo demás:

- Errores en compilación con códigos `R4C-…` estables, cada uno documentado con un ejemplo incorrecto y su arreglo, donde Resilience4j resuelve `fallbackMethod` por reflexión en ejecución.
- Un coste por llamada casi nulo, medido abiertamente: se muestran empates y regresiones, los ajustes de equidad están en el Javadoc de cada benchmark, Resilience4j recibe excepciones sin stack trace y no hay código muerto, y la barrera de asignaciones corre en CI.
- Código generado que es Java plano: los stack traces pasan por `X$Resilient`, no por cadenas de proxies, y las llamadas a `this` se interceptan en el estilo de subclase.
- Una guía de migración seria (tablas de configuración, valores por defecto distintos, cuándo quedarse en Resilience4j) y una promesa de compatibilidad explícita respaldada por japicmp y la regla del código generado.
- Una API que puede crecer: las configs son clases final con builders, `ResilienceSnapshot` es una clase, los handles y `FallbackContext` los implementa la librería, los rechazos comparten una base sealed sin stack trace, los enums documentan que crecen, `@Internal`/`@Experimental` se hacen cumplir, `PipelineCall` conserva una excepción checked precisa.
- Código sin locks correcto, hasta donde lo leyó el revisor: el protocolo `quiet` del breaker, el CAS acquire `RUNNING → IDLE` del timeout, la liberación y relectura de `BulkheadSemaphore`, el orden `count++ → offer → drain` de los waiters, la marca de cancelación de `LinkedFuture`, y el presupuesto de retry que se equivoca hacia menos reintentos.
- El quickstart (un programa probado, su salida, "What just happened") y la página de instalación (BOM en `annotationProcessor`, Maven viejo, descubrimiento en JDK 23, IDEs, orden con Lombok). Los árboles en/es coinciden y el español se lee con naturalidad.

## Fase 1: API, antes de congelarla

Un pull request. Todo esto cambia la API pública, lo que es gratis antes de 1.0 e imposible después. Cada cambio lleva una línea en el changelog (en/es) y, si toca el código generado, una comprobación de que `examples` sigue pasando.

- [x] **1.1 Parámetros de tipo que admiten null.** Con `@NullMarked`, `Pipeline<T>`, `PipelineCall<T, X>`, `FallbackHandler<T>` y `Classifier<T>` limitan `T` a no nulo, aunque `PipelineRunnable extends PipelineCall<@Nullable Void, X>` ya rompe ese límite y los fallbacks de métodos `void` devuelven null. Declarar `<T extends @Nullable Object>` en los cuatro, y en lo demás que reciba el tipo del resultado del usuario (`AsyncCall`, `Recovery`, `PipelineRunnable`: comprobar). Comprobación: `./gradlew build`; los tests del procesador compilan el código generado con `-Werror`.
  - **Resultado:** (#62) Hecho: los cuatro tipos, más `AsyncCall`, `Recovery` y los `executeAsync` de las políticas; los tipos `Mono`/`Flux` de reactor siguen sin admitir null (Reactor prohíbe elementos null).
- [x] **1.2 Records que crecen.** `reference/compatibility.md` dice que los records de eventos y snapshots pueden recibir componentes, pero añadir uno elimina el constructor canónico (incompatible en binario, japicmp falla). Decidir: o construir eventos y snapshots no está soportado (decirlo en `compatibility.md` y en el Javadoc de los records), o cada componente nuevo conserva el constructor viejo (decirlo, y añadir la regla a CLAUDE.md). La segunda es más amable con los tests de listeners de los usuarios; preferirla.
  - **Resultado:** (#62) Hecho: se conserva el constructor viejo (`compatibility.md`, `ResilienceEvent`, CLAUDE.md).
- [x] **1.3 Tipos sealed que crecen.** Añadir `PolicyConfig` y `CallRejectedException` (sus `permits` crecen con cada política nueva) a la lista de "puede crecer" de `compatibility.md` y a su Javadoc, como con los eventos: un `switch` exhaustivo sobre ellos necesita una rama por defecto.
  - **Resultado:** (#62) Hecho (`compatibility.md`, Javadoc de las dos clases).
- [x] **1.4 Un nombre para el evento de rechazo.** `CircuitBreakerEvent.CallNotPermitted` → `Rejected`, como en los eventos del bulkhead, el rate limiter y el adaptive limit. La excepción conserva su nombre. Alinear también el enum de la anotación `LimitAlgorithm` con `AdaptiveLimitConfig.Algorithm` (los otros enums, `Backoff`, `Jitter`, `WindowType`, coinciden con el de su config). Actualizar los módulos de métricas, la tabla de eventos de `guides/observability.md` (en/es) y los snippets de `examples/src/test/.../docs/`.
  - **Resultado:** (#62) Hecho para el evento. `LimitAlgorithm` se mantiene: ver [diseño](design.md#api-decisions).
- [x] **1.5 Constantes que se copian.** `HedgeConfig.MIN_ATTEMPTS`/`MAX_ATTEMPTS` son constantes de compilación: se copian en los class files de quien las usa, así que subir el tope de 10 no llegaría al código compilado antes. Hacerlas privadas (los límites están documentados) o no constantes.
  - **Resultado:** (#62) Hecho: privadas.
- [x] **1.6 Renombres pequeños.** `ResilienceRegistry.Builder.listen(String instance, …)` → `id`. `Resilient.create(type, registry, Object... args)`: los argumentos pueden ser null, así que `@Nullable Object...`. El formato del id derivado es `<interface>#…` en `ResilienceRegistry` y `ResilienceEvent` pero `<type>#…` en los handles: usar `<type>` (clases anotadas).
  - **Resultado:** (#62) Hecho.
- [x] **1.7 Decidir, no necesariamente cambiar.** Escribir cada decisión en `design.md`:
  - `@Timeout` no tiene `name` (las otras seis sí). Añadirlo después es aditivo.
  - Los parámetros de lista de los builders (`retryOn(List<Class<? extends Throwable>>)`) rechazan `List<Class<? extends Exception>>`; `Collection<? extends Class<? extends Throwable>>` lo aceptaría, pero solo antes de 1.0.
  - Orden de argumentos: `Resilient.of(type, delegate, registry)`, `create(type, registry, args…)`, `Pipeline.builder(registry, id)`.
  - `ResilienceHealthIndicator` y `ResilienceEndpoint` de Micronaut tienen constructores públicos: marcarlos `@Internal`.
  - Los setters de ajuste de `AdaptiveLimitConfig.Builder` tienen un Javadoc de una línea sin descripción principal: escribirla.
  - Los snapshots tienen formas distintas: los de retry, timeout y hedge incluyen `config`; los de bulkhead, rate limiter y adaptive limit copian algunos campos (`maxConcurrent`, `permits`, `minLimit`/`maxLimit`); el del circuit breaker no tiene ninguno de los dos. Elegir una forma, o escribir por qué difieren.
  - `CircuitBreakerSnapshot` usa `-1` para "todavía no hay tasa": mantener el centinela (documentado) o prever un accesor `hasRates()`.
  - `Pipeline.Builder.retry(String)` junto a `retry(Consumer)` (y los mismos pares en cada política): `retry(null)` es ambiguo. Aceptarlo o renombrar uno del par.
  - `LockedOverride.policy` es un `String` con un conjunto cerrado de valores: un enum sería más seguro; el string coincide con las claves de configuración.
  - Dos enums llamados `ExhaustedResult` (`Pipeline.ExhaustedResult` y el de la anotación, que además tiene `INHERIT`): importar los dos es incómodo. Aceptarlo o renombrar.
  - **Resultado:** (#62) Hecho: cada punto decidido en [diseño](design.md#api-decisions); los constructores de Micronaut son `@Internal` y los 51 setters de los builders tienen descripción principal.

Comprobación de toda la fase: `./gradlew build :stress:compileJava`, `./gradlew :quarkus:it:check`, `./gradlew :core:apiCompatibility` (el informe lista los cambios buscados y nada más).

## Fase 2: documentación

Un pull request, en y es juntos. Casi todo son cifras viejas o frases que contradicen el código.

- [x] **2.1 Cifras de rendimiento viejas.**
  - Las portadas (`docs/{en,es}/README.md`) rotulan su gráfico "resilience4cata 0.1.0" y muestran el bulkhead en 20.5 ns; el `README.md` raíz también. Actual: 17.3 ns (`benchmarks.md#release-counters`).
  - `performance.md`: la fila del bulkhead en el titular (20.5 / 264 ns, "empate") es anterior a 17.3 / 195.5 ns; "acquire/release … 23.2 → 9.3 ns" contradice la devolución con `getAndAdd` volátil; la tabla principal se midió con 0.1.0.
  - `faq.md` "How fast is it?" pone 1.9 ns (4 hilos) junto a los 89.2 ns de Resilience4j (1 hilo). Usar una sola fila.
  - El `README.md` raíz cita el breaker con cuatro hilos (832 ns para Resilience4j) donde la FAQ usa un hilo (89 ns): el README, las portadas, la FAQ y `performance.md` deben citar las mismas filas.
  - Decidir si se vuelve a correr la tabla del titular en AWS con el código de 1.0 (`infra/bench.sh`), para que cada cifra citada tenga una sola fuente.
  - **Resultado:** (#63) Hecho: las filas del bulkhead vienen de [contadores al devolver](benchmarks.md#release-counters) (17.3 / 195.5 ns) en `performance.md`, las portadas (también la leyenda y la fecha) y el README, que ahora cita además el breaker con un thread. La frase de la FAQ era correcta: el breaker cuesta 1.9 ns con uno y con cuatro threads. Volver a correr la tabla del titular con el código de 1.0 queda para la corrida en AWS de 2.5.
- [x] **2.2 Titulares honestos.**
  - Los 1.9 ns del breaker con cuatro hilos solo valen mientras la ventana no tiene fallos (el camino `quiet`). Desde alrededor de 1 % de fallos cada llamada escribe el cursor compartido: "mixed", 274 frente a 910 ns. Decirlo junto al titular.
  - Decir que Resilience4j actualiza sus métricas internas en cada llamada y resilience4cata no mantiene contadores por llamada, lo que explica parte de la diferencia.
  - La fila del timeout (22 frente a 8842 ns) compara un watchdog con un traspaso a un executor: sacarla del titular en negrita o matizarla.
  - "One clock-read pair per call, whatever the number of policies" es falso para pilas (el rate limiter, el adaptive limit, un breaker temporizado y un retry con `maxDuration` leen el reloj cada uno). Corregirlo.
  - Los benchmarks miden el tiempo medio en un bucle cerrado, lo que oculta las colas: decirlo donde se citan el rate limiter y el bulkhead con contención (los que pierden se aparcan), hasta que existan las corridas de percentiles.
  - x86 nunca se ha medido: decirlo en `performance.md` ("x86-64 pending" ya está; dejarlo visible junto al titular).
  - La ventana por conteo del circuit breaker es aproximada con contención (una lectura opaca vieja de `quiet` puede no registrar un éxito justo después de un fallo; saltarse el slot con el mismo resultado puede reordenar escrituras cuando la ventana da la vuelta). Escribirlo en `policies/circuit-breaker.md` y `architecture.md`.
  - **Resultado:** (#63) Hecho: el camino quiet del breaker, los contadores por llamada del retry de Resilience4j (comprobado en su jar 2.4.0: campos `LongAdder` en `RetryImpl`; no se afirma para su breaker), medias en bucle cerrado, solo ARM, la fila de lecturas del reloj y la ventana por conteo aproximada (las dos carreras comprobadas en el código). La fila del timeout ya estaba matizada.
- [x] **2.5 Benchmarks detrás de los titulares honestos.** Añadir un caso del breaker con una tasa de fallos realista (0.1 % y 1 %) junto a "success" y "mixed", con uno y cuatro hilos, con Resilience4j a la misma tasa. Correrlo en AWS y citarlo en 2.2.
  - **Resultado:** (#64) Hecho: `CircuitBreakerFailureRateBenchmark` (0 B, en el gate de allocations), corrido en 8 máquinas Graviton3 con `-f 6` junto a los casos sin fallos y de una de cada diez ([tasas de fallos realistas](benchmarks.md#failure-rates)). Con cuatro threads: 11.2 ns al 0.1 %, 65.4 ns al 1 %, frente a unos 1 000 ns de Resilience4j. Citado en `performance.md`. El resto de la tabla del titular (medida con 0.1.0) no se volvió a correr: conserva su fuente y su fecha, y las filas medidas después (bulkhead, breaker) citan su propia corrida.
- [x] **2.3 Contradicciones.**
  - `start/how-it-works.md`, "Policy order": "The order is not configurable yet" (falso desde `@PolicyOrder`).
  - Las tiras de datos de las páginas de políticas dicen "x of 7"; hay 8 políticas.
  - `README.md` raíz "names can still change before 1.0" frente a la FAQ "no more API changes planned". Quedarse con la de la FAQ.
  - `policies/retry.md` "Small print" cita R4C-ATR-006 para `enabled`: está retirado.
  - La tabla de prefijos de `errors.md` no tiene fila `R4C-ORD`.
  - `guides/troubleshooting.md` [R4C-RT-004]: reescribir con la regla actual (core puede ser más nuevo que el procesador; un core más viejo es RT-021).
  - Tabla de artefactos de `start/installation.md`: a la fila de annotations le faltan `@Hedge`, `@AdaptiveLimit`, `@PolicyOrder`.
  - El código generado de ejemplo de `policies/circuit-breaker.md` muestra `50.0f`; el código generado emite `50.0`. Regenerarlo desde `examples/build/generated/...`.
  - Las etiquetas de la portada dicen "Sync and CompletionStage": añadir `Mono`/`Flux`.
  - El roadmap de la FAQ dice "YAML configuration: v1" aunque Spring, Micronaut y Quarkus ya leen YAML: aclarar qué significa (archivos YAML en Java puro) o quitarlo.
  - **Resultado:** (#63) Hecho. "x de 7" era correcto (Retry y Hedge comparten una capa): ahora dice "capa x de 7", y los tipos de retorno incluyen `Mono` y `Flux`.
- [x] **2.4 El camino de alguien nuevo.**
  - Paso 6 del quickstart, "See the breaker open": bajar `minimumCalls`, mostrar `OPEN`, la `CallNotPermittedException` y la respuesta del fallback (snippet probado en `examples`).
  - Una guía "Retry and circuit breaker for an HTTP call": qué excepciones reintentar o abortar, un `@Timeout` dentro, un `name` para el breaker, números sensatos, idempotencia. Página nueva en los dos sidebars.
  - Un glosario corto, como definiciones en `policies/README.md` o en su propia página: jitter y "full jitter", half-open y sondas, id de instancia e id derivado, registry, bulkhead, hedge, excepción sin stack trace, código en línea recta, y la comprobación de ABI (`Abi.require` en los ejemplos generados).
  - Páginas de políticas: mover el bloque de código generado debajo de "How it behaves" (o plegarlo).
  - R4C-RT-003: el mensaje, `errors.md` y el "Verify that it works" de installation muestran solo Gradle; añadir Maven.
  - `guides/spring.md`: la pestaña de Maven añade `spring-boot-configuration-processor` sin versión ni motivo; explicarlo o quitarlo. Mover el detalle para mantenedores (versiones probadas en 4.1.0 y 4.1.2, internos de AOT, la comprobación de transacciones que lee el bytecode) a una sección final "Under the hood". La sección de transacciones (R4C-SPR-004) va antes de "Configuration": poner "Configuration" primero.
  - Sidebar: rotular el grupo "Proyecto" como para contribuidores.
  - `errors.md` tiene unas 3200 líneas en una página: añadir una pista arriba (buscar por el código, la tabla de prefijos enlaza cada área), o dividirla por área si el generador del sitio y `ErrorCodeTest` lo permiten.
  - Maven: `annotationProcessorPaths` sustituye el descubrimiento de todos los demás procesadores (Lombok, MapStruct), así que añadir el nuestro puede quitar los suyos sin aviso. Comprobar que `start/installation.md` lo dice claramente junto al snippet de Maven, con el arreglo (listarlos todos).
  - Valores por defecto que sorprenden a quien viene de Resilience4j: una lista corta en `migrate/from-resilience4j.md` (enlazada desde la FAQ). Candidatos que nombró el revisor: un retry espera de forma exponencial con jitter desde 200 ms por defecto; el rate limiter rechaza al instante (`maxWait = 0`); con clases anotadas `@Transactional` queda fuera de las políticas, y la comprobación de arranque lo rechaza, así que hay que reestructurar el código. También la afirmación del revisor de que un `Error` cuenta como éxito para el breaker: comprobarla primero en `CircuitBreakerPolicy` y en las capas generadas; si es cierta, documentarla (y decidir en la fase 1 si es el comportamiento correcto); si no, no hay nada que hacer.
  - Nombres de métricas: quien migra desde Resilience4j tiene que rehacer dashboards y alertas (`r4c.*`). Añadir a la página de migración una tabla de los nombres de métricas de Resilience4j a los nuestros.
  - Lo que Resilience4j tiene y nosotros no, en un solo sitio de la página de migración (parte ya está en "when to stay"): bulkhead con thread pool, cache, RxJava, Kotlin, reconfiguración en caliente (por decisión), una transición automática de open a half-open, endpoints de eventos, métricas de éxito y latencia, y que los hedges no llevan el contexto de quien llama a los intentos lanzados. Comprobar cada uno contra el código antes de escribirlo.
  - **Resultado:** (#63) Hecho, con tres cosas que se mantienen a propósito: los bloques de código generado siguen junto a los snippets (la vista lado a lado es la razón del componente `compare`, y su panel ya tiene scroll); los detalles de la guía de Spring siguen donde están (el orden de los post-processors, AOT y el límite en imágenes nativas son para usuarios; solo se simplificó la línea de versiones); el párrafo de transacciones sigue en "Clases anotadas", donde aplica. La afirmación del `Error` es cierta (`recordOn` vale `Exception` por defecto), ya estaba documentada, y ahora se enlaza desde la FAQ.

Comprobación: `./gradlew :examples:test --tests '*DocsTest*' :site:test`, y `./gradlew :site:serve` para mirar las páginas cambiadas. 2.5 es un cambio de benchmark (su propio pull request, con una corrida en AWS).

## Fase 3: bugs sospechados

Un pull request por bug confirmado. Para cada uno: primero escribir un test que lo reproduzca (seam determinista o jcstress, nunca tiempos), verlo fallar, luego arreglar, luego hacer el mutation check del test. Si no se reproduce, escribir aquí por qué y cerrar la tarea.

- [x] **3.1 Bulkhead: un slot libre con un waiter async en cola.** `BulkheadPolicy.exit` (cerca de la línea 323): cuando `hasQueuedThreads()` y `yieldToSync()`, libera y vuelve sin despachar a los waiters async. Si el hilo síncrono al que cedió luego vence, es interrumpido o pierde contra uno que se cuela, el slot queda libre mientras un waiter async espera, hasta la siguiente llamada; sin tráfico ese waiter se rechaza al llegar a `maxWait`. Test con `enqueueForTest`/`drainForTest` y el semáforo. Arreglo posible: después de ceder, despachar si el semáforo sigue teniendo un permiso y ningún hilo en cola lo tomó. Hoy jcstress no cubre el traspaso al ceder a un hilo síncrono: añadir un caso a `BulkheadWaitersStress` (o una clase nueva) y hacerle el mutation check.
  - **Resultado:** (#65) Confirmado y arreglado. El caso jcstress nuevo `BulkheadWaitersStress.YieldToSyncThatGivesUp` vio el slot libre con un waiter en cola una vez en 1,45 millones de ejecuciones. Ahora una espera síncrona que termina sin slot despacha a los waiters async, solo en el camino de rechazo. Con el arreglo pasa en modo default (unos 2,3 millones de ejecuciones); el mutation check es la corrida anterior al arreglo.
- [x] **3.2 Adaptive limit: muestras inconsistentes.** `AdaptiveLimitPolicy` (cerca de la línea 281): `SUM` y `COUNT` de una franja son escrituras con pérdida separadas, así que una carrera puede conservar una y perder la otra; una franja que retrocede (un hilo expulsado entre la lectura y la escritura) puede dejar `sum - usedSum` negativo, que se recorta a un RTT de 1 ns y empuja Gradient2 hacia arriba. Arreglo mínimo: un delta negativo o cero salta la ventana en vez de volverse 1 ns; mejor, escribir suma y conteo de forma consistente (una escritura empaquetada, o un número de secuencia). Test con el constructor `(id, config, env, stripes)` y los seams; añadir la carrera de franjas a `AdaptiveLimitStress`, que hoy no la cubre.
  - **Resultado:** (#65) Confirmado y arreglado. `aStripeThatWentBackIsNotReadAsAOneNanosecondWindow` (seam `loseLatencyForTest`) leía la ventana como 1 ns antes del arreglo. Ahora un conteo o una suma por debajo de los usados descarta la ventana y resincroniza. No se añadió a jcstress: no puede pausar un thread entre la lectura y la escritura con pérdida.
- [x] **3.3 Rate limiter: un `now` viejo después de la espera.** `RateLimiterPolicy` (cerca de la línea 134): `now` se lee una vez antes del bucle; tras perder el CAS y la espera de 1 ns (decenas de µs en Linux), la espera se calcula desde un tiempo viejo: quien llama duerme de más, o con `maxWait = 0` puede ser rechazado cuando ya le tocaba un permiso. Comprobar si releer el reloj tras la espera cambia el benchmark (medir con uno y cuatro hilos). La misma espera da una cola larga a quienes pierden (unos 50 µs de holgura del timer en Linux) que los benchmarks de tiempo medio no muestran: medirla con las corridas de percentiles (después de 1.0, o aquí si 3.3 cambia el bucle).
  - **Resultado:** (#65) Confirmado leyendo el código; no se puede reproducir con un test, porque nada puede ejecutarse entre la lectura de `tat` y el compare-and-set. Se arregló volviendo a leer el reloj después de aparcar, solo en el camino de contención. Medido en AWS: con cuatro threads, 183,6 contra 187,0 ns, así que no es más lento ([arreglos de la revisión](benchmarks.md#review-fixes)). La cola queda para las corridas de percentiles después de 1.0.
- [x] **3.4 Timeout: se borra la interrupción del propio usuario.** `TimeoutPolicy` (cerca de la línea 218): después de que el watchdog interrumpe, `Thread.interrupted()` borra también una interrupción que quien llama ya había puesto. Decidir la semántica (¿conservar una interrupción previa, volviendo a poner la marca si estaba antes de la llamada?) y probarla con `Watchdog(0)`.
  - **Resultado:** (#65) Confirmado y arreglado. `anInterruptTheCallerAlreadyHadSurvivesTheTimeout` fallaba antes del arreglo. El watchdog anota `callerInterrupted` antes de su release de `TIMED_OUT`, y quien llama conserva entonces esa interrupción. Pasan las jcstress del timeout y del watchdog.
- [x] **3.5 Presupuesto de retry: pérdida sin límite.** `RetryBudget` (cerca de la línea 125): un hilo expulsado entre su lectura opaca y su escritura puede borrar llamadas de otros hilos en su franja; las franjas se comparten cuando hay más hilos que 2 × CPUs. La dirección es segura (menos reintentos). Probablemente documentar, no arreglar: escribir el límite (o que no lo hay) en el Javadoc de la clase y en `architecture.md`.
  - **Resultado:** (#65) Documentado, no arreglado: el Javadoc de RetryBudget y `architecture.md` dicen ahora que la pérdida no tiene un límite fijo, solo baja el presupuesto y solo ocurre cuando se comparten franjas.
- [x] **3.6 Timeout con hilos virtuales.** La primera llamada temporizada de cada hilo nuevo registra un `ThreadScopes` en una cola compartida y el watchdog recorre cada hilo registrado en cada tick. Con un hilo virtual por petición eso es una asignación por petición y un recorrido proporcional a los hilos vivos. Medirlo (un caso JMH que arranque un hilo virtual por llamada); luego decidir si arreglar o documentar.
  - **Resultado:** (#65) Medido con el `VirtualThreadTimeoutBenchmark` nuevo: unos 270 B por thread nuevo; sin costo de tiempo con un thread; +0,8 µs (+8,6 %) sobre un arranque de thread de 9,3 µs con cuatro threads arrancando a la vez. Documentado en la página del timeout y en [benchmarks](benchmarks.md#review-fixes), sin cambios: solo paga la primera llamada con timeout de cada thread.

## Fase 4: release

- [x] (#66) Los arreglos de documentación de la segunda revisión: la primera llamada con timeout de un thread nuevo asigna memoria (`performance.md`), el rate limiter (no el bulkhead) aparca, las funcionalidades que leen el reloj, la frase del paso 6 del inicio rápido y un enlace a la guía HTTP, un test para la fila del timeout de la guía, el enlace de la FAQ a los valores por defecto, la media aproximada del adaptive limit, este estado.
- [x] (#66) `SECURITY.md`: versiones soportadas y cómo reportar una vulnerabilidad en privado.
- [x] Una corrida completa de jcstress en ARM (AWS Graviton), ya que el CI solo la corre en x86.
  - **Resultado:** 2026-11-02, una `c7g.4xlarge` (16 núcleos Graviton3), `-m default`, unos 40 minutos: 65 tests, 2598 configuraciones, ninguna falló, sin errores. Un test sale "interesting", como se esperaba: `RetryBudgetStress.CallsAreNeverOvercounted` perdió llamadas en el 10,9 % de sus muestras y nunca contó una de más. Es la pérdida documentada en la tarea 3.5. Después escribimos que en ARM se ve mucho más que en x86; las corridas posteriores de la CI en ambas (runners de 4 núcleos, modo quick) vieron tasas parecidas, 5,5–8,6 % en ARM y 6,3–18,6 % en x86, ya que perder una llamada no requiere ningún reordenamiento ([blog](blog/jcstress-on-arm-and-x86.md)). La corrida usó una configuración de Terraform desechable fuera del repositorio; correrla de forma periódica está en "Después de 1.0".
- [x] Decidir si la falta del procesador debe hacer fallar el arranque en Spring, Micronaut y Quarkus antes de 1.0 (decisión del dueño; quien evalúa adoptarla argumenta que añadirlo después cambia el comportamiento dentro de la versión mayor).
  - **Resultado:** sí, antes de 1.0.0, en una 1.0.0-RC2. Un bean cuya clase (o una superclase), o una interfaz que implementa, declara políticas pero no tiene clase generada ahora hace fallar:
    - el arranque de Spring con R4C-SPR-006;
    - la creación de un bean de una clase anotada en Micronaut con R4C-MN-007 (una interfaz anotada solo se envuelve con `@ResilientBean`, que ya falla con R4C-RT-003);
    - el build de Quarkus con R4C-QK-008.

    Cada uno tiene un interruptor para desactivarlo (`allow-missing-processor`), y los beans excluidos no se comprueban. Spring y Micronaut leen los archivos de clase (`PolicyAnnotations`, una copia en cada uno, que se mantienen iguales), así que la comprobación no se ejecuta en una imagen nativa; Quarkus usa el índice Jandex. Los tests cargan los fixtures anotados sin su `$Resilient`: con un class loader que lo esconde (Spring, Micronaut) o con una raíz de aplicación sin él (Quarkus). Cada comprobación tiene su mutation check.
- [x] Decidir si hay release candidate: `1.0.0-RC1` en Central, una o dos semanas de uso, luego 1.0.0. (Decisión del dueño.)
  - **Resultado:** sí. Los snippets de instalación siguieron en 0.13.0 hasta RC3; desde entonces muestran la última release candidate (decisiones del dueño).
- [x] Publicar 1.0.0-RC1 siguiendo [publicar una versión](releasing.md).
  - **Resultado:** publicada el 2026-11-02 (commit `release 1.0.0-RC1`, tag `v1.0.0-RC1`, corrida 41553226849 de `release.yml`, publicada en el portal de Central por el dueño).
    - `apiCompatibility` frente a 0.13.0 mostró solo los cambios buscados: el evento renombrado, los dos constructores de Micronaut y `FluxCall$Attempt` (solo de código fuente).
    - La comprobación del código generado (`-Pr4c.generatedBy=0.13.0`) no aplica: de 0.x a 1.0 se cambia de versión mayor, y los tests ya usan `CircuitBreakerEvent.Rejected`.
    - Las cuatro comparaciones de la portada se midieron otra vez sobre el código de la release candidate ([benchmarks](benchmarks.md#release-candidate), #67).
- [x] Después de la release candidate: `version=1.0.0-SNAPSHOT`, `r4c.apiBaseline=1.0.0-RC1`, javadoc.io sincronizado y generado para 1.0.0-RC1 en los 11 módulos.
- [ ] Publicar 1.0.0 tras una o dos semanas de uso, salvo que algo tenga que cambiar:
  - versión, tag `v1.0.0`, `release.yml`, Publish en el portal;
  - después, los snippets de instalación a 1.0.0 (no en el blog), `r4c.apiBaseline=1.0.0`, javadoc.io;
  - desde entonces, la comprobación del código generado (`-Pr4c.generatedBy=<release>`) es el paso 1 de cada release.

## Tercera revisión :id=third-review

Los mismos cuatro revisores leyeron 1.0.0-RC2 el 2026-11-05. Ninguno corrió el build completo; quien empieza corrió el quickstart contra los jars de RC2 de Maven Central.

| Revisor | Veredicto |
|---|---|
| Un diseñador de APIs | Sacar 1.0.0 con esta API; nada sería un error permanente. |
| Un escéptico de rendimiento y concurrencia | Sacar 1.0.0: ningún bug de orden de memoria, wake-up perdido, ABA o exactamente-una-vez en core; los arreglos de la fase 3 se sostienen. Un falso positivo en el chequeo de RC2. |
| Alguien que empieza | Una llamada protegida en unos 10 minutos; la salida del quickstart coincidió exactamente con RC2. Afirmaciones desactualizadas sobre el release candidate. |
| Un usuario de Resilience4j que evalúa adoptarla | Un piloto en dos o tres servicios, todavía no producción: ningún usuario externo por ahora, una sola persona manteniéndola, y solo Spring Boot 4.1. |

- [x] Un tipo cuyas anotaciones no llegan a ningún método (una interfaz anotada vacía, una clase con solo métodos final) no generaba nada ni avisaba, y Spring y Quarkus informaban entonces que faltaba el procesador. Ahora es [R4C-APT-007](errors.md#r4c-apt-007); un bundle con solo `@PolicyOrder` ya no cuenta como política en los chequeos de las integraciones.
- [x] El breaker contaba un `Error` como éxito (`recordOn = Exception`), al revés que Resilience4j. Decisión del dueño: `recordOn` vale `Throwable` por defecto.
- [x] Los jars de Quarkus no tenían `Automatic-Module-Name`; los mensajes de procesador ausente nombraban el procesador sin versión.
- [x] Documentación: la portada, el FAQ, la instalación, problemas frecuentes, clases anotadas, timeout, compatibilidad, rendimiento y el artículo de RC2 (ver la entrada Unreleased del changelog).
- [x] Publicar 1.0.0-RC3 con estos cambios (el valor por defecto del breaker y el nuevo error de compilación cambian el comportamiento), y luego 1.0.0.
  - **Resultado:** publicada el 2026-11-05 (commit `release 1.0.0-RC3`, tag `v1.0.0-RC3`, run 42052831462 de `release.yml`, publicada en el portal de Central por el dueño). Los snippets de instalación muestran 1.0.0-RC3.

Queda para después de 1.0 (abajo): los chequeos de procesador ausente de Spring y Micronaut en una imagen nativa (Spring podría correrlo en su paso AOT), `Ticker.onAdvance` sin forma de desregistrarse, `onInstanceCreated` sin la instancia nueva, ninguna forma de detener los hilos daemon compartidos (una fuga en una aplicación web recargable), el tick del watchdog que solo se achica, y los nombres distintos de los interruptores de las tres integraciones (deliberado; a escribir en [diseño](design.md)). Fuera del código: todavía sin feedback externo. La protección de rama en `main` y los releases de GitHub de los release candidates se añadieron el 2026-11-06 (paso 8 de [publicación](releasing.md)).

## Después de 1.0

Aditivo, así que no bloquea congelar la API. Más o menos en orden de valor. Los puntos marcados *(pedido antes de 1.0)* son los que algún revisor quería antes del release: el dueño decide si adelantarlos.

- Correr la comparación con Resilience4j en x86 (`c7i`) y publicarla junto a Graviton; en x86 cada atómico es una barrera completa y `nanoTime` es barato, así que las decisiones afinadas para ARM pueden quedar en otro orden. *(pedido antes de 1.0)*
- ~~jcstress en modo por defecto en un runner ARM (`ubuntu-24.04-arm` o Graviton) de forma periódica.~~ Hecho antes de 1.0: `stress` corre el modo quick en x86 y ARM después de cada push a main y cada noche, y `stress-arm` corre el modo por defecto en ARM cada semana. Falta: repetir allí los mutantes, ya que x86 es TSO y un orden debilitado no podía fallar en él. *(pedido antes de 1.0)*
- Benchmarks de percentiles y de bucle abierto (`SampleTime`) para el rate limiter y el bulkhead con contención. *(pedido antes de 1.0)*
- ~~Un fallo visible en Spring, Micronaut y Quarkus cuando hay clases anotadas sin clase generada.~~ Hecho antes de 1.0: ver la fase 4.
- Métricas opcionales de éxito y latencia, medidas antes de añadirlas (escrituras compartidas por llamada), para tener paridad con los dashboards de Resilience4j. *(pedido antes de 1.0)*
- Soporte de Spring Boot 3, o una razón documentada y una ruta de migración. *(pedido antes de 1.0)*
- Salud del proyecto: un segundo mantenedor con permisos de release, una política de seguridad (`SECURITY.md`) y una política de soporte. *(pedido antes de 1.0)*
- Huecos aditivos de la API que listó su revisor:
  - `Ticker.onAdvance` no tiene forma de darse de baja.
  - Los eventos solo llevan `nanoTime`, no la hora de reloj.
  - `ConfigException.problems()` devuelve `List<String>`, no problemas estructurados (código, clave, origen).
  - Solo los circuit breakers tienen un método de listado en el registry; retry, hedge y timeout no tienen handles.
- Funciones que Resilience4j tiene, cada una por decidir (construirla, o documentar por qué no): bulkhead con thread pool, cache, RxJava, soporte de Kotlin, una transición automática de open a half-open, endpoints de eventos, llevar el contexto de quien llama a los intentos de un hedge.
- Revisar las señales de adopción en unos seis meses, como haría el revisor que evaluó adoptarla: actividad, mantenedores, issues cerrados. Un piloto en un servicio Java puro con mucho tráfico es lo que recomendaría primero.
- De la tercera revisión: el chequeo de procesador ausente en una imagen nativa de Spring o Micronaut (Spring podría correrlo en su paso AOT, en la JVM); que `Ticker.onAdvance` devuelva una forma de desregistrarse; que `onInstanceCreated` pase la instancia nueva; una forma de detener los hilos daemon compartidos, o documentar que `core` va en el class path del servidor en una aplicación web recargable; que el tick del watchdog vuelva a crecer cuando no quedan timeouts cortos; dejar escritos en [diseño](design.md#api-decisions) los nombres de los interruptores de las tres integraciones como deliberados.
