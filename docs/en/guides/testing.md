# Testing

Test retries, breakers, rate limits, timeouts and bulkheads in milliseconds, with exact timing, by giving the registry a clock that only moves when the test says so.

## Why a manual clock :id=why

Resilience is mostly about time: a retry waits 1 s, a breaker stays open 30 s, a rate limiter hands out a permit every 500 ms. Tests that use the real clock are slow, and tests that shorten every delay to make them fast stop testing the configuration you ship.

`resilience4cata-testkit` has `ManualClock`, a clock that only moves when the test advances it. Give it to a registry and every policy of that registry follows it:

- **Sleeps advance the clock instead of blocking.** A retry with a 1 s delay returns at once, with the clock 1 s later.
- **Waits are measured on it.** A breaker stays open until the test advances past `waitInOpen`.
- **Delayed tasks run when the clock passes their deadline**, on the thread that calls `advance`, before it returns: async retries, async rate-limiter waits, async bulkhead wait limits and async timeouts. A task with no delay (an async retry with `delay = "0"`, a hedge after a failure) waits for the next `advance` too: `advance(Duration.ZERO)` runs it.
- **Sync timeouts fire exactly at their deadline**, by interrupting the timed thread.
- **Sync bulkhead waits (`maxWait`) end on the clock.**

The annotations and the generated code are the same as in production; only the registry changes.

## Dependency :id=dependency

Two artifacts, both test-only:

- **`resilience4cata-junit`** (since 0.3.0): a JUnit Jupiter extension that gives each test a fresh clock and a registry that follows it. The easiest way if you use JUnit. It brings `resilience4cata-testkit` with it.
- **`resilience4cata-testkit`**: the clock and the shortcuts on their own. It depends only on `resilience4cata-core` and has no JUnit dependency, so it works with any test framework.

<!-- tabs:start -->
#### **Gradle**
```groovy
dependencies {
    testImplementation platform('dev.resilience4cata:resilience4cata-bom:0.7.0')
    testImplementation 'dev.resilience4cata:resilience4cata-junit'      // with JUnit; brings testkit
    // or, with another test framework:
    // testImplementation 'dev.resilience4cata:resilience4cata-testkit'
}
```
#### **Gradle (Kotlin)**
```kotlin
dependencies {
    testImplementation(platform("dev.resilience4cata:resilience4cata-bom:0.7.0"))
    testImplementation("dev.resilience4cata:resilience4cata-junit")      // with JUnit; brings testkit
    // or, with another test framework:
    // testImplementation("dev.resilience4cata:resilience4cata-testkit")
}
```
#### **Maven**
```xml
<!-- with the BOM imported in <dependencyManagement> (see Installation) -->
<dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-junit</artifactId>  <!-- or resilience4cata-testkit without JUnit -->
    <scope>test</scope>
</dependency>
```
<!-- tabs:end -->

If the BOM is already imported for your main dependencies (see [Installation](../start/installation.md)), the platform line is not needed again in Gradle. If you annotate interfaces under `src/test`, the processor must also run on test sources: `testCompileOnly` the annotations and `testAnnotationProcessor` the processor.

`resilience4cata-junit` needs JUnit Jupiter 5.10 or newer, JUnit 6 included: it is compiled against `junit-jupiter-api` 5.10.5 (declared as an `api` dependency, so your own, newer JUnit version wins) and its tests run on both 5.10.5 and 6.0.0. It uses your JUnit; it does not bring an engine.

## Your first test :id=first-test

An interface with real production delays: four attempts, 1 s apart.

<!-- snippet: testing-first-interface -->
```java
public interface QuoteService {

    @Retry(maxAttempts = 4, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    String quote(String sku);
}
```

With `@ResilienceTest` on the class, a test takes a `ManualClock` and a `ResilienceRegistry` that follows it as parameters. It wraps a stub that fails three times, and checks both the result and how much time passed on the clock:

<!-- snippet: testing-junit-first -->
```java
@ResilienceTest
class QuoteServiceTest {

    @Test
    void retriesThreeTimesWithoutWaiting(ManualClock clock, ResilienceRegistry registry) {
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
}
```

### Without the extension :id=without-the-extension

With another test framework, or to build the registry yourself, create the clock and use `ResilienceTesting.registry(clock)` instead of `ResilienceRegistry.builder()`. This is the same test with testkit alone:

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

`ResilienceTesting.registry(clock)` returns an ordinary `ResilienceRegistry.Builder` with the clock as its ticker and a `java.util.Random` seeded with `ResilienceTesting.DEFAULT_SEED` (42) for jitter, so you can keep configuring it (`.retry("payments", …)`, `.listen(…)`, `ResilienceConfig.applyTo(…)`). It is the same as `ResilienceRegistry.builder().ticker(clock).random(new Random(42))`.

`ManualClock` in short: `read()` (nanoseconds, starting at 0), `elapsed()`, `advance(Duration)`, `advanceUntilIdle()` (runs every pending task, however far ahead, and returns how far the clock moved), `sleep(Duration)` (for stubs: advances the clock instead of blocking), `pendingTasks()`, `nextTaskIn()`. The clock plugs in through the `dev.resilience4cata.Ticker` SPI (`ResilienceRegistry.Builder.ticker(…)`), so another test clock can implement it too.

## The JUnit extension :id=junit

`@ResilienceTest` (package `dev.resilience4cata.junit`) registers `ResilienceExtension`. `@ExtendWith(ResilienceExtension.class)` does the same with the default settings.

- **Parameters.** A test method, and its `@BeforeEach` and `@AfterEach` methods, can take a `ManualClock`, a `ResilienceRegistry`, or both.
- **One pair per test.** Within one test, every parameter of a type gets the same instance, and the registry follows that clock. The next test gets a new clock and a new registry, so instances with the same `name` (a breaker opened by one test, for example) are never shared between tests. Each invocation of a `@ParameterizedTest` or `@RepeatedTest` counts as a test.
- **Built lazily.** The clock is created when a test first asks for the clock or the registry; the registry, the first time it asks for the registry, from `ResilienceTesting.registry(clock, seed)`.
- **Not in constructors or `@BeforeAll` methods.** They serve more than one test, so the extension fails them with a `ParameterResolutionException` that says so.

Wrap the service once in `@BeforeEach`: the test receives the same registry, so it can look at the handles of the policies it drives:

<!-- snippet: testing-junit-before-each -->
```java
@ResilienceTest
class InventoryServiceTest {

    private final AtomicBoolean down = new AtomicBoolean(true);
    private InventoryService inventory;

    @BeforeEach
    void wrap(ResilienceRegistry registry) {
        inventory = Resilient.of(InventoryService.class, sku -> {
            if (down.get()) {
                throw new IllegalStateException("inventory down");
            }
            return 7;
        }, registry);
    }

    @Test
    void theBreakerOpensThenLetsAProbeThrough(ManualClock clock, ResilienceRegistry registry) { // the same registry
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> inventory.stock("X")).isInstanceOf(IllegalStateException.class);
        }
        CircuitBreakerHandle breaker = registry.circuitBreaker("inventory");
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.OPEN);

        down.set(false);
        ResilienceTesting.elapseWaitInOpen(breaker, clock);
        assertThat(inventory.stock("X")).isEqualTo(7);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void eachTestStartsWithAClosedBreaker(ResilienceRegistry registry) { // a new registry for each test
        assertThat(registry.circuitBreaker("inventory").state()).isEqualTo(CircuitBreakerState.CLOSED);
    }
}
```

### Configuring the registry :id=configure-registry

A method annotated `@ConfigureRegistry` receives the `ResilienceRegistry.Builder` before it is built, already set up with the test's clock and seeded jitter: add named configs, defaults, listeners, or `ResilienceConfig.applyTo(builder)`. For an interface that takes its settings from a named config:

<!-- snippet: testing-junit-payment-interface -->
```java
public interface PaymentService {

    @Retry(config = "payments") // attempts and delays come from the registry's "payments" config
    String charge(String order);
}
```

<!-- snippet: testing-junit-configure -->
```java
@ResilienceTest
class PaymentServiceTest {

    private final List<RetryEvent.Retrying> retries = new CopyOnWriteArrayList<>();

    @ConfigureRegistry
    void configure(ResilienceRegistry.Builder builder) { // runs before the registry is built, once per test
        builder.retry("payments", r -> r.maxAttempts(3).delay(Duration.ofSeconds(2))
                        .backoff(RetryConfig.Backoff.FIXED).jitter(RetryConfig.Jitter.NONE))
                .listen(RetryEvent.Retrying.class, retries::add);
    }

    @Test
    void usesTheNamedConfig(ManualClock clock, ResilienceRegistry registry) {
        PaymentService payments = Resilient.of(PaymentService.class, order -> {
            throw new IllegalStateException("card declined");
        }, registry);

        assertThatThrownBy(() -> payments.charge("o-1")).isInstanceOf(IllegalStateException.class);

        assertThat(retries).hasSize(2);
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(4)); // two 2 s delays
    }
}
```

The method returns `void`, takes exactly one `ResilienceRegistry.Builder`, is not private, and may be static. It runs on the test instance, once per test, when the test first asks for the registry (a test that only takes the clock never runs it). With several such methods, an enclosing class's run before a `@Nested` class's, and a superclass's before a subclass's; the order of several in one class is not specified. Another signature fails the test with an `ExtensionConfigurationException`.

### Settings :id=junit-settings

| Attribute | Default | Meaning |
|---|---|---|
| `seed` | `42` (`ResilienceTesting.DEFAULT_SEED`) | Seed of the `java.util.Random` the registry draws jitter from, so jittered delays are the same on every run ([seeded jitter](#seeded-jitter)) |
| `startNanos` | `0` | First reading of the clock, for instance close to `Long.MAX_VALUE` to check that nothing breaks when `nanoTime` wraps around |
| `failOnPendingTasks` | `false` | Fails a test that passed but left tasks pending on its clock: an async retry, wait or timeout it never advanced past |

`@ResilienceTest` also goes on a method, to change the settings of one test. The closest annotation wins as a whole (the attributes are not merged): the method's, then the class's (or a superclass's), then an enclosing class's for `@Nested` tests.

`failOnPendingTasks` is checked after the `@AfterEach` methods, and only when the test passed, so it never hides the test's own failure. Completed async calls leave nothing behind: a finished call cancels its timeout, and a bulkhead hand-off cancels the waiter's wait limit.

<!-- snippet: testing-junit-async-interface -->
```java
public interface AsyncQuoteService {

    @Retry(maxAttempts = 3, delay = "1s") // the default FULL jitter
    CompletableFuture<String> quote(String sku);
}
```

<!-- snippet: testing-junit-settings -->
```java
@ResilienceTest(seed = 7, failOnPendingTasks = true)
class AsyncQuoteServiceTest {

    @Test
    void asyncRetriesRunWhenTheClockAdvances(ManualClock clock, ResilienceRegistry registry) {
        AtomicInteger calls = new AtomicInteger();
        AsyncQuoteService quotes = Resilient.of(AsyncQuoteService.class, sku -> calls.incrementAndGet() < 3
                ? CompletableFuture.failedFuture(new IllegalStateException("upstream down"))
                : CompletableFuture.completedFuture("quote for " + sku), registry);

        CompletableFuture<String> quote = quotes.quote("A-1"); // the second attempt waits on the clock
        assertThat(quote).isNotDone();

        clock.advanceUntilIdle(); // without this line the test fails: a retry is still pending on the clock
        assertThat(quote).isCompletedWithValue("quote for A-1");
    }
}
```

The extension works the same with every way of protecting code: `Resilient.of` on interfaces, `Resilient.create` on annotated classes, `Pipeline` (build it from the injected registry), and async methods.

## Recipes :id=recipes

The recipes build the clock and the registry by hand, so they work with any test framework. With the extension, take `clock` and `registry` as parameters instead and drop the first lines.

### Retry delays and backoff :id=retry

Record when each attempt happens, on the test's clock:

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

With `jitter = Jitter.NONE` the delays are exact. With jitter, see [seeded jitter](#seeded-jitter).

### Circuit breaker: open, then half-open :id=circuit-breaker

<!-- snippet: testing-breaker-interface -->
```java
public interface InventoryService {

    @CircuitBreaker(name = "inventory", windowSize = 4, minimumCalls = 4, waitInOpen = "30s", halfOpenCalls = 1)
    int stock(String sku);
}
```

`ResilienceTesting.elapseWaitInOpen(breaker, clock)` advances the clock by the breaker's `waitInOpen` (it fails with `IllegalStateException` if the breaker is not `OPEN`). The breaker still reports `OPEN` after that: it moves to half-open when the next call asks for a permit, not on a timer.

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

To force a state without driving calls, use the handle: `breaker.forceOpen()`, `breaker.disable()`, `breaker.reset()`. Those are in `core` and also work without testkit.

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

Permits are spaced `period / permits` apart, with a burst of up to `permits`. A caller with `maxWait` sleeps on the clock for its permit, so the test sees the wait in `clock.elapsed()`:

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

A sync `@Timeout` interrupts the thread that runs the call. On a manual clock it fires exactly when the clock passes the deadline. A stub that simulates a slow dependency must sleep **on the clock** (`clock.sleep(…)`), so that the clock moves and the interrupt reaches it:

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

The clock still advances by the whole sleep (5 s here): time jumps, it does not flow.

### Bulkhead with `maxWait` :id=bulkhead

The async form needs no extra threads: a call that finds the bulkhead full is queued, and its wait limit is a task on the clock.

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

A **sync** bulkhead with `maxWait` also ends on the clock, but the waiting caller is a blocked thread, so the test needs a second thread: start the slow call and the waiting call on their own threads, wait (in real time, bounded) until `registry.bulkhead(id).waitingCalls()` is 1, then `clock.advance(maxWait)`; the waiter is rejected with `BulkheadFullException`, or gets the slot as soon as the first call releases it.

## Async methods on the clock :id=async

Async retry delays are tasks on the clock. Each `advance` runs the attempts that fall due, on the test thread, before it returns; `advanceUntilIdle()` runs them all:

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

## Seeded jitter :id=seeded-jitter

With jitter (the default is `FULL`), delays are random. On a testkit registry they come from a `java.util.Random` with a fixed seed, so the same test gives the same delays on every run. `ResilienceTesting.registry(clock, seed)` picks another seed (with the JUnit extension, `@ResilienceTest(seed = …)`):

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

Draws are deterministic only when one thread fails at a time: with several threads failing at once, which call gets which delay depends on scheduling. Without testkit, `ResilienceRegistry.builder().random(new Random(seed))` does the same (the generator must be thread-safe; `SplittableRandom` is not).

## Asserting events and snapshots :id=events-and-snapshots

Register a listener on the builder (listeners are fixed at `build()`) and assert on what it collected. Event times (`nanoTime()`) are readings of the manual clock.

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

Snapshots and handles show the state without driving more calls. A breaker reports rates as `-1` until its window holds `minimumCalls` calls:

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

See [Observability](observability.md) for every event and snapshot field.

## Pitfalls :id=pitfalls

- **`Thread.sleep` in a stub does not move the clock.** Use `clock.sleep(…)` to simulate a slow dependency; a real sleep makes the test slow and a timeout never fires (the watchdog follows the manual clock).
- **One registry per test.** Instances with the same `name` are shared inside a registry, so a breaker opened by one test stays open for the next one if they share the registry. The JUnit extension gives each test its own.
- **Advance from one thread.** Two threads advancing at once both make progress, but the order of their tasks is then not deterministic.
- **The breaker moves to half-open on the next call**, not when the clock passes `waitInOpen`; `state()` reports `OPEN` until then.
- **Listeners must be registered before `build()`.** Adding them later is not possible.
- **Handles exist after wrapping.** `registry.circuitBreaker("inventory")` fails ([R4C-REG-009](../errors.md#r4c-reg-009)) until the interface has been wrapped with `Resilient.of` (or `Resilient.create`, or a `Pipeline` built).
- **A timed-out sleep still advances its full length** on the clock, so `clock.elapsed()` after a timeout is the stub's sleep, not the timeout.
- **`advanceUntilIdle()` gives up after 100,000 chained tasks** (`IllegalStateException`), which catches a task that keeps rescheduling itself.
