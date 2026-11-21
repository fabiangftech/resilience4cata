# resilience4cata 1.0.0-RC4: o seu próprio bulkhead não abre mais o circuit breaker

A quarta release candidate da 1.0.0 está no Maven Central. A API é a mesma da RC3, e um comportamento muda: o circuit breaker não conta mais como falhas as rejeições do próprio bulkhead, rate limiter ou adaptive limit do método.

*14 de novembro de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor do resilience4cata*

## O que o piloto encontrou :id=what-the-pilot-found

Ninguém roda o resilience4cata em produção ainda, então antes da 1.0 há um [piloto](https://github.com/fabiangftech/resilience4cata/tree/main/pilot), a coisa mais próxima disso sem um usuário. Ele roda três serviços pequenos, um em Spring Boot, um em Micronaut e um em Quarkus. Cada um pega o resilience4cata do Maven Central, como diz o seu guia, e chama um backend simulado por HTTP. O k6 envia a eles 10 minutos de carga: 1500 pedidos por segundo, com o backend fora do ar por um minuto e lento por outro. O método que chama o backend usa a pilha de costume:

<!-- not-compiled: o serviço do piloto, resumido -->
```java
@Fallback(method = "lastKnown")
@Retry(name = "inventory", maxAttempts = 3, delay = "20ms")
@CircuitBreaker(name = "inventory", windowSize = 100, minimumCalls = 20, failureRateThreshold = 50,
        waitInOpen = "5s", halfOpenCalls = 5)
@Timeout("200ms")
@Bulkhead(name = "inventory", maxConcurrent = 40, maxWait = "20ms")
public String stock(String id) throws IOException, InterruptedException { ... }
```

Na 1.0.0-RC3, o serviço Spring abriu o seu breaker durante a partida a frio, e o backend não tinha falhado nenhuma vez. As chamadas são mais lentas enquanto uma JVM aquece, então 40 delas encheram o bulkhead e as seguintes foram rejeitadas com `BulkheadFullException`. O breaker fica fora do bulkhead na ordem padrão, então viu essas rejeições como falhas. Quando a sua janela tinha 20 chamadas e metade delas eram rejeições, ele abriu, e os pedidos foram para o fallback enquanto ele ficou aberto.

A primeira execução do piloto evitou isso com `ignoreOn = BulkheadFullException.class` no breaker. Um usuário teria que saber que essa linha era necessária, e nada no erro diria isso a ele.

## Por que é uma armadilha :id=why-it-is-a-trap

O breaker responde a uma pergunta: a dependência está falhando? Um bulkhead cheio, um rate limiter esgotado ou um adaptive limit cheio respondem a outra: esta aplicação está ocupada? Misturar as duas tem três efeitos ruins:

- Uma rajada do seu próprio tráfego abre o breaker enquanto a dependência está saudável, e o fallback responde chamadas que teriam dado certo um instante depois.
- Um breaker aberto então rejeita até as chamadas que o bulkhead deixaria entrar, e a sobrecarga vira uma indisponibilidade que dura pelo menos `waitInOpen`.
- Uma sondagem half-open rejeitada pelo bulkhead conta como sondagem falha, então o breaker abre de novo sem perguntar nada à dependência.

O Resilience4j tem a mesma armadilha: o bulkhead e o rate limiter dele também ficam dentro do breaker, e os seus `BulkheadFullException` e `RequestNotPermitted` contam como falhas a menos que você os liste em `ignoreExceptions`. A diferença na 1.0.0-RC4 é que você não precisa mais se lembrar.

## A regra na RC4 :id=the-rule-in-rc4

O circuit breaker nunca registra `BulkheadFullException`, `RateLimitExceededException` nem `AdaptiveLimitFullException`. Elas não contam nem como falhas nem como sucessos, e uma sondagem half-open que recebe uma delas devolve a sua permissão. Isso vale independentemente do que digam `recordOn`, `ignoreOn` ou um classifier, para métodos síncronos, `CompletionStage`, `Mono` e `Flux` e para o `Pipeline`. Os detalhes estão na [página do circuit breaker](../policies/circuit-breaker.md#with-other-policies).

Duas exceções continuam contando, porque elas dizem algo sobre a dependência:

- `CallTimeoutException`: uma dependência lenta demais está falhando. Uma dependência lenta portanto ainda abre o breaker, pelos seus timeouts e chamadas lentas.
- `CallNotPermittedException` de outro breaker mais para dentro: a dependência atrás dele está falhando.

A regra é fixa e não tem chave, de propósito: ninguém deveria ter que configurar uma saída de uma armadilha. Um breaker que deve reagir a sobrecarga é outra ferramenta, e é para isso que existem o [bulkhead](../policies/bulkhead.md) e o [adaptive limit](../policies/adaptive-limit.md).

## O que muda para você :id=what-changes-for-you

- Se você adicionou `ignoreOn = BulkheadFullException.class`, ou a rejeição do rate limiter ou do adaptive limit, a um breaker, pode removê-lo. Deixá-lo não faz mal.
- Se você migra do Resilience4j, tire essas entradas de `ignoreExceptions`: o [guia de migração](../migrate/from-resilience4j.md#local-rejections) tem a nota.
- Nada mais muda. Todo módulo publicado foi comparado com a RC3: nenhuma mudança incompatível na API. Os testes de `examples`, compilados com o processor da RC3, passam nos módulos da RC4, então o código gerado pela RC3 continua funcionando.

## O piloto de novo :id=the-pilot-again

O piloto então rodou sobre o código novo, sem nenhum `ignoreOn`, na mesma máquina.

> [!MEASURED]
> AWS `c7g.4xlarge` (Graviton3), JDK 25, 2026-11-13, o código da 1.0.0-RC4. Cada serviço teve 4 núcleos, o backend 4 e o k6 8. Linha do Spring Boot; Micronaut e Quarkus ficam a menos de um milissegundo dela até o p99. Execução `20261113-202658` de `pilot/run.sh aws`, resumida no [README do piloto](https://github.com/fabiangftech/resilience4cata/tree/main/pilot).

| Fase | Pedidos respondidos pelo backend | p50 | p99 | p99.9 |
|---|---|---:|---:|---:|
| estável | 100 % (o seu 1 % de 503 resolvido por retries) | 6.8 ms | 28.7 ms | 42.3 ms |
| backend fora do ar | 0 %: o breaker respondeu todos os pedidos | 0.1 ms | 0.2 ms | 1.2 ms |
| backend lento (1 s) | 0 %: breaker, bulkhead ou timeout; ninguém esperou o segundo | 0.1 ms | 0.3 ms | 260.6 ms |
| depois das quedas | 100 % | 6.8 ms | 28.7 ms | 42.1 ms |

Os três serviços passaram em todas as verificações, cerca de 1.04 milhão de requisições cada um. No serviço Spring, um pedido dos 270,000 da fase estável foi respondido por um breaker aberto. Ele começou logo antes de o backend cair no minuto 4, e os seus retries encontraram o breaker já aberto: o breaker fazendo o seu trabalho, não a armadilha antiga.

## Experimente :id=try-it

<!-- tabs:start -->

#### **Gradle**

<!-- not-compiled: configuração de build -->
```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC4'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC4'
}
```

#### **Gradle (Kotlin)**

<!-- not-compiled: configuração de build -->
```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.0.0-RC4")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.0.0-RC4")
}
```

#### **Maven**

<!-- not-compiled: configuração de build -->
```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>1.0.0-RC4</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0-RC4</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

O [quickstart](../start/quickstart.md) protege uma chamada instável em uns cinco minutos; para uma aplicação, comece pelo guia de [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) ou [Quarkus](../guides/quarkus.md). O [anúncio da RC2](release-candidate.md) explica o que uma release candidate significa aqui e qual feedback ajuda mais.

## O que vem a seguir :id=what-comes-next

Se nada mais precisar mudar, a 1.0.0 vem a seguir com a mesma API e a promessa de [compatibilidade](../reference/compatibility.md) começa. Esta mudança é do tipo para o qual existe uma release candidate: depois da 1.0, mudar o que o breaker conta teria surpreendido os usuários em uma versão minor. Se você experimentar a RC4 e algo o surpreender, abra uma [issue](https://github.com/fabiangftech/resilience4cata/issues) ou uma [discussão](https://github.com/fabiangftech/resilience4cata/discussions) no GitHub.
