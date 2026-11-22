# Configuração

Com o módulo `resilience4cata-config`, os operadores reajustam o mesmo código compilado a partir de arquivos de properties, variáveis de ambiente e propriedades de sistema.

Adicione `implementation 'dev.resilience4cata:resilience4cata-config'` (Maven: escopo padrão) ao lado de `core`; veja a [instalação](../start/installation.md). Todas as chaves estão listadas na [referência de configuração](../reference/configuration.md).

## Um primeiro arquivo :id=a-first-file

Coloque um `resilience4cata.properties` no class path (por exemplo em `src/main/resources`). Este reajusta o cliente abaixo:

<!-- snippet: configuration-file -->
```properties
# Every retry that does not set these attributes itself
defaults.retry.delay=50ms
defaults.retry.jitter=none

# A named config, used by @Retry(config = "external") and @CircuitBreaker(config = "external")
configs.external.retry.maxAttempts=4
configs.external.circuitBreaker.minimumCalls=50
configs.external.circuitBreaker.waitInOpen=10s

# A named config that starts from another one
configs.batch.extends=external
configs.batch.timeout.value=30s

# One instance, by its name...
instances.shipping.circuitBreaker.failureRateThreshold=30
# ...or by its derived id, in double quotes
instances."dev.resilience4cata.examples.docs.configuration.ShippingClient#cancel(java.lang.String)".timeout.value=3s

# Applied only while the profile "test" is active
profiles.test.configs.external.retry.maxAttempts=1
```

<!-- snippet: configuration-client -->
```java
public interface ShippingClient {

    @Retry(config = "external")
    @CircuitBreaker(name = "shipping", config = "external", minimumCalls = 20)
    String track(String parcel);

    @Retry(maxAttempts = 2)
    @Timeout("1s")
    String cancel(String parcel);
}
```

Carregue-o uma vez, aplique-o ao builder do registry, e depois envolva seus serviços como de costume:

<!-- snippet: configuration-load -->
```java
ResilienceConfig config = ResilienceConfig.load();   // resilience4cata.properties, R4C_* variables, -Dr4c.* properties
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
ShippingClient shipping = Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
config.verifyInstances(registry);   // fails if an instances.<id> key matches no instance
```

Com este arquivo, `track` faz até 4 tentativas com 50 ms de intervalo (sem jitter), e seu breaker `shipping` abre com uma taxa de falhas de 30% após 20 chamadas (o `minimumCalls = 20` da anotação vence os 50 da config nomeada), e então espera 10 s. `cancel` faz 2 tentativas e estoura o timeout após 3 s em vez de 1 s da anotação.

`ResilienceConfig.load()` lê, nesta ordem, o recurso de class path `resilience4cata.properties` (se existir), o ambiente e as propriedades de sistema, com o context class loader da thread. `config.toRegistry()` é um atalho para `config.applyTo(ResilienceRegistry.builder()).build()`.

## Fontes :id=sources

Para escolher as fontes você mesmo, use o builder:

<!-- snippet: configuration-builder -->
```java
ResilienceConfig config = ResilienceConfig.builder()
        .optionalClasspath("resilience4cata.properties")
        .optionalFile(Path.of("/etc/shop/resilience4cata.properties"))   // skipped if missing
        .environment()                                                     // R4C_… variables
        .systemProperties()                                                // -Dr4c.… properties
        .build();
```

| Método | Lê |
|---|---|
| `classpath(name)` / `optionalClasspath(name)` | Um recurso do class path; a primeira forma falha ([`R4C-CFG-009`](../errors.md#r4c-cfg-009)) se ele não existir |
| `file(path)` / `optionalFile(path)` | Um arquivo; mesma regra |
| `properties(props)` / `properties(description, props)` | Um `java.util.Properties` que você construiu; a descrição aparece nas mensagens de erro |
| `properties(description, map, origins)` | Um mapa de chaves e valores, com onde cada chave foi definida (`origins`), mostrado nas mensagens de erro em vez da descrição. Para frameworks que rastreiam origens, como o Spring |
| `environment()` / `environment(map)` | Variáveis de ambiente (`R4C_…`), ou um mapa delas |
| `systemProperties()` / `systemProperties(props)` | Propriedades de sistema (`r4c.…`), ou um `Properties` usado como tal |
| `profiles(names...)` | Os perfis ativos; sobrescreve `profiles.active` |
| `classLoader(loader)` | O class loader para os recursos e para as classes de exceção nomeadas nos valores |
| `precedenceByOrder()` | Classifica as fontes pela ordem em que foram adicionadas, não pelo tipo (abaixo) |

Os arquivos usam a sintaxe `.properties`, lidos como UTF-8. Não há suporte a YAML nem recarga: a configuração é lida uma vez.

Quando a mesma chave vem de várias fontes, a fonte mais forte vence: **recursos do class path < arquivos e `Properties` < variáveis de ambiente < propriedades de sistema**. Entre duas fontes do mesmo tipo, vence a adicionada por último. Com `precedenceByOrder()`, vence a adicionada por último, qualquer que seja seu tipo: a [integração com Spring Boot](spring.md) a usa para manter a precedência do Spring. Dentro de uma fonte, uma chave sob um perfil ativo vence a mesma chave sem perfil; uma fonte mais forte continua vencendo, então uma variável de ambiente ou uma propriedade de sistema definida por um operador vence uma chave de perfil em um arquivo.

<!-- snippet: configuration-sources -->
```java
Properties system = new Properties();
system.setProperty("r4c.configs.external.retry.maxAttempts", "7");   // the r4c. prefix is required here

ResilienceConfig config = ResilienceConfig.builder()
        .classpath("resilience4cata.properties")                      // maxAttempts=4
        .environment(Map.of(
                "R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS", "6",
                "R4C_INSTANCES_SHIPPING_CIRCUIT_BREAKER_WAIT_IN_OPEN", "5s"))
        .systemProperties(system)
        .build();
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
// registry.retryConfig("external").maxAttempts() == 7: system properties > environment > files
```

## Chaves :id=keys

```properties
defaults.<policy>.<attribute>=…                 # registry-wide defaults
configs.<name>.<policy>.<attribute>=…           # a named config, used with config = "<name>"
configs.<name>.extends=<other>                  # start from every policy of another named config
instances.<id>.<policy>.<attribute>=…           # one instance, by its name or derived id
profiles.<profile>.<any key above>=…            # applies only while <profile> is active
profiles.active=<profile>,<profile>             # comma-separated; later profiles win
```

- **Políticas:** `retry`, `hedge`, `circuitBreaker`, `rateLimiter`, `adaptiveLimit`, `timeout`, `bulkhead`. Não há chaves `fallback`.
- **Atributos** são os da anotação, com os mesmos nomes (`maxAttempts`, `waitInOpen`, …). A duração do timeout é `timeout.value`. `name`, `config`, `classifier` e `onExhaustedResult` só podem ser definidos nas anotações.
- **Grafia:** camelCase e kebab-case são aceitos (`circuitBreaker.waitInOpen` = `circuit-breaker.wait-in-open`). Definir a mesma chave duas vezes em uma fonte, mesmo com duas grafias, é um erro ([`R4C-CFG-005`](../errors.md#r4c-cfg-005)).
- **Prefixo:** qualquer chave pode começar com `r4c.` ou `resilience4cata.`. Nas propriedades de sistema o prefixo é **obrigatório**, e as propriedades de sistema sem ele são ignoradas.
- **Nomes** de configs e perfis: letras minúsculas e dígitos separados por hífens simples (`slow-external`). `default` é reservado para configs e `active` para perfis ([`R4C-CFG-004`](../errors.md#r4c-cfg-004)).
- **`extends`** só pode nomear uma config definida na configuração externa, e não pode formar um ciclo ([`R4C-CFG-007`](../errors.md#r4c-cfg-007)). A cadeia é aplicada a partir da raiz.
- **Ids de instância** são o `name` da política, ou o derivado `<type>#<method>(<parameter types>)` ([como os ids são construídos](../start/how-it-works.md#the-registry-and-instance-ids)). Diferenciam maiúsculas de minúsculas. Um id com algo além de letras, dígitos, `_` e `-` vai entre aspas duplas: `instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts=5` ([`R4C-CFG-012`](../errors.md#r4c-cfg-012)).

### Valores :id=values

| Tipo | Sintaxe | Exemplos |
|---|---|---|
| Duração | dígitos mais `ns`, `us`, `ms`, `s`, `m`, `h` ou `d`; uma duração ISO-8601; ou `0`. Sem decimais, sinais ou espaços | `250ms`, `2s`, `PT2S`, `0` |
| Inteiro, decimal | Números simples | `5`, `25`, `2.5` |
| Booleano | `true` ou `false` | `retry.enabled=false` |
| Enum | Constante, sem diferenciar maiúsculas de minúsculas | `exponential`, `full`, `time` |
| Lista de exceções | Nomes binários de classes separados por vírgula, carregados com o class loader configurado | `java.io.IOException,java.util.concurrent.TimeoutException` |

A gramática de durações é a mesma das anotações.

## Variáveis de ambiente :id=environment-variables

Pegue a chave, converta-a para maiúsculas, substitua todo caractere que não seja letra ou dígito por `_`, e adicione o prefixo `R4C_` (ou `RESILIENCE4CATA_`):

| Chave | Variável de ambiente |
|---|---|
| `configs.external.retry.maxAttempts` | `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS` |
| `configs.slow-external.timeout.value` | `R4C_CONFIGS_SLOW_EXTERNAL_TIMEOUT_VALUE` |
| `instances.shipping.circuitBreaker.waitInOpen` | `R4C_INSTANCES_SHIPPING_CIRCUITBREAKER_WAITINOPEN` ou `R4C_INSTANCES_SHIPPING_CIRCUIT_BREAKER_WAIT_IN_OPEN` |
| `profiles.active` | `R4C_PROFILES_ACTIVE` |

As palavras dos nomes de políticas e atributos podem vir juntas ou separadas por `_`. Uma variável deve corresponder a exatamente uma chave; quando `_` pode representar tanto `.` quanto `-` e duas chaves servem, é um erro ([`R4C-CFG-010`](../errors.md#r4c-cfg-010)): defina essa chave em um arquivo ou em uma propriedade de sistema. As instâncias só podem ser endereçadas a partir do ambiente quando seu nome é formado por letras minúsculas, dígitos e hífens (`R4C_INSTANCES_PAYMENTS_API_RETRY_MAXATTEMPTS` define `instances.payments-api.retry.maxAttempts`); os ids derivados precisam de um arquivo ou de uma propriedade de sistema como `-Dr4c.instances."com.acme.Api#get(java.lang.String)".retry.maxAttempts=5`.

## Perfis :id=profiles

Um perfil agrupa chaves que se aplicam apenas em alguns ambientes. As chaves sob `profiles.<profile>.` substituem as mesmas chaves sem o perfil; elas não adicionam um nível de precedência próprio.

<!-- snippet: configuration-profiles -->
```java
ResilienceConfig config = ResilienceConfig.builder()
        .classpath("resilience4cata.properties")
        .profiles("test")                         // or profiles.active=test, or R4C_PROFILES_ACTIVE=test
        .build();
// config.activeProfiles() == [test]; configs.external.retry.maxAttempts is now 1
```

Vários perfis podem estar ativos: `profiles.active=test,local`. Quando dois perfis ativos definem a mesma chave, vence o posterior.

## Precedência :id=precedence

Da menor para a maior, para cada atributo de cada instância:

1. Os padrões embutidos (cada [página de política](../policies/README.md) os lista).
2. `defaults.<policy>.*`, ou `retryDefaults(...)` e afins no código.
3. A config nomeada que a anotação escolhe com `config = "…"`: primeiro o `retry("<name>", ...)` do código, depois `configs.<name>.*`, atributo por atributo (a cadeia de `extends` a partir da raiz).
4. Os próprios atributos da anotação.
5. `instances.<id>.*`, ou `retryInstance(id, ...)` e afins no código, exceto para os atributos de uma anotação com `locked = true` ([abaixo](#locking-an-instance)).

Assim, **uma anotação vence uma config nomeada**, e **uma chave de instância vence a anotação**: os operadores podem reajustar um método sem recompilar. As sobrescritas por instância se aplicam uma vez, quando a instância é criada.

O código e a configuração externa se combinam assim:

- Uma config nomeada definida nos dois é mesclada, com os valores externos vencendo atributo por atributo, qualquer que seja a ordem das chamadas (`applyTo` as registra com `retryOverlay(...)` e afins, que você também pode chamar):

  <!-- snippet: configuration-merge -->
  ```java
  ResilienceRegistry registry = ResilienceConfig.load()
          .applyTo(ResilienceRegistry.builder()
                  .retry("external", r -> r.maxAttempts(9).maxDuration(Duration.ofSeconds(5))))
          .build();
  // registry.retryConfig("external"): maxAttempts 4 (the file wins), maxDuration 5s (only the code sets it)
  ```

- `retryInstance(...)` e as outras chamadas `*Instance` feitas no builder depois de `applyTo` vencem as chaves externas `instances.*`.
- `retryDefaults(...)` e as outras chamadas `*Defaults` feitas depois de `applyTo` **substituem** os padrões externos dessa política em vez de se mesclar com eles.

## Travando uma instância :id=locking-an-instance

Para manter um valor que os operadores não devem mudar, adicione `locked = true` à anotação. Os atributos que a anotação define (diretamente, em nível de tipo ou através de um [bundle](bundles.md)) então vencem as chaves `instances.<id>.*` e as chamadas `retryInstance(...)`; os atributos que ela deixa sem definir ainda podem ser sobrescritos:

<!-- snippet: configuration-locked-client -->
```java
public interface PaymentsClient {

    @CircuitBreaker(name = "payments", failureRateThreshold = 30, locked = true)
    String charge(String order);
}
```

<!-- snippet: configuration-locked -->
```java
Properties ops = new Properties();
ops.setProperty("instances.payments.circuitBreaker.failureRateThreshold", "80");   // locked: ignored
ops.setProperty("instances.payments.circuitBreaker.waitInOpen", "20s");            // not in the annotation: applied
ResilienceConfig config = ResilienceConfig.builder().properties("ops.properties", ops).build();
ResilienceRegistry registry = config.toRegistry();
PaymentsClient payments = Resilient.of(PaymentsClient.class, order -> "paid", registry);
// registry.circuitBreaker("payments").config(): failureRateThreshold 30, waitInOpen 20s
assertThatThrownBy(() -> config.verifyInstances(registry))
        .hasMessageContaining("[R4C-CFG-014] 'instances.payments.circuitBreaker.failureRateThreshold'");
```

Uma sobrescrita ignorada não é silenciosa: o registry registra um aviso no log (`System.Logger`, nome `dev.resilience4cata.ResilienceRegistry`) quando cria a instância, `config.verifyInstances(registry)` reporta cada chave ignorada com sua fonte ([`R4C-CFG-014`](../errors.md#r4c-cfg-014)), e `registry.lockedOverrides()` lista as instâncias afetadas. Um método pode destravar o que seu tipo trava com `locked = false`. As configs nomeadas e os padrões não são afetados: uma anotação já vence sobre eles. Para um [pipeline](pipeline.md), `Pipeline.Builder.locked()` trava os valores definidos em seus passos.

## Validação e erros :id=validation-and-errors

`build()` valida tudo contra os padrões embutidos: chaves desconhecidas, valores que não podem ser interpretados, classes de exceção desconhecidas, `extends` inválidos, nomes inválidos e combinações que não podem funcionar juntas (por exemplo um `maxDelay` mais curto que `delay`). Cada problema é reunido em um único `ConfigException` (um `IllegalArgumentException`, com a lista em `problems()`), cada um com seu código, sua chave, sua fonte (arquivo e linha, variável de ambiente ou propriedade de sistema) e a chave válida mais próxima. Este arquivo tem três erros:

```properties
configs.external.circuit-breaker.wait-in-opne=10s
defaults.retry.delay=fast
configs.batch.extends=extrenal
```

<!-- snippet: configuration-errors -->
```java
assertThatThrownBy(() -> ResilienceConfig.builder().classpath(BROKEN).build())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("[R4C-CFG-001]")
        .hasMessageContaining("Did you mean 'configs.external.circuit-breaker.wait-in-open'?")
        .hasMessageContaining("[R4C-CFG-002]")
        .hasMessageContaining("[R4C-CFG-007]");
```

A mensagem, resumida:

```text
Invalid resilience4cata configuration (3 problems):
  [R4C-CFG-001] unknown key 'configs.external.circuit-breaker.wait-in-opne' (classpath:…/broken.properties, line 1). Did you mean 'configs.external.circuit-breaker.wait-in-open'? …
  [R4C-CFG-002] 'defaults.retry.delay' (classpath:…/broken.properties, line 2) = "fast" is not a valid duration: …
  [R4C-CFG-007] 'configs.batch.extends' (classpath:…/broken.properties, line 3) extends config 'extrenal', which is not defined in external configuration …
```

As instâncias só existem depois que os serviços são envolvidos, então `build()` não tem como saber se uma chave `instances.<id>` nomeia uma instância real. Chame `config.verifyInstances(registry)` depois de envolver seus serviços: ele falha com [`R4C-CFG-013`](../errors.md#r4c-cfg-013) e o id mais próximo para cada sobrescrita que não corresponde a nenhuma instância. As combinações que envolvem tanto uma chave de instância quanto os valores da anotação (por exemplo um `delay` de instância mais longo que o `maxDelay` da anotação) são verificadas quando a instância é criada ([`R4C-REG-003`](../errors.md#r4c-reg-003)).

Cada código `R4C-CFG` é explicado na [referência de erros](../errors.md#runtime-external-configuration-r4c-cfg).

## Inspecionando o resultado :id=inspecting-the-result

`config.describe()` lista cada valor efetivo e de onde ele veio, um por linha:

```text
active profiles: []
defaults.retry.delay = 50ms  <- 'defaults.retry.delay' (classpath:resilience4cata.properties, line 4)
configs.batch.retry.maxAttempts = 4  <- 'configs.external.retry.maxAttempts' (classpath:resilience4cata.properties, line 8)
instances.shipping.circuitBreaker.failureRateThreshold = 30  <- 'instances.shipping.circuitBreaker.failureRateThreshold' (classpath:resilience4cata.properties, line 17)
…
```

`activeProfiles()`, `configNames()` e `instanceIds()` retornam as partes uma a uma. Depois que os serviços são envolvidos, `registry.snapshot()` mostra a configuração resolvida de cada instância ([observabilidade](observability.md)).

## Limites :id=limits

- Sem hot reload e sem `reconfigure()`: a configuração é imutável depois que o registry é construído.
- Sem YAML ou outros formatos; leia-os você mesmo em um `Properties` e passe-o com `properties(description, props)`.
- `@Fallback`, os classificadores, `name` e `config` não são configuráveis externamente.
- `instances.<id>.*` vence a anotação, a menos que a anotação defina `locked = true` ([travando uma instância](#locking-an-instance)).
