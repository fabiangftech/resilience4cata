# OpenTelemetry

Exporte o que as policies fazem como métricas do OpenTelemetry: rejeições, retries, transições do breaker, timeouts e fallbacks como contadores, e o estado de breakers, bulkheads e rate limiters como gauges.

`resilience4cata-opentelemetry` se apoia nos [eventos e snapshots](observability.md) do `core`: um listener transforma eventos em contadores, e gauges assíncronos leem o snapshot do registry a cada coleta do SDK. Ele reporta os mesmos nomes de métricas e atributos que o módulo Micrometer, então dashboards e alertas funcionam com qualquer um dos dois.

Esta versão tem **apenas métricas**. Ainda não há spans de tracing.

## Dependência :id=dependency

O módulo depende de `resilience4cata-core` e da API do OpenTelemetry (`io.opentelemetry:opentelemetry-api`, 1.62.0 ou posterior), nada mais. Você traz o SDK e o exporter que usa: o módulo nunca os configura.

<!-- tabs:start -->
#### **Gradle**
```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.0.0-RC4')
    implementation 'dev.resilience4cata:resilience4cata-opentelemetry'    // version from the BOM

    implementation platform('io.opentelemetry:opentelemetry-bom:1.67.0')  // your SDK and exporter
    implementation 'io.opentelemetry:opentelemetry-sdk'
}
```
#### **Gradle (Kotlin)**
```kotlin
dependencies {
    implementation(platform("dev.resilience4cata:resilience4cata-bom:1.0.0-RC4"))
    implementation("dev.resilience4cata:resilience4cata-opentelemetry")    // version from the BOM

    implementation(platform("io.opentelemetry:opentelemetry-bom:1.67.0"))  // your SDK and exporter
    implementation("io.opentelemetry:opentelemetry-sdk")
}
```
#### **Maven**
```xml
<dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-opentelemetry</artifactId>
    <!-- version from the imported resilience4cata-bom -->
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <!-- version from the imported opentelemetry-bom -->
</dependency>
```
<!-- tabs:end -->

O módulo precisa do OpenTelemetry 1.62.0 ou posterior, a versão mais antiga que Spring Boot 4.1, Quarkus 3.40 e Micronaut 5.2 trazem; seus testes rodam na 1.62.0 (API e SDK) e na 1.67.0. O OpenTelemetry mantém sua API estável 1.x retrocompatível, então um SDK 1.x mais novo também deve funcionar. Com JPMS, adicione `requires dev.resilience4cata.opentelemetry;`. O jar da API do OpenTelemetry é um módulo automático (`io.opentelemetry.api`), requerido de forma transitiva.

## Configuração :id=setup

Três passos: crie as métricas na sua instância de `OpenTelemetry`, registre o listener delas no builder do registry e vincule o registry para os gauges.

<!-- snippet: opentelemetry-wiring -->
```java
OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(openTelemetry); // your SDK instance
ResilienceRegistry registry = metrics.register(ResilienceRegistry.builder()) // adds the counters' listener
        .build();
metrics.bindTo(registry); // gauges, read at each collection: later instances are included
PaymentGateway payments = Resilient.of(PaymentGateway.class, delegate, registry);
```

- `register(builder)` deve rodar **antes de `build()`**: os listeners são fixados quando o registry é construído. Ele devolve o mesmo builder, então o resto da sua configuração (`retry(...)`, `ticker(...)`, `ResilienceConfig.applyTo(...)`) se encadeia como de costume.
- `bindTo(registry)` pode rodar a qualquer momento. As instâncias de policies são criadas sob demanda, quando uma interface é envolvida ou um pipeline é construído; os gauges leem `registry.snapshot()` a cada coleta, então instâncias criadas depois de `bindTo` aparecem na próxima, sem chamada extra.
- `create(MeterProvider)` faz o mesmo que `create(OpenTelemetry)` quando você só tem o meter provider. `GlobalOpenTelemetry.get()` também funciona, se a sua aplicação (ou o agente Java) o define.
- Em um framework: a [extensão do Quarkus](quarkus.md#observability) registra essas métricas sozinha quando o Quarkus OpenTelemetry está presente e o Micrometer não; com [Spring Boot](spring.md#opentelemetry) e [Micronaut](micronaut.md#opentelemetry), que vinculam apenas o Micrometer, conecte os mesmos três passos como beans.

## Métricas :id=metrics

Toda métrica tem um atributo `name`: o id da instância da policy (o `name` da anotação, o id derivado `pkg.Type#method(...)` ou o id do pipeline). Os ids de instância são fixos no seu código, então o atributo é limitado. Os valores dos atributos ficam em minúsculas.

| Nome | Instrumento OpenTelemetry | Unidade | Atributos | Origem |
|---|---|---|---|---|
| `r4c.calls.rejected` | `LongCounter` | `{call}` | `name`, `kind` = `circuitbreaker` \| `ratelimiter` \| `adaptivelimit` \| `bulkhead` | `CircuitBreakerEvent.Rejected`, `RateLimiterEvent.Rejected`, `AdaptiveLimitEvent.Rejected`, `BulkheadEvent.Rejected` |
| `r4c.circuitbreaker.transitions` | `LongCounter` | `{transition}` | `name`, `from`, `to` (`closed`, `open`, `half_open`, `forced_open`, `disabled`) | `CircuitBreakerEvent.StateTransition` |
| `r4c.retry.retries` | `LongCounter` | `{retry}` | `name` | `RetryEvent.Retrying` |
| `r4c.retry.gave.up` | `LongCounter` | `{call}` | `name`, `reason` (`max_attempts`, `max_duration`, `not_retryable`, `interrupted`, `classifier_failed`, `budget_exhausted`) | `RetryEvent.GaveUp` |
| `r4c.hedge.hedged` | `LongCounter` | `{attempt}` | `name`, `reason` (`delay`, `failure`) | `HedgeEvent.Hedged` |
| `r4c.hedge.won` | `LongCounter` | `{call}` | `name` | `HedgeEvent.Won` |
| `r4c.hedge.skipped` | `LongCounter` | `{attempt}` | `name`, `reason` (`budget_exhausted`, `rejected`, `aborted`) | `HedgeEvent.Skipped` |
| `r4c.hedge.failed` | `LongCounter` | `{call}` | `name` | `HedgeEvent.Failed` |
| `r4c.adaptivelimit.limit.changes` | `LongCounter` | `{window}` | `name` | `AdaptiveLimitEvent.LimitChanged` |
| `r4c.timeout.timed.out` | `LongCounter` | `{call}` | `name` | `TimeoutEvent.TimedOut` |
| `r4c.fallback.applied` | `LongCounter` | `{call}` | `name` | `FallbackEvent.Applied` |
| `r4c.circuitbreaker.state` | `ObservableLongGauge` | nenhuma | `name`, `state`: 1 para o estado atual, 0 para os outros quatro | `state` do snapshot |
| `r4c.circuitbreaker.failure.rate` | `ObservableDoubleGauge` | `%` | `name` | `failureRate` do snapshot |
| `r4c.circuitbreaker.slow.call.rate` | `ObservableDoubleGauge` | `%` | `name` | `slowCallRate` do snapshot |
| `r4c.bulkhead.concurrent.calls` | `ObservableLongGauge` | `{call}` | `name` | `concurrentCalls` do snapshot |
| `r4c.bulkhead.waiting.calls` | `ObservableLongGauge` | `{call}` | `name` | `waitingCalls` do snapshot |
| `r4c.bulkhead.max.concurrent` | `ObservableLongGauge` | `{call}` | `name` | `maxConcurrent` do snapshot |
| `r4c.ratelimiter.available.permits` | `ObservableLongGauge` | `{permit}` | `name` | `availablePermits` do snapshot |
| `r4c.adaptivelimit.limit` | `ObservableLongGauge` | `{call}` | `name` | `limit` do snapshot |
| `r4c.adaptivelimit.concurrent.calls` | `ObservableLongGauge` | `{call}` | `name` | `concurrentCalls` do snapshot |

- **As taxas são percentuais** (0 a 100) com unidade `%`, como no snapshot e no módulo Micrometer, não proporções (0 a 1) com unidade `1`.
- **Uma taxa não tem ponto até fazer sentido.** O snapshot reporta `-1` até a janela do breaker conter `minimumCalls` chamadas (`halfOpenCalls` enquanto half-open), enquanto o breaker está aberto, forçado aberto ou desabilitado (não há janela) e, para `slow.call.rate`, quando a detecção de chamadas lentas está desligada. Pontos do OpenTelemetry não podem ser NaN, então o módulo não registra ponto para essa instância nessa coleta: a série tem uma lacuna.
- **Os gauges do bulkhead são gauges**, não `ObservableUpDownCounter`s, para que todo backend veja o mesmo tipo que com o módulo Micrometer.
- `reason` segue `RetryEvent.Reason`: um motivo adicionado em uma versão posterior aparece como um novo valor de atributo, sem mudança no módulo.
- O escopo de instrumentação é `dev.resilience4cata`, com a versão do módulo como versão do escopo.

Não adicione a classe ou a mensagem da exceção como atributo: na prática elas não têm limite. Instâncias de retry e timeout não têm gauges: o estado delas é a sua configuração.

## Quanto custa :id=cost

- **Chamadas bem-sucedidas não custam nada.** As policies não publicam evento em caso de sucesso, e o listener é o único código que este módulo adiciona a um caminho de chamada.
- **Cada evento custa um `add` em um contador.** O listener encontra os `Attributes` do evento em um cache (construído uma vez por instância e valores de atributos, já que os atributos do OpenTelemetry são imutáveis), então não aloca nada além do próprio registro do evento; o `add` do SDK então faz seu trabalho de agregação e, uma vez que a série existe, também não aloca nada (verificado por um teste com o SDK 1.62.0 e 1.67.0). Eventos só são publicados em rejeições, retries, desistências, transições, timeouts e fallbacks.
- **Os gauges não custam nada entre coletas.** Um callback em lote constrói um `registry.snapshot()` por coleta, que percorre as janelas dos breakers, no intervalo do reader.
- **O listener nunca lança exceção**: uma falha dentro do OpenTelemetry é registrada em `DEBUG` através do `System.Logger` e a chamada segue.

Ainda não medido: o tempo que um evento leva com a agregação do SDK.

## Exportação :id=exporting

O módulo só cria instrumentos; o seu SDK decide para onde eles vão. Duas configurações comuns:

### Prometheus :id=prometheus

O exporter Prometheus do SDK (`io.opentelemetry:opentelemetry-exporter-prometheus`, publicado com versão `-alpha`) serve `/metrics` a partir do seu próprio servidor HTTP. Esta configuração é testada neste repositório:

<!-- snippet: opentelemetry-prometheus -->
```java
PrometheusHttpServer prometheus = PrometheusHttpServer.builder().setPort(port).build(); // 9464 by default
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(prometheus).build())
        .build();
```

Depois que um breaker abriu, um scrape contém linhas como estas (do teste; resumidas, sem as linhas `# HELP`/`# TYPE` e o label `otel_scope_version`):

```text
r4c_retry_retries_total{name="payments",otel_scope_name="dev.resilience4cata"} 3.0
r4c_retry_gave_up_total{name="payments",otel_scope_name="dev.resilience4cata",reason="max_attempts"} 1.0
r4c_retry_gave_up_total{name="payments",otel_scope_name="dev.resilience4cata",reason="not_retryable"} 1.0
r4c_calls_rejected_total{kind="circuitbreaker",name="payments",otel_scope_name="dev.resilience4cata"} 1.0
r4c_circuitbreaker_transitions_total{from="closed",name="payments",otel_scope_name="dev.resilience4cata",to="open"} 1.0
r4c_circuitbreaker_state{name="payments",otel_scope_name="dev.resilience4cata",state="closed"} 0.0
r4c_circuitbreaker_state{name="payments",otel_scope_name="dev.resilience4cata",state="open"} 1.0
r4c_bulkhead_max_concurrent{name="payments",otel_scope_name="dev.resilience4cata"} 10.0
r4c_ratelimiter_available_permits{name="payments",otel_scope_name="dev.resilience4cata"} 100.0
```

O exporter transforma pontos em sublinhados, adiciona `_total` aos contadores e `_percent` às taxas (`r4c_circuitbreaker_failure_rate_percent`) e descarta as unidades no estilo `{call}`: os **nomes são os mesmos que com o registry Prometheus do Micrometer**, então os [painéis PromQL](micrometer.md#grafana) do guia de observabilidade funcionam sem mudanças. O exporter adiciona os labels `otel_scope_name` e `otel_scope_version` (`setOtelScopeLabelsEnabled(false)` os remove) e uma série `target_info`.

### OTLP :id=otlp

Para enviar métricas a um OpenTelemetry Collector ou a um backend OTLP, registre um reader periódico com o exporter OTLP (`io.opentelemetry:opentelemetry-exporter-otlp`). Via HTTP:

<!-- snippet: opentelemetry-otlp -->
```java
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(OtlpHttpMetricExporter.builder()
                                .setEndpoint(collector + "/v1/metrics") // http://localhost:4318/v1/metrics by default
                                .build())
                        .setInterval(Duration.ofSeconds(30))
                        .build())
                .build())
        .build();
```

Para gRPC, use `OtlpGrpcMetricExporter` da mesma forma (porta 4317 por padrão). Esta configuração é testada contra um receptor OTLP que decodifica o que o exporter envia: nomes, tipos de instrumento, unidades, atributos e o escopo chegam como na [tabela acima](#metrics).

Com o módulo de autoconfiguração do SDK ou o agente Java do OpenTelemetry, o SDK é configurado por variáveis de ambiente `OTEL_*`, e `OpenTelemetryMetrics.create(GlobalOpenTelemetry.get())` o utiliza.

## Mesmos nomes que o Micrometer :id=naming

O módulo Micrometer (`resilience4cata-micrometer`) implementa a mesma especificação: os mesmos nomes de métricas, chaves e valores de atributos e unidades, com taxas em percentual. Trocar de um para o outro, ou rodar serviços com os dois, preserva seus dashboards e alertas. As únicas diferenças vêm dos exporters (os labels extras `otel_scope_*` e o `target_info` acima).

## Encerramento :id=closing

<!-- snippet: opentelemetry-close -->
```java
metrics.close(); // at shutdown: removes the gauge callbacks, and the counters stop counting
```

`OpenTelemetryMetrics` é `AutoCloseable`. Fechá-lo remove o callback em lote de cada registry vinculado; o listener continua no registry (listeners não podem ser removidos), mas não registra mais nada. `close()` é idempotente, e `bindTo` não faz nada depois dele, como no Micrometer. Fechar o SDK (`openTelemetry.close()`) continua sendo tarefa sua.

Vincular o mesmo registry de novo não faz nada. Um `OpenTelemetryMetrics` pode vincular vários registries, mas se os ids de instância deles se sobrepõem, os gauges reportam os mesmos conjuntos de atributos duas vezes: use um por registry, ou ids distintos.

## Tracing :id=tracing

Não nesta versão. Spans para uma biblioteca de resiliência exigem decisões que as métricas não exigem: se um retry é um span ou um por tentativa, como um fallback ou um breaker aberto marca o span, e o custo de um span nos caminhos de sucesso, que os eventos nunca tocam. Uma versão posterior poderia começar sem esse custo adicionando span events a partir do mesmo listener (`retry`, `circuit_breaker.transition`, `fallback`) ao span que está ativo na thread, e deixar a criação de spans para a sua instrumentação.
