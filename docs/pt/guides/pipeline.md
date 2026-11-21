# API de Pipeline

`Pipeline` aplica as mesmas policies sem anotações, para código que você não pode anotar ou policies que você escolhe em tempo de execução.

## Quando usar :id=when-to-use-it

| Use anotações quando | Use um `Pipeline` quando |
|---|---|
| A interface ou a classe é sua | O código é uma classe de terceiros, uma lambda ou um script |
| As policies são conhecidas em tempo de compilação | As policies são escolhidas em tempo de execução |
| Você quer verificações em tempo de compilação de cada atributo e fallback | Verificações em tempo de execução, quando `build()` roda, bastam |
| Cada nanossegundo conta | Alguns nanossegundos a mais por chamada não são problema |

## Um primeiro pipeline :id=a-first-pipeline

Construa um pipeline uma vez, a partir do registry, e reutilize-o. Ele é imutável e thread-safe.

<!-- snippet: pipeline-minimal -->
```java
Pipeline<String> geocode = Pipeline.builder(registry, "geocoder", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(1)))
        .fallback(failure -> "unknown")
        .build();

String city = geocode.execute(() -> legacyGeocoder.city("10115"));   // "Berlin", after one retry
```

`execute` executa a chamada através das policies e declara a exceção checada da chamada (aqui `IOException`), então você trata o que a própria chamada lança. Quando nada se recupera, ele lança a última falha da chamada ou uma rejeição: `CallNotPermittedException`, `RateLimitExceededException`, `CallTimeoutException` ou `BulkheadFullException`.

## Cada passo :id=every-step

<!-- snippet: pipeline-full -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retry("external", r -> r.maxAttempts(4))
        .rateLimiter("external", rl -> rl.permits(100))
        .build();

Pipeline<Receipt> charge = Pipeline.builder(registry, "payments", Receipt.class)
        .retry("external")                                   // named config
        .circuitBreaker(cb -> cb.failureRateThreshold(25))   // defaults + overrides
        .rateLimiter("external", rl -> rl.permits(50))       // named config + overrides
        .timeout(t -> t.timeout(Duration.ofSeconds(2)))
        .bulkhead()                                          // defaults
        .fallbackHandler(context -> Receipt.pending(context.attempts()))
        .build();

Receipt receipt = charge.execute(() -> client.charge(order));            // throws what charge throws
CompletableFuture<Receipt> later = charge.executeAsync(() -> client.chargeAsync(order));
charge.run(() -> client.cancel(order));                                  // a call with no result
```

- **Policies:** `retry`, `hedge`, `circuitBreaker`, `rateLimiter`, `adaptiveLimit`, `timeout` e `bulkhead`, cada uma no máximo uma vez ([`R4C-RT-012`](../errors.md#r4c-rt-012)). Cada uma recebe nenhum argumento (os padrões), uma config nomeada, um `Consumer` do builder de config da policy (sobrescritas sobre os padrões) ou ambos (sobrescritas sobre a config nomeada). Os builders têm os mesmos atributos que as anotações; por exemplo, a duração do timeout é `t.timeout(Duration)`.
- **Ordem:** as policies se aninham como com anotações, `Fallback → Retry | Hedge → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → call`, qualquer que seja a ordem em que você chama os métodos do builder. Cada tentativa do retry passa pelo breaker e tem seu próprio timeout.
- **Hedge:** `hedge(h -> h.delay(Duration.ofMillis(50)))` inicia a chamada de novo enquanto a primeira tentativa está lenta e fica com o primeiro sucesso ([Hedge](../policies/hedge.md)); apenas para chamadas idempotentes, e apenas com `executeAsync`: `execute` e `run` em um pipeline com hedge lançam [`R4C-RT-018`](../errors.md#r4c-rt-018). Um pipeline não pode ter ao mesmo tempo um retry e um hedge ([`R4C-RT-019`](../errors.md#r4c-rt-019)).
- **Fallback:** `fallback(failure -> value)` ou `fallbackHandler(context -> value)`; o `FallbackContext` do handler fornece a falha e `attempts()` (ele não tem argumentos: `argumentCount()` é 0). `fallbackApplyOn(List.of(...))` e `fallbackSkipOn(List.of(...))` escolhem as falhas que ele trata; sem um fallback, são um erro ([`R4C-RT-013`](../errors.md#r4c-rt-013)).
- **Fallback assíncrono:** `fallbackAsync(...)` e `fallbackAsyncHandler(...)` devolvem um stage, para `executeAsync`. Com os dois tipos, cada modo usa o seu; com apenas um fallback assíncrono, `execute` e `run` esperam o stage dele.
- **Classifiers:** `classifier(...)` para o retry e o breaker juntos, ou `retryClassifier(...)` e `circuitBreakerClassifier(...)` separadamente, além de `onExhaustedResult(Pipeline.ExhaustedResult.THROW)`; veja [classifiers](classifiers.md).

Três formas de chamar:

| Método | Para |
|---|---|
| `T execute(PipelineCall<? extends T, X>) throws X` | Uma chamada que devolve um valor |
| `void run(PipelineRunnable<X>) throws X` | Uma chamada sem resultado (um classifier vê `null`; o resultado de um fallback é descartado) |
| `CompletableFuture<T> executeAsync(PipelineCall<? extends CompletionStage<T>, ?>)` | Uma chamada que devolve um stage: a forma não bloqueante de cada policy, como nos [métodos assíncronos](async.md) |

`build()` verifica combinações que não podem funcionar: um classifier sem retry nem breaker ([`R4C-RT-014`](../errors.md#r4c-rt-014)), um classifier de retry sem retry ([`R4C-RT-015`](../errors.md#r4c-rt-015)), `onExhaustedResult` sem classifier de retry ([`R4C-RT-016`](../errors.md#r4c-rt-016)), um classifier de breaker sem breaker ([`R4C-RT-017`](../errors.md#r4c-rt-017)), um retry e um hedge juntos ([`R4C-RT-019`](../errors.md#r4c-rt-019)). Um id em branco é [`R4C-RT-011`](../errors.md#r4c-rt-011).

## O id é o id da instância :id=the-id-is-the-instance-id

O id passado a `Pipeline.builder` nomeia as instâncias de policies do pipeline, como o atributo `name` faz nas anotações:

<!-- snippet: pipeline-handle -->
```java
Pipeline<String> search = Pipeline.builder(registry, "search", String.class)
        .circuitBreaker(cb -> cb.windowSize(2).minimumCalls(2))
        .build();

CircuitBreakerHandle breaker = registry.circuitBreaker("search");   // the pipeline's id is the instance id
breaker.forceOpen();                                                // every call is now rejected
```

Assim, `registry.circuitBreaker(id)`, `rateLimiter(id)` e `bulkhead(id)` as encontram, os eventos levam o id como `id()`, e a configuração externa as reajusta com `instances.<id>.*`. Pipelines construídos com o mesmo id compartilham suas instâncias, e o mesmo vale para anotações cujo `name` é esse id; todos devem resolver para a mesma configuração, ou `build()` falha com [`R4C-REG-008`](../errors.md#r4c-reg-008).

Um fallback cuja função lança uma exceção checada que a chamada não declara chega ao chamador envolvido em `UndeclaredThrowableException`.

## Quanto custa :id=what-it-costs

O caminho síncrono de sucesso não aloca nada além da sua própria lambda. Porém, uma única classe `Pipeline` atende a todos os pontos de chamada, então, em uma aplicação com muitos pipelines, sua única chamada à sua lambda se torna megamórfica e mais lenta; o código gerado tem um ponto de chamada por método e não tem esse custo.

> [!MEASURED]
> Retry mais circuit breaker em uma thread: 2.9 ns com um `Pipeline`, 2.3 ns com anotações, 175 ns e 288 B com Resilience4j 2.4.0. Depois que outros cinco pipelines rodaram na mesma JVM, o pipeline mede 7.9 ns (AWS Graviton3 `c7g.xlarge`). [Benchmarks](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3)

Onde você puder anotar, as anotações continuam sendo a opção mais rápida.
