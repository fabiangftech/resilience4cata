# 04 — Conjunto de recursos, API de anotações e modelo de configuração

> **Histórico.** Esta pesquisa é anterior à implementação e é mantida como foi escrita; para o que a biblioteca faz hoje, veja [recursos](../policies/README.md), [arquitetura](../architecture.md) e [benchmarks](../benchmarks.md).

> Pesquisa da fase 1. Status: **proposta para discussão**. Nenhum código de produção; todo trecho Java abaixo é um esboço de API.
> Escopo: o que os usuários veem e digitam. Como as anotações são entrelaçadas (APT vs. proxies vs. bytecode), os detalhes internos de concorrência
> e a organização dos módulos são tratados por documentos irmãos e só são citados onde restringem a API.

## Conteúdo :id=contents

1. [Resumo](#1-summary)
2. [Trabalhos anteriores](#2-prior-art)
3. [Catálogo de recursos e níveis](#3-feature-catalog-and-tiers)
4. [Design das anotações](#4-annotation-design)
5. [Ordem de execução](#5-execution-order)
6. [Múltiplas configurações](#6-multiple-configurations)
7. [Fallbacks e classificação de exceções](#7-fallbacks-and-exception-classification)
8. [Async e virtual threads](#8-async-and-virtual-threads)
9. [Eventos, métricas e inspeção de estado](#9-events-metrics-and-state-inspection)
10. [Experiência do desenvolvedor e testes](#10-developer-experience-and-testing)
11. [Questões em aberto](#11-open-questions)
12. [Fontes](#12-sources)

---

## 1. Resumo :id=_1-summary

- **Seis policies no MVP**: `@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, `@RateLimiter`, `@Fallback`. O suporte async
  vem do tipo de retorno (`CompletionStage`), não de uma anotação separada. A **v1** adiciona `@Hedge`, `@AdaptiveLimit`,
  budgets de retry, bindings de Micrometer/OpenTelemetry, YAML e reconfiguração em tempo real. **Depois**: `@Cached`, tipos reativos,
  injeção de falhas, integrações com frameworks.
- **Anotações compostas são um recurso de primeira classe**: uma `@ExternalCall` definida pelo usuário agrupa policies por meio da
  meta-anotação `@ResiliencePolicy`. Essa é a principal forma de ter "múltiplas configurações" no código.
- **Configuração e estado são separados.** Uma *config* é um modelo nomeado e imutável (`config = "external"`). Uma
  *instância* é o objeto com estado (janela do breaker, permissões do limiter), identificado por `name` e, por padrão, uma por
  método. O Resilience4j usa uma única string para as duas coisas; essa é uma fonte comum de confusão.
- **Resolução em camadas**, da menor para a maior precedência: padrões embutidos → `defaults` → config nomeada (que pode
  fazer `extends` de outra) → sobreposição de perfil → anotação da classe → anotação do método → sobrescritas externas por instância
  (properties/YAML → ambiente → propriedades do sistema) → sobrescritas programáticas.
  Tudo é resolvido **uma vez**, quando a instância é criada, em records imutáveis. O hot path nunca consulta a
  configuração.
- **Fallbacks com tipagem segura**: a forma principal é `@Fallback(UserFallback.class)` com uma interface genérica `FallbackHandler<T>`. Uma
  forma por nome de método existe por conveniência, e o annotation processor a verifica em tempo de compilação.
- **Um único classificador para exceções e resultados**: listas de classes `recordOn`/`ignoreOn`, mais um
  `Classifier<T>` opcional que vê tanto o resultado quanto o throwable.
- **A testabilidade faz parte do design**: `Clock`/`Scheduler` injetáveis, um `Random` de jitter com seed, `forceOpen()` e um
  módulo `test` com uma extensão JUnit 5 e asserções sobre eventos.
- **Hot reload**: não no MVP. A v1 tem uma API explícita `reconfigure(...)` que troca um snapshot imutável (uma leitura volatile
  por chamada), limitada a parâmetros que é seguro mudar em tempo real.

## 2. Trabalhos anteriores :id=_2-prior-art

| Biblioteca | Estilo de declaração | Fonte de configuração | Fallback | Ordem das policies combinadas |
|---|---|---|---|---|
| **Resilience4j** (anotações via Spring AOP / CDI) | `@CircuitBreaker(name, fallbackMethod)`, `@Retry`, `@RateLimiter`, `@Bulkhead(type)`, `@TimeLimiter` | `resilience4j.<policy>.configs.<cfg>.*` + `instances.<name>.baseConfig` | Nome de método como **string**, resolvido por reflection, casado pelo tipo do parâmetro de exceção | Fixa `Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(fn)))))`, alterável por propriedades `*AspectOrder` |
| **MicroProfile Fault Tolerance 4.x** | `@Retry`, `@Timeout`, `@CircuitBreaker`, `@Bulkhead`, `@Fallback`, `@Asynchronous` com atributos tipados (`maxRetries`, `delay`+`delayUnit`, `failOn`/`skipOn`, `retryOn`/`abortOn`) | MP Config: `<class>/<method>/<Annotation>/<param>`, `<class>/<Annotation>/<param>`, `<Annotation>/<param>`; `<Annotation>/enabled` | `@Fallback(value = Handler.class)` **ou** string `fallbackMethod` (validada no deploy) | Definida pela especificação: Fallback → Retry → CircuitBreaker → Timeout → Bulkhead (o SmallRye também adiciona RateLimit) |
| **SmallRye FT** (implementação do MP FT) | Adiciona `@RateLimit`, `@ExponentialBackoff`, `@FibonacciBackoff`, `@CustomBackoff`, `@RetryWhen`, `@BeforeRetry` e `@ApplyGuard`/`@ApplyFaultTolerance` (reuso de um guard nomeado construído programaticamente) | MP Config, mais uma API programática `Guard` | Igual ao MP | Igual ao MP |
| **Spring Retry** | `@Retryable(retryFor, noRetryFor, maxAttempts, backoff = @Backoff(...), recover = "...")`, `@CircuitBreaker` | Expressões SpEL nos atributos (`maxAttemptsExpression`) | `@Recover` casado pela **assinatura** (tipo de retorno + exceção + argumentos) | Uma policy por método |
| **Spring Framework 7** (core) | `@Retryable`, `@ConcurrencyLimit`, `@EnableResilientMethods` | Atributos mais placeholders `${…}` | nenhum | n/a |
| **Failsafe** | Sem anotações. `Failsafe.with(fallback, retryPolicy, circuitBreaker, timeout).get(...)` | Só builders | Policy `Fallback.of(...)` (tipada) | **A ordem de composição é a ordem dos argumentos** (externo → interno) |
| **Polly v8 (.NET)** | `ResiliencePipelineBuilder().AddRetry(...).AddCircuitBreaker(...).AddTimeout(...)` | `ResiliencePipelineRegistry<TKey>`, binding de options, **recargas dinâmicas** via `IOptionsMonitor` | Estratégia de fallback (tipada `Outcome<T>`) | Ordem das chamadas `Add*` (primeira = mais externa); inclui estratégias de hedging e caos |

O que tiramos de cada uma:

- **MP FT**: atributos tipados (`Class<? extends Throwable>[]`, não regexes), uma chave de configuração determinística por
  classe/método/anotação, uma chave `enabled` e configuração que sobrescreve as anotações. Os pontos fracos são a ordem rígida
  e a falta de configs nomeadas compartilhadas (até o `@ApplyGuard` do SmallRye).
- **Resilience4j**: configs nomeadas com herança por `baseConfig` e compartilhamento de instâncias entre métodos. Os pontos fracos são
  fallbacks por string sem verificação em tempo de compilação, a mesma string servindo como chave de configuração e identidade do estado, e uma ordem
  que só pode ser alterada globalmente.
- **Failsafe / Polly**: ordem expressa explicitamente no builder, fallbacks tipados e um único predicado "deve tratar"
  que cobre tanto exceções quanto resultados (`handleResultIf`, `ShouldHandle`). O Polly também contribui com hedging,
  registries de pipelines e recarga pela troca de pipelines inteiros.
- **Spring Retry**: a busca do fallback pela assinatura (`@Recover`) dispensa strings, mas os erros de ambiguidade só aparecem em
  tempo de execução. Ficamos com a ideia e levamos a verificação para o tempo de compilação.

## 3. Catálogo de recursos e níveis :id=_3-feature-catalog-and-tiers

| Recurso | Nível | Notas |
|---|---|---|
| Circuit breaker (janelas deslizantes por contagem e por tempo, taxa de chamadas lentas, número de sondagens half-open) | **MVP** | Mesmo modelo do R4j para que os usuários possam migrar. Também `forceOpen`/`disable` para operações e testes. |
| Retry (fixo, exponencial, exponencial + jitter full/equal, duração máxima, retry baseado no resultado) | **MVP** | Jitter ligado por padrão ([AWS backoff & jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)). |
| Timeout (sync e async) | **MVP** | Timeouts sync interrompem o chamador; o comportamento em virtual threads é documentado (§8). |
| Bulkhead (semáforo, máximo de concorrentes + espera máxima) | **MVP** | Um bulkhead de thread pool tem pouco valor com virtual threads; no máximo uma opção da v1. |
| Rate limiter (token bucket/recarga fixa, permissões por período, espera máxima) | **MVP** | Parte do conjunto de comparação com o R4j. |
| Fallback (handler tipado / método verificado) | **MVP** | §7. |
| Async: tipos de retorno `CompletionStage`/`CompletableFuture` | **MVP** | Detectado pela assinatura do método. |
| Anotações compostas (`@ResiliencePolicy`) | **MVP** | A principal resposta a "múltiplas configurações". |
| Configs nomeadas, `extends`, perfis, properties + env + sysprops | **MVP** | §6. |
| API programática com builders (sem anotações) | **MVP** | Também a base para a qual as anotações compilam ou delegam. |
| Eventos (tipados, hierarquia sealed), registro de listeners | **MVP** | Nenhuma alocação quando nenhum listener está registrado. |
| Inspeção de estado (`registry.snapshot()`) | **MVP** | Records somente leitura; bons para endpoints de health. |
| Testes: `Clock`, `Scheduler`, jitter com seed, estados forçados | **MVP** | §10. |
| Hedging (`@Hedge`: espera, máximo de tentativas com hedge) | **v1** | [The Tail at Scale](https://research.google/pubs/the-tail-at-scale/); hedging do Polly. Mutuamente exclusivo com `@Retry` no mesmo nível na v1. |
| Limite de concorrência adaptativo (`@AdaptiveLimit`: AIMD, Vegas, Gradient2) | **v1** | [concurrency-limits](https://github.com/Netflix/concurrency-limits) da Netflix. |
| Budget de retry (proporção limitada de retries por instância) | **v1** | Evita tempestades de retries ([Finagle retry budgets](https://twitter.github.io/finagle/guide/Clients.html#retries)). |
| Bindings de métricas: Micrometer, OpenTelemetry | **v1** | Módulos separados; o core expõe só uma SPI. |
| Fonte de configuração YAML | **v1** | Módulo separado, para que o core não precise de dependência de YAML. |
| API de reconfiguração em tempo real | **v1** | §6.7. |
| Extensão JUnit 5 + asserções de eventos no estilo AssertJ | **v1** (Clock/forçar estados no MVP) | |
| Ordem de execução configurável | **v1** | Ordem padrão no MVP. §5. |
| `@Cached` (cache de resultados com TTL) | **depois** | Normalmente o Caffeine atende melhor; só vale a pena adicionar se puder compartilhar a infraestrutura de chaves/instâncias. |
| Tipos reativos (Reactor/Mutiny), corrotinas Kotlin | **depois** | Módulos adaptadores. |
| Injeção de falhas / caos (`@Inject…` de latência/exceção, como o Simmy do Polly) | **depois** | Muito útil em testes e staging. |
| Propagação de deadline / orçamento de tempo entre chamadas aninhadas | **depois** | Baseia-se em `ScopedValue` (preview no 21, final no [JDK 25, JEP 506](https://openjdk.org/jeps/506)). |
| Integrações com Spring/Quarkus/Micronaut | **depois** | Fora do escopo por decisão. |

## 4. Design das anotações :id=_4-annotation-design

### 4.1 Princípios :id=_41-principles

1. **Atributos tipados sempre que o Java permite.** As listas de exceções são `Class<? extends Throwable>[]`, enums substituem flags
   em string e os fallbacks são classes.
2. **Todo atributo tem um sentinela de "não definido"** (`-1`, `""`, um array vazio, `Inherit.class`). Uma anotação não consegue
   distinguir "não escrito" de "escrito com o valor padrão", então um padrão real no atributo sobrescreveria silenciosamente
   as configs nomeadas e quebraria a herança. Os padrões efetivos vivem na camada de configuração (§6), não na
   anotação.
3. **Durações são strings** em uma única gramática estrita (`"250ms"`, `"2s"`, `"1m"`, `"PT2S"`), porque:
   anotações não podem conter um `Duration`; pares `value + unit` (estilo MP) dobram o número de atributos; e a mesma
   gramática é usada em arquivos de propriedades. O annotation processor rejeita literais inválidos em tempo de compilação (§10.1).
4. **Utilizáveis em métodos e tipos.** Uma anotação no tipo se aplica a todo método público, e uma no método
   a sobrescreve *atributo por atributo* (não por inteiro).
5. **`config` e `name` significam coisas diferentes**:
   - `config` seleciona de qual modelo nomeado partir.
   - `name` seleciona a *instância com estado*. Se vazio, o id da instância é `<fqcn>#<method>(<erased param types>)`.
     Dois métodos com o mesmo `name` compartilham um breaker ou limiter. Os dois métodos precisam resolver para uma
     configuração idêntica; se não resolverem, a inicialização falha.
6. **Toda policy suporta `enabled`**, que pode ser sobrescrito externamente (como no `<Annotation>/enabled` do MP).

### 4.2 Anotações propostas (esboço) :id=_42-proposed-annotations-sketch

```java
package io.resilience4cata.annotation;   // package name provisional

@Retention(RUNTIME) @Target({METHOD, TYPE, ANNOTATION_TYPE}) @Documented
public @interface CircuitBreaker {
    String name() default "";                  // instance id; "" = per method
    String config() default "";                // named template; "" = "default"
    WindowType windowType() default WindowType.INHERIT;   // COUNT, TIME
    int windowSize() default -1;               // calls or seconds
    int minimumCalls() default -1;
    float failureRateThreshold() default -1f;  // percent, 0 < x <= 100
    float slowCallRateThreshold() default -1f;
    String slowCallDuration() default "";
    String waitInOpen() default "";            // e.g. "30s"
    int halfOpenCalls() default -1;
    Class<? extends Throwable>[] recordOn() default {};
    Class<? extends Throwable>[] ignoreOn() default {};
    Class<? extends Classifier> classifier() default Classifier.Inherit.class;
    String enabled() default "";               // "", "true", "false"  (tri-state)
}

@Retention(RUNTIME) @Target({METHOD, TYPE, ANNOTATION_TYPE}) @Documented
public @interface Retry {
    String name() default "";
    String config() default "";
    int maxAttempts() default -1;              // total attempts, including the first
    String delay() default "";                 // initial delay
    Backoff backoff() default Backoff.INHERIT; // FIXED, EXPONENTIAL
    double multiplier() default -1;
    String maxDelay() default "";
    Jitter jitter() default Jitter.INHERIT;    // NONE, FULL, EQUAL
    String maxDuration() default "";           // overall budget across attempts
    Class<? extends Throwable>[] retryOn() default {};
    Class<? extends Throwable>[] abortOn() default {};
    Class<? extends Classifier> classifier() default Classifier.Inherit.class;
    String enabled() default "";
}

public @interface Timeout {            // @Target/@Retention as above
    String value() default "";         // "2s"; empty = from config
    String config() default "";
    String enabled() default "";
}

public @interface Bulkhead {
    String name() default "";
    String config() default "";
    int maxConcurrent() default -1;
    String maxWait() default "";        // "0" = fail fast
    String enabled() default "";
}

public @interface RateLimiter {
    String name() default "";
    String config() default "";
    int permits() default -1;
    String period() default "";         // e.g. "1s"
    String maxWait() default "";
    String enabled() default "";
}

public @interface Fallback {
    Class<? extends io.resilience4cata.FallbackHandler<?>> value() default Inherit.class; // typed handler
    String method() default "";                    // alternative: sibling method, compile-time checked
    Class<? extends Throwable>[] applyOn() default {Throwable.class};
    Class<? extends Throwable>[] skipOn() default {};
}

// v1
public @interface Hedge { String name() default ""; String config() default "";
                          String delay() default ""; int maxHedged() default -1; }
public @interface AdaptiveLimit { String name() default ""; String config() default "";
                                  LimitAlgorithm algorithm() default LimitAlgorithm.INHERIT; /* AIMD, VEGAS, GRADIENT2 */ }
```

Dois tipos de anotação ficam **de fora** de propósito:

- `@Asynchronous` (MP): o tipo de retorno já diz se uma chamada é async. Um marcador separado que precisa concordar com
  o tipo de retorno é redundante e fácil de errar. Para rodar um método *síncrono* em outro executor, use
  `@Timeout` com `offload = true` (questão em aberto §11) ou a API programática.
- `@Bulkhead(type = THREADPOOL)` (R4j): virtual threads tornam o isolamento por semáforo o padrão certo.

### 4.3 Anotações compostas ("meta-anotações") :id=_43-composed-annotations-meta-annotations

Os usuários devem poder nomear um pacote de policies uma vez e reutilizá-lo em todo lugar:

```java
@ResiliencePolicy                              // marks a composed annotation; required
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
@Bulkhead(maxConcurrent = 50)
public @interface ExternalCall {}

@ResiliencePolicy
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Retry(maxAttempts = 1)                         // explicit: no retries for writes
@CircuitBreaker(config = "external")
@Timeout("5s")
public @interface NonIdempotentCall {}

@ExternalCall
public class PaymentsClient {
    public Receipt charge(Order o) { ... }                    // @ExternalCall applies

    @NonIdempotentCall                                         // overrides the class-level bundle
    public Receipt refund(Order o) { ... }

    @Timeout("500ms")                                          // overrides one attribute only
    public Status status(String id) { ... }
}
```

Regras:

- **Profundidade de meta-anotações**: ilimitada, com detecção de ciclos. Uma anotação composta pode ela mesma ser anotada com
  outra anotação composta.
- **Semântica de mesclagem**: funciona como a sobrescrita de atributos do Spring, só que mais simples. A mesclagem é feita por tipo de policy e depois
  atributo por atributo. A anotação direta vence a composta. O nível de método vence o nível de classe. Um
  atributo não definido (sentinela) nunca sobrescreve nada.
- **Conflitos**: se duas anotações compostas no mesmo elemento definem o mesmo atributo de policy com valores diferentes,
  isso é um **erro de compilação** (APT) ou um erro de inicialização (modo só runtime). Um comportamento silencioso de "o último vence"
  não é aceitável.
- As anotações compostas ganham a sua própria chave de configuração (`ExternalCall/Timeout/value`), para que os operadores possam reajustar toda a
  família sem mexer em cada classe.

### 4.4 Interfaces e herança :id=_44-interfaces-and-inheritance

- Uma anotação em um método de interface se aplica às implementações; uma anotação na implementação vence.
- `@Inherited` só é respeitada em anotações de classe (semântica do Java). A herança por meio de overrides de métodos é
  implementada explicitamente e documentada.
- Métodos private, static e final: se podem ser suportados depende do mecanismo de entrelaçamento (veja a
  pesquisa sobre mecanismos). A API deve reportar posicionamentos não suportados como **erros**. O comportamento silencioso do Spring AOP de "a autoinvocação
  não é interceptada" não deve se repetir.

## 5. Ordem de execução :id=_5-execution-order

### 5.1 Padrão :id=_51-default

Da mais externa para a mais interna:

```
Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method
```

Raciocínio:

- **Fallback mais externo**: ele vê o resultado final depois que todas as outras policies rodaram, incluindo `CallNotPermitted`.
- **Retry fora do CircuitBreaker**: cada tentativa é registrada pelo breaker, e um breaker aberto interrompe os retries na hora
  (`abortOn` inclui `CallNotPermittedException` por padrão).
- **RateLimiter dentro do breaker**: chamadas rejeitadas não gastam permissões.
- **Timeout dentro do Retry**: o timeout se aplica a cada tentativa. `Retry.maxDuration` cobre a operação inteira.
- **Bulkhead mais interno**: uma permissão só é mantida enquanto a chamada está de fato rodando, não durante as esperas de backoff.

Isso coincide com o MP FT e é compatível com o padrão do R4j (o R4j não tem o Fallback como camada).

### 5.2 Ordem configurável (v1) :id=_52-configurable-order-v1

Usuários que precisam de outra ordem (por exemplo, um timeout em volta do laço de retry *inteiro*) podem declará-la explicitamente. A
ordem é validada e armazenada por instância; nunca é decidida por chamada:

```java
@ResiliencePolicy
@Retention(RUNTIME) @Target({METHOD, TYPE})
@Order({Layer.FALLBACK, Layer.TIMEOUT, Layer.RETRY, Layer.CIRCUIT_BREAKER})  // omitted layers keep their relative default order
@Retry(config = "external") @CircuitBreaker(config = "external") @Timeout("10s")
public @interface WholeOperationDeadline {}
```

Ordem padrão global: `resilience4cata.order=fallback,retry,circuit-breaker,...`. Em vez de um segundo `@Timeout`, um timeout
*por tentativa* e um *da operação inteira* deveriam ser dois atributos separados: `@Timeout("500ms")` é por tentativa e
`@Retry(maxDuration = "3s")` é o total. Isso cobre cerca de 90% das necessidades reais sem `@Order`.

Qualquer ordem que mude a semântica de forma surpreendente (Bulkhead fora do Retry, Fallback não mais externo) produz um
**aviso** na inicialização que nomeia a consequência.

## 6. Múltiplas configurações :id=_6-multiple-configurations

### 6.1 Conceitos :id=_61-concepts

| Conceito | O que é | Mutável? |
|---|---|---|
| **Defaults** | Valores embutidos para cada policy, sobrescrevíveis em `defaults` | Não (depois do build) |
| **Config nomeada** | Um modelo, p. ex. `external`, `internal`, `batch`. Pode fazer `extends` de outra config nomeada | Não |
| **Perfil** | Uma sobreposição que é ativada como um todo (`prod`, `test`, `eu-west`). Sobrescreve chaves de defaults e configs nomeadas | Não |
| **Instância** | Objeto com estado (estado do breaker, janela, permissões) com um id | Estado sim, config só via `reconfigure` |
| **Policy resolvida** | Record imutável com todos os valores finais, calculado uma vez por instância | Não |

### 6.2 Precedência (da menor para a maior) :id=_62-precedence-lowest-to-highest

```
 1. built-in defaults
 2. defaults.<policy>.*                       (file/env/…)
 3. configs.<name>.<policy>.*                 (walks the `extends` chain, root first)
 4. profiles.<active>.…                       (applies over 2 and 3, in profile activation order)
 5. composed-annotation attributes            (@ExternalCall)
 6. class-level annotation attributes
 7. method-level annotation attributes
 8. instances.<id>.<policy>.*                 external per-instance override
 9. programmatic override (builder)           highest; for tests and explicit code
```

Dentro de uma camada externa, a prioridade das fontes é a mesma do MP Config: **propriedades do sistema > variáveis de
ambiente > arquivos da aplicação > padrões do classpath**. Cada fonte se conecta por uma pequena SPI `ConfigSource`.

A configuração externa (8) sobrescreve as anotações (5–7), como no MP FT. As anotações são os padrões do desenvolvedor, e os operadores
precisam poder alterá-los sem recompilar. Um usuário que quer um valor que não pode ser sobrescrito marca a instância
como `locked: true`, ou chama `.locked()` programaticamente, e as tentativas de sobrescrevê-lo são registradas em log.

### 6.3 Formato externo :id=_63-external-format

Properties (core, sem dependência):

```properties
resilience4cata.profiles.active=prod

resilience4cata.defaults.retry.max-attempts=3
resilience4cata.defaults.retry.jitter=full

resilience4cata.configs.external.circuit-breaker.failure-rate-threshold=50
resilience4cata.configs.external.circuit-breaker.wait-in-open=30s
resilience4cata.configs.external.retry.delay=100ms

resilience4cata.configs.slow-external.extends=external
resilience4cata.configs.slow-external.timeout.value=10s

resilience4cata.profiles.test.configs.external.circuit-breaker.wait-in-open=10ms

# per instance, by explicit name …
resilience4cata.instances.payments.circuit-breaker.minimum-calls=20
# … or by derived id, MP-compatible spelling also accepted
com.acme.PaymentsClient/charge/Timeout/value=3s
```

YAML (módulo `yaml`, v1) é a mesma árvore:

```yaml
resilience4cata:
  defaults:
    retry: { max-attempts: 3, jitter: full }
  configs:
    external:
      circuit-breaker: { failure-rate-threshold: 50, wait-in-open: 30s }
      retry: { delay: 100ms }
    slow-external:
      extends: external
      timeout: { value: 10s }
  profiles:
    test:
      configs:
        external:
          circuit-breaker: { wait-in-open: 10ms }
```

As **variáveis de ambiente** usam o mapeamento do MP Config: maiúsculas, e todo caractere não alfanumérico vira `_`, então
`RESILIENCE4CATA_CONFIGS_EXTERNAL_RETRY_DELAY=200ms`. Esse mapeamento não pode ser revertido quando um nome de config contém `-`
ou `_`. Daí saem duas regras: os nomes são restritos a `[a-z0-9]+(-[a-z0-9]+)*`, e a fonte de ambiente casa normalizando
chaves *conhecidas* em vez de interpretar os nomes das variáveis de ambiente.

### 6.4 API programática :id=_64-programmatic-api

A configuração programática tem o mesmo poder que os arquivos e as anotações. As anotações são açúcar sintático sobre este modelo.

```java
Resilience resilience = Resilience.builder()
    .source(ConfigSources.classpath("resilience4cata.properties"))
    .source(ConfigSources.environment())
    .source(ConfigSources.systemProperties())
    .profiles("prod")
    .defaults(d -> d.retry(r -> r.maxAttempts(3).jitter(Jitter.FULL)))
    .config("external", c -> c
        .circuitBreaker(cb -> cb.failureRateThreshold(50).waitInOpen(Duration.ofSeconds(30)))
        .retry(r -> r.delay(Duration.ofMillis(100)).retryOn(IOException.class)))
    .config("slow-external", c -> c.extendsConfig("external").timeout(Duration.ofSeconds(10)))
    .clock(Clock.system())                        // replaceable in tests
    .listener(event -> log.debug("{}", event))
    .strict(true)                                 // fail on unknown keys/names (default true)
    .build();                                     // all validation happens here

// Without annotations: an explicit, reusable, immutable pipeline
Pipeline<Receipt> charge = resilience.pipeline("payments", Receipt.class)
    .config("external")
    .fallback((Throwable t) -> Receipt.pending())
    .build();
Receipt r = charge.execute(() -> client.charge(order));

// With annotations: the woven/generated object comes from the same Resilience instance
PaymentsClient client = resilience.wrap(PaymentsClient.class, new PaymentsClient());  // mechanism TBD (doc 02)
```

### 6.5 Validação: quando :id=_65-validation-when

| Verificação | Tempo de compilação (APT, se usado) | `build()` / inicialização | Runtime |
|---|---|---|---|
| Faixas de atributos, sintaxe de durações, sobreposição `retryOn ∩ abortOn` | ✔ | ✔ (no modo só runtime) | |
| Compatibilidade da assinatura do fallback | ✔ | ✔ | |
| Conflitos entre anotações compostas, posicionamento ilegal (private/final) | ✔ | ✔ | |
| O nome de `config` referenciado existe | ✘ (as configs são externas) | ✔ | |
| Chaves desconhecidas em arquivos (erros de digitação), com "você quis dizer `wait-in-open`?" | | ✔ | |
| Ciclos de `extends`, `name` compartilhado com configs divergentes | | ✔ | |
| Perfil que não existe | | ✔ (aviso) | |

Regra: **depois que `build()` retorna, nenhum erro de configuração pode aparecer durante uma chamada.** As instâncias podem ser criadas de forma preguiçosa
(na primeira chamada) para acelerar a inicialização. Nesse caso, a configuração do método ainda assim foi validada antecipadamente durante
`build()`, varrendo os tipos anotados registrados (o índice do APT os lista).

### 6.6 Mantendo o hot path limpo :id=_66-keeping-the-hot-path-clean

- A resolução produz `record ResolvedRetry(int maxAttempts, long delayNanos, ...)` e records semelhantes: primitivos,
  campos final, durações já convertidas para nanos, conjuntos de exceções pré-compilados em um classificador (§7.3).
- Cada call site entrelaçado guarda uma referência direta ao seu `Pipeline` (um campo static final no código gerado, ou um
  handle em cache por método no modo proxy). **Não há buscas em mapa por nome a cada chamada**.
- As buscas por nome (`resilience.circuitBreaker("payments")`) são para gestão e testes, não para invocações.

### 6.7 Hot reload: vale o custo? :id=_67-hot-reload-worth-the-cost

**Não no MVP. Na v1 como API explícita, não como observador de arquivos.**

- *Custo*: uma leitura `volatile`/acquire de um snapshot de configuração por policy por chamada. Isso é barato em x86 e um pouco mais
  caro em ARM. Também impede parte do constant folding do JIT, o que importa quando os parâmetros da policy seriam de outra forma
  tratados como constantes. As instâncias que nunca são reconfiguradas não devem pagar **nada**. Formas possíveis de conseguir isso:
  uma flag `reloadable` que seleciona outra implementação no build, ou invalidação por `MutableCallSite`/`SwitchPoint`,
  que mantém o caminho rápido com constant folding. A pesquisa de concorrência deveria fazer benchmark disso.
- *Semântica*: alguns parâmetros podem mudar com segurança (limiares, esperas, máximo de tentativas, permissões, timeout). Outros mudam
  a forma do estado (tipo ou tamanho da janela, tamanho do bulkhead enquanto há permissões em uso). Estes últimos exigem um reset
  documentado do estado ou são rejeitados.
- *API*:

```java
resilience.reconfigure("payments", c -> c.circuitBreaker(cb -> cb.failureRateThreshold(30)));
// or: resilience.reload()  -> re-reads all sources, validates everything, then swaps atomically, all-or-nothing
```

Observar arquivos ou configuração remota fica a cargo das integrações, que chamam `reload()`. O comportamento equivalente do Polly também
vem do seu host (`IOptionsMonitor`), não da biblioteca principal.

## 7. Fallbacks e classificação de exceções :id=_7-fallbacks-and-exception-classification

### 7.1 Handler de fallback tipado (forma principal) :id=_71-typed-fallback-handler-primary-form

```java
public interface FallbackHandler<T> {
    T apply(FallbackContext ctx) throws Throwable;   // ctx: failure, attempt count, args, method, instance id
}

public final class UserFallback implements FallbackHandler<User> {
    @Override public User apply(FallbackContext ctx) {
        return ctx.failure() instanceof CallNotPermittedException
            ? User.anonymous()
            : User.cached(ctx.argument(0, String.class));
    }
}

public class UserClient {
    @Retry @CircuitBreaker(config = "external")
    @Fallback(UserFallback.class)
    public User find(String id) { ... }
}
```

O annotation processor verifica que `T` é atribuível ao tipo de retorno do método, ou ao `T` dentro de
`CompletionStage<T>` em métodos async. Os handlers são criados uma vez por instância por meio de uma `HandlerFactory`, que
por padrão usa o construtor sem argumentos e pode ser ligada a DI mais tarde.

### 7.2 Fallback por método irmão (forma de conveniência, verificada em tempo de compilação) :id=_72-sibling-method-fallback-convenience-form-compile-time-checked

```java
@Fallback(method = "findFallback")
public User find(String id) { ... }

private User findFallback(String id, IOException e) { ... }        // most specific match wins
private User findFallback(String id, Throwable t) { ... }
```

Regras, que seguem o R4j e o `@Recover` do Spring e são tornadas estritas:

- Os parâmetros devem ser os parâmetros originais, opcionalmente seguidos de **um** subtipo de `Throwable`.
- O tipo de retorno deve ser atribuível ao tipo de retorno original. Em métodos async, pode ser `T` ou
  `CompletionStage<T>`.
- Quando existem várias sobrecargas, o tipo de exceção mais específico vence. Se nenhuma casa, a exceção original é
  relançada.
- **Todas as verificações rodam no annotation processor**, e os erros incluem sugestões (§10.1). No modo só runtime elas rodam
  em `build()`.
- A string não pode ser evitada: anotações Java não podem conter referências a métodos. Gerar constantes a partir do
  metamodelo do APT (`UserClient_.FIND_FALLBACK`) foi considerado e rejeitado, por problemas de ordem entre rounds e
  erros confusos quando o processor não está ativo.

### 7.3 Classificação de exceções e resultados :id=_73-exception-and-result-classification

Cada policy que registra resultados (CircuitBreaker, Retry, Hedge, AdaptiveLimit) tem três entradas:

1. **Listas de permissão/negação**: `recordOn`/`ignoreOn` (CB), `retryOn`/`abortOn` (Retry). Precedência: **a negação vence a permissão**,
   depois vence o **tipo mais específico** da hierarquia (semântica do MP FT). Padrões: o CB registra `Exception`, o Retry
   repete `Exception`, e nenhum trata `Error` (exceto `StackOverflowError`? → questão em aberto).
2. **Desembrulhamento**: `CompletionException`, `ExecutionException`, `InvocationTargetException` e
   `UndeclaredThrowableException` são desembrulhadas antes da classificação. Isso é configurável.
3. **Classifier** para tudo o que as listas não conseguem expressar, incluindo resultados:

```java
@FunctionalInterface
public interface Classifier<T> {
    Outcome classify(T result, Throwable failure);   // exactly one of result/failure is meaningful
    enum Outcome { SUCCESS, FAILURE, IGNORE }        // IGNORE = do not count, do not retry
}

public final class HttpStatusClassifier implements Classifier<HttpResponse<?>> {
    public Outcome classify(HttpResponse<?> r, Throwable t) {
        if (t != null) return t instanceof HttpTimeoutException ? Outcome.FAILURE : Outcome.IGNORE;
        int s = r.statusCode();
        return s >= 500 || s == 429 ? Outcome.FAILURE : Outcome.SUCCESS;
    }
}

@Retry(config = "external", classifier = HttpStatusClassifier.class)
@CircuitBreaker(config = "external", classifier = HttpStatusClassifier.class)
public HttpResponse<String> get(URI uri) { ... }
```

- Usar um único método para resultados e exceções evita a divisão do R4j entre `recordExceptions`,
  `recordFailurePredicate` e `recordResultPredicate`, e a confusão sobre a precedência entre eles.
- Um *resultado* `FAILURE` é registrado ou repetido. Se os retries se esgotam, o último resultado é retornado (padrão) ou uma
  `ResultRejectedException` é lançada. Isso é configurável por `Retry.onExhaustedResult = RETURN | THROW`.
- **Desempenho**: as listas são compiladas no momento da resolução em um cache `ClassValue<Outcome>`, então classificar uma dada
  classe de exceção é uma única busca depois da primeira vez. Isso é mais barato do que percorrer `isAssignableFrom` sobre a
  lista a cada falha.

## 8. Async e virtual threads :id=_8-async-and-virtual-threads

- **O tipo de retorno decide**: `CompletionStage<T>`/`CompletableFuture<T>` recebe o pipeline async (retries não bloqueantes
  agendados em um `Scheduler`, timeouts por agendamento ao estilo de `orTimeout`, permissões do bulkhead liberadas na conclusão).
  Qualquer outro tipo de retorno é sync.
- **Sync em virtual threads** é o estilo recomendado do Java 21. O backoff do retry põe a virtual thread para dormir, o que é
  barato ([JEP 444](https://openjdk.org/jeps/444)). Bulkheads por semáforo bloqueiam virtual threads em vez de threads de
  plataforma.
- **Timeout sync** precisa (a) rodar a chamada em outra thread (virtual) e interrompê-la no timeout, ou
  (b) um watchdog que interrompe a thread chamadora. (b) evita trocar de thread, mas depende de o código chamado respeitar
  interrupções. Proposta: (b) por padrão para sync, documentado como "cooperativo". A pesquisa de concorrência decide a
  implementação. A structured concurrency ([JEP 505](https://openjdk.org/jeps/505)) ainda é preview nos JDKs recentes,
  então a API não deve depender dela.
- **Executors** são configuráveis por config (`async.executor`), com `Executors.newVirtualThreadPerTaskExecutor()` como
  padrão para qualquer trabalho que a própria biblioteca delegue a outra thread.
- **Hedging** (v1) precisa de concorrência, então sempre roda as tentativas com hedge em outras threads, também em métodos sync, e cancela as
  perdedoras.

## 9. Eventos, métricas e inspeção de estado :id=_9-events-metrics-and-state-inspection

```java
public sealed interface ResilienceEvent permits CircuitBreakerEvent, RetryEvent, TimeoutEvent,
                                                BulkheadEvent, RateLimiterEvent, FallbackEvent {
    String instance(); long nanoTime();
}
public sealed interface CircuitBreakerEvent extends ResilienceEvent {
    record StateTransition(String instance, long nanoTime, State from, State to) implements CircuitBreakerEvent {}
    record CallNotPermitted(String instance, long nanoTime) implements CircuitBreakerEvent {}
    record FailureRateExceeded(String instance, long nanoTime, float rate) implements CircuitBreakerEvent {}
}

resilience.listen(CircuitBreakerEvent.StateTransition.class, e -> alerting.notify(e));
resilience.listen("payments", RetryEvent.class, e -> ...);     // per instance
```

- **Custo zero quando não usado**: cada instância guarda uma referência `final` ao listener que é `NOOP` quando nada está
  registrado. Os records de eventos só são criados atrás de uma verificação `if (listener != NOOP)`. Os eventos de sucesso por chamada são
  opcionais (`events.per-call=true`). Os eventos de transição de estado são sempre entregues.
- **Métricas**: o core conta os resultados em `LongAdder`s locais à instância, expostos por `snapshot()`. Os módulos `micrometer` e
  `opentelemetry` (v1) ligam os snapshots como gauges ou function counters, então o hot path não faz buscas de
  medidores.
- **Inspeção de estado** (health checks, endpoints de administração):

```java
ResilienceSnapshot s = resilience.snapshot();
for (CircuitBreakerSnapshot cb : s.circuitBreakers()) {
    System.out.printf("%s %s failureRate=%.1f%% calls=%d%n", cb.instance(), cb.state(), cb.failureRate(), cb.bufferedCalls());
}
resilience.circuitBreaker("payments").forceOpen();   // operational controls: forceOpen, forceClosed, disable, reset
```

## 10. Experiência do desenvolvedor e testes :id=_10-developer-experience-and-testing

### 10.1 Mensagens de erro :id=_101-error-messages

Todos os erros incluem **onde** (classe, método, anotação, atributo, ou chave de configuração com a sua fonte), **o quê** e
**como corrigir**:

```
error: [R4C-FB-002] @Fallback(method = "findFallbak") on com.acme.UserClient.find(String):
       no method named 'findFallbak' found.
       Did you mean 'findFallback'?
       Expected signature: User findFallbak(String id[, <? extends Throwable> e])

error: [R4C-CFG-011] unknown key 'resilience4cata.configs.external.circuit-breaker.wait-in-opne'
       (from classpath:resilience4cata.properties, line 7). Did you mean 'wait-in-open'?

error: [R4C-CMP-004] conflicting values for @Timeout.value on com.acme.PaymentsClient.charge(Order):
       "2s" from @ExternalCall and "5s" from @NonIdempotentCall. Set it explicitly on the method to resolve.
```

- Códigos de erro estáveis (`R4C-…`) com uma página de documentação por código, em inglês e espanhol.
- Um **dump de diagnóstico**, `resilience.describe("com.acme.PaymentsClient#charge")`, imprime a configuração efetiva
  com a *origem* de cada valor (`waitInOpen=30s ← configs.external (prod profile, env)`). Isso ajuda muito com
  configuração em camadas.
- As verificações em tempo de compilação estão disponíveis quando o annotation processor está em uso (a decisão sobre o mecanismo cabe ao
  doc 02). Caso contrário, as mesmas verificações rodam em `build()`, com mensagens idênticas.

### 10.2 A história dos testes :id=_102-testing-story

O core fornece os pontos de extensão (MVP); o módulo `test` acrescenta conveniências (extensão JUnit na v1).

```java
var clock = new ManualClock();                       // implements io.resilience4cata.Clock (nanoTime + wall)
var scheduler = new ManualScheduler(clock);          // retries/timeouts advance on clock.advance(...)

Resilience r = Resilience.builder()
    .clock(clock).scheduler(scheduler)
    .random(new SplittableRandom(42))                // deterministic jitter
    .profiles("test")
    .build();

UserClient client = r.wrap(UserClient.class, fakeClient);

fakeClient.failNext(5, new IOException("boom"));
assertThat(client.find("1")).isEqualTo(User.anonymous());

clock.advance(Duration.ofSeconds(30));               // move past waitInOpen without sleeping
assertThat(r.circuitBreaker("payments").state()).isEqualTo(State.HALF_OPEN);

// JUnit 5 (v1)
@ResilienceTest(profiles = "test")
class UserClientTest {
    @Inject ManualClock clock;
    @Inject RecordedEvents events;

    @Test void opensAfterFailures(@ForceState(instance = "payments", state = State.OPEN) CircuitBreakerHandle cb) {
        assertThrows(CallNotPermittedException.class, () -> client.find("1"));
        events.assertThat().containsExactly(CallNotPermitted.class);
    }
}
```

- **Desligar tudo** para testes unitários da lógica de negócio com `resilience4cata.enabled=false`, ou
  `Resilience.disabled()`, um repasse direto que ainda roda os fallbacks só se `fallback.when-disabled=true`.
- **Reset entre testes**: `resilience.reset()` reinicia o estado de todas as instâncias, mas mantém a configuração.
- **Injeção de falhas** (depois) é reutilizável em testes de integração: `@InjectLatency("200ms", rate = 0.1)`.

## 11. Questões em aberto :id=_11-open-questions

1. As durações devem ser strings (proposto) ou `long` + `TimeUnit` (estilo MP)? Strings são legíveis e casam com o formato
   dos arquivos; números ganham verificação de tipo do javac mesmo sem o processor.
2. O annotation processor deve ser **obrigatório** ou opcional? Se for opcional, toda verificação em tempo de compilação precisa ser repetida
   em `build()`. Isso depende da decisão sobre o mecanismo (doc 02).
3. Os `Error`s (p. ex. `OutOfMemoryError`) devem passar por fora de todas as policies? Proposta: sim, exceto uma lista de permissão configurável.
4. `@Timeout` deve oferecer `offload = true` para código que não responde a interrupções?
5. A configuração externa deve poder **adicionar** policies que um método não declarou
   (`instances.<id>.retry.enabled=true`)? O MP FT só permite sobrescrever as declaradas. Permitir isso é poderoso, mas torna
   o código mais difícil de entender, e custa parte da flexibilidade do código gerado. Proposta: não.
6. Mantemos as chaves compatíveis com MP (`<class>/<method>/<Annotation>/<param>`) só como aliases, ou as descartamos? Elas ajudam
   na migração, mas dobram a documentação.
7. Granularidade padrão das instâncias: por método (proposto) ou por classe? O compartilhamento por nome do R4j é comum para "um breaker
   por serviço downstream". O `name` explícito cobre esse caso.

## 12. Fontes :id=_12-sources

- Documentação do Resilience4j: <https://resilience4j.readme.io/docs> — uso com Spring Boot / anotações e ordem dos aspects:
  <https://resilience4j.readme.io/docs/getting-started-3>; código-fonte: <https://github.com/resilience4j/resilience4j>
- Especificação MicroProfile Fault Tolerance 4.1 (anotações, chaves de configuração, ordem de execução):
  <https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.1/microprofile-fault-tolerance-spec-4.1.html>;
  repositório: <https://github.com/eclipse/microprofile-fault-tolerance>
- MicroProfile Config 3.1, mapeamento de variáveis de ambiente:
  <https://download.eclipse.org/microprofile/microprofile-config-3.1/microprofile-config-spec-3.1.html>
- SmallRye Fault Tolerance (extensões: `@RateLimit`, anotações de backoff, `@ApplyGuard`, API programática):
  <https://smallrye.io/docs/smallrye-fault-tolerance/>
- Spring Retry (`@Retryable`, `@Recover`, `@Backoff`): <https://github.com/spring-projects/spring-retry>
- Recursos de resiliência do core do Spring Framework 7 (`@Retryable`, `@ConcurrencyLimit`):
  <https://docs.spring.io/spring-framework/reference/core/resilience.html>
- Policies e composição do Failsafe: <https://failsafe.dev/policies/>, <https://failsafe.dev/>
- Polly v8: pipelines <https://www.pollydocs.org/pipelines/>, hedging <https://www.pollydocs.org/strategies/hedging.html>,
  registry e recargas dinâmicas <https://www.pollydocs.org/advanced/dependency-injection.html>,
  caos <https://www.pollydocs.org/chaos/>
- Netflix concurrency-limits (limites adaptativos): <https://github.com/Netflix/concurrency-limits>
- J. Dean, L. Barroso, "The Tail at Scale" (hedged requests): <https://research.google/pubs/the-tail-at-scale/>
- M. Brooker, "Exponential Backoff And Jitter": <https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/>
- Finagle retry budgets: <https://twitter.github.io/finagle/guide/Clients.html#retries>
- JEP 444 Virtual Threads: <https://openjdk.org/jeps/444>; JEP 505 Structured Concurrency (preview):
  <https://openjdk.org/jeps/505>; JEP 506 Scoped Values: <https://openjdk.org/jeps/506>
