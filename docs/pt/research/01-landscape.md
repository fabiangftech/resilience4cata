# 01 — Panorama: Resilience4j e trabalhos anteriores

> **Histórico.** Esta pesquisa é anterior à implementação e é mantida como foi escrita; para saber o que a biblioteca faz hoje, veja [funcionalidades](../policies/README.md), [arquitetura](../architecture.md) e [benchmarks](../benchmarks.md).

> Pesquisa da fase 1. Sem código de produção. Última atualização: 2026-10-23.
> Escopo: o que existe, como a referência (Resilience4j) funciona por dentro, de onde vêm seus custos e o que uma nova biblioteca orientada a anotações, em Java puro, para Java 21+, pode fazer melhor.
> Fora do escopo (coberto pelos documentos irmãos): a escolha do mecanismo de processamento de anotações, o desenho detalhado das primitivas de concorrência, a superfície de API/configuração, a organização dos módulos.

## Legenda de evidências :id=evidence-legend

Cada afirmação é marcada:

| Marca | Significado |
|---|---|
| **[SRC]** | Verificado lendo o código-fonte. Resilience4j no commit `b7c0069` de `master` (2026-10-07, após a 2.4.0); Failsafe em `master` (último commit em 2025-12-28); Sentinel `master` pela API do GitHub. |
| **[BENCH]** | Verificado a partir de um benchmark publicado ou de um arquivo de resultados em um repositório. |
| **[DOC]** | Verificado na documentação oficial, em notas de versão ou no README de um projeto. |
| **[ISSUE]** | Verificado em uma issue/PR do GitHub (um relato de usuário, não necessariamente confirmado pelos mantenedores). |
| **[INF]** | Inferido por nós (raciocínio a partir do código/comportamento da JVM, não medido). Deve ser validado com JMH em uma fase posterior. |

---

## 1. Resilience4j em profundidade :id=_1-resilience4j-in-depth

### 1.1 Situação e forma :id=_11-status-and-shape

- Última versão **2.4.0** (2026-03-14); 2.3.0 (2025-01-03); ~10,8 mil estrelas; ~348 issues abertas. [DOC] ([releases](https://github.com/resilience4j/resilience4j/releases))
- A 2.4.0 elevou a base de compilação do JDK 17 para o **JDK 21** "for support virtual thread" (PR #2331); o `build.gradle` define `sourceCompatibility = JavaVersion.VERSION_21`. [DOC][SRC] Portanto, "Java 21" por si só já não é um diferencial.
- O Vavr deixou de ser dependência na 2.0.0. [DOC] ([RELEASENOTES.adoc](https://github.com/resilience4j/resilience4j/blob/master/RELEASENOTES.adoc))
- Build Gradle multiprojeto, ~38 módulos: `circuitbreaker`, `retry`, `ratelimiter`, `bulkhead`, `timelimiter`, `cache` (JCache), `hedge` (mais recente), `circularbuffer`, `consumer`, `core`, `annotations`, `micrometer`, `metrics`, `feign`, `kotlin`, `reactor`, `rxjava2/3`, `vavr`, `spring6`, `spring-boot3`, `spring-boot4`, `micronaut`, `commons-configuration`, `framework-common`, `all`, `bom`, `test`. [SRC]

### 1.2 Funcionalidades por módulo :id=_12-features-per-module

| Módulo | O que faz | Notas de implementação |
|---|---|---|
| **CircuitBreaker** | CLOSED / OPEN / HALF_OPEN mais DISABLED, FORCED_OPEN, METRICS_ONLY. Janela deslizante por contagem ou por tempo; limiares de taxa de falhas **e** de taxa de chamadas lentas; `minimumNumberOfCalls`; predicados de exceções a registrar/ignorar; predicado de resultado. | Um objeto de estado por estado atrás de `AtomicReference<CircuitBreakerState>`; transições por CAS. Um `ReentrantLock` protege apenas OPEN→HALF_OPEN. [SRC] |
| **Retry** | máximo de tentativas, `IntervalFunction` (fixa, exponencial, aleatória, personalizada), predicados de retry por exceção / por resultado, síncrono + assíncrono. | Um novo `ContextImpl` por chamada, com um `AtomicInteger` e dois `AtomicReference`s (4 objetos por chamada mesmo quando a primeira tentativa tem sucesso); dorme por meio de uma função estática `Thread::sleep`. [SRC] |
| **RateLimiter** | `AtomicRateLimiter` (padrão, baseado em ciclos) e `SemaphoreBasedRateLimiter` (renovação por scheduler). | Veja §1.4. |
| **Bulkhead** | `SemaphoreBulkhead` (`java.util.concurrent.Semaphore`, justiça opcional, `maxWaitDuration`) e `FixedThreadPoolBulkhead` (`ThreadPoolExecutor` + `ArrayBlockingQueue`/`SynchronousQueue`, `CompletableFuture.supplyAsync`, `ContextPropagator`). | [SRC] |
| **TimeLimiter** | Timeouts para `Future` (`future.get(timeout)`) e `CompletionStage` (agenda uma `TimeoutException` em um `ScheduledExecutorService`). | Não consegue interromper uma chamada síncrona simples: precisa de um future, então o código síncrono precisa ser levado para outra thread. [SRC] |
| **Cache** | Envolve o JCache (JSR-107). | Pouco usado; wrapper fino. [SRC][INF] |
| **Hedge** | Requisições hedged (issue #1454). | Módulo mais recente. [SRC] |
| **Fallback** | Sem módulo próprio no core. Disponível como `Decorators.withFallback(...)` e, com anotações, como uma string `fallbackMethod = "name"` resolvida por reflexão nas integrações Spring/Micronaut. | Veja §1.3. [SRC] |
| **Registries** | `CircuitBreakerRegistry` etc. com configurações nomeadas ("default" mais as nomeadas); `InMemoryRegistryStore` sobre `ConcurrentHashMap<String, CompletableFuture<E>>`, evitando de propósito o `computeIfAbsent` para não fixar (pinning) virtual threads. | [SRC] |
| **Eventos** | `EventProcessor` com consumidores em `CopyOnWriteArraySet`, mais um mapa por tipo de evento. Guarda `hasConsumers()`. | Veja §1.5. [SRC] |

### 1.3 Como as anotações funcionam, e o que recebem os usuários de Java puro :id=_13-how-the-annotations-work-and-what-plain-java-users-get

O módulo `resilience4j-annotations` contém **apenas declarações de anotações**: `@CircuitBreaker(name, configuration, fallbackMethod)`, `@Retry`, `@RateLimiter`, `@Bulkhead`, `@TimeLimiter`, `@Timer`. Não tem processador. [SRC] ([CircuitBreaker.java](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-annotations/src/main/java/io/github/resilience4j/circuitbreaker/annotation/CircuitBreaker.java))

O comportamento é implementado por:

- **Spring** (`resilience4j-spring6`, `spring-boot3/4`): aspectos `@Around` anotados com AspectJ, executados por proxies do Spring AOP (`CircuitBreakerAspect`, `RetryAspect`, ...). [SRC]
- **Micronaut** (`resilience4j-micronaut`): `MethodInterceptor`s do AOP em tempo de compilação do Micronaut. [SRC]

**Java puro não recebe nada declarativo.** Sem Spring ou Micronaut, o usuário precisa usar a API funcional de decoradores (`CircuitBreaker.decorateSupplier(cb, supplier)`, `Decorators.ofSupplier(..).withRetry(..).withCircuitBreaker(..)`) ou configurar por conta própria o weaving do AspectJ (os aspectos do Spring estão presos a beans do Spring: `SpelResolver`, `BeanFactory`, `FallbackExecutor`). [SRC][INF] Esta é a maior lacuna de ergonomia para o nosso público-alvo.

**Trabalho por chamada no aspecto do Spring.** `CircuitBreakerAspect.circuitBreakerAroundAdvice` faz o seguinte em **toda** invocação [SRC] ([aspect](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/circuitbreaker/configure/CircuitBreakerAspect.java), [DefaultSpelResolver](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/spelresolver/DefaultSpelResolver.java), [FallbackExecutor](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackExecutor.java), [FallbackMethod](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackMethod.java)):

1. `MethodSignature.getMethod()`, depois monta `methodName` por concatenação de strings (`declaringClass.getName() + "#" + method.getName()`).
2. `spelResolver.resolve(method, args, annotation.name())`: até quatro verificações por regex `Pattern.matcher(...).matches()` sobre o nome, mesmo para um nome literal. Se for uma expressão, cria um novo `MethodBasedEvaluationContext` e **faz o parse da expressão SpEL a cada chamada**.
3. Busca no registry `getOrCreateCircuitBreaker(methodName, backend, configKey)`.
4. Aloca uma lambda `CheckedSupplier` que captura o join point.
5. `FallbackExecutor.execute` resolve o nome do fallback via SpEL **de novo**, depois chama `FallbackMethod.create(...)`, que aloca um `MethodMeta`, clona `getParameterTypes()`, faz uma busca `ConcurrentHashMap.computeIfAbsent` em um cache estático e aloca um novo `FallbackMethod`. Depois, o fallback é invocado por `Method.invoke` reflexivo.
6. O tipo de retorno é inspecionado (`CompletionStage`, lista de extensões Reactor/RxJava) para escolher um handler.

Usuários relataram que o aspecto é lento (issue [#1377](https://github.com/resilience4j/resilience4j/issues/1377), "CircuitBreaker advice taking 2s", muito provavelmente um artefato de configuração/chamada lenta, sem resolução). [ISSUE] Também há um pedido aberto para suportar **meta-anotações** / anotações compostas ([#2405](https://github.com/resilience4j/resilience4j/issues/2405), 2026-03). [ISSUE]

**Conclusão:** tudo nos passos 1–6, exceto a permissão/registro em si, é invariante por método e pode ser resolvido uma única vez, em tempo de build ou na primeira chamada. [INF]

### 1.4 Internos do core :id=_14-core-internals

**Máquina de estados do circuit breaker** ([CircuitBreakerStateMachine.java](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java)) [SRC]
- `decorateSupplier` chama `acquirePermission()`, lê o timestamp de início, executa o supplier e então `onResult(duration, unit, result)` ou `onError(...)`. `java.lang.Error` não é tratado.
- Os timestamps vêm de `Function<Clock, Long> currentTimestampFunction`, cujo padrão é `clock -> System.nanoTime()`. O retorno como **`Long` boxed** significa uma possível alocação por timestamp, duas por chamada, a menos que a escape analysis a elimine. [SRC] para a assinatura, [INF] para a alocação.
- A expiração do estado OPEN usa `clock.instant().isAfter(...)`, que aloca um `Instant` por chamada rejeitada. [SRC]/[INF]
- O caminho de registro: `ClosedState.onSuccess` → `CircuitBreakerMetrics.onSuccess` → `Metrics.record(...)` retorna um **novo `SnapshotImpl`** a cada chamada. [SRC]

**Janelas deslizantes** (`resilience4j-core/.../core/metrics`) [SRC]
- `FixedSizeSlidingWindowMetrics` (por contagem) e `SlidingTimeWindowMetrics` (por tempo): um anel de buckets `Measurement` mais uma `TotalAggregation`, com todas as mutações sob **um único `ReentrantLock`** (era `synchronized` até a 2.3.0, trocado para evitar o pinning de virtual threads, #2232). Cada `record` aloca um `SnapshotImpl`. **Este continua sendo o padrão** (`DEFAULT_SLIDING_WINDOW_SYNCHRONIZATION_STRATEGY = SYNCHRONIZED`).
- Desde a 2.3.0 há um `SlidingWindowSynchronizationStrategy.LOCK_FREE` opcional ([PR #2241](https://github.com/resilience4j/resilience4j/pull/2241), verificado com Lincheck), motivado por um usuário que viu `metrics.record` bloqueando sob carga ([#2151](https://github.com/resilience4j/resilience4j/issues/2151)). [ISSUE] A versão lock-free por contagem é uma lista de nós encadeada por CAS. **Cada registro copia a `PackedAggregation` da cauda, aloca um novo `Node` e aloca um `SnapshotImpl`**, então é lock-free, mas não livre de alocação, e tenta de novo com `CASBackoffUtil` sob contenção. [SRC]
- Defeito encontrado durante a leitura: a janela por tempo LOCK_FREE é construída com `io.github.resilience4j.core.Clock.SYSTEM`, **ignorando o `Clock` configurado pelo usuário** (o caminho SYNCHRONIZED passa `clock`) (`CircuitBreakerMetrics.buildTimeBasedMetrics`). [SRC] Isso faz com que testes com relógio falso se comportem de forma diferente entre as estratégias.

**AtomicRateLimiter** ([source](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/AtomicRateLimiter.java)) [SRC]
- `State(config, activeCycle, activePermissions, nanosToWait)` imutável em uma `AtomicReference`. O tempo é dividido em ciclos de `limitRefreshPeriod`. As permissões são repostas preguiçosamente no acesso (sem thread em segundo plano), e `activePermissions` pode ficar negativo para "reservar" permissões futuras.
- `updateStateWithBackOff`: laço de CAS. **Cada tentativa aloca um novo `State`** (as tentativas que falham viram lixo); back-off com `parkNanos(1)` quando o CAS falha. Sob contenção pesada é um desenho de uma única palavra quente: todos os chamadores fazem CAS em uma única referência. [SRC]; o comportamento sob contenção é [INF].
- A espera é feita com laços de `parkNanos` na thread chamadora.

**Bulkhead**: `java.util.concurrent.Semaphore` (AQS) na variante de semáforo. Adequado para contenção baixa a moderada; o AQS enfileira os que esperam. [SRC]/[INF]

**Retry**: o `ContextImpl` por chamada descrito acima (4 alocações). As métricas por instância são `LongAdder`s (bom). [SRC]

### 1.5 Publicação de eventos :id=_15-event-publishing

- `EventProcessor.hasConsumers()` verifica duas coleções (`CopyOnWriteArraySet` mais um `ConcurrentHashMap`). [SRC]
- `publishSuccessEvent` / `publishCircuitErrorEvent` verificam `hasConsumers()` **antes** de alocar o evento. `publishCallNotPermittedEvent`, `publishCircuitIgnoredErrorEvent`, `publishStateTransitionEvent`, `publishResetEvent` e os eventos de taxa de falhas excedida **alocam o evento primeiro** (o construtor de todo evento chama `ZonedDateTime.now(...)`) (no caminho de rejeição, que é o caminho quente quando o breaker está OPEN). [SRC] A issue [#1391](https://github.com/resilience4j/resilience4j/issues/1391) (alocação de eventos no caminho de sucesso, com uma captura de tela do profiler) continua aberta desde 2021. [ISSUE]
- **No Spring Boot, os eventos estão sempre ligados:** `CircuitBreakerConfiguration.registerEventConsumer` anexa um `CircularEventConsumer` (buffer padrão de **100**) a **todo** circuit breaker para os endpoints do actuator. Então, na implantação mais comum, cada chamada aloca um objeto de evento (`CircuitBreakerOnSuccessEvent` com um `ZonedDateTime`, uma `Duration` e um nome), mais uma inserção no buffer circular. [SRC]; a magnitude do custo é [INF].

### 1.6 Números publicados :id=_16-published-numbers

- O repositório inclui `resilience4j-circuitbreaker/src/jmh/resources/resultsWithGCProfiling.txt` (execução antiga, época do Parallel GC): [BENCH]

  | Benchmark | ops/µs | B/op |
  |---|---|---|
  | `directSupplier` (sem CB) | 12.26 | ~0 |
  | `protectedSupplier` (CB) | 4.12 | **272** |
  | `protectedSupplierWithOneConsumer` | 4.02 | 284 |

  Ou seja, historicamente ~0,16 µs e **272 bytes por chamada** de overhead para um circuit breaker sozinho, em uma única thread. Números atuais não são publicados; existem fontes JMH para CB, bulkhead, rate limiter e métricas (incluindo `VirtualThreadMetricsBenchmark`). [SRC]
- Issue [#117](https://github.com/resilience4j/resilience4j/issues/117) (2017): um mantenedor fez um protótipo de **CircuitBreaker sem alocação**, que não foi adotado porque exigia mudanças de API. [ISSUE] Isso prova que a meta é alcançável, e que é a forma da API (objetos de evento, valores de retorno `Snapshot`) que a impede.
- Issue [#1513](https://github.com/resilience4j/resilience4j/issues/1513) (TCC de graduação, R4j 1.6.1, Java 11): ~500 ns/op de overhead do CB na execução JMH do usuário. [ISSUE] (depende do hardware).
- Issue [#2023](https://github.com/resilience4j/resilience4j/issues/2023): bulkhead de semáforo vs de thread pool se comportando de forma inesperada sob carga. [ISSUE]
- Acadêmico: um artigo do SBRC compara Retry/CB no Polly e no Resilience4j e encontra impacto "leve a moderado" no tempo de execução ([artigo](https://sol.sbc.org.br/index.php/sbrc/article/download/21194/21019)). [DOC] (só o resumo)
- **Não existe nenhuma comparação JMH pública lado a lado** entre Resilience4j, Failsafe e SmallRye que tenhamos encontrado. [DOC, resultado negativo] Devemos publicar uma: é ao mesmo tempo uma ferramenta de validação e um trunfo de marketing.

### 1.7 De onde vem o overhead do Resilience4j (resumo) :id=_17-where-resilience4js-overhead-comes-from-summary

| # | Origem | Evidência |
|---|---|---|
| 1 | Resolução de nomes por SpEL/regex a cada chamada, concatenação de strings, busca do fallback, captura de lambda nos aspectos do Spring | [SRC] |
| 2 | Invocação reflexiva do fallback (`Method.invoke`) | [SRC] |
| 3 | Objetos de evento alocados por chamada sempre que existe algum consumidor (sempre no Spring Boot); alguns tipos de evento são alocados mesmo sem consumidores | [SRC] |
| 4 | `SnapshotImpl` alocado em cada `record` (nas duas estratégias); a janela lock-free também aloca `Node` + cópia da agregação | [SRC] |
| 5 | Janela deslizante padrão serializada sob um único `ReentrantLock` → contenção com muitas threads (#2151) | [SRC][ISSUE] |
| 6 | Timestamps `Long` boxed via `Function<Clock, Long>`; `Instant` no estado OPEN | [SRC] / [INF] |
| 7 | O retry aloca 4 objetos por chamada mesmo com sucesso na primeira tentativa | [SRC] |
| 8 | Rate limiter: uma única `AtomicReference<State>`, um novo `State` por tentativa de CAS | [SRC] |
| 9 | Cadeias megamórficas de `Supplier` quando os decoradores são empilhados (`Decorators`), o que limita o inlining | [INF] |
| 10 | Configuração como objetos grandes com campos `Predicate`/`Function` verificados a cada chamada (os predicados de exceções a registrar/ignorar percorrem hierarquias de classes) | [SRC] parcial / [INF] |

---

## 2. Concorrentes e trabalhos anteriores :id=_2-competitors-and-prior-art

### 2.1 Failsafe (`dev.failsafe`)

- **Situação:** 3.3.2 é a tag mais recente; últimos commits em dez. de 2025 (só CI/testes) e, antes disso, junho de 2024. **Na prática, em manutenção.** [DOC] ([repo](https://github.com/failsafe-lib/failsafe))
- **Funcionalidades:** RetryPolicy, CircuitBreaker (por contagem e por tempo, com limiares por proporção), RateLimiter (suave e em rajadas), Bulkhead, Timeout, Fallback; composição de políticas `Failsafe.with(fallback, retry, cb, timeout)`; síncrono, assíncrono (`CompletableFuture`) e um estilo `AsyncExecution` para APIs de callback; `Call` para execuções canceláveis; listeners de eventos por política. [DOC][SRC]
- **Anotações:** nenhuma. [DOC]
- **Desempenho:** o registro do estado do circuito é **`synchronized`** (`CircuitState.recordSuccess/recordFailure`, `ClosedState.checkThreshold`, `HalfOpenState`), assim como `SyncExecutionImpl.preExecute/postExecute`. Um novo `SyncExecutionImpl` é criado por chamada e um novo `ExecutionResult` por tentativa. [SRC] No Java 21–23, `synchronized` fixa (pinning) virtual threads (o [JEP 491](https://openjdk.org/jeps/491) só corrigiu isso no JDK 24). [DOC]
- **Copiar:** o modelo de composição limpo (as políticas são pura configuração; os executores são criados por composição; a política mais externa é listada primeiro); `Call`/cancelamento; um modelo de listeners consistente; zero dependências.
- **Evitar:** `synchronized` de granularidade grossa; objetos de execução por chamada; nenhuma camada declarativa.

### 2.2 SmallRye Fault Tolerance / MicroProfile Fault Tolerance (referência principal) :id=_22-smallrye-fault-tolerance--microprofile-fault-tolerance-key-reference

- **Situação:** especificação MicroProfile FT **4.1** (4.1.2, mar. de 2025). SmallRye FT **6.9.x / 6.10.x** (set. de 2026), em desenvolvimento ativo; é a implementação no Quarkus e no WildFly. [DOC] ([spec](https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html), [SmallRye](https://github.com/smallrye/smallrye-fault-tolerance))
- **Anotações da especificação:** `@Retry(maxRetries, delay, maxDuration, jitter, retryOn, abortOn)`, `@Timeout`, `@Fallback(value=Handler.class | fallbackMethod, applyOn, skipOn)`, `@CircuitBreaker(requestVolumeThreshold, failureRatio, successThreshold, delay, failOn, skipOn)`, `@Bulkhead(value, waitingTaskQueue)`, `@Asynchronous`. [DOC]
- **Configuração externa (copiar isto):** todo parâmetro de anotação pode ser sobrescrito pelo MicroProfile Config, e a chave mais específica vence: `<class>/<method>/<Annotation>/<param>` > `<class>/<Annotation>/<param>` > `<Annotation>/<param>`. O mesmo vale para `enabled`, mais um `MP_Fault_Tolerance_NonFallback_Enabled` global. [DOC] Este é exatamente o modelo "padrões + sobrescrita por método + configuração externa" que nossos objetivos descrevem, e ele está comprovado em produção.
- **Semântica que vale copiar:** um contrato de interação definido (Fallback mais externo; cada tentativa de retry passa pelo CB; o timeout recomeça a cada tentativa; o CB é verificado antes do bulkhead); o estado do circuit breaker é um singleton por (classe do bean, método). [DOC]
- **Extensões do SmallRye:** `@RateLimit`, `@ExponentialBackoff`, `@FibonacciBackoff`, `@CustomBackoff`, `@RetryWhen`, `@BeforeRetry`, tipos assíncronos de Kotlin/Mutiny/RxJava. **`@ApplyGuard("name")`** (6.7.0+, antes `@ApplyFaultTolerance`) aplica um `Guard` nomeado, compartilhado e construído programaticamente a muitos métodos, configurável via `smallrye.faulttolerance."name".retry.max-retries` (6.8.0+). [DOC] ([reusable](https://smallrye.io/docs/smallrye-fault-tolerance/6.8.0/reference/reusable.html))
- **Java puro:** `smallrye-fault-tolerance-standalone` suporta **apenas a API programática** (`Guard`, `TypedGuard`); as anotações exigem interceptadores CDI (ArC/Weld). [DOC] ([API programática](https://smallrye.io/docs/smallrye-fault-tolerance/6.9.3/reference/programmatic-api.html)) Então, como no Resilience4j, não há um caminho declarativo independente.
- **Desempenho:** o Quarkus valida as anotações e pré-calcula as cadeias de estratégias por método em tempo de build (ArC). [INF a partir da arquitetura do Quarkus; não medido aqui]
- **Evitar:** `Future` como tipo assíncrono; correspondência de exceções apenas por `Class<?>[]`; exigir um contêiner.

### 2.3 Netflix Hystrix (histórico) :id=_23-netflix-hystrix-historical

- **Situação:** em modo de manutenção desde 2018 (última 1.5.18). O README da Netflix aponta para o Resilience4j e para **limites de concorrência adaptativos**. [DOC] ([README](https://github.com/Netflix/Hystrix))
- **Lições:**
  - O isolamento por thread pool como padrão era caro (uma troca de contexto por chamada, perda dos thread-locals), e a maioria dos usuários passou para semáforos. O isolamento por semáforo/limite deve ser o padrão; delegar a outra thread deve ser opcional. [DOC/INF]
  - O padrão de objeto-comando (uma subclasse de `HystrixCommand` por chamada) é pesado e prejudicava a ergonomia. `@HystrixCommand` (javanica) precisava de AspectJ/Spring. [DOC]
  - Os "health counts" em janela rolante sobre buckets de tempo e o agrupamento de requisições (request collapsing) eram boas ideias. Os internos baseados em RxJava eram custosos. [INF]
  - Limiares estáticos e pré-ajustados são frágeis. A Netflix passou para limites adaptativos ([concurrency-limits](https://github.com/Netflix/concurrency-limits), v0.5.4 dez. de 2025: Vegas, Gradient2, AIMD). [DOC]

### 2.4 Alibaba Sentinel

- **Situação:** 1.8.10 (maio de 2026), ativo. [DOC] ([repo](https://github.com/alibaba/Sentinel))
- **Funcionalidades:** controle de fluxo por QPS ou por threads concorrentes, com aquecimento (warm-up) e modelagem por limitação de taxa; circuit breaking (proporção de lentas, proporção de erros, contagem de erros); controle de fluxo por **parâmetro quente**; proteção adaptativa do sistema (carga/CPU/RT); controle de fluxo em cluster; um dashboard; fontes dinâmicas de regras (Nacos, ZooKeeper, Apollo, ...). [DOC]
- **Anotações:** `@SentinelResource(value, blockHandler, fallback, ...)` via `sentinel-annotation-aspectj` (AspectJ) ou `sentinel-annotation-cdi-interceptor`. [SRC] De novo, sem weaving independente.
- **Internos (copiar):** `LeapArray`, um anel de buckets de tempo em um `AtomicReferenceArray<WindowWrap>`, criados por CAS e reiniciados sob `tryLock()` com `Thread.yield()` como alternativa. Os contadores por bucket são **`LongAdder[]`** (`MetricBucket`), portanto distribuídos em faixas (striped) e amigáveis à contenção. [SRC]
- **Evitar:** a maquinaria global de thread-locals `Context`/`Entry` e uma cadeia de slots por recurso com recursos identificados por string (pesado); regras desacopladas do código dificultam o raciocínio local. [INF]

### 2.5 Spring Retry → resiliência no Spring Framework 7 :id=_25-spring-retry-to-spring-framework-7-resilience

- **O Spring Retry está arquivado**; seu README diz que foi "superseded by Spring Framework 7". Última versão 2.0.13 (jun. de 2026). [DOC] ([repo](https://github.com/spring-projects/spring-retry))
- **Spring Framework 7.0**, resiliência no core: `@Retryable(includes, excludes, maxAttempts, delay, jitter, multiplier, maxDelay)`, `@ConcurrencyLimit(n)` ("particularly useful with Virtual Threads"), `RetryTemplate`/`RetryPolicy`, habilitados com `@EnableResilientMethods`; tipos de retorno reativos usam o retry do Reactor. **Sem circuit breaker.** Baseado em proxies. [DOC] ([blog](https://spring.io/blog/2025/09/09/core-spring-resilience-features))
- **Lições:** a plataforma agora cuida de retry e de limitação de concorrência, então uma nova biblioteca precisa oferecer mais do que isso (circuit breaking, rate limiting, timeouts, composição, configuração, desempenho). Seu `maxAttempts` conta *retentativas*, não o total de tentativas, ao contrário do Spring Retry; precisamos definir isso sem ambiguidade. [DOC]

### 2.6 Polly v8 (.NET), para ideias de design :id=_26-polly-v8-net-for-design-ideas

- **Situação:** 8.8.0 (set. de 2026), ativo; parte de `Microsoft.Extensions.Resilience`. [DOC] ([repo](https://github.com/App-vNext/Polly))
- **Design:** `ResiliencePipelineBuilder` compõe estratégias (retry, circuit breaker, timeout, rate limiter, fallback, **hedging** e estratégias de **caos**); `ResiliencePipelineRegistry` com pipelines por chave e recarga dinâmica; telemetria embutida. [DOC]
- **Desempenho (copiar):** a v8 foi uma reescrita focada em alocação. Para um pipeline de 5 estratégias (timeout+rate limiter+retry+CB+timeout): **v7 2.277 µs / 2824 B → v8 2.089 µs / 40 B**. Técnicas: pooling de `ResilienceContext`, **lambdas estáticas que recebem o estado por parâmetro** (sem captura), `Outcome<T>` que carrega uma exceção **sem lançá-la**, predicados como expressões switch em vez de listas de predicados, pipelines em cache. [BENCH][DOC] ([performance](https://www.pollydocs.org/advanced/performance.html))
- **Lições:** prova de que uma redução de ~99% nas alocações é alcançável para um pipeline completo. O tipo "resultado/outcome" por estratégia evita fluxo de controle guiado por exceções.

### 2.7 Mais recentes / outros (2024–2026) :id=_27-newer--other-20242026

| Biblioteca | Notas |
|---|---|
| **Helidon 4 Fault Tolerance** | API programática SE (`Async`, `Bulkhead`, `CircuitBreaker`, `Fallback`, `Retry`, `Timeout`) feita para virtual threads; anotações MP; **Helidon Declarative** (em incubação) `@Ft.Retry`, `@Ft.CircuitBreaker`, ... processadas por um **processador de anotações que gera código em tempo de build, "to avoid reflection and bytecode manipulation at runtime"**. [DOC] ([declarative](https://helidon.io/docs/v4/se/injection/declarative.html), [SE FT](https://helidon.io/docs/v4/se/fault-tolerance.html)) É o trabalho anterior mais próximo do nosso objetivo, embora esteja preso ao Helidon Inject. |
| **Micronaut** | `@Retryable`, `@CircuitBreaker` (uma variante do retry) e os interceptadores Resilience4j-Micronaut; **AOP em tempo de compilação** (sem proxies/reflexão em runtime). [DOC] ([javadoc](https://docs.micronaut.io/5.0.1/api/io/micronaut/retry/annotation/package-summary.html)) Preso ao contêiner de DI do Micronaut. |
| **Netflix concurrency-limits** | Limitação de concorrência adaptativa (no estilo do controle de congestionamento do TCP). Uma ideia forte de diferencial para um modo "adaptativo" do bulkhead. [DOC] |
| **Spring Framework 7** | Veja §2.5. |
| **Resilience4j 2.4 / Hedge** | Alcançando as virtual threads (base JDK 21, `ReentrantLock` em vez de `synchronized`), janelas lock-free opcionais, hedging. [DOC] |

---

## 3. Matriz de comparação de funcionalidades :id=_3-feature-comparison-matrix

Legenda: ✅ sim · ⚠️ parcial / com ressalvas · ❌ não · — não se aplica.

| Capacidade | Resilience4j 2.4 | Failsafe 3.3 | SmallRye FT 6.x | Hystrix 1.5 | Sentinel 1.8 | Spring FW 7 | Polly 8 (.NET) | Helidon 4 FT |
|---|---|---|---|---|---|---|---|---|
| Circuit breaker | ✅ contagem+tempo, chamada lenta | ✅ contagem+tempo | ✅ contagem (rolante) | ✅ buckets de tempo | ✅ proporção lentas/erros/contagem | ❌ | ✅ | ✅ |
| Retry + backoff/jitter | ✅ | ✅ | ✅ (+Fibonacci, personalizado) | ❌ | ❌ | ✅ | ✅ | ✅ |
| Rate limiter | ✅ | ✅ suave/em rajadas | ✅ `@RateLimit` | ❌ | ✅ QPS, warm-up, parâmetro quente | ❌ | ✅ | ❌ |
| Bulkhead por semáforo | ✅ | ✅ | ✅ | ✅ | ✅ (contagem de threads) | ✅ `@ConcurrencyLimit` | ✅ (via concorrência do RateLimiter) | ✅ |
| Bulkhead por thread pool | ✅ | ❌ | ✅ (`@Asynchronous`) | ✅ (padrão) | ❌ | ❌ | — | ⚠️ |
| Timeout | ⚠️ precisa de Future/CS | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Fallback | ⚠️ decorador / nome de método em string | ✅ | ✅ handler ou método | ✅ | ✅ | ❌ | ✅ | ✅ |
| Hedging | ✅ (novo) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Limites adaptativos | ❌ (#201 aberta desde 2018) | ❌ | ❌ | ❌ | ⚠️ proteção do sistema | ❌ | ❌ | ❌ |
| Anotações | ✅ declaradas, ❌ processadas de forma independente | ❌ | ✅ (só CDI) | ⚠️ javanica + AspectJ | ✅ (AspectJ/CDI) | ✅ (proxies do Spring) | — | ✅ (MP/CDI, ou Helidon Declarative) |
| **Anotações em Java puro** | ❌ | ❌ | ❌ | ❌ | ⚠️ AspectJ manual | ❌ | — | ❌ |
| Processamento de anotações em tempo de build (sem reflexão) | ⚠️ só via Micronaut | — | ⚠️ build-time do Quarkus | ❌ | ❌ | ❌ | — | ✅ (em incubação) |
| Configurações nomeadas + padrão | ✅ | ❌ (código) | ✅ via `@ApplyGuard` | ✅ (Archaius) | ✅ regras | ❌ | ✅ registry | ✅ |
| Sobrescrita por método via configuração externa | ⚠️ por nome de instância | ❌ | ✅ classe/método/anotação/parâmetro | ✅ | ✅ (fontes de regras) | ⚠️ placeholders | ✅ (recarga de options) | ✅ |
| Reconfiguração dinâmica em runtime | ⚠️ parcial (limites do RateLimiter; substituição no registry) | ❌ | ⚠️ | ✅ | ✅ | ❌ | ✅ | ⚠️ |
| Listeners de eventos | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ telemetria | ✅ |
| Métricas (Micrometer etc.) | ✅ | ❌ | ✅ (MP Metrics/Micrometer/OTel) | ✅ | ✅ próprias | ⚠️ | ✅ (métricas .NET) | ✅ |
| Assíncrono (`CompletionStage`) | ✅ | ✅ | ✅ | ⚠️ Rx | ⚠️ | ✅ (reativo) | ✅ (Task/ValueTask) | ✅ |
| Reactor / RxJava / Kotlin | ✅ / ✅ / ✅ | ❌ | Mutiny, Rx, Kotlin | Rx | ⚠️ adaptador Reactor | Reactor | — | ❌ |
| Amigável a virtual threads | ✅ desde 2.3/2.4 | ⚠️ `synchronized` (pinning < JDK 24) | ✅ | ❌ | ⚠️ | ✅ | — | ✅ (projetado para VT) |
| Caminho quente sem alocação | ❌ (272 B/op histórico no CB) | ❌ | ? | ❌ | ⚠️ | ? | ✅ quase (40 B / 5 estratégias) | ? |
| Números JMH publicados | ⚠️ arquivo antigo no repo | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Mantido (2026) | ✅ | ⚠️ manutenção | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |

"?" = não investigado ou desconhecido; a medir.

---

## 4. Oportunidades: onde uma nova biblioteca pode superar o Resilience4j :id=_4-opportunities-where-a-new-library-can-beat-resilience4j

### 4.1 Desempenho :id=_41-performance

1. **Caminho quente sem alocação em regime estável** (nenhum evento consumido, caminho de sucesso). Concretamente: nenhum valor de retorno `Snapshot` (expor as métricas sob demanda, não a cada registro), timestamps `long` primitivos (`System.nanoTime()` diretamente, uma interface no estilo `LongSupplier` para testes em vez de `Function<Clock, Long>`), nenhum objeto de contexto por chamada para o retry quando a primeira tentativa tem sucesso (contexto preguiçoso) e nenhum `Instant` nas verificações de OPEN (guardar o prazo em nanos como `long`). Evidência de que é possível: o protótipo da R4j #117, os 40 B/5 estratégias do Polly v8. [SRC][BENCH] **Meta a propor ao agente de desempenho: 0 B/op no caminho de sucesso de CB+Retry+Bulkhead, verificado com JMH `-prof gc`.**
2. **Métricas amigáveis à contenção por padrão**: contadores distribuídos em faixas/no estilo `LongAdder` ou com CAS por bucket (no estilo do `LeapArray` do Sentinel), em vez de um único lock (padrão da R4j) ou de uma lista com CAS em uma única cauda que aloca a cada registro (LOCK_FREE da R4j). [SRC]
3. **Eventos que não custam nada quando ninguém escuta, e pouco quando alguém escuta**: verificar antes de alocar em *todos* os caminhos (a R4j pula a verificação em vários tipos de evento, incluindo call-not-permitted, e cada evento captura `ZonedDateTime.now()`) e, idealmente, evitar por completo objetos de evento para os consumidores de métricas (contadores, não fluxos de eventos). Não anexar buffers a cada instância por padrão (a integração Spring Boot da R4j faz isso). [SRC]
4. **Resolver tudo uma vez por método**: nomes, configurações, alvos de fallback e adaptadores de tipo de retorno vinculados em tempo de build ou na primeira chamada a um handle final por método, de modo que o trabalho por chamada seja apenas permissão, invocação e registro. Compare com o SpEL/regex/reflexão por chamada da R4j (§1.3). [SRC]
5. **Fallback sem reflexão**: chamadas diretas geradas (ou `MethodHandle`s vinculados uma única vez) em vez de `Method.invoke`, e com uma assinatura verificada em tempo de compilação (a R4j só registra "No fallback method match found" em runtime). [SRC]
6. **Pipelines monomórficos e fundidos**: fundir as pilhas comuns (por ex. Retry(CB(Bulkhead(call)))) em um único método gerado/especializado em vez de lambdas `Supplier` megamórficas aninhadas. [INF], a confirmar com JMH e logs do JIT.
7. **Rate limiter sob contenção**: evitar alocar um `State` por tentativa de CAS (empacotar ciclo+permissões em um `long`, ou usar um `AtomicLong` por ciclo). [SRC]/[INF]
8. **Exceções como valores no caminho de rejeição**: exceções de rejeição pré-alocadas e sem stack trace (`CallNotPermitted`, bulkhead cheio), ou uma API no estilo `Outcome` para usuários que não querem nenhum throw (ideia do Polly). [DOC]/[INF]
9. **Nenhum `synchronized` nos caminhos quentes**: Java 21 é o nosso mínimo e as correções de pinning do JEP 491 só chegam no JDK 24. O Failsafe é o exemplo a não seguir. [SRC][DOC]
10. **Publicar uma suíte JMH reproduzível comparando R4j, Failsafe, SmallRye standalone e a nossa** (1/4/16/64 threads, threads de plataforma e virtual threads, `-prof gc`). Não existe nenhuma publicamente. [DOC, resultado negativo]

### 4.2 Ergonomia :id=_42-ergonomics

1. **Anotações declarativas que funcionam em Java puro**, sem runtime de Spring, CDI ou AspectJ. Ninguém oferece isso hoje, exceto o Helidon Declarative (preso ao contêiner, em incubação). Este é o nosso principal diferencial. (O mecanismo fica a cargo do agente de processamento de anotações; a geração em tempo de build à la Helidon/Micronaut é o trabalho anterior mais forte.) [DOC]
2. **Configuração em camadas no estilo MicroProfile**: valores da anotação como padrões, depois o perfil nomeado (`@CircuitBreaker(config = "payments")`), depois a sobrescrita externa por `class/method/policy/param`, com uma fonte de configuração plugável (properties, env, propriedades do sistema) e sem dependência de framework. [DOC]
3. **Políticas nomeadas compartilhadas aplicadas por anotação** (`@ApplyGuard` do SmallRye) e **meta-anotações compostas** (a R4j #2405 continua aberta). [DOC][ISSUE]
4. **Validação em tempo de compilação**: nomes de configuração desconhecidos, assinaturas de fallback erradas, combinações de políticas incompatíveis e limiares inválidos reportados como erros do compilador em vez de avisos em runtime. [INF]
5. **Uma ordem de políticas definida e documentada** (o MicroProfile tem uma; a ordem dos aspectos da R4j é configurada por propriedades `@Order` do Spring). [DOC]
6. **Semântica sem ambiguidade**: `maxAttempts` vs `maxRetries` (o Spring 7 mudou o significado), se `Error`s são registrados, inspeção da cadeia de causas. [DOC]
7. **Timeouts honestos para código síncrono** em virtual threads (o TimeLimiter da R4j precisa de um `Future`). [SRC]
8. **Limite de concorrência adaptativo opcional** (Netflix concurrency-limits) como modo do bulkhead: uma funcionalidade pedida à R4j desde 2018 (#201). [ISSUE][DOC]
9. **Ergonomia de testes**: um relógio injetável que seja respeitado em todo lugar (o LOCK_FREE da R4j o ignora). [SRC]

### 4.3 Riscos / o que não prometer demais :id=_43-risks--things-not-to-over-promise

- O Resilience4j tem um ecossistema enorme (starters do Spring Boot, Micrometer, Feign, Reactor, Kotlin). Começamos sem nada disso; só Java puro é um público mais restrito. [INF]
- A maioria das chamadas reais é limitada por I/O (ms), então economias abaixo de 1 µs importam principalmente para serviços com alto QPS, proteção de trabalho limitado por CPU (o caso de streaming da R4j #1513), sistemas sensíveis a alocação/de baixa latência e escala com virtual threads. O argumento deve ser "overhead quase zero, escala com as threads" mais "anotações sem framework", não apenas velocidade bruta. [INF]
- A escape analysis do JIT pode já eliminar algumas das alocações que listamos (itens [INF]). Medir antes de afirmar.

---

## 5. Recomendações para as próximas fases :id=_5-recommendations-for-the-next-phases

1. Adotar a **semântica das anotações do MicroProfile FT e sua hierarquia de sobrescrita de configuração** como modelo de referência (adaptando os nomes), e acrescentar políticas nomeadas no estilo do SmallRye e composição por meta-anotações.
2. Usar **código gerado em tempo de build** para tratar as anotações (trabalhos anteriores do Helidon/Micronaut); deixar a decisão final para a pesquisa sobre o mecanismo de anotações.
3. Tornar o **contrato de desempenho explícito e medido**: 0 B/op nos caminhos de sucesso, nenhum lock nos caminhos quentes, métricas via contadores distribuídos em faixas, eventos alocados preguiçosamente e uma suíte JMH comparativa publicada desde o primeiro dia.
4. Limitar a v1 a **CircuitBreaker, Retry, Timeout, Bulkhead (semáforo), RateLimiter, Fallback**; considerar adaptive limit e hedging como diferenciais da v2; deixar de fora o Cache e o bulkhead por thread pool (as virtual threads o tornam menos relevante).

## Fontes :id=sources

- Resilience4j: [repo](https://github.com/resilience4j/resilience4j), [notas de versão](https://github.com/resilience4j/resilience4j/blob/master/RELEASENOTES.adoc), [v2.4.0](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0), issues [#117](https://github.com/resilience4j/resilience4j/issues/117), [#201](https://github.com/resilience4j/resilience4j/issues/201), [#1377](https://github.com/resilience4j/resilience4j/issues/1377), [#1391](https://github.com/resilience4j/resilience4j/issues/1391), [#1513](https://github.com/resilience4j/resilience4j/issues/1513), [#2023](https://github.com/resilience4j/resilience4j/issues/2023), [#2151](https://github.com/resilience4j/resilience4j/issues/2151), [#2224](https://github.com/resilience4j/resilience4j/issues/2224), [#2405](https://github.com/resilience4j/resilience4j/issues/2405), PR [#2241](https://github.com/resilience4j/resilience4j/pull/2241), [arquivo de resultados JMH](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/jmh/resources/resultsWithGCProfiling.txt)
- Failsafe: [repo](https://github.com/failsafe-lib/failsafe), [InfoQ sobre a 3.x](https://www.infoq.com/news/2022/02/failsafe-resilience-3)
- MicroProfile FT 4.1: [spec](https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html); SmallRye FT: [repo](https://github.com/smallrye/smallrye-fault-tolerance), [API programática](https://smallrye.io/docs/smallrye-fault-tolerance/6.9.3/reference/programmatic-api.html), [reusable / @ApplyGuard](https://smallrye.io/docs/smallrye-fault-tolerance/6.8.0/reference/reusable.html)
- Hystrix: [repo/README](https://github.com/Netflix/Hystrix); Netflix [concurrency-limits](https://github.com/Netflix/concurrency-limits)
- Sentinel: [repo](https://github.com/alibaba/Sentinel)
- Spring: [Spring Retry (arquivado)](https://github.com/spring-projects/spring-retry), [blog de resiliência do Spring Framework 7](https://spring.io/blog/2025/09/09/core-spring-resilience-features), [referência](https://docs.spring.io/spring-framework/reference/core/resilience.html)
- Polly: [repo](https://github.com/App-vNext/Polly), [performance](https://www.pollydocs.org/advanced/performance.html)
- Helidon: [Declarative](https://helidon.io/docs/v4/se/injection/declarative.html), [SE Fault Tolerance](https://helidon.io/docs/v4/se/fault-tolerance.html); Micronaut [anotações de retry](https://docs.micronaut.io/5.0.1/api/io/micronaut/retry/annotation/package-summary.html)
- JDK: [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- Acadêmico: [artigo do SBRC, Polly vs Resilience4j](https://sol.sbc.org.br/index.php/sbrc/article/download/21194/21019)
