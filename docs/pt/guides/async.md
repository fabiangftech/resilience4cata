# Métodos assíncronos

Um método que retorna `CompletionStage<T>` ou `CompletableFuture<T>` recebe a forma não bloqueante de cada política: nenhuma thread dorme, espera ou fica estacionada em seu nome.

Não existe anotação `@Asynchronous`: o tipo de retorno decide. As mesmas anotações, atributos e chaves de configuração se aplicam.

## Uma interface assíncrona :id=an-asynchronous-interface

<!-- snippet: async-interface -->
```java
public interface TrackingClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @Fallback(method = "unknown")
    CompletableFuture<String> track(String parcel);

    default CompletionStage<String> unknown(String parcel, IOException failure) {
        return CompletableFuture.completedFuture("unknown (" + failure.getMessage() + ")");
    }

    @Timeout("100ms")
    CompletionStage<String> eta(String parcel);
}
```

<!-- snippet: async-call -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
TrackingClient tracking = Resilient.of(TrackingClient.class, http, registry);

CompletableFuture<String> status = tracking.track("p-1");   // returns at once: no thread sleeps between attempts
// status completes with "in transit" after two failed attempts
```

A chamada retorna um future imediatamente. A primeira tentativa roda na thread de quem chama; quando ela falha, o retry agenda a próxima tentativa após seu atraso em um timer, e a tentativa então começa em uma nova virtual thread. Nenhuma thread fica bloqueada durante o atraso.

## O que muda em relação a um método síncrono :id=what-changes-compared-with-a-synchronous-method

| Política | Método síncrono | Método assíncrono |
|---|---|---|
| Retry | Dorme na thread de quem chama entre as tentativas | Agenda a próxima tentativa; o future retornado se completa quando uma tentativa tem sucesso ou o retry desiste |
| [Hedge](../policies/hedge.md) | Não suportado (erro de compilação [`R4C-HDG-001`](../errors.md#r4c-hdg-001)) | Inicia a chamada de novo após `delay` sem resultado, ou imediatamente após uma falha; o primeiro sucesso completa o future e as outras tentativas são canceladas |
| Circuit breaker | Registra a chamada quando ela retorna ou lança | Registra a chamada quando seu stage se completa |
| Rate limiter com `maxWait` | Dorme até a permissão estar disponível | Inicia a chamada quando a permissão está disponível, sem bloquear quem chama |
| [Adaptive limit](../policies/adaptive-limit.md) | Mantém a vaga enquanto o método roda, e mede sua latência | Mantém a vaga até o stage se completar, e mede a latência até essa conclusão |
| Timeout | Interrompe a thread de quem chama no prazo | Falha o future retornado com `CallTimeoutException` no prazo, e cancela o stage atrasado se ele for um `Future` (através das políticas internas, que liberam imediatamente: veja [cancelamento](#cancellation)) |
| Bulkhead | Mantém a vaga enquanto o método roda; com `maxWait`, espera em um semáforo | Mantém a vaga até o stage se completar; com `maxWait`, enfileira a chamada sem bloquear uma thread |
| Fallback | Retorna um valor | Retorna um valor ou um stage |

Regras que valem para todas as políticas:

- **Um método que lança em vez de retornar um stage** é tratado como um stage que falhou: as políticas veem a exceção e, se nada se recuperar, o future retornado falha com ela. Um método que retorna `null` em vez de um stage falha com um `NullPointerException` da mesma forma.
- **As falhas são desembrulhadas.** Um stage que falha com `CompletionException` ou `ExecutionException` é julgado pela sua causa, então `retryOn`, `recordOn`, os classificadores e as assinaturas de fallback veem a sua exceção (`IOException` no exemplo), não o invólucro.
- **As rejeições falham o future.** Um breaker aberto, um bulkhead cheio, um adaptive limit cheio ou um rate limiter esgotado falham o future retornado com `CallNotPermittedException`, `BulkheadFullException`, `AdaptiveLimitFullException` ou `RateLimitExceededException`.
- **Os callbacks não rodam no timer.** Retries atrasados, hedges, esperas do rate limiter e timeouts disparam em um timer da biblioteca, que entrega o trabalho a uma nova virtual thread; o mesmo acontece com uma chamada enfileirada no bulkhead quando ela obtém sua vaga. Essa thread tem o context class loader de quem chamou (o da thread que fez a chamada) e nenhum `ThreadLocal`, MDC ou valor thread-local herdado: nunca os de outra requisição. Passe explicitamente o que uma tentativa precisa (para métodos `Mono` e `Flux`, o contexto do Reactor chega a todas as tentativas).

Uma chamada que estourou o timeout, do mesmo teste:

<!-- snippet: async-timeout -->
```java
CompletableFuture<String> eta = tracking.eta("p-1").toCompletableFuture();

assertThatThrownBy(() -> eta.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(CallTimeoutException.class);   // after 100 ms; no thread was blocked
```

Quando você mesmo faz `join()` ou `get()` no future, o JDK embrulha a falha: `join()` lança `CompletionException` e `get()` lança `ExecutionException`, com a exceção da política como causa.

## Cancelamento :id=cancellation

Cancelar o future que um método assíncrono retorna (com `cancel(...)`, ou `completeExceptionally` com um `CancellationException`) cancela a chamada dentro dele, através de todas as camadas: cada política repassa o cancelamento ao stage que está esperando, até o stage que o seu método retornou, que é cancelado se for um `Future`. O que cada política faz:

| Política | Ao cancelar |
|---|---|
| Retry | Não inicia mais nenhuma tentativa: um backoff pendente é cancelado, e a tentativa em andamento é cancelada |
| Hedge | Não inicia mais nenhuma tentativa: o atraso pendente é cancelado, e todas as tentativas em andamento são canceladas. Um hedge também cancela seus próprios perdedores da mesma forma, com um `HedgeCancelledException` |
| Circuit breaker | Não registra nada, como uma chamada ignorada: uma permissão half-open é devolvida |
| Rate limiter com `maxWait` | Uma chamada que ainda espera sua permissão nunca começa, e a permissão é devolvida (como quando uma espera síncrona é interrompida). Uma chamada que já começou mantém sua permissão |
| Adaptive limit | Libera a vaga assim que o stage termina, sem amostra: uma chamada cancelada não diz nada sobre a dependência |
| Timeout | Cancela seu prazo: nenhum `CallTimeoutException`, nenhum evento `TimedOut` |
| Bulkhead | Libera a vaga assim que o stage termina, o que para um `CompletableFuture` é imediato. Uma chamada ainda enfileirada (`maxWait`) sai da fila e nunca começa |
| Fallback | Não roda: ninguém espera um resultado alternativo |

Um cancelamento não publica nenhum evento: nenhum `GaveUp`, nenhum `Retry`, nenhuma falha, nenhuma rejeição. As camadas internas já liberaram tudo antes que os callbacks que você anexou ao future rodem, então um callback pode iniciar uma nova chamada na vaga que foi liberada.

Coisas a saber:

- **O cancelamento chega ao stage, não ao trabalho por trás dele.** Cancelar um `CompletableFuture` o completa; não interrompe o código que o teria completado. O bulkhead libera sua vaga quando o stage termina, então, se o seu stage não for um `Future`, ou ignorar `cancel`, a vaga continua ocupada (e o breaker registra o resultado tardio) até o stage se completar.
- **Cancele o future que o método retornou.** Um future derivado dele (`thenApply`, `whenComplete`, `copy()` e afins) é um novo `CompletableFuture`: cancelá-lo não chega à chamada, como no JDK.
- **Cancelamento e conclusão competem com segurança.** Se a chamada se completar no mesmo momento, vence o que vier primeiro; vagas e permissões são liberadas exatamente uma vez em qualquer caso.
- `Pipeline.executeAsync` se comporta da mesma forma.

## Fallbacks

Um fallback irmão de um método assíncrono retorna o tipo de valor `T` ou um `CompletionStage<T>` (no exemplo, `unknown` retorna um stage). Uma classe handler implementa `FallbackHandler<T>` ou `FallbackHandler<CompletionStage<T>>`. O fallback roda depois que o retry desiste, e também para rejeições e timeouts, como em sua forma síncrona. Veja [fallback](../policies/fallback.md).

## Stages com curinga :id=wildcard-stages

O tipo de retorno pode ser `CompletionStage<? extends X>` ou `CompletableFuture<? extends X>`; `?` sozinho significa `? extends Object`. As políticas então trabalham com `X`: os fallbacks retornam um `X` (ou um stage de um), e os classificadores devem aceitar `X`.

<!-- snippet: async-wildcard -->
```java
public interface ParcelClient {

    @Retry(maxAttempts = 2, delay = "10ms")
    @Fallback(method = "noWeight")
    CompletionStage<? extends Number> weight(String parcel);

    default Integer noWeight(String parcel, Exception failure) {
        return 0;
    }
}
```

## O que não é suportado :id=what-is-not-supported

- **Outros tipos de stage são erros de compilação** ([`R4C-ASY-001`](../errors.md#r4c-asy-001)): `CompletionStage` raw, `? super X`, e subtipos de `CompletionStage` que não sejam `CompletableFuture`.
- **O `Mono` e o `Flux` do Reactor são suportados** com `resilience4cata-reactor`: veja [Project Reactor](reactor.md). **Outros tipos reativos são erros de compilação** ([`R4C-ASY-002`](../errors.md#r4c-asy-002)): `Flow.Publisher` e outros `Publisher`s de Reactive Streams (o `Flowable` do RxJava), o `Uni` e o `Multi` do Mutiny, e os tipos do RxJava. Eles apenas constroem um pipeline que roda quando alguém se inscreve, então as políticas protegeriam a construção dele, não o trabalho. Converta para um `CompletionStage` na implementação (`uni.subscribeAsCompletionStage()`), ou para um `Mono` ou um `Flux`.
- **Outros tipos assíncronos são tratados como síncronos.** Um método que retorna `java.util.concurrent.Future` ou qualquer outra coisa é protegido como qualquer método síncrono: as políticas veem apenas a chamada que *retorna* o objeto, não o trabalho que ele representa.

> [!WARNING]
> Se um método retorna um `Future`, um timeout ou um retry não se aplica ao resultado. Retorne um `CompletableFuture` em vez disso, ou embrulhe o trabalho em um.

## Custo :id=cost

As chamadas assíncronas alocam: cada camada cria futures e passa por callbacks de `CompletableFuture`, que é o preço de não bloquear threads. As chamadas síncronas não são afetadas.

> [!MEASURED]
> Um timeout assíncrono com um stage pendente custa 152 ns e 192 B em uma thread, contra 598 ns e 240 B do Resilience4j 2.4.0 (AWS Graviton3 `c7g.xlarge`). [Benchmarks](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)

## Veja também :id=see-also

- `Pipeline.executeAsync(...)` oferece o mesmo comportamento sem anotações ([Pipeline](pipeline.md)).
- Em um `ManualClock`, os atrasos e timeouts assíncronos rodam na thread que avança o relógio ([testes](testing.md)).
