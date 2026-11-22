# 测试

给注册表一个只在测试要求时才走动的时钟，就能在几毫秒内以精确的时间测试重试、熔断器、限流、超时和舱壁隔离。

## 为什么使用手动时钟 :id=why

弹性主要与时间有关：重试等待 1 s，熔断器保持打开 30 s，限流器每 500 ms 发放一个许可。使用真实时钟的测试很慢，而为了加快速度而缩短每个延迟的测试，就不再测试你实际发布的配置了。

`resilience4cata-testkit` 提供 `ManualClock`，一个只在测试推进它时才走动的时钟。把它交给注册表，该注册表的每个策略都会跟随它：

- **睡眠会推进时钟而不是阻塞。** 延迟为 1 s 的重试会立即返回，时钟前进 1 s。
- **等待在它上面计量。** 熔断器保持打开，直到测试把时钟推进到超过 `waitInOpen`。
- **延迟任务在时钟越过其截止时间时运行**，在调用 `advance` 的线程上、在它返回之前运行：异步重试、异步限流器等待、异步舱壁隔离等待上限和异步超时。没有延迟的任务（`delay = "0"` 的异步重试、失败后的对冲请求）也会等待下一次 `advance`：`advance(Duration.ZERO)` 会运行它。
- **同步超时恰好在截止时间触发**，方式是中断被计时的线程。
- **同步舱壁隔离的等待（`maxWait`）在时钟上结束。**

注解和生成的代码与生产环境中相同；只有注册表不同。

## 依赖 :id=dependency

两个构件，都只用于测试：

- **`resilience4cata-junit`**（自 0.3.0 起）：一个 JUnit Jupiter 扩展，为每个测试提供一个全新的时钟和一个跟随它的注册表。如果你使用 JUnit，这是最简单的方式。它会带上 `resilience4cata-testkit`。
- **`resilience4cata-testkit`**：单独提供时钟和快捷方法。它只依赖 `resilience4cata-core`，不依赖 JUnit，因此可以与任何测试框架一起使用。

<!-- tabs:start -->
#### **Gradle**
```groovy
dependencies {
    testImplementation platform('dev.resilience4cata:resilience4cata-bom:1.0.0')
    testImplementation 'dev.resilience4cata:resilience4cata-junit'      // with JUnit; brings testkit
    // or, with another test framework:
    // testImplementation 'dev.resilience4cata:resilience4cata-testkit'
}
```
#### **Gradle (Kotlin)**
```kotlin
dependencies {
    testImplementation(platform("dev.resilience4cata:resilience4cata-bom:1.0.0"))
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

如果已经为主依赖导入了 BOM（参见[安装](../start/installation.md)），在 Gradle 中就不需要再写 platform 那一行。如果你在 `src/test` 下给接口加注解，注解处理器也必须在测试源码上运行：用 `testCompileOnly` 引入注解，用 `testAnnotationProcessor` 引入注解处理器。

`resilience4cata-junit` 需要 JUnit Jupiter 5.10 或更新版本，包括 JUnit 6：它针对 `junit-jupiter-api` 5.10.5 编译（声明为 `api` 依赖，因此你自己的更新版本 JUnit 优先），其测试在 5.10.5 和 6.0.0 上都会运行。它使用你的 JUnit；不会带入测试引擎。

## 你的第一个测试 :id=first-test

一个使用真实生产延迟的接口：四次尝试，间隔 1 s。

<!-- snippet: testing-first-interface -->
```java
public interface QuoteService {

    @Retry(maxAttempts = 4, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    String quote(String sku);
}
```

在类上加 `@ResilienceTest` 后，测试可以接收一个 `ManualClock` 和一个跟随它的 `ResilienceRegistry` 作为参数。它包装一个失败三次的桩，并同时检查结果和时钟上经过的时间：

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

### 不使用扩展 :id=without-the-extension

使用其他测试框架，或者想自己构建注册表时，创建时钟并用 `ResilienceTesting.registry(clock)` 代替 `ResilienceRegistry.builder()`。下面是只用 testkit 的同一个测试：

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

`ResilienceTesting.registry(clock)` 返回一个普通的 `ResilienceRegistry.Builder`，以该时钟作为 ticker，并使用以 `ResilienceTesting.DEFAULT_SEED`（42）为种子的 `java.util.Random` 生成抖动，因此你可以继续配置它（`.retry("payments", …)`、`.listen(…)`、`ResilienceConfig.applyTo(…)`）。它等同于 `ResilienceRegistry.builder().ticker(clock).random(new Random(42))`。

`ManualClock` 简述：`read()`（纳秒，从 0 开始）、`elapsed()`、`advance(Duration)`、`advanceUntilIdle()`（运行每个待处理任务，无论多远，并返回时钟前进了多少）、`sleep(Duration)`（用于桩：推进时钟而不是阻塞）、`pendingTasks()`、`nextTaskIn()`。时钟通过 `dev.resilience4cata.Ticker` SPI（`ResilienceRegistry.Builder.ticker(…)`）接入，因此其他测试时钟也可以实现它。

## JUnit 扩展 :id=junit

`@ResilienceTest`（包 `dev.resilience4cata.junit`）注册 `ResilienceExtension`。`@ExtendWith(ResilienceExtension.class)` 以默认设置做同样的事。

- **参数。** 测试方法及其 `@BeforeEach` 和 `@AfterEach` 方法可以接收 `ManualClock`、`ResilienceRegistry` 或两者。
- **每个测试一对。** 在一个测试内，同一类型的每个参数都得到同一个实例，注册表跟随那个时钟。下一个测试得到新的时钟和新的注册表，因此同 `name` 的实例（例如一个测试打开的熔断器）从不在测试之间共享。`@ParameterizedTest` 或 `@RepeatedTest` 的每次调用都算作一个测试。
- **延迟构建。** 时钟在测试第一次请求时钟或注册表时创建；注册表在第一次请求注册表时，由 `ResilienceTesting.registry(clock, seed)` 创建。
- **不能用于构造函数或 `@BeforeAll` 方法。** 它们服务于不止一个测试，因此扩展会以一个说明原因的 `ParameterResolutionException` 让它们失败。

在 `@BeforeEach` 中包装服务一次：测试会收到同一个注册表，因此可以查看它驱动的策略的 handle：

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

### 配置注册表 :id=configure-registry

带有 `@ConfigureRegistry` 注解的方法会在构建之前收到 `ResilienceRegistry.Builder`，它已经设置好了测试的时钟和带种子的抖动：可以添加命名配置、默认值、监听器，或调用 `ResilienceConfig.applyTo(builder)`。对于从命名配置获取设置的接口：

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

该方法返回 `void`，只接收一个 `ResilienceRegistry.Builder`，不是 private，可以是 static。它在测试实例上运行，每个测试一次，在测试第一次请求注册表时运行（只接收时钟的测试从不运行它）。有多个这样的方法时，外部类的在 `@Nested` 类的之前运行，父类的在子类的之前运行；同一个类中多个方法的顺序未作规定。其他签名会以 `ExtensionConfigurationException` 让测试失败。

### 设置 :id=junit-settings

| 属性 | 默认值 | 含义 |
|---|---|---|
| `seed` | `42`（`ResilienceTesting.DEFAULT_SEED`） | 注册表用于抽取抖动的 `java.util.Random` 的种子，因此每次运行的带抖动延迟都相同（[带种子的抖动](#seeded-jitter)） |
| `startNanos` | `0` | 时钟的第一个读数，例如设为接近 `Long.MAX_VALUE`，以检查 `nanoTime` 回绕时没有任何东西出错 |
| `failOnPendingTasks` | `false` | 让一个已通过但在其时钟上留下待处理任务的测试失败：一个它从未推进越过的异步重试、等待或超时 |

`@ResilienceTest` 也可以放在方法上，以修改某一个测试的设置。最近的注解整体生效（属性不合并）：先是方法上的，然后是类上的（或父类的），对于 `@Nested` 测试再是外部类的。

`failOnPendingTasks` 在 `@AfterEach` 方法之后检查，并且只在测试通过时检查，因此它从不掩盖测试自身的失败。已完成的异步调用不会留下任何东西：完成的调用会取消它的超时，舱壁隔离的交接会取消等待者的等待上限。

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

该扩展对每种保护代码的方式都同样适用：接口上的 `Resilient.of`、带注解类上的 `Resilient.create`、`Pipeline`（从注入的注册表构建），以及异步方法。

## 示例 :id=recipes

这些示例手动构建时钟和注册表，因此可以用于任何测试框架。使用扩展时，改为把 `clock` 和 `registry` 作为参数接收，并去掉开头几行。

### 重试延迟和退避 :id=retry

在测试的时钟上记录每次尝试发生的时间：

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

使用 `jitter = Jitter.NONE` 时延迟是精确的。使用抖动时，参见[带种子的抖动](#seeded-jitter)。

### 熔断器：打开，然后半开 :id=circuit-breaker

<!-- snippet: testing-breaker-interface -->
```java
public interface InventoryService {

    @CircuitBreaker(name = "inventory", windowSize = 4, minimumCalls = 4, waitInOpen = "30s", halfOpenCalls = 1)
    int stock(String sku);
}
```

`ResilienceTesting.elapseWaitInOpen(breaker, clock)` 把时钟推进熔断器的 `waitInOpen`（如果熔断器不是 `OPEN`，它会以 `IllegalStateException` 失败）。之后熔断器仍报告 `OPEN`：它在下一次调用请求许可时才进入半开，而不是由定时器触发。

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

要在不驱动调用的情况下强制设置状态，请使用 handle：`breaker.forceOpen()`、`breaker.disable()`、`breaker.reset()`。它们位于 `core` 中，不使用 testkit 也能工作。

### 限流器 :id=rate-limiter

<!-- snippet: testing-rate-limiter-interface -->
```java
public interface SearchService {

    @RateLimiter(name = "search", permits = 2, period = "1s")
    String search(String query);

    @RateLimiter(name = "export", permits = 1, period = "1s", maxWait = "2s")
    String export(String query);
}
```

许可之间相隔 `period / permits`，突发量最多为 `permits`。带 `maxWait` 的调用方在时钟上睡眠以等待许可，因此测试能在 `clock.elapsed()` 中看到这段等待：

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

### 超时 :id=timeout

同步 `@Timeout` 会中断运行调用的线程。在手动时钟上，它恰好在时钟越过截止时间时触发。模拟慢依赖的桩必须**在时钟上**睡眠（`clock.sleep(…)`），这样时钟才会走动，中断才能到达它：

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

时钟仍然会推进完整的睡眠时间（这里是 5 s）：时间是跳跃的，而不是流动的。

### 带 `maxWait` 的舱壁隔离 :id=bulkhead

异步形式不需要额外的线程：发现舱壁隔离已满的调用会被排队，它的等待上限是时钟上的一个任务。

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

带 `maxWait` 的**同步**舱壁隔离同样在时钟上结束，但等待中的调用方是一个被阻塞的线程，因此测试需要第二个线程：在各自的线程上启动慢调用和等待调用，（以有上限的真实时间）等待直到 `registry.bulkhead(id).waitingCalls()` 为 1，然后调用 `clock.advance(maxWait)`；等待者会以 `BulkheadFullException` 被拒绝，或者在第一个调用释放槽位后立即获得它。

## 时钟上的异步方法 :id=async

异步重试延迟是时钟上的任务。每次 `advance` 都会在测试线程上、在返回之前运行到期的尝试；`advanceUntilIdle()` 会运行全部尝试：

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

## 带种子的抖动 :id=seeded-jitter

使用抖动时（默认是 `FULL`），延迟是随机的。在 testkit 注册表上，它们来自一个固定种子的 `java.util.Random`，因此同一个测试每次运行都得到相同的延迟。`ResilienceTesting.registry(clock, seed)` 选择另一个种子（使用 JUnit 扩展时为 `@ResilienceTest(seed = …)`）：

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

只有在同一时间只有一个线程失败时，抽取才是确定的：多个线程同时失败时，哪个调用得到哪个延迟取决于调度。不使用 testkit 时，`ResilienceRegistry.builder().random(new Random(seed))` 做同样的事（生成器必须是线程安全的；`SplittableRandom` 不是）。

## 断言事件和快照 :id=events-and-snapshots

在 builder 上注册一个监听器（监听器在 `build()` 时固定），然后对它收集到的内容进行断言。事件时间（`nanoTime()`）是手动时钟的读数。

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
    assertThat(first.id()).isEqualTo(
            "dev.resilience4cata.examples.docs.testing.QuoteService#quote(java.lang.String)");
}
```

快照和 handle 无需驱动更多调用即可显示状态。在窗口积累到 `minimumCalls` 次调用之前，熔断器报告的比率为 `-1`：

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

每个事件和快照字段参见[可观测性](observability.md)。

## 常见陷阱 :id=pitfalls

- **桩中的 `Thread.sleep` 不会推进时钟。** 请用 `clock.sleep(…)` 模拟慢依赖；真实的睡眠会让测试变慢，并且超时永远不会触发（watchdog 跟随手动时钟）。
- **每个测试一个注册表。** 同 `name` 的实例在一个注册表内共享，因此如果两个测试共享注册表，一个测试打开的熔断器在下一个测试中仍然是打开的。JUnit 扩展为每个测试提供各自的注册表。
- **从一个线程推进。** 两个线程同时推进时都会有进展，但此时它们任务的顺序不是确定的。
- **熔断器在下一次调用时进入半开**，而不是在时钟越过 `waitInOpen` 时；在此之前 `state()` 报告 `OPEN`。
- **监听器必须在 `build()` 之前注册。** 之后无法再添加。
- **handle 在包装之后才存在。** 在用 `Resilient.of`（或 `Resilient.create`，或构建 `Pipeline`）包装接口之前，`registry.circuitBreaker("inventory")` 会失败（[R4C-REG-009](../errors.md#r4c-reg-009)）。
- **超时的睡眠仍会在时钟上推进其完整长度**，因此超时后的 `clock.elapsed()` 是桩的睡眠时间，而不是超时时间。
- **`advanceUntilIdle()` 在 100,000 个链式任务后放弃**（`IllegalStateException`），这样可以捕获一直重新调度自身的任务。
