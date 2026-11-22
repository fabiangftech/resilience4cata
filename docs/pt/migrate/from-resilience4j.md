# Migrar do Resilience4j

Como os registries, configs, decorators e anotações do Spring do Resilience4j correspondem ao resilience4cata, o que não tem equivalente e o que se comporta de forma diferente.

## Você deve migrar? :id=when

O resilience4cata cobre oito policies (retry, hedge, circuit breaker, rate limiter, adaptive limit, timeout, bulkhead, fallback) para Java 21+ puro, com código gerado em tempo de compilação. **Continue no Resilience4j** se você precisa de algo destes, que o resilience4cata ainda não tem:

- integração com Spring Boot 3, CDI ou AspectJ (o resilience4cata se integra apenas com Spring Boot 4.1+, [abaixo](#spring-boot));
- operadores de RxJava ou de coroutines Kotlin (o resilience4cata suporta `CompletionStage`, `CompletableFuture` e, com `resilience4cata-reactor`, `Mono` e `Flux` do Reactor: [abaixo](#reactor));
- um bulkhead de thread pool, um cache ou um time limiter para futures arbitrários;
- mudar a configuração de uma policy em tempo de execução (o resilience4cata resolve a configuração uma vez, por decisão de projeto);
- uma versão do Java anterior à 21.

**Considere migrar** se o overhead e a alocação por chamada importam para você (veja [Desempenho](../performance.md)), se você quer que erros de configuração sejam detectados em tempo de compilação, ou se você prefere anotações simples e código gerado que você pode ler a proxies AOP.

## O modelo mental :id=mental-model

| | Resilience4j | resilience4cata |
|---|---|---|
| Onde as policies vivem | decorators (`Decorators.ofSupplier(…)`, `Retry.decorateSupplier(…)`) ou aspectos do Spring AOP em volta de um bean | uma classe gerada em tempo de compilação: `<Interface>$Resilient` envolve a sua implementação, ou `<Class>$Resilient` estende a sua classe |
| Quando um erro de configuração é encontrado | na inicialização ou na primeira chamada | a maior parte quando você compila ([códigos de erro](../errors.md)) |
| Ordem das policies | a ordem em que você compõe os decorators; com Spring, uma ordem fixa de aspectos (configurável) | por padrão Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → método; `@PolicyOrder` a muda por método, tipo ou bundle |
| Registries | um por policy (`CircuitBreakerRegistry`, `RetryRegistry`, …) | um `ResilienceRegistry` para todas as policies |
| Instâncias | buscadas pelo nome | uma por método anotado, por padrão; métodos com o mesmo `name` compartilham uma |

## Correspondência de conceitos :id=concepts

| Resilience4j | resilience4cata |
|---|---|
| `CircuitBreakerRegistry.of(config)`, `RetryRegistry.of(…)`, … | `ResilienceRegistry.builder()…build()` |
| `CircuitBreakerConfig.custom()…build()` registrado como config nomeada | `registryBuilder.circuitBreaker("name", b -> b…)`, usado com `@CircuitBreaker(config = "name")`; ou `configs.<name>.circuitBreaker.*` em properties |
| config padrão (`configs.default` no Spring) | `registryBuilder.circuitBreakerDefaults(b -> …)` ou `defaults.circuitBreaker.*` |
| `registry.circuitBreaker("inventory")` + `decorateSupplier` | `@CircuitBreaker(name = "inventory")` no método da interface + `Resilient.of(…)` |
| `Decorators.ofSupplier(…).withRetry(…).withCircuitBreaker(…)` para código que você não pode anotar | [`Pipeline.builder(registry, id).retry().circuitBreaker()…build()`](../guides/pipeline.md) |
| `@CircuitBreaker(name = "x", fallbackMethod = "f")` do Spring | `@CircuitBreaker(name = "x")` + `@Fallback(method = "f")` |
| `@Retry`, `@RateLimiter`, `@Bulkhead`, `@TimeLimiter` do Spring | `@Retry`, `@RateLimiter`, `@Bulkhead`, `@Timeout` (pacote `dev.resilience4cata.annotation`) |
| `resilience4j.circuitbreaker.instances.x.*` | `instances.x.circuitBreaker.*` ([chaves](../reference/configuration.md)) |
| `baseConfig: other` | `configs.<name>.extends=other` |
| `circuitBreaker.getEventPublisher().onStateTransition(…)` | `registryBuilder.listen(CircuitBreakerEvent.StateTransition.class, …)` ([eventos](../guides/observability.md#events)) |
| `circuitBreaker.getMetrics()` | `registry.circuitBreaker(id).snapshot()` |
| `transitionToForcedOpenState()`, `transitionToDisabledState()`, `reset()` | `forceOpen()`, `disable()`, `reset()` em `CircuitBreakerHandle` |
| `resilience4j-micrometer` (`TaggedCircuitBreakerMetrics`, …) | `resilience4cata-micrometer` ([Micrometer](../guides/micrometer.md)); os nomes dos meters são diferentes (`r4c.*`), então dashboards e alertas precisam de novas consultas |
| `RetryConfig.retryOnResult(…)`, `CircuitBreakerConfig.recordResult(…)` | um [`Classifier`](../guides/classifiers.md) em `@Retry` / `@CircuitBreaker` |
| `failAfterMaxAttempts(true)` (`MaxRetriesExceededException`) | `@Retry(onExhaustedResult = THROW)` (`ResultRejectedException`) |
| `IntervalFunction.ofExponentialBackoff(…)`, `ofExponentialRandomBackoff(…)` | `backoff`, `multiplier`, `maxDelay`, `jitter` |
| `TimeLimiter` + um executor para código bloqueante | `@Timeout` na thread do chamador |
| `resilience4j-hedge`: `Hedge.of(config).decorateCompletionStage(…)` | [`@Hedge`](../policies/hedge.md) em um método que devolve um `CompletableFuture`, ou `Pipeline…hedge(…)` com `executeAsync` ([correspondência](#map-hedge)) |
| `mono.transformDeferred(RetryOperator.of(retry))` e os outros operadores do Reactor, em um `Mono` | um método `Mono` com as anotações, ou `mono.transform(MonoPipelineOperator.of(pipeline))` ([abaixo](#reactor)) |
| os mesmos operadores em um `Flux` | um método `Flux` com as anotações, ou `flux.transform(FluxPipelineOperator.of(pipeline))` ([abaixo](#reactor)) |
| `ThreadPoolBulkhead`, `Cache`, operadores de RxJava/Kotlin | sem equivalente |

## Correspondência de configuração :id=config-mapping

Métodos de builder do Resilience4j (as propriedades do Spring Boot usam os mesmos nomes em kebab-case) e o atributo do resilience4cata com o mesmo papel. Os padrões diferem em alguns pontos; as colunas **Padrão** são as de cada biblioteca.

### Circuit breaker :id=map-circuit-breaker

| Resilience4j | Padrão | resilience4cata | Padrão |
|---|---|---|---|
| `failureRateThreshold` | 50 | `failureRateThreshold` | 50 |
| `slowCallRateThreshold` | 100 | `slowCallRateThreshold` | 100 |
| `slowCallDurationThreshold` | 60 s (chamadas sempre cronometradas) | `slowCallDuration` | 0 = desligado (chamadas não cronometradas) |
| `slidingWindowType` | `COUNT_BASED` | `windowType` | `COUNT` |
| `slidingWindowSize` | 100 | `windowSize` | 100 |
| `minimumNumberOfCalls` | 100 | `minimumCalls` | 100 |
| `waitDurationInOpenState` | 60 s | `waitInOpen` | 60 s |
| `permittedNumberOfCallsInHalfOpenState` | 10 | `halfOpenCalls` | 10 |
| `maxWaitDurationInHalfOpenState` | 0 | `maxWaitInHalfOpen` | 0 |
| `recordExceptions` / `recordException(predicate)` | todas as exceções | `recordOn` (classes) ou `classifier` | `Throwable` |
| `ignoreExceptions` | nenhuma | `ignoreOn` | nenhuma |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `false` | sem equivalente: half-open na próxima chamada | — |
| `waitIntervalFunctionInOpenState`, `transitionOnResult`, `initialState`, `slidingWindowSynchronizationStrategy` | | sem equivalente | — |
| `writableStackTraceEnabled` | `true` | não é necessário: as rejeições são sempre pré-alocadas e sem stack trace | — |

### Retry :id=map-retry

| Resilience4j | Padrão | resilience4cata | Padrão |
|---|---|---|---|
| `maxAttempts` | 3 | `maxAttempts` | 3 |
| `waitDuration` | 500 ms, fixo | `delay` (+ `backoff = FIXED, jitter = NONE` para o mesmo comportamento) | 200 ms, exponencial, full jitter |
| `intervalFunction(IntervalFunction.ofExponentialBackoff(…))` | | `backoff = EXPONENTIAL`, `multiplier`, `maxDelay` | ×2, máx. 10 s |
| `IntervalFunction.ofRandomized(…)`, `ofExponentialRandomBackoff(…)` | | `jitter = FULL` ou `EQUAL` | `FULL` |
| `retryExceptions` / `retryOnException(predicate)` | todas as exceções | `retryOn` ou `classifier` | `Exception` |
| `ignoreExceptions` | nenhuma | `abortOn` | — (um breaker aberto nunca recebe retry, de qualquer forma) |
| `retryOnResult(predicate)` | | `classifier` | — |
| `failAfterMaxAttempts` | `false` | `onExhaustedResult = THROW` | `RETURN` |
| sem equivalente | | `maxDuration` (orçamento de tempo para todas as tentativas) | 0 = ilimitado |

### Rate limiter, timeout, bulkhead :id=map-others

| Resilience4j | Padrão | resilience4cata | Padrão |
|---|---|---|---|
| `RateLimiterConfig.limitForPeriod` | 50 | `@RateLimiter(permits)` | 50 |
| `RateLimiterConfig.limitRefreshPeriod` | 500 ns | `@RateLimiter(period)` | 1 s |
| `RateLimiterConfig.timeoutDuration` | 5 s | `@RateLimiter(maxWait)` | 0 = rejeita na hora |
| `RateLimiterConfig.drainPermissionsOnResult` | | sem equivalente | — |
| `TimeLimiterConfig.timeoutDuration` | 1 s | `@Timeout(value)` | 1 s |
| `TimeLimiterConfig.cancelRunningFuture` | `true` | sempre: o stage de uma chamada assíncrona é cancelado se for um `Future` | — |
| `BulkheadConfig.maxConcurrentCalls` | 25 | `@Bulkhead(maxConcurrent)` | 25 |
| `BulkheadConfig.maxWaitDuration` | 0 | `@Bulkhead(maxWait)` | 0 |
| `BulkheadConfig.fairCallHandlingStrategyEnabled` | `true` | sem equivalente: o semáforo de espera não é justo | — |

> [!WARNING]
> Três padrões pegam as pessoas de surpresa: o retry do resilience4cata usa **backoff exponencial com full jitter a partir de 200 ms**, não 500 ms fixos; seu rate limiter **rejeita na hora** em vez de esperar até 5 s; e seu breaker **não cronometra as chamadas** a menos que você defina `slowCallDuration`. Defina-os explicitamente ao migrar.

### Hedge :id=map-hedge

O módulo `resilience4j-hedge` do Resilience4j (2.x) é apenas programático e assíncrono, como o nosso (sem anotação do Spring), e inicia no máximo um hedge.

| Resilience4j | resilience4cata | Notas |
|---|---|---|
| `HedgeConfig.Builder.preconfiguredDuration(d)` | `@Hedge(delay = "…")` | O nosso tem padrão de 1 s; `"0"` inicia todas as tentativas de uma vez |
| `averagePlusPercentageDuration(…)`, `averagePlusAmountDuration(…)` | sem equivalente | Apenas um delay fixo; defina-o perto do p95 da dependência |
| um hedge, sempre | `@Hedge(maxAttempts = 2)` (o padrão) | Até 10 tentativas |
| `withMaxConcurrency(n)` (tamanho core do thread pool do hedge) | sem equivalente | Os hedges começam em novas virtual threads; o [orçamento de hedge](../policies/hedge.md#the-hedge-budget) limita o número deles em vez disso |
| `withContextPropagators(…)` | sem equivalente | As tentativas 2+ rodam sem os `ThreadLocal`s do chamador |
| `submit(callable, executor)` para código bloqueante | sem equivalente | Métodos síncronos são um erro de compilação ([R4C-HDG-001](../errors.md#r4c-hdg-001)): devolva um `CompletableFuture` |
| `HedgeOnPrimarySuccessEvent`, `…OnSecondarySuccessEvent`, `…Failure…` | `HedgeEvent.Hedged`, `Won`, `Skipped`, `Failed` | Uma primeira tentativa que vence antes do delay não publica nada |

A semântica difere: no Resilience4j vence a primeira tentativa a **terminar**, então uma falha rápida de qualquer tentativa faz a chamada falhar; no resilience4cata vence o primeiro **sucesso**, uma falha em `hedgeOn` inicia a próxima tentativa na hora, e a chamada só falha quando todas as tentativas falharam (ou uma falha com uma falha de `abortOn`). A perdedora é cancelada, e o circuit breaker, o bulkhead e o timeout dentro do hedge liberam o que ela ocupava.

## Nomes de métricas :id=metric-names

Dashboards e alertas construídos sobre `resilience4j-micrometer` precisam de novas consultas: os meters se chamam `r4c.*` e todos têm a tag `name`, o id da instância ([Nomes de métricas](../guides/observability.md#metric-names)). Os nomes do Resilience4j 2.4.0 e o meter do resilience4cata mais próximo:

| Resilience4j | resilience4cata | Nota |
|---|---|---|
| `resilience4j.circuitbreaker.state` | `r4c.circuitbreaker.state` | 1 para o estado atual, 0 para os outros, em ambos |
| `resilience4j.circuitbreaker.failure.rate`, `.slow.call.rate` | `r4c.circuitbreaker.failure.rate`, `.slow.call.rate` | percentuais em ambos |
| `resilience4j.circuitbreaker.not.permitted.calls`, `.calls{kind=not_permitted}` | `r4c.calls.rejected{kind=circuitbreaker}` | |
| `resilience4j.circuitbreaker.calls{kind=successful\|failed\|ignored}` | nenhum | sem contadores por chamada ([abaixo](#no-success-metrics)) |
| `resilience4j.circuitbreaker.buffered.calls`, `.slow.calls` | nenhum | `bufferedCalls` e `slowCalls` no snapshot |
| `resilience4j.retry.calls{kind=…}` | `r4c.retry.retries`, `r4c.retry.gave.up{reason}` | apenas retries e desistências, sem contagem de sucessos |
| `resilience4j.timelimiter.calls{kind=timeout}` | `r4c.timeout.timed.out` | |
| `resilience4j.bulkhead.available.concurrent.calls` | `r4c.bulkhead.concurrent.calls` | o nosso conta as vagas em uso, não as livres |
| `resilience4j.bulkhead.max.allowed.concurrent.calls` | `r4c.bulkhead.max.concurrent` | |
| `resilience4j.ratelimiter.available.permissions` | `r4c.ratelimiter.available.permits` | |
| `resilience4j.ratelimiter.waiting_threads` | nenhum | |
| `resilience4j.bulkhead.thread.pool.size` e os outros meters do bulkhead de thread pool | nenhum | não há bulkhead de thread pool |

## Diferenças de comportamento :id=differences

### A ordem :id=order

Fallback → Retry (ou Hedge) → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → método por padrão, qualquer que seja a ordem das anotações. Ela coincide com a ordem padrão dos aspectos do Spring no Resilience4j para as policies que ambos têm. Onde o Resilience4j muda a ordem globalmente com as propriedades `<module>AspectOrder`, o resilience4cata a muda por método, tipo ou bundle com [`@PolicyOrder`](../policies/README.md#changing-the-order), em tempo de compilação; o fallback continua sendo o mais externo.

### Um breaker aberto interrompe os retries :id=open-breaker

Um retry nunca retenta `CallNotPermittedException`, diga o que disser o seu `abortOn`, então, assim que o breaker abre, o retry desiste na hora em vez de continuar tentando contra ele. No Resilience4j você mesmo adiciona essa exceção a `ignoreExceptions`.

### Um bulkhead cheio não abre o breaker :id=local-rejections

O breaker nunca registra `BulkheadFullException`, `RateLimitExceededException` ou `AdaptiveLimitFullException`, digam o que disserem seu `recordOn` ou `ignoreOn`: elas são a carga desta própria aplicação, não falhas da dependência. No Resilience4j, o bulkhead e o rate limiter também ficam dentro do breaker, e suas `BulkheadFullException` e `RequestNotPermitted` contam como falhas a menos que você as adicione a `ignoreExceptions`, então um pico pode abrir o breaker enquanto a dependência está bem. Remova essas entradas ao migrar; nada as substitui.

### Errors não recebem retry por padrão :id=errors

`retryOn` tem padrão `Exception`: um `Error` (por exemplo `OutOfMemoryError`, `AssertionError`) não recebe retry. O breaker o registra como falha, como o Resilience4j faz (`recordOn` tem padrão `Throwable`). `@Fallback(applyOn)` tem padrão `Throwable`, então um fallback captura `Error`s a menos que você os adicione a `skipOn`.

### Timeouts interrompem a thread do chamador :id=timeouts

Um `@Timeout` síncrono não move a chamada para outra thread: ela roda onde você a chamou, e um watchdog compartilhado a interrompe quando o tempo acaba. Código que ignora interrupções roda até o fim antes de o chamador receber `CallTimeoutException`. Os timeouts nunca são adiantados e atrasam no máximo uns dois ticks do watchdog (ticks de 1–50 ms). Veja [Timeout](../policies/timeout.md).

### Breakers passam a half-open na próxima chamada :id=half-open

Não há timer que mova um breaker aberto para half-open: a primeira chamada depois de `waitInOpen` faz isso. Um snapshot tirado nesse meio-tempo ainda diz `OPEN`.

### A configuração é resolvida uma vez :id=no-reload

Valores das anotações, configs nomeadas e configuração externa são combinados quando a instância é criada e nunca mudam depois. Não há `changeConfig`, nem evento de refresh, nem hot reload. Os listeners também são fixados quando o registry é construído.

### Autoinvocação no estilo de interface :id=self-invocation

Com `Resilient.of`, uma chamada da sua implementação a um método dela mesma pula as policies, como um proxy do Spring. Classes anotadas (o estilo de subclasse) interceptam `this.method()`. Veja [Classes anotadas](../guides/annotated-classes.md).

### Instâncias são por método, a menos que nomeadas :id=instances

Uma instância do Resilience4j é compartilhada por tudo que usa o nome dela. No resilience4cata, cada método anotado recebe suas próprias instâncias, com um id como `com.acme.StockClient#stock(java.lang.String)`; dê aos métodos o mesmo `name` para compartilhar uma (suas configurações resolvidas devem então ser idênticas, [R4C-REG-008](../errors.md#r4c-reg-008)). `@Timeout` não tem `name`.

### O processor é obrigatório :id=processor

Sem o annotation processor nada é gerado e `Resilient.of` falha com [R4C-RT-003](../errors.md#r4c-rt-003). No Spring Boot, Micronaut e Quarkus, um bean cuja classe ou interface tem anotações mas nenhuma classe gerada faz a inicialização (ou o build do Quarkus) falhar com [R4C-SPR-006](../errors.md#r4c-spr-006), [R4C-MN-007](../errors.md#r4c-mn-007) ou [R4C-QK-008](../errors.md#r4c-qk-008), em vez de rodar sem proteção. Não há alternativa por reflection ou proxy.

### Sem métricas de sucesso ou latência :id=no-success-metrics

O Resilience4j conta cada chamada (os meters `calls` do retry e do breaker, e os do time limiter). O resilience4cata não mantém contador por chamada, porque isso seria uma escrita compartilhada em cada chamada; ele conta o que dá errado (rejeições, retries, desistências, timeouts, fallbacks) e expõe o estado como gauges. Conte sucessos e latência onde você já mede as requisições, por exemplo com o `@Timed` do Micrometer ou as métricas do seu servidor HTTP ([por quê](../faq.md#success-metrics)).

### Classes anotadas e `@Transactional` :id=transactions

Com as anotações do Spring em uma classe, o aspecto do Resilience4j e o proxy de transação do Spring são ordenados pela precedência dos aspectos. No resilience4cata, uma classe anotada se torna uma subclasse gerada, e o proxy do Spring fica em volta dela, fora das policies: uma única transação abrangeria todas as tentativas. Um bean que tem retry (ou hedge) e é transacional ao mesmo tempo, portanto, interrompe a inicialização com [`R4C-SPR-004`](../errors.md#r4c-spr-004) (Micronaut `R4C-MN-005`, Quarkus `R4C-QK-002`). Coloque as policies em uma interface, ou mova a transação para outro bean ([Spring: classes anotadas](../guides/spring.md#classes)).

### Hedges não levam thread-locals :id=hedge-context

Uma tentativa de hedge começa em uma nova virtual thread sem os `ThreadLocal`s ou o MDC do chamador, como um retry assíncrono ([Hedge](../policies/hedge.md)). Já o contexto do Reactor chega a cada tentativa de um método `Mono`.

## O mesmo serviço, das duas formas :id=example

Uma consulta de estoque com um retry (3 tentativas, com 500 ms de intervalo, apenas em `IOException`), um breaker compartilhado sob o nome `inventory`, um timeout de 2 s e um fallback.

**Resilience4j**, com decorators:

<!-- not-compiled: código do Resilience4j -->
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

**resilience4cata**: as anotações na interface,

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

e o ponto de chamada:

<!-- snippet: migrate-r4c-use -->
```java
ResilienceRegistry registry = ResilienceTesting.registry(clock).build(); // ResilienceRegistry.builder() in production
StockClient stock = Resilient.of(StockClient.class, sku -> {
    calls.incrementAndGet();
    throw new IOException("inventory unreachable");
}, registry);

int units = stock.stock("SKU-1"); // three attempts 500 ms apart, then the fallback
```

A chamada síncrona continua síncrona: não há executor, porque o timeout interrompe a thread do chamador em vez de mover a chamada para outra.

**Configuração externa.** As chaves do `application.yml` do Spring Boot e seus equivalentes do resilience4cata em um arquivo `.properties` (`instances.inventory.*` sobrescreve a anotação para essa instância; `defaults.*` apenas preenche atributos que a anotação não define):

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

Carregue-o com `ResilienceConfig.load()` (como `resilience4cata.properties` no classpath) ou `ResilienceConfig.builder().classpath(…)`, e depois `config.applyTo(ResilienceRegistry.builder())`. Veja [Configuração](../guides/configuration.md).

## Do resilience4j-spring-boot3 :id=spring-boot

`resilience4cata-spring` substitui `resilience4j-spring-boot3` no Spring Boot 4.1 ou mais recente
([guia do Spring Boot](../guides/spring.md)). Remova `spring-boot-starter-aop`: nada é tecido nem envolvido em proxy; o
annotation processor gera as classes protegidas, e a integração faz o Spring instanciá-las.

| resilience4j-spring-boot3 | resilience4cata-spring |
|---|---|
| `resilience4j.<module>.instances.<name>.<key>` | `resilience4cata.instances.<name>.<policy>.<attribute>` (`circuit-breaker`, `retry`, `rate-limiter`, `timeout`, `bulkhead`) |
| `resilience4j.<module>.configs.default.<key>` | `resilience4cata.defaults.<policy>.<attribute>` |
| `resilience4j.<module>.configs.<name>.<key>` | `resilience4cata.configs.<name>.<policy>.<attribute>`, usado por `@Retry(config = "<name>")` e similares |
| `baseConfig: <name>` | `resilience4cata.configs.<config>.extends: <name>`; uma anotação escolhe sua config com `config = "<name>"` |
| propriedades `<module>AspectOrder` | `@PolicyOrder` no método, no tipo ou em um bundle ([Mudar a ordem](../policies/README.md#changing-the-order)) |
| beans `CircuitBreakerConfigCustomizer`, `RetryConfigCustomizer`… | beans `ResilienceRegistryCustomizer` |
| beans `RegistryEventConsumer`, `getEventPublisher()` | beans `EventListener<…>` |
| `/actuator/circuitbreakers`, `/actuator/retries`, `/actuator/ratelimiters`… | `/actuator/resilience4cata` (todas as policies, somente leitura) |
| `/actuator/circuitbreakerevents` e os outros endpoints de eventos | nenhum: nenhum histórico de eventos é mantido; use beans listener ou métricas |
| health indicators, desligados por padrão (`registerHealthIndicator`) | o indicador `resilience4cata`, ligado, mas UP com breakers abertos a menos que listados em `resilience4cata.spring.health.down-when-open` |
| métricas `resilience4j.circuitbreaker.*`… | métricas `r4c.*` ([Micrometer](../guides/micrometer.md)), com `resilience4cata-micrometer` |
| `fallbackMethod = "f"` resolvido por reflection | `@Fallback(method = "f")`, verificado em tempo de compilação |

As mesmas chaves em `application.yml`:

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

Erros de digitação fazem a inicialização falhar com o arquivo e a linha da chave, em vez de serem ignorados.

## Do resilience4j-reactor :id=reactor

Um `Mono` decorado com os operadores do Resilience4j:

<!-- not-compiled: código do Resilience4j -->
```java
Mono<Price> price = webClient.get().uri("/prices/{sku}", sku).retrieve().bodyToMono(Price.class)
        .transformDeferred(TimeLimiterOperator.of(timeLimiter))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .transformDeferred(RetryOperator.of(retry));
```

se torna, com `resilience4cata-reactor`, um método `Mono` com as anotações (`@Retry`, `@CircuitBreaker`, `@Timeout` em `Mono<Price> price(String sku)`), ou, para código que você não pode anotar, um único operador para todo o pipeline: `.transform(MonoPipelineOperator.of(pipeline))`. Veja [Project Reactor](../guides/reactor.md).

| resilience4j-reactor | resilience4cata-reactor |
|---|---|
| um operador por policy, aninhados pela ordem de `transformDeferred` | uma anotação por policy, aninhadas na [ordem padrão](#order) ou com `@PolicyOrder`; um `MonoPipelineOperator` para um pipeline |
| o retry assina o mesmo `Mono` de novo | um método `Mono` é chamado de novo a cada tentativa (um novo `Mono`); `MonoPipelineOperator` assina a mesma fonte de novo, `defer(...)` a constrói de novo |
| `TimeLimiterOperator`: `Mono.timeout` no scheduler paralelo do Reactor, armado a cada assinatura | `@Timeout` no timer da biblioteca, pulado para um valor que já está lá na assinatura |
| em um `Flux`, o retry assina de novo após uma falha, mesmo quando elementos foram emitidos (eles vêm de novo) | em um método `Flux`, o retry e o fallback atuam apenas antes do primeiro elemento; uma falha posterior chega ao assinante |
| `TimeLimiterOperator` em um `Flux`: `flux.timeout`, um timer agendado para cada elemento | `@Timeout` reiniciado a cada elemento sem um timer por elemento (uma leitura do relógio) |
| `CircuitBreakerOperator` em um `Flux` cronometra o stream inteiro para chamadas lentas | o tempo até o primeiro elemento |

## Passo a passo :id=steps

1. Adicione as dependências, incluindo o processor no processor path ([Instalação](../start/installation.md)); com Spring Boot, `resilience4cata-spring` ([acima](#spring-boot)).
2. Para cada chamada decorada, coloque a chamada atrás de uma interface (ou anote a classe) e traduza a configuração com as tabelas acima. Defina `backoff`, `jitter` e `maxWait` explicitamente onde os padrões diferem.
3. Substitua `Decorators`/anotações do Spring por anotações do resilience4cata e `Resilient.of` (ou `Resilient.create` para classes anotadas). Chamadas que você não pode colocar atrás de uma interface passam por um [`Pipeline`](../guides/pipeline.md).
4. Mova os consumidores de eventos para `ResilienceRegistry.Builder.listen(…)`, e as métricas para o [módulo Micrometer](../guides/micrometer.md).
5. Substitua testes baseados em tempo por [`ManualClock`](../guides/testing.md).
6. Faça o build com `-Xlint:all -Werror` se quiser: o código gerado compila sem avisos.
