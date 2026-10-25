# Testing

Test retries, breakers, rate limits, timeouts and bulkheads in milliseconds, with exact timing, by giving the registry a clock that only moves when the test says so.

## Why a manual clock :id=why

Resilience is mostly about time: a retry waits 1 s, a breaker stays open 30 s, a rate limiter hands out a permit every 500 ms. Tests that use the real clock are slow, and tests that shorten every delay to make them fast stop testing the configuration you ship.

`resilience4cata-testkit` has `ManualClock`, a clock that only moves when the test advances it. Give it to a registry and every policy of that registry follows it:

- **Sleeps advance the clock instead of blocking.** A retry with a 1 s delay returns at once, with the clock 1 s later.
- **Waits are measured on it.** A breaker stays open until the test advances past `waitInOpen`.
- **Delayed tasks run when the clock passes their deadline**, on the thread that calls `advance`, before it returns: async retries, async rate-limiter waits, async bulkhead wait limits and async timeouts.
- **Sync timeouts fire exactly at their deadline**, by interrupting the timed thread.
- **Sync bulkhead waits (`maxWait`) end on the clock.**

The annotations and the generated code are the same as in production; only the registry changes.

## Dependency :id=dependency

testkit depends only on `resilience4cata-core` and has no JUnit dependency, so it works with any test framework.

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

Without the BOM, add the version (`0.1.0`) in Gradle too. If you annotate interfaces under `src/test`, the processor must also run on test sources: `testCompileOnly` the annotations and `testAnnotationProcessor` the processor (see [Installation](../start/installation.md)).

## Your first test :id=first-test

An interface with real production delays: four attempts, 1 s apart.

<!-- snippet: testing-first-interface -->
```java
public interface QuoteService {

    @Retry(maxAttempts = 4, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    String quote(String sku);
}
```

The test builds the registry with `ResilienceTesting.registry(clock)` instead of `ResilienceRegistry.builder()`, wraps a stub that fails three times, and checks both the result and how much time passed on the clock:

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

## Recipes :id=recipes

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

With jitter (the default is `FULL`), delays are random. On a testkit registry they come from a `java.util.Random` with a fixed seed, so the same test gives the same delays on every run. `ResilienceTesting.registry(clock, seed)` picks another seed:

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
- **One registry per test.** Instances with the same `name` are shared inside a registry, so a breaker opened by one test stays open for the next one if they share the registry.
- **Advance from one thread.** Two threads advancing at once both make progress, but the order of their tasks is then not deterministic.
- **The breaker moves to half-open on the next call**, not when the clock passes `waitInOpen`; `state()` reports `OPEN` until then.
- **Listeners must be registered before `build()`.** Adding them later is not possible.
- **Handles exist after wrapping.** `registry.circuitBreaker("inventory")` fails ([R4C-REG-009](../errors.md#r4c-reg-009)) until the interface has been wrapped with `Resilient.of` (or `Resilient.create`, or a `Pipeline` built).
- **A timed-out sleep still advances its full length** on the clock, so `clock.elapsed()` after a timeout is the stub's sleep, not the timeout.
- **`advanceUntilIdle()` gives up after 100,000 chained tasks** (`IllegalStateException`), which catches a task that keeps rescheduling itself.
