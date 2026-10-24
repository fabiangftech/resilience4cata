# Features

> Síntesis de [01 Panorama](research/01-landscape.md), [03 Rendimiento interno](research/03-performance-internals.md) y [04 API y configuración](research/04-api-and-configuration.md). Es una propuesta: los nombres y atributos todavía pueden cambiar antes de `1.0`.

## Niveles

| Feature | Nivel | Notas |
|---|---|---|
| Circuit breaker: ventanas por cantidad y por tiempo, tasa de llamadas lentas, sondeo en half-open, `forceOpen` / `disable` | **MVP** | Mismo modelo que Resilience4j, para facilitar la migración |
| Retry: backoff fijo o exponencial, jitter full o equal (activo por defecto), `maxDuration`, retry según el resultado | **MVP** | |
| Timeout, síncrono y asíncrono | **MVP** | Síncrono: la llamada sigue en el thread del llamador y un temporizador la interrumpe |
| Bulkhead (semáforo, rechazo inmediato o con `maxWait`) | **MVP** | Sin bulkhead de pool de threads: los virtual threads lo hacen innecesario |
| Rate limiter | **MVP** | GCRA por dentro, configurado como permisos por período |
| Fallback (clase handler tipada, o un método validado al compilar) | **MVP** | |
| Async según el tipo de retorno (`CompletionStage` / `CompletableFuture`) | **MVP** | Sin anotación `@Asynchronous` |
| Anotaciones compuestas (`@ResiliencePolicy`) | **MVP** | La forma principal de reutilizar configuraciones en el código |
| Configuraciones con nombre, `extends`, perfiles; properties, variables de entorno y propiedades del sistema | **MVP** | |
| API programática (sin anotaciones) | **MVP** | Para código que no se puede anotar |
| Eventos tipados, sin asignar memoria si nadie escucha | **MVP** | |
| Inspección de estado (`registry.snapshot()`) | **MVP** | Records de solo lectura, útiles para health checks |
| Soporte para tests: reloj y scheduler manuales, jitter con semilla, estados forzados | **MVP** | En `testkit` |
| Hedging (`@Hedge`) | v1 | Diferenciador; Resilience4j no lo tiene |
| Límite de concurrencia adaptativo (`@AdaptiveLimit`: AIMD, Vegas, Gradient2) | v1 | Pedido en Resilience4j desde 2018 |
| Presupuesto de reintentos | v1 | Evita tormentas de reintentos |
| Puentes a Micrometer y OpenTelemetry | v1 | Módulos separados |
| Configuración YAML | v1 | Módulo separado, para que `core` siga sin dependencias |
| Orden de ejecución configurable (`@Order`) | v1 | |
| Extensión de JUnit 5 | v1 | |
| `@Cached`, tipos reactivos, corrutinas de Kotlin, inyección de fallos, propagación de deadlines, integración con frameworks | después | |

## Anotaciones

```java
@CircuitBreaker  @Retry  @Timeout  @Bulkhead  @RateLimiter  @Fallback   // MVP
@Hedge  @AdaptiveLimit  @Order                                           // v1
@ResiliencePolicy                                                        // marca anotaciones compuestas
```

Reglas de diseño ([04 §4](research/04-api-and-configuration.md)):

- **`config` y `name` son cosas distintas.** `config` elige una plantilla con nombre. `name` elige la instancia con estado (ventana del breaker, permisos); por defecto hay una instancia por método. Dos métodos con el mismo `name` comparten la instancia y deben resolver a la misma configuración.
- **Cada atributo tiene un valor "sin definir"** (`-1`, `""`, `{}`), así una anotación solo sobrescribe lo que escribe. Los valores por defecto reales viven en la capa de configuración.
- **Tipado donde Java lo permite**: clases de excepción, enums, clases de fallback.
- **Las duraciones son strings** (`"250ms"`, `"2s"`, `"PT2S"`), validados al compilar.
- Las anotaciones van en métodos o tipos. Una anotación de método sobrescribe la de tipo atributo por atributo.
- Las ubicaciones no soportadas (métodos final o privados, según el estilo generado) son **errores de compilación**, nunca se ignoran en silencio.

### Ejemplo

```java
@ResiliencePolicy
@Retention(CLASS) @Target({METHOD, TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
@Bulkhead(maxConcurrent = 50)
public @interface ExternalCall {}

public interface PaymentClient {

    @ExternalCall
    @Fallback(PaymentFallback.class)          // handler tipado: FallbackHandler<Receipt>
    Receipt charge(Order order);

    @ExternalCall
    @Timeout("500ms")                         // sobrescribe un atributo del paquete
    CompletableFuture<Status> status(String id);
}
```

Si dos anotaciones compuestas en el mismo elemento ponen valores distintos en el mismo atributo, el build falla. No hay un "gana el último" silencioso.

### Clases anotadas

Una clase no final que declara al menos una anotación de resiliencia obtiene una subclase generada, `<Clase>$Resilient`. Esta sobrescribe cada método que tiene políticas y lo termina con `super.metodo(...)`, así que las llamadas que el objeto se hace a sí mismo también pasan por las políticas:

```java
public class InventoryService implements InventoryApi {      // InventoryApi: @Retry(delay = "100ms")
    public InventoryService(Repository repository) { ... }

    @Override
    @Retry(maxAttempts = 4)                    // gana sobre la interfaz; conserva su delay
    public Stock stock(String sku) { ... }

    public Report report(String sku) {
        return new Report(this.price(sku));    // interceptado: price() se reintenta y luego usa el fallback
    }

    @Retry(maxAttempts = 3) @Fallback(method = "cachedPrice")
    public Price price(String sku) { ... }

    protected Price cachedPrice(String sku, Exception e) { ... }   // no privado: la subclase lo llama
}

InventoryService service = Resilient.create(InventoryService.class, registry, repository);
InventoryService typed = new InventoryService$Resilient(registry, repository);   // lo mismo, verificado por el compilador
```

- Precedencia, de menor a mayor: interfaz, método de la interfaz, clase, método de la clase. Un método que sobrescribe uno de una superclase le gana. Las anotaciones a nivel de tipo de la clase se aplican a todo método que pueda sobrescribir, incluidos los heredados, pero no a `toString`/`equals`/`hashCode` ni a los demás métodos de `Object`, que solo toman las anotaciones escritas en ellos. Las anotaciones a nivel de tipo omiten los métodos final.
- `Resilient.create` elige el constructor que acepta los argumentos, como lo haría el compilador; si no encaja ninguno o encajan varios (por ejemplo `null` pasado a constructores sobrecargados), falla y los nombra. Los constructores generados reciben primero un `ResilienceRegistry` y después los parámetros propios de la clase.
- Una clase que implementa una interfaz anotada pero no declara ninguna anotación propia no se procesa: se envuelve con `Resilient.of(InventoryApi.class, impl, registry)`, que aplica solo las políticas de la interfaz y no intercepta la auto-invocación.
- Errores de compilación: clases final, sealed, abstractas, internas (no estáticas) o privadas, records, enums, clases con solo constructores privados, y métodos final, privados o estáticos anotados a nivel de método.
- Los métodos que el constructor de la clase llama sobre `this` corren antes de que se configuren las políticas: no hay que anotarlos.

### Métodos genéricos y stages con comodín

- `@Fallback` funciona en métodos genéricos como `<T> T load(Class<T> type, String id)`. Un método hermano de fallback declara los mismos parámetros de tipo, en el mismo orden y con los mismos límites (los nombres pueden cambiar), por ejemplo `default <U> U load(Class<U> type, String id, IOException e)`; el código generado lo llama con argumentos de tipo explícitos. Un `FallbackHandler<X>` solo se puede usar cuando el tipo del resultado del método no usa sus parámetros de tipo (`<T> String describe(T value)`), porque un único `X` no sirve para todo `T` sin un cast no verificado; si no, es un error de compilación.
- Un método async puede devolver `CompletionStage<? extends X>` o `CompletableFuture<? extends X>` (`?` equivale a `? extends Object`). Las políticas trabajan con `X`: los fallbacks devuelven un `X`, un `CompletionStage<X>` o un `CompletionStage<? extends X>`, y los classifiers tienen que aceptar `X`. `? super X` es un error de compilación.

## Orden de ejecución

Por defecto, de afuera hacia adentro ([04 §5](research/04-api-and-configuration.md)):

```
Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → método
```

- El fallback va afuera, así ve el resultado final, incluidos los rechazos.
- El breaker registra cada intento del retry, y un breaker abierto corta los reintentos.
- Las llamadas que rechaza el breaker no gastan permisos del rate limiter.
- El timeout aplica por intento; `@Retry(maxDuration)` limita la operación completa.
- El permiso del bulkhead se ocupa solo mientras corre el método, no durante el backoff.

## Múltiples configuraciones

Orden de resolución, de menor a mayor prioridad ([04 §6](research/04-api-and-configuration.md)):

```
1. valores por defecto de la librería
2. defaults.<política>.*
3. configs.<nombre>.*              (siguiendo la cadena de extends)
4. profiles.<activo>.*
5. anotación compuesta             (@ExternalCall)
6. anotación de clase
7. anotación de método
8. instances.<id>.*                (externo, por instancia: archivos < env < propiedades del sistema)
9. override programático
```

- La configuración externa (8) sobrescribe las anotaciones, como en MicroProfile Fault Tolerance: operaciones puede ajustar valores sin recompilar. Está previsto un atributo `locked` que lo impida para una instancia; todavía no está implementado.
- Una configuración con nombre registrada a la vez por código y externamente se combina: los valores externos reemplazan a los del código atributo por atributo, sin importar el orden de las llamadas.
- Todo se valida y se resuelve **una sola vez**, al crear la instancia, en records inmutables. El camino caliente nunca busca configuración.
- Las claves desconocidas fallan con un mensaje "¿quisiste decir …?".
- **Sin recarga en caliente.** La configuración es inmutable una vez construido el registry; para cambiarla hay que reiniciar la aplicación.

```properties
defaults.retry.maxAttempts=3
configs.external.circuitBreaker.failureRateThreshold=50
configs.external.retry.delay=200ms
configs.batch.extends=external
configs.batch.timeout.value=30s
instances.payments.circuitBreaker.minimumCalls=20
instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts=5
profiles.test.configs.external.retry.maxAttempts=1
```

### Cargar configuración externa (módulo `config`)

```java
ResilienceRegistry registry = ResilienceConfig.load()   // resilience4cata.properties del classpath (si existe), entorno, propiedades del sistema
        .applyTo(ResilienceRegistry.builder())
        .build();
```

- Claves: `defaults.<policy>.<attribute>`, `configs.<name>.<policy>.<attribute>`, `configs.<name>.extends`, `profiles.<profile>.…`, `profiles.active`. Políticas: `retry`, `circuitBreaker`, `rateLimiter`, `timeout`, `bulkhead`; los atributos son los de la anotación (el timeout es `timeout.value`). Se aceptan camelCase y kebab-case; se permite un prefijo opcional `resilience4cata.` o `r4c.`, obligatorio en las propiedades del sistema.
- Nombres de configuraciones y perfiles: minúsculas y dígitos separados por guiones simples (`slow-external`).
- Variables de entorno: `R4C_` + la clave en mayúsculas con cada carácter no alfanumérico como `_`: `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS=5`, `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE=10s`, `R4C_PROFILES_ACTIVE=test`. Cada variable tiene que corresponder a una sola clave.
- Fuentes, de menor a mayor prioridad: recursos del classpath < archivos < entorno < propiedades del sistema. Las claves de un perfil le ganan a las que no son de perfil, venga de donde venga.
- Valores: duraciones como en las anotaciones (`250ms`, `2s`, `PT2S`), enums sin distinguir mayúsculas, clases de excepción por nombre completo (separadas por comas).
- `build()` informa todos los problemas a la vez, con la clave, su fuente (archivo y línea, variable de entorno, propiedad del sistema) y la clave válida más parecida, por ejemplo `[R4C-CFG-001] unknown key 'configs.external.circuit-breaker.wait-in-opne' (file /app/resilience4cata.properties, line 7). Did you mean 'configs.external.circuit-breaker.wait-in-open'?`
- `ResilienceConfig.describe()` lista cada valor efectivo y de dónde viene.
- Overrides por instancia: `instances.<id>.<política>.<atributo>`, donde `<id>` es el `name` de la política, o el id derivado `<interfaz>#<método>(<tipos de parámetros>)` si no tiene. Los ids distinguen mayúsculas; uno que contenga algo distinto de letras, dígitos, `_` y `-` va entre comillas dobles: `instances."com.acme.Api#get(java.lang.String)".retry.max-attempts=5`. Se aplican después de los valores de la anotación, una sola vez, al crear la instancia, y también dentro de perfiles (`profiles.test.instances.…`). Las variables de entorno solo pueden referirse a nombres en minúsculas (`R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS`); para ids derivados hay que usar un archivo o una propiedad del sistema (`-Dr4c.instances."<id>".retry.maxAttempts=5`). Por código: `ResilienceRegistry.Builder.retryInstance(id, r -> …)` y equivalentes; las llamadas hechas después de `applyTo` ganan.
- Las instancias se crean al envolver los servicios, así que `build()` no puede saber si un id existe: después de envolverlos, `config.verifyInstances(registry)` da `[R4C-CFG-013]` con un "¿quisiste decir…?" por cada override que no corresponde a ninguna instancia. Los valores por instancia que solo tienen sentido junto con los de la anotación (por ejemplo un `delay` mayor que el `maxDelay` por defecto) se verifican al crear la instancia.
- Una configuración con nombre puede definirse a la vez por código y externamente: los valores externos reemplazan a los del código atributo por atributo (`ResilienceRegistry.Builder.retryOverlay(...)` y equivalentes).

## Classifiers: resultados como fallas

`@Retry` y `@CircuitBreaker` aceptan un `classifier`: una clase que implementa `dev.resilience4cata.Classifier<T>` y decide, para cada llamada, si su resultado o su excepción es un `SUCCESS`, un `FAILURE` o algo para `IGNORE`.

```java
public final class HttpStatusClassifier implements Classifier<HttpResponse<String>> {
    @Override
    public Outcome classify(@Nullable HttpResponse<String> response, @Nullable Throwable failure) {
        if (failure != null) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
        }
        int status = response.statusCode();
        return status >= 500 || status == 429 ? Outcome.FAILURE : Outcome.SUCCESS;
    }
}

public interface Api {
    @Retry(maxAttempts = 3, classifier = HttpStatusClassifier.class)
    @CircuitBreaker(classifier = HttpStatusClassifier.class)
    HttpResponse<String> get(URI uri) throws IOException;
}
```

- El procesador verifica la clase al compilar: concreta y no genérica, con un constructor público sin argumentos, y con un `T` al que se pueda asignar el tipo de retorno del método (o el `T` de su `CompletionStage<T>`). Se crea una instancia por método; el retry y el breaker la comparten si nombran la misma clase.
- El classifier reemplaza a `retryOn` / `recordOn` (poner los dos es un error de compilación). `abortOn` / `ignoreOn` se siguen aplicando primero, así que un breaker abierto sigue cortando los reintentos por defecto.
- **Retry:** solo se reintenta lo que es `FAILURE`, sea un resultado o una excepción. Si los intentos se agotan con un resultado `FAILURE`, por defecto se devuelve el último resultado; `@Fallback` solo atiende excepciones, así que no corre. Con `@Retry(onExhaustedResult = ExhaustedResult.THROW)` el retry lanza en cambio una `ResultRejectedException` con el último resultado (`result()`, `result(Class)`) y los intentos; pasa por `@Fallback` como cualquier otra falla (un método hermano puede recibir `ResultRejectedException`). `onExhaustedResult` necesita un classifier (si no, es un error de compilación) y se decide al compilar, no por configuración. `maxDuration` se aplica como siempre.
- **Circuit breaker:** `FAILURE` cuenta como falla (y como lenta si lo fue), `SUCCESS` como éxito. `IGNORE` no se registra, y una sonda half-open devuelve su permiso.
- Un classifier no debería lanzar excepciones. Si lo hace, su excepción pasa a ser la falla de la llamada: el breaker registra una falla, el retry se detiene y publica `RetryEvent.GaveUp` con motivo `CLASSIFIER_FAILED`, y `@Fallback` la recibe.
- Los métodos async se comportan igual.
- Costo: los métodos sin classifier generan exactamente el mismo código que antes. Con uno, una llamada exitosa hace una llamada directa a `classify` por política, sin asignar memoria ni leer el reloj.

## API programática

Para código que no se puede anotar (clases de terceros, lambdas, scripts), se arma un `Pipeline` a partir del registry. Es inmutable y thread-safe; se construye una vez y se reutiliza.

```java
Pipeline<Receipt> charge = Pipeline.builder(registry, "payments", Receipt.class)
        .retry("external")                                   // configuración con nombre
        .circuitBreaker(cb -> cb.failureRateThreshold(25))   // valores por defecto + ajustes
        .rateLimiter("external", rl -> rl.permits(50))       // configuración con nombre + ajustes
        .timeout(t -> t.timeout(Duration.ofSeconds(2)))
        .bulkhead()                                          // valores por defecto
        .fallbackHandler(context -> Receipt.pending(context.attempts()))
        .build();

Receipt receipt = charge.execute(() -> client.charge(order));            // lanza lo que lanza charge
CompletableFuture<Receipt> later = charge.executeAsync(() -> client.chargeAsync(order));
charge.run(() -> client.cancel(order));                                  // una llamada sin resultado
```

- Cada política es opcional y se configura con los valores por defecto, una configuración con nombre, ajustes, o ambos. Las políticas se anidan en el mismo orden que con anotaciones (Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead), sin importar en qué orden se llamen los métodos del builder.
- El id (`"payments"`) nombra las instancias de las políticas: `registry.circuitBreaker("payments")` devuelve el breaker. Pipelines con el mismo id y la misma configuración comparten instancias; el mismo id con otra configuración es un error.
- `execute` declara la excepción checked de la llamada. Cuando los reintentos se agotan, lanza la falla propia de la llamada o un rechazo (`CallNotPermittedException`, `RateLimitExceededException`, `CallTimeoutException`, `BulkheadFullException`), o una `ResultRejectedException` con `onExhaustedResult(THROW)`.
- `classifier(...)` (o `retryClassifier` / `circuitBreakerClassifier`) decide qué se reintenta y qué se registra, incluidos los resultados, con las mismas reglas que el atributo de la anotación.
- `fallback(Function<Throwable, T>)` y `fallbackHandler(FallbackHandler<T>)` corren para las fallas elegidas con `fallbackApplyOn` / `fallbackSkipOn`.
- `executeAsync` usa la forma async no bloqueante de cada política.
- El camino exitoso sincrónico no asigna memoria más allá de tu propia lambda. Aun así, una sola clase `Pipeline` atiende todos los puntos de llamada, así que su llamada a tu lambda se vuelve megamórfica en una aplicación con muchos pipelines: las anotaciones siguen siendo la opción más rápida donde se pueden usar.
- `run(PipelineRunnable<X>)` protege una llamada sin resultado, con las mismas políticas y semántica y sin asignar memoria en el éxito; un classifier recibe `null` como resultado y el resultado del fallback se descarta.
- `onExhaustedResult(Pipeline.ExhaustedResult.THROW)` (necesita un classifier de retry) lanza una `ResultRejectedException` cuando los reintentos se agotan con un resultado rechazado, como el atributo de la anotación.
- `fallbackAsync(Function<Throwable, CompletionStage<T>>)` / `fallbackAsyncHandler(FallbackHandler<CompletionStage<T>>)` le dan a `executeAsync` un fallback que devuelve un stage. Combinado con `fallback`, cada modo usa el suyo; con solo un fallback async, `execute` y `run` esperan su stage.

## Eventos e inspección del estado

Las políticas publican eventos tipados. Los listeners se registran en el builder del registry, para un record de evento, la interfaz de eventos de una política o `ResilienceEvent` para todos, y opcionalmente para una sola instancia:

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(CircuitBreakerEvent.StateTransition.class,
                t -> alerts.send(t.instance() + ": " + t.from() + " -> " + t.to()))
        .listen("payments", RetryEvent.class, e -> log.debug("{}", e))
        .listen(ResilienceEvent.class, e -> {
            switch (e) {
                case BulkheadEvent.Rejected r -> metrics.bulkheadRejected(r.instance());
                case FallbackEvent.Applied f -> log.warn("fallback for {}", f.instance(), f.failure());
                default -> { }
            }
        })
        .build();
```

| Evento | Cuándo |
|---|---|
| `CircuitBreakerEvent.StateTransition(from, to)` | Cada cambio de estado, publicado una sola vez por el thread que lo hizo |
| `CircuitBreakerEvent.CallNotPermitted(state)` | Una llamada rechazada con el breaker abierto, forzado a abierto o sin sondas half-open |
| `RetryEvent.Retrying(attempt, delay, failure)` | Un intento fallido que se va a reintentar |
| `RetryEvent.GaveUp(attempts, reason, failure)` | No hay más intentos: `MAX_ATTEMPTS`, `MAX_DURATION`, `NOT_RETRYABLE`, `INTERRUPTED` o `CLASSIFIER_FAILED` (el classifier lanzó una excepción; `failure` es esa excepción) |
| `TimeoutEvent.TimedOut(timeout)` | Una llamada superó su timeout |
| `BulkheadEvent.Rejected` / `RateLimiterEvent.Rejected` | Se rechazó una llamada |
| `FallbackEvent.Applied(failure)` | El fallback corre para una falla |

Todo evento lleva `instance()` (el `name`, o `<interfaz>#<método>(<tipos de parámetros>)`) y `nanoTime()`. Las llamadas exitosas no publican nada; para eso está el snapshot.

- **Sin costo si no se usa.** Los listeners quedan fijos al construir el registry (no se pueden registrar después, igual que el resto de la configuración). Cada política busca sus listeners una vez, al crearse, así que un tipo de evento que nadie escucha nunca asigna memoria, ni toma locks, ni lee el reloj.
- **Sincrónicos.** Los listeners corren en el thread que causó el evento: el del llamador en llamadas sincrónicas, un thread virtual de la librería en timeouts async y reintentos diferidos. Tienen que ser rápidos y no bloquear; el trabajo lento va a una cola.
- **Aislados.** Un listener que lanza una excepción no afecta a la llamada ni a los otros listeners: su excepción va al uncaught exception handler del thread.
- Los eventos publicados desde threads distintos pueden llegar desordenados; cada transición llega exactamente una vez.

`registry.snapshot()` devuelve records de solo lectura de todas las instancias creadas hasta ahora, para health checks y lecturas de métricas. Puede asignar memoria y recorrer las ventanas, y no le agrega nada a las llamadas:

```java
ResilienceSnapshot s = registry.snapshot();
for (CircuitBreakerSnapshot cb : s.circuitBreakers()) {
    System.out.printf("%s %s failureRate=%.1f%% calls=%d%n", cb.instance(), cb.state(), cb.failureRate(), cb.bufferedCalls());
}
s.bulkheads().forEach(b -> gauge(b.instance(), b.concurrentCalls(), b.waitingCalls(), b.maxConcurrent()));
s.rateLimiters().forEach(r -> gauge(r.instance(), r.availablePermits()));
```

Las tasas son porcentajes, o `-1` hasta que la ventana tiene `minimumCalls` llamadas (la tasa de llamadas lentas también es `-1` si la detección de llamadas lentas está apagada). Los retries y timeouts muestran su configuración resuelta. Para una sola instancia: `registry.circuitBreaker(id).snapshot()`, `bulkhead(id).snapshot()` y `rateLimiter(id).snapshot()`.

## Tests

Agregá `testImplementation("dev.resilience4cata:resilience4cata-testkit")`. Un `ManualClock` reemplaza el reloj del sistema y los timers de la librería en un registry, así los tests mueven el tiempo a mano en lugar de esperar:

```java
ManualClock clock = new ManualClock();
ResilienceRegistry registry = ResilienceTesting.registry(clock).build(); // también fija la semilla del jitter en 42
Inventory inventory = Resilient.of(Inventory.class, stub, registry);

inventory.fetch("a");                  // @Retry(delay = "1s"): 3 fallas y después éxito; vuelve al instante
assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(3));

clock.advance(Duration.ofSeconds(30)); // un breaker abierto con waitInOpen = 30s deja pasar la siguiente llamada como sonda
```

- Las esperas (backoff de reintentos, esperas del rate limiter) avanzan el reloj en vez de bloquear.
- Las esperas del breaker, las llamadas lentas, los permisos del rate limiter y el `maxDuration` del retry se miden con el reloj.
- El backoff de reintentos async, las esperas async del rate limiter, los límites de espera async del bulkhead y los timeouts async corren en el thread que llama a `advance`, antes de que vuelva.
- Los timeouts sincrónicos disparan exactamente cuando el reloj pasa el plazo (sin holgura). Un stub que simula una dependencia lenta con `clock.sleep(Duration.ofSeconds(5))` termina en `CallTimeoutException`.
- Un bulkhead sincrónico con `maxWait` espera con el reloj: un llamador bloqueado obtiene un lugar apenas se libera uno, y se rechaza cuando el reloj pasa `maxWait`, ya sea que lo avance otro thread o que la llamada que ocupa el lugar duerma sobre él con `clock.sleep(...)`. Espera mientras el reloj no se mueva.
- `ResilienceRegistry.builder().random(new Random(seed))` hace reproducible el jitter sin el testkit. El generador tiene que ser thread-safe (`Random` lo es, `SplittableRandom` no).
- `clock.advanceUntilIdle()` corre todas las tareas diferidas pendientes; `ResilienceTesting.elapseWaitInOpen(breaker, clock)` pasa la espera en abierto de un breaker.
- El reloj es una SPI (`dev.resilience4cata.Ticker`, se configura con `Builder.ticker(...)`), así que se pueden enchufar otros relojes de test.
- Límites: un sleep que termina en timeout igual avanza el reloj su duración completa. Con varios threads avanzando el mismo reloj, el orden de las tareas no es determinista.

## Contrato de rendimiento

Objetivos a verificar con JMH desde la primera versión ([01 §4.1](research/01-landscape.md), [03 §10](research/03-performance-internals.md)):

| Objetivo | Cómo se verifica |
|---|---|
| **0 B/op** en el camino exitoso de cada política y de una cadena completa | JMH `-prof gc`, como control en CI |
| Sin locks ni `synchronized` en el camino caliente | Revisión de código, ArchUnit, escenarios JMH con contención |
| Código generado lineal y monomórfico por método; sin cadenas de lambdas compartidas | JMH con perfil de JIT contaminado, `-XX:+PrintInlining` |
| Un par de lecturas de reloj por llamada, sin importar cuántas políticas haya | Revisión de código |
| Excepciones de rechazo preasignadas y sin stack trace; eventos creados solo si alguien escucha | Escenarios JMH de rechazo |
| Estructuras sin locks correctas | jcstress |
| Comparación publicada y reproducible contra Resilience4j 2.4.0 | 11 escenarios JMH ([03 §9](research/03-performance-internals.md)) |

Las mediciones actuales están en [benchmarks](benchmarks.md). La mayoría de las llamadas reales dependen de I/O (milisegundos), así que la propuesta es "overhead casi nulo que escala con los threads, más anotaciones sin framework", no solo velocidad bruta.
