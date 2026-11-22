# Chaves de configuração

Cada chave que o módulo `resilience4cata-config` lê, com sua variável de ambiente, tipo, padrão embutido e valores válidos.

Esta página é a referência; [o guia de configuração](../guides/configuration.md) explica como usá-la. As tabelas são verificadas contra o código por um teste (`ConfigurationReferenceTest` no módulo `config`), então uma chave ou um padrão que muda no código quebra o build até que esta página acompanhe.

## Formas das chaves :id=key-forms

Cada tabela lista a forma `defaults.<policy>.<attribute>`. A mesma parte `<policy>.<attribute>` funciona em todas as formas:

| Forma | Aplica-se a | Exemplo |
|---|---|---|
| `defaults.<policy>.<attribute>` | todas as instâncias da política | `defaults.retry.maxAttempts=5` |
| `configs.<name>.<policy>.<attribute>` | instâncias cuja anotação diz `config = "<name>"` | `configs.external.timeout.value=2s` |
| `configs.<name>.extends=<other>` | herda todas as políticas de outra config nomeada | `configs.batch.extends=external` |
| `instances.<id>.<policy>.<attribute>` | uma instância: seu `name`, ou seu id derivado entre aspas duplas | `instances."com.acme.Api#get(java.lang.String)".timeout.value=2s` |
| `profiles.<profile>.<any key above>` | só enquanto o perfil está ativo | `profiles.test.defaults.retry.maxAttempts=1` |
| `profiles.active=<a>,<b>` | os perfis ativos, os posteriores vencem | `profiles.active=test` |

- Políticas: `retry`, `hedge`, `circuitBreaker`, `rateLimiter`, `adaptiveLimit`, `timeout`, `bulkhead`. `@Fallback` não tem chaves.
- Nomes de políticas e de atributos podem ser escritos em camelCase ou kebab-case: `circuit-breaker.wait-in-open` é `circuitBreaker.waitInOpen`.
- Nomes de configs e de perfis são letras minúsculas e dígitos separados por hífens simples (`slow-external`).
- Um id de instância diferencia maiúsculas de minúsculas. Ele precisa estar entre aspas duplas, a menos que seja feito só de letras, dígitos, `_` e `-`. Um id derivado é `<type>#<method>(<parameter types>)`, com tipos de parâmetro totalmente qualificados e apagados (erasure), separados por vírgulas sem espaços, por exemplo `com.acme.CatalogClient#price(java.lang.String,int)`.
- Toda chave pode começar com `r4c.` ou `resilience4cata.`. Nas **propriedades de sistema o prefixo é obrigatório**; as outras propriedades de sistema são ignoradas.
- Uma chave com o prefixo curto (`r4c.`, ou `R4C_` no ambiente) cujo primeiro segmento não é `defaults`, `configs`, `instances` ou `profiles`, nem parecido com um deles, pertence a outra ferramenta e é ignorada: `-Dr4c.debug=true` ou `R4C_TOKEN` não impedem a aplicação de iniciar. Uma chave que parece nossa (`r4c.defualts.…`) continua sendo um erro ([R4C-CFG-001](../errors.md#r4c-cfg-001)), assim como qualquer chave desconhecida com o prefixo longo ou sem prefixo.
- Alguns atributos existem só nas anotações e não têm chave: `name`, `config`, `locked`, `classifier`, `onExhaustedResult`, e tudo em `@Fallback`.

## Variáveis de ambiente :id=environment-variables

`R4C_` (ou `RESILIENCE4CATA_`) seguido da chave em maiúsculas, com todo caractere que não seja letra ou dígito substituído por `_`. As tabelas mostram a variável de cada chave `defaults.`; as outras formas seguem a mesma regra:

| Chave | Variável |
|---|---|
| `configs.external.retry.maxAttempts` | `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS` |
| `configs.slow-external.timeout.value` | `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE` |
| `instances.payments-api.retry.maxAttempts` | `R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS` |
| `profiles.active` | `R4C_PROFILES_ACTIVE` |

As palavras também podem ser separadas (`R4C_DEFAULTS_CIRCUIT_BREAKER_WAIT_IN_OPEN`). Uma variável precisa corresponder a exatamente uma chave ([R4C-CFG-010](../errors.md#r4c-cfg-010)). Instâncias só podem ser definidas pelo ambiente quando o nome é feito de letras minúsculas, dígitos e hífens; um id derivado precisa de um arquivo ou de uma propriedade de sistema.

## Valores :id=values

| Tipo | Sintaxe |
|---|---|
| duração | dígitos e uma unidade: `ns`, `us`, `ms`, `s`, `m`, `h`, `d` (`250ms`, `2s`); uma duração ISO-8601 (`PT2S`, `PT1M30S`); ou `0`. Sem decimais, sem valores negativos, sem espaços. A mesma gramática das anotações. |
| int | um número inteiro (`5`) |
| decimal | um número como `50` ou `2.5` |
| boolean | `true` ou `false` |
| enum | um dos valores listados, sem diferenciar maiúsculas de minúsculas (`exponential`, `EXPONENTIAL`) |
| lista de classes | nomes binários de classes de exceção separados por vírgulas (`java.io.IOException, java.net.SocketTimeoutException`), carregados com o class loader configurado; classes aninhadas como `a.B$C` ou `a.B.C`; vazio para nenhuma |

As combinações também são verificadas: `maxDelay` não pode ser menor que `delay`, e o `period` de um rate limiter precisa ter pelo menos um nanossegundo por permissão ([R4C-CFG-006](../errors.md#r4c-cfg-006)).

## Fontes e precedência :id=precedence

`ResilienceConfig.load()` lê, da menor para a maior: o recurso de classpath `resilience4cata.properties` (opcional), as variáveis de ambiente, as propriedades de sistema. Com `ResilienceConfig.builder()` você escolhe as fontes; para uma mesma chave elas se ordenam recursos de classpath < arquivos e `Properties` < variáveis de ambiente < propriedades de sistema, e entre fontes do mesmo tipo vence a adicionada por último. Dentro de uma fonte, uma chave sob um perfil ativo vence a mesma chave sem perfil; uma fonte mais forte vence até uma chave de perfil de uma mais fraca.

Os valores então se combinam, do menor para o maior:

1. padrões embutidos (a coluna **Padrão** abaixo);
2. `defaults.*` (e `ResilienceRegistry.Builder.*Defaults(...)` no código);
3. `configs.<name>.*`, começando pela raiz da cadeia de `extends`; as chaves externas sobrescrevem uma config com o mesmo nome registrada no código, atributo por atributo;
4. os atributos das anotações;
5. `instances.<id>.*`, para que a operação possa reajustar uma instância sem recompilar, exceto os atributos de uma anotação com `locked = true`, que mantêm o valor da anotação ([Travar uma instância](../guides/configuration.md#locking-an-instance)).

Tudo é resolvido uma vez, quando uma instância é criada. Nada é recarregado depois. Para verificar que cada chave `instances.<id>` corresponde a uma instância, chame `config.verifyInstances(registry)` depois de envolver seus serviços ([R4C-CFG-013](../errors.md#r4c-cfg-013)); ele também reporta as chaves que uma anotação travada ignora ([R4C-CFG-014](../errors.md#r4c-cfg-014)).

## Retry :id=retry

Atributos de `@Retry`. Veja [Retry](../policies/retry.md).

| Chave | Variável de ambiente | Tipo | Padrão | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.retry.enabled` | `R4C_DEFAULTS_RETRY_ENABLED` | boolean | `true` | `true`, `false` | `false` executa cada chamada uma vez, sem retry. |
| `defaults.retry.maxAttempts` | `R4C_DEFAULTS_RETRY_MAXATTEMPTS` | int | `3` | ≥ 1 | Total de tentativas, incluindo a primeira. |
| `defaults.retry.delay` | `R4C_DEFAULTS_RETRY_DELAY` | duração | `200ms` | ≥ 0 | Atraso antes do primeiro retry; a base do backoff. |
| `defaults.retry.backoff` | `R4C_DEFAULTS_RETRY_BACKOFF` | enum | `exponential` | `fixed`, `exponential` | `fixed`: todo atraso é `delay`. `exponential`: `delay × multiplier^(n−1)`, limitado a `maxDelay`. |
| `defaults.retry.multiplier` | `R4C_DEFAULTS_RETRY_MULTIPLIER` | decimal | `2.0` | finito, ≥ 1 | Fator de crescimento do backoff exponencial. |
| `defaults.retry.maxDelay` | `R4C_DEFAULTS_RETRY_MAXDELAY` | duração | `10s` | ≥ `delay` | Limite superior de um único atraso. |
| `defaults.retry.jitter` | `R4C_DEFAULTS_RETRY_JITTER` | enum | `full` | `none`, `full`, `equal` | `full`: aleatório em [0, d]. `equal`: d/2 mais uma metade aleatória. `none`: exatamente d. |
| `defaults.retry.maxDuration` | `R4C_DEFAULTS_RETRY_MAXDURATION` | duração | `0` | ≥ 0; `0` = ilimitado | Orçamento de tempo para a operação inteira: desiste quando esperar o próximo atraso o ultrapassaria. |
| `defaults.retry.retryOn` | `R4C_DEFAULTS_RETRY_RETRYON` | lista de classes | `java.lang.Exception` | classes de exceção | Falhas que são repetidas, subclasses incluídas. `Error`s não são repetidos por padrão. |
| `defaults.retry.abortOn` | `R4C_DEFAULTS_RETRY_ABORTON` | lista de classes | — | classes de exceção | Falhas que nunca são repetidas; vencem `retryOn` e um classifier. Uma `CallNotPermittedException` (breaker aberto) também nunca é repetida, listada ou não. |
| `defaults.retry.budgetPercent` | `R4C_DEFAULTS_RETRY_BUDGETPERCENT` | decimal | `0` | finito, ≥ 0 | Orçamento de retries: retries permitidos, em porcentagem das chamadas da instância no último `budgetWindow`, além de `budgetMinRetriesPerSecond`. Acima de `0` ativa o orçamento; `0` significa sem orçamento. |
| `defaults.retry.budgetMinRetriesPerSecond` | `R4C_DEFAULTS_RETRY_BUDGETMINRETRIESPERSECOND` | int | `10` | ≥ 0 | Orçamento de retries: retries permitidos por segundo de `budgetWindow`, seja qual for o tráfego, uma vez que `budgetPercent` ativa o orçamento. `0` deixa só a porcentagem. |
| `defaults.retry.budgetWindow` | `R4C_DEFAULTS_RETRY_BUDGETWINDOW` | duração | `10s` | 1s a 1h | Orçamento de retries: até quanto tempo atrás chamadas e retries são contados. |

## Hedge :id=hedge

Atributos de `@Hedge`. Veja [Hedge](../policies/hedge.md).

| Chave | Variável de ambiente | Tipo | Padrão | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.hedge.enabled` | `R4C_DEFAULTS_HEDGE_ENABLED` | boolean | `true` | `true`, `false` | `false` executa cada chamada uma vez, sem hedge. |
| `defaults.hedge.maxAttempts` | `R4C_DEFAULTS_HEDGE_MAXATTEMPTS` | int | `2` | 2 a 10 | Tentativas em andamento no máximo, incluindo a primeira. |
| `defaults.hedge.delay` | `R4C_DEFAULTS_HEDGE_DELAY` | duração | `1s` | ≥ 0; `0` = todas de uma vez | Quanto tempo a tentativa mais recente roda sem resultado antes que a próxima comece. |
| `defaults.hedge.hedgeOn` | `R4C_DEFAULTS_HEDGE_HEDGEON` | lista de classes | `java.lang.Exception` | classes de exceção | Falhas depois das quais a próxima tentativa começa na hora; uma falha não listada encerra a chamada. |
| `defaults.hedge.abortOn` | `R4C_DEFAULTS_HEDGE_ABORTON` | lista de classes | — | classes de exceção | Falhas que encerram a chamada na hora e cancelam as outras tentativas; vencem `hedgeOn`. |
| `defaults.hedge.budgetPercent` | `R4C_DEFAULTS_HEDGE_BUDGETPERCENT` | decimal | `10` | finito, ≥ 0 | Orçamento de hedges: hedges permitidos, em porcentagem das chamadas da instância no último `budgetWindow`, além de `budgetMinHedgesPerSecond`. `0` desativa o orçamento. |
| `defaults.hedge.budgetMinHedgesPerSecond` | `R4C_DEFAULTS_HEDGE_BUDGETMINHEDGESPERSECOND` | int | `1` | ≥ 0 | Orçamento de hedges: hedges permitidos por segundo de `budgetWindow`, seja qual for o tráfego. |
| `defaults.hedge.budgetWindow` | `R4C_DEFAULTS_HEDGE_BUDGETWINDOW` | duração | `10s` | 1s a 1h | Orçamento de hedges: até quanto tempo atrás chamadas e hedges são contados. |

## Circuit breaker :id=circuit-breaker

Atributos de `@CircuitBreaker`. Veja [Circuit breaker](../policies/circuit-breaker.md).

| Chave | Variável de ambiente | Tipo | Padrão | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.circuitBreaker.enabled` | `R4C_DEFAULTS_CIRCUITBREAKER_ENABLED` | boolean | `true` | `true`, `false` | `false`: o breaker começa `DISABLED`, deixa passar todas as chamadas e não registra nada. |
| `defaults.circuitBreaker.windowType` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWTYPE` | enum | `count` | `count`, `time` | `count`: as últimas `windowSize` chamadas. `time`: as chamadas do último `windowDuration` (lê o relógio a cada chamada). |
| `defaults.circuitBreaker.windowSize` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWSIZE` | int | `100` | ≥ 1 | Chamadas em uma janela `count`; uma janela `time` o ignora. |
| `defaults.circuitBreaker.windowDuration` | `R4C_DEFAULTS_CIRCUITBREAKER_WINDOWDURATION` | duração | `60s` | segundos inteiros, ≥ `1s` | Duração de uma janela `time`; uma janela `count` o ignora. |
| `defaults.circuitBreaker.minimumCalls` | `R4C_DEFAULTS_CIRCUITBREAKER_MINIMUMCALLS` | int | `100` | ≥ 1 | Chamadas na janela antes que as taxas sejam avaliadas (limitado a `windowSize` em uma janela por contagem). |
| `defaults.circuitBreaker.failureRateThreshold` | `R4C_DEFAULTS_CIRCUITBREAKER_FAILURERATETHRESHOLD` | decimal | `50` | > 0 e ≤ 100 | Taxa de falhas, em porcentagem, que abre o breaker. |
| `defaults.circuitBreaker.slowCallRateThreshold` | `R4C_DEFAULTS_CIRCUITBREAKER_SLOWCALLRATETHRESHOLD` | decimal | `100` | > 0 e ≤ 100 | Taxa de chamadas lentas, em porcentagem, que abre o breaker. |
| `defaults.circuitBreaker.slowCallDuration` | `R4C_DEFAULTS_CIRCUITBREAKER_SLOWCALLDURATION` | duração | `0` | ≥ 0; `0` = desligado | Chamadas mais longas que isso são lentas. As chamadas só são cronometradas quando está definido. |
| `defaults.circuitBreaker.waitInOpen` | `R4C_DEFAULTS_CIRCUITBREAKER_WAITINOPEN` | duração | `60s` | ≥ 0 | Quanto tempo o breaker fica aberto; a próxima chamada depois disso é um teste half-open. |
| `defaults.circuitBreaker.halfOpenCalls` | `R4C_DEFAULTS_CIRCUITBREAKER_HALFOPENCALLS` | int | `10` | ≥ 1 | Chamadas de teste que passam enquanto está half-open. |
| `defaults.circuitBreaker.maxWaitInHalfOpen` | `R4C_DEFAULTS_CIRCUITBREAKER_MAXWAITINHALFOPEN` | duração | `0` | ≥ 0; `0` = sem limite | Quanto tempo o breaker pode ficar half-open antes de abrir de novo se seus testes não terminaram. |
| `defaults.circuitBreaker.recordOn` | `R4C_DEFAULTS_CIRCUITBREAKER_RECORDON` | lista de classes | `java.lang.Throwable` | classes de exceção | Falhas registradas como falhas. Qualquer outra coisa conta como sucesso. |
| `defaults.circuitBreaker.ignoreOn` | `R4C_DEFAULTS_CIRCUITBREAKER_IGNOREON` | lista de classes | — | classes de exceção | Falhas registradas nem como sucesso nem como falha; vencem `recordOn` e um classifier. |

## Rate limiter :id=rate-limiter

Atributos de `@RateLimiter`. Veja [Rate limiter](../policies/rate-limiter.md).

| Chave | Variável de ambiente | Tipo | Padrão | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.rateLimiter.enabled` | `R4C_DEFAULTS_RATELIMITER_ENABLED` | boolean | `true` | `true`, `false` | `false` deixa passar todas as chamadas. |
| `defaults.rateLimiter.permits` | `R4C_DEFAULTS_RATELIMITER_PERMITS` | int | `50` | ≥ 1 | Chamadas por `period`, e a maior rajada. |
| `defaults.rateLimiter.period` | `R4C_DEFAULTS_RATELIMITER_PERIOD` | duração | `1s` | > 0 e ≥ `permits` ns | Duração do período; as permissões ficam espaçadas `period / permits` entre si. |
| `defaults.rateLimiter.maxWait` | `R4C_DEFAULTS_RATELIMITER_MAXWAIT` | duração | `0` | ≥ 0; `0` = rejeitar na hora | Quanto tempo uma chamada pode esperar por uma permissão antes de `RateLimitExceededException`. |

## Adaptive limit :id=adaptive-limit

Atributos de `@AdaptiveLimit`, mais o ajuste fino dos algoritmos, que não tem atributo de anotação (`minWindowSamples`, `smoothing`, `rttTolerance`, `longWindow`, `queueSize`, `backoffRatio`). Veja [Adaptive limit](../policies/adaptive-limit.md).

| Chave | Variável de ambiente | Tipo | Padrão | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.adaptiveLimit.enabled` | `R4C_DEFAULTS_ADAPTIVELIMIT_ENABLED` | boolean | `true` | `true`, `false` | `false` não limita as chamadas concorrentes. |
| `defaults.adaptiveLimit.algorithm` | `R4C_DEFAULTS_ADAPTIVELIMIT_ALGORITHM` | enum | `gradient2` | `gradient2`, `aimd` | `gradient2`: compara a latência de cada janela com uma média de longo prazo. `aimd`: uma vaga a mais depois de uma janela ocupada, multiplicado por `backoffRatio` depois de um drop. |
| `defaults.adaptiveLimit.initialLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_INITIALLIMIT` | int | `20` | `minLimit` a `maxLimit` | O limite antes que a primeira janela termine. |
| `defaults.adaptiveLimit.minLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_MINLIMIT` | int | `1` | ≥ 1 | O mais baixo que o limite chega. |
| `defaults.adaptiveLimit.maxLimit` | `R4C_DEFAULTS_ADAPTIVELIMIT_MAXLIMIT` | int | `200` | ≥ `minLimit` | O mais alto que o limite chega. |
| `defaults.adaptiveLimit.window` | `R4C_DEFAULTS_ADAPTIVELIMIT_WINDOW` | duração | `1s` | 10ms a 1h | Quanto tempo uma janela dura no mínimo; o limite é recalculado no seu fim. |
| `defaults.adaptiveLimit.minWindowSamples` | `R4C_DEFAULTS_ADAPTIVELIMIT_MINWINDOWSAMPLES` | int | `10` | ≥ 1 | Chamadas concluídas de que uma janela precisa antes de terminar; uma janela com menos é estendida por mais um `window`, mantendo suas chamadas. |
| `defaults.adaptiveLimit.dropOn` | `R4C_DEFAULTS_ADAPTIVELIMIT_DROPON` | lista de classes | `dev.resilience4cata.CallTimeoutException, dev.resilience4cata.BulkheadFullException, java.util.concurrent.TimeoutException` | classes de exceção | Falhas que significam que a dependência está sobrecarregada: amostras com drop (`aimd` recua). Qualquer outra falha não é amostra. |
| `defaults.adaptiveLimit.ignoreOn` | `R4C_DEFAULTS_ADAPTIVELIMIT_IGNOREON` | lista de classes | — | classes de exceção | Falhas que nunca são amostras; vencem `dropOn`. |
| `defaults.adaptiveLimit.smoothing` | `R4C_DEFAULTS_ADAPTIVELIMIT_SMOOTHING` | decimal | `0.2` | > 0 e ≤ 1 | `gradient2`: quanto de cada nova estimativa é mantido. |
| `defaults.adaptiveLimit.rttTolerance` | `R4C_DEFAULTS_ADAPTIVELIMIT_RTTTOLERANCE` | decimal | `1.5` | finito, ≥ 1 | `gradient2`: quantas vezes a latência de longo prazo uma janela pode atingir antes que o limite baixe. |
| `defaults.adaptiveLimit.longWindow` | `R4C_DEFAULTS_ADAPTIVELIMIT_LONGWINDOW` | int | `600` | ≥ 1 | `gradient2`: janelas incluídas na média da latência de longo prazo. |
| `defaults.adaptiveLimit.queueSize` | `R4C_DEFAULTS_ADAPTIVELIMIT_QUEUESIZE` | int | `4` | ≥ 0 | `gradient2`: folga que o limite deixa acima da concorrência estimada, para que possa crescer. A latência sozinha não leva o limite muito abaixo do dobro desse valor. |
| `defaults.adaptiveLimit.backoffRatio` | `R4C_DEFAULTS_ADAPTIVELIMIT_BACKOFFRATIO` | decimal | `0.9` | ≥ 0.5 e < 1 | `aimd`: por quanto uma janela com drop multiplica o limite. |

## Timeout :id=timeout

Atributos de `@Timeout`. O `value` da anotação é a chave `timeout.value`. `@Timeout` não tem `name`, então `instances.<id>.timeout.*` sempre usa o id derivado. Veja [Timeout](../policies/timeout.md).

| Chave | Variável de ambiente | Tipo | Padrão | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.timeout.enabled` | `R4C_DEFAULTS_TIMEOUT_ENABLED` | boolean | `true` | `true`, `false` | `false` nunca aplica timeout a uma chamada. |
| `defaults.timeout.value` | `R4C_DEFAULTS_TIMEOUT_VALUE` | duração | `1s` | > 0 | Quanto tempo uma tentativa pode levar (`@Timeout("2s")`; `TimeoutConfig.timeout()` no código). |

## Bulkhead :id=bulkhead

Atributos de `@Bulkhead`. Veja [Bulkhead](../policies/bulkhead.md).

| Chave | Variável de ambiente | Tipo | Padrão | Valores válidos | Significado |
|---|---|---|---|---|---|
| `defaults.bulkhead.enabled` | `R4C_DEFAULTS_BULKHEAD_ENABLED` | boolean | `true` | `true`, `false` | `false` não limita as chamadas concorrentes. |
| `defaults.bulkhead.maxConcurrent` | `R4C_DEFAULTS_BULKHEAD_MAXCONCURRENT` | int | `25` | ≥ 1 | Chamadas rodando ao mesmo tempo. |
| `defaults.bulkhead.maxWait` | `R4C_DEFAULTS_BULKHEAD_MAXWAIT` | duração | `0` | ≥ 0; `0` = rejeitar na hora | Quanto tempo uma chamada pode esperar por uma vaga livre antes de `BulkheadFullException`. |

## Erros :id=errors

Todos os problemas de uma configuração são reportados juntos, em uma única `ConfigException` com um código `R4C-CFG-nnn`, a chave, sua origem (arquivo e linha, variável de ambiente ou propriedade de sistema) e a chave válida mais próxima. Veja [códigos de erro](../errors.md#r4c-cfg-001).
