# Benchmarks

> Apenas resultados medidos. Todo número aqui vem de uma execução neste repositório; qualquer outra coisa é uma hipótese e pertence aos documentos de pesquisa.

## Retry, uma thread (2026-10-23) :id=retry-single-thread-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="RetryBenchmark -prof gc"` — fonte: `benchmark/src/main/java/dev/resilience4cata/benchmark/RetryBenchmark.java`.

**Configuração:** JMH 1.37, JDK 21.0.10, notebook Apple M1, 3 forks × 5 × 1 s de aquecimento × 5 × 1 s de medição, `-Xms1g -Xmx1g`. As duas bibliotecas: 3 tentativas, atraso zero, sem jitter, toda exceção é retentada, sem event listeners. A falha é uma exceção pré-alocada e sem stack trace, então nenhuma das bibliotecas paga para criá-la.

| Cenário | Baseline (sem biblioteca) | resilience4cata 0.1.0-SNAPSHOT | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| A primeira tentativa tem sucesso | 1.14 ± 0.13 ns | **1.55 ± 0.08 ns**, 0 B/op | 12.61 ± 0.10 ns, 0 B/op |
| Falha uma vez, depois tem sucesso | 3.03 ± 1.43 ns | **7.50 ± 0.75 ns**, 0 B/op | 411.6 ± 15.1 ns, 16 B/op |
| Igual, Resilience4j sem o seu `Thread.sleep(0)` | — | — | 38.8 ± 3.6 ns, 16 B/op |

**Overhead sobre o baseline:**
- Caminho de sucesso: cerca de **0.4 ns** para resilience4cata vs **11.5 ns** para Resilience4j.
- Um retry: cerca de **4.5 ns** vs **36 ns** só para a maquinaria de retry do Resilience4j, ou **409 ns** com o comportamento padrão do Resilience4j.

**Como ler estes números:**
- O Resilience4j 2.4.0 chama `Thread.sleep` entre as tentativas mesmo quando o atraso é zero (verificado no seu bytecode). Isso explica a maior parte dos 411 ns. A variante `NoSleep` o remove com o próprio `RetryImpl.setSleepFunction` do Resilience4j, para isolar o custo do resto.
- Este é o melhor caso para o Resilience4j: um único supplier decorado, então o JIT vê um único tipo em cada call site e a escape analysis remove os seus objetos por chamada no caminho de sucesso. Aplicações reais decoram muitos métodos. A pesquisa prevê diferenças maiores com um perfil do JIT poluído ([03 §7.4](research/03-performance-internals.md)); esse cenário ainda não foi medido.
- Só uma thread. O retry não tem estado mutável compartilhado, então não se espera que a contenção importe para ele; vai importar para o circuit breaker e o rate limiter.
- Um notebook, uma JDK. Pendente: JDK 25, x86-64, perfil poluído.

## Retry, quatro serviços (2026-10-23) :id=retry-four-services-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="MultiTypeRetryBenchmark -prof gc"` — fonte: `benchmark/src/main/java/dev/resilience4cata/benchmark/MultiTypeRetryBenchmark.java`.

Mesma configuração e mesmos ajustes de cima, mas com quatro serviços sem relação entre si (interface própria, classe de implementação própria), cada um chamado do seu próprio call site, como numa aplicação real. **Uma operação chama os quatro serviços**; as colunas "por chamada" dividem por quatro.

| Cenário | Baseline | resilience4cata | Resilience4j 2.4.0 | Resilience4j, sem `Thread.sleep(0)` |
|---|---:|---:|---:|---:|
| A primeira tentativa tem sucesso, por op | 1.87 ± 0.03 ns | **4.39 ± 0.11 ns**, 0 B | 87.1 ± 4.0 ns, 320 B | — |
| ↳ overhead por chamada | — | **0.6 ns**, 0 B | 21.3 ns, 80 B | — |
| Falha uma vez, depois tem sucesso, por op | 3.57 ± 0.09 ns | **41.1 ± 0.9 ns**, 0 B | 2794 ± 128 ns, 64 B | 1094 ± 13 ns, 64 B |
| ↳ overhead por chamada | — | **9.4 ns**, 0 B | 698 ns, 16 B | 273 ns, 16 B |

**O que mudou em relação à execução com um único serviço:**
- **O Resilience4j começa a alocar no caminho de sucesso: 80 B por chamada**, onde a execução com um único serviço mostrava 0 B. Os quatro suppliers decorados passam pelo mesmo call site `supplier.get()` dentro de `Retry.decorateSupplier`. O JIT já não consegue fazer inline através dele, então a escape analysis deixa de remover os objetos por chamada do Resilience4j. Este é o efeito previsto em [03 §7.4](research/03-performance-internals.md), agora medido.
- **O resilience4cata continua em 0 B** e o overhead do seu caminho de sucesso quase não se move (0.4 → 0.6 ns por chamada): cada classe gerada tem o seu próprio call site, com um único tipo de receptor.
- O seu caminho de retry vai de 4.5 para 9.4 ns por chamada. O do Resilience4j vai de 36 para 273 ns por chamada sem o sleep: uma falha que atravessa frames que o JIT não conseguiu fazer inline é muito mais cara de desempilhar.

Ainda pendente: JDK 25, x86-64, mais de uma thread.

## Circuit breaker, 1 e 4 threads (2026-10-23) :id=circuit-breaker-1-and-4-threads-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="CircuitBreakerBenchmark -t 4 -prof gc"` — fonte: `benchmark/src/main/java/dev/resilience4cata/benchmark/CircuitBreakerBenchmark.java`.

Mesma máquina e mesmos ajustes de JMH de cima. As duas bibliotecas: janela por contagem de 100 chamadas, avaliada após 100 chamadas, abrindo com 50% de falhas, sem event listeners. Todas as threads compartilham **um** breaker. O Resilience4j é medido com as duas estratégias de janela: `SYNCHRONIZED` (o seu padrão) e `LOCK_FREE`. No cenário misto, uma chamada em cada dez falha, então o breaker continua fechado enquanto a sua janela registra falhas. Diferença de padrões: o Resilience4j sempre cronometra as chamadas para a detecção de chamadas lentas; o resilience4cata não, a menos que `slowCallDuration` esteja definido.

| Cenário | Threads | Baseline | resilience4cata | R4j `SYNCHRONIZED` | R4j `LOCK_FREE` |
|---|---:|---:|---:|---:|---:|
| Toda chamada tem sucesso | 1 | 1.10 ns | **2.03 ± 0.03 ns**, 0 B | 66.4 ± 1.7 ns, 16 B | 124 ± 16 ns, 224 B |
| Uma em cada dez falha | 1 | 1.91 ns | **11.4 ± 0.3 ns**, 0 B | 95.0 ± 4.6 ns, 7 B | 135 ± 7 ns, 243 B |
| Toda chamada tem sucesso | 4 | 1.44 ns | **3.16 ± 0.08 ns**, 0 B | 477 ± 41 ns, 1 B | 1074 ± 96 ns, 776 B |
| Uma em cada dez falha | 4 | 2.82 ns | **202 ± 21 ns**, 0 B | 601 ± 13 ns, 1 B | 1103 ± 64 ns, 768 B |

(Tempos por chamada; B são os bytes alocados por chamada.)

**Overhead sobre o baseline:** cerca de **0.9 ns** por chamada bem-sucedida numa thread (Resilience4j: 65 ns) e **1.7 ns** com quatro threads compartilhando o breaker (Resilience4j: 476 ns).

**Como o caminho de sucesso chegou lá.** A primeira versão funcional custava 9.5 ns numa thread e 244 ns com quatro threads. Cada passo foi medido:

| Mudança | 1 thread, sucesso | 4 threads, sucesso | 4 threads, misto |
|---|---:|---:|---:|
| Primeira versão: contadores atômicos em objetos separados, leituras acquire | 9.5 ns | 244 ns | 500 ns |
| Pular escritas quando a janela só contém sucessos rápidos | 9.5 ns | 10.2 ns | 259 ns |
| Leituras opaque em vez de acquire (seguro: tudo o que se alcança é `final`) | 5.7 ns | — | — |
| Contadores como campos da janela, sem saltos de ponteiro | 4.9 ns | — | — |
| Campo `quiet`: uma leitura por chamada enquanto está saudável | 2.0 ns | 3.1 ns | 317–351 ns ⚠ |
| Escrever `quiet` só quando muda; cursor no seu próprio objeto; sem contador `completed` enquanto fechado | 2.0 ns | 3.2 ns | 202–230 ns |

- **Sob contenção, dominam as escritas compartilhadas, não os locks.** Escrever uma linha de cache que outros núcleos leem custa mais do que um design lock-free economiza. O maior ganho veio de não escrever nada quando nada muda.
- **Em ARM, as leituras acquire não são grátis.** Cada uma virou um `ldar`; cinco por chamada custavam cerca de 4 ns.
- **Cada mudança foi verificada nos quatro cenários.** Duas delas pioraram o caso misto com quatro threads antes de serem corrigidas (linha ⚠): colocar o cursor junto com os contadores, e reescrever `quiet` a cada falha; as duas colocavam escritas por chamada numa linha que toda chamada lê.
- **O caso misto com quatro threads continua sendo o gargalo** (cerca de 200 ns): toda chamada avança um cursor compartilhado para que a janela guarde exatamente as últimas 100 chamadas. Relaxar essa semântica (por exemplo, janelas particionadas em stripes) poderia eliminá-lo; isso é uma decisão de design, não foi feito.
- A estratégia `LOCK_FREE` do Resilience4j é mais lenta que o seu padrão em todos os cenários aqui e aloca 200–800 B por chamada.
- Um notebook (Apple M1: 4 núcleos de desempenho + 4 de eficiência), JDK 21. Pendente: JDK 25, x86-64, 8+ threads.

## Circuit breaker, janela por tempo (2026-10-23) :id=circuit-breaker-time-based-window-2026-10-23

Mesma classe de benchmark (métodos `*Time*`): uma janela de 10 segundos, avaliada após 100 chamadas, limiar de 50%; Resilience4j `TIME_BASED` com a sua estratégia padrão `SYNCHRONIZED`.

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Toda chamada tem sucesso | 1 | **46.6 ± 0.3 ns**, 0 B | 111.4 ± 0.5 ns, 0 B |
| Uma em cada dez falha | 1 | **69 ± 19 ns**, 0 B | 135.3 ± 0.8 ns, 0 B |
| Toda chamada tem sucesso | 4 | **164 ± 28 ns**, 0 B | 703 ± 52 ns, 41 B |
| Uma em cada dez falha | 4 | **191 ± 11 ns**, 0 B | 744 ± 53 ns, 41 B |

**O relógio domina.** Uma janela por tempo precisa ler o relógio a cada chamada, e nesta máquina só `System.nanoTime()` custa **21 ns numa thread e 163 ns com quatro threads** (medido à parte). No macOS, o HotSpot mantém `nanoTime` monotônico entre threads com um compare-and-set sobre um valor global, então chamadores concorrentes disputam por ele; no Linux é uma chamada vDSO sem estado compartilhado. Sem o relógio, a janela por tempo do resilience4cata custa aproximadamente 25 ns numa thread e alguns nanossegundos com quatro. Todo contador é particionado (`LongAdder`) para que os sucessos não disputem entre si; a única alocação é um bucket por segundo.

Duas consequências:
- A janela por contagem, que não lê relógio a menos que `slowCallDuration` esteja definido, é muito mais barata onde as duas servem. É o padrão.
- Um relógio grosso em cache (atualizado por uma thread em segundo plano a cada milissegundo) eliminaria a maior parte desse custo. Não foi feito: significa uma thread daemon por JVM e resolução de milissegundos.

## Timeout, 1 e 4 threads (2026-10-23) :id=timeout-1-and-4-threads-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="TimeoutBenchmark -t 4 -prof gc"` — fonte: `benchmark/src/main/java/dev/resilience4cata/benchmark/TimeoutBenchmark.java`.

Chamadas que terminam a tempo (trabalho trivial, timeout de 1 s). O resilience4cata executa a chamada na thread do chamador e um watchdog compartilhado a interromperia se ela se atrasasse. O `TimeLimiter` do Resilience4j precisa de um `Future`, então uma chamada bloqueante tem que ser entregue a outra thread: é assim que ele é usado em código síncrono, e é o que se mede (um pool fixo de 4 threads de plataforma).

| Threads | Baseline | resilience4cata | Resilience4j 2.4.0 (`TimeLimiter` + executor) |
|---:|---:|---:|---:|
| 1 | 1.10 ns | **15.6 ± 0.1 ns**, 0 B | 9 225 ± 1 353 ns, 189 B |
| 4 | 1.44 ns | **20.2 ± 2.4 ns**, 0 B | 19 194 ± 3 763 ns, 160 B |

- Cerca de **14.5 ns de overhead por chamada**, aproximadamente 600× menos do que entregar a chamada a outra thread. Nenhuma leitura de relógio e nenhuma escrita compartilhada por chamada: cada thread escreve só no seu próprio scope reutilizável.
- Isso é mais do que custam as outras políticas (retry 0.4 ns, circuit breaker 0.9 ns). Candidatos prováveis, ainda não investigados: a busca no `ThreadLocal` dos scopes da thread e o compare-and-set quando a chamada termina.
- O preço de não ler o relógio é a precisão: o watchdog faz um tick a cada `timeout / 16` do menor timeout em uso (1 a 50 ms) e interrompe uma chamada entre o seu timeout e cerca de dois ticks depois, nunca antes.

## Bulkhead, 1 e 4 threads (2026-10-23) :id=bulkhead-1-and-4-threads-2026-10-23

`./gradlew :benchmark:jmh -Pjmh="BulkheadBenchmark -t 4 -prof gc"` — fonte: `benchmark/src/main/java/dev/resilience4cata/benchmark/BulkheadBenchmark.java`.

Todas as threads compartilham um bulkhead. *Livre*: um limite de 1000 que nunca é alcançado, sem espera. *Com espera*: o mesmo limite com `maxWait = 100ms`, então o resilience4cata usa o seu modo `Semaphore`. *Cheio*: um limite de 1 ocupado pelo setup, então toda chamada é rejeitada. Resilience4j: `SemaphoreBulkhead`, mesmos limites, stack traces desativados na sua exceção de rejeição.

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Livre | 1 | **9.3 ± 0.2 ns**, 0 B | 22.9 ± 0.1 ns, 0 B |
| Livre | 4 | **324 ± 15 ns**, 0 B | 445 ± 4 ns, 0 B |
| Modo com espera, livre | 1 | 23.2 ± 0.3 ns, 0 B | 23.4 ± 1.2 ns, 0 B |
| Modo com espera, livre | 4 | 447 ± 6 ns, 0 B | 451 ± 12 ns, 0 B |
| Cheio (toda chamada rejeitada) | 1 | **2.4 ± 0.6 ns**, 0 B | 143 ± 11 ns, 464 B |
| Cheio (toda chamada rejeitada) | 4 | **3.3 ± 0.4 ns**, 0 B | 267 ± 41 ns, 464 B |

- **As rejeições são 60–80× mais baratas**: um bulkhead cheio só lê o seu contador (sem escrita, logo sem contenção) e lança uma exceção pré-alocada. Sob sobrecarga, que é quando um bulkhead importa, isso evita que o próprio caminho de rejeição vire um gargalo.
- **Slots livres: 9.3 ns vs 22.9 ns numa thread.** A primeira versão media 23.2 ns, o mesmo que o Resilience4j: usava atômicos com full fence (volatile). Ocupar um slot só precisa de semântica *acquire* e liberá-lo de *release*, o mesmo que um lock; em ARM isso transforma `casal`/`ldaddal` em instruções mais baratas e levou a 9.3 ns (421 → 324 ns com quatro threads).
- **Com quatro threads, as duas bibliotecas pagam pela contenção** (300–450 ns): um limite global exato precisa de um contador compartilhado escrito duas vezes por chamada. Dividir o limite em stripes poderia reduzi-lo, ao preço de um limite que só é respeitado por stripe; não foi feito.
- O modo com espera usa o `Semaphore` da JDK, como o Resilience4j, então custa o mesmo.
- A mesma mudança para uma ordenação mais fraca pode se aplicar ao compare-and-set de fim de chamada do timeout e às atualizações da janela do circuit breaker; ainda não medido.

## Rate limiter, 1 e 4 threads (2026-10-24) :id=rate-limiter-1-and-4-threads-2026-10-24

`./gradlew :benchmark:jmh -Pjmh="RateLimiterBenchmark -t 4 -prof gc"` — fonte: `benchmark/src/main/java/dev/resilience4cata/benchmark/RateLimiterBenchmark.java`.

Todas as threads compartilham um limiter, e nenhuma das bibliotecas espera por uma permissão. *Livre*: um bilhão de permissões por segundo, nunca alcançado, então toda chamada passa. *Esgotado*: uma permissão por hora, tomada pelo setup, então toda chamada é rejeitada. Resilience4j: o seu `AtomicRateLimiter` padrão, mesmos limites, stack traces desativados na sua exceção de rejeição. As duas bibliotecas leem `System.nanoTime()` uma vez por chamada.

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Livre | 1 | **25.3 ± 0.4 ns**, 0 B | 57.5 ± 5.2 ns, 40 B |
| Livre | 4 | **131 ± 1 ns**, 0 B | 206 ± 7 ns, 40 B |
| Esgotado (toda chamada rejeitada) | 1 | **22.9 ± 0.1 ns**, 0 B | 243 ± 12 ns, 560 B |
| Esgotado (toda chamada rejeitada) | 4 | **164 ± 1 ns**, 0 B | 1763 ± 60 ns, 524 B |

- **A maior parte do custo do resilience4cata é o relógio.** `System.nanoTime()` custa 21 ns numa thread e 163 ns com quatro nesta máquina (veja a seção da janela por tempo acima); o próprio limiter acrescenta cerca de 2–4 ns numa thread. Uma rejeição só lê, então com quatro threads um limiter esgotado custa exatamente o que custa o relógio sob contenção. No Linux, onde `nanoTime` não tem estado compartilhado, estes números devem ser muito menores; ainda não medido.
- **As rejeições são 10× mais baratas e não alocam nada**; o Resilience4j aloca um novo `State` por tentativa e ~520 B por chamada rejeitada.
- **Contenção: a primeira versão perdia com quatro threads, 658 ns vs 214 ns.** Toda chamada admitida escreve o único `tat` compartilhado, e os chamadores que perdiam o compare-and-set tentavam de novo na hora, então a linha de cache pulava entre os núcleos. Trocar o atômico (strong, weak, acquire) não mudou nada; girar com `onSpinWait` (uma vez ou com backoff exponencial) e `Thread.yield()` quase não ajudaram (565–665 ns e 414 ns). Estacionar (park) por 1 ns depois de um compare-and-set perdido, como faz o Resilience4j, levou a **131 ns** (351 ns vs 523 ns do Resilience4j com oito threads). O custo passa para o chamador que perdeu a corrida: no Linux, o park mais curto de uma thread de plataforma é de cerca de 50 µs de timer slack. Isso só acontece quando as chamadas admitidas chegam mais rápido do que a linha de cache consegue se mover entre os núcleos, o que está muito acima de qualquer taxa para a qual um limiter normalmente é configurado.

## Métodos assíncronos, 1 thread (2026-10-24) :id=async-methods-1-thread-2026-10-24

`./gradlew :benchmark:jmh -Pjmh="AsyncBenchmark -prof gc"` — fonte: `benchmark/src/main/java/dev/resilience4cata/benchmark/AsyncBenchmark.java`.

Só o caminho de sucesso: o delegate retorna um `CompletableFuture` já completo, e cada benchmark retorna o stage que o chamador recebe. O Resilience4j usa as suas variantes `decorateCompletionStage` com os mesmos ajustes; o seu retry e o seu time limiter recebem um `ScheduledExecutorService` de uma thread. *Pilha* aninha retry, circuit breaker, timeout e bulkhead na mesma ordem nas duas bibliotecas. As linhas de circuit breaker, rate limiter, timeout e pilha foram reexecutadas com 6 forks porque a primeira execução teve muito ruído (±137 ns no breaker do resilience4cata).

| Política | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| Circuit breaker | **18.8 ± 0.5 ns**, 48 B | 85.8 ± 3.7 ns, 71 B |
| Retry | **19.3 ± 1.6 ns**, 48 B | 28.7 ± 1.4 ns, 48 B |
| Bulkhead | **28.1 ± 0.8 ns**, 48 B | 33.1 ± 9.5 ns, 48 B |
| Rate limiter | **48.0 ± 1.6 ns**, 48 B | 63.6 ± 1.3 ns, 88 B |
| Timeout | **213 ± 19 ns**, 200 B | 3551 ± 1772 ns, 184 B |
| Pilha (retry, breaker, timeout, bulkhead) | **344 ± 21 ns**, 362 B | 3600 ± 2205 ns, 352 B |

- **A alocação é mais ou menos a mesma do Resilience4j**: cada camada retorna um novo future (48 B). O assíncrono não pode ser livre de alocação; o caminho síncrono não é afetado por este trabalho.
- **O timeout domina nas duas bibliotecas.** Cada chamada agenda uma tarefa num `ScheduledThreadPoolExecutor` e a cancela quando o stage completa: um lock e uma inserção e remoção no heap. O resilience4cata remove as tarefas canceladas na hora (`setRemoveOnCancelPolicy`). Os números do Resilience4j variam muito entre forks (1.8–5.3 µs); não investigado. Um timer sem lock, como o watchdog síncrono, deveria baixar os 213 ns do resilience4cata; ainda não feito.
- A linha do rate limiter inclui um `System.nanoTime()` (≈21 ns, veja acima); o breaker não lê relógio a menos que `slowCallDuration` esteja definido.

## Reescrita do timer e espera assíncrona do bulkhead (2026-10-24) :id=timer-rewrite-and-async-bulkhead-wait-2026-10-24

Mesma máquina e mesmos ajustes. Executado depois de integrar o trabalho paralelo sobre o timer assíncrono (`TimeoutTimer`), o protocolo do timeout síncrono e a espera assíncrona do bulkhead, um benchmark por vez numa máquina ociosa.

**Timeout assíncrono.** `*TimeoutPending` retorna um future ainda pendente, que o benchmark completa logo após a chamada, então se medem o agendamento e o cancelamento do timer; `baselinePending` é o mesmo sem nenhuma biblioteca (14.5 ns, 24 B). Ali o Resilience4j recebe um scheduler com remove-on-cancel. `*Timeout` retorna um future já completo: o resilience4cata o reconhece e pula o timer (ele não pode estourar o tempo).

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Stage pendente | 1 | **127 ± 10 ns**, 192 B | 257 ± 11 ns, 234 B |
| Stage pendente | 4 | **510 ± 35 ns**, 192 B | 928 ± 60 ns, 234 B |
| Stage completo | 1 | **7.1 ± 0.2 ns**, 24 B (eram 213 ns) | 3795 ± 3163 ns, 181 B |
| Stage completo | 4 | **11.2 ± 0.6 ns**, 24 B | 3173 ± 1003 ns, 180 B |

- O timer lock-free reduz pela metade o custo de uma chamada assíncrona com timeout em relação ao scheduler do Resilience4j, mesmo quando o Resilience4j recebe remove-on-cancel. Com quatro threads as duas pagam pelo estado compartilhado: as stripes de inbox por id de thread do resilience4cata e a conclusão do future de resultado.
- Mudança de comportamento: os timeouts assíncronos agora são baseados em ticks como os síncronos (nunca antes, no máximo cerca de dois ticks atrasados, tick = menor timeout assíncrono / 16 dentro de 1–50 ms), onde o `ScheduledThreadPoolExecutor` tinha precisão abaixo do milissegundo.

**Timeout síncrono** (`TimeoutBenchmark`), depois de remover uma busca em ThreadLocal por chamada e usar um CAS acquire no fim da chamada:

| Threads | Antes | Depois | Resilience4j 2.4.0 |
|---:|---:|---:|---:|
| 1 | 15.6 ns | **13.1 ± 0.1 ns** | 9039 ± 439 ns |
| 4 | 20.2 ns | **15.9 ± 0.7 ns** | 18423 ± 771 ns |

O overhead sobre o baseline cai de cerca de 14.5 para 12 ns numa thread. Um CAS só release seria ainda mais barato, mas permite uma interrupção antecipada (veja o comentário em `TimeoutPolicy.end`), então não é usado.

**Bulkhead, caminho síncrono depois da fila de espera assíncrona** (`BulkheadBenchmark`): `exit()` com `maxWait > 0` agora faz uma leitura volatile a mais, para ver se há chamadores assíncronos na fila. Sem custo mensurável: *Com espera* 23.1 ± 0.05 ns (eram 23.2) numa thread e 442 ± 3 ns (eram 447) com quatro; *Livre* 9.2 ns e *Cheio* 2.2 ns, sem mudança.

## Execuções em paralelo na AWS (`infra/`) :id=parallel-runs-on-aws-infra

`infra/bench.sh <base-rev> <new-rev> [include-regex]` executa todos os benchmarks em máquinas EC2 temporárias em paralelo e compara duas revisões do git. Uma execução completa (150 unidades benchmark/número de threads, 283 execuções de JMH) leva cerca de 20 minutos de ponta a ponta em vez de cerca de 3 horas numa máquina, e custa bem menos de US$1.

- **Configuração.** Cada revisão é compilada localmente num clone novo; o seu classpath de runtime de benchmark é empacotado e enviado para um bucket S3 por execução, então as máquinas só instalam uma JDK (Amazon Corretto 21) e executam `org.openjdk.jmh.Main`. O Terraform (`infra/main.tf`) cria uma VPC descartável, o bucket, um instance role limitado a esse bucket e as máquinas; `bench.sh` destrói tudo no final, também em caso de falha.
- **Justiça.** Uma unidade é um método de benchmark num número de threads. As suas execuções base e nova rodam uma após a outra na mesma máquina (alternando qual vai primeiro), então toda comparação base/nova é feita no mesmo hardware. As unidades são distribuídas da mais longa para a mais curta na máquina menos carregada (`infra/plan.py`).
- **Hardware.** `c7g.xlarge`: 4 núcleos Graviton3 sem SMT e com clock fixo (sem turbo), o que mantém as máquinas intercambiáveis: toda máquina também executa um benchmark de calibração (`BulkheadBenchmark.resilience4cataFree`, 1 thread), e em 19 máquinas ele mediu 20.45–20.47 ns (0.1% de dispersão). Os ajustes de JMH são os dos próprios benchmarks (3 forks × 5 × 1 s de aquecimento e medição, `-prof gc`).
- **Limites da conta.** Esta conta permite só 3 instâncias spot ao mesmo tempo (independentemente da sua cota spot de 96 vCPUs) e 64 vCPUs on-demand, então uma execução usa 3 máquinas spot e 16 on-demand do mesmo tipo (`MACHINES`, `SPOT_MACHINES`).
- Os números do Graviton não são comparáveis com os números do Apple Silicon nas seções acima; compare base e nova dentro de uma mesma execução.

## Conclusão do MVP: classifier, pipeline, eventos, testkit (2026-10-24, AWS Graviton3) :id=mvp-completion-classifier-pipeline-events-testkit-2026-10-24-aws-graviton3

Base: commit `3bc496f`. Nova: o trabalho integrado do classifier, do `Pipeline` programático, dos eventos e snapshots, e do testkit (`PolicyEnvironment`). Medido com `infra/bench.sh` como descrito acima. Duas regressões encontradas na primeira execução foram corrigidas e medidas de novo; a tabela mostra os números finais.

**Verificação de regressões** (resilience4cata, ns/op, todos 0 B/op nos caminhos síncronos):

| Benchmark | Threads | Base | Nova | Mudança |
|---|---:|---:|---:|---:|
| Retry, sucesso | 1 | 1.4 ± 0.0 | 1.2 ± 0.1 | −11% |
| Retry, sucesso | 4 | 1.3 ± 0.0 | 1.4 ± 0.1 | +3% |
| Retry, um retry | 1 | 7.3 ± 0.1 | 6.5 ± 0.1 | −11% |
| Retry, um retry | 4 | 7.3 ± 0.1 | 6.6 ± 0.1 | −10% |
| Retry, quatro serviços, um retry | 1 | 38.4 ± 0.1 | 38.8 ± 0.1 | +1% |
| Retry, quatro serviços, um retry | 4 | 39.2 ± 0.6 | 37.6 ± 1.2 | −4% |
| Circuit breaker, sucesso | 1 | 1.9 ± 0.0 | 1.9 ± 0.0 | 0% |
| Circuit breaker, sucesso | 4 | 1.9 ± 0.0 | 1.9 ± 0.0 | 0% |
| Circuit breaker, misto | 1 | 17.5 ± 0.1 | 17.2 ± 0.0 | −2% |
| Circuit breaker, misto | 4 | 282 ± 10 | 274 ± 29 | −3% |
| Circuit breaker (janela por tempo), misto | 4 | 126 ± 9 | 130 ± 10 | +3% |
| Rate limiter, livre | 1 | 42.2 ± 0.1 | 41.8 ± 0.1 | −1% |
| Rate limiter, livre | 4 | 188 ± 2 | 187 ± 2 | 0% |
| Rate limiter, esgotado | 4 | 35.0 ± 0.3 | 34.7 ± 0.2 | −1% |
| Bulkhead, livre | 1 | 20.5 ± 0.0 | 20.5 ± 0.0 | 0% |
| Bulkhead, cheio (rejeitando) | 1 | 2.1 ± 0.1 | 2.3 ± 0.0 | **+12%** |
| Bulkhead, cheio (rejeitando) | 4 | 2.0 ± 0.0 | 2.3 ± 0.0 | **+15%** |
| Bulkhead, modo com espera | 1 | 18.9 ± 0.0 | 18.9 ± 0.0 | 0% |
| Bulkhead, modo com espera | 4 | 359 ± 36 | 338 ± 38 | −6% |
| Timeout (síncrono) | 1 | 22.4 ± 0.2 | 22.2 ± 0.2 | −1% |
| Timeout (síncrono) | 4 | 23.5 ± 0.3 | 23.5 ± 0.5 | 0% |
| Circuit breaker assíncrono | 1 | 24.4 ± 0.1 | 24.4 ± 0.1 | 0% |
| Retry assíncrono | 1 | 24.1 ± 0.1 | 24.1 ± 0.1 | 0% |
| Rate limiter assíncrono | 4 | 275 ± 1 | 273 ± 6 | −1% |
| Timeout assíncrono, stage pendente | 1 | 152 ± 17 | 152 ± 12 | 0% |
| Pilha assíncrona (cinco políticas) | 1 | 122 ± 0.3 | 122 ± 0.2 | 0% |

- **As rejeições do bulkhead custam 0.3 ns a mais** (também medido no Apple Silicon: 2.06 → 2.36 ns). Um bulkhead cheio agora verifica o campo `final` do event listener antes de retornar `false`: o preço dos eventos de rejeição. Os caminhos de sucesso não têm essa verificação.
- **Corrigido: falhas de retry sob contenção.** A primeira execução mostrou *quatro serviços, um retry* com 4 threads indo de 37 para 57 ± 22 ns, com forks divididos entre ~40 e 60–85 ns. O `RetryPolicy` tinha ganhado um segundo `ClassValue` (para os veredictos do classifier) que nunca era consultado sem classifier. Todo `ClassValue` compartilha o pequeno cache de busca da classe da exceção, e em algumas JVMs o extra fazia as buscas colidirem e caírem no caminho mais lento do `ClassValue`, que escreve nesse cache compartilhado. Um `ClassValue` por política, retornando um veredicto de três valores, restaurou forks uniformes (39.2 → 37.6 ns).
- **Corrigido: timeout síncrono, uma thread** (22.4 → 23.8 ns, reproduzido duas vezes). O ramo de timeout estourado de `TimeoutPolicy.end` tinha crescido com a chamada do evento, o que afeta como o JIT faz inline do método por chamada. Mover esse ramo para o seu próprio método o trouxe de volta a 22.2 ns.
- Na primeira execução, *Bulkhead, modo com espera* com 4 threads mostrou 366 → 452 ns; uma repetição deu 359 → 338 ns, então essa diferença era ruído entre execuções da JVM, não uma regressão.

**Contra o Resilience4j 2.4.0 no Graviton3** (revisão nova, ns/op e B/op):

| Benchmark | Threads | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| Retry, sucesso | 1 | **1.2 ns**, 0 B | 22.9 ns, 0 B |
| Retry, um retry (Resilience4j sem o seu `sleep(0)`) | 1 | **6.5 ns**, 0 B | 61.6 ns, 16 B |
| Circuit breaker, sucesso (Resilience4j: janela por contagem, estratégia padrão `SYNCHRONIZED`) | 1 | **1.9 ns**, 0 B | 89.2 ns, 0 B |
| Circuit breaker, sucesso | 4 | **1.9 ns**, 0 B | 832 ns, 2 B |
| Circuit breaker, misto | 4 | **274 ns**, 0 B | 910 ns, 2 B |
| Rate limiter, livre | 4 | **187 ns**, 0 B | 275 ns, 40 B |
| Rate limiter, esgotado | 4 | **34.7 ns**, 0 B | 1599 ns, 505 B |
| Bulkhead, livre | 1 | 20.5 ns, 0 B | 20.4 ns, 0 B |
| Bulkhead, livre | 4 | **264 ns**, 0 B | 432 ns, 0 B |
| Bulkhead, cheio | 1 | **2.3 ns**, 0 B | 139 ns, 416 B |
| Timeout (síncrono) | 1 | **22.2 ns**, 0 B | 8842 ns, 176 B |
| Timeout assíncrono, stage pendente | 1 | **152 ns**, 192 B | 598 ns, 240 B |
| Timeout assíncrono, stage pendente | 4 | **369 ns**, 192 B | 1563 ns, 234 B |

**Classifier** (`ClassifierBenchmark`: um classifier baseado no resultado sobre a chamada protegida, todas as chamadas têm sucesso):

| Benchmark | Threads | resilience4cata | Resilience4j |
|---|---:|---:|---:|
| Circuit breaker, sem classifier | 1 | 2.1 ns, 0 B | — |
| Circuit breaker + classifier | 1 | **2.4 ns**, 0 B | 102 ns, 24 B (`recordResult`) |
| Circuit breaker + classifier | 4 | **2.4 ns**, 0 B | 902 ns, 18 B |
| Retry + circuit breaker + classifier | 1 | **2.7 ns**, 0 B | 148 ns, 104 B (`retryOnResult` + `recordResult`) |
| Retry + circuit breaker + classifier | 4 | **2.8 ns**, 0 B | 1274 ns, 90 B |

Um classifier custa cerca de 0.3 ns por política numa chamada bem-sucedida: uma chamada direta e monomórfica a `classify`.

**`Pipeline` programático** (`PipelineBenchmark`, ns/op; o código gerado e o pipeline alocam 0 B):

| Políticas | Threads | Gerado (anotações) | `Pipeline` | Resilience4j |
|---|---:|---:|---:|---:|
| Retry + circuit breaker | 1 | 2.3 | 2.9 | 175, 288 B |
| Retry + circuit breaker | 4 | 2.4 | 3.0 | 1647, 830 B |
| Retry, breaker, rate limiter, bulkhead | 1 | 61.6 | 62.3 | 261, 328 B |
| Retry, breaker, rate limiter, bulkhead | 4 | 318 | 294 | 1234, 350 B |
| As seis políticas | 1 | 76.3 | 80.3 | — |
| Lambda que captura (retry + breaker) | 1 | 2.4 | 2.8 | 190, 336 B |

- O pipeline acrescenta cerca de 0.5 ns sobre o código gerado nestas pilhas e continua livre de alocação, também com uma lambda que captura um argumento a cada chamada (a escape analysis a remove).
- `pipelinePolluted` (o mesmo pipeline depois de outros cinco pipelines passarem por `Pipeline.execute`) mede 7.9 ns numa thread contra 2.9 ns sem poluição: o único call site `call.call()` dentro de `Pipeline` vira megamórfico numa aplicação com muitos pipelines. O código gerado tem um call site por método e não tem esse custo.
- O Resilience4j não tem timeout síncrono na thread do chamador, então não há coluna do Resilience4j para seis políticas.

## Classes anotadas, genéricos, overrides de configuração, bulkhead com ticker (2026-10-24, AWS Graviton3) :id=annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3

Base: commit `53a6651`. Nova: classes anotadas (estilo subclasse), `@Fallback` em métodos genéricos e stages assíncronos com wildcard, `onExhaustedResult` / `GaveUp` em falha do classifier / `Pipeline.run` e fallbacks assíncronos do pipeline, overrides por instância no `config`, e o `maxWait` do bulkhead síncrono num ticker manual. Mesmo método da seção anterior (`infra/bench.sh`, 19 `c7g.xlarge`, 160 unidades, 329 execuções; calibração 20.51–20.54 ns, 0.1% de dispersão).

**Verificação de regressões** (resilience4cata, ns/op, 1 thread salvo indicação; todos 0 B/op nos caminhos síncronos):

| Benchmark | Base | Nova | Mudança |
|---|---:|---:|---:|
| Retry, sucesso | 1.3 ± 0.0 | 1.3 ± 0.0 | +2% |
| Retry, um retry | 6.5 ± 0.0 | 6.5 ± 0.0 | +1% |
| Retry, quatro serviços, um retry (4 threads) | 37.8 ± 0.9 | 37.7 ± 0.5 | 0% |
| Circuit breaker, sucesso | 1.8 ± 0.0 | 1.9 ± 0.0 | +1% |
| Circuit breaker, misto | 17.2 ± 0.0 | 17.2 ± 0.0 | 0% |
| Rate limiter, livre | 41.7 ± 0.1 | 41.7 ± 0.1 | 0% |
| Rate limiter, esgotado | 34.2 ± 0.1 | 34.6 ± 0.1 | +1% |
| Bulkhead, livre | 20.5 ± 0.0 | 20.5 ± 0.0 | 0% |
| Bulkhead, cheio | 2.3 ± 0.0 | 2.3 ± 0.0 | −1% |
| Bulkhead, modo com espera | 18.9 ± 0.0 | 18.9 ± 0.0 | 0% |
| Classifier em retry + breaker | 2.8 ± 0.0 | 2.7 ± 0.0 | −3% |
| Circuit breaker assíncrono | 24.4 ± 0.1 | 24.4 ± 0.1 | 0% |
| Timeout assíncrono, stage pendente | 149 ± 12 | 149 ± 12 | 0% |
| Pilha assíncrona (cinco políticas) | 122 ± 0.2 | 122 ± 0.2 | 0% |
| Timeout (síncrono) | 22.1 ± 0.2 | 23.7 ± 0.1 | **+7%** |
| Timeout (síncrono), 4 threads | 23.6 ± 0.2 | 23.4 ± 0.3 | −1% |

- Tudo com uma thread fica dentro de ±2%, exceto o timeout síncrono. Com quatro threads as diferenças vão nos dois sentidos dentro das suas barras de erro (por exemplo *Bulkhead, livre* 258 → 228 ± 32 ns, *Circuit breaker, misto* 257 → 271 ± 22 ns).
- **Em aberto: timeout síncrono +1.5 ns numa thread.** É estável (6 forks: 22.1–22.4 vs 23.6–23.8 ns, também com `-XX:-TieredCompilation`: 22.3–22.5 vs 23.6–23.8 ns), mas o código que ele executa não mudou: o `TimedService$Resilient` gerado, `TimeoutPolicy` e `Watchdog` são idênticos, o estado resolvido da política é idêntico, e o `ThreadLocal` do watchdog cai no seu slot de origem nos dois builds. Quatro threads não mostram diferença. A "correção" da seção anterior para o mesmo número (tirar o ramo de timeout estourado de `end`) pode ter sido o mesmo efeito de layout no sentido contrário. Próximo passo: `-prof perfasm` nas máquinas (precisa de `perf` e `hsdis`) para comparar o código compilado.
- A camada de retry com classifier ganhou um `try`/`catch` em volta de `classify` (para `GaveUp` quando o classifier lança exceção); os benchmarks de classifier não mostram custo.

**Novo nesta rodada** (ns/op, 0 B/op):

| Benchmark | Threads | Resultado |
|---|---:|---:|
| Retry, sucesso: classe anotada (subclasse) vs wrapper de interface | 1 | **1.0** vs 1.2 |
| Retry, um retry: classe anotada vs wrapper de interface | 1 | **5.6** vs 6.5 |
| Retry, um retry: classe anotada vs wrapper de interface | 4 | **5.5** vs 6.6 |
| `Pipeline.run` (chamada void), retry + circuit breaker | 1 | 3.0 (`execute`: 2.9) |

- O estilo subclasse é um pouco mais rápido que o wrapper: o override chama `super.method()` diretamente em vez de passar por um campo `delegate`, um load e uma chamada de interface a menos.
- `Pipeline.run` custa o mesmo que `execute` e não aloca nada.
- Não medido, por design: overrides de configuração por instância (resolvidos uma vez na criação) e o modo ticker do bulkhead (só relógios de teste; o caminho padrão acrescenta uma leitura de um boolean `final` depois de um `tryAcquire` que falhou, e *Bulkhead, modo com espera* não muda).

## Retry budget, 0.3.0 (2026-10-25, AWS Graviton3)

Base: `6d82305` (0.2.0). Nova: a integração da 0.3.0 (retry budget, módulos Micrometer, OpenTelemetry e JUnit, `onInstanceCreated`). Mesmo método de cima (`infra/bench.sh`, `c7g.xlarge`; calibração 20.48–20.53 ns, 0.2% de dispersão). Todo caminho síncrono continua em 0 B/op.

**Sem regressão sem budget** (ns/op, 1 / 4 threads):

| Benchmark | Base | Nova |
|---|---:|---:|
| `RetryBenchmark.resilience4cataSuccess` | 1.4 / 1.3 | 1.2 / 1.3 |
| `MultiTypeRetryBenchmark.resilience4cataSuccess` | 4.3 / 4.3 | 3.9 / 4.0 |
| `PipelineBenchmark.pipelineRetryCircuitBreaker` | 2.9 / 2.9 | 2.9 / 2.9 |
| `RetryBenchmark.resilience4cataRetryOnce` | 6.7 / 6.5 | 6.8 / 6.7 |
| `SubclassBenchmark.wrapperRetryOnce` | 6.6 / 6.5 | 6.8 / 6.8 |

Uma chamada retentada custa 0.1–0.3 ns a mais (a verificação de null do budget no caminho de falha); os sucessos não mudam.

**A contagem por chamada do budget** (`RetryBudgetBenchmark`, `budgetPercent = 1000` para que todo retry seja concedido; ns/op, 1 / 4 threads; sem budget: 1.3 / 1.3 sucesso, 6.9 / 7.1 retentada):

| Como uma chamada é contada | Sucesso | Retentada uma vez |
|---|---:|---:|
| Loop de compare-and-set na stripe da thread (`threadId & mask`) | 9.7 / 9.8 | 64 / 380 |
| Uma soma atômica (`getAndAddRelease`, LDADD) numa stripe por hash | 12.0 / 12.0 | 73 / 400 |
| **Leitura e escrita simples numa stripe por hash (entregue)** | **3.5 / 3.6** | **62 / 301** |

Duas lições. Um compare-and-set, mesmo sem contenção numa linha que nenhuma outra thread escreve, custou cerca de 8 ns por chamada no Graviton3, e a soma atômica de instrução única mais; o contador entregue é, em vez disso, com perdas (duas threads que compartilham uma stripe e disputam podem perder uma chamada, o que só reduz o budget). E tomar a stripe dos bits baixos do id da thread colocava todo worker do JMH numa só stripe (os seus ids eram múltiplos do número de stripes): a versão com compare-and-set pagou por isso em contenção (380 ns), a com perdas perdeu tantas chamadas que o budget recusava retries mesmo com dez vezes a margem necessária. Misturar o id primeiro (hashing de Fibonacci) corrigiu as duas. Com 4 threads, uma chamada retentada é dominada pelo contador de retries compartilhado e pela leitura do relógio, só no caminho de falha.

## Hedge e cancelamento assíncrono, 0.4.0 (2026-10-25, AWS Graviton3) :id=hedge-and-async-cancellation-040-2026-10-25-aws-graviton3

Base: `4fc079a` (0.3.0 mais docs). Nova: a integração da 0.4.0 (cancelamento para baixo em toda política assíncrona, `@Hedge`, subclasses geradas que já não são `final`). Mesmo método de cima (`infra/bench.sh`, `c7g.xlarge`; calibração 20.51–20.54 ns).

**Cancelamento assíncrono.** A primeira versão ligava o future de cada política ao seu stage interno com um release store no construtor. No Graviton3 o C2 compilou isso em duas barreiras completas extras (`dmb ish`) por camada de política, cerca de 5 ns cada (circuit breaker assíncrono 24.4 → 29.1 ns); no Apple Silicon as mesmas barreiras ficavam dentro do ruído. Um store simples basta (o future é sempre repassado com um happens-before) e reduz o custo a cerca de 0.5 ns por camada (ns/op, 1 / 4 threads; alocação sem mudança):

| Benchmark | Base | Release store | Store simples (entregue) |
|---|---:|---:|---:|
| `AsyncBenchmark.resilience4cataCircuitBreaker` | 24.4 / 24.9 | 29.1 / 29.9 | 24.9 / 25.6 |
| `AsyncBenchmark.resilience4cataRetry` | 24.1 / 24.6 | 28.9 / 29.5 | 24.7 / 25.3 |
| `AsyncBenchmark.resilience4cataBulkhead` | 41.8 / 392 | 46.2 / 503 | 42.8 / 418 |
| `AsyncBenchmark.resilience4cataRateLimiter` | 66.0 / 275 | 71.2 / 294 | 66.9 / 278 |
| `AsyncBenchmark.resilience4cataStack` | 121.5 / 573 | 139.3 / 554 | 123.5 / 558 |

Uma refatoração na mesma mudança também moveu o callback assíncrono do bulkhead para um helper compartilhado; o nível de chamada extra impediu o C2 de fazer inline do caminho empilhado, e a escape analysis deixou de remover um objeto (a pilha de quatro políticas foi de 200 para 224 B/op). O gate de alocação pegou isso; o callback voltou a ficar num só método.

**Hedge** (novo, sem base; ns/op e B/op, 1 / 4 threads). O `resilience4j-hedge` do Resilience4j 2.4.0 com os mesmos ajustes (veja o Javadoc de `HedgeBenchmark` para os ajustes):

| Benchmark | resilience4cata | Resilience4j 2.4.0 | `CompletableFuture` escrito à mão |
|---|---:|---:|---:|
| primeira tentativa já completa | 9.4 / 9.7 ns, 24 B | — | — |
| a primeira tentativa ganha antes do atraso | 154 / 267 ns, 216 B | 3601 / 5577 ns, 1561 B | 649 / 1968 ns, 297 B |
| igual, sem budget de hedge | 150 / 268 ns, 216 B | | |
| o hedge dispara (atraso de 1 ms; µs) | 2114 / 2116 µs | 1067 / 1070 µs | |

Quando o hedge dispara, o tempo é o atraso mais a precisão do timer de cada biblioteca: o nosso trabalha em ticks de um dezesseis avos do atraso, no mínimo 1 ms, e dispara até cerca de dois ticks atrasado, então um atraso de 1 ms faz o hedge depois de cerca de 2 ms; o scheduled executor do Resilience4j dispara na hora, mas deixa todo atraso cancelado na fila, que é para onde vai a maior parte do seu custo no caminho comum. As subclasses geradas sem `final` não mudaram nada mensurável (`SubclassBenchmark` dentro do ruído).

## Adaptive limit, 0.6.0 (2026-10-26, AWS Graviton3)

Base: `67e1ebd` (0.5.0 mais docs). Nova: a integração do `@AdaptiveLimit`. Mesmo método de cima (`infra/bench.sh`, `c7g.xlarge`, 3 forks; calibração 20.47–20.50 ns em 19 máquinas). `AdaptiveLimitBenchmark` é novo, então não tem base; ns/op e B/op, 1 / 4 threads, todas as threads no mesmo limiter (ajustes de justiça no Javadoc do benchmark: mesmos limites, a tolerância e as janelas padrão da Netflix, e `minRttThreshold(0)` para que a Netflix registre toda amostra, como faz o `@AdaptiveLimit`):

| Benchmark | ns/op, 1 / 4 threads | B/op, 1 / 4 threads |
|---|---:|---:|
| `@AdaptiveLimit` (Gradient2, limite nunca alcançado) | 86.3 / 380 | 0 / 0 |
| igual, assíncrono sobre um stage completo | 108.7 / 565 | 48 / 48 |
| igual, toda chamada rejeitada (limite cheio) | 2.1 / 2.1 | 0 / 0 |
| `@Bulkhead` fixo com o mesmo limite | 20.5 / 284 | 0 / 0 |
| Netflix concurrency-limits 0.5.4, `SimpleLimiter` + `Gradient2Limit` com janela | 118.0 / 1087 | 40 / 95 |
| Netflix, toda chamada rejeitada | 3.0 / 2.9 | 0 / 0 |

- **O relógio é a diferença com o bulkhead fixo.** Um adaptive limit precisa cronometrar toda chamada, então lê `System.nanoTime()` duas vezes. Nesta máquina uma leitura custa cerca de 33 ns (o rate limiter esgotado, que o lê uma vez e faz pouco mais, leva 34.6 ns), então as duas leituras explicam os 66 ns entre `@AdaptiveLimit` e `@Bulkhead` numa thread; o trabalho próprio do limiter (o slot, a amostra na stripe desta thread, a verificação da janela) fica dentro do ruído disso.
- **Contra o limiter da Netflix:** 27% mais rápido numa thread e 2.9 vezes mais rápido com quatro, sem alocar. A Netflix aloca o `Optional` e o listener de cada chamada, e a sua amostra com janela é compartilhada por todas as threads, enquanto o `@AdaptiveLimit` escreve cada amostra na stripe da thread que chama e deixa só a thread que encerra a janela ler todas.
- **As rejeições só leem:** 2.1 ns qualquer que seja o número de threads.

O resto da suíte não se moveu, exceto o `Pipeline`: um pipeline construído sem `adaptiveLimit` agora verifica um campo null a mais por chamada, +0.2 ns (`pipelineRetryCircuitBreaker` 3.0 → 3.2 ns; o código gerado não muda). Quatro benchmarks com 4 threads se moveram 15–48% na primeira execução (`CircuitBreakerBenchmark.resilience4cataMixed`, `PipelineBenchmark.pipelineFour`, `BulkheadBenchmark.resilience4cataWaiting`, `HedgeBenchmark.resilience4cataPending`) embora o seu código não tenha mudado; com 6 forks cada, as faixas base e nova se sobrepõem totalmente (−7% a +5%), então era ruído.

## Suporte a Reactor e o rate limiter assíncrono, 0.11.0 (2026-10-27, AWS Graviton3) :id=reactor-support-and-the-async-rate-limiter-0110-2026-10-27-aws-graviton3

Base: `9a26494` (0.10.0 mais docs). Nova: `resilience4cata-reactor` e o rate limiter assíncrono que já não estaciona (park). Mesmo método de cima (`infra/bench.sh`, `c7g.xlarge`, 3 forks; calibração 20.45–20.48 ns em 19 máquinas).

**Métodos `Mono` contra o resilience4j-reactor 2.4.0.** `ReactorBenchmark` é novo, então não tem base. Cada benchmark constrói o `Mono` protegido e se inscreve nele; o Resilience4j usa os seus operadores Reactor com `transformDeferred` e os ajustes de `AsyncBenchmark` (notas de justiça no Javadoc do benchmark). A fonte é `Mono.just` (o valor já está lá na inscrição), ou para `*Pending` um `Sinks.One` preenchido logo após a inscrição, para que toda camada espere. ns/op e B/op, 1 / 4 threads; os bytes são os do fork típico (veja a nota abaixo):

| Benchmark | resilience4cata, ns/op | Resilience4j, ns/op | resilience4cata, B/op | Resilience4j, B/op |
|---|---:|---:|---:|---:|
| sem política (`baseline`) | 52.4 / 53.1 | — | 88 | — |
| circuit breaker | **130.2 / 118.8** | 246.4 / 1378.7 | 200 | 248 |
| retry, a primeira tentativa tem sucesso | **127.8 / 139.0** | 726.2 / 734.1 | 200 | 1000 |
| timeout | **105.2 / 105.3** | 3165.5 / 6229.7 | 176 | 1344 |
| bulkhead | **144.1 / 621.7** | 157.5 / 683.7 | 200 | 200 |
| rate limiter | **166.5 / 351.6** | 184.2 / 659.3 | 200 | 240 / 195 |
| retry + breaker + timeout + bulkhead | **227.3 / 587.7** | 4337.8 / 5598.1 | 368 | 2624 |
| mesma pilha, `MonoPipelineOperator` | 251.0 / 606.2 | (a mesma pilha do Resilience4j) | 448 | — |
| sem política, pendente (`baselinePending`) | 152.7 / 154.3 | — | 168 | — |
| timeout, pendente | **441.0 / 560.5** | 3725.1 / 6332.8 | 480 | 1787 |
| pilha, pendente | **703.1 / 935.5** | 4394.8 / 6513.9 | 912 | 2704 |

- **Todo par é mais rápido, com uma thread e com quatro.** As diferenças: 1.9× (breaker, uma thread) a 11.6× (breaker, quatro threads), 5.7× para o retry, 19× para a pilha numa thread. O bulkhead é o mais próximo: 9% numa thread, e dentro do erro com quatro (621.7 ± 107.6 vs 683.7 ± 38.0), onde os dois pagam por um contador compartilhado.
- **O timeout:** um valor que já está lá na inscrição não pode perder o seu prazo, então o `@Timeout` não arma timer para ele (105 ns); o `TimeLimiterOperator` do Resilience4j é `Mono.timeout`, que agenda uma tarefa no scheduler paralelo do Reactor para cada inscrição. Com um valor pendente os dois armam um timer, e o resilience4cata continua 8.4× (uma thread) e 11.3× (quatro) mais rápido.
- **Bytes por chamada, além dos 88 B da inscrição:** uma política em volta de um `Mono` constante custa 112 B (o `Mono` do método e a sua lambda, a inscrição que entrega o valor, a lambda da chamada, o future da política, o future completo da constante); o seu valor é lido sem se inscrever, como fazem os próprios operadores do Reactor. Esses objetos sofrem scalar replacement em alguns forks e não em outros: o mesmo benchmark mediu 200 B num fork e 224–280 B em outro, então os orçamentos do gate de alocação são o fork mais alto, não estes valores típicos.

**O rate limiter assíncrono já não estaciona.** `RateLimiterPolicy.reserve` estacionava por 1 ns depois de perder o CAS no seu `tat`, como o `tryAcquire` síncrono. Um chamador assíncrono pode ser uma thread de event loop, que nunca deve estacionar, e o BlockHound reportou isso (`MonoBlockHoundTest` em examples), então `reserve` agora tenta o CAS de novo na hora (`Thread.onSpinWait()` também é reportado pelo BlockHound). `AsyncBenchmark.resilience4cataRateLimiter`, base → nova: 67.1 → 66.9 ns numa thread, 277.7 ± 0.6 → 297.2 ± 28.1 ns com quatro (+7%); 48 B nos dois casos. Os outros métodos de `AsyncBenchmark` medidos não se moveram (circuit breaker 25.0 → 24.9 / 25.6 → 25.6, pilha 123.6 → 123.4 / 589.0 → 558.0). No Apple M1 (JDK 21, 4 forks) a mesma mudança custou muito mais sob contenção, 264.5 ± 52.2 → 661.0 ± 29.0 ns com quatro threads, onde estacionar também afastava os perdedores do `System.nanoTime()` disputado do macOS; um backoff exponencial com `onSpinWait` (até 64 giros) mediu os mesmos 659.9 ± 16.0 ns ali e foi rejeitado. O caminho síncrono continua estacionando.

## Métodos Flux e stages já concluídos, 0.11.0 (2026-10-29, AWS Graviton3) :id=flux-and-done-stages

Base: `4649484` (suporte a `Flux` antes deste ajuste). Nova: `53cba0a`: os elementos de uma tentativa sem timeout vão ao subscriber como estão, um serializador mais barato quando um timeout escuta, nenhuma fronteira de tentativa sem retry nem fallback, os caminhos rápidos do árbitro, e o atalho de já concluído do circuit breaker, bulkhead, rate limiter e adaptive limit. Mesmo método de cima (`infra/bench.sh`, `c7g.xlarge`, 3 forks; calibração 20.51–20.54 ns em 19 máquinas).

**Métodos `Flux` contra o resilience4j-reactor 2.4.0.** `FluxBenchmark` é novo, então não tem base. Cada benchmark constrói o `Flux` protegido, se inscreve, e todo elemento chega a um `Blackhole`; uma operação é um stream inteiro. O Resilience4j usa os seus operadores Reactor com `transformDeferred` e os ajustes de `ReactorBenchmark`; num `Flux` o seu time limiter é `flux.timeout`, um prazo reiniciado por cada elemento como o `@Timeout` (notas de justiça no Javadoc do benchmark). Dez elementos já presentes na inscrição salvo indicação; `pending`: dez elementos emitidos logo após a inscrição, para que toda camada espere e os dois timeouts armem um timer; `1000 elements`: mil elementos presentes na inscrição. ns/op 1 / 4 threads, B/op do fork típico numa thread:

| Benchmark | resilience4cata, ns/op | Resilience4j, ns/op | resilience4cata, B/op | Resilience4j, B/op |
|---|---:|---:|---:|---:|
| sem política (`baseline`) | 56.0 / 57.3 | — | 96 | — |
| circuit breaker | **195.1 / 189.3** | 272.3 / 1322.0 | 283 | 216 |
| retry, a primeira tentativa tem sucesso | **270.3 / 215.8** | 1196.8 / 1257.1 | 392 | 1024 |
| timeout | **680.5 / 688.4** | 28896.1 / 63439.9 | 384 | 3983 |
| bulkhead | **212.3** / 754.9 | 244.6 / **697.2** | 288 | 219 |
| rate limiter | 240.1 / **354.1** | **234.3** / 885.4 | 283 | 248 |
| retry + breaker + timeout + bulkhead | **788.8 / 984.3** | 30583.2 / 63403.4 | 616 | 5104 |
| mesma pilha, `FluxPipelineOperator` | 824.4 / 1040.0 | (a mesma pilha do Resilience4j) | 752 | — |
| sem política, pendente (`baselinePending`) | 1088.3 / 1004.5 | — | 1248 | — |
| timeout, pendente | **1842.8 / 2135.3** | 30396.9 / 50648.3 | 1656 | 5390 |
| pilha, pendente | **2157.6 / 2703.1** | 31580.6 / 55194.6 | 2224 | 6264 |
| sem política, 1000 elementos (`baselineLong`) | 1043.2 / 1042.6 | — | 96 | — |
| timeout, 1000 elementos | **45057.8 / 45107.2** | 2632248.7 / 5172988.8 | 384 | 241529 |
| pilha, 1000 elementos | **46189.5 / 46545.0** | 2822600.4 / 5586559.5 | 579 | 242903 |

- **O timeout é onde está a diferença:** o `flux.timeout` do Reactor agenda uma tarefa no scheduler paralelo para cada elemento (cerca de 2.6 µs e 240 B por elemento aqui); o `@Timeout` lê o relógio e escreve um campo por elemento e não move nenhum timer (cerca de 44 ns por elemento, 0 B), então mil elementos levam 45 µs contra 2.6 ms. Com dez elementos: 42× numa thread, 92× com quatro; a pilha 39× e 64×.
- **O circuit breaker** é 1.4× mais rápido numa thread e 7× com quatro, onde o breaker do Resilience4j sofre contenção. **O retry** é 4.4× e 5.8× mais rápido: o `RetryOperator` do Resilience4j constrói um contexto de retry e uma maquinaria de reinscrição por inscrição.
- **Dois casos ficam dentro do erro:** o bulkhead com quatro threads (754.9 ± 36.7 vs 697.2 ± 41.8, os dois pagando por um contador compartilhado, como nos métodos `Mono`) e o rate limiter numa thread (240.1 ± 9.7 vs 234.3 ± 0.9). O bulkhead numa thread e o rate limiter com quatro são mais rápidos (1.2× e 2.5×).
- **Bytes:** um stream protegido aloca o seu `Flux`, a inscrição da chamada, a tentativa e o seu subscriber, e as lambdas das camadas, cerca de 190 B além da inscrição, uma vez por stream; os seus elementos não alocam nada. O Resilience4j aloca menos para um único breaker, bulkhead ou rate limiter (um subscriber cada) e muito mais assim que entra um retry ou um timeout.

**Ajuste, medido no Apple M1 enquanto era feito (JDK 21, 2 forks, dez elementos):** a primeira versão perdia nas políticas isoladas (circuit breaker 428 ns contra 148 ns). Dois CAS por elemento para serializar os elementos contra o fim custavam cerca de 8.5 ns por elemento; o par store-buffering que os substituiu, cerca de 7–11 ns (a sua leitura volatile depois de uma escrita volatile espera o store buffer), então os elementos de uma tentativa sem timeout, cujo fim só pode vir do seu próprio stream, o pulam por completo (circuit breaker 217 → 146 ns ali). O atalho de já concluído e a fronteira que se pula sem retry levaram o resto.

**O atalho de já concluído nos benchmarks existentes**, base → nova, ns/op 1 / 4 threads (bytes numa thread):

| Benchmark | Base | Nova |
|---|---:|---:|
| `AsyncBenchmark` circuit breaker | 24.9 / 25.5 (48 B) | **9.7 / 9.2** (24 B) |
| `AsyncBenchmark` bulkhead | 42.7 / 396.1 (48 B) | **28.4 / 312.1** (24 B) |
| `AsyncBenchmark` rate limiter | 67.1 / 283.8 (48 B) | **52.5** / 289.6 (24 B) |
| `AsyncBenchmark` pilha | 123.4 / 586.2 (200 B) | **91.2 / 474.5** (152 B) |
| `AdaptiveLimitBenchmark` assíncrono | 108.6 / 505.1 (48 B) | **94.7** / 499.8 (24 B) |
| `ReactorBenchmark` circuit breaker | 129.3 / 121.7 | **105.7 / 105.3** (176 B) |
| `ReactorBenchmark` bulkhead | 140.8 / 514.5 | **126.4** / 525.3 (176 B) |
| `ReactorBenchmark` rate limiter | 166.7 / 360.7 | **146.5** / 377.6 (176 B) |
| `ReactorBenchmark` pilha | 228.5 / 567.4 | **201.4 / 540.8** (376 B) |

Um stage que já está completo quando a chamada retorna (um valor em cache, `Mono.just`, um stream que terminou durante a sua inscrição) agora é registrado na hora e respondido com uma cópia completa, como o timeout já fazia, em vez de um future ligado, uma lambda e o future dependente de `whenComplete`. O retry, o timeout, o hedge, os stages pendentes e todo caminho síncrono não se moveram além do erro (`PipelineBenchmark`, `HedgeBenchmark`, `AsyncBenchmark.resilience4cataRetry`/`Timeout*`, `ReactorBenchmark.*Pending`: dentro de ±9%, todos dentro das suas barras de erro). Os números do Resilience4j em `ReactorBenchmark` também não se moveram (circuit breaker 243.3 / 1290.7, pilha 4318.7 / 6671.2), então contra o resilience4j-reactor o circuit breaker `Mono` agora é 2.3× mais rápido numa thread e 12.3× com quatro.

## Beans de frameworks e uma comparação completa, 0.13.0 (2026-11-01, AWS Graviton3) :id=frameworks

Todos os benchmarks contra todos os rivais, mais três novos que chamam os beans que um framework entrega a uma aplicação: `SpringBenchmark` (Spring Boot 4.1.2, os aspects do resilience4j-spring-boot4 2.4.0), `MicronautBenchmark` (Micronaut 5.2.2 na JDK 25, os interceptors do resilience4j-micronaut 2.4.0 e os próprios `@Retryable`/`@CircuitBreaker` do Micronaut) e `QuarkusBenchmark` (Quarkus 3.40.1, as anotações do SmallRye Fault Tolerance e decorators do Resilience4j construídos num bean). Os beans do resilience4cata são as subclasses geradas que cada integração cria. Nova: `f6e9097`. Mesmo método de cima (`infra/bench.sh`, `c7g.xlarge`, 3 forks; calibração 20.48–20.53 ns em 19 e 10 máquinas, duas execuções). Os ajustes são os dos benchmarks do core (retry de 3 tentativas sem atraso, breaker com janela por contagem de 100, bulkhead de 1000 sem espera, rate limiter que nunca se esgota, timeout de 1 s); cada setup verifica que toda instância do Resilience4j participa da sua chamada, para que um interceptor ausente não possa parecer um rápido (notas de justiça no Javadoc de cada benchmark). O bean sem políticas custa 0.5–1.6 ns em todos os frameworks.

**Beans de frameworks**, ns/op 1 / 4 threads, contra o rival mais rápido nesse framework (B/op numa thread: o resilience4cata aloca 0 B em todas as linhas):

| Chamada | Spring: resilience4cata | Spring: Resilience4j | Micronaut: resilience4cata | Micronaut: rival mais rápido | Quarkus: resilience4cata | Quarkus: rival mais rápido |
|---|---:|---:|---:|---:|---:|---:|
| retry, a primeira tentativa tem sucesso | **1.1 / 1.1** | 1346.0 / 1530.9 | **1.1 / 1.0** | 104.8 / 100.4 (R4j) | **1.1 / 1.1** | 22.9 / 51.5 (R4j) |
| retry, uma falha | **6.1 / 6.7** | 5068.3 / 6007.2 | **10.2 / 9.3** | 959.5 / 1758.8 (R4j / Micronaut) | **6.2 / 6.3** | 471.3 / 568.4 (R4j) |
| circuit breaker | **1.5 / 1.4** | 1737.0 / 2142.5 | **1.5 / 1.5** | 304.5 / 1500.7 (R4j) | **1.5 / 1.5** | 101.6 / 897.6 (R4j) |
| circuit breaker aberto | **2.3 / 2.3** | 4174.2 / 4444.6 | **2.3 / 2.3** | 1127.4 / 1316.6 (R4j) | **2.3 / 2.3** | 355.9 / 452.7 (R4j) |
| bulkhead | **20.4 / 280.4** | 1687.7 / 4411.2 | **20.7 / 279.9** | 490.2 / 2797.8 (R4j) | 20.4 / **270.2** | 20.3 / 460.8 (R4j) |
| rate limiter | **42.0 / 184.4** | 1481.8 / 3956.2 | **42.0 / 181.7** | 141.9 / 629.8 (R4j) | **42.0 / 180.6** | 66.7 / 270.2 (R4j) |
| timeout, síncrono | — | — | — | — | **24.2 / 23.2** | 1510.2 / 2246.9 (FT) |
| timeout, future completo | **3.7 / 3.7** | 3632.2 / 8404.7 | **4.2 / 4.2** | 27816.7 / 17510.6 (R4j) | **3.7 / 3.7** | 444.1 / 1194.5 (R4j) |
| retry + breaker + rate limiter + bulkhead | **62.1 / 289.6** | 6104.4 / 8317.4 | **62.1 / 268.2** | 1117.9 / 3474.8 (R4j) | **62.0 / 329.3** | 217.2 / 1067.9 (R4j) |

- **Spring:** os aspects do resilience4j-spring-boot4 custam 1.3–6 µs e 1.6–6.3 KB por chamada: o seu pointcut é dinâmico, e avaliá-lo carrega uma classe pelo nome (`Class.forName`) a cada chamada. A subclasse do resilience4cata é um override simples, então um bean Spring custa o que a política custa nos benchmarks do core.
- **Micronaut:** os interceptors do resilience4j-micronaut custam 100 ns–1.1 µs; os próprios `@Retryable` e `@CircuitBreaker` do Micronaut custam 1.0–1.8 µs e 2.3–3.6 KB (`micronautRetry`, `micronautCircuitBreaker`). O timeout do Resilience4j através do interceptor varia entre forks (27.8 ± 23.1 µs).
- **Quarkus:** os interceptors do Fault Tolerance custam 0.3–1.9 µs e 0.6–2.2 KB por chamada; os decorators do Resilience4j, chamados à mão de um bean sem nenhum interceptor, são o rival mais rápido em todas as linhas exceto o timeout síncrono (o Resilience4j não tem). O seu bulkhead (dois atômicos, como o nosso) empata numa thread: 20.3 ± 0.0 contra 20.4 ± 0.1.

**Os outros benchmarks**, as mesmas linhas das seções acima, reexecutados contra a variante mais rápida do Resilience4j (`LockFree`, `Synchronized`, `NoSleep`) ou o limiter da Netflix: 156 casos comparáveis nesta execução e nos benchmarks de frameworks, 150 ganhos. Os outros seis são o bulkhead do Quarkus numa thread (acima) e os cinco abaixo; o bulkhead com espera permitida é uma vitória apertada numa thread e um empate com quatro, então também está listado:

| Benchmark | resilience4cata | Rival mais rápido | Por quê |
|---|---:|---:|---|
| `HedgeBenchmark` o hedge dispara (atraso de 1 ms), 1 / 4 threads | 2114.7 / 2116.6 | **1067.6 / 1069.4** | os ticks grossos do `TimeoutTimer`: nunca antes, até ~2 ticks atrasado, então um hedge de 1 ms começa depois de ~2 ms |
| `BulkheadBenchmark` slot livre, 1 thread | 20.5 ± 0.0 | **20.4 ± 0.0** | os dois são dois atômicos: o piso do hardware |
| `BulkheadBenchmark` espera permitida, 1 / 4 threads | **18.9** / 427.0 ± 85.1 | 20.4 / 445.6 ± 52.6 | um empate com quatro threads |
| `FluxBenchmark` bulkhead, 4 threads | 740.4 ± 83.9 | 688.2 ± 38.6 | dentro do erro (309 B contra 203 B) |
| `FluxBenchmark` rate limiter, 1 thread | 256.6 ± 22.9 | 234.2 ± 1.0 | dentro do erro (296 B contra 248 B) |

## Atrasos do hedge na hora certa (2026-11-01, AWS Graviton3) :id=hedge-precision

Base: `04e045e` (main). Nova: `7c9f25f`: o atraso de um hedge vai para uma faixa precisa do `TimeoutTimer`; o chamador passa o seu início (uma leitura de relógio), o prazo é `start + delay`, e a thread do timer acorda no mais cedo desses prazos em vez de só no próximo tick. Mesmo método de cima (`infra/bench.sh`, `c7g.xlarge`; três execuções, as duas últimas com 6 forks; calibração 20.49–20.54 ns). ns/op 1 / 4 threads (µs para o hedge que dispara):

| Benchmark | Base | Nova | Resilience4j |
|---|---:|---:|---:|
| o hedge dispara (atraso de 1 ms), µs | 2113.5 / 2116.8 | **1059.6 / 1067.7** | 1067.3 / 1070.5 |
| a primeira tentativa ainda em execução, a chamada retorna | 149.9 / 280.3 | 179.4 / 259.1 | 3667.7 / 5441.8 |
| igual, sem budget | 152.4 / 256.7 | 180.1 / 268.7 | — |
| primeira tentativa já completa | 9.4 / 9.7 | 9.5 / 9.7 | — |

- **O hedge que dispara** começava até dois ticks do timer atrasado: com um atraso de 1 ms o tick é 1 ms, o timer pegava a entrada até um tick depois da chamada e então esperava o atraso inteiro, então neste loop fechado toda chamada pagava os dois (2.1 ms). Agora ele dispara no atraso mais o despertar da thread, como faz o `ScheduledThreadPoolExecutor` do Resilience4j: 0.7% mais rápido numa thread, 0.3% com quatro (dentro do erro ali). Nenhuma das bibliotecas consegue ir mais baixo: as duas esperam o atraso e o despertar do SO.
- **O preço é uma leitura de relógio** quando a primeira tentativa ainda está em execução quando a chamada retorna, cerca de 29 ns numa thread (com quatro threads a mudança fica dentro do erro). Uma tentativa já completa não lê relógio. Ainda cerca de 20× mais rápido que o `resilience4j-hedge`.
- **Os timeouts assíncronos não se moveram** (`AsyncBenchmark.resilience4cataTimeout*`, `TimeoutBenchmark`, os timeouts de `ReactorBenchmark` e `FluxBenchmark`: dentro de ±5%). Uma primeira versão pegava toda inbox da mais antiga para a mais nova invertendo-a, o que fazia a thread do timer percorrer cada inbox duas vezes: `AsyncBenchmark.resilience4cataTimeoutPending` com quatro threads foi de 436 para 514 ns em duas execuções, já que a thread do timer divide os quatro núcleos com as threads do benchmark. Agora só as entradas precisas são reordenadas, e as simples são pegas numa só passada como antes (455.0 ± 61.1 → 437.1 ± 36.7).

## Streams Flux: um objeto a menos por tentativa, nenhuma cópia por camada (2026-11-01, AWS Graviton3) :id=flux-ended

Base: `04e045e` (main). Nova: `b6d6ac4`: uma tentativa `Flux` é o seu próprio subscriber (um objeto em vez de dois), e um stream que terminou durante a sua inscrição atravessa toda camada de política como um único stage compartilhado e imutável (`Elements.ended()`) em vez de uma cópia completa por camada; o timeout, o `Pipeline` e a fronteira da tentativa o reconhecem. Mesmo método de cima (`infra/bench.sh`, `c7g.xlarge`, 3 forks em 19 máquinas; as linhas de bulkhead, rate limiter e resilience4j de novo com 6 forks, que são as mostradas para elas; calibração 20.47–20.53 ns). ns/op 1 / 4 threads, B/op do fork típico numa thread:

| `FluxBenchmark` | Base | Nova | Base B/op | Nova B/op | Resilience4j |
|---|---:|---:|---:|---:|---:|
| circuit breaker | 189.2 / 204.0 | **182.8 / 200.5** | 288 | 264 | 272.2 / 1401.7 |
| bulkhead | 211.1 / 762.2 | **194.6 / 702.8** | 288 | 264 | 267.7 / 721.0 |
| rate limiter | 239.8 / 376.7 | **222.5 / 348.2** | 283 | 264 | 229.2 / 843.0 |
| retry, a primeira tentativa tem sucesso | 249.7 / 232.3 | 252.8 / 222.7 | 392 | 392 | 1202.0 / 1277.3 |
| timeout | 676.9 / 683.9 | **665.7 / 666.2** | 384 | 360 | 29051.8 / 63827.3 |
| retry + breaker + timeout + bulkhead | 787.8 / 1023.1 | **751.3 / 928.8** | 616 | 512 | 31170.0 / 61439.4 |
| mesma pilha, `FluxPipelineOperator` | 822.6 / 1047.2 | **778.6 / 975.3** | 752 | 648 | — |

- **Os dois casos `Flux` que estavam dentro do erro** em [a comparação completa](#frameworks) agora estão à frente: o rate limiter numa thread por 3% (os forks do Resilience4j mediram 234 ns, exceto um em 205 ns), e o bulkhead com quatro threads por 2.5%, ainda dentro das duas barras de erro (os nossos forks 592–804 ns, os do Resilience4j 695–742 ns: os dois pagam por um contador compartilhado sob contenção, como nos métodos `Mono`). O bulkhead numa thread é 1.4× mais rápido.
- **Bytes**: 24 B a menos por stream para cada política isolada, 104 B para as pilhas. Um stream ainda aloca 27–48 B a mais que o operador do resilience4j-reactor para um breaker, bulkhead ou rate limiter (o seu `Flux`, a inscrição da chamada, a tentativa e as lambdas das camadas, contra um subscriber de operador), e muito menos assim que entra um retry ou um timeout.
- **Sem mudança:** streams pendentes e longos, o retry (a sua tentativa já pulava a fronteira para um stream que terminou), e todas as linhas de `ReactorBenchmark` (`Mono`) e `AsyncBenchmark`, dentro do erro. Uma primeira execução mostrou o rate limiter `Flux` com quatro threads 10% mais lento e o retry `Mono` até 10% mais lento, cada um por um ou dois forks lentos; com seis forks os dois ficaram 8% mais rápidos e sem mudança. O adaptive limit síncrono com quatro threads (`AdaptiveLimitBenchmark.resilience4cata`, código que esta mudança não toca) mediu +40% nessa execução e +6% com seis forks (342.3 ± 43.6 → 364.2 ± 45.2), enquanto o bulkhead simples ao lado se moveu −12%: as linhas de quatro threads ali variam tanto assim de uma execução para outra.

## Um bulkhead com espera com os atômicos do bulkhead sem espera (2026-11-01, AWS Graviton3) :id=bulkhead-semaphore

Base: `410a649` (main). Nova: `6395eb1`: com `maxWait > 0` o bulkhead usava `java.util.concurrent.Semaphore`, cujos `tryAcquire` e `release` são loops de compare-and-set com full fence sobre o estado do AQS; agora usa `BulkheadSemaphore`, cujas permissões são um contador com padding tomado com uma leitura opaque e um compare-and-set acquire, como sem espera, enquanto o `AbstractQueuedSynchronizer` só enfileira e estaciona as threads que esperam. Uma liberação é uma soma volatile seguida do despertar da fila. Mesmo método de cima (`infra/bench.sh`, `c7g.xlarge`, 6 forks em 8 máquinas; calibração 20.48–20.51 ns). ns/op 1 / 4 threads:

| Benchmark | Base | Nova | Resilience4j |
|---|---:|---:|---:|
| `BulkheadBenchmark`, espera permitida | 18.9 / 385.1 | **18.6 / 209.7** | 20.1 / 475.4 |
| `BulkheadBenchmark`, sem espera (código sem mudança) | 20.5 / 263.9 | 20.5 / 257.4 | 20.0 / 430.4 |
| `AsyncBenchmark` bulkhead (sem espera) | 28.4 / 321.2 | 28.5 / 329.2 | 40.9 / 590.3 |
| `QuarkusBenchmark` bulkhead (sem espera) | 20.4 / 275.0 | 20.4 / 278.7 | 20.3 / 467.3 |

- **Com quatro threads o bulkhead com espera é 1.8× mais rápido que antes**, e 2.3× mais rápido que o `SemaphoreBulkhead` do Resilience4j, com o qual empatava (os dois usavam os atômicos com full fence do AQS sobre um estado compartilhado).
- **O bulkhead com espera agora é mais rápido que o sem espera** (18.6 contra 20.5 ns, 210 contra 257 ns), embora os dois ocupem um slot da mesma forma. A única diferença que resta é como o devolvem: um `getAndAdd` volatile com espera, um `getAndAddRelease` sem.
- Os bytes não mudam (0 B síncrono). Os bulkheads sem espera não mudam, dentro do erro.

## Slots devolvidos com uma soma volatile (2026-11-01, AWS Graviton3) :id=release-counters

O bulkhead sem espera e o adaptive limit devolviam um slot com `getAndAddRelease`, o atômico mais fraco que é correto ali; agora usam um `getAndAdd` volatile (`95aa67a`). O atômico mais forte mediu mais rápido. Isso saiu do [bulkhead com espera](#bulkhead-semaphore), cuja liberação é uma soma volatile e que tinha ficado mais rápido que o bulkhead sem espera. Por que a variante release é mais lenta no Graviton3 não foi verificado. Duas execuções (`infra/bench.sh`, `c7g.xlarge`, 6 forks): as linhas assíncronas, `Mono`, `Flux` e de frameworks contra `a0139b2` em 8 máquinas on-demand; as linhas síncronas, de adaptive limit e de `Pipeline` de uma execução cuja base era `410a649`, cujo código difere de `a0139b2` só nos atalhos `Flux` e assíncronos, que essas linhas não usam. O benchmark de calibração é este bulkhead, então agora mede 17.3 ns em vez de 20.5. ns/op 1 / 4 threads:

| Benchmark | Base | Nova | Resilience4j |
|---|---:|---:|---:|
| `BulkheadBenchmark`, sem espera | 20.5 / 259.0 | **17.3 / 195.5** | 20.4 / 347.6–450.3 |
| `QuarkusBenchmark` bulkhead | 20.4 / 264.1 | **17.2 / 198.6** | 20.3 / 381.4–467.5 |
| `SpringBenchmark` bulkhead | 20.4 / 278.8 | **17.2 / 197.4** | 1696.8 / 3934.1 |
| `MicronautBenchmark` bulkhead | 20.8 / 276.5 | **17.3 / 264.3** | 496.2 / 2760.2 |
| `FluxBenchmark` bulkhead | 195.2 / 737.4 | **191.3 / 537.5** | 257.2 / 729.3 |
| `ReactorBenchmark` bulkhead | 126.3 / 518.2 | **122.8 / 464.3** | 157.2 / 686.0 |
| `AsyncBenchmark` bulkhead | 28.4 / 346.0 | **25.4** / 359.5 | 40.9 / 510.5 |
| `AsyncBenchmark` pilha | 91.8 / 410.6 | **87.8 / 343.5** | 990.9 / 1766.5 |
| `AdaptiveLimitBenchmark` síncrono | 86.1 / 345.3 | **83.2 / 289.8** | 118.0 / 1068.6 (Netflix) |
| `AdaptiveLimitBenchmark` assíncrono | 94.7 / 441.9 | **92.2 / 369.5** | — |
| `PipelineBenchmark` quatro políticas, gerado | 62.2 / 339.3 | **59.7 / 268.1** | 259.7 / 1253.0 |

- **Os dois últimos empates agora são vitórias**: o bulkhead com um slot livre numa thread, chamado diretamente (17.3 contra 20.4 ns) e no Quarkus (17.2 contra 20.3 ns), que [a comparação completa](#frameworks) contava como empates no piso do hardware. O piso não era do hardware.
- **O bulkhead `Flux` com quatro threads**, à frente só dentro do erro depois da [mudança no Flux](#flux-ended), agora é 1.4× mais rápido (537.5 contra 729.3 ns).
- Linhas dentro do erro: o bulkhead assíncrono com quatro threads (+4%, ± 57 ns), e com quatro threads as linhas de `Pipeline` que têm um bulkhead (+6 a +8%, ± 12 a ± 38 ns, na execução com a outra base). As próprias linhas do Resilience4j se moveram até 30% com quatro threads entre as duas revisões, com o mesmo código.

## Circuit breaker com taxas de falha realistas (2026-11-02, AWS Graviton3) :id=failure-rates

Os números de destaque do breaker são os dois extremos: toda chamada tem sucesso (1.9 ns com quatro threads) e uma chamada em cada dez falha (274 ns). A maioria dos serviços saudáveis fica no meio, então `CircuitBreakerFailureRateBenchmark` faz falhar uma chamada em cada 1 000 (0.1 %) ou uma em cada 100 (1 %), com a configuração dos benchmarks `*Mixed` (uma janela por contagem de 100 chamadas, julgada após 100, abrindo com 50 %, a mesma falha pré-alocada e sem stack trace nas duas bibliotecas). As linhas de sucesso e de uma em dez abaixo vêm da mesma execução (`CircuitBreakerBenchmark`). Código depois da 0.13.0 (`1ea4fc6`), executado como as duas metades de cada máquina (8 máquinas, `-f 6`; as metades concordam dentro do erro, então a tabela dá a sua média), nanossegundos por chamada:

| Falhas | Threads | resilience4cata | Resilience4j `SYNCHRONIZED` | Resilience4j `LOCK_FREE` |
|---|---:|---:|---:|---:|
| nenhuma | 1 | **1.9** | 89.9 | 134.6 |
| nenhuma | 4 | **1.9** | 807.8 | 1708.3 |
| 0.1 % | 1 | **4.6** | 103.6 | 146.3 |
| 0.1 % | 4 | **11.2** | 1024.8 | 1596.8 |
| 1 % | 1 | **16.2** | 104.6 | 147.4 |
| 1 % | 4 | **65.4** | 1005.5 | 1592.0 |
| 10 % | 1 | **17.2** | 98.3 | 136.3 |
| 10 % | 4 | **278.0** | 939.6 | 1615.2 |

O resilience4cata aloca 0 B em todas as linhas; o Resilience4j de 0 a 26 B (`SYNCHRONIZED`) e de 208 a 816 B (`LOCK_FREE`).

- **O custo acompanha a frequência com que a janela contém uma falha** (inferido do código, não perfilado). Com uma falha em cada 1 000 chamadas, a janela de 100 a contém durante um décimo das chamadas; os outros nove décimos são quietos, uma leitura de campo cada. Com uma em cada 100, a janela sempre contém uma falha, então toda chamada avança o cursor compartilhado da janela; a maioria das chamadas então encontra o seu slot já contendo um sucesso e não escreve mais nada. Com uma em dez, as falhas mudam de slot em quase toda volta, então mais chamadas reescrevem um slot e os contadores compartilhados.
- **A diferença para o Resilience4j com quatro threads**: 91× com 0.1 %, 15× com 1 %, 3.4× com 10 %. O custo do Resilience4j quase não se move com a taxa de falhas: ele registra toda chamada de qualquer forma.
- A única linha com dispersão: 1 % com quatro threads, 63.1 e 67.6 ns nas duas metades (forks de 57 a 74 ns), que é o cursor compartilhado sob contenção.

## Correções da revisão pré-1.0, e um timeout por nova virtual thread (2026-11-02, AWS Graviton3) :id=review-fixes

O [plano para a 1.0](plan-1.0.md) (fase 3) corrigiu quatro condições de corrida encontradas na revisão: um slot de bulkhead deixado livre enquanto um chamador assíncrono esperava, uma janela do adaptive limit lida como 1 ns depois de uma stripe voltar atrás, o rate limiter calculando a sua espera com uma leitura de relógio feita antes de estacionar, e um timeout síncrono limpando uma interrupção que o chamador já tinha. Base `9184cc5` contra `a307d4b`, 10 máquinas, `-f 6`, nanossegundos por chamada para uma e quatro threads:

| Benchmark | Base | Nova |
|---|---:|---:|
| `RateLimiterBenchmark`, permissões livres | 41.7 / 187.0 | 41.7 / **183.6** |
| `RateLimiterBenchmark`, esgotado | 34.6 / 34.8 | 34.6 / 34.8 |
| `BulkheadBenchmark`, modo com espera, livre | 18.7 / 204.8 | 18.6 / 209.9 |
| `BulkheadBenchmark`, livre | 17.3 / 205.9 | 17.3 / 208.1 |
| `AdaptiveLimitBenchmark` | 83.6 / 306.7 | 83.1 / 290.9 |
| `TimeoutBenchmark` | 23.8 / 23.6 | 23.8 / 23.5 |

- **O rate limiter agora lê o relógio de novo depois de perder uma corrida**, então um chamador sob contenção já não espera a partir de um tempo desatualizado. A leitura extra está só no caminho que já estacionou: com quatro threads, 183.6 contra 187.0 ns (forks 182.9–184.0 contra 185.5–188.8).
- As outras correções estão em caminhos frios (uma espera síncrona que desiste, uma janela que termina, um timeout que dispara). As linhas de bulkhead com quatro threads se moveram +1 % e +3 %, o tamanho do ruído na mesma execução: o próprio bulkhead do Resilience4j, sem mudança, se moveu +3 % e +9 %.

**Um timeout síncrono com uma nova virtual thread por chamada** (`VirtualThreadTimeoutBenchmark`, novo): cada chamada inicia uma virtual thread, faz a chamada com timeout nela e faz join, então toda chamada é a primeira da sua thread e registra a thread no watchdog (um objeto `ThreadScopes` e uma inserção na fila compartilhada do watchdog; uma thread morta é esquecida no próximo tick).

| Threads | Thread, chamada simples | Thread, chamada com `@Timeout` | Diferença |
|---:|---:|---:|---:|
| 1 | 9291 ns, 487 B | 9254 ns, 760 B | nenhuma mensurável, +273 B |
| 4 | 9274 ns, 443 B | 10069 ns, 711 B | +795 ns (+8.6 %), +268 B |

Iniciar uma virtual thread domina. Com quatro threads iniciando ao mesmo tempo, o timeout acrescenta cerca de 0.8 µs a cada uma, muito provavelmente a fila compartilhada pela qual passam os registros (inferido, não perfilado). Numa thread que faz várias chamadas com timeout só a primeira paga.

## As linhas da página inicial no release candidate (2026-11-02, AWS Graviton3) :id=release-candidate

As quatro comparações da página inicial, medidas de novo sobre o código da 1.0.0-RC1 (`439ba5c`; o commit de release só muda a versão), para que todas venham de uma mesma execução. Cada benchmark rodou como as duas metades de cada máquina (8 máquinas, `-f 6`); a tabela dá a média das metades, nanossegundos e bytes por chamada:

| Cenário | Threads | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|---:|
| Retry, a primeira tentativa tem sucesso | 1 | **1.4 ns**, 0 B | 22.9 ns, 0 B |
| Retry, a primeira tentativa tem sucesso | 4 | **1.3 ns**, 0 B | 23.0 ns, 0 B |
| Circuit breaker, toda chamada tem sucesso (`SYNCHRONIZED` para o Resilience4j) | 1 | **1.9 ns**, 0 B | 89.9 ns, 0 B |
| Circuit breaker, toda chamada tem sucesso | 4 | **1.9 ns**, 0 B | 849.5 ns, 2 B |
| Timeout síncrono (Resilience4j: `TimeLimiter` + executor) | 1 | **23.6 ns**, 0 B | 8907.6 ns, 183 B |
| Timeout síncrono | 4 | **23.6 ns**, 0 B | 9976.8 ns, 151 B |
| Bulkhead, slots livres | 1 | **17.3 ns**, 0 B | 20.1 ns, 0 B |
| Bulkhead, slots livres | 4 | **192.3 ns**, 0 B | 410.8 ns, 0 B |

- O retry numa thread diferiu entre as duas metades, com o mesmo código: 1.42 a 1.54 ns numa, 1.25 a 1.28 ns na outra, cada uma estável nos seus seis forks. Um efeito de layout entre execuções da JVM; a tabela dá a média, 1.4 ns. O destaque da 0.1.0 mediu 1.2 ns.
- O timeout síncrono mede 23.6 ns, como na [execução depois do MVP](#annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3) (23.7 ns); os 22.2 ns do destaque da 0.1.0 não foram vistos desde então.
- Com quatro threads, o bulkhead (192.3 ns) e o do Resilience4j (forks de 361 a 528 ns) têm a dispersão habitual da contenção.

## Percentis sob contenção (2026-11-12, AWS Graviton3) :id=percentiles

As outras seções dão o tempo médio por chamada, o que esconde as caudas. Aqui os mesmos `RateLimiterBenchmark` e `BulkheadBenchmark` rodaram no modo `SampleTime` do JMH, que cronometra chamadas individuais: `MACHINES=6 JMH_OPTIONS="-bm sample" infra/bench.sh HEAD HEAD '^(RateLimiterBenchmark|BulkheadBenchmark)[.]'` sobre `580a090`, 6 `c7g.xlarge`, 3 forks por benchmark e número de threads, cada execução como as duas metades da sua máquina (a tabela dá a média das metades; de 1.2 a 2.2 milhões de chamadas cronometradas por linha com quatro threads).

Como ler:

- **Todo valor inclui cerca de 34 ns de medição.** Cada chamada amostrada é cronometrada com duas leituras de relógio: a linha `baseline` (o serviço chamado diretamente, sem política) tem uma mediana de 34 ns, onde o modo de tempo médio mede cerca de 1 ns. Compare as linhas entre si, não com as médias das outras seções.
- **Um p99.99 de 2 a 6 µs é a máquina**, não a biblioteca: o baseline também o tem (uma interrupção, um safepoint do JIT ou do GC). As maiores amostras, cerca de 10 ms com quatro threads, também estão no baseline, e são deixadas de fora.
- **Loop fechado**, como em todas as outras seções: cada thread chama de novo assim que a sua chamada retorna, então uma chamada travada atrasa as próximas chamadas dessa thread em vez de acumular mais chamadores. Uma execução em loop aberto (chamadas iniciadas a uma taxa fixa, latência contada desde o início planejado) ainda não foi feita.

Quatro threads, um limiter ou bulkhead compartilhado:

| Cenário | Biblioteca | p50 | p90 | p99 | p99.9 | p99.99 | Média |
|---|---|---:|---:|---:|---:|---:|---:|
| Sem política (`baseline`) | — | 34 ns | 35 ns | 46 ns | 52 ns | 3.5 µs | 54 ns |
| Rate limiter, permissões livres | resilience4cata | 78 ns | 81 ns | 160 ns | **110 µs** | 320 µs | 539 ns |
| | Resilience4j 2.4.0 | 100 ns | 102 ns | 167 ns | 910 µs | 8.4 ms | 4.4 µs |
| Rate limiter, esgotado (todas rejeitadas) | resilience4cata | 74 ns | 76 ns | 78 ns | 81 ns | 3.9 µs | 96 ns |
| | Resilience4j 2.4.0 | 431 ns | 488 ns | 56.7 µs | 224 µs | 387 µs | 2.1 µs |
| Bulkhead, slots livres | resilience4cata | 348 ns | 410 ns | 526 ns | 555 ns | 5.9 µs | 373 ns |
| | Resilience4j 2.4.0 | 540 ns | 784 ns | 1.1 µs | 1.8 µs | 6.2 µs | 605 ns |
| Bulkhead, espera permitida, slots livres | resilience4cata | 354 ns | 477 ns | 544 ns | 716 ns | 5.4 µs | 407 ns |
| | Resilience4j 2.4.0 | 576 ns | 850 ns | 1.5 µs | 3.1 µs | 7.6 µs | 643 ns |
| Bulkhead, cheio (todas rejeitadas) | resilience4cata | 36 ns | 38 ns | 47 ns | 54 ns | 2.8 µs | 48 ns |
| | Resilience4j 2.4.0 | 297 ns | 316 ns | 348 ns | 1.9 µs | 15.3 µs | 347 ns |

- **O rate limiter sob contenção tem a cauda que as médias escondiam.** Entre 0.1 % e 1 % das chamadas perdem a corrida pelo `tat` compartilhado, estacionam por 1 ns e esperam 110 µs ou mais (p99.9), até 320 µs (p99.99); a média de 539 ns são sobretudo essas chamadas. Isso é mais longo que os 50 µs de timer slack que [a seção do rate limiter](#rate-limiter-1-and-4-threads-2026-10-24) esperava. O `AtomicRateLimiter` do Resilience4j também estaciona os seus perdedores, e a sua cauda é 8× mais longa no p99.9 (910 µs) e 26× no p99.99 (8.4 ms). Isso só acontece quando as chamadas admitidas chegam mais rápido do que a linha de cache compartilhada se move entre os núcleos, aqui quatro threads que não fazem mais nada.
- **Um limiter esgotado não tem cauda**: uma rejeição só lê, então todo percentil até o p99.9 fica a menos de 30 ns da sua mediana. As rejeições do Resilience4j escrevem o seu estado compartilhado e estacionam ao perder uma corrida: 56.7 µs no p99.
- **Os bulkheads não têm cauda de park**: um slot é ocupado com um compare-and-set e devolvido com uma soma, e ninguém estaciona enquanto há slots livres. O p99.9 do resilience4cata é 555 ns (716 ns com espera permitida), o do Resilience4j 1.8 µs (3.1 µs).
- **As rejeições de um bulkhead cheio ficam no baseline** no resilience4cata (mediana de 36 ns, uma leitura e uma exceção pré-alocada). As do Resilience4j chegam a 1.9 µs no p99.9 (não investigado).

Com uma thread nada estaciona. As linhas do resilience4cata ficam a menos de 10 ns da sua mediana até o p99.9 (rate limiter 80 → 88 ns, bulkhead 54 → 62 ns). As do Resilience4j fazem o mesmo enquanto as chamadas passam, e chegam a 1.5 a 1.9 µs no p99.9 nas suas rejeições (o seu rate limiter aloca cerca de 550 B por rejeição, [acima](#rate-limiter-1-and-4-threads-2026-10-24)).

## Serviços sob um limite de CPU (2026-11-15, AWS Graviton3) :id=cpu-limit

O piloto (`pilot/README.md`: serviços Spring Boot, Micronaut e Quarkus sobre a 1.0.0-RC4 do Maven Central, 10 minutos de carga k6 a 1500 pedidos por segundo com o backend fora do ar por um minuto e lento por outro, uma `c7g.4xlarge`, JDK 25) rodou três vezes: sem limite, e com cada serviço limitado a 1 e a 0.5 núcleo (`CPU=<cores> pilot/run.sh aws`). O limite é uma cota de cgroup v2 com um período de 100 ms, que é o que um `limits.cpu` do Kubernetes define; as threads do serviço rodam em 4 núcleos, então ele pode gastar a cota de um período cedo e então fica congelado até o próximo. Com o limite a JVM vê 1 processador. Os serviços usam 0.34–0.42 núcleo em média fora do primeiro minuto, quando o JIT compila.

| | Sem limite | 1 núcleo | 0.5 núcleo |
|---|---|---|---|
| Períodos estrangulados, primeiro minuto | – | 16–22 % | 67–99 % (threads esperando 1.6–2.2 s por segundo) |
| Períodos estrangulados, regime estável | – | 0 % | Spring 8 %, Micronaut 0 %, Quarkus 2 % |
| Interrupção mais tardia de um `@Timeout` de 200 ms, tempo no método | 221.6–225.1 ms | até 1629 ms | até 4802 ms, todas no primeiro minuto |
| Interrupção mais cedo | 188 ms | 188 ms | 188 ms |
| Pedidos do primeiro minuto respondidos por um breaker aberto | 0 % | Spring 2.5 % | 2.9–35.9 % |

- **Um timeout só se atrasa enquanto o processo está congelado.** Sem limite, a interrupção mais tardia veio dentro dos dois ticks do watchdog (25 ms para um timeout de 200 ms). Com 0.5 núcleo, toda interrupção depois de 250 ms veio no primeiro minuto, enquanto o serviço estava estrangulado a maior parte do tempo: a thread do watchdog e a interrompida ficavam congeladas juntas. Nenhuma veio depois de 250 ms na fase estável. Nenhum timeout disparou antes da hora em nenhuma execução.
- **Uma inicialização a frio sem CPU abre o breaker**: as chamadas realmente levaram mais de 200 ms, e um timeout conta como falha da dependência. O breaker voltou a fechar depois de `waitInOpen` (5 s).
- **As políticas mantiveram os seus limites em todas as execuções**: no máximo 40 chamadas no bulkhead, as permissões do rate limiter, o breaker fechado no final, nada deixado dentro, uma thread da biblioteca, o heap estável.
- **O Spring precisa de mais de meio núcleo com esta carga**: com 0.5 núcleo as suas chamadas ficaram lentas o bastante para encher o bulkhead na fase estável (1.4 % dos pedidos respondidos pelo fallback do bulkhead, p99 318 ms). Micronaut e Quarkus passaram em todas as verificações.

## Footprint em native image (2026-11-17, runner do GitHub) :id=native-footprint

O que o resilience4cata acrescenta a uma native image: `./gradlew :native:footprint:footprint` (job `native (footprint)` do workflow `native`) constrói as aplicações Spring Boot e Micronaut dos testes nativos (`native/spring`, `native/micronaut`: uma classe anotada, uma interface envolvida com um fallback, métodos `Mono` e `Flux` dos dois estilos, configuração YAML, health, métricas Micrometer, o endpoint) e as mesmas fontes sem a biblioteca (`native/spring-plain`, `native/micronaut-plain`: compiladas sem o processador, sem nenhum módulo do resilience4cata no class path, ou seja, os mesmos beans sem proteção). As quatro imagens iniciam uma de cada vez, 10 vezes cada. A inicialização é o tempo desde o início do processo até o primeiro `GET /stock/abc` respondido, uma chamada através de um retry e de um circuit breaker nas aplicações com a biblioteca. O RSS é medido com `ps` logo depois dessa chamada, e de novo depois de 2000 requisições de 8 threads sobre cinco endpoints. GraalVM CE para JDK 25.0.2, runner `ubuntu-24.04` (x86, 4 CPUs), Spring Boot 4.1.2, plataforma Micronaut 5.2.4. Medianas, com a faixa das 10 execuções:

| Imagem | Tamanho | Inicialização | RSS após iniciar | RSS após a carga |
|---|---:|---:|---:|---:|
| Spring Boot | 91.6 MB | 78 ms (73–129) | 122.5 MB | 149.8 MB (145.2–149.9) |
| Spring Boot sem resilience4cata | 89.7 MB | 67 ms (64–81) | 113.4 MB | 139.2 MB (139.1–139.3) |
| Micronaut | 79.3 MB | 32 ms (28–37) | 86.9 MB | 134.2 MB (133.6–134.7) |
| Micronaut sem resilience4cata | 77.3 MB | 27 ms (25–34) | 82.5 MB | 131.1 MB (130.9–131.6) |

A diferença, com menos sem: **+1.9 MB de imagem** nos dois; **+11 ms (Spring Boot) e +5 ms (Micronaut) de inicialização**; **+9.1 e +4.4 MB de RSS após iniciar, +10.6 e +3.1 MB após a carga**. A diferença cobre tudo o que as aplicações com a biblioteca têm: core, config, a integração com o framework, o binding do Micrometer, o módulo reactor, as classes geradas e o estado das políticas, e o trabalho da primeira chamada (as instâncias do registry, a configuração lida do YAML). Para onde vai o RSS extra não foi analisado. Os números são destas aplicações pequenas; uma aplicação maior acrescenta as suas próprias classes aos dois lados, então a parcela da biblioteca nela é menor. O RSS quase não varia entre execuções, a inicialização uns poucos milissegundos.

## Gate de alocação :id=allocation-gate

O tempo em runners de CI compartilhados é ruído, mas os bytes alocados por operação não são, então o CI usa um gate sobre eles. `./gradlew :benchmark:allocationCheck` executa todo benchmark listado em `benchmark/allocation-budgets.properties` com ajustes curtos em três forks (`-f 3 -wi 3 -w 200ms -i 3 -r 200ms -t 1 -prof gc`) e falha se o fork que menos aloca alcança o seu orçamento + 1 B/op, ou se falta um benchmark nos resultados. Três forks porque o C2 decide uma vez por JVM se faz scalar replacement de um objeto: um fork mantém os mesmos bytes em toda iteração, e o seguinte pode alocar um ou dois objetos a mais. No runner de CI (x86), 21 execuções do gate de um fork mediram `FluxBenchmark.resilience4cataPipelineStack` em 696, 728, 752 e 776 B e `resilience4cataTimeout` de 384 a 440 B, então o gate falhava de vez em quando em mudanças que não alocam nada; um aquecimento mais longo, em vez disso, colocou todo fork no valor mais alto (Apple M1). Uma alocação que o código passa a fazer aparece em todo fork, então o fork mais baixo ainda a pega. A tabela mostra os bytes de todo fork. Ele imprime uma tabela e escreve `benchmark/build/jmh/allocation-report.md`; o workflow `alloc-gate` o executa em todo PR. Acrescentar um benchmark é uma linha: `<BenchmarkClass>.<method> = <bytes per op>`.

Orçamentos: 0 B/op para todo caminho síncrono de sucesso e de rejeição (retry, circuit breaker por contagem/tempo/aberto, rate limiter livre/esgotado, bulkhead livre/cheio/com espera, timeout, classifiers, código gerado e `Pipeline` incluindo `run` e uma lambda que captura, subclasse e wrapper). Os caminhos assíncronos alocam os seus futures: 48 B para o retry; desde a 0.11.0, 24 B (uma cópia completa) para o circuit breaker, bulkhead, rate limiter, adaptive limit e timeout sobre um stage completo ([acima](#flux-and-done-stages)), e 152 B para a pilha de quatro políticas (200 B antes); e 200 B para um timeout sobre um stage pendente (medido 191–192 B; o trabalho compartilhado da thread do timer varia uns poucos bytes, e 8 B de folga ainda é menos que um objeto por chamada). Medido localmente na JDK 21 (Apple M1), 2026-10-24. `CircuitBreakerBenchmark.*Open` (breaker forçado a abrir, toda chamada rejeitada) foi acrescentado para o gate; o Resilience4j recebe `writableStackTraceEnabled(false)` ali, por justiça.

## Teste de soak :id=soak-test

Um vazamento de uns poucos bytes por chamada, ou de um objeto por thread que alguma vez fez uma chamada, não aparece num teste que dura segundos. `./gradlew :examples:soakTest -Psoak.duration=2h` (padrão 2 minutos) executa todas as políticas, síncronas, assíncronas, `Mono` e `Flux`, mais um `Pipeline`, sobre relógios reais. A carga vem de threads de plataforma e de novas virtual threads. Os backends respondem, falham, respondem tarde demais ou nunca respondem, e um segundo em cada quatro é uma tempestade de falhas, então os breakers abrem e fecham o tempo todo. Alguns chamadores cancelam. O heap é fixo em 512 MB, e cerca de 60 vezes durante a execução o teste amostra:

- o heap depois de um GC completo;
- as threads da biblioteca;
- as threads para as quais o watchdog do timeout guarda scopes;
- as tarefas do timer;
- os meters do Micrometer, as séries do OpenTelemetry (os dois módulos escutam o registry; o OpenTelemetry exporta a cada segundo através de um reader periódico) e as instâncias do registry.

Ele falha se:

- o menor heap depois do GC no último quarto da execução está mais de 16 MB (`-Psoak.heapBudgetMb`) acima do menor entre 20% e 45% dela;
- uma thread da biblioteca ou uma instância do registry aparece depois do aquecimento, ou um meter ou uma série do OpenTelemetry não pertence a nenhuma instância;
- quando a carga para, OpenTelemetry e Micrometer não contaram os mesmos eventos;
- quando a carga para, sobra uma chamada, uma tarefa do timer, um slot de bulkhead ou de adaptive limit, ou o scope do watchdog de uma thread morta;
- uma falha é inesperada, ou o Reactor descarta um erro. Um caso é contado mas permitido: o próprio operador `timeout` do chamador descartando um erro que disputou com o seu cancelamento, o que as regras de Reactive Streams permitem.

O relatório vai para `examples/build/soak/report.md`, com um heap dump quando o heap cresceu. O workflow `soak` executa o teste semanalmente por 2 horas na JDK 21 e 25, ou à mão com uma duração escolhida, e copia o relatório para o resumo do job.

O teste foi verificado por mutação, localmente na JDK 21 (Apple M1, 8 CPUs), 2026-10-31:

- Com um watchdog que nunca esquece threads mortas, o teste falhou depois de um minuto: a verificação do watchdog falhou (89,576 threads registradas), e a do heap também (22.7 → 46.5 MB).
- Quando toda tarefa assíncrona atrasada era guardada, a verificação do heap falhou depois de dois minutos (50.8 → 158.0 MB).
