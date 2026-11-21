# Políticas

O resilience4cata tem oito políticas, uma anotação cada; coloque-as em um método ou em uma interface inteira, sozinhas ou empilhadas, e o processor as aninha em uma ordem padrão, que `@PolicyOrder` pode mudar.

| Política | Anotação | O que faz | Lançada quando rejeita |
|---|---|---|---|
| [Fallback](fallback.md) | `@Fallback` | Responde com um resultado alternativo quando a chamada falha ou é rejeitada | — |
| [Retry](retry.md) | `@Retry` | Chama de novo após uma falha, com backoff e jitter | — (relança a última falha) |
| [Hedge](hedge.md) | `@Hedge` | Inicia a mesma chamada de novo enquanto a primeira está lenta e fica com o primeiro sucesso (só async, só chamadas idempotentes) | — (relança a última falha) |
| [Circuit breaker](circuit-breaker.md) | `@CircuitBreaker` | Para de chamar uma dependência que continua falhando e depois a testa | `CallNotPermittedException` |
| [Rate limiter](rate-limiter.md) | `@RateLimiter` | Deixa passar no máximo N chamadas por período | `RateLimitExceededException` |
| [Adaptive limit](adaptive-limit.md) | `@AdaptiveLimit` | Limita quantas chamadas rodam ao mesmo tempo, com um limite que ajusta pela latência delas | `AdaptiveLimitFullException` |
| [Timeout](timeout.md) | `@Timeout` | Interrompe uma chamada sync, ou faz falhar uma async, que demora demais | `CallTimeoutException` |
| [Bulkhead](bulkhead.md) | `@Bulkhead` | Limita quantas chamadas rodam ao mesmo tempo | `BulkheadFullException` |

Todas as políticas, exceto Hedge, funcionam em métodos síncronos e em métodos que retornam `CompletionStage<T>` ou `CompletableFuture<T>` (veja [Métodos async](../guides/async.md)); Hedge precisa de um método assíncrono e ocupa o lugar do Retry (os dois não podem ser combinados em um método). As exceções ficam no pacote `dev.resilience4cata`, estendem `RuntimeException`, não têm stack trace e carregam o `id()` da política. As quatro rejeições (`CallNotPermittedException`, `RateLimitExceededException`, `AdaptiveLimitFullException`, `BulkheadFullException`) compartilham a superclasse `CallRejectedException`: capture-a para responder a toda rejeição do mesmo jeito, por exemplo com `503 Service Unavailable`. Cada política lança uma única instância compartilhada, então não as compare por identidade.

## De qual política eu preciso? :id=which-policy-do-i-need

| Problema | Política | Comece com |
|---|---|---|
| As chamadas falham de vez em quando, e tentar de novo costuma funcionar | Retry | `@Retry(maxAttempts = 3, delay = "200ms")` |
| Uma dependência cai por minutos, e as chamadas se acumulam esperando por ela | Circuit breaker (dentro de um retry) | `@CircuitBreaker(name = "…")` |
| Uma dependência trava, e suas threads travam junto | Timeout | `@Timeout("2s")` |
| Uma API permite 10 requisições por segundo | Rate limiter | `@RateLimiter(permits = 10, period = "1s")` |
| Uma dependência lenta esgota todas as suas threads ou conexões | Bulkhead | `@Bulkhead(maxConcurrent = 10)` |
| O mesmo, mas você não sabe quantas chamadas concorrentes ela aguenta, ou isso muda | Adaptive limit | `@AdaptiveLimit` com um `@Timeout` dentro |
| O p99 está muito acima do p50 em uma leitura idempotente de backends replicados | Hedge | `@Hedge(delay = "50ms")` em um método `CompletableFuture` |
| Existe uma resposta degradada razoável (cache, valor padrão, "tente mais tarde") | Fallback | `@Fallback(method = "…")` |
| A falha está no resultado, não em uma exceção (HTTP `503`) | Retry / circuit breaker com um [classifier](../guides/classifiers.md) | `@Retry(classifier = …)` |

Uma pilha comum para uma chamada remota é um timeout, um circuit breaker e um retry, mais um fallback onde houver uma resposta degradada.

## A ordem padrão :id=default-order

Da mais externa para a mais interna:

```mermaid
flowchart LR
    caller(["quem chama"]) --> F["Fallback"] --> R["Retry | Hedge"] --> C["CircuitBreaker"] --> L["RateLimiter"] --> A["AdaptiveLimit"] --> T["Timeout"] --> B["Bulkhead"] --> M(["seu método"])
```

A ordem em que você escreve as anotações não importa. A ordem padrão foi escolhida para que cada política veja o que deve ver:

- **O fallback é o mais externo**, então vê o resultado final: a última falha quando os retries desistem, e toda rejeição das políticas internas.
- **O retry fica fora do circuit breaker**, então cada tentativa é registrada pelo breaker, e um breaker aberto interrompe os retries (abaixo). **O hedge** fica no mesmo lugar, pelos mesmos motivos: cada tentativa disparada passa sozinha pelo breaker, pelo limiter, pelo timeout e pelo bulkhead.
- **O circuit breaker fica fora do rate limiter**, então as chamadas que ele rejeita não gastam permissões.
- **O adaptive limit fica dentro do rate limiter e fora do timeout e do bulkhead**, então um timeout ou um bulkhead cheio dentro dele é o sinal de sobrecarga que baixa o seu limite.
- **O timeout fica dentro do retry**, então vale por tentativa; `@Retry(maxDuration = …)` limita a operação inteira.
- **O bulkhead é o mais interno**, então um slot fica ocupado só enquanto o método realmente roda: não durante o backoff de um retry, não enquanto se espera uma permissão.

## Mudando a ordem :id=changing-the-order

`@PolicyOrder` lista as camadas a mover, da mais externa para a mais interna. Elas se movem juntas, nessa ordem, para o
lugar da mais externa delas; as camadas que você não lista mantêm a ordem padrão ao redor, e as camadas que o método não
tem são ignoradas. Aqui o timeout envolve todo o loop do retry, então um segundo limita todas as tentativas juntas:

<!-- snippet: policies-order -->
```java
import static dev.resilience4cata.annotation.Layer.RETRY;
import static dev.resilience4cata.annotation.Layer.TIMEOUT;

import dev.resilience4cata.annotation.Jitter;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportApi {

    @PolicyOrder({TIMEOUT, RETRY})                // Fallback -> Timeout -> Retry -> ...: one deadline for every attempt
    @Timeout("1s")
    @Retry(maxAttempts = 10, delay = "300ms", jitter = Jitter.NONE)
    String report(String id);
}
```

As mudanças mais comuns:

- **`{TIMEOUT, RETRY}`**: um único prazo para a operação inteira, como acima. Com a ordem padrão cada tentativa tem o
  próprio timeout (um `@Retry(maxDuration = …)` também limita a operação inteira, sem reordenar).
- **`{CIRCUIT_BREAKER, RETRY}`**: o breaker registra a operação inteira uma vez, não cada tentativa, e um breaker aberto
  rejeita a chamada antes de o retry começar.
- **`{BULKHEAD, RETRY}`**: o bulkhead ocupa um slot durante a operação inteira, pausas de backoff incluídas, então limita
  operações em andamento em vez de chamadas em voo.

O que mais saber:

- **O fallback é sempre o mais externo**, então continua vendo o resultado final. `FallbackContext.attempts()` conta as
  tentativas do retry só enquanto o retry é a camada logo dentro do fallback, como por padrão; com outra camada no meio
  ele informa `1`.
- **Qualquer ordem compila.** Algumas mudam a semântica de formas que vale conferir: um rate limiter fora do breaker gasta
  permissões em chamadas que o breaker rejeita, e um bulkhead fora de um timeout segura o slot até o timeout disparar.
- **Onde colocar:** em um método, em um tipo (para todo método) ou em um [bundle](../guides/bundles.md); a do método
  vence a do tipo, e uma anotação direta vence a de um bundle. Listar uma camada duas vezes é
  [`R4C-ORD-001`](../errors.md#r4c-ord-001).
- **É decidida em tempo de compilação**, como o resto do código gerado, então não pode vir da configuração. Um
  [`Pipeline`](../guides/pipeline.md) sempre usa a ordem padrão.

## Combinando políticas :id=combining-them

<!-- compare:start -->
<!-- snippet: policies-stack -->
```java
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface CheckoutApi {

    @Bulkhead(maxConcurrent = 20)                       // the order you write them in does not matter:
    @Timeout("2s")                                      // they always nest as
    @RateLimiter(permits = 50, period = "1s")           // Fallback → Retry → CircuitBreaker
    @CircuitBreaker(name = "checkout")                  //   → RateLimiter → Timeout → Bulkhead → method
    @Retry(maxAttempts = 3, delay = "200ms")
    @Fallback(method = "later")
    String placeOrder(String cartId);

    default String later(String cartId, Exception failure) {
        return "queued " + cartId;
    }
}
```
<!-- not-compiled: código gerado, resumido -->
```java
public final class CheckoutApi$Resilient implements CheckoutApi {
    // … one final field per policy, created from the registry in the constructor

    @Override
    public java.lang.String placeOrder(java.lang.String p0) {           // Fallback + Retry
        final dev.resilience4cata.generated.RetryPolicy r4c$retry = this.retry0;
        final long r4c$start = r4c$retry.start();
        int r4c$attempts = 0;
        while (true) {
            try {
                return this.r4c$circuitBreaker0(p0);
            } catch (java.lang.Throwable r4c$e) {
                final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);
                if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {
                    if (!this.fallback0.handles(r4c$e)) {
                        throw r4c$e;
                    }
                    if (r4c$e instanceof java.lang.Exception r4c$f0) {
                        return this.delegate.later(p0, r4c$f0);
                    }
                    throw r4c$e;
                }
            }
        }
    }

    private java.lang.String r4c$circuitBreaker0(java.lang.String p0) {
        // … acquire a permit, then:
            final java.lang.String r4c$result = this.r4c$rateLimiter0(p0);
        // …
    }

    private java.lang.String r4c$rateLimiter0(java.lang.String p0) {
        // … take a permit, then:
        return this.r4c$timeout0(p0);
    }

    private java.lang.String r4c$timeout0(java.lang.String p0) {
        // … start timing, then:
            r4c$result = this.r4c$bulkhead0(p0);
        // …
    }

    private java.lang.String r4c$bulkhead0(java.lang.String p0) {
        // … take a slot, then:
            return this.delegate.placeOrder(p0);
        // …
    }
}
```
<!-- compare:end -->

Cada política vira uma camada de código linear: o método público contém a camada mais externa, e cada camada interna é um método privado que chama a próxima, até a sua implementação. Nada é compartilhado entre métodos, exceto o que você compartilha por `name`.

- As anotações também podem ir na própria interface, para todo método, e ser agrupadas em anotações suas; veja [Bundles](../guides/bundles.md).
- Cada política lê a configuração uma vez, quando você envolve a interface, dos padrões embutidos, de `defaults.*`, de configs nomeadas e da anotação, nessa ordem; veja [Configuração](../guides/configuration.md).
- Breakers, rate limiters e bulkheads com o mesmo `name` compartilham uma instância, e o mesmo vale para pipelines com esse id. Retries e timeouts não guardam estado entre chamadas, exceto um retry com um [retry budget](retry.md#retry-budget), que compartilha o budget do mesmo jeito.

### Um breaker aberto interrompe os retries :id=an-open-breaker-stops-the-retries

Um retry nunca repete `CallNotPermittedException`, a exceção que um circuit breaker aberto lança, diga o que disser o seu `abortOn`. Então, assim que o breaker abre, o retry desiste na tentativa seguinte em vez de esperar o backoff, e o fallback responde:

<!-- snippet: policies-open-breaker -->
```java
CheckoutApi checkout = Resilient.of(CheckoutApi.class, cartId -> {
    calls.incrementAndGet();
    return "placed " + cartId;
}, registry);
registry.circuitBreaker("checkout").forceOpen();

assertThat(checkout.placeOrder("cart-1")).isEqualTo("queued cart-1");  // the fallback answered
assertThat(calls).hasValue(0);                  // the breaker rejected the first attempt...
assertThat(clock.elapsed()).isZero();           // ...and the retry did not wait to try again
```

### Rejeições e as camadas externas :id=rejections-and-the-outer-layers

Uma rejeição de uma política interna é, por padrão, uma `Exception` como qualquer outra para as camadas fora dela, com uma exceção:

- `RateLimitExceededException`, `AdaptiveLimitFullException`, `CallTimeoutException` e `BulkheadFullException` são **repetidas** por um `@Retry` externo (após o seu delay). Liste-as no `abortOn` do retry quando não for isso que você quer.
- Um `@CircuitBreaker` externo **registra** `CallTimeoutException` como falha, mas **nunca** a rejeição de um limite local (`BulkheadFullException`, `RateLimitExceededException`, `AdaptiveLimitFullException`), digam o que disserem o seu `recordOn`, `ignoreOn` ou classifier: um bulkhead cheio é a carga da própria aplicação e não deve abrir o breaker quando a dependência está bem.
- `Error`s não são repetidos (`retryOn` tem `Exception` como padrão), mas o breaker os registra como falhas (`recordOn` tem `Throwable` como padrão), e o `applyOn = Throwable` padrão do fallback os captura, a menos que o seu parâmetro de exceção seja mais estreito.

## Uso programático :id=programmatic-use

Código que não pode ser anotado recebe as mesmas políticas, na ordem padrão, de um [`Pipeline`](../guides/pipeline.md). Cada página de política mostra o equivalente em pipeline.

## Quanto custa :id=what-it-costs

> [!MEASURED]
> No AWS Graviton3 (`c7g.xlarge`, JDK 21), uma chamada bem-sucedida passando por retry, circuit breaker, rate limiter e bulkhead custa 61.6 ns com código gerado, contra 261 ns e 328 B no Resilience4j 2.4.0; as seis políticas custam 76.3 ns (o Resilience4j não tem timeout na thread de quem chama para comparar). O código gerado não aloca nada nesses caminhos. [Benchmarks: conclusão do MVP](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)

## Glossário :id=glossary

- **Backoff e jitter.** O backoff é como a espera entre retries cresce (fixa ou exponencial). O jitter torna cada espera aleatória para que muitos chamadores não repitam no mesmo instante; o *full jitter*, o padrão, escolhe a espera de forma uniforme entre zero e o valor do backoff.
- **Bulkhead.** Um limite de quantas chamadas rodam ao mesmo tempo, para que uma dependência lenta não tome todas as threads. Chamadas acima do limite esperam (`maxWait`) ou são rejeitadas.
- **Estados do circuit breaker.** *Fechado*: as chamadas passam e seus resultados são contados. *Aberto*: as chamadas são rejeitadas na hora, durante `waitInOpen`. *Semiaberto*: algumas chamadas de teste (`halfOpenCalls`) passam, e o resultado delas decide se o breaker fecha ou abre de novo.
- **Id derivado.** O id da instância de política de um método quando a anotação não tem `name`: `<type>#<method>(<parameter types>)`, por exemplo `com.acme.CatalogClient#price(java.lang.String,int)`. Usado em chaves de configuração, eventos, métricas e handles.
- **Hedge.** Iniciar a mesma chamada de novo enquanto a primeira tentativa está lenta e ficar com a que tiver sucesso primeiro.
- **Instância.** Um objeto de política com o próprio estado (a janela de um breaker, os slots de um bulkhead). Métodos compartilham uma instância quando usam o mesmo `name`; caso contrário, cada método tem a sua.
- **Registry.** O `ResilienceRegistry`: guarda as configurações e cria as instâncias, uma vez cada. Toda classe gerada e todo `Pipeline` obtêm suas políticas de um.
- **Rejeição.** Uma chamada que uma política recusa antes de executá-la: um breaker aberto, um bulkhead ou adaptive limit cheio, um rate limiter esgotado. Lança uma `CallRejectedException`.
- **Exceção sem stack trace.** Uma exceção criada sem stack trace. As rejeições não têm stack trace e são pré-alocadas, então rejeitar uma chamada quase não custa nada; registre no log a mensagem ou o `id()` delas.
- **Código linear.** Código gerado que chama cada política diretamente, em ordem, no próprio método, em vez de passar por uma cadeia de lambdas compartilhados. É o que mantém uma chamada barata.
- **Verificação de ABI (`Abi.require`).** Uma chamada no início de todo construtor gerado: verifica que o `resilience4cata-core` no class path é pelo menos tão novo quanto o processor que gerou a classe, e falha com [`R4C-RT-021`](../errors.md#r4c-rt-021) se não for.
