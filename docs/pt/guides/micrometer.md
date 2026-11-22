# Micrometer

Exporte o que as políticas fazem para qualquer backend do Micrometer (Prometheus, Datadog, OTLP, …): contadores de retries, rejeições, transições do breaker, timeouts e fallbacks, e gauges com o estado de cada circuit breaker, bulkhead e rate limiter.

O módulo `resilience4cata-micrometer` (disponível desde 0.3.0) é construído sobre os [eventos e handles](observability.md) de `core`; o próprio `core` não depende do Micrometer. Ele traz `io.micrometer:micrometer-core` como dependência transitiva (versão 1.17.1, aquela com a qual é testado).

## Instalação :id=install

Importe o BOM, como para os outros módulos ([instalação](../start/installation.md)), e adicione o módulo:

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.0.0')
    implementation 'dev.resilience4cata:resilience4cata-micrometer'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation(platform("dev.resilience4cata:resilience4cata-bom:1.0.0"))
    implementation("dev.resilience4cata:resilience4cata-micrometer")
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-micrometer</artifactId>
</dependency>
```

<!-- tabs:end -->

Com JPMS, adicione `requires dev.resilience4cata.micrometer;` (ele faz `requires transitive` tanto de `dev.resilience4cata.core` quanto de `micrometer.core`).

## Configuração :id=setup

Três linhas, em volta do registry que você já constrói:

<!-- snippet: micrometer-setup -->
```java
MeterRegistry meterRegistry = new SimpleMeterRegistry(); // in your app: the registry you already export
MicrometerMetrics metrics = MicrometerMetrics.create(meterRegistry);
ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // counters, from events
        .build();
metrics.bindTo(registry); // gauges, for every breaker, bulkhead and rate limiter, even those created later
```

- `register(builder)` adiciona um listener de eventos ao builder e retorna o builder, então você continua configurando-o (`.retry(…)`, `.listen(…)`, [configuração externa](configuration.md)) antes de `build()`. Ele deve rodar antes de `build()`: os listeners são fixados quando o registry é construído.
- `bindTo(registry)` registra os gauges das instâncias que existem, e de toda instância que o registry criar depois. As instâncias são criadas quando um serviço é envolvido (`Resilient.of`, `Resilient.create`) ou um `Pipeline` é construído, então você pode fazer o bind logo após `build()`, antes de envolver qualquer coisa.

Os serviços anotados e os [pipelines](pipeline.md) são reportados da mesma forma. A tag `name` é o id da instância: o `name` definido na anotação, o id derivado `pkg.Type#method(java.lang.String)` quando não há nome, ou o id do pipeline.

## Métricas :id=metrics

Os nomes e tags são os de [Observabilidade](observability.md#metric-names); a coluna Prometheus mostra como um `PrometheusMeterRegistry` os exporta (os pontos viram sublinhados, os contadores recebem `_total`, a unidade percent vira um sufixo `_percent`).

| Medidor | Tipo | Tags | No Prometheus |
|---|---|---|---|
| `r4c.calls.rejected` | counter | `name`, `kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `r4c_calls_rejected_total` |
| `r4c.circuitbreaker.transitions` | counter | `name`, `from`, `to` | `r4c_circuitbreaker_transitions_total` |
| `r4c.retry.retries` | counter | `name` | `r4c_retry_retries_total` |
| `r4c.retry.gave.up` | counter | `name`, `reason` | `r4c_retry_gave_up_total` |
| `r4c.hedge.hedged` | counter | `name`, `reason` | `r4c_hedge_hedged_total` |
| `r4c.hedge.won` | counter | `name` | `r4c_hedge_won_total` |
| `r4c.hedge.skipped` | counter | `name`, `reason` | `r4c_hedge_skipped_total` |
| `r4c.hedge.failed` | counter | `name` | `r4c_hedge_failed_total` |
| `r4c.adaptivelimit.limit.changes` | counter | `name` | `r4c_adaptivelimit_limit_changes_total` |
| `r4c.timeout.timed.out` | counter | `name` | `r4c_timeout_timed_out_total` |
| `r4c.fallback.applied` | counter | `name` | `r4c_fallback_applied_total` |
| `r4c.circuitbreaker.state` | gauge | `name`, `state` | `r4c_circuitbreaker_state` |
| `r4c.circuitbreaker.failure.rate` | gauge, percent | `name` | `r4c_circuitbreaker_failure_rate_percent` |
| `r4c.circuitbreaker.slow.call.rate` | gauge, percent | `name` | `r4c_circuitbreaker_slow_call_rate_percent` |
| `r4c.bulkhead.concurrent.calls` | gauge | `name` | `r4c_bulkhead_concurrent_calls` |
| `r4c.bulkhead.waiting.calls` | gauge | `name` | `r4c_bulkhead_waiting_calls` |
| `r4c.bulkhead.max.concurrent` | gauge | `name` | `r4c_bulkhead_max_concurrent` |
| `r4c.ratelimiter.available.permits` | gauge | `name` | `r4c_ratelimiter_available_permits` |
| `r4c.adaptivelimit.limit` | gauge | `name` | `r4c_adaptivelimit_limit` |
| `r4c.adaptivelimit.concurrent.calls` | gauge | `name` | `r4c_adaptivelimit_concurrent_calls` |

Os valores das tags são em minúsculas: estados `closed`, `open`, `half_open`, `forced_open`, `disabled`; motivos do retry `max_attempts`, `max_duration`, `not_retryable`, `interrupted`, `classifier_failed`, `budget_exhausted`; motivos do hedge `delay`, `failure` (`hedged`) e `budget_exhausted`, `rejected`, `aborted` (`skipped`) (um motivo adicionado em uma versão posterior aparece sozinho).

Quando cada medidor aparece:

- **Os contadores** são registrados no seu primeiro evento: um serviço que nunca fez retry ainda não tem a série `r4c_retry_retries_total`. Em PromQL, `… or vector(0)` preenche a lacuna quando você precisa de um zero.
- **Os gauges** são registrados quando a instância recebe o bind: as cinco séries `state` do circuit breaker (uma vale 1, as outras 0), suas duas taxas, os três gauges do bulkhead, o do rate limiter e os dois do adaptive limit. Retries, hedges, timeouts e fallbacks não têm gauges.
- **As taxas são NaN** até a janela do breaker conter `minimumCalls` chamadas, enquanto o breaker está aberto, forçado a abrir ou desativado (não há janela então), e, para a taxa de chamadas lentas, quando a detecção de chamadas lentas está desligada (sem `slowCallDuration`). O Prometheus armazena NaN como valor; os gráficos mostram uma lacuna.

## O que o Prometheus mostra :id=prometheus

O `PaymentGateway` destes exemplos tem `@Retry(maxAttempts = 3)`, `@CircuitBreaker(windowSize = 4, minimumCalls = 4)`, um rate limiter de 100 permissões por segundo e um bulkhead de 10, todos chamados `payments`. Após duas chamadas contra um gateway fora do ar (a primeira chamada falha três vezes; a quarta falha abre o breaker, que rejeita o retry da segunda chamada), um scrape contém estas linhas. Elas vêm de um scrape real de `PrometheusMeterRegistry` nos testes do projeto, sem as linhas `# HELP` e `# TYPE`:

<!-- snippet: micrometer-prometheus -->
```text
r4c_bulkhead_concurrent_calls{name="payments"} 0.0
r4c_bulkhead_max_concurrent{name="payments"} 10.0
r4c_bulkhead_waiting_calls{name="payments"} 0.0
r4c_calls_rejected_total{kind="circuitbreaker",name="payments"} 1.0
r4c_circuitbreaker_failure_rate_percent{name="payments"} NaN
r4c_circuitbreaker_slow_call_rate_percent{name="payments"} NaN
r4c_circuitbreaker_state{name="payments",state="closed"} 0.0
r4c_circuitbreaker_state{name="payments",state="disabled"} 0.0
r4c_circuitbreaker_state{name="payments",state="forced_open"} 0.0
r4c_circuitbreaker_state{name="payments",state="half_open"} 0.0
r4c_circuitbreaker_state{name="payments",state="open"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",name="payments",to="open"} 1.0
r4c_ratelimiter_available_permits{name="payments"} 98.0
r4c_retry_gave_up_total{name="payments",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{name="payments",reason="not_retryable"} 1.0
r4c_retry_retries_total{name="payments"} 3.0
```

A taxa de falhas é NaN porque o breaker está aberto. O rate limiter mostra 98 de 100 permissões: quatro tentativas pegaram uma cada, e os atrasos do retry devolveram algumas (o teste roda no [relógio manual](testing.md), então os números são exatos).

## Painéis do Grafana :id=grafana

PromQL para os painéis de que a maioria dos serviços precisa:

| Painel | Consulta | Notas |
|---|---|---|
| Rejeições por segundo, por política | `sum by (instance, kind) (rate(r4c_calls_rejected_total[5m]))` | série temporal empilhada |
| Breakers abertos agora | `sum by (instance) (r4c_circuitbreaker_state{state=~"open\|forced_open"})` | stat ou state timeline; alerte quando `> 0` por alguns minutos |
| Transições do breaker | `increase(r4c_circuitbreaker_transitions_total{to="open"}[1h])` | um breaker instável abre muitas vezes |
| Retries por segundo | `sum by (instance) (rate(r4c_retry_retries_total[5m]))` | compare com sua taxa de requisições |
| Desistências por motivo | `sum by (instance, reason) (increase(r4c_retry_gave_up_total[1h]))` | `not_retryable` inclui chamadas interrompidas por um breaker aberto |
| Taxa de falhas | `r4c_circuitbreaker_failure_rate_percent` | lacunas até `minimumCalls`, e enquanto aberto |
| Saturação do bulkhead | `r4c_bulkhead_concurrent_calls / r4c_bulkhead_max_concurrent` | 1 significa cheio; plote também `r4c_bulkhead_waiting_calls` |
| Folga do rate limiter | `min by (instance) (r4c_ratelimiter_available_permits)` | perto de 0 significa que quem chama está sendo limitado |
| Timeouts por segundo | `sum by (instance) (rate(r4c_timeout_timed_out_total[5m]))` | |
| Fallbacks por segundo | `sum by (instance) (rate(r4c_fallback_applied_total[5m]))` | a fração de respostas degradadas |

## Cardinalidade :id=cardinality

Toda série carrega `name`, e suas outras tags têm um conjunto fixo de valores, então o número de séries cresce com o número de instâncias, não com o tráfego:

- um circuit breaker: 7 séries de gauge, mais até 25 séries `transitions` (na prática, as poucas transições que acontecem) e uma `rejected`;
- um retry: uma série `retries` e uma série `gave.up` por motivo que ocorreu;
- um hedge: até duas séries `hedged`, uma `won`, uma `failed`, e uma série `skipped` por motivo que ocorreu;
- um bulkhead: 3 gauges e uma `rejected`; um rate limiter: 1 gauge e uma `rejected`; um timeout ou um fallback: um contador.

Mantenha os ids de instância limitados: eles vêm do seu código (um `name` de anotação, um método, um id de pipeline), então são, a menos que você construa ids de pipeline a partir de dados da requisição. Nunca faça isso, nem pelas métricas nem pelas próprias políticas, que então nunca compartilhariam estado. Prefira um `name` compartilhado pelos métodos que chamam a mesma dependência: uma série por dependência é mais fácil de ler que uma por método. Não adicione você mesmo a classe ou a mensagem da exceção como tag: na prática, elas são ilimitadas.

## Ciclo de vida :id=lifecycle

- **Um `MicrometerMetrics` por `MeterRegistry`.** Para exportar para vários backends, passe a ele um `CompositeMeterRegistry`. Um `MicrometerMetrics` pode servir vários registries de resiliência: chame `register` em cada builder e `bindTo` em cada registry. O Micrometer identifica um medidor apenas por nome e tags, então, se dois registries com bind têm uma instância com o mesmo id, seus contadores se somam e são reportados os gauges do registry que recebeu o bind primeiro.
- **`bindTo` é idempotente** para o mesmo registry, e seguro a partir de qualquer thread, como todo o resto da classe.
- **Nada mantém vivo um registry descartado.** Os gauges guardam as políticas de forma fraca (o padrão do Micrometer), então o meter registry nunca mantém um `ResilienceRegistry` alcançável; depois que esse registry é coletado pelo garbage collector, seus gauges reportam NaN. No sentido inverso, um registry com bind mantém o `MicrometerMetrics` e seu meter registry alcançáveis, que é o que você quer enquanto ele roda.
- **`close()`** remove do meter registry todos os medidores que registrou, para de contar eventos e para de registrar gauges para novas instâncias. Os serviços continuam funcionando. Chame-o quando você descartar um registry antes do fim da aplicação (em testes, ou quando um componente é reiniciado):

<!-- snippet: micrometer-close -->
```java
metrics.close(); // removes every meter it registered; the services keep working
```

## Quanto custa :id=cost

- Uma **chamada bem-sucedida** não custa nada a mais: ela não publica nenhum evento, e os gauges não estão no caminho da chamada.
- Um **evento** (um retry, uma rejeição, uma transição, um timeout, um fallback) custa uma busca do contador em cache pelo id da instância, uma leitura de array e o incremento do Micrometer. O primeiro evento de cada contador o registra no Micrometer. Além do próprio registro do evento, um evento em cache não aloca nada (um teste verifica isso). Se o Micrometer lançar (um registry que rejeita um medidor, por exemplo), `core` passa a exceção ao uncaught exception handler da thread e a chamada continua.
- Um **scrape** lê os handles; as duas taxas fazem um snapshot do breaker cada, que percorre a janela do breaker.
- **Criar uma instância** (ao envolver um serviço ou construir um pipeline) lista as instâncias do registry uma vez para registrar os novos gauges.

O overhead do módulo ainda não foi medido.
