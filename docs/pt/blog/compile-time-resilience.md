# Um circuit breaker de 1.9 ns: resiliência gerada em tempo de compilação

A maioria das bibliotecas de resiliência em Java envolve cada chamada em decorators em tempo de execução; o resilience4cata escreve esse código em tempo de compilação, e um circuit breaker saudável acaba custando uns dois nanossegundos.

*27 de outubro de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor do resilience4cata*

## O problema dos decorators :id=the-problem-with-decorators

Retries, circuit breakers, timeouts e bulkheads costumam ficar no caminho da chamada como decorators em tempo de execução. Você envolve um `Supplier` ou um `Callable` em uma função por policy, ou deixa um framework colocar na frente do seu bean um proxy que faz isso por você. Funciona, é flexível, e é assim que o Resilience4j, a biblioteca que muitas equipes Java usam, é construído.

Também custa algo em cada chamada, incluindo as que dão certo, que são quase todas. Cada camada é mais uma chamada por uma interface funcional, e o estado da policy por trás dela é compartilhado por todas as threads. Em um benchmark com um único método decorado, o JIT vê um só tipo em cada call site e consegue fazer inline de boa parte disso, que é o melhor caso. Um serviço real decora dezenas de métodos, e esses call sites compartilhados deixam de ser monomórficos.

Eu queria saber quão baratas as mesmas garantias poderiam ser se o código fosse escrito para um método de cada vez, do jeito que você escreveria à mão se tivesse paciência.

## Deixe o compilador escrever :id=let-the-compiler-write-it

O resilience4cata é um conjunto de anotações mais um annotation processor. Você anota uma interface:

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
```
<!-- compare:end -->

Em tempo de compilação, o processor escreve `StockClient$Resilient`, uma classe comum que implementa a interface. Cada método protegido vira código linear: um laço para o retry, um bloco `try` para o breaker, um campo `final` por policy. Não há lambdas, proxies nem reflection no caminho da chamada, e todo call site é monomórfico porque cada método tem o seu. Em tempo de execução, você envolve a sua implementação uma vez:

<!-- not-compiled: um exemplo de uso de uma linha; o quickstart tem o programa completo e testado -->
```java
StockClient client = Resilient.of(StockClient.class, new HttpStockClient(), registry);
```

As policies por trás desses campos seguem um protocolo pequeno (`acquire`, `onSuccess`, `onFailure`), pensado para que o caminho de sucesso faça o mínimo possível:

- **Nenhuma alocação.** As rejeições lançam uma exceção pré-alocada e sem stack trace por policy, e os eventos só são criados quando alguém escuta.
- **Nenhuma leitura de relógio, a menos que um recurso precise.** O breaker só mede o tempo das chamadas quando você configura um limite de chamada lenta.
- **Nenhuma escrita compartilhada quando nada muda.** Enquanto um breaker está fechado e só viu sucessos rápidos, uma chamada lê um campo e não escreve nada.

Essa última regra foi a que mais fez diferença. Sob contenção, escrever em uma linha de cache que outros núcleos estão lendo custa muito mais do que qualquer truque lock-free economiza. Em um Apple M1 com quatro threads, a primeira versão do breaker custava 244 ns por chamada bem-sucedida; pular as escritas enquanto a janela só tem sucessos rápidos levou isso a 10 ns, e ler um único campo enquanto saudável, a cerca de 3 ns ([como o caminho de sucesso chegou lá](../benchmarks.md#circuit-breaker-1-and-4-threads-2026-10-23)).

## Os números :id=the-numbers

Toda comparação usa configuração equivalente nas duas bibliotecas, e cada ajuste feito por justiça está escrito no Javadoc do benchmark.

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, resilience4cata 0.1.0 vs Resilience4j 2.4.0, tempo e bytes alocados por chamada. De [MVP completion (2026-10-24, AWS Graviton3)](../benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3).

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Retry, a primeira tentativa dá certo | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Retry, falha uma vez e depois dá certo | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, toda chamada dá certo | 1 | **1.9 ns**, 0 B | 89.2 ns, 0 B |
| Circuit breaker, toda chamada dá certo | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Bulkhead, cheio (toda chamada rejeitada) | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Bulkhead, vagas livres | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |
| Retry + circuit breaker | 1 | **2.3 ns**, 0 B | 175 ns, 288 B |

A tabela também mostra onde a abordagem não ajuda:

- **Alguns casos empatam.** Um bulkhead com vagas livres pega e devolve uma permissão com uma operação atômica nas duas bibliotecas: 20.5 ns contra 20.4 ns.
- **A contenção ainda custa centenas de nanossegundos.** Com quatro threads e uma chamada em cada dez falhando, o breaker custa 274 ns (Resilience4j: 910 ns), porque toda chamada avança um cursor de janela compartilhado. Contadores compartilhados custam ao bulkhead e ao rate limiter da mesma forma.
- **Chamadas assíncronas alocam.** Toda camada assíncrona retorna um novo future nas duas bibliotecas; só os caminhos síncronos são livres de alocação.
- **Algumas linhas não comparam igual com igual.** O retry do Resilience4j chama `Thread.sleep(0)` entre as tentativas, então a linha dele usa uma variante sem isso (o melhor caso dele). O `TimeLimiter` dele precisa de um `Future`, então um timeout síncrono significa outra thread; essa comparação está na [página de desempenho](../performance.md#headline), com essa ressalva.

Um nanossegundo importa para uma chamada que passa pela rede? Raramente, sozinho. Importa quando a resiliência envolve chamadas que costumam ser baratas (um cache, um serviço local, uma fila em memória), quando o mesmo código roda milhões de vezes por segundo, ou quando você prefere não ter que pensar se uma policy é cara demais para adicionar.

## O que isso custa para você :id=what-it-costs-you

A geração em tempo de compilação tem trade-offs, e eles são deliberados:

- **O annotation processor é obrigatório.** Não há fallback por reflection: se a classe gerada estiver faltando, `Resilient.of` falha na hora e diz qual dependência adicionar.
- **Erros são erros de compilação.** Uma duração inválida, um atributo desconhecido ou um fallback com a assinatura errada quebra o build com um código como `R4C-ATR-001`, documentado na [referência de erros](../errors.md).
- **A configuração é resolvida uma vez.** Os valores podem vir de anotações, arquivos de propriedades, variáveis de ambiente ou da configuração do Spring, Micronaut e Quarkus, mas são fixados quando o registry é construído: não há hot reload, por [design](../design.md).
- **Ele é jovem.** A versão atual é a 0.8; até a 1.0 a API ainda pode mudar.

## Experimente :id=try-it

Duas linhas no Gradle:

<!-- not-compiled: configuração de build -->
```groovy
implementation 'dev.resilience4cata:resilience4cata-all:0.8.0'
annotationProcessor 'dev.resilience4cata:resilience4cata-processor:0.8.0'
```

Depois, o [quickstart](../start/quickstart.md) protege uma chamada instável em uns cinco minutos. Há integrações para [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) e [Quarkus](../guides/quarkus.md), um [testkit](../guides/testing.md) com relógio manual e um [guia para migrar do Resilience4j](../migrate/from-resilience4j.md). O código tem licença Apache-2.0 no [GitHub](https://github.com/fabiangftech/resilience4cata); issues e discussões são bem-vindas.
