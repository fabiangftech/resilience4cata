# Claves de configuración

Todas las claves que lee el módulo `resilience4cata-config`, con su variable de entorno, tipo, valor por defecto y valores válidos.

Esta página es la referencia; [la guía de configuración](../guides/configuration.md) explica cómo usarla. Un test (`ConfigurationReferenceTest` en el módulo `config`) compara las tablas con el código, así que si una clave o un valor por defecto cambia en el código, el build falla hasta que esta página se actualice.

## Formas de clave :id=key-forms

Cada tabla muestra la forma `defaults.<policy>.<attribute>`. La misma parte `<policy>.<attribute>` sirve en todas las formas:

| Forma | Se aplica a | Ejemplo |
|---|---|---|
| `defaults.<policy>.<attribute>` | todas las instancias de la política | `defaults.retry.maxAttempts=5` |
| `configs.<name>.<policy>.<attribute>` | instancias cuya anotación dice `config = "<name>"` | `configs.external.timeout.value=2s` |
| `configs.<name>.extends=<other>` | hereda todas las políticas de otra configuración con nombre | `configs.batch.extends=external` |
| `instances.<id>.<policy>.<attribute>` | una instancia: su `name`, o su id derivado entre comillas dobles | `instances."com.acme.Api#get(java.lang.String)".timeout.value=2s` |
| `profiles.<profile>.<cualquier clave anterior>` | solo mientras el perfil está activo | `profiles.test.defaults.retry.maxAttempts=1` |
| `profiles.active=<a>,<b>` | los perfiles activos; ganan los últimos | `profiles.active=test` |

- Políticas: `retry`, `hedge`, `circuitBreaker`, `rateLimiter`, `adaptiveLimit`, `timeout`, `bulkhead`. `@Fallback` no tiene claves.
- Los nombres de política y de atributo se pueden escribir en camelCase o kebab-case: `circuit-breaker.wait-in-open` es `circuitBreaker.waitInOpen`.
- Los nombres de configuraciones y perfiles son letras minúsculas y dígitos separados por guiones simples (`slow-external`).
- Un id de instancia distingue mayúsculas. Va entre comillas dobles salvo que tenga solo letras, dígitos, `_` y `-`. Un id derivado es `<type>#<method>(<parameter types>)`, con los tipos de parámetro totalmente calificados y borrados (erasure), separados por comas sin espacios, por ejemplo `com.acme.CatalogClient#price(java.lang.String,int)`.
- Toda clave puede empezar con `r4c.` o `resilience4cata.`. En las **propiedades del sistema el prefijo es obligatorio**; las demás propiedades del sistema se ignoran.
- Algunos atributos existen solo en las anotaciones y no tienen clave: `name`, `config`, `locked`, `classifier`, `onExhaustedResult` y todo lo de `@Fallback`.

## Variables de entorno :id=environment-variables

`R4C_` (o `RESILIENCE4CATA_`) seguido de la clave en mayúsculas, con cada carácter que no sea letra ni dígito reemplazado por `_`. Las tablas muestran la variable de cada clave `defaults.`; las demás formas siguen la misma regla:

| Clave | Variable |
|---|---|
| `configs.external.retry.maxAttempts` | `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS` |
| `configs.slow-external.timeout.value` | `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE` |
| `instances.payments-api.retry.maxAttempts` | `R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS` |
| `profiles.active` | `R4C_PROFILES_ACTIVE` |

También se pueden separar las palabras (`R4C_DEFAULTS_CIRCUIT_BREAKER_WAIT_IN_OPEN`). Una variable debe corresponder exactamente a una clave ([R4C-CFG-010](../errors.md#r4c-cfg-010)). Desde el entorno solo se pueden configurar instancias cuyo nombre sea de letras minúsculas, dígitos y guiones; un id derivado necesita un archivo o una propiedad del sistema.

## Valores :id=values

| Tipo | Sintaxis |
|---|---|
| duración | dígitos y una unidad: `ns`, `us`, `ms`, `s`, `m`, `h`, `d` (`250ms`, `2s`); una duración ISO-8601 (`PT2S`, `PT1M30S`); o `0`. Sin decimales, sin negativos, sin espacios. La misma gramática que en las anotaciones. |
| int | un número entero (`5`) |
| decimal | un número como `50` o `2.5` |
| boolean | `true` o `false` |
| enum | uno de los valores listados, sin distinguir mayúsculas (`exponential`, `EXPONENTIAL`) |
| lista de clases | nombres binarios de clases de excepción separados por comas (`java.io.IOException, java.net.SocketTimeoutException`), cargados con el class loader configurado; clases anidadas como `a.B$C` o `a.B.C`; vacío para ninguna |

También se validan las combinaciones: `maxDelay` no puede ser menor que `delay`, y el `period` de un rate limiter debe ser de al menos un nanosegundo por permiso ([R4C-CFG-006](../errors.md#r4c-cfg-006)).

## Fuentes y precedencia :id=precedence

`ResilienceConfig.load()` lee, de menor a mayor: el recurso de classpath `resilience4cata.properties` (opcional), las variables de entorno y las propiedades del sistema. Con `ResilienceConfig.builder()` eliges las fuentes; para una misma clave el orden es recursos de classpath < archivos y `Properties` < variables de entorno < propiedades del sistema, y entre fuentes del mismo tipo gana la última agregada. Dentro de una misma fuente, una clave de un perfil activo gana a la misma clave sin perfil; una fuente más fuerte gana incluso sobre una clave de perfil de una más débil.

Después los valores se combinan, de menor a mayor:

1. los valores por defecto incorporados (la columna **Por defecto** de abajo);
2. `defaults.*` (y `ResilienceRegistry.Builder.*Defaults(...)` en código);
3. `configs.<name>.*`, empezando por la raíz de la cadena `extends`; las claves externas sobrescriben, atributo por atributo, una configuración con el mismo nombre registrada en código;
4. los atributos de las anotaciones;
5. `instances.<id>.*`, para que operaciones pueda reajustar una instancia sin recompilar, salvo los atributos de una anotación con `locked = true`, que conservan el valor de la anotación ([Bloquear una instancia](../guides/configuration.md#bloquear-una-instancia)).

Todo se resuelve una vez, cuando se crea una instancia. Después no se recarga nada. Para comprobar que cada clave `instances.<id>` corresponde a una instancia, llama a `config.verifyInstances(registry)` después de envolver tus servicios ([R4C-CFG-013](../errors.md#r4c-cfg-013)); también reporta las claves que una anotación bloqueada ignora ([R4C-CFG-014](../errors.md#r4c-cfg-014)).

## Retry :id=retry

Atributos de `@Retry`. Ver [Retry](../policies/retry.md).

| Clave | Variable de entorno | Tipo | Por defecto | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.retry.enabled` | `R4C_DEFAULTS_RETRY_ENABLED` | boolean | `true` | `true`, `false` | `false` ejecuta cada llamada una vez, sin reintentos. |
| `defaults.retry.maxAttempts` | `R4C_DEFAULTS_RETRY_MAXATTEMPTS` | int | `3` | ≥ 1 | Intentos en total, el primero incluido. |
| `defaults.retry.delay` | `R4C_DEFAULTS_RETRY_DELAY` | duración | `200ms` | ≥ 0 | Espera antes del primer reintento; la base del backoff. |
| `defaults.retry.backoff` | `R4C_DEFAULTS_RETRY_BACKOFF` | enum | `exponential` | `fixed`, `exponential` | `fixed`: cada espera es `delay`. `exponential`: `delay × multiplier^(n−1)`, con tope `maxDelay`. |
| `defaults.retry.multiplier` | `R4C_DEFAULTS_RETRY_MULTIPLIER` | decimal | `2.0` | finito, ≥ 1 | Factor de crecimiento del backoff exponencial. |
| `defaults.retry.maxDelay` | `R4C_DEFAULTS_RETRY_MAXDELAY` | duración | `10s` | ≥ `delay` | Tope de una sola espera. |
| `defaults.retry.jitter` | `R4C_DEFAULTS_RETRY_JITTER` | enum | `full` | `none`, `full`, `equal` | `full`: aleatoria en [0, d]. `equal`: d/2 más una mitad aleatoria. `none`: exactamente d. |
| `defaults.retry.maxDuration` | `R4C_DEFAULTS_RETRY_MAXDURATION` | duración | `0` | ≥ 0; `0` = sin límite | Presupuesto de tiempo de toda la operación: se rinde cuando la siguiente espera lo superaría. |
| `defaults.retry.retryOn` | `R4C_DEFAULTS_RETRY_RETRYON` | lista de clases | `java.lang.Exception` | clases de excepción | Fallos que se reintentan, subclases incluidas. Por defecto los `Error` no se reintentan. |
| `defaults.retry.abortOn` | `R4C_DEFAULTS_RETRY_ABORTON` | lista de clases | — | clases de excepción | Fallos que nunca se reintentan; ganan sobre `retryOn` y sobre un clasificador. Una `CallNotPermittedException` (breaker abierto) tampoco se reintenta nunca, esté en la lista o no. |
| `defaults.retry.budgetPercent` | `R4C_DEFAULTS_RETRY_BUDGETPERCENT` | decimal | `0` | finito, ≥ 0 | Presupuesto de reintentos: reintentos permitidos, en porcentaje de las llamadas de la instancia en la última `budgetWindow`, además de `budgetMinRetriesPerSecond`. Por encima de `0` activa el presupuesto; `0` significa sin presupuesto. |
| `defaults.retry.budgetMinRetriesPerSecond` | `R4C_DEFAULTS_RETRY_BUDGETMINRETRIESPERSECOND` | int | `10` | ≥ 0 | Presupuesto de reintentos: reintentos permitidos por segundo de `budgetWindow`, sea cual sea el tráfico, una vez que `budgetPercent` activa el presupuesto. `0` deja solo el porcentaje. |
| `defaults.retry.budgetWindow` | `R4C_DEFAULTS_RETRY_BUDGETWINDOW` | duración | `10s` | de 1s a 1h | Presupuesto de reintentos: hasta dónde hacia atrás se cuentan llamadas y reintentos. |

## Hedge :id=hedge

Atributos de `@Hedge`. Ver [Hedge](../policies/hedge.md).

| Clave | Variable de entorno | Tipo | Por defecto | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.hedge.enabled` | `R4C_DEFAULTS_HEDGE_ENABLED` | boolean | `true` | `true`, `false` | `false` ejecuta cada llamada una vez, sin hedge. |
| `defaults.hedge.maxAttempts` | `R4C_DEFAULTS_HEDGE_MAXATTEMPTS` | int | `2` | de 2 a 10 | Intentos en vuelo como máximo, el primero incluido. |
| `defaults.hedge.delay` | `R4C_DEFAULTS_HEDGE_DELAY` | duración | `1s` | ≥ 0; `0` = todos a la vez | Cuánto tiempo lleva el último intento sin resultado antes de que empiece el siguiente. |
| `defaults.hedge.hedgeOn` | `R4C_DEFAULTS_HEDGE_HEDGEON` | lista de clases | `java.lang.Exception` | clases de excepción | Fallos tras los que el siguiente intento empieza enseguida; un fallo no listado termina la llamada. |
| `defaults.hedge.abortOn` | `R4C_DEFAULTS_HEDGE_ABORTON` | lista de clases | — | clases de excepción | Fallos que terminan la llamada enseguida y cancelan los demás intentos; ganan sobre `hedgeOn`. |
| `defaults.hedge.budgetPercent` | `R4C_DEFAULTS_HEDGE_BUDGETPERCENT` | decimal | `10` | finito, ≥ 0 | Presupuesto de hedge: hedges permitidos, en porcentaje de las llamadas de la instancia en la última `budgetWindow`, además de `budgetMinHedgesPerSecond`. `0` apaga el presupuesto. |
| `defaults.hedge.budgetMinHedgesPerSecond` | `R4C_DEFAULTS_HEDGE_BUDGETMINHEDGESPERSECOND` | int | `1` | ≥ 0 | Presupuesto de hedge: hedges permitidos por segundo de `budgetWindow`, sea cual sea el tráfico. |
| `defaults.hedge.budgetWindow` | `R4C_DEFAULTS_HEDGE_BUDGETWINDOW` | duración | `10s` | de 1s a 1h | Presupuesto de hedge: hasta dónde hacia atrás se cuentan llamadas y hedges. |

## Circuit breaker :id=circuit-breaker

Atributos de `@CircuitBreaker`. Ver [Circuit breaker](../policies/circuit-breaker.md).

| Clave | Variable de entorno | Tipo | Por defecto | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.circuitBreaker.enabled` | `R4C_DEFAULTS_CIRCUITBREAKER_ENABLED` | boolean | `true` | `true`, `false` | `false`: el breaker empieza `DISABLED`, deja pasar todas las llamadas y no registra nada. |
| `defaults.circuitBreaker.windowType` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWTYPE` | enum | `count` | `count`, `time` | `count`: las últimas `windowSize` llamadas. `time`: los últimos `windowSize` segundos (lee el reloj en cada llamada). |
| `defaults.circuitBreaker.windowSize` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWSIZE` | int | `100` | ≥ 1 | Llamadas (`count`) o segundos (`time`) de la ventana deslizante. |
| `defaults.circuitBreaker.minimumCalls` | `R4C_DEFAULTS_CIRCUITBREAKER_MINIMUMCALLS` | int | `100` | ≥ 1 | Llamadas en la ventana antes de evaluar las tasas (con tope `windowSize` en una ventana por conteo). |
| `defaults.circuitBreaker.failureRateThreshold` | `R4C_DEFAULTS_CIRCUITBREAKER_FAILURERATETHRESHOLD` | decimal | `50` | > 0 y ≤ 100 | Tasa de fallos, en porcentaje, que abre el breaker. |
| `defaults.circuitBreaker.slowCallRateThreshold` | `R4C_DEFAULTS_CIRCUITBREAKER_SLOWCALLRATETHRESHOLD` | decimal | `100` | > 0 y ≤ 100 | Tasa de llamadas lentas, en porcentaje, que abre el breaker. |
| `defaults.circuitBreaker.slowCallDuration` | `R4C_DEFAULTS_CIRCUITBREAKER_SLOWCALLDURATION` | duración | `0` | ≥ 0; `0` = desactivado | Las llamadas más largas que esto son lentas. Solo se cronometran las llamadas si se define. |
| `defaults.circuitBreaker.waitInOpen` | `R4C_DEFAULTS_CIRCUITBREAKER_WAITINOPEN` | duración | `60s` | ≥ 0 | Cuánto tiempo queda abierto el breaker; la siguiente llamada después es una sonda semiabierta. |
| `defaults.circuitBreaker.halfOpenCalls` | `R4C_DEFAULTS_CIRCUITBREAKER_HALFOPENCALLS` | int | `10` | ≥ 1 | Llamadas de sonda permitidas en estado semiabierto. |
| `defaults.circuitBreaker.maxWaitInHalfOpen` | `R4C_DEFAULTS_CIRCUITBREAKER_MAXWAITINHALFOPEN` | duración | `0` | ≥ 0; `0` = sin límite | Cuánto puede seguir semiabierto antes de volver a abrirse si sus sondas no terminaron. |
| `defaults.circuitBreaker.recordOn` | `R4C_DEFAULTS_CIRCUITBREAKER_RECORDON` | lista de clases | `java.lang.Exception` | clases de excepción | Fallos que se registran como fallos. Todo lo demás, incluidos los `Error`, cuenta como éxito. |
| `defaults.circuitBreaker.ignoreOn` | `R4C_DEFAULTS_CIRCUITBREAKER_IGNOREON` | lista de clases | — | clases de excepción | Fallos que no cuentan ni como éxito ni como fallo; ganan sobre `recordOn` y sobre un clasificador. |

## Rate limiter :id=rate-limiter

Atributos de `@RateLimiter`. Ver [Rate limiter](../policies/rate-limiter.md).

| Clave | Variable de entorno | Tipo | Por defecto | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.rateLimiter.enabled` | `R4C_DEFAULTS_RATELIMITER_ENABLED` | boolean | `true` | `true`, `false` | `false` deja pasar todas las llamadas. |
| `defaults.rateLimiter.permits` | `R4C_DEFAULTS_RATELIMITER_PERMITS` | int | `50` | ≥ 1 | Llamadas por `period`, y la ráfaga más grande. |
| `defaults.rateLimiter.period` | `R4C_DEFAULTS_RATELIMITER_PERIOD` | duración | `1s` | > 0 y ≥ `permits` ns | Duración del periodo; los permisos se espacian `period / permits`. |
| `defaults.rateLimiter.maxWait` | `R4C_DEFAULTS_RATELIMITER_MAXWAIT` | duración | `0` | ≥ 0; `0` = rechaza enseguida | Cuánto puede esperar una llamada un permiso antes de `RateLimitExceededException`. |

## Adaptive limit :id=adaptive-limit

Atributos de `@AdaptiveLimit`, más el ajuste de los algoritmos, que no tiene atributo en la anotación (`minWindowSamples`, `smoothing`, `rttTolerance`, `longWindow`, `queueSize`, `backoffRatio`). Ver [Adaptive limit](../policies/adaptive-limit.md).

| Clave | Variable de entorno | Tipo | Por defecto | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.adaptiveLimit.enabled` | `R4C_DEFAULTS_ADAPTIVELIMIT_ENABLED` | boolean | `true` | `true`, `false` | `false` no limita las llamadas concurrentes. |
| `defaults.adaptiveLimit.algorithm` | `R4C_DEFAULTS_ADAPTIVELIMIT_ALGORITHM` | enum | `gradient2` | `gradient2`, `aimd` | `gradient2`: compara la latencia de cada ventana con un promedio de largo plazo. `aimd`: un lugar más tras una ventana ocupada, por `backoffRatio` tras un descarte. |
| `defaults.adaptiveLimit.initialLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_INITIALLIMIT` | int | `20` | `minLimit` a `maxLimit` | El límite antes de que termine la primera ventana. |
| `defaults.adaptiveLimit.minLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_MINLIMIT` | int | `1` | ≥ 1 | Lo más bajo que llega el límite. |
| `defaults.adaptiveLimit.maxLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_MAXLIMIT` | int | `200` | ≥ `minLimit` | Lo más alto que llega el límite. |
| `defaults.adaptiveLimit.window` | `R4C_DEFAULTS_ADAPTIVELIMIT_WINDOW` | duración | `1s` | 10ms a 1h | Cuánto dura como mínimo una ventana; el límite se recalcula al final. |
| `defaults.adaptiveLimit.minWindowSamples` | `R4C_DEFAULTS_ADAPTIVELIMIT_MINWINDOWSAMPLES` | int | `10` | ≥ 1 | Llamadas terminadas que necesita una ventana para terminar; una ventana con menos se extiende una `window` más, conservando sus llamadas. |
| `defaults.adaptiveLimit.dropOn` | `R4C_DEFAULTS_ADAPTIVELIMIT_DROPON` | lista de clases | `dev.resilience4cata.CallTimeoutException, dev.resilience4cata.BulkheadFullException, java.util.concurrent.TimeoutException` | clases de excepción | Fallos que indican que la dependencia está sobrecargada: muestras con descarte (`aimd` retrocede). Cualquier otro fallo no es una muestra. |
| `defaults.adaptiveLimit.ignoreOn` | `R4C_DEFAULTS_ADAPTIVELIMIT_IGNOREON` | lista de clases | — | clases de excepción | Fallos que nunca son muestras; ganan sobre `dropOn`. |
| `defaults.adaptiveLimit.smoothing` | `R4C_DEFAULTS_ADAPTIVELIMIT_SMOOTHING` | decimal | `0.2` | > 0 y ≤ 1 | `gradient2`: cuánto de cada estimación nueva se conserva. |
| `defaults.adaptiveLimit.rttTolerance` | `R4C_DEFAULTS_ADAPTIVELIMIT_RTTTOLERANCE` | decimal | `1.5` | finito, ≥ 1 | `gradient2`: cuántas veces la latencia de largo plazo puede alcanzar una ventana antes de que el límite baje. |
| `defaults.adaptiveLimit.longWindow` | `R4C_DEFAULTS_ADAPTIVELIMIT_LONGWINDOW` | int | `600` | ≥ 1 | `gradient2`: ventanas promediadas en la latencia de largo plazo. |
| `defaults.adaptiveLimit.queueSize` | `R4C_DEFAULTS_ADAPTIVELIMIT_QUEUESIZE` | int | `4` | ≥ 0 | `gradient2`: margen que deja el límite por encima de la concurrencia estimada, para poder crecer. La latencia sola no lleva el límite mucho más abajo del doble de este valor. |
| `defaults.adaptiveLimit.backoffRatio` | `R4C_DEFAULTS_ADAPTIVELIMIT_BACKOFFRATIO` | decimal | `0.9` | ≥ 0,5 y < 1 | `aimd`: por cuánto multiplica el límite una ventana con un descarte. |

## Timeout :id=timeout

Atributos de `@Timeout`. El `value` de la anotación es la clave `timeout.value`. `@Timeout` no tiene `name`, así que `instances.<id>.timeout.*` siempre usa el id derivado. Ver [Timeout](../policies/timeout.md).

| Clave | Variable de entorno | Tipo | Por defecto | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.timeout.enabled` | `R4C_DEFAULTS_TIMEOUT_ENABLED` | boolean | `true` | `true`, `false` | `false` nunca corta una llamada por tiempo. |
| `defaults.timeout.value` | `R4C_DEFAULTS_TIMEOUT_VALUE` | duración | `1s` | > 0 | Cuánto puede tardar un intento (`@Timeout("2s")`; `TimeoutConfig.timeout()` en código). |

## Bulkhead :id=bulkhead

Atributos de `@Bulkhead`. Ver [Bulkhead](../policies/bulkhead.md).

| Clave | Variable de entorno | Tipo | Por defecto | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.bulkhead.enabled` | `R4C_DEFAULTS_BULKHEAD_ENABLED` | boolean | `true` | `true`, `false` | `false` no limita las llamadas concurrentes. |
| `defaults.bulkhead.maxConcurrent` | `R4C_DEFAULTS_BULKHEAD_MAXCONCURRENT` | int | `25` | ≥ 1 | Llamadas ejecutándose a la vez. |
| `defaults.bulkhead.maxWait` | `R4C_DEFAULTS_BULKHEAD_MAXWAIT` | duración | `0` | ≥ 0; `0` = rechaza enseguida | Cuánto puede esperar una llamada un hueco libre antes de `BulkheadFullException`. |

## Errores :id=errors

Todos los problemas de una configuración se informan juntos, en una sola `ConfigException` con un código `R4C-CFG-nnn`, la clave, su origen (archivo y línea, variable de entorno o propiedad del sistema) y la clave válida más parecida. Ver [códigos de error](../errors.md#r4c-cfg-001).
