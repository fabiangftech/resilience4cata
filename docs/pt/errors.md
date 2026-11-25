# Referência de erros

Todo erro que o resilience4cata reporta começa com um código estável entre colchetes, por exemplo:

```text
error: [R4C-FB-002] @Fallback(method = "findFallbak") on find: no method named findFallbak in UserClient. Did you mean findFallback? Expected a default or static method java.lang.String findFallbak(java.lang.String, <a Throwable type> e), where the exception parameter is optional.
```

O código indica qual seção desta página explica o erro. O resto da mensagem diz onde (o compilador
aponta para o elemento anotado; classe, método e atributo são nomeados no texto), o que está errado
e como corrigir.

A página é longa: procure nela pelo código (a busca do site, ou a busca do navegador), ou comece pela área na tabela abaixo. Cada código tem sua própria seção, então um link como `errors.md#r4c-atr-001` a abre diretamente.

| Prefixo | Reportado por | Quando |
|---|---|---|
| `R4C-APT` | annotation processor | posicionamento das anotações, problemas gerais |
| `R4C-ATR` | annotation processor | valores de atributos |
| `R4C-CMP` | annotation processor | anotações compostas (bundles `@ResiliencePolicy`) |
| `R4C-ASY` | annotation processor | métodos assíncronos |
| `R4C-HDG` | annotation processor | `@Hedge` |
| `R4C-CLS` | annotation processor | classes `classifier` e `onExhaustedResult` |
| `R4C-FB` | annotation processor | `@Fallback` |
| `R4C-SUB` | annotation processor | classes anotadas (subclasses geradas) |
| `R4C-ORD` | annotation processor | `@PolicyOrder` |
| `R4C-RT` | `resilience4cata-core` em tempo de execução | `Resilient.of`, `Resilient.create`, `Pipeline.Builder` |
| `R4C-REG` | `resilience4cata-core` em tempo de execução | builders `*Config`, `ResilienceRegistry` e seu builder: configurações nomeadas, instâncias |
| `R4C-CFG` | `resilience4cata-config` em tempo de execução | configuração externa (`ResilienceConfig`) |
| `R4C-SPR` | `resilience4cata-spring` na inicialização | a integração com Spring Boot: beans que não podem ser protegidos como declarados |
| `R4C-MN` | `resilience4cata-micronaut` na inicialização ou na criação do bean | a integração com Micronaut: beans que não podem ser protegidos como declarados |
| `R4C-QK` | `resilience4cata-quarkus` enquanto o Quarkus constrói a aplicação | a extensão Quarkus: beans que não podem ser protegidos como declarados, ou que seriam protegidos duas vezes |
| `R4C-TK` | `resilience4cata-testkit` nos testes | `ManualClock`, `ResilienceTesting` |
| `R4C-JU` | `resilience4cata-junit` nos testes | a extensão JUnit (`@ResilienceTest`) |

Os códigos são estáveis: um código nunca é renumerado nem ganha um novo significado. Um erro novo recebe o próximo
número livre da sua área; um erro que deixou de ser reportado mantém seu código, marcado como aposentado abaixo.

Os exemplos Java errados e corrigidos dos códigos do annotation processor são compilados pelo build
(`ErrorCodeTest` no módulo `processor`): cada exemplo errado reporta seu código e cada correção compila
sem avisos.

## Annotation processor: posicionamento (R4C-APT) :id=annotation-processor-placement-r4c-apt

### R4C-APT-001

**Causa:** uma anotação de resiliência num enum ou num record, ou num dos seus métodos. O estilo wrapper de
interface precisa de uma interface, e o estilo de subclasse precisa estender o tipo, mas enums e records são final.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public record PriceClient(String baseUrl) {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Correção:** declare os métodos numa interface, implemente-a com o record e envolva a implementação com
`Resilient.of(PriceClient.class, implementation, registry)`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    String price(String sku);
}
```

### R4C-APT-002

**Causa:** uma anotação de resiliência num método static ou private. As chamadas a esses métodos não podem
ser interceptadas por um wrapper nem por uma subclasse.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    static String price(String sku) {
        return "1";
    }
}
```

**Correção:** anote um método de instância não privado (abstract ou default numa interface; sobrescrevível
numa classe).

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    String price(String sku);
}
```

### R4C-APT-003

**Causa:** a interface anotada é private (um membro private de uma classe). A classe gerada
`<Interface>$Resilient` é uma classe de nível superior e precisa implementá-la.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Clients {
    private interface PriceClient {
        @Retry
        String price(String sku);
    }
}
```

**Correção:** torne a interface package-private ou public.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry
        String price(String sku);
    }
}
```

### R4C-APT-004

**Causa:** uma anotação tem um atributo que o processor não conhece, por exemplo
`Unknown attribute @Retry.hedges`. Isso acontece quando `resilience4cata-annotations` é mais novo que
`resilience4cata-processor`.

**Correção:** use a mesma versão para todos os artefatos do resilience4cata, de preferência por meio do BOM:

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:<version>')
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    implementation 'dev.resilience4cata:resilience4cata-core'
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:<version>')
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

### R4C-APT-005

**Causa:** o processor não conseguiu escrever um arquivo-fonte gerado, ou o arquivo de metadados de native-image que
ele escreve ao lado (`META-INF/native-image/dev.resilience4cata.generated/<Interface>$Resilient/reachability-metadata.json`
na saída de classes): o `Filer` do compilador falhou, por exemplo porque o diretório de saída não tem permissão
de escrita, ou porque outro processor ou um arquivo-fonte já define `<Interface>$Resilient`.

**Correção:** verifique o diretório de fontes gerados do build (com Gradle,
`build/generated/sources/annotationProcessor`), certifique-se de que nenhuma classe escrita à mão se chame
`<Interface>$Resilient`, e reconstrua depois de `./gradlew clean`.

### R4C-APT-006

**Causa:** a opção de compilador `-Ar4c.inject` tem um valor diferente de `micronaut`; ou é
`-Ar4c.inject=micronaut`, mas o `io.micronaut.context.annotation.Replaces` do Micronaut não está no class path de
compilação, então a subclasse gerada de uma classe anotada não pode substituir a classe como bean do Micronaut. A opção é
lida uma vez por compilação; o erro não tem posição no código-fonte.

**Errado** (compilado com `-Ar4c.inject=spring`):

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class InventoryService {
    @Retry
    public int stock(String sku) {
        return 1;
    }
}
```

**Correção:** use `-Ar4c.inject=micronaut` numa aplicação Micronaut, com `io.micronaut:micronaut-inject` no
class path de compilação (toda aplicação Micronaut o tem); remova a opção para qualquer outro contêiner (o Spring Boot
não precisa de nenhuma: veja o [guia do Spring Boot](guides/spring.md)). Veja o [guia do Micronaut](guides/micronaut.md#classes).

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.add('-Ar4c.inject=micronaut')
}
```

### R4C-APT-007

**Causa:** um tipo tem anotações de resiliência em nível de tipo, mas elas não chegam a nenhum método, então nada seria
gerado. Uma interface que não declara nem herda nenhum método abstract ou default (as anotações em nível de tipo não
chegam aos métodos das interfaces que a estendem), ou uma classe anotada cujos métodos são todos final, static ou
private. Sem este erro as anotações não fariam nada, e as integrações com Spring Boot, Micronaut e Quarkus
reportariam o tipo como compilado sem o processor ([R4C-SPR-006](#r4c-spr-006)).

**Errado:**

```java
// com/acme/RemoteDefaults.java
package com.acme;

import dev.resilience4cata.annotation.Retry;

@Retry(maxAttempts = 3)
public interface RemoteDefaults {
}

// com/acme/PriceClient.java
package com.acme;

public interface PriceClient extends RemoteDefaults {
    String price(String sku);
}
```

**Correção:** coloque as anotações na interface que declara os métodos, ou compartilhe-as com um
[bundle `@ResiliencePolicy`](guides/bundles.md) usado ali. Para uma classe, torne sobrescrevíveis os métodos a proteger.

```java
// com/acme/PriceClient.java
package com.acme;

import dev.resilience4cata.annotation.Retry;

@Retry(maxAttempts = 3)
public interface PriceClient {
    String price(String sku);
}
```

## Annotation processor: valores de atributos (R4C-ATR) :id=annotation-processor-attribute-values-r4c-atr

### R4C-ATR-001

**Causa:** um atributo de duração (`delay`, `maxDelay`, `maxDuration`, `slowCallDuration`, `waitInOpen`,
`maxWaitInHalfOpen`, `maxWait`, `period`, `budgetWindow`, `window`, ou o valor de `@Timeout`) não é uma
duração válida.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(delay = "2 seconds")
    String price(String sku);
}
```

**Correção:** use um número seguido de `ns`, `us`, `ms`, `s`, `m`, `h` ou `d` (`"250ms"`, `"2s"`), uma duração
ISO-8601 (`"PT2S"`), ou `"0"`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(delay = "2s")
    String price(String sku);
}
```

### R4C-ATR-002

**Causa:** uma duração que deve ser maior que zero é `"0"`: o valor de `@Timeout`, ou
`@RateLimiter.period`.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Timeout;

public interface PriceClient {
    @Timeout("0")
    String price(String sku);
}
```

**Correção:** defina uma duração positiva. Para desligar a policy, use `enabled = false` (ou configuração)
em vez disso.

```java
package com.acme;

import dev.resilience4cata.annotation.Timeout;

public interface PriceClient {
    @Timeout("2s")
    String price(String sku);
}
```

### R4C-ATR-003

**Causa:** uma contagem é menor que 1: `maxAttempts`, `windowSize`, `minimumCalls`, `halfOpenCalls`,
`maxConcurrent`, `permits`, `initialLimit`, `minLimit` ou `maxLimit`.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(maxAttempts = 0)
    String price(String sku);
}
```

**Correção:** defina 1 ou mais (`maxAttempts` conta a primeira chamada), ou omita-o para usar o valor
configurado.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(maxAttempts = 3)
    String price(String sku);
}
```

### R4C-ATR-004

**Causa:** `@Retry.multiplier` é menor que 1, infinito ou NaN.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(multiplier = 0.5)
    String price(String sku);
}
```

**Correção:** use um número finito de pelo menos 1 (1 mantém o atraso constante).

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(multiplier = 2.0)
    String price(String sku);
}
```

### R4C-ATR-005

**Causa:** `@CircuitBreaker.failureRateThreshold` ou `slowCallRateThreshold` não é uma porcentagem maior
que 0 e no máximo 100.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;

public interface PriceClient {
    @CircuitBreaker(failureRateThreshold = 150)
    String price(String sku);
}
```

**Correção:** use um valor em (0, 100].

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;

public interface PriceClient {
    @CircuitBreaker(failureRateThreshold = 50)
    String price(String sku);
}
```

### R4C-ATR-006

**Causa:** aposentado; não é mais reportado. `enabled` era uma string (`""`, `"true"` ou `"false"`); desde a 0.12.0 é
um `boolean` (`enabled = false`), que o compilador verifica.

### R4C-ATR-007

**Causa:** um atributo de budget de retry ou de hedge está fora do intervalo: `budgetPercent` é negativo, infinito ou NaN,
`budgetMinRetriesPerSecond` (`@Retry`) ou `budgetMinHedgesPerSecond` (`@Hedge`) é negativo, ou `budgetWindow`
é menor que 1 s ou maior que 1 h. (Um `budgetWindow` que nem sequer é uma duração é
[R4C-ATR-001](#r4c-atr-001).)

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(budgetPercent = 20, budgetWindow = "500ms")
    String price(String sku);
}
```

**Correção:** use uma porcentagem de 0 ou mais, uma reserva de 0 ou mais retries por segundo, e uma janela entre
`"1s"` e `"1h"`; ou omita o atributo para herdá-lo (`budgetWindow` tem `10s` por padrão).

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(budgetPercent = 20, budgetMinRetriesPerSecond = 1, budgetWindow = "10s")
    String price(String sku);
}
```

### R4C-ATR-008

**Causa:** `@Hedge(maxAttempts = …)` está abaixo de 2 ou acima de 10. `maxAttempts` conta todas as tentativas, a
primeira incluída, então 1 nunca faria hedge; mais de 10 tentativas em andamento para uma chamada é recusado.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(maxAttempts = 1, delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

**Correção:** use um valor de 2 (um hedge, o padrão) a 10, ou remova `@Hedge` se a chamada não deve ter hedge.

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(maxAttempts = 2, delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

### R4C-ATR-009

**Causa:** os limites de `@AdaptiveLimit` estão fora de ordem (`minLimit` acima de `maxLimit`, ou `initialLimit`
fora deles), ou sua `window` é menor que 10 ms ou maior que 1 h. Só os limites definidos na anotação
são comparados; um limite que vem de uma configuração nomeada ou externa é verificado quando o registry o
resolve ([R4C-REG-003](#r4c-reg-003)).

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.AdaptiveLimit;

public interface OrderClient {
    @AdaptiveLimit(minLimit = 50, maxLimit = 20)
    String place(String order);
}
```

**Correção:** mantenha `minLimit <= initialLimit <= maxLimit`, e uma `window` de `"10ms"` a `"1h"`.

```java
package com.acme;

import dev.resilience4cata.annotation.AdaptiveLimit;

public interface OrderClient {
    @AdaptiveLimit(minLimit = 20, maxLimit = 50)
    String place(String order);
}
```

### R4C-ATR-010

**Causa:** um `@CircuitBreaker` cujos atributos de janela não combinam com seu tipo de janela: `windowSize` (um número de
chamadas) com `windowType = TIME`, `windowDuration` com `windowType = COUNT`, os dois na mesma anotação, ou um
`windowDuration` que não é um número inteiro de segundos de pelo menos `1s`. Configurações e builders podem definir
os dois, já que os padrões servem aos dois tipos de janela: cada janela ignora o outro atributo.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.WindowType;

public interface LedgerClient {
    @CircuitBreaker(windowType = WindowType.TIME, windowSize = 60)
    long balance(String account);
}
```

**Correção:** dê a uma janela por tempo sua duração com `windowDuration`, e a uma janela por contagem suas chamadas com `windowSize`.

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.WindowType;

public interface LedgerClient {
    @CircuitBreaker(windowType = WindowType.TIME, windowDuration = "60s")
    long balance(String account);
}
```

## Annotation processor: anotações compostas (R4C-CMP) :id=annotation-processor-composed-annotations-r4c-cmp

### R4C-CMP-001

**Causa:** um tipo de anotação traz uma anotação de resiliência mas não está marcado com `@ResiliencePolicy`,
então o processor ignoraria suas policies onde quer que ele fosse usado.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Patient {
}
```

**Correção:** adicione `@ResiliencePolicy`.

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Patient {
}
```

### R4C-CMP-002

**Causa:** duas anotações compostas no mesmo elemento definem o mesmo atributo com valores diferentes, então
nenhuma pode vencer.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public interface PriceClient {
    @Fast
    @Patient
    String price(String sku);
}

@ResiliencePolicy
@Retry(maxAttempts = 2)
@Retention(RetentionPolicy.CLASS)
@interface Fast {
}

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@interface Patient {
}
```

**Correção:** mantenha só um dos bundles, ou faça-os concordar, ou defina o atributo diretamente no elemento,
que vence todos os bundles.

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public interface PriceClient {
    @Patient
    String price(String sku);
}

@ResiliencePolicy
@Retry(maxAttempts = 2)
@Retention(RetentionPolicy.CLASS)
@interface Fast {
}

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@interface Patient {
}
```

## Annotation processor: métodos assíncronos (R4C-ASY) :id=annotation-processor-asynchronous-methods-r4c-asy

### R4C-ASY-001

**Causa:** um método anotado retorna um tipo `CompletionStage` que não é suportado: um tipo raw, um
argumento `? super X`, ou um subtipo de `CompletionStage` diferente de `CompletableFuture`.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<? super Integer> price(String sku);
}
```

**Correção:** retorne `CompletionStage<T>` ou `CompletableFuture<T>`, onde `T` também pode ser `?` ou `? extends X`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<Integer> price(String sku);
}
```

### R4C-ASY-002

**Causa:** um método anotado retorna um tipo reativo lazy: um `Flow.Publisher` ou um `Publisher` de Reactive Streams
(o `Flowable` do RxJava, uma subclasse do `Flux` do Reactor), o `Uni` ou o `Multi` do Mutiny, ou o `Single`, `Maybe`,
`Completable` ou `Observable` do RxJava. Um método assim só constrói uma pipeline que executa depois, quando recebe uma inscrição, então as
policies protegeriam a construção dela, não o trabalho: uma falha nunca teria retry nem timeout. O `Mono` e o
`Flux` do Reactor são suportados com `resilience4cata-reactor` (veja [R4C-ASY-003](#r4c-asy-003)).

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.Flow;

public interface PriceClient {
    @Retry
    Flow.Publisher<Integer> price(String sku);
}
```

**Correção:** retorne `CompletionStage<T>` ou `CompletableFuture<T>`, convertendo na implementação
(`uni.subscribeAsCompletionStage()`), ou o `Mono<T>` ou `Flux<T>` do Reactor com `resilience4cata-reactor`, ou mantenha o
tipo reativo e use os próprios operadores de retry e timeout da biblioteca reativa.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<Integer> price(String sku);
}
```

### R4C-ASY-003

**Causa:** um método anotado retorna o `Mono` ou o `Flux` do Reactor, e `resilience4cata-reactor` não está no
class path de compilação (para um `Flux`, versão 0.11.0 ou mais nova). O código gerado desses métodos chama esse módulo.

**Errado:** com `reactor-core` mas sem `resilience4cata-reactor`:

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Mono;

public interface PriceClient {
    @Retry
    Mono<Integer> price(String sku);
}
```

**Correção:** adicione `dev.resilience4cata:resilience4cata-reactor` (mesma versão dos outros módulos) às dependências,
ao lado de `resilience4cata-core`. O código continua igual.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Mono;

public interface PriceClient {
    @Retry
    Mono<Integer> price(String sku);
}
```

### R4C-ASY-004

**Causa:** um método anotado que retorna o `Flux` do Reactor tem um `@Hedge`, um classifier em `@Retry` ou
`@CircuitBreaker`, ou um `@PolicyOrder` que muda sua ordem. Um hedge misturaria os elementos de dois streams; um
classifier julga um resultado, e um stream tem muitos; e a regra de que o retry e o fallback só agem antes do
primeiro elemento do stream é definida para a ordem padrão.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import reactor.core.publisher.Flux;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    Flux<Integer> quotes(String sku);
}
```

**Correção:** use `@Retry` (com `retryOn`/`abortOn`, ou `recordOn`/`ignoreOn` no breaker, em vez de um classifier),
mantenha a ordem padrão, ou retorne um `Mono` ou um `CompletionStage` onde você precisar do hedge ou do classifier.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Flux;

public interface QuoteClient {
    @Retry
    Flux<Integer> quotes(String sku);
}
```

## Annotation processor: hedges (R4C-HDG)

### R4C-HDG-001

**Causa:** `@Hedge` se aplica a um método síncrono: um que não retorna `CompletionStage<T>` nem
`CompletableFuture<T>`. Um hedge inicia a chamada de novo enquanto a primeira tentativa ainda está executando, o que exige uma
chamada assíncrona. Um `@Hedge` no tipo, ou num bundle `@ResiliencePolicy`, se aplica a todos os métodos, então cada
método síncrono do tipo é reportado.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    String quote(String sku);
}
```

**Correção:** retorne um `CompletableFuture` (ou `CompletionStage`), ou remova `@Hedge` do método. Para um `@Hedge` no tipo, anote os métodos assíncronos em vez do tipo.

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

### R4C-HDG-002

**Causa:** `@Hedge` e `@Retry` se aplicam ao mesmo método, diretamente, no tipo, ou por meio de
bundles `@ResiliencePolicy`. Eles ocupam o mesmo lugar na ordem das policies, e um retry em volta de um hedge
multiplicaria as tentativas.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    @Retry(maxAttempts = 3)
    CompletableFuture<String> quote(String sku);
}
```

**Correção:** mantenha um. Um hedge já inicia uma nova tentativa imediatamente quando uma falha com uma falha em `hedgeOn`, então
`maxAttempts` no hedge cobre o retry simples.

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms", maxAttempts = 3)
    CompletableFuture<String> quote(String sku);
}
```

## Annotation processor: ordem das policies (R4C-ORD) :id=annotation-processor-policy-order-r4c-ord

### R4C-ORD-001

**Causa:** `@PolicyOrder` lista uma camada mais de uma vez (no método, no seu tipo ou num bundle). Cada camada tem
um lugar na ordem.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportClient {
    @Retry
    @Timeout("2s")
    @PolicyOrder({Layer.TIMEOUT, Layer.RETRY, Layer.TIMEOUT})
    String report(String id);
}
```

**Correção:** liste cada camada uma vez, da mais externa para a mais interna.

```java
package com.acme;

import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportClient {
    @Retry
    @Timeout("2s")
    @PolicyOrder({Layer.TIMEOUT, Layer.RETRY})
    String report(String id);
}
```

## Annotation processor: classifiers (R4C-CLS)

A classe gerada cria uma instância de cada classe `classifier` por método, então a classe precisa ser uma
classe concreta, não genérica, com um construtor sem argumentos acessível, que implemente `Classifier<T>` para o
tipo de resultado do método.

### R4C-CLS-001

**Causa:** `classifier` está definido, mas `dev.resilience4cata.Classifier` não está no classpath de compilação.

**Errado:** compilar isto sem `resilience4cata-core` no classpath:

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Correção:** adicione a dependência do core:

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

### R4C-CLS-002

**Causa:** `classifier` nomeia uma interface, uma anotação ou um enum em vez de uma classe.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Classifier.class)
    String price(String sku);
}
```

**Correção:** nomeie uma classe que implemente `Classifier<T>`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-003

**Causa:** a classe do classifier é abstract, então não pode ser instanciada.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    abstract class Retryable implements Classifier<String> {
    }
}
```

**Correção:** use uma classe concreta.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-004

**Causa:** a classe do classifier declara parâmetros de tipo; o código gerado só poderia criá-la com um
tipo raw não verificado.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable<T> implements Classifier<T> {
        @Override
        public Outcome classify(T result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Correção:** implemente `Classifier<T>` com um `T` concreto (ou um supertipo, como `Classifier<Object>`).

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<Object> {
        @Override
        public Outcome classify(Object result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-005

**Causa:** a classe do classifier (ou uma classe em que ela está aninhada) é private, ou package-private em outro
pacote, então a classe gerada, no pacote da interface, não pode criá-la.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    private static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Correção:** torne-a public, ou package-private no pacote da interface.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-006

**Causa:** o classifier é uma classe interna (aninhada não static), que precisa de uma instância da sua classe
envolvente.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Correção:** torne-a `static`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-007

**Causa:** o classifier não tem um construtor sem argumentos que a classe gerada possa chamar.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        private Retryable() {
        }

        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Correção:** adicione um construtor sem argumentos public (ou, no pacote da interface, package-private), ou remova
o private.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-008

**Causa:** a classe do classifier não implementa `dev.resilience4cata.Classifier`.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = String.class)
    String price(String sku);
}
```

**Correção:** nomeie uma classe que implemente `Classifier<T>`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-009

**Causa:** o classifier implementa o tipo raw `Classifier`, então seu tipo de resultado não pode ser verificado.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier {
        @Override
        public Outcome classify(Object result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Correção:** implemente `Classifier<T>` com o tipo de resultado do método, como a mensagem sugere.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-010

**Causa:** o classifier implementa `Classifier<X>`, mas o resultado do método (o valor do stage, para
um método assíncrono) não é atribuível a `X`.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<Integer> {
        @Override
        public Outcome classify(Integer result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Correção:** implemente `Classifier` para o tipo de resultado do método ou para um supertipo dele.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-011

**Causa:** `@Retry` define tanto `classifier` quanto `retryOn`, ou `@CircuitBreaker` define tanto `classifier` quanto
`recordOn`. O classifier decide quais exceções são falhas, então a lista seria ignorada.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

public interface PriceClient {
    @Retry(classifier = Retryable.class, retryOn = IOException.class)
    String price(String sku) throws IOException;

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Correção:** remova `retryOn` (ou `recordOn`) e retorne `Outcome.FAILURE` para essas exceções a partir do
classifier. `abortOn` e `ignoreOn` continuam se aplicando antes do classifier.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku) throws IOException;

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
```

### R4C-CLS-012

**Causa:** `@Retry` define `onExhaustedResult` sem um `classifier`. Só um classifier pode rejeitar um
resultado, então a configuração nunca se aplicaria.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(onExhaustedResult = ExhaustedResult.THROW)
    String price(String sku);
}
```

**Correção:** adicione um classifier que rejeite os resultados a retentar, ou remova `onExhaustedResult`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = NotEmpty.class, onExhaustedResult = ExhaustedResult.THROW)
    String price(String sku);

    final class NotEmpty implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure != null || result == null || result.isEmpty() ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
```

## Annotation processor: fallbacks (R4C-FB)

Um `@Fallback` nomeia uma classe handler, `@Fallback(MyHandler.class)`, ou métodos irmãos,
`@Fallback(method = "name")`. Uma classe handler precisa cumprir as mesmas regras de um classifier: concreta,
não genérica, acessível, com um construtor sem argumentos, implementando `FallbackHandler<T>` para o tipo de
resultado do método.

### R4C-FB-001

**Causa:** `@Fallback` não define nem uma classe handler nem um nome de método, ou define os dois.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback
    String find(String id);
}
```

**Correção:** defina exatamente um deles.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-002

**Causa:** `@Fallback(method = "...")` nomeia um método que não existe na interface (ou classe).
A mensagem sugere o nome existente mais próximo.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallbak")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

**Correção:** corrija o nome, ou adicione o método. Ele recebe os mesmos parâmetros do método anotado,
opcionalmente seguidos da exceção, e retorna o mesmo tipo.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-003

**Causa:** existem métodos com o nome do fallback, mas nenhum pode ser usado. A mensagem lista cada candidato
e por que foi rejeitado: private (a classe gerada não pode chamá-lo), tipo de retorno errado, uma exceção
checked que o método anotado não declara, parâmetros de tipo diferentes, ou um parâmetro de exceção que
não é uma classe. Candidatos com outros parâmetros não são listados.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    private String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

**Correção:** numa interface, use um método default ou static; numa classe, um não privado. Siga a
assinatura que a mensagem mostra.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-004

**Causa:** `@Fallback` nomeia uma classe handler, mas `dev.resilience4cata.FallbackHandler` não está no
classpath de compilação.

**Errado:** compilar isto sem `resilience4cata-core` no classpath:

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Correção:** adicione a dependência do core:

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

### R4C-FB-005

**Causa:** o handler nomeado por `@Fallback` é uma interface, uma anotação ou um enum em vez de uma classe.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(FallbackHandler.class)
    String find(String id);
}
```

**Correção:** nomeie uma classe que implemente `FallbackHandler<T>`.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-006

**Causa:** a classe handler é abstract.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    abstract class Unknown implements FallbackHandler<String> {
    }
}
```

**Correção:** use uma classe concreta.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-007

**Causa:** a classe handler declara parâmetros de tipo.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown<T> implements FallbackHandler<T> {
        @Override
        public T apply(FallbackContext context) {
            return null;
        }
    }
}
```

**Correção:** implemente `FallbackHandler<T>` com um `T` concreto. Para um método genérico, use um método irmão
em vez disso (veja [R4C-FB-014](#r4c-fb-014)).

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-008

**Causa:** a classe handler (ou uma classe em que ela está aninhada) é private, ou package-private em outro
pacote, então a classe gerada não pode criá-la.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    private static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Correção:** torne-a public, ou package-private no pacote da interface.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-009

**Causa:** o handler é uma classe interna (aninhada não static).

**Errado:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Correção:** torne-a `static`.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-010

**Causa:** o handler não tem um construtor sem argumentos que a classe gerada possa chamar.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        private Unknown() {
        }

        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Correção:** adicione um construtor sem argumentos public (ou, no pacote da interface, package-private), ou remova
o private.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-011

**Causa:** a classe handler não implementa `dev.resilience4cata.FallbackHandler`.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(String.class)
    String find(String id);
}
```

**Correção:** nomeie uma classe que implemente `FallbackHandler<T>`.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-012

**Causa:** o handler implementa o tipo raw `FallbackHandler`.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler {
        @Override
        public Object apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Correção:** implemente `FallbackHandler<T>` com o tipo de resultado do método, como a mensagem sugere.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-013

**Causa:** o handler implementa `FallbackHandler<X>`, mas `X` não é atribuível ao tipo de retorno do método.
Para um método assíncrono que retorna um stage de `T`, `X` pode ser `T` ou `CompletionStage<T>`.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<Integer> {
        @Override
        public Integer apply(FallbackContext context) {
            return 0;
        }
    }
}
```

**Correção:** implemente `FallbackHandler` para o tipo de retorno do método (boxed, para um primitivo).

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-014

**Causa:** uma classe handler num método genérico cujo tipo de retorno depende dos parâmetros de tipo do método.
Um handler implementa `FallbackHandler<X>` para um único `X`, que não pode servir a toda instanciação sem um
cast não verificado.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface Repository {
    @Fallback(Nothing.class)
    <T> T load(Class<T> type, String id);

    final class Nothing implements FallbackHandler<Object> {
        @Override
        public Object apply(FallbackContext context) {
            return null;
        }
    }
}
```

**Correção:** use um método de fallback irmão que declare os mesmos parâmetros de tipo.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface Repository {
    @Fallback(method = "loadFallback")
    <T> T load(Class<T> type, String id);

    default <T> T loadFallback(Class<T> type, String id, Exception e) {
        return null;
    }
}
```

## Annotation processor: classes anotadas (R4C-SUB) :id=annotation-processor-annotated-classes-r4c-sub

Uma classe anotada recebe uma subclasse gerada, `<Class>$Resilient`, que sobrescreve os métodos anotados
e é criada com `Resilient.create(...)`. Por isso a classe precisa ser extensível, e os métodos anotados,
sobrescrevíveis.

### R4C-SUB-001

**Causa:** a classe anotada é final.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Correção:** remova `final`, ou mova as anotações para uma interface e envolva a implementação com
`Resilient.of(...)`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-002

**Causa:** a classe anotada é sealed, então a subclasse gerada não é uma subclasse permitida.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public sealed class PriceService permits CachedPriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}

final class CachedPriceService extends PriceService {
}
```

**Correção:** torne-a non-sealed (ou uma classe comum), ou use uma interface e `Resilient.of(...)`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}

final class CachedPriceService extends PriceService {
}
```

### R4C-SUB-003

**Causa:** a classe anotada é abstract. A subclasse gerada precisa ser instanciável e chama
`super` para cada método.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public abstract class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Correção:** anote uma subclasse concreta, ou uma interface.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-004

**Causa:** a classe anotada, ou uma classe em que ela está aninhada, é private.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Services {
    private static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

**Correção:** torne-a package-private, protected ou public.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Services {
    static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

### R4C-SUB-005

**Causa:** a classe anotada é uma classe interna (aninhada não static): sua subclasse gerada precisaria de uma
instância da classe envolvente.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class Services {
    class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

**Correção:** torne-a uma classe aninhada static.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class Services {
    static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

### R4C-SUB-006

**Causa:** todos os construtores da classe anotada são private, então a subclasse gerada não pode chamar nenhum.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    private PriceService() {
    }

    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Correção:** torne pelo menos um construtor package-private, protected ou public.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    protected PriceService() {
    }

    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-007

**Causa:** um método anotado (na classe, numa superclasse ou numa interface que ela implementa) é final, então a
subclasse não pode sobrescrevê-lo e suas anotações seriam ignoradas.

**Errado:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public final String price(String sku) {
        return "1";
    }
}
```

**Correção:** remova `final`, ou remova as anotações.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-008

**Causa:** um método anotado é package-private numa superclasse de outro pacote. A subclasse gerada,
no pacote da classe anotada, não pode sobrescrevê-lo, então suas anotações seriam ignoradas.

**Errado:**

```java
// com/acme/base/BaseService.java
package com.acme.base;

import dev.resilience4cata.annotation.Retry;

public class BaseService {
    @Retry
    String price(String sku) {
        return "1";
    }
}

// com/acme/PriceService.java
package com.acme;

import com.acme.base.BaseService;
import dev.resilience4cata.annotation.Retry;

public class PriceService extends BaseService {
    @Retry
    public String name() {
        return "prices";
    }
}
```

**Correção:** torne o método protected ou public.

```java
// com/acme/base/BaseService.java
package com.acme.base;

import dev.resilience4cata.annotation.Retry;

public class BaseService {
    @Retry
    protected String price(String sku) {
        return "1";
    }
}

// com/acme/PriceService.java
package com.acme;

import com.acme.base.BaseService;
import dev.resilience4cata.annotation.Retry;

public class PriceService extends BaseService {
    @Retry
    public String name() {
        return "prices";
    }
}
```

## Tempo de execução: Resilient e Pipeline (R4C-RT) :id=runtime-resilient-and-pipeline-r4c-rt

Estes são lançados pelo `resilience4cata-core` quando um serviço é envolvido ou uma pipeline é construída. Os tipos
de exceção estão documentados em cada método; o código está no início da mensagem.

### R4C-RT-001

**Causa:** `Resilient.of(type, ...)` com um `type` que é uma classe, não uma interface
(`IllegalArgumentException`).

**Errado:** `Resilient.of(PriceService.class, new PriceService(), registry)` onde `PriceService` é uma
classe anotada.

**Correção:** para uma classe anotada, use `Resilient.create(PriceService.class, registry)`; `Resilient.of` recebe
a interface anotada.

### R4C-RT-002

**Causa:** o objeto passado para `Resilient.of` não implementa a interface
(`IllegalArgumentException`). Isso só pode acontecer por meio de tipos raw ou casts não verificados.

**Correção:** passe uma implementação da interface.

### R4C-RT-003

**Causa:** a classe gerada `<type>$Resilient` não foi encontrada (`IllegalStateException`): o tipo não tem
anotação de resiliência, ou o annotation processor não executou quando ele foi compilado.

**Errado:** um build que só adiciona as anotações:

```groovy
dependencies {
    compileOnly 'dev.resilience4cata:resilience4cata-annotations:<version>'
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

**Correção:** adicione o processor, que é obrigatório (não há alternativa por reflexão):

```groovy
dependencies {
    compileOnly 'dev.resilience4cata:resilience4cata-annotations:<version>'
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
}
```

Com Maven:

```xml
<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version><!-- the same version as resilience4cata-core --></version>
  </path>
</annotationProcessorPaths>
```

Numa native image do GraalVM, o mesmo erro significa que o processor executou, mas a imagem não consegue alcançar a classe por
reflexão: os metadados de alcançabilidade que ele escreve ao lado dos arquivos de classe,
`META-INF/native-image/dev.resilience4cata.generated/<type>$Resilient/reachability-metadata.json`, não estavam no
class path do build da imagem, ou um passo do build (shading, um filtro de recursos personalizado) os removeu. A mensagem
termina então com uma frase que nomeia esse arquivo. Mantenha a saída do processor, metadados incluídos, no jar ou
diretório que o `native-image` lê; veja [Native image](start/installation.md#native-image).

### R4C-RT-004

**Causa:** a classe gerada não tem o construtor que esta versão do `resilience4cata-core`
espera, ou uma subclasse gerada não estende a classe anotada (`IllegalStateException`). Ela foi
gerada por uma versão incompatível do processor.

**Correção:** use a mesma versão para `resilience4cata-core` e `resilience4cata-processor` (o BOM as mantém
alinhadas), e depois reconstrua do zero.

### R4C-RT-005

**Causa:** o `resilience4cata-core` não consegue acessar a classe gerada (`IllegalStateException`), geralmente
porque a aplicação é um módulo nomeado que não exporta o pacote para o core.

**Correção:** adicione ao `module-info.java` da aplicação:

```java
exports com.acme to dev.resilience4cata.core;
```

### R4C-RT-006

**Causa:** o construtor da classe gerada falhou com uma exceção checked, ou não pôde ser instanciado
(`IllegalStateException`, com a falha como causa). Exceções unchecked do construtor, como
uma configuração inválida, são relançadas como estão.

**Correção:** olhe a causa; ela geralmente vem do construtor de um classifier ou de um fallback handler.

### R4C-RT-007

**Causa:** `Resilient.create(type, ...)` com uma interface (`IllegalArgumentException`).

**Correção:** envolva uma implementação em vez disso: `Resilient.of(PriceClient.class, implementation, registry)`.

### R4C-RT-008

**Causa:** `Resilient.create(type, ...)` com um tipo que não pode ter uma subclasse gerada: final,
abstract, local, anônimo, um enum, um record, um array ou um primitivo (`IllegalArgumentException`). O
processor reporta a maioria desses casos em tempo de compilação ([R4C-SUB-001](#r4c-sub-001) e seguintes).

**Correção:** anote uma classe de nível superior ou aninhada static que não seja final nem abstract, ou uma interface,
e use `Resilient.of(...)`.

### R4C-RT-009

**Causa:** nenhum construtor da classe anotada aceita os argumentos passados para `Resilient.create`
(`IllegalArgumentException`). A mensagem lista os construtores.

**Correção:** passe argumentos que combinem com um construtor, ou chame diretamente o construtor gerado, que o
compilador verifica: `new PriceService$Resilient(registry, ...)`.

### R4C-RT-010

**Causa:** mais de um construtor aceita os argumentos passados para `Resilient.create`, por exemplo um
único `null` (`IllegalArgumentException`).

**Correção:** chame diretamente o construtor gerado, com um cast para escolher a sobrecarga:
`new PriceService$Resilient(registry, (String) null)`.

### R4C-RT-011

**Causa:** `Pipeline.builder(registry, id)` com um id em branco (`IllegalArgumentException`).

**Correção:** passe o id que nomeia as instâncias de policy da pipeline, por exemplo `"payments"`.

### R4C-RT-012

**Causa:** uma parte do `Pipeline.Builder` é definida duas vezes: uma policy, um classifier, `onExhaustedResult` ou um
fallback (`IllegalStateException`).

**Errado:** `Pipeline.builder(registry, "payments").retry().retry("five")`

**Correção:** defina cada parte uma vez por builder; passe as sobrescritas para a única chamada:
`retry("five", b -> b.maxAttempts(3))`.

### R4C-RT-013

**Causa:** `fallbackApplyOn` ou `fallbackSkipOn` está definido, mas a pipeline não tem fallback
(`IllegalStateException` de `build()`).

**Correção:** adicione um fallback (`fallback`, `fallbackHandler`, `fallbackAsync`), ou remova a seleção.

### R4C-RT-014

**Causa:** `classifier(...)` está definido, mas a pipeline não tem nem retry nem circuit breaker
(`IllegalStateException` de `build()`).

**Correção:** adicione a policy à qual o classifier se destina, ou remova o classifier.

### R4C-RT-015

**Causa:** `retryClassifier(...)` está definido, mas a pipeline não tem retry (`IllegalStateException` de
`build()`).

**Correção:** adicione um retry, ou remova o classifier do retry.

### R4C-RT-016

**Causa:** `onExhaustedResult(...)` está definido, mas a pipeline não tem um retry com classifier
(`IllegalStateException` de `build()`). Só um classifier pode rejeitar um resultado. O equivalente com anotações
é [R4C-CLS-012](#r4c-cls-012).

**Correção:** adicione um retry e um classifier (`classifier` ou `retryClassifier`), ou remova `onExhaustedResult`.

### R4C-RT-017

**Causa:** `circuitBreakerClassifier(...)` está definido, mas a pipeline não tem circuit breaker
(`IllegalStateException` de `build()`).

**Correção:** adicione um circuit breaker, ou remova o classifier do circuit breaker.

### R4C-RT-018

**Causa:** `execute(...)` ou `run(...)` é chamado numa pipeline que tem um hedge (`IllegalStateException`). Um
hedge inicia a chamada de novo enquanto a primeira tentativa ainda está executando, o que exige uma chamada que retorne um
`CompletionStage`.

**Correção:** chame `executeAsync(() -> client.callAsync(...))`, ou remova `.hedge(...)` da pipeline.

### R4C-RT-019

**Causa:** a pipeline tem tanto `.retry(...)` quanto `.hedge(...)` (`IllegalStateException` de `build()`).

**Correção:** mantenha um: um hedge já inicia uma nova tentativa quando uma falha (`hedgeOn`), e um retry em volta dele
multiplicaria as tentativas. Este é o gêmeo em tempo de execução de [R4C-HDG-002](#r4c-hdg-002).

### R4C-RT-020

**Causa:** um `Flux` é protegido com uma pipeline (`FluxPipelineOperator`) que tem um hedge ou um classifier
(`IllegalStateException`, entregue ao subscriber do `Flux`). Um hedge misturaria os elementos de dois
streams, e um classifier julga um resultado enquanto um stream tem muitos.

**Correção:** remova `.hedge(...)` ou o classifier, ou construa uma pipeline separada para o `Flux`.

### R4C-RT-021

**Causa:** uma classe gerada pelo annotation processor (`X$Resilient`) é construída sobre um
`resilience4cata-core` (ou, para métodos `Mono` e `Flux`, um `resilience4cata-reactor`) mais antigo que o
`resilience4cata-processor` que a gerou (`IllegalStateException`, do construtor gerado). O
código gerado pode chamar métodos que este runtime não tem. O código gerado pelo processor `1.a` roda no
runtime `1.b` para todo `b >= a`, dentro de uma versão major. Isso costuma acontecer quando uma biblioteca compilada com um
resilience4cata mais novo é usada por uma aplicação cuja árvore de dependências resolve um core mais antigo.

**Correção:** use um runtime pelo menos tão novo quanto o processor: importe a mesma versão de `resilience4cata-bom` para todos os
artefatos do resilience4cata, ou verifique qual versão o seu build resolve (`./gradlew dependencyInsight --dependency
resilience4cata-core`, `mvn dependency:tree`).

## Tempo de execução: registry e configuração das policies (R4C-REG) :id=runtime-registry-and-policy-configuration-r4c-reg

Estes são lançados pelo `resilience4cata-core` quando uma configuração de policy é construída (`RetryConfig.builder()` e
os outros builders `*Config`), quando um `ResilienceRegistry` é construído, e quando o registry cria ou procura
uma instância de policy: enquanto um serviço é envolvido (`Resilient.of`, `Resilient.create`) ou uma pipeline é construída
(`Pipeline.Builder.build()`, que os repassa sem alteração). Os tipos de exceção estão documentados em cada
método; o código está no início da mensagem. A configuração externa (`ResilienceConfig`) valida seus
valores antes que cheguem ao registry e os reporta como [R4C-CFG-006](#r4c-cfg-006), com as chaves que os
definem.

### R4C-REG-001

**Causa:** um builder `*Config` rejeitou um valor (`IllegalArgumentException` de `build()`), por exemplo um
`maxAttempts` abaixo de 1, uma duração negativa, uma duração de mais de cerca de 292 anos (`Long.MAX_VALUE` nanossegundos), um `maxDelay` menor que o `delay`, ou um limiar de taxa fora de
`(0, 100]`. A mensagem nomeia o atributo e o valor.

**Errado:** `RetryConfig.builder().maxAttempts(0).build()`

**Correção:** use um valor no intervalo que a mensagem indica: `RetryConfig.builder().maxAttempts(1).build()`.

### R4C-REG-002

**Causa:** `ResilienceRegistry.Builder.build()` encontrou uma configuração padrão ou nomeada inválida
(`IllegalArgumentException`, com o erro [R4C-REG-001](#r4c-reg-001) como causa). A mensagem nomeia a
configuração (ou os padrões) e onde ela foi definida.

**Errado:**

```java
ResilienceRegistry.builder()
        .retry("slow", r -> r.delay(Duration.ofSeconds(2)).maxDelay(Duration.ofSeconds(1)))
        .build();
```

**Correção:** torne os valores dessa configuração válidos em conjunto. Uma configuração nomeada parte dos padrões
(`retryDefaults(...)`), depois aplica `retry("slow", ...)`, depois `retryOverlay("slow", ...)`
(`configs.slow.retry.*` na configuração externa):

```java
ResilienceRegistry.builder()
        .retry("slow", r -> r.delay(Duration.ofSeconds(1)).maxDelay(Duration.ofSeconds(2)))
        .build();
```

### R4C-REG-003

**Causa:** a configuração de uma instância de policy é inválida depois que tudo o que se aplica a ela é
combinado (`IllegalArgumentException`, com o erro [R4C-REG-001](#r4c-reg-001) como causa): sua configuração
nomeada (ou os padrões), depois os valores definidos na sua anotação ou passo de pipeline, depois suas sobrescritas
por instância (`retryInstance(id, ...)`, `instances.<id>.*` na configuração externa). É lançado quando a
instância é criada: por `Resilient.of`/`Resilient.create` ou por `Pipeline.Builder.build()`.

**Errado:** uma sobrescrita por instância que não combina com os valores próprios da instância:

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryInstance("payments", r -> r.delay(Duration.ofSeconds(30)))
        .build();
Pipeline.builder(registry, "payments").retry(r -> r.maxDelay(Duration.ofSeconds(20))).build();
```

**Correção:** mude a sobrescrita, ou o valor com que ela colide, para que a combinação seja válida, por exemplo
`retryInstance("payments", r -> r.delay(Duration.ofSeconds(10)))`.

### R4C-REG-004

**Causa:** `config = "name"` (numa anotação ou num passo de pipeline), ou `retryConfig("name")` e as outras
buscas `*Config(String)`, nomeia uma configuração que o registry não tem (`IllegalArgumentException`). A
mensagem lista as configurações conhecidas e sugere a mais próxima ("Did you mean …?").

**Errado:** `@Retry(config = "fiev")` com um registry que só registra `retry("five", ...)`.

**Correção:** corrija o nome, ou registre a configuração: `ResilienceRegistry.builder().retry("fiev", r -> ...)`, ou
`configs.fiev.retry.*` na configuração externa.

### R4C-REG-005

**Causa:** a mesma configuração nomeada é registrada duas vezes num builder, por exemplo duas chamadas a
`retry("five", ...)` (`IllegalArgumentException`).

**Correção:** registre-a uma vez. Para mudar alguns dos seus atributos em outro lugar, use o overlay, que se aplica por cima
dela: `retryOverlay("five", r -> r.maxAttempts(3))`.

### R4C-REG-006

**Causa:** uma configuração nomeada ou um overlay é registrado com o nome `""` ou `"default"`, por exemplo
`retry("default", ...)` (`IllegalArgumentException`). Os dois nomes são reservados: significam os padrões.

**Correção:** mude os padrões com `retryDefaults(...)` (`defaults.retry.*` na configuração externa), ou
dê outro nome à configuração.

### R4C-REG-007

**Causa:** `retryInstance("", ...)` ou outra chamada `*Instance(...)` com um id de instância vazio
(`IllegalArgumentException`).

**Correção:** passe o id da instância: o `name` definido na sua anotação, o id da pipeline, ou o id derivado
`<type>#<method>(<parameter types>)`, por exemplo `com.acme.PriceClient#price(java.lang.String)`.

### R4C-REG-008

**Causa:** dois pontos de chamada compartilham uma instância de retry, circuit breaker, rate limiter ou bulkhead, através do
mesmo `name` nas suas anotações ou do mesmo id de pipeline, mas resolvem para configurações diferentes
(`IllegalStateException`). A mensagem mostra as duas configurações. Instâncias com o mesmo nome são compartilhadas,
então precisam concordar.

**Errado:**

```java
interface Inventory {
    @Retry(name = "backend", maxAttempts = 3)
    String stock(String sku);

    @Retry(name = "backend", maxAttempts = 5)
    String reserve(String sku);
}
```

**Correção:** dê nomes diferentes aos pontos de chamada se eles devem ser independentes, ou torne a configuração deles
idêntica (por exemplo, o mesmo `config = "..."` e nenhum atributo diferente) se devem compartilhar uma
instância.

### R4C-REG-009

**Causa:** `registry.circuitBreaker(id)`, `registry.rateLimiter(id)` ou `registry.bulkhead(id)` com um id
que nenhuma instância deste registry tem (`IllegalArgumentException`). A mensagem lista os ids conhecidos e
sugere o mais próximo. As instâncias são criadas quando sua interface é envolvida (`Resilient.of`), sua classe
é criada (`Resilient.create`) ou sua pipeline é construída, então procurar uma antes disso também falha.

**Correção:** use o `name` definido na anotação (ou o id da pipeline), ou o id derivado
`<type>#<method>(<parameter types>)`, e procure-o depois que o serviço ou a pipeline tiver sido criado.

## Tempo de execução: configuração externa (R4C-CFG) :id=runtime-external-configuration-r4c-cfg

`ResilienceConfig.build()` reporta todos os problemas de uma vez num único `ConfigException`; cada linha começa com seu
código e nomeia a chave e sua origem (arquivo e linha, variável de ambiente ou propriedade de sistema).

### R4C-CFG-001

**Causa:** uma chave desconhecida, malformada ou incompleta: policy ou atributo desconhecido, segmento vazio, uma chave que
termina cedo demais. A mensagem sugere a chave válida mais próxima. Uma chave com o prefixo curto (`r4c.`, `R4C_`) cujo
primeiro segmento não se parece com `defaults`, `configs`, `instances` ou `profiles` é ignorada em vez disso, como sendo de outra
ferramenta ([Chaves](reference/configuration.md#key-forms)).

**Errado:**

```properties
configs.external.circuit-breaker.wait-in-opne=10s
```

**Correção:** use uma das formas de chave que a mensagem lista: `defaults.<policy>.<attribute>`,
`configs.<name>.<policy>.<attribute>`, `configs.<name>.extends`, `instances.<id>.<policy>.<attribute>`,
`profiles.<profile>.…` ou `profiles.active`.

```properties
configs.external.circuit-breaker.wait-in-open=10s
```

### R4C-CFG-002

**Causa:** um valor que não pode ser interpretado para seu atributo: um número, boolean, constante de enum ou duração.

**Errado:**

```properties
defaults.retry.delay=fast
```

**Correção:** use um valor do tipo do atributo; as durações usam a mesma gramática das anotações (`"250ms"`,
`"2s"`, `"PT2S"`, `"0"`).

```properties
defaults.retry.delay=250ms
```

### R4C-CFG-003

**Causa:** uma lista de exceções (`retry-on`, `abort-on`, `record-on`, `ignore-on`) nomeia uma classe que o class
loader não encontra, ou uma classe que não é um `Throwable`.

**Errado:**

```properties
defaults.retry.retry-on=java.io.IOExeption
```

**Correção:** use nomes totalmente qualificados de classes de exceção visíveis para o class loader da aplicação. Numa native image do GraalVM, registre cada uma das suas próprias classes para reflexão: a mensagem mostra a entrada a adicionar ([Native image](start/installation.md#native-image)).

```properties
defaults.retry.retry-on=java.io.IOException
```

### R4C-CFG-004

**Causa:** um nome de configuração ou de perfil inválido, ou um reservado (`default` para uma configuração, `active` para um
perfil). Os nomes usam letras minúsculas e dígitos separados por hífens simples, para que correspondam a
variáveis de ambiente.

**Errado:**

```properties
profiles.active=Test
```

**Correção:** renomeie-o, por exemplo `test`.

```properties
profiles.active=test
```

### R4C-CFG-005

**Causa:** a mesma chave é definida duas vezes numa mesma origem (por exemplo duas vezes num arquivo, ou sob duas grafias
como `max-attempts` e `maxAttempts`).

**Correção:** mantenha uma delas. Origens diferentes podem definir a mesma chave; a ordem de precedência decide.

### R4C-CFG-006

**Causa:** os valores são válidos um a um, mas não juntos, por exemplo um `max-delay` menor que o
`delay`, ou `max-attempts=0` (o builder da configuração os rejeitou). A mensagem nomeia as chaves envolvidas.

**Errado:**

```properties
configs.slow.retry.delay=2s
configs.slow.retry.max-delay=1s
```

**Correção:** torne os valores coerentes, como a mensagem descreve.

```properties
configs.slow.retry.delay=1s
configs.slow.retry.max-delay=2s
```

### R4C-CFG-007

**Causa:** `configs.<name>.extends` nomeia uma configuração que não está definida na configuração externa, ou
configurações estendem umas às outras em ciclo.

**Errado:**

```properties
configs.batch.extends=slwo
```

**Correção:** estenda uma configuração existente (a mensagem sugere o nome mais próximo e lista os conhecidos) e
quebre qualquer ciclo.

### R4C-CFG-008

**Causa:** reservado; não é reportado pela versão atual.

### R4C-CFG-009

**Causa:** uma origem de configuração não pode ser lida: um recurso do classpath ou arquivo que não existe, ou um
erro de I/O.

**Correção:** verifique o caminho, ou não adicione a origem quando ela for opcional.

### R4C-CFG-010

**Causa:** uma variável de ambiente corresponde a mais de uma chave, porque `_` representa tanto `.` quanto `-`.

**Correção:** defina essa chave num arquivo properties ou como propriedade de sistema em vez disso.

### R4C-CFG-011

**Causa:** aposentado; não é mais reportado. Uma configuração nomeada definida tanto no código quanto externamente agora é mesclada,
vencendo os valores externos.

### R4C-CFG-012

**Causa:** um id de instância malformado numa chave `instances.`: um id que contém `.` mas não está entre aspas, um
id vazio, uma `"` não fechada ou mal posicionada, aspas em volta de algo que não seja o id, ou um id que começa ou termina com
espaço em branco.

**Errado:**

```properties
instances.com.acme.Api#get(java.lang.String).retry.max-attempts=5
```

**Correção:** coloque o id da instância entre aspas.

```properties
instances."com.acme.Api#get(java.lang.String)".retry.max-attempts=5
```

### R4C-CFG-013

**Causa:** `config.verifyInstances(registry)` encontrou uma sobrescrita `instances.` que não corresponde a nenhuma instância
dessa policy no registry (reportado por `verifyInstances`, não por `build()`, porque as instâncias só existem depois que
os serviços são envolvidos). A mensagem sugere o id mais próximo.

**Correção:** use um id de instância existente: o `name` da policy, ou `<type>#<method>(<parameter types>)` quando
ela não tem um.

### R4C-CFG-014

**Causa:** `config.verifyInstances(registry)` encontrou uma sobrescrita `instances.` de um atributo que a
anotação da instância define com `locked = true` (ou que uma pipeline construída com `.locked()` define no seu passo).
O registry manteve o valor da anotação e ignorou a sobrescrita; a mensagem mostra a configuração que a
instância usa.

**Correção:** remova a chave, ou remova `locked = true` da anotação se os operadores devem poder
mudar esse valor. Veja [Travar uma instância](guides/configuration.md#locking-an-instance).

## Tempo de execução: integração com Spring Boot (R4C-SPR) :id=runtime-spring-boot-integration-r4c-spr

`resilience4cata-spring` verifica os beans na inicialização e a faz falhar com estes códigos quando um bean perderia
silenciosamente sua proteção. O Spring Boot imprime a mensagem como a *Description* e a correção como a *Action* do seu
relatório "APPLICATION FAILED TO START". Veja o [guia do Spring Boot](guides/spring.md).

### R4C-SPR-001

**Causa:** um bean é uma instância de uma classe anotada `C` mas não da `C$Resilient` gerada, então suas
policies nunca executariam: um método `@Bean` retornou `new C(...)`, o bean foi registrado com um supplier,
ou ele é uma subclasse de `C` que não tem anotações próprias (`BeanCreationException`).

```text
[R4C-SPR-001] Bean 'pricing' is an instance of com.acme.PricingService, which has resilience4cata annotations, but not of the generated com.acme.PricingService$Resilient, so its policies would not run. ...
```

**Correção:** registre a própria classe (component scanning, `@Import`), para que o Spring instancie
`C$Resilient`; ou retorne `new C$Resilient(registry, ...)` do método `@Bean`, com o
`ResilienceRegistry` injetado como parâmetro; ou anote a subclasse. Para deixar o bean sem proteção de
propósito, liste seu nome em `resilience4cata.spring.exclude`.

### R4C-SPR-002

**Causa:** uma classe `@Configuration` com métodos `@Bean` também tem anotações do resilience4cata. O Spring
instancia sua própria subclasse CGLIB de uma classe assim, então a subclasse gerada não pode ser usada.

**Correção:** mova os métodos anotados para uma classe `@Component` ou `@Service`, ou declare a configuração
com `@Configuration(proxyBeanMethods = false)`, que o Spring não estende.

### R4C-SPR-003

**Causa:** um bean implementa duas ou mais interfaces anotadas, nenhuma das quais estende todas as outras
(`BeanCreationException`). O wrapper gerado de uma interface implementa só essa interface, então o
bean não poderia mais ser injetado pelas outras.

**Correção:** anote a classe em vez disso (sua subclasse gerada mantém todas as interfaces e continua sendo o tipo
do bean), ou declare uma interface que estenda as outras e implemente essa. Para envolver o bean você mesmo,
liste-o em `resilience4cata.spring.exclude` e defina um `@Bean` que retorne `Resilient.of(...)`.

### R4C-SPR-004

**Causa:** uma classe anotada tem um método com `@Retry` ou `@Hedge` em vigor (no método, na classe, numa
superclasse, numa interface ou num bundle) que também é transacional: o `@Transactional` do Spring ou do Jakarta com uma
propagação que participa de uma transação ou inicia uma (`REQUIRED`, `SUPPORTS`, `MANDATORY`, `REQUIRES_NEW`, `NESTED`)
(`BeanCreationException`). O proxy de transação fica em volta da subclasse gerada, então todas as tentativas executariam
na mesma transação, e uma tentativa que falha e a marca como rollback-only faz o commit de uma tentativa posterior
bem-sucedida falhar com `UnexpectedRollbackException`.

```text
[R4C-SPR-004] Bean 'ledger' (com.acme.Ledger) has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**Correção:** declare a policy numa interface que o bean implementa em vez disso (seu wrapper fica fora do
proxy de transação: uma transação por tentativa); ou mova `@Transactional` para outro bean que o método com retry
chama; ou, se uma única transação compartilhada por todas as tentativas é intencional, defina
`resilience4cata.spring.allow-retry-inside-transaction=true`. `NOT_SUPPORTED` e `NEVER` não são reportados.

### R4C-SPR-005

**Causa:** um bean implementa uma interface anotada, mas o Spring o criou antes de o post-processor do resilience4cata
ser registrado, então ele nunca foi envolvido e suas policies não executariam. O Spring cria um bean tão
cedo quando um `BeanPostProcessor` ou `BeanFactoryPostProcessor` depende dele (um parâmetro de construtor ou um campo
desse post-processor), e registra no log que o bean "is not eligible for getting processed by all BeanPostProcessors"
(`ResilienceBeanException`, depois que todo singleton é criado).

```text
[R4C-SPR-005] Bean 'stockClient' (com.acme.HttpStockClient) implements com.acme.StockClient, which has resilience4cata annotations, but it was created before resilience4cata's post-processor was registered, ... It was needed by 'auditPostProcessor'. ...
```

**Correção:** injete o bean de forma lazy nesse post-processor (`ObjectProvider<StockClient>` ou `@Lazy`), para que ele seja
criado depois, passando por todos os post-processors; ou anote a classe em vez da interface (sua subclasse
gerada é instanciada no lugar da classe, cedo ou não). Para deixar o bean sem proteção de propósito, liste seu
nome em `resilience4cata.spring.exclude`.

### R4C-SPR-006

**Causa:** a classe de um bean, uma das suas superclasses, ou uma interface que ele implementa tem anotações do resilience4cata,
mas a classe que o annotation processor gera para ele (`C$Resilient`) não existe: o processor não executou
quando esse tipo foi compilado, então suas policies não executariam. As anotações têm retenção `CLASS`, então nada mais
perceberia (`ResilienceBeanException`, quando o bean é criado). A verificação lê arquivos de classe, então não executa
numa native image.

```text
[R4C-SPR-006] Bean 'prices' is an instance of com.acme.PriceService, which has resilience4cata annotations, but there is no generated com.acme.PriceService$Resilient: the annotation processor did not run when PriceService was compiled, so its policies would not run. ...
```

**Correção:** adicione `resilience4cata-processor` ao annotation processor path, e depois reconstrua do zero
([instalação](start/installation.md)): `annotationProcessor` com Gradle (e `testAnnotationProcessor` para fontes
de teste), `annotationProcessorPaths` do `maven-compiler-plugin` com Maven, onde todos os outros processors também precisam ser
listados. Para iniciar sem as policies de propósito, defina `resilience4cata.spring.allow-missing-processor=true`, ou
liste o bean em `resilience4cata.spring.exclude`.

## Tempo de execução: integração com Micronaut (R4C-MN) :id=runtime-micronaut-integration-r4c-mn

`resilience4cata-micronaut` verifica os beans e falha com estes códigos quando um bean perderia silenciosamente sua
proteção. Os erros de `@ResilientBean` (001 a 003) interrompem a inicialização: toda definição de bean anotada com
`@ResilientBean` é verificada quando o contexto inicia, antes que qualquer uma seja criada. Os outros são reportados quando o bean
é criado, o que acontece na inicialização para beans `@Context` e no primeiro uso para os demais (o Micronaut cria os beans
de forma lazy), como um `ResilienceBeanException` dentro do `BeanInstantiationException` do Micronaut. Veja o
[guia do Micronaut](guides/micronaut.md).

### R4C-MN-001

**Causa:** um bean anotado com `@ResilientBean(I.class)` é exposto como outros tipos além de `I`, por exemplo porque
também declara `@Bean(typed = {I.class, Impl.class})`. `@ResilientBean` limita os tipos do bean a `I`, porque o
wrapper gerado que substitui o bean implementa só `I`: injetá-lo como outro tipo falharia ou contornaria
as policies.

```text
[R4C-MN-001] Bean com.acme.HttpPayments is annotated @ResilientBean(Payments.class) but is exposed as [com.acme.HttpPayments, com.acme.Payments]. ...
```

**Correção:** remova `@Bean(typed = ...)` (ou `@Type`) do bean e injete a interface.

### R4C-MN-002

**Causa:** `@ResilientBean(X.class)` nomeia um tipo que não é uma interface, ou uma interface que o bean não
implementa, então nenhum wrapper gerado pode protegê-lo.

**Correção:** nomeie a interface anotada que o bean implementa. Para uma classe anotada, remova `@ResilientBean`:
compile com `-Ar4c.inject=micronaut` para que o Micronaut crie sua subclasse gerada, ou retorne
`new C$Resilient(registry, ...)` de um método `@Factory` ([classes anotadas](guides/micronaut.md#classes)).

### R4C-MN-003

**Causa:** um bean anotado com `@ResilientBean` tem um escopo que o Micronaut envolve num proxy (`@Refreshable`,
`@RequestScope`, `@ThreadLocal`, qualquer `@ScopedProxy`). Um proxy assim procura seu alvo pela classe do bean,
que o wrapper gerado não é.

**Correção:** torne o bean `@Singleton`, `@Prototype` ou `@Context`, ou mantenha o bean com escopo sem proteção e injete-o
num singleton que implemente a interface anotada.

### R4C-MN-004

**Causa:** um bean é uma instância de uma classe anotada `C` mas não da `C$Resilient` gerada, então suas
policies nunca executariam: o Micronaut criou o próprio `C` (ele tem uma anotação de escopo ou um construtor `@Inject`, e
o código foi compilado sem `-Ar4c.inject=micronaut`), ou um método `@Factory` retornou `new C(...)`. Também reportado
quando o Micronaut cria `C$Resilient` a partir da sua própria definição embora ela tenha sido compilada sem a opção: seu
construtor repete o `@Inject` de `C`, então ele é um bean, mas sem o escopo e os qualificadores de `C` (um `@Singleton`
viraria uma nova instância a cada injeção).

```text
[R4C-MN-004] Bean com.acme.InventoryService is an instance of com.acme.InventoryService, which has resilience4cata annotations, but not of the generated InventoryService$Resilient, so its policies would not run. ...
```

**Correção:** compile com `-Ar4c.inject=micronaut`, para que o Micronaut crie `C$Resilient` no lugar de `C`; ou remova
a anotação de escopo (e `@Inject`) de `C` e retorne `new C$Resilient(registry, ...)` de um método `@Factory`
com tipo `C`. Para deixar o bean sem proteção de propósito, liste o nome da classe em `resilience4cata.micronaut.exclude`.

### R4C-MN-005

**Causa:** uma classe anotada compilada com `-Ar4c.inject=micronaut` tem um método com `@Retry` ou `@Hedge` em
vigor que também é `@Transactional` (o do Micronaut, ou o do Jakarta mapeado para ele) com uma propagação que participa de uma transação ou
inicia uma (`REQUIRED`, `SUPPORTS`, `MANDATORY`, `REQUIRES_NEW`, `NESTED`). A subclasse gerada
repete `@Transactional`, então o proxy de transação do Micronaut fica em volta dela: todas as tentativas executariam na mesma
transação, e uma tentativa que falha e a marca como rollback-only faz o commit de uma tentativa posterior bem-sucedida
falhar.

```text
[R4C-MN-005] Bean com.acme.Ledger has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**Correção:** declare a policy numa interface que o bean implementa e anote o bean com `@ResilientBean` (o
wrapper fica fora do proxy de transação: uma transação por tentativa); ou mova `@Transactional` para outro
bean que o método com retry chama; ou, se uma única transação compartilhada por todas as tentativas é intencional, defina
`resilience4cata.micronaut.allow-retry-inside-transaction=true`. `NOT_SUPPORTED` e `NEVER` não são reportados.
A verificação lê o arquivo de classe gerado, então uma native image a pula.

### R4C-MN-006

**Causa:** um switch da integração com Micronaut tem um valor inválido, por exemplo
`resilience4cata.micronaut.verify-instances: strict`. O contexto não consegue iniciar.

```text
[R4C-MN-006] Invalid value 'strict' for resilience4cata.micronaut.verify-instances. Use fail, warn or off.
```

**Correção:** use um dos valores que a mensagem lista.

### R4C-MN-007

**Causa:** a classe de um bean ou uma das suas superclasses tem anotações do resilience4cata, mas a subclasse que o
annotation processor gera para ela (`C$Resilient`) não existe: o processor não executou quando essa classe foi
compilada, então suas policies não executariam. As anotações têm retenção `CLASS`, então nada mais perceberia
(`ResilienceBeanException`, quando o bean é criado). A verificação lê arquivos de classe, então não executa numa native
image. Uma interface anotada só é envolvida por meio de `@ResilientBean`, que falha com
[R4C-RT-003](#r4c-rt-003) quando o wrapper está faltando.

```text
[R4C-MN-007] Bean com.acme.PriceService has resilience4cata annotations, but there is no generated com.acme.PriceService$Resilient: the annotation processor did not run when PriceService was compiled, so its policies would not run. ...
```

**Correção:** adicione `resilience4cata-processor` ao annotation processor path depois de `micronaut-inject-java`, com
`-Ar4c.inject=micronaut`, e depois reconstrua do zero ([Micronaut](guides/micronaut.md)). Para iniciar sem as policies
de propósito, defina `resilience4cata.micronaut.allow-missing-processor=true`, ou liste a classe em
`resilience4cata.micronaut.exclude`.

## Build: extensão Quarkus (R4C-QK) :id=build-quarkus-extension-r4c-qk

`resilience4cata-quarkus` verifica a aplicação enquanto o Quarkus a constrói (`quarkusBuild`, dev mode, todo
`@QuarkusTest`) e faz o build falhar com estes códigos quando um bean não seria protegido como declarado, ou seria
protegido duas vezes. O Quarkus os imprime entre seus "deployment problems". Veja o [guia do Quarkus](guides/quarkus.md).

### R4C-QK-001

**Causa:** um método protegido pelo resilience4cata também traz anotações do MicroProfile ou do SmallRye Fault Tolerance
(`org.eclipse.microprofile.faulttolerance.*`, `io.smallrye.faulttolerance.api.*`), no método ou
na sua classe; para uma interface anotada, também no método da classe que a implementa. Os nomes simples são os
mesmos (`@Retry`, `@CircuitBreaker`, `@Timeout`, `@Bulkhead`, `@Fallback`), então isso costuma ser um import errado. Com
`quarkus-smallrye-fault-tolerance` instalado as duas bibliotecas se aplicariam, então o build falha; sem ele as anotações do Fault
Tolerance não fazem nada, e a mesma mensagem é registrada como aviso.

```text
[R4C-QK-001] Methods protected by resilience4cata also carry MicroProfile Fault Tolerance annotations: com.acme.CatalogClient.fetch(). SmallRye Fault Tolerance would apply its interceptor as well, so both libraries would retry, time out and count the same calls. ...
```

**Correção:** mantenha uma biblioteca por método: remova a outra anotação, ou corrija o import. Veja
[SmallRye Fault Tolerance vs resilience4cata](guides/quarkus.md#smallrye).

### R4C-QK-002

**Causa:** uma classe anotada tem um método com `@Retry` ou `@Hedge` em vigor que também é
`@Transactional` (Jakarta Transactions, com Narayana) com um tipo que participa de uma transação ou inicia uma
(`REQUIRED`, `REQUIRES_NEW`, `MANDATORY`, `SUPPORTS`). O interceptor de transação fica em volta da subclasse
gerada, então todas as tentativas executariam na mesma transação, e uma tentativa que falha e a marca como
rollback-only faz o commit de uma tentativa posterior bem-sucedida falhar.

```text
[R4C-QK-002] Bean com.acme.Ledger has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**Correção:** declare a policy numa interface que o bean implementa em vez disso (seu wrapper fica fora do
interceptor de transação: uma transação por tentativa); ou mova `@Transactional` para outro bean que o
método com retry chama; ou, se uma única transação compartilhada por todas as tentativas é intencional, defina
`quarkus.resilience4cata.allow-retry-inside-transaction=true`. `NOT_SUPPORTED` e `NEVER` não são reportados.

### R4C-QK-003

**Causa:** um bean implementa duas ou mais interfaces anotadas, nenhuma das quais estende todas as outras. O
wrapper gerado de uma interface implementa só essa interface, então o bean só pode ser injetado através de um
único wrapper.

**Correção:** declare uma interface que estenda as outras e implemente essa, ou anote a classe em vez disso.
Para envolver o bean você mesmo, liste sua classe em `quarkus.resilience4cata.beans.exclude` e retorne
`Resilient.of(...)` de um método producer.

### R4C-QK-004

**Causa:** uma classe anotada declara métodos ou campos producer (`@Produces`). A subclasse gerada
substitui a classe como bean, e os producers do CDI não são herdados, então eles desapareceriam.

**Correção:** mova os producers para outro bean, ou liste a classe em `quarkus.resilience4cata.beans.exclude`.

### R4C-QK-005

**Causa:** uma classe anotada é um bean de escopo normal (`@ApplicationScoped`, `@RequestScoped`,
`@SessionScoped`) e não tem um construtor não privado sem parâmetros. O client proxy que o Quarkus coloca na
frente da subclasse gerada precisa de um, e o Quarkus só pode adicioná-lo à subclasse se a classe tiver um.

```text
[R4C-QK-005] com.acme.OrderService is a normal-scoped bean (@ApplicationScoped) with resilience4cata annotations, but has no non-private constructor without parameters. ...
```

**Correção:** adicione um construtor sem parâmetros (package-private basta: só o proxy o usa), ou use
injeção por campo em vez de injeção por construtor, ou torne o bean `@Singleton`, que não tem client proxy.

### R4C-QK-006

**Causa:** um bean protegido pelo resilience4cata tem uma condição de lookup (`@LookupIfProperty`,
`@LookupUnlessProperty`): uma classe anotada, ou um bean que implementa uma interface anotada. O bean que a aplicação
recebe é a subclasse gerada ou o wrapper da interface, e o Quarkus lê as condições de lookup só na classe
original, então o bean continuaria resolvível independentemente do que a propriedade diz.

```text
[R4C-QK-006] Beans protected by resilience4cata have lookup conditions (@LookupIfProperty, @LookupUnlessProperty): com.acme.BetaFeature. ...
```

**Correção:** use uma condição de tempo de build em vez disso (`@IfBuildProperty`, `@IfBuildProfile` e suas formas `Unless`), que
funciona em classes anotadas, ou verifique a propriedade no código que procura o bean.

### R4C-QK-007

**Causa:** um recurso do Quarkus REST (uma classe com `@Path`, ou uma que implementa uma interface com `@Path`) é uma
classe anotada. O Quarkus REST cria as classes de recurso ele mesmo, então a subclasse gerada nunca serviria os
endpoints e as policies não executariam.

```text
[R4C-QK-007] REST resource classes (@Path) have resilience4cata annotations: com.acme.OrdersResource. ...
```

**Correção:** mova as chamadas protegidas para um bean que o recurso injeta (uma classe anotada, ou um bean que implementa uma
interface anotada), ou proteja a interface do REST client que o recurso chama.

### R4C-QK-008

**Causa:** a classe de um bean de classe, uma das suas superclasses, ou uma interface que ela implementa tem anotações do resilience4cata,
mas a classe que o annotation processor gera para ela (`C$Resilient`) não está na aplicação: o
processor não executou quando esse tipo foi compilado, então suas policies não executariam. As anotações têm retenção `CLASS`,
então nada em tempo de execução perceberia (`DeploymentException`, durante o build).

```text
[R4C-QK-008] Beans have resilience4cata annotations but no generated class: the annotation processor did not run when these types were compiled, so their policies would not run: com.acme.PriceService (no com.acme.PriceService$Resilient). ...
```

**Correção:** adicione `resilience4cata-processor` ao annotation processor path, e depois reconstrua do zero
([Quarkus](guides/quarkus.md)). Para construir sem as policies de propósito, defina
`quarkus.resilience4cata.allow-missing-processor=true`, ou liste as classes dos beans em
`quarkus.resilience4cata.beans.exclude`.

## Testes: testkit (R4C-TK) :id=tests-testkit-r4c-tk

`ManualClock` e `ResilienceTesting` reportam usos incorretos nos testes com estes códigos.

### R4C-TK-001

**Causa:** `ManualClock.advance(…)` com uma duração negativa (`IllegalArgumentException`). Um relógio só anda
para a frente.

**Correção:** passe uma duração de zero ou mais. Para começar um teste em outro instante, crie o relógio com esse instante:
`new ManualClock(startNanos)`, ou `@ResilienceTest(startNanos = …)` com a extensão JUnit.

### R4C-TK-002

**Causa:** `ManualClock.advanceUntilIdle()` desistiu depois de 100.000 tarefas seguidas
(`IllegalStateException`): cada tarefa que ele executou agendou outra, então o relógio nunca ficaria ocioso. Normalmente um
retry que continua falhando sem limite de tempo, ou um helper de teste que se reagenda.

**Correção:** avance até um instante fixo com `clock.advance(…)` em vez disso, ou dê ao retry um limite (`maxAttempts`,
`maxDuration`) para que as tarefas terminem.

### R4C-TK-003

**Causa:** `ResilienceTesting.elapseWaitInOpen(breaker, clock)` num circuit breaker que não está `OPEN`
(`IllegalStateException`); a mensagem diz em que estado ele está.

**Correção:** chame-o depois que o breaker abriu: verifique que as falhas registradas até ali atingem `minimumCalls` e
`failureRateThreshold`. Para testar o estado aberto sem falhas, use `breaker.forceOpen()`.

## Testes: extensão JUnit (R4C-JU) :id=tests-junit-extension-r4c-ju

`@ResilienceTest` (`ResilienceExtension`) reporta usos incorretos com estes códigos; o JUnit os mostra como a falha do
teste ou da classe.

### R4C-JU-001

**Causa:** um parâmetro `ManualClock` ou `ResilienceRegistry` num construtor ou num método `@BeforeAll`
(`ParameterResolutionException`). A extensão cria um novo relógio e um novo registry para cada teste, então não existe
nenhum antes de o primeiro teste começar.

**Correção:** receba-os como parâmetros do método de teste ou de um método `@BeforeEach`.

### R4C-JU-002

**Causa:** um método `@ConfigureRegistry` com outra assinatura (`ExtensionConfigurationException`; o JUnit 5
o envolve num `ParameterResolutionException`).

**Correção:** torne-o um método `void` não privado com um único parâmetro `ResilienceRegistry.Builder`, static ou não:
`void configure(ResilienceRegistry.Builder builder)`.

### R4C-JU-003

**Causa:** com `@ResilienceTest(failOnPendingTasks = true)`, um teste passou mas deixou tarefas no seu relógio
(`AssertionError`): um retry, espera ou timeout assíncrono que nunca executou. A mensagem lista seus atrasos.

**Correção:** avance o relógio além delas (`clock.advanceUntilIdle()`, ou `clock.advance(…)` o suficiente), ou defina
`failOnPendingTasks = false` se deixá-las é intencional.
