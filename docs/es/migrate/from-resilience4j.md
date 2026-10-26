# Migrar desde Resilience4j

Cómo se corresponden los registries, configs, decoradores y anotaciones de Spring de Resilience4j con resilience4cata, qué no tiene equivalente y qué se comporta distinto.

## ¿Deberías migrar? :id=when

resilience4cata cubre seis políticas (retry, circuit breaker, rate limiter, timeout, bulkhead, fallback) para Java 21+ puro, con código generado en tiempo de compilación. **Quédate en Resilience4j** si necesitas algo de esto, que resilience4cata todavía no tiene:

- integración con Spring Boot 3, CDI o AspectJ (resilience4cata se integra solo con Spring Boot 4.1+, [más abajo](#spring-boot));
- operadores de Reactor, RxJava o corrutinas de Kotlin (resilience4cata solo admite `CompletionStage` / `CompletableFuture`);
- un bulkhead con pool de threads, una caché o un time limiter para futures arbitrarios;
- cambiar la configuración de una política en tiempo de ejecución (resilience4cata resuelve la configuración una sola vez, por diseño);
- una versión de Java anterior a la 21.

**Considera migrar** si te importan el overhead y la asignación de memoria por llamada (consulta [Rendimiento](../performance.md)), si quieres detectar los errores de configuración al compilar, o si prefieres anotaciones simples y código generado que puedes leer en lugar de proxies de AOP.

## El modelo mental :id=mental-model

| | Resilience4j | resilience4cata |
|---|---|---|
| Dónde viven las políticas | decoradores (`Decorators.ofSupplier(…)`, `Retry.decorateSupplier(…)`) o aspectos de Spring AOP alrededor de un bean | una clase generada en tiempo de compilación: `<Interface>$Resilient` envuelve tu implementación, o `<Class>$Resilient` extiende tu clase |
| Cuándo se detecta un error de configuración | al arrancar o en la primera llamada | en su mayoría al compilar ([códigos de error](../errors.md)) |
| Orden de las políticas | el orden en que compones los decoradores; con Spring, un orden fijo de aspectos (configurable) | fijo: Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → método |
| Registries | uno por política (`CircuitBreakerRegistry`, `RetryRegistry`, …) | un solo `ResilienceRegistry` para todas las políticas |
| Instancias | se buscan por nombre | una por método anotado de forma predeterminada; los métodos con el mismo `name` comparten una |

## Correspondencia de conceptos :id=concepts

| Resilience4j | resilience4cata |
|---|---|
| `CircuitBreakerRegistry.of(config)`, `RetryRegistry.of(…)`, … | `ResilienceRegistry.builder()…build()` |
| `CircuitBreakerConfig.custom()…build()` registrado como config con nombre | `registryBuilder.circuitBreaker("name", b -> b…)`, usado con `@CircuitBreaker(config = "name")`; o `configs.<name>.circuitBreaker.*` en properties |
| config predeterminada (`configs.default` en Spring) | `registryBuilder.circuitBreakerDefaults(b -> …)` o `defaults.circuitBreaker.*` |
| `registry.circuitBreaker("inventory")` + `decorateSupplier` | `@CircuitBreaker(name = "inventory")` en el método de la interfaz + `Resilient.of(…)` |
| `Decorators.ofSupplier(…).withRetry(…).withCircuitBreaker(…)` para código que no puedes anotar | [`Pipeline.builder(registry, id).retry().circuitBreaker()…build()`](../guides/pipeline.md) |
| `@CircuitBreaker(name = "x", fallbackMethod = "f")` de Spring | `@CircuitBreaker(name = "x")` + `@Fallback(method = "f")` |
| `@Retry`, `@RateLimiter`, `@Bulkhead`, `@TimeLimiter` de Spring | `@Retry`, `@RateLimiter`, `@Bulkhead`, `@Timeout` (paquete `dev.resilience4cata.annotation`) |
| `resilience4j.circuitbreaker.instances.x.*` | `instances.x.circuitBreaker.*` ([claves](../reference/configuration.md)) |
| `baseConfig: other` | `configs.<name>.extends=other` |
| `circuitBreaker.getEventPublisher().onStateTransition(…)` | `registryBuilder.listen(CircuitBreakerEvent.StateTransition.class, …)` ([eventos](../guides/observability.md#events)) |
| `circuitBreaker.getMetrics()` | `registry.circuitBreaker(id).snapshot()` |
| `transitionToForcedOpenState()`, `transitionToDisabledState()`, `reset()` | `forceOpen()`, `disable()`, `reset()` en `CircuitBreakerHandle` |
| `resilience4j-micrometer` (`TaggedCircuitBreakerMetrics`, …) | `resilience4cata-micrometer` ([Micrometer](../guides/micrometer.md)); los nombres de los meters cambian (`r4c.*`), así que los dashboards y las alertas necesitan consultas nuevas |
| `RetryConfig.retryOnResult(…)`, `CircuitBreakerConfig.recordResult(…)` | un [`Classifier`](../guides/classifiers.md) en `@Retry` / `@CircuitBreaker` |
| `failAfterMaxAttempts(true)` (`MaxRetriesExceededException`) | `@Retry(onExhaustedResult = THROW)` (`ResultRejectedException`) |
| `IntervalFunction.ofExponentialBackoff(…)`, `ofExponentialRandomBackoff(…)` | `backoff`, `multiplier`, `maxDelay`, `jitter` |
| `TimeLimiter` + un executor para código bloqueante | `@Timeout` en el thread de quien llama |
| `resilience4j-hedge`: `Hedge.of(config).decorateCompletionStage(…)` | [`@Hedge`](../policies/hedge.md) en un método que devuelve un `CompletableFuture`, o `Pipeline…hedge(…)` con `executeAsync` ([correspondencia](#map-hedge)) |
| `ThreadPoolBulkhead`, `Cache`, operadores de Reactor/RxJava/Kotlin | sin equivalente |

## Correspondencia de configuración :id=config-mapping

Los métodos de los builders de Resilience4j (las properties de Spring Boot usan los mismos nombres en kebab-case) y el atributo de resilience4cata con el mismo papel. Los valores predeterminados difieren en algunos casos; las columnas **Predeterminado** son las de cada biblioteca.

### Circuit breaker :id=map-circuit-breaker

| Resilience4j | Predeterminado | resilience4cata | Predeterminado |
|---|---|---|---|
| `failureRateThreshold` | 50 | `failureRateThreshold` | 50 |
| `slowCallRateThreshold` | 100 | `slowCallRateThreshold` | 100 |
| `slowCallDurationThreshold` | 60 s (las llamadas siempre se cronometran) | `slowCallDuration` | 0 = desactivado (las llamadas no se cronometran) |
| `slidingWindowType` | `COUNT_BASED` | `windowType` | `COUNT` |
| `slidingWindowSize` | 100 | `windowSize` | 100 |
| `minimumNumberOfCalls` | 100 | `minimumCalls` | 100 |
| `waitDurationInOpenState` | 60 s | `waitInOpen` | 60 s |
| `permittedNumberOfCallsInHalfOpenState` | 10 | `halfOpenCalls` | 10 |
| `maxWaitDurationInHalfOpenState` | 0 | `maxWaitInHalfOpen` | 0 |
| `recordExceptions` / `recordException(predicate)` | todas las excepciones | `recordOn` (clases) o `classifier` | `Exception` |
| `ignoreExceptions` | ninguna | `ignoreOn` | ninguna |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `false` | sin equivalente: pasa a semiabierto en la siguiente llamada | — |
| `waitIntervalFunctionInOpenState`, `transitionOnResult`, `initialState`, `slidingWindowSynchronizationStrategy` | | sin equivalente | — |
| `writableStackTraceEnabled` | `true` | no hace falta: los rechazos siempre son preasignados y sin stack trace | — |

### Retry :id=map-retry

| Resilience4j | Predeterminado | resilience4cata | Predeterminado |
|---|---|---|---|
| `maxAttempts` | 3 | `maxAttempts` | 3 |
| `waitDuration` | 500 ms, fijo | `delay` (+ `backoff = FIXED, jitter = NONE` para el mismo comportamiento) | 200 ms, exponencial, jitter completo |
| `intervalFunction(IntervalFunction.ofExponentialBackoff(…))` | | `backoff = EXPONENTIAL`, `multiplier`, `maxDelay` | ×2, máx. 10 s |
| `IntervalFunction.ofRandomized(…)`, `ofExponentialRandomBackoff(…)` | | `jitter = FULL` o `EQUAL` | `FULL` |
| `retryExceptions` / `retryOnException(predicate)` | todas las excepciones | `retryOn` o `classifier` | `Exception` |
| `ignoreExceptions` | ninguna | `abortOn` | — (un breaker abierto nunca se reintenta de todos modos) |
| `retryOnResult(predicate)` | | `classifier` | — |
| `failAfterMaxAttempts` | `false` | `onExhaustedResult = THROW` | `RETURN` |
| sin equivalente | | `maxDuration` (presupuesto de tiempo para todos los intentos) | 0 = ilimitado |

### Rate limiter, timeout, bulkhead :id=map-others

| Resilience4j | Predeterminado | resilience4cata | Predeterminado |
|---|---|---|---|
| `RateLimiterConfig.limitForPeriod` | 50 | `@RateLimiter(permits)` | 50 |
| `RateLimiterConfig.limitRefreshPeriod` | 500 ns | `@RateLimiter(period)` | 1 s |
| `RateLimiterConfig.timeoutDuration` | 5 s | `@RateLimiter(maxWait)` | 0 = rechaza de inmediato |
| `RateLimiterConfig.drainPermissionsOnResult` | | sin equivalente | — |
| `TimeLimiterConfig.timeoutDuration` | 1 s | `@Timeout(value)` | 1 s |
| `TimeLimiterConfig.cancelRunningFuture` | `true` | siempre: el stage de una llamada async se cancela si es un `Future` | — |
| `BulkheadConfig.maxConcurrentCalls` | 25 | `@Bulkhead(maxConcurrent)` | 25 |
| `BulkheadConfig.maxWaitDuration` | 0 | `@Bulkhead(maxWait)` | 0 |
| `BulkheadConfig.fairCallHandlingStrategyEnabled` | `true` | sin equivalente: el semáforo de espera no es justo | — |

> [!WARNING]
> Tres valores predeterminados suelen sorprender: el retry de resilience4cata usa **backoff exponencial con jitter completo desde 200 ms**, no un valor fijo de 500 ms; su rate limiter **rechaza de inmediato** en lugar de esperar hasta 5 s; y su breaker **no cronometra las llamadas** a menos que configures `slowCallDuration`. Configúralos explícitamente al migrar.

### Hedge :id=map-hedge

El módulo `resilience4j-hedge` de Resilience4j (2.x) es programático y solo async, como el nuestro (sin anotación de Spring), y lanza como mucho un hedge.

| Resilience4j | resilience4cata | Notas |
|---|---|---|
| `HedgeConfig.Builder.preconfiguredDuration(d)` | `@Hedge(delay = "…")` | El nuestro vale 1 s por defecto; `"0"` lanza todos los intentos a la vez |
| `averagePlusPercentageDuration(…)`, `averagePlusAmountDuration(…)` | sin equivalente | Solo una espera fija; ponla cerca del p95 de la dependencia |
| un hedge, siempre | `@Hedge(maxAttempts = 2)` (el valor por defecto) | Hasta 10 intentos |
| `withMaxConcurrency(n)` (tamaño base del pool de threads del hedge) | sin equivalente | Los hedges empiezan en virtual threads nuevos; el [presupuesto de hedge](../policies/hedge.md#el-presupuesto-de-hedge) limita su número en su lugar |
| `withContextPropagators(…)` | sin equivalente | Los intentos 2+ corren sin los `ThreadLocal` de quien llama |
| `submit(callable, executor)` para código bloqueante | sin equivalente | Los métodos sincrónicos son un error de compilación ([R4C-HDG-001](../errors.md#r4c-hdg-001)): devuelve un `CompletableFuture` |
| `HedgeOnPrimarySuccessEvent`, `…OnSecondarySuccessEvent`, `…Failure…` | `HedgeEvent.Hedged`, `Won`, `Skipped`, `Failed` | Un primer intento que gana antes de la espera no publica nada |

La semántica difiere: en Resilience4j gana el primer intento que **termina**, así que una falla rápida de cualquiera de los dos hace fallar la llamada; en resilience4cata gana el primer **éxito**, una falla de `hedgeOn` lanza el siguiente intento enseguida, y la llamada solo falla cuando todos los intentos han fallado (o uno falla con una falla de `abortOn`). El perdedor se cancela, y el circuit breaker, el bulkhead y el timeout dentro del hedge liberan lo que tenía.

## Diferencias de comportamiento :id=differences

### El orden es fijo :id=order

Fallback → Retry (o Hedge) → CircuitBreaker → RateLimiter → Timeout → Bulkhead → método, sin importar el orden de las anotaciones. Coincide con el orden predeterminado de aspectos de Spring en Resilience4j para las políticas que ambas tienen. No hay `@Order` ni reordenamiento por método; para otro orden, usa dos interfaces o un [`Pipeline`](../guides/pipeline.md) dentro de un método envuelto.

### Un breaker abierto detiene los retries :id=open-breaker

Un retry nunca reintenta `CallNotPermittedException`, diga lo que diga su `abortOn`, así que en cuanto el breaker se abre, el retry se rinde de inmediato en lugar de seguir reintentando contra él. En Resilience4j tienes que agregar tú mismo esa excepción a `ignoreExceptions`.

### Los errores no se registran ni se reintentan de forma predeterminada :id=errors

`recordOn` y `retryOn` tienen como valor predeterminado `Exception`: un `Error` (por ejemplo `OutOfMemoryError`, `AssertionError`) no se reintenta y cuenta como **éxito** para el breaker. `@Fallback(applyOn)` tiene como valor predeterminado `Throwable`, así que un fallback sí captura los `Error`s a menos que los agregues a `skipOn`.

### Los timeouts interrumpen el thread de quien llama :id=timeouts

Un `@Timeout` síncrono no mueve la llamada a otro thread: se ejecuta donde la hiciste, y un watchdog compartido la interrumpe cuando se acaba el tiempo. El código que ignora las interrupciones se ejecuta hasta terminar antes de que quien llama reciba `CallTimeoutException`. Los timeouts nunca se disparan antes de tiempo y como mucho llegan unos dos ticks del watchdog tarde (ticks de 1–50 ms). Consulta [Timeout](../policies/timeout.md).

### Los breakers pasan a semiabierto en la siguiente llamada :id=half-open

No hay ningún temporizador que pase un breaker abierto a semiabierto: lo hace la primera llamada después de `waitInOpen`. Un snapshot tomado entre medio sigue diciendo `OPEN`.

### La configuración se resuelve una sola vez :id=no-reload

Los valores de las anotaciones, las configs con nombre y la configuración externa se combinan cuando se crea la instancia y nunca cambian después. No hay `changeConfig`, ni evento de refresco, ni recarga en caliente. Los listeners también quedan fijos cuando se construye el registry.

### Autoinvocación en el estilo de interfaz :id=self-invocation

Con `Resilient.of`, una llamada de tu implementación a uno de sus propios métodos se salta las políticas, como un proxy de Spring. Las clases anotadas (el estilo de subclase) sí interceptan `this.method()`. Consulta [Clases anotadas](../guides/annotated-classes.md).

### Las instancias son por método salvo que les des nombre :id=instances

Una instancia de Resilience4j la comparte todo lo que usa su nombre. En resilience4cata cada método anotado obtiene sus propias instancias, con un id como `com.acme.StockClient#stock(java.lang.String)`; da el mismo `name` a varios métodos para que compartan una (sus configuraciones resueltas deben ser entonces idénticas, [R4C-REG-008](../errors.md#r4c-reg-008)). `@Timeout` no tiene `name`.

### El procesador es obligatorio :id=processor

Sin el procesador de anotaciones no se genera nada y `Resilient.of` falla con [R4C-RT-003](../errors.md#r4c-rt-003). No hay alternativa por reflexión ni por proxy.

## El mismo servicio, de las dos formas :id=example

Una consulta de stock con un retry (3 intentos, separados por 500 ms, solo ante `IOException`), un breaker compartido con el nombre `inventory`, un timeout de 2 s y un fallback.

**Resilience4j**, con decoradores:

<!-- not-compiled: Resilience4j code -->
```java
CircuitBreaker breaker = CircuitBreaker.of("inventory", CircuitBreakerConfig.custom()
        .failureRateThreshold(50)
        .slidingWindowSize(20)
        .minimumNumberOfCalls(10)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .permittedNumberOfCallsInHalfOpenState(3)
        .build());
Retry retry = Retry.of("inventory", RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(500))
        .retryExceptions(IOException.class)
        .ignoreExceptions(CallNotPermittedException.class)
        .build());
TimeLimiter timeLimiter = TimeLimiter.of(Duration.ofSeconds(2));
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

Supplier<CompletionStage<Integer>> decorated = Decorators
        .ofCompletionStage(() -> CompletableFuture.supplyAsync(() -> client.stock(sku), scheduler)) // a blocking call moved to a thread
        .withTimeLimiter(timeLimiter, scheduler)
        .withCircuitBreaker(breaker)
        .withRetry(retry, scheduler)
        .withFallback(List.of(Exception.class), e -> -1)
        .decorate();
int units = decorated.get().toCompletableFuture().join();
```

**resilience4cata**: las anotaciones en la interfaz,

<!-- snippet: migrate-r4c-interface -->
```java
public interface StockClient {

    @Fallback(method = "unknownStock")
    @Retry(name = "inventory", maxAttempts = 3, delay = "500ms", backoff = Backoff.FIXED, jitter = Jitter.NONE,
            retryOn = IOException.class)
    @CircuitBreaker(name = "inventory", failureRateThreshold = 50, windowSize = 20, minimumCalls = 10,
            waitInOpen = "30s", halfOpenCalls = 3)
    @Timeout("2s")
    int stock(String sku) throws IOException;

    default int unknownStock(String sku, Exception failure) {
        return -1;
    }
}
```

y el punto de llamada:

<!-- snippet: migrate-r4c-use -->
```java
ResilienceRegistry registry = ResilienceTesting.registry(clock).build(); // ResilienceRegistry.builder() in production
StockClient stock = Resilient.of(StockClient.class, sku -> {
    calls.incrementAndGet();
    throw new IOException("inventory unreachable");
}, registry);

int units = stock.stock("SKU-1"); // three attempts 500 ms apart, then the fallback
```

La llamada síncrona sigue siendo síncrona: no hay executor, porque el timeout interrumpe el thread de quien llama en lugar de mover la llamada a otro.

**Configuración externa.** Las claves de `application.yml` de Spring Boot y sus equivalentes de resilience4cata en un archivo `.properties` (`instances.inventory.*` sobrescribe la anotación para esa instancia; `defaults.*` solo completa los atributos que la anotación no define):

<!-- snippet: migrate-properties -->
```properties
# resilience4j.circuitbreaker.instances.inventory.sliding-window-size=50
instances.inventory.circuitBreaker.windowSize=50
# resilience4j.circuitbreaker.instances.inventory.wait-duration-in-open-state=10s
instances.inventory.circuitBreaker.waitInOpen=10s
# resilience4j.retry.instances.inventory.max-attempts=5
instances.inventory.retry.maxAttempts=5
# resilience4j.circuitbreaker.configs.default.failure-rate-threshold=40
defaults.circuitBreaker.failureRateThreshold=40
```

Cárgalo con `ResilienceConfig.load()` (como `resilience4cata.properties` en el classpath) o con `ResilienceConfig.builder().classpath(…)`, y luego `config.applyTo(ResilienceRegistry.builder())`. Consulta [Configuración](../guides/configuration.md).

## Desde resilience4j-spring-boot3 :id=spring-boot

`resilience4cata-spring` reemplaza a `resilience4j-spring-boot3` en Spring Boot 4.1 o posterior
([guía de Spring Boot](../guides/spring.md)). Quita `spring-boot-starter-aop`: no se teje ni se envuelve nada con
proxies; el procesador de anotaciones genera las clases protegidas y la integración hace que Spring las instancie.

| resilience4j-spring-boot3 | resilience4cata-spring |
|---|---|
| `resilience4j.<module>.instances.<name>.<key>` | `resilience4cata.instances.<name>.<policy>.<attribute>` (`circuit-breaker`, `retry`, `rate-limiter`, `timeout`, `bulkhead`) |
| `resilience4j.<module>.configs.default.<key>` | `resilience4cata.defaults.<policy>.<attribute>` |
| `resilience4j.<module>.configs.<name>.<key>` | `resilience4cata.configs.<name>.<policy>.<attribute>`, usada por `@Retry(config = "<name>")` y similares |
| `baseConfig: <name>` | `resilience4cata.configs.<config>.extends: <name>`; una anotación elige su config con `config = "<name>"` |
| propiedades `<module>AspectOrder` | ninguna: el orden es fijo (Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead) |
| beans `CircuitBreakerConfigCustomizer`, `RetryConfigCustomizer`… | beans `ResilienceRegistryCustomizer` |
| beans `RegistryEventConsumer`, `getEventPublisher()` | beans `EventListener<…>` |
| `/actuator/circuitbreakers`, `/actuator/retries`, `/actuator/ratelimiters`… | `/actuator/resilience4cata` (todas las políticas, solo lectura) |
| `/actuator/circuitbreakerevents` y los demás endpoints de eventos | ninguno: no se guarda historial de eventos; usa beans listener o métricas |
| indicadores de salud, desactivados por defecto (`registerHealthIndicator`) | el indicador `resilience4cata`, activo pero UP con breakers abiertos salvo los listados en `resilience4cata.spring.health.down-when-open` |
| métricas `resilience4j.circuitbreaker.*`… | métricas `r4c.*` ([Micrometer](../guides/micrometer.md)), con `resilience4cata-micrometer` |
| `fallbackMethod = "f"` resuelto por reflexión | `@Fallback(method = "f")`, comprobado al compilar |

Las mismas claves en `application.yml`:

```yaml
# resilience4j.circuitbreaker.instances.inventory.sliding-window-size: 50
# resilience4j.circuitbreaker.configs.default.failure-rate-threshold: 40
# resilience4j.retry.instances.inventory.max-attempts: 5
resilience4cata:
  defaults:
    circuit-breaker:
      failure-rate-threshold: 40
  instances:
    inventory:
      circuit-breaker:
        window-size: 50
      retry:
        max-attempts: 5
```

Las erratas detienen el arranque con el fichero y la línea de la clave, en lugar de ignorarse.

## Paso a paso :id=steps

1. Agrega las dependencias, incluido el procesador en el processor path ([Instalación](../start/installation.md)); con Spring Boot, `resilience4cata-spring` ([más arriba](#spring-boot)).
2. Para cada llamada decorada, pon la llamada detrás de una interfaz (o anota la clase) y traduce la configuración con las tablas anteriores. Define `backoff`, `jitter` y `maxWait` explícitamente donde los valores predeterminados difieren.
3. Reemplaza `Decorators`/las anotaciones de Spring por las anotaciones de resilience4cata y `Resilient.of` (o `Resilient.create` para clases anotadas). Las llamadas que no puedas poner detrás de una interfaz pasan por un [`Pipeline`](../guides/pipeline.md).
4. Mueve los consumidores de eventos a `ResilienceRegistry.Builder.listen(…)`, y las métricas al [módulo de Micrometer](../guides/micrometer.md).
5. Reemplaza las pruebas basadas en tiempo por [`ManualClock`](../guides/testing.md).
6. Compila con `-Xlint:all -Werror` si quieres: el código generado compila sin advertencias.
