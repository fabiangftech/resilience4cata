# Pruebas

Prueba retries, breakers, límites de tasa, timeouts y bulkheads en milisegundos, con tiempos exactos, dándole al registry un reloj que solo avanza cuando la prueba lo indica.

## Por qué un reloj manual :id=why

La resiliencia trata sobre todo del tiempo: un retry espera 1 s, un breaker permanece abierto 30 s, un rate limiter entrega un permiso cada 500 ms. Las pruebas que usan el reloj real son lentas, y las que acortan cada demora para ser rápidas dejan de probar la configuración que despliegas.

`resilience4cata-testkit` tiene `ManualClock`, un reloj que solo avanza cuando la prueba lo hace avanzar. Pásalo a un registry y todas las políticas de ese registry lo siguen:

- **Las esperas con sleep avanzan el reloj en lugar de bloquear.** Un retry con una demora de 1 s retorna de inmediato, con el reloj 1 s más adelante.
- **Las esperas se miden en él.** Un breaker permanece abierto hasta que la prueba avanza más allá de `waitInOpen`.
- **Las tareas diferidas se ejecutan cuando el reloj pasa su plazo**, en el thread que llama a `advance`, antes de que retorne: retries async, esperas async del rate limiter, límites de espera async del bulkhead y timeouts async.
- **Los timeouts sync se disparan exactamente en su plazo**, interrumpiendo el thread medido.
- **Las esperas sync del bulkhead (`maxWait`) terminan en el reloj.**

Las anotaciones y el código generado son los mismos que en producción; solo cambia el registry.

## Dependencia :id=dependency

testkit depende solo de `resilience4cata-core` y no depende de JUnit, así que funciona con cualquier framework de pruebas.

<!-- tabs:start -->
#### **Gradle**
```groovy
dependencies {
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'   // version from the BOM
}
```
#### **Gradle (Kotlin)**
```kotlin
dependencies {
    testImplementation("dev.resilience4cata:resilience4cata-testkit")   // version from the BOM
}
```
#### **Maven**
```xml
<dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-testkit</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```
<!-- tabs:end -->

Sin el BOM, agrega la versión (`0.1.0`) también en Gradle. Si anotas interfaces bajo `src/test`, el procesador también debe ejecutarse sobre las fuentes de prueba: `testCompileOnly` para las anotaciones y `testAnnotationProcessor` para el procesador (consulta [Instalación](../start/installation.md)).

## Tu primera prueba :id=first-test

Una interfaz con demoras reales de producción: cuatro intentos, con 1 s entre cada uno.

<!-- snippet: testing-first-interface -->
```java
public interface QuoteService {

    @Retry(maxAttempts = 4, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    String quote(String sku);
}
```

La prueba construye el registry con `ResilienceTesting.registry(clock)` en lugar de `ResilienceRegistry.builder()`, envuelve un stub que falla tres veces y verifica tanto el resultado como cuánto tiempo pasó en el reloj:

<!-- snippet: testing-first-test -->
```java
@Test
void retriesThreeTimesWithoutWaiting() {
    ManualClock clock = new ManualClock();
    ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    AtomicInteger calls = new AtomicInteger();
    QuoteService quotes = Resilient.of(QuoteService.class, sku -> {
        if (calls.incrementAndGet() < 4) {
            throw new IllegalStateException("upstream down");
        }
        return "quote for " + sku;
    }, registry);

    assertThat(quotes.quote("A-1")).isEqualTo("quote for A-1");

    assertThat(calls).hasValue(4);
    assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(3)); // three 1 s delays, no real waiting
}
```

`ResilienceTesting.registry(clock)` devuelve un `ResilienceRegistry.Builder` normal con el reloj como ticker y un `java.util.Random` inicializado con la semilla `ResilienceTesting.DEFAULT_SEED` (42) para el jitter, así que puedes seguir configurándolo (`.retry("payments", …)`, `.listen(…)`, `ResilienceConfig.applyTo(…)`). Es lo mismo que `ResilienceRegistry.builder().ticker(clock).random(new Random(42))`.

`ManualClock` en resumen: `read()` (nanosegundos, empezando en 0), `elapsed()`, `advance(Duration)`, `advanceUntilIdle()` (ejecuta todas las tareas pendientes, sin importar qué tan adelante estén, y devuelve cuánto avanzó el reloj), `sleep(Duration)` (para stubs: avanza el reloj en lugar de bloquear), `pendingTasks()`, `nextTaskIn()`. El reloj se conecta por el SPI `dev.resilience4cata.Ticker` (`ResilienceRegistry.Builder.ticker(…)`), así que otro reloj de prueba también puede implementarlo.

## Recetas :id=recipes

### Demoras y backoff del retry :id=retry

Registra cuándo ocurre cada intento, en el reloj de la prueba:

<!-- snippet: testing-retry-interface -->
```java
public interface BackoffService {

    // exponential backoff (the default): 1 s, 2 s, then capped at 3 s
    @Retry(maxAttempts = 4, delay = "1s", maxDelay = "3s", jitter = Jitter.NONE)
    String load(String id);

    // the default FULL jitter: each delay is random between 0 and the backoff
    @Retry(maxAttempts = 4, delay = "1s")
    String loadJittered(String id);
}
```

<!-- snippet: testing-retry-stub -->
```java
static final class FailingBackoffService implements BackoffService {
    private final ManualClock clock;
    private final List<Duration> calledAt;

    FailingBackoffService(ManualClock clock, List<Duration> calledAt) {
        this.clock = clock;
        this.calledAt = calledAt;
    }

    @Override
    public String load(String id) {
        calledAt.add(clock.elapsed());
        throw new IllegalStateException("still down");
    }

    @Override
    public String loadJittered(String id) {
        return load(id);
    }
}
```

<!-- snippet: testing-retry-backoff -->
```java
@Test
void exponentialBackoffIsCappedAtMaxDelay() {
    ManualClock clock = new ManualClock();
    List<Duration> calledAt = new CopyOnWriteArrayList<>();
    BackoffService service = Resilient.of(BackoffService.class, new FailingBackoffService(clock, calledAt),
            ResilienceTesting.registry(clock).build());

    assertThatThrownBy(() -> service.load("x")).isInstanceOf(IllegalStateException.class);

    assertThat(calledAt).containsExactly(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(3),
            Duration.ofSeconds(6)); // delays of 1 s, 2 s, 3 s
}
```

Con `jitter = Jitter.NONE` las demoras son exactas. Con jitter, consulta [jitter con semilla](#seeded-jitter).

### Circuit breaker: abierto y luego semiabierto :id=circuit-breaker

<!-- snippet: testing-breaker-interface -->
```java
public interface InventoryService {

    @CircuitBreaker(name = "inventory", windowSize = 4, minimumCalls = 4, waitInOpen = "30s", halfOpenCalls = 1)
    int stock(String sku);
}
```

`ResilienceTesting.elapseWaitInOpen(breaker, clock)` avanza el reloj el `waitInOpen` del breaker (falla con `IllegalStateException` si el breaker no está `OPEN`). Después de eso el breaker sigue reportando `OPEN`: pasa a semiabierto cuando la siguiente llamada pide un permiso, no por un temporizador.

<!-- snippet: testing-breaker -->
```java
@Test
void theBreakerOpensThenLetsAProbeThrough() {
    ManualClock clock = new ManualClock();
    ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    AtomicBoolean down = new AtomicBoolean(true);
    InventoryService inventory = Resilient.of(InventoryService.class, sku -> {
        if (down.get()) {
            throw new IllegalStateException("inventory down");
        }
        return 7;
    }, registry);

    for (int i = 0; i < 4; i++) { // four failures out of four calls: 100% >= 50%
        assertThatThrownBy(() -> inventory.stock("X")).isInstanceOf(IllegalStateException.class);
    }
    CircuitBreakerHandle breaker = registry.circuitBreaker("inventory");
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);
    assertThatThrownBy(() -> inventory.stock("X")).isInstanceOf(CallNotPermittedException.class);

    down.set(false);
    ResilienceTesting.elapseWaitInOpen(breaker, clock); // clock.advance(30 s)
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN); // half-open only when a call arrives
    assertThat(inventory.stock("X")).isEqualTo(7); // the probe succeeds
    assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
}
```

Para forzar un estado sin generar llamadas, usa el handle: `breaker.forceOpen()`, `breaker.disable()`, `breaker.reset()`. Están en `core` y también funcionan sin testkit.

### Rate limiter :id=rate-limiter

<!-- snippet: testing-rate-limiter-interface -->
```java
public interface SearchService {

    @RateLimiter(name = "search", permits = 2, period = "1s")
    String search(String query);

    @RateLimiter(name = "export", permits = 1, period = "1s", maxWait = "2s")
    String export(String query);
}
```

Los permisos se espacian `period / permits`, con una ráfaga de hasta `permits`. Quien llama con `maxWait` espera en el reloj por su permiso, así que la prueba ve la espera en `clock.elapsed()`:

<!-- snippet: testing-rate-limiter -->
```java
@Test
void theThirdCallInTheSameInstantIsRejected() {
    ManualClock clock = new ManualClock();
    ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    SearchService search = Resilient.of(SearchService.class, new EchoSearch(), registry);

    search.search("a");
    search.search("b"); // permits = 2: a burst of two passes at once
    assertThatThrownBy(() -> search.search("c")).isInstanceOf(RateLimitExceededException.class);

    clock.advance(Duration.ofMillis(500)); // one permit every period / permits = 500 ms
    assertThat(search.search("c")).isEqualTo("results for c");
}

@Test
void aCallerWithMaxWaitSleepsOnTheClock() {
    ManualClock clock = new ManualClock();
    SearchService search = Resilient.of(SearchService.class, new EchoSearch(),
            ResilienceTesting.registry(clock).build());

    search.export("a");
    assertThat(search.export("b")).isEqualTo("export of b"); // waited for the next permit

    assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(1));
}
```

### Timeout :id=timeout

Un `@Timeout` sync interrumpe el thread que ejecuta la llamada. En un reloj manual se dispara exactamente cuando el reloj pasa el plazo. Un stub que simula una dependencia lenta debe esperar **en el reloj** (`clock.sleep(…)`), para que el reloj avance y la interrupción le llegue:

<!-- snippet: testing-timeout-interface -->
```java
public interface ReportService {

    @Timeout("2s")
    String render(String id) throws InterruptedException;
}
```

<!-- snippet: testing-timeout -->
```java
@Test
void aSlowCallTimesOutOnTheClock() {
    ManualClock clock = new ManualClock();
    ReportService reports = Resilient.of(ReportService.class, id -> {
        clock.sleep(Duration.ofSeconds(5)); // a slow dependency, simulated on the test's clock
        return "report " + id;
    }, ResilienceTesting.registry(clock).build());

    assertThatThrownBy(() -> reports.render("r-1")).isInstanceOf(CallTimeoutException.class);
    assertThat(Thread.currentThread().isInterrupted()).isFalse(); // the interrupt is cleared
    assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(5)); // the sleep still advanced in full
}
```

El reloj igual avanza toda la espera (5 s aquí): el tiempo salta, no fluye.

### Bulkhead con `maxWait` :id=bulkhead

La forma async no necesita threads adicionales: una llamada que encuentra el bulkhead lleno se encola, y su límite de espera es una tarea en el reloj.

<!-- snippet: testing-async-interface -->
```java
public interface RenderService {

    @Bulkhead(name = "renderer", maxConcurrent = 1, maxWait = "30s")
    CompletableFuture<String> render(String id);

    @Retry(maxAttempts = 3, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    CompletableFuture<String> fetch(String id);
}
```

<!-- snippet: testing-bulkhead-async -->
```java
@Test
void aQueuedCallIsRejectedWhenTheClockPassesMaxWait() {
    ManualClock clock = new ManualClock();
    ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    CompletableFuture<String> slowRender = new CompletableFuture<>(); // completed by the test
    RenderService renderer = Resilient.of(RenderService.class, new RenderStub(slowRender), registry);

    CompletableFuture<String> first = renderer.render("slow"); // holds the only slot
    CompletableFuture<String> queued = renderer.render("next"); // waits up to 30 s, no thread blocked
    assertThat(registry.bulkhead("renderer").waitingCalls()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(30));

    assertThatThrownBy(queued::join).isInstanceOf(CompletionException.class)
            .hasCauseInstanceOf(BulkheadFullException.class);
    slowRender.complete("rendered slow");
    assertThat(first.join()).isEqualTo("rendered slow");
}
```

Un bulkhead **sync** con `maxWait` también termina en el reloj, pero quien espera es un thread bloqueado, así que la prueba necesita un segundo thread: inicia la llamada lenta y la llamada que espera en sus propios threads, espera (en tiempo real, con límite) hasta que `registry.bulkhead(id).waitingCalls()` sea 1, y luego `clock.advance(maxWait)`; quien espera es rechazado con `BulkheadFullException`, u obtiene el lugar en cuanto la primera llamada lo libera.

## Métodos async en el reloj :id=async

Las demoras de los retries async son tareas en el reloj. Cada `advance` ejecuta los intentos que vencen, en el thread de la prueba, antes de retornar; `advanceUntilIdle()` los ejecuta todos:

<!-- snippet: testing-async-retry -->
```java
@Test
void asyncRetriesRunWhenTheClockAdvances() {
    ManualClock clock = new ManualClock();
    RenderStub stub = new RenderStub(new CompletableFuture<>());
    stub.failures.set(2);
    RenderService renderer = Resilient.of(RenderService.class, stub, ResilienceTesting.registry(clock).build());

    CompletableFuture<String> result = renderer.fetch("a"); // first attempt fails, retry scheduled in 1 s
    assertThat(result).isNotDone();

    clock.advance(Duration.ofSeconds(1)); // second attempt runs here, on this thread, and fails
    assertThat(result).isNotDone();
    assertThat(clock.advanceUntilIdle()).isEqualTo(Duration.ofSeconds(1)); // runs the third attempt

    assertThat(result).isCompletedWithValue("fetched a");
}
```

## Jitter con semilla :id=seeded-jitter

Con jitter (el valor por defecto es `FULL`), las demoras son aleatorias. En un registry de testkit provienen de un `java.util.Random` con una semilla fija, así que la misma prueba da las mismas demoras en cada ejecución. `ResilienceTesting.registry(clock, seed)` elige otra semilla:

<!-- snippet: testing-seeded-jitter -->
```java
@Test
void theSameSeedGivesTheSameJitter() {
    assertThat(callTimesWithSeed(7)).isEqualTo(callTimesWithSeed(7));
    assertThat(callTimesWithSeed(7)).isNotEqualTo(callTimesWithSeed(8));
}

private static List<Duration> callTimesWithSeed(long seed) {
    ManualClock clock = new ManualClock();
    List<Duration> calledAt = new CopyOnWriteArrayList<>();
    BackoffService service = Resilient.of(BackoffService.class, new FailingBackoffService(clock, calledAt),
            ResilienceTesting.registry(clock, seed).build());
    assertThatThrownBy(() -> service.loadJittered("x")).isInstanceOf(IllegalStateException.class);
    return calledAt;
}
```

Los valores sorteados son deterministas solo cuando falla un thread a la vez: con varios threads fallando al mismo tiempo, qué llamada recibe qué demora depende de la planificación. Sin testkit, `ResilienceRegistry.builder().random(new Random(seed))` hace lo mismo (el generador debe ser thread-safe; `SplittableRandom` no lo es).

## Verificar eventos y snapshots :id=events-and-snapshots

Registra un listener en el builder (los listeners quedan fijos en `build()`) y verifica lo que recolectó. Los tiempos de los eventos (`nanoTime()`) son lecturas del reloj manual.

<!-- snippet: testing-events -->
```java
@Test
void eachRetryPublishesAnEvent() {
    ManualClock clock = new ManualClock();
    List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(ResilienceEvent.class, events::add) // listeners are fixed at build()
            .build();
    AtomicInteger calls = new AtomicInteger();
    QuoteService quotes = Resilient.of(QuoteService.class, sku -> {
        if (calls.incrementAndGet() < 3) {
            throw new IllegalStateException("upstream down");
        }
        return "ok";
    }, registry);

    quotes.quote("A-1");

    assertThat(events).hasSize(2).allSatisfy(e -> assertThat(e).isInstanceOf(RetryEvent.Retrying.class));
    RetryEvent.Retrying first = (RetryEvent.Retrying) events.get(0);
    assertThat(first.attempt()).isEqualTo(1);
    assertThat(first.delay()).isEqualTo(Duration.ofSeconds(1));
    assertThat(first.instance()).isEqualTo(
            "dev.resilience4cata.examples.docs.testing.QuoteService#quote(java.lang.String)");
}
```

Los snapshots y los handles muestran el estado sin generar más llamadas. Un breaker reporta las tasas como `-1` hasta que su ventana contiene `minimumCalls` llamadas:

<!-- snippet: testing-snapshot -->
```java
@Test
void theSnapshotShowsTheWindow() {
    ManualClock clock = new ManualClock();
    ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
    InventoryService inventory = Resilient.of(InventoryService.class, sku -> {
        throw new IllegalStateException("inventory down");
    }, registry);

    for (int i = 0; i < 2; i++) {
        assertThatThrownBy(() -> inventory.stock("X")).isInstanceOf(IllegalStateException.class);
    }

    CircuitBreakerSnapshot snapshot = registry.circuitBreaker("inventory").snapshot();
    assertThat(snapshot.state()).isEqualTo(CircuitBreakerState.CLOSED);
    assertThat(snapshot.bufferedCalls()).isEqualTo(2);
    assertThat(snapshot.failedCalls()).isEqualTo(2);
    assertThat(snapshot.failureRate()).isEqualTo(-1f); // not evaluated before minimumCalls = 4
}
```

Consulta [Observabilidad](observability.md) para ver cada campo de eventos y snapshots.

## Errores comunes :id=pitfalls

- **`Thread.sleep` en un stub no mueve el reloj.** Usa `clock.sleep(…)` para simular una dependencia lenta; una espera real hace lenta la prueba y un timeout nunca se dispara (el watchdog sigue al reloj manual).
- **Un registry por prueba.** Las instancias con el mismo `name` se comparten dentro de un registry, así que un breaker abierto por una prueba sigue abierto para la siguiente si comparten el registry.
- **Avanza desde un solo thread.** Dos threads que avanzan a la vez progresan ambos, pero entonces el orden de sus tareas no es determinista.
- **El breaker pasa a semiabierto en la siguiente llamada**, no cuando el reloj pasa `waitInOpen`; `state()` reporta `OPEN` hasta entonces.
- **Los listeners deben registrarse antes de `build()`.** No es posible agregarlos después.
- **Los handles existen después de envolver.** `registry.circuitBreaker("inventory")` falla ([R4C-REG-009](../errors.md#r4c-reg-009)) hasta que la interfaz se haya envuelto con `Resilient.of` (o `Resilient.create`, o se haya construido un `Pipeline`).
- **Una espera que excede el timeout igual avanza toda su duración** en el reloj, así que `clock.elapsed()` después de un timeout es la espera del stub, no el timeout.
- **`advanceUntilIdle()` se rinde después de 100,000 tareas encadenadas** (`IllegalStateException`), lo que detecta una tarea que se reprograma sin parar.
