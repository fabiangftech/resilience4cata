# Desempenho

Quanto o resilience4cata custa por chamada, medido contra o Resilience4j 2.4.0 com configuração equivalente, e como reproduzir isso.

Baixo overhead é a razão de o resilience4cata existir: o processador de anotações gera código em linha reta para cada método, então uma chamada bem-sucedida passa por algumas leituras de campo e, na maioria das políticas, sem alocação, sem lock e sem leitura de relógio. Esta página resume as medições; [Benchmarks em detalhe](benchmarks.md) tem cada execução, sua configuração e como cada número foi obtido.

## O que é medido, e com que equidade :id=method

- **JMH 1.37**, 3 forks × 5 × 1 s de aquecimento e 5 × 1 s de medição, com `-prof gc` para os bytes alocados por chamada. JMH puro, sem plugin do Gradle.
- **Configuração equivalente nas duas bibliotecas**: mesmas tentativas, atrasos, tamanhos de janela, limiares e limites; sem listeners de eventos; as falhas são exceções pré-alocadas e sem stack trace, para que nenhuma das bibliotecas pague por criá-las. As exceções de rejeição do Resilience4j recebem `writableStackTraceEnabled(false)`.
- **Os ajustes feitos por equidade estão documentados** no Javadoc de cada benchmark. O principal: o Resilience4j 2.4.0 chama `Thread.sleep` entre as tentativas mesmo com atraso zero, então a comparação de retry usa uma variante sem esse sleep (`resilience4jRetryOnceNoSleep`), que é o melhor caso do Resilience4j.
- **Diferenças de comportamento que os números incluem**: o breaker do Resilience4j sempre mede o tempo das chamadas (o resilience4cata só quando `slowCallDuration` está definido); o `TimeLimiter` do Resilience4j precisa de um `Future`, então uma chamada síncrona tem que ir para outra thread (o resilience4cata aplica o timeout na thread de quem chama).
- **Duas máquinas, nunca misturadas**: uma AWS `c7g.xlarge` (4 núcleos Graviton3, sem SMT, clock fixo, Amazon Corretto 21) e um laptop Apple M1 (JDK 21) para rodadas anteriores. Números de máquinas diferentes não são comparáveis.
- Um benchmark com um único método decorado é o melhor caso do Resilience4j: o JIT vê um único tipo em cada ponto de chamada. Aplicações reais têm muitos.
- **Tempo médio por chamada, em loop fechado** (JMH `AverageTime`): cada thread chama de novo assim que a última chamada retorna. Isso esconde as caudas: sob contenção o rate limiter estaciona as threads que perdem uma corrida, e com quatro threads saturando um limiter entre 0.1 % e 1 % das chamadas esperam 110 µs ou mais (o do Resilience4j: 910 µs no p99.9). O bulkhead não tem essa cauda. Percentis: [benchmarks](benchmarks.md#percentiles).
- **Só ARM até agora**: Graviton3 e Apple M1. x86-64 ainda não foi medido; lá os atômicos são barreiras completas e o relógio é barato, então a ordem de alguns resultados pode ser diferente.

## Resultados principais :id=headline

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, contra Resilience4j 2.4.0, nanossegundos e bytes alocados por chamada. O retry cuja primeira tentativa tem sucesso, o breaker em que toda chamada tem sucesso e o timeout síncrono são o código da 1.0.0-RC1, de [a release candidate (2026-11-02)](benchmarks.md#release-candidate); o breaker com chamadas que falham, de [taxas de falha realistas (2026-11-02)](benchmarks.md#failure-rates); o bulkhead com vagas livres, o código depois da 0.13.0, de [vagas devolvidas com um add volatile (2026-11-01)](benchmarks.md#release-counters), onde o Resilience4j mediu 20.4 ns em uma thread e de 348 a 450 ns com quatro; todas as outras linhas, resilience4cata 0.1.0, de [conclusão do MVP (2026-10-24, AWS Graviton3)](benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3).

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Retry, a primeira tentativa tem sucesso | 1 | **1.4 ns**, 0 B | 22.9 ns, 0 B |
| Retry, falha uma vez e depois tem sucesso (Resilience4j sem seu `sleep(0)`) | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, toda chamada tem sucesso | 1 | **1.9 ns**, 0 B | 89.9 ns, 0 B |
| Circuit breaker, toda chamada tem sucesso | 4 | **1.9 ns**, 0 B | 850 ns, 2 B |
| Circuit breaker, uma chamada em 1 000 falha | 4 | **11.2 ns**, 0 B | 1025 ns, 26 B |
| Circuit breaker, uma chamada em 100 falha | 4 | **65.4 ns**, 0 B | 1006 ns, 22–26 B |
| Circuit breaker, uma chamada em dez falha | 4 | **278 ns**, 0 B | 940 ns, 2 B |
| Rate limiter, permissões livres | 4 | **187 ns**, 0 B | 275 ns, 40 B |
| Rate limiter, esgotado (toda chamada rejeitada) | 4 | **34.7 ns**, 0 B | 1599 ns, 505 B |
| Bulkhead, vagas livres | 1 | **17.3 ns**, 0 B | 20.4 ns, 0 B |
| Bulkhead, vagas livres | 4 | **195.5 ns**, 0 B | 348–450 ns, 0 B |
| Bulkhead, cheio (toda chamada rejeitada) | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Timeout, chamada síncrona que termina a tempo (Resilience4j: `TimeLimiter` + executor) | 1 | **23.6 ns**, 0 B | 8908 ns, 183 B |
| Timeout async, stage ainda pendente | 1 | **152 ns**, 192 B | 598 ns, 240 B |
| Timeout async, stage ainda pendente | 4 | **369 ns**, 192 B | 1563 ns, 234 B |

Lendo a tabela com honestidade:

- **Os 1.9 ns do circuit breaker valem enquanto sua janela não tem nenhuma falha.** Nesse caso uma chamada só lê um campo. Quando as últimas `windowSize` chamadas incluem uma falha ou uma chamada lenta, cada chamada escreve o cursor compartilhado da janela. Com quatro threads isso custa 11.2 ns a 0.1 % de falhas, 65.4 ns a 1 % e 278 ns a 10 %, contra cerca de 1 000 ns do Resilience4j em qualquer taxa: 91×, 15× e 3.4× em vez de 450× ([taxas de falha realistas](benchmarks.md#failure-rates)).
- **O Resilience4j faz trabalho por chamada que o resilience4cata não faz**: seu retry conta cada chamada em suas métricas embutidas (contadores `LongAdder`), e seu breaker sempre mede o tempo das chamadas. O resilience4cata não mantém nenhum contador por chamada fora da janela do breaker ([por que não há métricas de sucesso](faq.md#success-metrics)).
- **O bulkhead com vagas livres em uma thread era um empate** (20.5 contra 20.4 ns, ambos um atômico para pegar e outro para devolver) até que a vaga passou a ser devolvida com um add volatile, que mediu mais rápido que um só com release no Graviton3: 17.3 ns.
- **Caminhos com contenção custam centenas de nanossegundos nas duas bibliotecas.** O caso misto do breaker com quatro threads (278 ns) é dominado por um único cursor de janela compartilhado que cada chamada avança; o bulkhead e o rate limiter com quatro threads pagam por um contador compartilhado, e o rate limiter estaciona as threads que perdem uma corrida nele.
- **O timeout síncrono é a política síncrona mais cara** (23.6 ns em uma thread; o resultado principal da 0.1.0 mediu 22.2 ns, que não foi visto desde então, veja [a release candidate](benchmarks.md#release-candidate)). A comparação do Resilience4j não é equivalente: seu `TimeLimiter` entrega a chamada a outra thread, que é onde vai a maior parte dos seus 8.9 µs.
- **Chamadas async alocam nas duas bibliotecas**: cada camada devolve um novo future. Os caminhos async do resilience4cata não são livres de alocação; só os síncronos são.
- **O rate limiter lê o relógio uma vez por chamada**, nas duas bibliotecas; essa leitura é a maior parte do seu custo (no Apple Silicon, `System.nanoTime()` sozinho custa cerca de 21 ns em uma thread e 163 ns com quatro; veja [Rate limiter, 1 e 4 threads](benchmarks.md#rate-limiter-1-and-4-threads-2026-10-24)).

### Classifiers, Pipeline e classes anotadas :id=more

> [!MEASURED]
> Mesma máquina e rodada que acima ([conclusão do MVP](benchmarks.md#mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3), [classes anotadas](benchmarks.md#annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3)), toda chamada tem sucesso, 0 B do lado do resilience4cata.

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Circuit breaker + classifier de resultado | 1 | **2.4 ns** | 102 ns, 24 B (`recordResult`) |
| Circuit breaker + classifier de resultado | 4 | **2.4 ns** | 902 ns, 18 B |
| Retry + circuit breaker + classifier | 1 | **2.7 ns** | 148 ns, 104 B |
| Retry + circuit breaker, código gerado | 1 | **2.3 ns** | 175 ns, 288 B |
| Retry + circuit breaker, `Pipeline` programático | 1 | **2.9 ns** | (mesma pilha Resilience4j) |
| Retry, classe anotada (subclasse) vs wrapper de interface | 1 | 1.0 vs 1.2 ns | — |

- Um `Pipeline` cujo único ponto de chamada `call.call()` já viu outros cinco pipelines mediu **7.9 ns** em vez de 2.9 ns: com muitos pipelines em uma aplicação esse ponto de chamada fica megamórfico. O código gerado tem um ponto de chamada por método e não paga isso.

### Métodos Mono e Flux (Project Reactor) :id=reactor

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), JDK 21, contra os operadores do resilience4j-reactor 2.4.0 com a mesma configuração, toda chamada tem sucesso. De [métodos Flux e stages já concluídos (2026-10-29, AWS Graviton3)](benchmarks.md#flux-and-done-stages).

Métodos `Mono`, com o valor já disponível na assinatura, salvo indicação:

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Circuit breaker | 1 | **106 ns**, 176 B | 243 ns, 248 B |
| Circuit breaker | 4 | **105 ns**, 176 B | 1291 ns, 240 B |
| Retry, a primeira tentativa tem sucesso | 1 | **135 ns**, 227 B | 725 ns, 1000 B |
| Timeout | 1 | **105 ns**, 176 B | 3184 ns, 1344 B |
| Timeout, o valor chega depois | 1 | **430 ns**, 480 B | 3686 ns, 1797 B |
| Bulkhead | 1 | **126 ns**, 176 B | 157 ns, 200 B |
| Retry + breaker + timeout + bulkhead | 1 | **201 ns**, 376 B | 4319 ns, 2624 B |

Métodos `Flux`, um stream inteiro de dez elementos, salvo indicação:

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Circuit breaker | 1 | **195 ns**, 283 B | 272 ns, 216 B |
| Circuit breaker | 4 | **189 ns**, 317 B | 1322 ns, 218 B |
| Retry, a primeira tentativa tem sucesso | 1 | **270 ns**, 392 B | 1197 ns, 1024 B |
| Timeout (reiniciado a cada elemento) | 1 | **681 ns**, 384 B | 28896 ns, 3983 B |
| Timeout, mil elementos | 1 | **45 µs**, 384 B | 2632 µs, 241529 B |
| Retry + breaker + timeout + bulkhead | 1 | **789 ns**, 616 B | 30583 ns, 5104 B |

- Assinar sem nenhuma política custa 52 ns e 88 B para um `Mono.just`, 56 ns e 96 B para dez elementos, nesta máquina; todas as linhas o incluem.
- Os casos mais próximos: um bulkhead em volta de um `Mono` com quatro threads, e em volta de um `Flux` com quatro threads (755 vs 697 ns) ou um rate limiter em uma (240 vs 234 ns), dentro do erro. Um timeout de `Flux` custa cerca de 44 ns por elemento, sem timer por elemento; o do Resilience4j agenda um por elemento. Métodos reativos alocam, como todo caminho async; o gate de alocação acompanha seus bytes.

### Muitos serviços em uma aplicação :id=many-services

> [!MEASURED]
> Apple M1, JDK 21, quatro serviços sem relação entre si, cada um com seu próprio retry, uma chamada a cada um por operação. De [Retry, quatro serviços](benchmarks.md#retry-four-services-2026-10-23).

| Por chamada | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| Overhead, a primeira tentativa tem sucesso | **0.6 ns**, 0 B | 21.3 ns, 80 B |
| Overhead, falha uma vez e depois tem sucesso | **9.4 ns**, 0 B | 273 ns, 16 B (sem seu `sleep(0)`) |

Com quatro suppliers decorados compartilhando o ponto de chamada do Resilience4j, seu caminho de sucesso começa a alocar 80 B por chamada; as classes geradas do resilience4cata têm cada uma seu próprio ponto de chamada e continuam em 0 B.

## O gate de alocação zero :id=allocation-gate

A alocação é verificada em cada pull request. `./gradlew :benchmark:allocationCheck` executa os benchmarks listados em `benchmark/allocation-budgets.properties` com `-prof gc` e falha se algum alocar seu orçamento + 1 B por chamada. **Todo caminho síncrono de sucesso e de rejeição tem orçamento de 0 B**: retry, circuit breaker (janelas por contagem e por tempo, aberto), rate limiter, bulkhead, timeout, classifiers, código gerado e `Pipeline` (também com uma lambda que captura). Os caminhos async têm orçamentos para seus futures (48 B para um retry, 24 B para outra política cujo stage já está concluído). Uma exceção que o gate não vê: a primeira chamada com timeout em uma thread nova registra essa thread no watchdog do timeout, cerca de 270 B, o que importa com uma virtual thread nova por requisição ([um timeout por virtual thread nova](benchmarks.md#review-fixes)). Detalhes em [Gate de alocação](benchmarks.md#allocation-gate).

Os eventos só alocam quando alguém escuta: um tipo de evento sem listeners custa uma verificação de null no caminho de falha ou de rejeição, o que mediu 0.3 ns em um bulkhead cheio.

## O contrato de desempenho :id=contract

As metas que o projeto se impõe, e como cada uma é verificada:

| Meta | Como é verificada |
|---|---|
| **0 B/op** no caminho de sucesso de cada política e de uma pilha completa (a primeira chamada com timeout de uma thread: cerca de 270 B, uma vez) | JMH `-prof gc` no CI: `./gradlew :benchmark:allocationCheck` reprova um pull request se um benchmark listado chega ao seu orçamento + 1 B/op (0 para todo caminho síncrono; valores medidos para os futures async) |
| Sem locks e sem `synchronized` nos hot paths | revisão de código, cenários JMH de contenção |
| Código gerado monomórfico e em linha reta por método; sem cadeias de lambdas compartilhadas | JMH com um perfil de JIT poluído, `-XX:+PrintInlining` |
| Nenhuma leitura de relógio em um caminho de sucesso, a menos que uma funcionalidade precise (rate limiting, detecção de chamadas lentas, janelas de tempo, a latência do adaptive limit, o `maxDuration` de um retry); cada uma dessas políticas lê o relógio ela mesma, então uma pilha delas o lê mais de uma vez | revisão de código |
| Exceções de rejeição pré-alocadas e sem stack trace; eventos alocados só se alguém escuta | cenários JMH de rejeição (breaker aberto, bulkhead cheio, rate limiter esgotado), controlados em 0 B/op no CI |
| Estruturas lock-free corretas | jcstress, no CI em x86 e ARM depois de cada push para main e todas as noites, e semanalmente em seu modo padrão mais longo em ARM |
| Comparação publicada e reproduzível com o Resilience4j 2.4.0 | o módulo `benchmark`, com configuração equivalente do Resilience4j e notas de equidade ([Benchmarks em detalhe](benchmarks.md)) |

A maioria das chamadas reais é limitada por I/O (milissegundos), então o objetivo é um overhead perto de zero que se mantenha com muitas threads, não só velocidade bruta.

## O que o torna barato :id=why

- **Código gerado, por método, em linha reta**: sem lambda decoradora compartilhada, então o JIT faz inline de cada ponto de chamada com um único tipo receptor.
- **Nada alocado no sucesso**, e as rejeições lançam uma única exceção pré-alocada e sem stack trace por instância.
- **Sem locks** em nenhum caminho de chamada, e atômicos escolhidos por medição: um compare-and-set com acquire em vez de uma barreira completa para pegar uma vaga levou o bulkhead livre de 23.2 a 9.3 ns no Apple M1 ([Bulkhead, 1 e 4 threads](benchmarks.md#bulkhead-1-and-4-threads-2026-10-23)), enquanto devolvê-la com um add volatile mediu mais rápido que um add só com release no Graviton3 ([contadores com release](benchmarks.md#release-counters)).
- **Nenhuma escrita compartilhada enquanto tudo está saudável**: um breaker cuja janela só tem sucessos rápidos é verificado com uma leitura por chamada.
- **Nenhuma leitura de relógio** a menos que uma funcionalidade precise (detecção de chamadas lentas, janelas de tempo, rate limiting, a latência do adaptive limit, o `maxDuration` de um retry). Os timeouts usam uma thread watchdog compartilhada em vez de ler o relógio a cada chamada.

## Reproduza :id=reproduce

A partir de um clone do repositório (JDK 17 ou posterior para executar o Gradle; ele usa um toolchain Java 21):

```bash
./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"                 # one benchmark class
./gradlew :benchmark:jmh -Pjmh="CircuitBreakerBenchmark -t 4 -prof gc"   # four threads
./gradlew :benchmark:jmh -PjmhJdk=25                                     # every benchmark on another JDK
./gradlew :benchmark:allocationCheck                                     # the allocation gate
```

Os resultados são escritos em `benchmark/build/jmh/results.json`. Os fontes estão em `benchmark/src/main/java/dev/resilience4cata/benchmark/`, cada um com sua contraparte Resilience4j e os ajustes feitos por equidade no seu Javadoc. Meça em uma máquina ociosa, com vários forks, e compare apenas números da mesma máquina.
