# Testes

Teste retries, breakers, rate limits, timeouts e bulkheads em milissegundos, com tempos exatos, dando ao registry um relógio que só se move quando o teste manda.

## Por que um relógio manual :id=why

Resiliência é, em grande parte, uma questão de tempo: um retry espera 1 s, um breaker fica aberto 30 s, um rate limiter entrega uma permissão a cada 500 ms. Testes que usam o relógio real são lentos, e testes que encurtam cada delay para ficar rápidos deixam de testar a configuração que você entrega.

`resilience4cata-testkit` tem `ManualClock`, um relógio que só se move quando o teste o avança. Entregue-o a um registry e todas as policies desse registry o seguem:

- **Os sleeps avançam o relógio em vez de bloquear.** Um retry com delay de 1 s retorna na hora, com o relógio 1 s adiante.
- **As esperas são medidas nele.** Um breaker fica aberto até o teste avançar além de `waitInOpen`.
- **Tarefas adiadas rodam quando o relógio passa do seu prazo**, na thread que chama `advance`, antes de ele retornar: retries assíncronos, esperas assíncronas do rate limiter, limites de espera assíncronos do bulkhead e timeouts assíncronos. Uma tarefa sem delay (um retry assíncrono com `delay = "0"`, um hedge depois de uma falha) também espera o próximo `advance`: `advance(Duration.ZERO)` a executa.
- **Os timeouts síncronos disparam exatamente no prazo**, interrompendo a thread cronometrada.
- **As esperas síncronas do bulkhead (`maxWait`) terminam no relógio.**

As anotações e o código gerado são os mesmos que em produção; só o registry muda.

## Dependência :id=dependency

Dois artefatos, ambos só para testes:

- **`resilience4cata-junit`** (desde 0.3.0): uma extensão do JUnit Jupiter que dá a cada teste um relógio novo e um registry que o segue. A forma mais fácil se você usa JUnit. Ela traz `resilience4cata-testkit` junto.
- **`resilience4cata-testkit`**: o relógio e os atalhos por si sós. Depende apenas de `resilience4cata-core` e não tem dependência de JUnit, então funciona com qualquer framework de testes.

<!-- tabs:start -->
#### **Gradle**
```groovy
dependencies {
    testImplementation platform('dev.resilience4cata:resilience4cata-bom:1.0.0-RC4')
    testImplementation 'dev.resilience4cata:resilience4cata-junit'      // with JUnit; brings testkit
    // or, with another test framework:
    // testImplementation 'dev.resilience4cata:resilience4cata-testkit'
}
```
#### **Gradle (Kotlin)**
```kotlin
dependencies {
    testImplementation(platform("dev.resilience4cata:resilience4cata-bom:1.0.0-RC4"))
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

Se o BOM já está importado para as suas dependências principais (veja [Instalação](../start/installation.md)), a linha da platform não é necessária de novo no Gradle. Se você anota interfaces em `src/test`, o processor também deve rodar nos fontes de teste: `testCompileOnly` para as anotações e `testAnnotationProcessor` para o processor.

`resilience4cata-junit` precisa do JUnit Jupiter 5.10 ou mais recente, JUnit 6 incluído: ele é compilado contra `junit-jupiter-api` 5.10.5 (declarado como dependência `api`, então a sua própria versão do JUnit, mais nova, prevalece) e seus testes rodam tanto na 5.10.5 quanto na 6.0.0. Ele usa o seu JUnit; não traz um engine.

## Seu primeiro teste :id=first-test

Uma interface com delays reais de produção: quatro tentativas, com 1 s de intervalo.

<!-- snippet: testing-first-interface -->
```java
public interface QuoteService {

    @Retry(maxAttempts = 4, delay = "1s", backoff = Backoff.FIXED, jitter = Jitter.NONE)
    String quote(String sku);
}
```

Com `@ResilienceTest` na classe, um teste recebe como parâmetros um `ManualClock` e um `ResilienceRegistry` que o segue. Ele envolve um stub que falha três vezes e verifica tanto o resultado quanto quanto tempo passou no relógio:

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

### Sem a extensão :id=without-the-extension

Com outro framework de testes, ou para construir o registry você mesmo, crie o relógio e use `ResilienceTesting.registry(clock)` em vez de `ResilienceRegistry.builder()`. Este é o mesmo teste apenas com o testkit:

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

`ResilienceTesting.registry(clock)` devolve um `ResilienceRegistry.Builder` comum com o relógio como ticker e um `java.util.Random` com a semente `ResilienceTesting.DEFAULT_SEED` (42) para o jitter, então você pode continuar a configurá-lo (`.retry("payments", …)`, `.listen(…)`, `ResilienceConfig.applyTo(…)`). É o mesmo que `ResilienceRegistry.builder().ticker(clock).random(new Random(42))`.

`ManualClock` em resumo: `read()` (nanossegundos, começando em 0), `elapsed()`, `advance(Duration)`, `advanceUntilIdle()` (executa todas as tarefas pendentes, por mais adiante que estejam, e devolve quanto o relógio andou), `sleep(Duration)` (para stubs: avança o relógio em vez de bloquear), `pendingTasks()`, `nextTaskIn()`. O relógio se conecta através da SPI `dev.resilience4cata.Ticker` (`ResilienceRegistry.Builder.ticker(…)`), então outro relógio de testes também pode implementá-la.

## A extensão JUnit :id=junit

`@ResilienceTest` (pacote `dev.resilience4cata.junit`) registra `ResilienceExtension`. `@ExtendWith(ResilienceExtension.class)` faz o mesmo com as configurações padrão.

- **Parâmetros.** Um método de teste, e seus métodos `@BeforeEach` e `@AfterEach`, podem receber um `ManualClock`, um `ResilienceRegistry` ou ambos.
- **Um par por teste.** Dentro de um teste, todo parâmetro de um tipo recebe a mesma instância, e o registry segue esse relógio. O próximo teste recebe um relógio novo e um registry novo, então instâncias com o mesmo `name` (um breaker aberto por um teste, por exemplo) nunca são compartilhadas entre testes. Cada invocação de um `@ParameterizedTest` ou `@RepeatedTest` conta como um teste.
- **Construídos sob demanda.** O relógio é criado quando um teste pede pela primeira vez o relógio ou o registry; o registry, na primeira vez que o teste pede o registry, a partir de `ResilienceTesting.registry(clock, seed)`.
- **Não em construtores nem em métodos `@BeforeAll`.** Eles servem a mais de um teste, então a extensão os faz falhar com uma `ParameterResolutionException` que diz isso.

Envolva o serviço uma vez em `@BeforeEach`: o teste recebe o mesmo registry, então pode olhar os handles das policies que ele aciona:

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

### Configurar o registry :id=configure-registry

Um método anotado com `@ConfigureRegistry` recebe o `ResilienceRegistry.Builder` antes de ele ser construído, já configurado com o relógio do teste e o jitter com semente: adicione configs nomeadas, padrões, listeners ou `ResilienceConfig.applyTo(builder)`. Para uma interface que pega suas configurações de uma config nomeada:

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

O método devolve `void`, recebe exatamente um `ResilienceRegistry.Builder`, não é privado e pode ser estático. Ele roda na instância do teste, uma vez por teste, quando o teste pede o registry pela primeira vez (um teste que só recebe o relógio nunca o executa). Com vários desses métodos, os de uma classe externa rodam antes dos de uma classe `@Nested`, e os de uma superclasse antes dos de uma subclasse; a ordem de vários em uma mesma classe não é especificada. Outra assinatura faz o teste falhar com uma `ExtensionConfigurationException`.

### Configurações :id=junit-settings

| Atributo | Padrão | Significado |
|---|---|---|
| `seed` | `42` (`ResilienceTesting.DEFAULT_SEED`) | Semente do `java.util.Random` de onde o registry tira o jitter, para que os delays com jitter sejam os mesmos em cada execução ([jitter com semente](#seeded-jitter)) |
| `startNanos` | `0` | Primeira leitura do relógio, por exemplo perto de `Long.MAX_VALUE` para verificar que nada quebra quando `nanoTime` dá a volta |
| `failOnPendingTasks` | `false` | Faz falhar um teste que passou mas deixou tarefas pendentes no seu relógio: um retry, uma espera ou um timeout assíncrono que ele nunca ultrapassou |

`@ResilienceTest` também vai em um método, para mudar as configurações de um teste. A anotação mais próxima prevalece como um todo (os atributos não são mesclados): a do método, depois a da classe (ou de uma superclasse), depois a de uma classe externa para testes `@Nested`.

`failOnPendingTasks` é verificado depois dos métodos `@AfterEach`, e apenas quando o teste passou, então nunca esconde a própria falha do teste. Chamadas assíncronas concluídas não deixam nada para trás: uma chamada terminada cancela seu timeout, e uma entrega do bulkhead cancela o limite de espera do chamador em espera.

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

A extensão funciona da mesma forma com todas as maneiras de proteger código: `Resilient.of` em interfaces, `Resilient.create` em classes anotadas, `Pipeline` (construa-o a partir do registry injetado) e métodos assíncronos.

## Receitas :id=recipes

As receitas constroem o relógio e o registry à mão, então funcionam com qualquer framework de testes. Com a extensão, receba `clock` e `registry` como parâmetros e remova as primeiras linhas.

### Delays e backoff do retry :id=retry

Registre quando cada tentativa acontece, no relógio do teste:

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

Com `jitter = Jitter.NONE` os delays são exatos. Com jitter, veja [jitter com semente](#seeded-jitter).

### Circuit breaker: aberto, depois half-open :id=circuit-breaker

<!-- snippet: testing-breaker-interface -->
```java
public interface InventoryService {

    @CircuitBreaker(name = "inventory", windowSize = 4, minimumCalls = 4, waitInOpen = "30s", halfOpenCalls = 1)
    int stock(String sku);
}
```

`ResilienceTesting.elapseWaitInOpen(breaker, clock)` avança o relógio pelo `waitInOpen` do breaker (falha com `IllegalStateException` se o breaker não está `OPEN`). O breaker ainda reporta `OPEN` depois disso: ele passa a half-open quando a próxima chamada pede uma permissão, não por um timer.

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

Para forçar um estado sem acionar chamadas, use o handle: `breaker.forceOpen()`, `breaker.disable()`, `breaker.reset()`. Eles estão no `core` e também funcionam sem o testkit.

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

As permissões são espaçadas em `period / permits`, com uma rajada de até `permits`. Um chamador com `maxWait` dorme no relógio esperando sua permissão, então o teste vê a espera em `clock.elapsed()`:

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

Um `@Timeout` síncrono interrompe a thread que executa a chamada. Em um relógio manual, ele dispara exatamente quando o relógio passa do prazo. Um stub que simula uma dependência lenta deve dormir **no relógio** (`clock.sleep(…)`), para que o relógio se mova e a interrupção o alcance:

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

O relógio ainda avança o sleep inteiro (5 s aqui): o tempo salta, não flui.

### Bulkhead com `maxWait` :id=bulkhead

A forma assíncrona não precisa de threads extras: uma chamada que encontra o bulkhead cheio é enfileirada, e seu limite de espera é uma tarefa no relógio.

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

Um bulkhead **síncrono** com `maxWait` também termina no relógio, mas o chamador em espera é uma thread bloqueada, então o teste precisa de uma segunda thread: inicie a chamada lenta e a chamada em espera em suas próprias threads, espere (em tempo real, com limite) até `registry.bulkhead(id).waitingCalls()` ser 1, depois `clock.advance(maxWait)`; o chamador em espera é rejeitado com `BulkheadFullException`, ou recebe a vaga assim que a primeira chamada a libera.

## Métodos assíncronos no relógio :id=async

Os delays de retries assíncronos são tarefas no relógio. Cada `advance` executa as tentativas que vencem, na thread do teste, antes de retornar; `advanceUntilIdle()` executa todas:

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

## Jitter com semente :id=seeded-jitter

Com jitter (o padrão é `FULL`), os delays são aleatórios. Em um registry do testkit eles vêm de um `java.util.Random` com semente fixa, então o mesmo teste dá os mesmos delays em cada execução. `ResilienceTesting.registry(clock, seed)` escolhe outra semente (com a extensão JUnit, `@ResilienceTest(seed = …)`):

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

Os sorteios só são determinísticos quando uma thread falha por vez: com várias threads falhando ao mesmo tempo, qual chamada recebe qual delay depende do escalonamento. Sem o testkit, `ResilienceRegistry.builder().random(new Random(seed))` faz o mesmo (o gerador deve ser thread-safe; `SplittableRandom` não é).

## Verificar eventos e snapshots :id=events-and-snapshots

Registre um listener no builder (os listeners são fixados em `build()`) e verifique o que ele coletou. Os tempos dos eventos (`nanoTime()`) são leituras do relógio manual.

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

Snapshots e handles mostram o estado sem acionar mais chamadas. Um breaker reporta as taxas como `-1` até sua janela conter `minimumCalls` chamadas:

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

Veja [Observabilidade](observability.md) para cada evento e campo de snapshot.

## Armadilhas :id=pitfalls

- **`Thread.sleep` em um stub não move o relógio.** Use `clock.sleep(…)` para simular uma dependência lenta; um sleep real deixa o teste lento e um timeout nunca dispara (o watchdog segue o relógio manual).
- **Um registry por teste.** Instâncias com o mesmo `name` são compartilhadas dentro de um registry, então um breaker aberto por um teste continua aberto para o próximo se eles compartilham o registry. A extensão JUnit dá a cada teste o seu.
- **Avance a partir de uma única thread.** Duas threads avançando ao mesmo tempo ambas progridem, mas a ordem das suas tarefas deixa de ser determinística.
- **O breaker passa a half-open na próxima chamada**, não quando o relógio passa de `waitInOpen`; `state()` reporta `OPEN` até lá.
- **Os listeners devem ser registrados antes de `build()`.** Adicioná-los depois não é possível.
- **Os handles existem depois de envolver.** `registry.circuitBreaker("inventory")` falha ([R4C-REG-009](../errors.md#r4c-reg-009)) até que a interface tenha sido envolvida com `Resilient.of` (ou `Resilient.create`, ou um `Pipeline` construído).
- **Um sleep que sofreu timeout ainda avança toda a sua duração** no relógio, então `clock.elapsed()` depois de um timeout é o sleep do stub, não o timeout.
- **`advanceUntilIdle()` desiste depois de 100,000 tarefas encadeadas** (`IllegalStateException`), o que captura uma tarefa que se reagenda sem parar.
