# Quanto custa uma anotação de resiliência no Spring, Micronaut e Quarkus

Em cada um dos três frameworks, um bean protegido custa o que a sua policy custa, alguns nanossegundos; as mesmas anotações passando pelos interceptors habituais de cada framework custam de dezenas de nanossegundos a vários microssegundos por chamada.

*1 de novembro de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor do resilience4cata*

## A pergunta :id=the-question

O [primeiro artigo](compile-time-resilience.md) comparou policies chamadas diretamente: uma classe gerada contra os decorators do Resilience4j. Poucas aplicações chamam uma policy diretamente. Elas colocam uma anotação em um método de um bean e deixam o framework fazer o resto: o Spring por meio de um aspect, o Micronaut por meio de um interceptor, o Quarkus por meio de um interceptor CDI como o do SmallRye Fault Tolerance. Essa maquinaria roda em cada chamada, incluindo as bem-sucedidas, então eu queria medir o que uma aplicação realmente recebe.

## Como foi medido :id=how-it-was-measured

Os benchmarks sobem um contexto de aplicação real e chamam o bean que o framework injeta, a partir de uma e de quatro threads:

- **Spring Boot 4.1.2**: os aspects do resilience4j-spring-boot4 2.4.0 contra a subclasse gerada do resilience4cata, que a integração com Spring registra como o bean.
- **Micronaut 5.2.2** no JDK 25: os interceptors do resilience4j-micronaut 2.4.0 e os próprios `@Retryable` e `@CircuitBreaker` do Micronaut, contra a subclasse gerada.
- **Quarkus 3.40.1**: as anotações do SmallRye Fault Tolerance, e decorators do Resilience4j construídos uma vez em um bean e chamados à mão, sem nenhum interceptor, que é o melhor caso do Resilience4j ali.

As configurações são as mesmas dos benchmarks do core: um retry de três tentativas sem espera, um circuit breaker com janela de 100 chamadas, um bulkhead de 1000 chamadas que nunca enche e um rate limiter que nunca se esgota. Um benchmark rápido também pode significar um interceptor que nunca rodou, então cada setup verifica primeiro que toda instância do Resilience4j participa da sua chamada. O breaker contou a chamada, um bulkhead cheio a rejeita, um limiter esgotado a rejeita e um breaker aberto a rejeita.

Do lado do resilience4cata, nada é específico do framework. A classe que você anota é compilada com uma subclasse gerada ao lado, e a integração faz dessa subclasse o bean:

<!-- not-compiled: uma ilustração curta; os guias de Spring, Micronaut e Quarkus têm os beans testados -->
```java
@Service
public class Inventory {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    public int stock(String sku) {
        return warehouse.stock(sku);
    }
}
```

O bean é `Inventory$Resilient`, cujo override de `stock` é código comum que chama `super.stock(sku)`. Não há proxy nem cadeia de interceptors no meio.

## Os números :id=the-numbers

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), 3 forks, ns por chamada em uma thread; o resilience4cata aloca 0 B em todas as linhas. Aparece o rival mais rápido em cada framework: os interceptors do Resilience4j no Spring e no Micronaut, decorators do Resilience4j chamados à mão ou o Fault Tolerance no Quarkus. De [Framework beans and a full comparison, 0.13.0](../benchmarks.md#frameworks).

| Chamada | Spring: resilience4cata | Spring: Resilience4j | Micronaut: resilience4cata | Micronaut: Resilience4j | Quarkus: resilience4cata | Quarkus: rival mais rápido |
|---|---:|---:|---:|---:|---:|---:|
| retry, a primeira tentativa dá certo | **1.1** | 1346.0 | **1.1** | 104.8 | **1.1** | 22.9 |
| circuit breaker | **1.5** | 1737.0 | **1.5** | 304.5 | **1.5** | 101.6 |
| circuit breaker aberto | **2.3** | 4174.2 | **2.3** | 1127.4 | **2.3** | 355.9 |
| rate limiter | **42.0** | 1481.8 | **42.0** | 141.9 | **42.0** | 66.7 |
| bulkhead | **20.4** | 1687.7 | **20.7** | 490.2 | 20.4 | **20.3** |
| retry + breaker + rate limiter + bulkhead | **62.1** | 6104.4 | **62.1** | 1117.9 | **62.0** | 217.2 |

Com quatro threads as diferenças aumentam, porque o estado compartilhado por trás dos interceptors começa a disputar. O circuit breaker no Spring vai a 1.4 ns contra 2142 ns, e no Micronaut a 1.5 ns contra 1501 ns.

## Para onde vai o tempo :id=where-the-time-goes

A coluna do resilience4cata é a mesma nos três frameworks, e a mesma dos benchmarks do core. O framework simplesmente não está no caminho da chamada. As colunas dos rivais variam por framework porque cada um paga por um tipo diferente de interceptação:

- **Spring:** os aspects do resilience4j-spring-boot4 custam de 1.3 a 6 µs e de 1.6 a 6.3 KB por chamada. O pointcut deles é dinâmico, e avaliá-lo carrega uma classe pelo nome (`Class.forName`) em cada chamada.
- **Micronaut:** o AOP em tempo de compilação dele é muito mais barato, de 100 ns a 1.1 µs para os interceptors do resilience4j-micronaut. Os próprios `@Retryable` e `@CircuitBreaker` do Micronaut custam de 1.0 a 1.8 µs e de 2.3 a 3.6 KB.
- **Quarkus:** os interceptors do Fault Tolerance custam de 0.3 a 1.9 µs por chamada. Os decorators do Resilience4j, chamados à mão sem interceptor, são o rival mais rápido ali. É também o único caso da tabela que o resilience4cata não vence: um bulkhead com vagas livres, 20.4 ns contra 20.3 ns. Os dois pegam e devolvem uma permissão com duas operações atômicas, o que eu tomei pelo piso do hardware; não era (veja a atualização abaixo).

## Os casos que não foram vitórias :id=the-cases-that-were-not-wins

A mesma execução repetiu todos os benchmarks do core contra a variante mais rápida do Resilience4j. De 156 casos comparáveis, incluindo os de framework, o resilience4cata venceu 150. Eu disse que mostraria os empates e as derrotas, então aqui estão os outros seis:

- **Um hedge com 1 ms de espera perdeu por 2×**, em uma thread e em quatro: 2.1 ms contra 1.07 ms. O timer compartilhado trabalha em ticks de um dezesseis avos da espera, de pelo menos 1 ms, e uma espera podia começar até uns dois ticks atrasada. Os hedges agora contam a espera a partir do início da chamada, ao custo de uma leitura de relógio, e o timer acorda no prazo. O hedge dispara em 1060 µs contra 1067 µs do Resilience4j ([Hedge delays on time](../benchmarks.md#hedge-precision)). Nenhuma das duas bibliotecas consegue ir muito abaixo disso, porque ambas esperam o sistema operacional acordar uma thread.
- **Dois casos de bulkhead em uma thread foram empates**: o do Quarkus acima, e o mesmo bulkhead chamado diretamente (20.5 ns contra 20.4 ns).

> [!NOTE]
> Atualização, mais tarde no mesmo dia: isso não era o piso do hardware. Devolver a vaga com uma soma volatile em vez de uma só com release mediu mais rápido no Graviton3, e o bulkhead agora custa 17.3 ns contra 20.4 ns do Resilience4j ([Slots given back with a volatile add](../benchmarks.md#release-counters)).

- **Dois casos de `Flux` estão dentro da margem de erro**: o bulkhead com quatro threads e o rate limiter com uma, em que o resilience4cata aloca mais por stream do que os operadores do resilience4j-reactor. Isso está sendo trabalhado.

Perder um benchmark foi útil. O atraso do hedge estava na documentação desde a 0.4.0 ("até uns dois ticks atrasado"), mas só uma comparação com um timer preciso o fez parecer o que era.

## Ressalvas :id=caveats

- **Microssegundos só importam ao lado de chamadas baratas.** Uma chamada remota que leva 20 ms não vai notar 2 µs de interceptação. Um serviço que protege caches em memória, chamadas locais ou milhões de chamadas por segundo vai.
- **São microbenchmarks** em um único tipo de máquina, com todas as policies configuradas para nunca rejeitar. A [página de benchmarks](../benchmarks.md) tem o método, as notas de justiça e todos os números, incluindo empates e derrotas.
- **O bean é uma subclasse.** Com classes anotadas, um proxy baseado em classe como o `@Transactional` do Spring fica fora das policies, então uma transação abrange todos os retries. Cada integração se recusa a iniciar quando um método com retry participa de uma transação, e os guias explicam como escolher.

## Experimente :id=try-it

Adicione a integração do seu framework e o annotation processor. Os guias de [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) e [Quarkus](../guides/quarkus.md) têm as dependências para Gradle e Maven e um exemplo testado. Se você vem do Resilience4j, o [guia de migração](../migrate/from-resilience4j.md) mapeia cada anotação e propriedade. O código tem licença Apache-2.0 no [GitHub](https://github.com/fabiangftech/resilience4cata).
