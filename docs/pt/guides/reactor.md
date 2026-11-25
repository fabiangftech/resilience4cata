# Project Reactor

Um método que devolve `Mono<T>` ou `Flux<T>` do Reactor recebe a forma assíncrona de cada policy, uma vez por assinatura, com o contexto do assinante, e é cancelado através das policies quando o assinante cancela.

<div class="r4c-facts">

| Módulo | Reactor | Tipos | Desde |
|---|---|---|---|
| `resilience4cata-reactor` | 3.6 ou mais recente | `Mono<T>`, `Flux<T>` | 0.11.0 |

</div>

## Instalação :id=install

Adicione o módulo junto aos outros; o processor não precisa de nada novo.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.1.0'
    implementation 'dev.resilience4cata:resilience4cata-reactor:1.1.0'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.1.0'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.1.0")
    implementation("dev.resilience4cata:resilience4cata-reactor:1.1.0")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.1.0")
}
```

#### **Maven**

```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-reactor</artifactId>
  <version>1.1.0</version>
</dependency>
```

<!-- tabs:end -->

O módulo depende de `resilience4cata-core` e `reactor-core`; o Reactor da sua própria aplicação, mais novo, prevalece. Um método `Mono` compilado sem ele é o erro [`R4C-ASY-003`](../errors.md#r4c-asy-003).

## Uma interface Mono :id=a-mono-interface

As anotações são as mesmas que em qualquer outro método; o tipo de retorno decide.

<!-- snippet: reactor-interface -->
```java
public interface PriceClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @CircuitBreaker(name = "prices")
    @Timeout("2s")
    @Fallback(method = "unknown")
    Mono<BigDecimal> price(String sku);

    default Mono<BigDecimal> unknown(String sku, Throwable failure) {
        return Mono.just(BigDecimal.ZERO);
    }
}
```

<!-- snippet: reactor-call -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
PriceClient prices = Resilient.of(PriceClient.class, http, registry);

Mono<BigDecimal> price = prices.price("sku-1");   // nothing runs yet
BigDecimal value = price.block();                 // subscribes: two failed attempts, then 42
```

Classes anotadas funcionam da mesma forma ([classes anotadas](annotated-classes.md)), assim como beans do Spring, Micronaut e Quarkus: em um controller WebFlux, devolva o `Mono` do bean protegido como ele é.

## O que um método Mono faz :id=what-a-mono-method-does

- **Nada roda antes de uma assinatura.** Chamar `price("sku-1")` constrói um `Mono`; cada assinatura é uma chamada através das policies, então assinar duas vezes (ou `repeat()`) chama duas vezes.
- **Cada tentativa chama o método de novo.** Um retry ou um hedge chama a sua implementação para obter um novo `Mono` e o assina, como em um método `CompletionStage`. Um método que lança exceção, ou devolve `null`, é uma tentativa falha.
- **O contexto do assinante chega a cada tentativa,** e também ao `Mono` do fallback, então tracing, o `ReactiveSecurityContextHolder` do Spring Security e suas próprias chaves funcionam dentro das policies:

<!-- snippet: reactor-context -->
```java
Mono<BigDecimal> price = prices.price("sku-1")
        .contextWrite(Context.of("tenant", "acme"));   // the delegate's Mono, and every retry, see it
```

- **Um `Mono` vazio é um sucesso sem valor** (`null` para um classifier), e o resultado também é vazio.
- **Cancelar a assinatura cancela a chamada através de cada policy:** o `Mono` da tentativa atual é cancelado, um retry esperando seu delay para, o breaker não registra nada, o bulkhead e o adaptive limit liberam sua vaga. Um cliente que se desconecta de um endpoint WebFlux faz isso.
- **Falhas chegam sem demanda, valores sob demanda,** como o Reactive Streams exige; o `Mono` gerado passa no [Reactive Streams TCK](https://github.com/reactive-streams/reactive-streams-jvm/tree/master/tck).
- **Nenhuma policy bloqueia a thread,** o que o BlockHound verifica no build: um rate limiter ou um bulkhead com `maxWait` faz a chamada esperar sem estacionar uma thread, como em qualquer [método assíncrono](async.md).

Qual thread emite: a primeira tentativa assina na thread que faz a assinatura. Delays de retry, hedges, timeouts e esperas do rate limiter disparam no timer da biblioteca, que entrega o trabalho a uma nova virtual thread, então o valor de uma tentativa posterior chega lá. Adicione `publishOn(...)` depois do método se os próximos operadores precisarem de um scheduler específico.

> [!MEASURED]
> No AWS Graviton3, um método `Mono` com circuit breaker custa 106 ns por chamada contra 243 ns do operador do resilience4j-reactor 2.4.0 (105 vs 1291 ns com quatro threads), e uma pilha de retry, breaker, timeout e bulkhead 201 ns contra 4319 ns, incluindo os 52 ns de assinar o `Mono`. Veja [Desempenho](../performance.md#reactor).

## Fallbacks

Um fallback de um método `Mono` devolve o valor (`BigDecimal`) ou um `Mono` dele (`Mono<BigDecimal>`), como método irmão ou um [`FallbackHandler`](../policies/fallback.md); um `CompletionStage` não é aceito. O `Mono` dele é assinado com o contexto do chamador.

## Métodos Flux :id=flux

Um método que devolve `Flux<T>` recebe as mesmas policies, adaptadas a um stream: cada assinatura é uma chamada, cada tentativa assina de novo o `Flux` do método, e seus elementos chegam ao assinante à medida que chegam, com a demanda e o contexto do assinante.

<!-- snippet: reactor-flux-interface -->
```java
public interface PriceFeed {

    @Retry(maxAttempts = 3, delay = "50ms")
    @Timeout("5s")                       // at most 5 s to the first price, and between prices
    @Fallback(method = "closed")
    Flux<BigDecimal> prices(String sku);

    default Flux<BigDecimal> closed(String sku, Throwable failure) {
        return Flux.empty();
    }
}
```

<!-- snippet: reactor-flux-call -->
```java
PriceFeed feed = Resilient.of(PriceFeed.class, exchange, ResilienceRegistry.builder().build());

feed.prices("sku-1")
        .doOnNext(price -> System.out.println(price))   // two failed attempts, then 41, 42, 43
        .blockLast();
```

- **O primeiro elemento confirma a chamada.** Antes dele, uma falha é retentada ou substituída pelo fallback, como em um `Mono`. Depois dele, a falha chega ao assinante como é: um retry enviaria os elementos de novo, e um fallback misturaria duas fontes.

<!-- snippet: reactor-flux-committed -->
```java
StepVerifier.create(committed.prices("sku-1"))
        .expectNext(new BigDecimal(41))       // the first element: no retry, no fallback from now on
        .expectErrorMessage("feed broke")     // so the failure reaches the subscriber as it is
        .verify();
```

- **`@Timeout` é o tempo sem elemento:** até o primeiro, entre dois e até o fim. Um stream que continua emitindo nunca sofre timeout, por mais que dure; um que trava sofre, antes do primeiro elemento (então é retentado) ou depois dele (o assinante recebe `CallTimeoutException`). O tempo esperando a demanda do assinante conta, como no operador `timeout` do Reactor.
- **O circuit breaker** registra uma chamada por tentativa quando seu stream termina, então uma falha depois de elementos é uma falha; `slowCallDuration` é o tempo até o primeiro elemento. Um assinante que cancela depois do primeiro elemento (`next()`, `take(1)`) conta como sucesso, porque o stream respondeu; um que cancela antes não é registrado. **O adaptive limit** ocupa uma vaga enquanto o stream roda e amostra o tempo até o primeiro elemento.
- **O bulkhead** ocupa sua vaga até o stream terminar ou ser cancelado. **O rate limiter** consome uma permissão por tentativa, não por elemento.
- **Um fallback** devolve um `Flux<T>`, ou um valor `T` (um stream desse único elemento), como método irmão ou um [`FallbackHandler`](../policies/fallback.md).
- **A demanda** é transferida: a próxima tentativa recebe o pedido de tudo o que o assinante solicitou, já que nada foi emitido antes do primeiro elemento. Uma requisição não positiva é reportada e ignorada, como fazem os próprios operadores do Reactor (o próprio Reactor sinaliza o erro da regra 3.9 a qualquer outro assinante); o `Flux` gerado passa em todas as regras do Reactive Streams TCK.
- **Cancelar a assinatura** cancela a chamada através de cada policy; no WebFlux, um cliente que se desconecta de um endpoint de server-sent events (`produces = MediaType.TEXT_EVENT_STREAM_VALUE`) que devolve o `Flux` do bean faz isso, e o bulkhead libera sua vaga.
> [!MEASURED]
> No AWS Graviton3, um stream de dez elementos através de um método `Flux` com `@Timeout` custa 681 ns contra 28896 ns com o time limiter do resilience4j-reactor 2.4.0 (`flux.timeout`, um timer por elemento), e a pilha de retry, breaker, timeout e bulkhead 789 ns contra 30583 ns; mil elementos com timeout levam 45 µs contra 2.6 ms. Um único bulkhead ou rate limiter fica dentro da margem de erro do Resilience4j. Veja [Desempenho](../performance.md#reactor).

- **Não em métodos `Flux`:** `@Hedge` (mesclaria dois streams), classifiers (um resultado para muitos elementos: use `retryOn`/`abortOn` e `recordOn`/`ignoreOn`) e um `@PolicyOrder` que mude a ordem. Cada um é [`R4C-ASY-004`](../errors.md#r4c-asy-004).

## Código que você não pode anotar :id=code-you-cannot-annotate

`MonoPipelineOperator` aplica um [pipeline](pipeline.md) a um `Mono`, para uma chamada `WebClient` ou um cliente de terceiros:

<!-- snippet: reactor-operator -->
```java
MonoPipelineOperator<String> resilient = MonoPipelineOperator.of(Pipeline.builder(registry, "rates", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(2)))
        .build());

Mono<String> rates = webClientCall.transform(resilient);   // each attempt subscribes to webClientCall again
```

Cada tentativa assina a fonte de novo, o que executa uma requisição `WebClient` de novo. Uma fonte que dá o mesmo resultado a todo assinante (`cache()`, uma fonte hot) não é retentada de forma útil; em vez disso, construa-a por tentativa com `resilient.defer(() -> ...)`.

`FluxPipelineOperator` faz o mesmo para um `Flux` (`bodyToFlux`), com as regras de stream [acima](#flux); o valor do fallback do pipeline se torna um stream desse único elemento, e um pipeline com hedge ou classifier não pode proteger um `Flux` ([`R4C-RT-020`](../errors.md#r4c-rt-020)):

<!-- snippet: reactor-flux-operator -->
```java
FluxPipelineOperator<String> resilient = FluxPipelineOperator.of(Pipeline.builder(registry, "quotes", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(5)))
        .build());

Flux<String> quotes = webClientStream.transform(resilient);   // an attempt subscribes to webClientStream again
```

## Testes :id=testing

Em um [`ManualClock`](testing.md), delays, timeouts e hedges acontecem quando o teste avança o relógio, e o `StepVerifier` verifica o resultado:

<!-- snippet: reactor-testing -->
```java
ManualClock clock = new ManualClock();
ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
PriceClient prices = Resilient.of(PriceClient.class, slow, registry);

StepVerifier.create(prices.price("sku-1"))
        .then(() -> clock.advance(Duration.ofSeconds(7)))   // three 2 s timeouts and the delays between them
        .expectNext(BigDecimal.ZERO)                        // the fallback
        .verifyComplete();
```

O relógio manual é o da biblioteca; `StepVerifier.withVirtualTime` move os schedulers do Reactor, não os timers das policies.

## Limites :id=limits

- **Exatamente `Mono` ou `Flux`:** uma subclasse de qualquer um deles, `Mono<? super T>` ou um `Mono` raw não é um método reativo ([`R4C-ASY-001`](../errors.md#r4c-asy-001), ou `R4C-ASY-002` para uma subclasse).
- **Um método `Flux`** não tem hedge, nem classifier, e usa a ordem padrão ([`R4C-ASY-004`](../errors.md#r4c-asy-004)), e seu retry e fallback atuam apenas antes do primeiro elemento ([acima](#flux)).
- **Transações:** um `@Transactional` reativo em um método `Mono` com retry ou hedge de uma classe anotada executaria todas as tentativas em uma única transação, o que Spring, Micronaut e Quarkus reportam na inicialização, como para outros métodos ([`R4C-SPR-004`](../errors.md#r4c-spr-004)).
- **Mutiny e RxJava** ainda não são suportados; converta para um `CompletionStage` (`uni.subscribeAsCompletionStage()`).
