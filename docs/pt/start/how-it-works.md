# Como funciona

Um processador de anotações transforma suas anotações em Java simples em tempo de compilação; em tempo de execução um `ResilienceRegistry` dá a esse código sua configuração e seu estado.

## O que o processador gera :id=what-the-processor-generates

Para cada interface que usa uma anotação de resiliência, o processador escreve uma classe chamada `<Interface>$Resilient` no mesmo pacote. Ela implementa a interface, guarda um campo `final` de política por método protegido e chama sua implementação através das políticas como código em linha reta: um loop para o retry, um bloco `try` para o breaker, sem lambdas, sem proxies, sem reflection no caminho da chamada.

<!-- compare:start -->
<!-- snippet: how-it-works-interface -->
```java
public interface StockClient {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    int stock(String sku, int warehouse);
}
```
<!-- not-compiled: código gerado, resumido (nomes de pacote encurtados) -->
```java
public final class StockClient$Resilient implements StockClient {

    private final StockClient delegate;
    private final RetryPolicy retry0;
    private final CircuitBreakerPolicy circuitBreaker0;

    public StockClient$Resilient(StockClient delegate, ResilienceRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        // …
        Abi.require(2, StockClient$Resilient.class); // fails at once on a core older than the processor
        this.retry0 = Policies.retry(registry, "….StockClient#stock(java.lang.String,int)", "", "", r4c$b -> {
            r4c$b.maxAttempts(3);
        }, false);
        this.circuitBreaker0 = Policies.circuitBreaker(registry, "….StockClient#stock(java.lang.String,int)", "", "", null, false);
    }

    @Override
    public int stock(String p0, int p1) {
        final RetryPolicy r4c$retry = this.retry0;
        final long r4c$start = r4c$retry.start();
        int r4c$attempts = 0;
        while (true) {
            try {
                return this.r4c$circuitBreaker0(p0, p1);
            } catch (Throwable r4c$e) {
                final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);
                if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {
                    throw r4c$e;
                }
            }
        }
    }

    private int r4c$circuitBreaker0(String p0, int p1) {
        final CircuitBreakerPolicy r4c$cb = this.circuitBreaker0;
        final long r4c$token = r4c$cb.acquire();
        if (r4c$token < 0) {
            throw r4c$cb.rejection();
        }
        final long r4c$started = r4c$cb.startTimer();
        try {
            final int r4c$result = this.delegate.stock(p0, p1);
            r4c$cb.onSuccess(r4c$token, r4c$started);
            return r4c$result;
        } catch (Throwable r4c$e) {
            r4c$cb.onFailure(r4c$token, r4c$started, r4c$e);
            throw r4c$e;
        }
    }
}
```
<!-- compare:end -->

O que decorre desse design:

- **Erros são erros de compilação.** Atributos desconhecidos, durações inválidas, um fallback com a assinatura errada, uma anotação em um método `static` ou `private`: o build falha com um código como `[R4C-ATR-001]`, que tem sua própria seção na [referência de erros](../errors.md).
- **O processador é obrigatório.** Se a classe gerada estiver faltando, `Resilient.of` falha na hora com [`R4C-RT-003`](../errors.md#r4c-rt-003), indicando a dependência a adicionar. Não há alternativa com reflection.
- **O código gerado só pode chamar a API do `core`**, então o `core` precisa ser pelo menos tão novo quanto o `processor` que compilou o código, dentro da mesma versão major: código gerado pelo processor `1.a` roda no core `1.b` para todo `b >= a`. Em um core mais antigo, o construtor gerado falha na hora com [`R4C-RT-021`](../errors.md#r4c-rt-021); o BOM mantém as versões alinhadas.
- **O wrapper é thread-safe.** Crie-o uma vez e compartilhe-o, como o registry.

> [!MEASURED]
> Um retry cuja primeira tentativa tem sucesso custa 1.4 ns e não aloca nada, contra 22.9 ns do Resilience4j 2.4.0 (AWS Graviton3 `c7g.xlarge`, uma thread, o código da 1.0.0-RC1). [Benchmarks: a release candidate](../benchmarks.md#release-candidate)

## Dois estilos, mais uma API programática :id=two-styles-plus-a-programmatic-api

| Estilo | Você escreve | Você obtém com | Autoinvocação (`this.other()`) |
|---|---|---|---|
| **Interface anotada** | Anotações em uma interface; qualquer implementação | `Resilient.of(Type.class, impl, registry)` | Não interceptada: as chamadas da implementação a si mesma pulam as políticas |
| **[Classe anotada](../guides/annotated-classes.md)** | Anotações em uma classe não final | `Resilient.create(Type.class, registry, constructorArgs...)` | Interceptada: a subclasse gerada sobrescreve o método |
| **[`Pipeline`](../guides/pipeline.md)** | Sem anotações: um builder | `Pipeline.builder(registry, id, Type.class)…build()` | n/a |

No estilo de interface só contam as anotações da interface. Uma classe que implementa uma interface anotada mas não declara nenhuma anotação ela mesma não é processada; envolva-a com `Resilient.of`. Uma classe que declara anotações é processada no estilo de classe.

## O registry e os ids de instância :id=the-registry-and-instance-ids

`ResilienceRegistry` guarda as configurações (padrões, configs nomeadas, sobrescritas por instância), os listeners de eventos e cada instância de política com estado. Construa-o uma vez com `ResilienceRegistry.builder()…build()`; depois disso ele é imutável.

Cada método protegido recebe suas próprias instâncias de política, sob um **id de instância**:

- o atributo `name` da política, se tiver um (`@CircuitBreaker(name = "prices")` → `prices`); métodos, pipelines e wrappers que usam o mesmo nome compartilham uma instância (uma janela de breaker, um conjunto de permissões), e precisam resolver para a mesma configuração ([`R4C-REG-008`](../errors.md#r4c-reg-008) caso contrário);
- caso contrário, o id derivado `<qualified type>#<method>(<parameter types>)`, com tipos de parâmetro totalmente qualificados e apagados (erasure), separados por vírgulas sem espaços: `com.acme.StockClient#stock(java.lang.String,int)`. Tipos aninhados usam `.` (`com.acme.Repository.Cache#get(java.lang.String)`). Para uma classe anotada, o tipo é a classe.

`@Timeout` não tem `name`: seu id é sempre o derivado. `@Fallback` não guarda estado e não tem handle nem configuração.

O id é como você encontra uma instância em tempo de execução (`registry.circuitBreaker(id)`, `rateLimiter(id)`, `bulkhead(id)`), como eventos e snapshots a nomeiam e como a configuração externa a reajusta (`instances.<id>.…`). Este teste envolve `StockClient` e busca seu breaker pelo id derivado:

<!-- snippet: how-it-works-ids -->
```java
AtomicInteger calls = new AtomicInteger();
StockClient stock = Resilient.of(StockClient.class, (sku, warehouse) -> {
    if (calls.incrementAndGet() == 1) {
        throw new IllegalStateException("warehouse busy");
    }
    return 7;
}, registry);

int count = stock.stock("sku-1", 3);   // the first attempt fails, the retry succeeds

CircuitBreakerSnapshot breaker = registry.circuitBreaker(
        "dev.resilience4cata.examples.docs.howitworks.StockClient#stock(java.lang.String,int)").snapshot();
// breaker.bufferedCalls() == 2 and breaker.failedCalls() == 1: the breaker saw both attempts
```

## De onde vem a configuração :id=where-configuration-comes-from

Todo atributo de anotação tem um valor "não definido", então uma anotação só sobrescreve o que escreve; os padrões reais ficam no registry. Os valores se resolvem nesta ordem, da menor precedência para a maior:

1. Padrões embutidos (para `@Retry`: 3 tentativas, backoff exponencial de 200 ms com full jitter; cada [página de política](../policies/README.md) os lista).
2. Padrões do registry: `retryDefaults(...)` e companhia, ou `defaults.<policy>.*` na [configuração externa](../guides/configuration.md).
3. A config nomeada escolhida com `config = "…"`: registrada em código com `retry("external", ...)`, externamente com `configs.external.*`.
4. As anotações: bundles abaixo das anotações diretas, o tipo abaixo do método ([bundles](../guides/bundles.md)).
5. Sobrescritas por instância: `retryInstance(id, ...)` e companhia, ou `instances.<id>.*` externamente.

Tudo é resolvido **uma vez**, quando o wrapper, a subclasse ou o pipeline é criado, em registros imutáveis; as chamadas nunca consultam a configuração. Não há hot reload: mudar um valor significa construir um novo registry, na prática reiniciar a aplicação.

<!-- snippet: how-it-works-registry -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryDefaults(r -> r.delay(Duration.ofMillis(50)))                     // every retry
        .circuitBreakerInstance(
                "dev.resilience4cata.examples.docs.howitworks.StockClient#stock(java.lang.String,int)",
                cb -> cb.minimumCalls(20))                                     // one instance
        .build();
StockClient stock = Resilient.of(StockClient.class, (sku, warehouse) -> 7, registry);   // resolved here
```

Um nome de config desconhecido falha nesse momento ([`R4C-REG-004`](../errors.md#r4c-reg-004)), não na primeira chamada.

## Ordem das políticas :id=policy-order

Seja qual for a ordem em que você escreve as anotações, as políticas se aninham nesta ordem por padrão, da mais externa para dentro ([`@PolicyOrder`](../policies/README.md#changing-the-order) a muda):

```mermaid
flowchart LR
    caller([quem chama]) --> F[Fallback] --> R[Retry | Hedge] --> CB[CircuitBreaker] --> RL[RateLimiter] --> AL[AdaptiveLimit] --> T[Timeout] --> B[Bulkhead] --> M([seu método])
```

- **O fallback é o mais externo**, então vê o resultado final, incluindo rejeições como um breaker aberto ou um bulkhead cheio.
- **Cada tentativa do retry passa pelo breaker**, que a registra (o exemplo acima mostra duas chamadas registradas para uma chamada com retry), e um breaker aberto interrompe os retries: `@Retry` nunca repete uma `CallNotPermittedException`.
- **Chamadas rejeitadas pelo breaker não usam permissões do rate limiter.**
- **O timeout se aplica a cada tentativa**; `@Retry(maxDuration = …)` limita a operação inteira.
- **A vaga do bulkhead fica ocupada só enquanto o método roda**, não durante o backoff do retry.

A ordem acima é a padrão; `@PolicyOrder` a muda por método, tipo ou bundle (o fallback continua o mais externo). A [visão geral das políticas](../policies/README.md) explica como as políticas interagem.
