# Observabilidade

Veja o que as políticas fazem através de eventos, leia seu estado através de snapshots e handles, e construa métricas, logs e health checks sobre ambos.

O resilience4cata oferece dois blocos de construção:

1. **Eventos**: um registro para cada coisa que uma política faz além de deixar uma chamada passar (um retry, uma transição do breaker, uma rejeição, um timeout, um fallback), entregue aos listeners que você registra no builder do registry.
2. **Snapshots e handles**: o estado atual de cada instância, lido quando você o pede.

> [!TIP]
> Para métricas, adicione o [módulo Micrometer](micrometer.md) ou o [módulo OpenTelemetry](opentelemetry.md): três linhas de configuração dão a você as [métricas abaixo](#metric-names), construídas sobre esses dois blocos. Esta página descreve os próprios blocos, para logging, health checks ou outra biblioteca de métricas.

## Eventos :id=events

Todo evento implementa a interface sealed `dev.resilience4cata.event.ResilienceEvent`, com `id()` (o id da instância: o `name`, o id derivado `pkg.Type#method(java.lang.String,int)`, ou um id de pipeline) e `nanoTime()` (uma leitura do relógio do registry: `System.nanoTime()`, ou o [relógio manual](testing.md) nos testes). Cada política tem sua própria interface sealed de records:

| Política | Record do evento | Campos (depois de `id`, `nanoTime`) | Publicado quando |
|---|---|---|---|
| Circuit breaker | `CircuitBreakerEvent.StateTransition` | `CircuitBreakerState from`, `CircuitBreakerState to` | o estado muda, exatamente uma vez por mudança, incluindo `forceOpen()`, `disable()` e `reset()` |
| Circuit breaker | `CircuitBreakerEvent.Rejected` | `CircuitBreakerState state` | uma chamada é rejeitada (`OPEN`, `FORCED_OPEN`, ou `HALF_OPEN` com todas as sondas ocupadas) |
| Retry | `RetryEvent.Retrying` | `int attempt`, `Duration delay`, `@Nullable Throwable failure` | uma tentativa falhou e outra vai rodar após `delay`; `failure` é `null` quando um [classificador](classifiers.md) rejeitou um resultado |
| Retry | `RetryEvent.GaveUp` | `int attempts`, `RetryEvent.Reason reason`, `@Nullable Throwable failure` | o retry para sem sucesso: `MAX_ATTEMPTS`, `MAX_DURATION`, `NOT_RETRYABLE` (a falha não está em `retryOn`, está em `abortOn`, ou é o `CallNotPermittedException` de um breaker aberto), `INTERRUPTED`, `CLASSIFIER_FAILED`, `BUDGET_EXHAUSTED` (o [orçamento de retries](../policies/retry.md#retry-budget) se esgotou) |
| Hedge | `HedgeEvent.Hedged` | `int attempt`, `HedgeEvent.Reason reason` | um [hedge](../policies/hedge.md) iniciou a tentativa `attempt` (2 ou mais) ao lado das outras: `DELAY` (nenhum resultado dentro de `delay`) ou `FAILURE` (uma tentativa falhou com uma falha em `hedgeOn`) |
| Hedge | `HedgeEvent.Won` | `int attempt` | a tentativa `attempt` (2 ou mais) teve sucesso primeiro e as outras foram canceladas; não é publicado quando a primeira tentativa vence |
| Hedge | `HedgeEvent.Skipped` | `int attempt`, `HedgeEvent.SkipReason reason` | um hedge que teria começado não começou: `BUDGET_EXHAUSTED` (o orçamento de hedges se esgotou), `REJECTED` (uma política interna rejeitou uma tentativa), `ABORTED` (uma falha em `abortOn`, ou fora de `hedgeOn`, encerrou a chamada) |
| Hedge | `HedgeEvent.Failed` | `int attempts`, `Throwable failure` | a chamada com hedge falha com `failure` após `attempts` tentativas |
| Rate limiter | `RateLimiterEvent.Rejected` | nenhum | uma chamada é rejeitada com `RateLimitExceededException` |
| Timeout | `TimeoutEvent.TimedOut` | `Duration timeout` | uma chamada estoura o timeout |
| Bulkhead | `BulkheadEvent.Rejected` | `int maxConcurrent` | uma chamada é rejeitada com `BulkheadFullException` |
| Adaptive limit | `AdaptiveLimitEvent.Rejected` | `int limit` | uma chamada é rejeitada com `AdaptiveLimitFullException` |
| Adaptive limit | `AdaptiveLimitEvent.LimitChanged` | `int from`, `int to`, `Duration windowLatency` | uma janela terminou e o [adaptive limit](../policies/adaptive-limit.md) moveu seu limite |
| Fallback | `FallbackEvent.Applied` | `Throwable failure` | um fallback está prestes a rodar para `failure`; não é publicado quando a falha é relançada porque nenhum fallback a aceita (`skipOn`, ou nenhum método irmão aceita seu tipo) |

Um retry com `enabled = false` não publica nada. Uma primeira falha que não pode ser retentada publica `GaveUp` com `attempts = 1` e `NOT_RETRYABLE`.

### Escutando :id=listening

Os listeners são registrados em `ResilienceRegistry.Builder`, e somente lá: eles são fixados quando o registry é construído. Escute um tipo de record, uma interface de política inteira, ou `ResilienceEvent`; em todas as instâncias, ou em um id de instância:

<!-- snippet: observability-listen -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        // one record type, on every instance
        .listen(CircuitBreakerEvent.StateTransition.class,
                t -> log.add(t.id() + ": " + t.from() + " -> " + t.to()))
        // every retry event (Retrying and GaveUp), on one instance only
        .listen("payments", RetryEvent.class, e -> log.add(e.toString()))
        // everything: keep a default branch, new policies add event types
        .listen(ResilienceEvent.class, e -> {
            switch (e) {
                case RetryEvent.GaveUp g -> log.add("gave up after " + g.attempts() + ": " + g.reason());
                default -> { }
            }
        })
        .build();
```

- **Os listeners rodam de forma síncrona** na thread que causou o evento: a thread de quem chama, ou uma virtual thread da biblioteca para timeouts assíncronos e retries assíncronos atrasados. Mantenha-os rápidos; entregue o trabalho lento (uma chamada de rede) ao seu próprio executor.
- **Uma exceção lançada por um listener** não chega à chamada nem aos outros listeners: ela vai para o uncaught exception handler da thread.
- **Ordem:** eventos publicados a partir de threads diferentes podem chegar fora de ordem; cada transição do breaker chega exatamente uma vez.
- `EventListener<E>` é uma interface funcional (`void onEvent(E event)`), então uma lambda ou uma referência de método funciona.

> [!IMPORTANT]
> **Novas versões adicionam tipos de eventos.** A hierarquia é sealed e cresce em versões minor (uma nova política traz sua própria interface de eventos, como `HedgeEvent` fez na 0.4.0). Mantenha um ramo `default` em um `switch` sobre `ResilienceEvent` ou sobre a interface de uma política, ou escute os tipos de record de que você precisa (`listen(RetryEvent.GaveUp.class, …)`), que novos tipos nunca afetam. Código compilado contra uma versão mais antiga que recebe um tipo desconhecido lança `MatchException` no listener; como qualquer exceção de listener, ela vai para o uncaught exception handler e nunca quebra a chamada.

### Quanto custam os eventos :id=cost

Cada política resolve seus listeners uma vez, quando a instância é criada, em um campo por tipo de evento. Um tipo de evento que ninguém escuta custa **uma verificação de null, e apenas nos caminhos que não são de sucesso**. Uma chamada bem-sucedida não publica nada e não verifica nada. Quando alguém escuta, o record do evento é alocado e o relógio é lido somente nesse ponto.

**Não há contadores por chamada** (nem "chamadas bem-sucedidas" nem totais de latência), por design: um contador atualizado a cada chamada seria uma escrita compartilhada no hot path. Se você precisa de contagens de chamadas ou latência, meça-as no seu próprio código em volta da chamada, ou com as métricas do seu cliente HTTP ou framework.

## Snapshots :id=snapshots

`registry.snapshot()` retorna um `ResilienceSnapshot` com uma lista por política, cada uma ordenada por id de instância. Ele é construído quando você o chama, nunca no caminho da chamada, e pode alocar e percorrer as janelas dos breakers: chame-o no momento do scrape ou do health check, não a cada requisição.

<!-- snippet: observability-snapshot -->
```java
ResilienceSnapshot snapshot = registry.snapshot(); // built when asked, never on the call path
for (CircuitBreakerSnapshot b : snapshot.circuitBreakers()) {
    lines.add(b.id() + " " + b.state() + " failureRate=" + b.failureRate()
            + " buffered=" + b.bufferedCalls() + " failed=" + b.failedCalls() + " slow=" + b.slowCalls());
}
for (BulkheadSnapshot b : snapshot.bulkheads()) {
    lines.add(b.id() + " in use " + b.concurrentCalls() + "/" + b.maxConcurrent()
            + ", waiting " + b.waitingCalls() + ", free " + b.availableSlots());
}
for (RateLimiterSnapshot r : snapshot.rateLimiters()) {
    lines.add(r.id() + " permits " + r.availablePermits() + "/" + r.permits());
}
```

| Record | Campos |
|---|---|
| `CircuitBreakerSnapshot` | `id`, `state`, `failureRate` e `slowCallRate` (percentual, ou `-1` até a janela conter `minimumCalls` chamadas, `halfOpenCalls` enquanto half-open; `slowCallRate` também é `-1` quando a detecção de chamadas lentas está desligada), `bufferedCalls`, `failedCalls`, `slowCalls` (chamadas na janela atual: a janela deslizante enquanto fechado, as sondas enquanto half-open, nenhuma enquanto aberto, forçado a abrir ou desativado) |
| `BulkheadSnapshot` | `id`, `concurrentCalls`, `waitingCalls`, `maxConcurrent`, e o método `availableSlots()` |
| `AdaptiveLimitSnapshot` | `id`, `algorithm`, `limit`, `concurrentCalls`, `minLimit`, `maxLimit`, `windows` (encerradas até agora), `windowLatency` (a média da última janela), `longTermLatency` (a média de longo prazo do Gradient2), e o método `availableSlots()` |
| `RateLimiterSnapshot` | `id`, `availablePermits`, `permits` |
| `RetrySnapshot` | `id`, `config` (o `RetryConfig` resolvido) |
| `HedgeSnapshot` | `id`, `config` (o `HedgeConfig` resolvido), `budget` (`calls`, `hedges`, `allowedHedges` na janela do orçamento; `null` sem orçamento) |
| `TimeoutSnapshot` | `id`, `config` (o `TimeoutConfig` resolvido) |

`snapshot.only(id)` mantém as instâncias com um id (`isEmpty()` quando não há nenhuma). `snapshot.toMap()` transforma o
snapshot em mapas, listas, strings, números, booleanos e nulls, que qualquer codificador JSON escreve como estão: uma chave
por tipo de política, cada uma uma lista de um mapa por instância cujas chaves são os campos acima, nessa ordem, com as
configurações aninhadas como mapas de seus atributos. As durações são strings ISO-8601 (`PT0.2S`), os enums e as classes
seus nomes, e uma taxa que não é um número finito é `null`. Os endpoints do Spring Boot e do Micronaut servem exatamente
isso; chaves podem ser adicionadas em versões minor, nunca removidas ou renomeadas.

## Handles :id=handles

Os handles leem uma instância ao vivo, e o do breaker também pode mudar seu estado:

| Método | Retorna |
|---|---|
| `registry.circuitBreaker(id)` | `CircuitBreakerHandle`: `id()`, `state()`, `config()`, `snapshot()`, `forceOpen()`, `disable()`, `reset()` |
| `registry.circuitBreakers()` | todos os handles de breaker, ordenados por id |
| `registry.rateLimiter(id)` | `RateLimiterHandle`: `id()`, `config()`, `availablePermits()`, `snapshot()` |
| `registry.bulkhead(id)` | `BulkheadHandle`: `id()`, `config()`, `concurrentCalls()`, `waitingCalls()`, `snapshot()` |
| `registry.adaptiveLimit(id)` | `AdaptiveLimitHandle`: `id()`, `config()`, `limit()`, `concurrentCalls()`, `snapshot()` |

Uma instância só existe depois que sua interface foi envolvida (`Resilient.of`, `Resilient.create`) ou seu `Pipeline` foi construído; antes disso, ou para um id que não existe, a busca lança `IllegalArgumentException` [R4C-REG-009](../errors.md#r4c-reg-009) com o id mais próximo. Retry e timeout não têm handle; sua configuração está no snapshot. Não há método de listagem para bulkheads, rate limiters e adaptive limits: enumere-os através de `registry.snapshot()`.

## Métricas :id=metrics

O [módulo Micrometer](micrometer.md) (`resilience4cata-micrometer`) transforma os eventos em contadores e os handles em gauges. Sua página tem a configuração, um scrape real do Prometheus, consultas do Grafana e conselhos sobre cardinalidade. O [módulo OpenTelemetry](opentelemetry.md) (`resilience4cata-opentelemetry`) reporta as mesmas métricas, com os mesmos nomes e tags, através da API de métricas do OpenTelemetry, então os dashboards funcionam com qualquer um dos dois.

### Nomes das métricas :id=metric-names

Os nomes são em minúsculas e separados por pontos, como o Micrometer recomenda (o Prometheus os mostra como `r4c_retry_retries_total` e assim por diante); todo medidor tem uma tag `name` (o id da instância: os ids de instância são fixados no seu código, então a tag é limitada) e no máximo uma ou duas tags a mais com um conjunto fixo de valores. Os valores das tags são em minúsculas. A tag não se chama `instance` porque o Prometheus adiciona seu próprio label `instance` (o host:port do target) a toda série que coleta, e renomearia o nosso para `exported_instance`. Duas decisões de formato, fixas a partir da 1.0: os nomes seguem o estilo com pontos do Micrometer (e do Resilience4j) nos dois módulos, não o `_` dentro de um segmento das convenções semânticas do OpenTelemetry, e as taxas do circuit breaker são percentuais de 0 a 100, como `failureRateThreshold`.

| Medidor | Tipo | Tags | Origem |
|---|---|---|---|
| `r4c.calls.rejected` | counter | `name`, `kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `CircuitBreakerEvent.Rejected`, `RateLimiterEvent.Rejected`, `AdaptiveLimitEvent.Rejected`, `BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | counter | `name`, `from`, `to` (estados em minúsculas) | `StateTransition` |
| `r4c.retry.retries` | counter | `name` | `Retrying` |
| `r4c.retry.gave.up` | counter | `name`, `reason` (`Reason` em minúsculas) | `GaveUp` |
| `r4c.hedge.hedged` | counter | `name`, `reason` = `delay` \| `failure` | `Hedged` |
| `r4c.hedge.won` | counter | `name` | `Won` |
| `r4c.hedge.skipped` | counter | `name`, `reason` = `budget_exhausted` \| `rejected` \| `aborted` | `Skipped` |
| `r4c.hedge.failed` | counter | `name` | `Failed` |
| `r4c.adaptivelimit.limit.changes` | counter | `name` | `LimitChanged` |
| `r4c.timeout.timed.out` | counter | `name` | `TimedOut` |
| `r4c.fallback.applied` | counter | `name` | `Applied` |
| `r4c.circuitbreaker.state` | gauge | `name`, `state`: 1 para o estado atual, 0 para os outros | `state()` |
| `r4c.circuitbreaker.failure.rate`, `r4c.circuitbreaker.slow.call.rate` | gauge (percent) | `name` | `snapshot()`; NaN até a janela conter `minimumCalls` chamadas |
| `r4c.bulkhead.concurrent.calls`, `r4c.bulkhead.waiting.calls`, `r4c.bulkhead.max.concurrent` | gauge | `name` | `BulkheadHandle` |
| `r4c.ratelimiter.available.permits` | gauge | `name` | `RateLimiterHandle` |
| `r4c.adaptivelimit.limit`, `r4c.adaptivelimit.concurrent.calls` | gauge | `name` | `AdaptiveLimitHandle` |

### Outra biblioteca de métricas :id=diy

Uma ponte para outra biblioteca faz o que o módulo Micrometer faz, com três peças da API do core: um listener para os contadores, os handles lidos no momento da coleta para os gauges, e `registry.onInstanceCreated(…)` para saber das instâncias criadas depois que você fez o bind do registry (as instâncias são criadas quando um serviço é envolvido ou um pipeline é construído, nunca durante uma chamada):

<!-- snippet: observability-diy -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        // counters: from events, on the thread that caused them
        .listen(RetryEvent.Retrying.class, e -> retries.merge(e.id(), 1L, Long::sum))
        .build();
// gauges: one per instance, read at collection time; new instances are announced once each
registry.onInstanceCreated(() -> {
    for (CircuitBreakerHandle breaker : registry.circuitBreakers()) {
        gauges.putIfAbsent(breaker.id(), () -> breaker.state() == CircuitBreakerState.OPEN ? 1 : 0);
    }
});
```

`onInstanceCreated` roda sua ação uma vez por nova instância, na thread que a cria, depois que a instância fica visível em `snapshot()` e nas buscas de handles; ele retorna um `Registration` cujo `close()` o interrompe. Bulkheads e rate limiters não têm método de listagem: encontre-os em `registry.snapshot()`. Guarde seus contadores em cache por instância e valores de tags, para que um evento não registre um medidor a cada vez.

## Registrando as transições no log :id=logging

Um listener que registra no log cada mudança de estado do breaker através de `System.Logger` (que chega ao seu backend de logging pela sua ponte do JDK, por exemplo o `slf4j-jdk-platform-logging` do SLF4J):

<!-- snippet: observability-logging -->
```java
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import java.lang.System.Logger.Level;

/** Logs every circuit breaker state change: a warning when a breaker opens, info otherwise. */
public final class BreakerTransitionLogger implements EventListener<CircuitBreakerEvent.StateTransition> {

    private final System.Logger logger;

    public BreakerTransitionLogger(System.Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onEvent(CircuitBreakerEvent.StateTransition t) {
        boolean opened = t.to() == CircuitBreakerState.OPEN || t.to() == CircuitBreakerState.FORCED_OPEN;
        logger.log(opened ? Level.WARNING : Level.INFO, "Circuit breaker {0}: {1} -> {2}",
                t.id(), t.from(), t.to());
    }
}
```

<!-- snippet: observability-logging-wiring -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(CircuitBreakerEvent.StateTransition.class, new BreakerTransitionLogger(logger))
        .build();
```

Registre no log as transições e as desistências, não cada `Retrying` ou rejeição: esses podem chegar no ritmo do seu tráfego durante um incidente. Conte-os em vez disso.

## Health checks :id=health

Uma verificação construída sobre o snapshot, para um endpoint de readiness ou de diagnóstico:

<!-- snippet: observability-health -->
```java
import dev.resilience4cata.CircuitBreakerSnapshot;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import java.util.List;

/** A readiness-style check: degraded while any circuit breaker rejects calls. */
public record ResilienceHealth(boolean healthy, List<String> openBreakers) {

    public static ResilienceHealth of(ResilienceRegistry registry) {
        List<String> open = registry.snapshot().circuitBreakers().stream()
                .filter(b -> b.state() == CircuitBreakerState.OPEN || b.state() == CircuitBreakerState.FORCED_OPEN)
                .map(CircuitBreakerSnapshot::id)
                .toList();
        return new ResilienceHealth(open.isEmpty(), open);
    }
}
```

<!-- snippet: observability-health-use -->
```java
ResilienceHealth health = ResilienceHealth.of(registry);
if (!health.healthy()) {
    // report "degraded" with health.openBreakers() on your readiness endpoint
}
```

> [!WARNING]
> Não ligue um breaker aberto a uma probe de **liveness**: reiniciar o serviço não conserta a dependência que o breaker protege, e todas as instâncias reiniciariam ao mesmo tempo. Reporte-o como degradado, ou em um endpoint de readiness apenas se desviar o tráfego realmente ajudar.
