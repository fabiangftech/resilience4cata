# Classificadores

Um classificador decide, chamada a chamada, se um resultado ou uma exceção é um sucesso, uma falha ou nenhum dos dois, para `@Retry` e `@CircuitBreaker`.

As listas de exceções (`retryOn`, `recordOn`) só conseguem olhar exceções. Muitos clientes reportam falhas no resultado: um cliente HTTP retorna uma resposta com status `503`, uma API de jobs retorna `Status.FAILED`. Um classificador cobre os dois casos, em uma única classe.

## Escrevendo um :id=writing-one

Um classificador implementa `dev.resilience4cata.Classifier<T>`, onde `T` é o tipo de resultado do método (ou o `T` do seu `CompletionStage<T>`):

<!-- snippet: classifiers-classifier -->
```java
import dev.resilience4cata.Classifier;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

public final class StatusClassifier implements Classifier<Response> {

    @Override
    public Outcome classify(@Nullable Response response, @Nullable Throwable failure) {
        if (failure != null) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
        }
        int status = response.status();
        if (status >= 500 || status == 429) {
            return Outcome.FAILURE;     // retried, and counted by the breaker
        }
        return status == 404 ? Outcome.IGNORE : Outcome.SUCCESS;
    }
}
```

`classify(result, failure)` recebe exatamente um argumento significativo: `failure` é `null` quando a chamada retornou normalmente (e `result` é `null` para um método `void` ou um resultado `null`). Ele retorna um `Outcome`:

| Outcome | `@Retry` | `@CircuitBreaker` |
|---|---|---|
| `SUCCESS` | Para e retorna o resultado (ou relança a exceção) | Registrado como sucesso (e como lento, se foi lento) |
| `FAILURE` | Tenta de novo, se restarem tentativas e tempo | Registrado como falha (e como lento, se foi lento) |
| `IGNORE` | Para e retorna o resultado (ou relança a exceção) | Não é registrado; uma sonda half-open devolve sua permissão |

Classificar uma exceção como `SUCCESS` ou `IGNORE` não a engole: a exceção ainda chega a quem chamou, apenas não é retentada, e o breaker a conta como sucesso ou não a conta.

## Usando-o :id=using-it

<!-- snippet: classifiers-usage -->
```java
@Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class)
@CircuitBreaker(name = "orders", classifier = StatusClassifier.class)
Response get(String orderId) throws IOException;
```

- A classe é nomeada na anotação e criada pelo código gerado: ela deve ser concreta, não genérica, de nível superior ou aninhada `static`, acessível, com um construtor sem argumentos, e implementar `Classifier<X>` com o resultado do método atribuível a `X`. O processador verifica tudo isso quando você compila ([`R4C-CLS-001`](../errors.md#r4c-cls-001) a [`R4C-CLS-010`](../errors.md#r4c-cls-010)).
- É criada uma instância por método. Quando `@Retry` e `@CircuitBreaker` nomeiam a mesma classe, eles compartilham essa instância.
- Um classificador **substitui** `retryOn` (em `@Retry`) ou `recordOn` (em `@CircuitBreaker`); definir os dois é um erro de compilação ([`R4C-CLS-011`](../errors.md#r4c-cls-011)). `abortOn` e `ignoreOn` continuam valendo, e vencem o classificador. O `CallNotPermittedException` de um breaker aberto sempre interrompe os retries, mesmo que o classificador retorne `FAILURE` para ele.
- Ele existe só na anotação: não há chave de configuração para ele. Em um `Pipeline`, passe uma instância (abaixo).
- Os métodos assíncronos são classificados da mesma forma, com o valor do stage ou sua falha desembrulhada.

O que o código gerado faz com ele (resumido):

<!-- not-compiled: código gerado, resumido -->
```java
public final class OrdersApi$Resilient implements OrdersApi {
    // …
    private final StatusClassifier retryClassifier0;   // one instance per method, shared by both policies
    // …
    @Override
    public Response get(java.lang.String p0) throws java.io.IOException {
        final dev.resilience4cata.generated.RetryPolicy r4c$retry = this.retry0;
        final StatusClassifier r4c$classifier = this.retryClassifier0;
        final long r4c$start = r4c$retry.start();
        int r4c$attempts = 0;
        while (true) {
            final Response r4c$result;
            try {
                r4c$result = this.r4c$circuitBreaker0(p0);
            } catch (dev.resilience4cata.generated.ClassifierFailure r4c$w) {
                // the breaker's classifier threw: end the call with its exception, never retry it
                try {
                    throw r4c$w.failure();
                } catch (java.lang.Throwable r4c$c) {
                    r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);
                    throw r4c$c;
                }
            } catch (java.lang.Throwable r4c$e) {
                // … classify(null, r4c$e), then retry or rethrow
                continue;
            }
            final dev.resilience4cata.Classifier.Outcome r4c$verdict;
            try {
                r4c$verdict = r4c$classifier.classify(r4c$result, null);
            } catch (java.lang.Throwable r4c$c) {
                r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);
                throw r4c$c;
            }
            if (r4c$verdict != dev.resilience4cata.Classifier.Outcome.FAILURE) {
                return r4c$result;
            }
            final long r4c$wait = r4c$retry.onFailedResult(++r4c$attempts, r4c$start);
            if (r4c$wait < 0 || !r4c$retry.pause(r4c$wait)) {
                return r4c$result; // rejected, but no retries left: the last result
            }
        }
    }
    // …
}
```

O classificador é chamado diretamente, sem alocação e sem leitura de relógio. Métodos sem classificador geram o mesmo código de antes; nada muda para eles.

## Quando os retries se esgotam com um resultado rejeitado :id=when-the-retries-run-out-on-a-rejected-result

Se a última tentativa ainda retornou um resultado `FAILURE`, não há exceção para relançar. `onExhaustedResult` decide o que acontece:

- `RETURN` (o padrão): o último resultado é retornado a quem chamou, como se não houvesse retry. `@Fallback` **não** roda, já que nada foi lançado.
- `THROW`: o retry lança `dev.resilience4cata.ResultRejectedException`, que vai para `@Fallback` como qualquer outra falha:

<!-- snippet: classifiers-exhausted -->
```java
@Retry(maxAttempts = 3, delay = "100ms", classifier = StatusClassifier.class,
        onExhaustedResult = ExhaustedResult.THROW)
@Fallback(method = "unavailable")
Response submit(String orderId) throws IOException;

default Response unavailable(String orderId, ResultRejectedException rejected) {
    Response last = rejected.result(Response.class);   // the last rejected result
    return new Response(503, "gave up after " + rejected.attempts() + " attempts: " + last.body());
}
```

`ResultRejectedException` carrega `id()`, `attempts()` e o último resultado, como `result()` (um `Object`) ou `result(Class<R>)` (tipado). `onExhaustedResult` precisa de um classificador ([`R4C-CLS-012`](../errors.md#r4c-cls-012)), é decidido em tempo de compilação, e não faz parte da configuração do retry: nenhuma chave de configuração pode mudá-lo. `maxDuration` se aplica normalmente: desistir porque o orçamento acabou com um resultado rejeitado se comporta da mesma forma.

## Um classificador que lança :id=a-classifier-that-throws

Um classificador não deveria lançar. Se lançar, sua exceção se torna a falha da chamada, igual para métodos síncronos e assíncronos:

- `@CircuitBreaker` registra a chamada como `FAILURE`, depois lança a exceção do classificador.
- `@Retry` para imediatamente, publica `RetryEvent.GaveUp` com o motivo `CLASSIFIER_FAILED` (com a exceção do classificador como `failure`, e as tentativas feitas até agora, incluindo esta), e a lança.
- `@Fallback`, se presente, a recebe.

Isso vale onde quer que o classificador rode. Quando o classificador do breaker lança, o retry em volta dele não classifica essa exceção como a falha do método, e não a retenta, seja o retry com o mesmo classificador, com outro ou sem nenhum: o breaker entrega a exceção ao retry em um invólucro interno (`dev.resilience4cata.generated.ClassifierFailure`, nunca visto por quem chama), e a chamada termina como acima. Quem chama, ou o fallback, recebe a própria exceção do classificador. Ainda assim, mantenha os classificadores totais: trate explicitamente resultados `null` e tipos de exceção inesperados.

## O mesmo com `Pipeline` :id=same-thing-with-pipeline

<!-- snippet: classifiers-pipeline -->
```java
Pipeline<Response> get = Pipeline.builder(registry, "orders-pipeline", Response.class)
        .retry(r -> r.maxAttempts(3))
        .circuitBreaker()
        .classifier(new StatusClassifier())              // both policies
        .onExhaustedResult(Pipeline.ExhaustedResult.THROW)
        .build();

Response response = get.execute(() -> orders.get("o-1"));
```

`classifier(...)` o define tanto para o retry quanto para o circuit breaker; `retryClassifier(...)` e `circuitBreakerClassifier(...)` definem um de cada. `onExhaustedResult(Pipeline.ExhaustedResult.THROW)` precisa de um classificador de retry. Sem a política correspondente, `build()` falha ([`R4C-RT-014`](../errors.md#r4c-rt-014) a [`R4C-RT-017`](../errors.md#r4c-rt-017)). Veja [API Pipeline](pipeline.md).

## Quanto custa :id=what-it-costs

> [!MEASURED]
> No AWS Graviton3 (`c7g.xlarge`, JDK 21), uma chamada bem-sucedida através de um circuit breaker com classificador custa 2.4 ns e não aloca nada, em 1 thread e em 4; o Resilience4j 2.4.0 com `recordResult` leva 102 ns e 24 B em 1 thread. Retry mais circuit breaker mais classificador: 2.7 ns, 0 B, contra 148 ns e 104 B. Um classificador adiciona cerca de 0.3 ns por política. [Benchmarks: conclusão do MVP](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)
