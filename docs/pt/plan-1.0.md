# Plano para a 1.0

O que falta antes de lançar a 1.0.0, a partir de uma revisão da 0.13.0 feita por quatro revisores independentes, e o que fica para depois dela.

Esta página é o plano de trabalho: cada tarefa diz o que mudar, onde e como verificar, para que o trabalho possa ser retomado daqui sem nenhum outro contexto. Marque uma tarefa como concluída (`[x]`) no mesmo pull request que a realiza e acrescente o número desse pull request.

## Status

- Versões no Maven Central: 0.13.0 e os release candidates 1.0.0-RC1, 1.0.0-RC2, 1.0.0-RC3 e 1.0.0-RC4. `main` está em 1.0.0-SNAPSHOT.
- O plano de desempenho das execuções na AWS está concluído (pull requests #53–#59, veja [benchmarks](benchmarks.md)).
- Os títulos do Javadoc levam o nome do artefato (#60); o javadoc.io foi sincronizado para os 11 módulos.
- As fases 1 a 3 estão concluídas (#62 a #65).
- Uma segunda revisão pelos mesmos quatro revisores (2026-11-02, depois do #65): o designer de API, o revisor de desempenho e o iniciante dizem que está pronto para a 1.0; o adotante pede primeiro uma 1.0.0-RC1, e três coisas antes da 1.0 (uma falha explícita quando o processador estiver ausente, uma execução do jcstress em ARM, uma política de segurança). As pequenas correções de documentação deles estão na fase 4.
- A 1.0.0-RC1 está no Maven Central (2026-11-02). `main` está em 1.0.0-SNAPSHOT, com a baseline da API em 1.0.0-RC1, então a API está congelada: uma mudança incompatível em relação ao release candidate faz o build falhar.
- O branch principal do SonarCloud ainda se chamava `master`, então as análises de `main` nunca chegavam à visão geral do projeto. Agora é `main`; seus 21 problemas de confiabilidade foram revisados (#68), e só a biblioteca publicada é analisada (#69).
- A 1.0.0-RC2 (2026-11-04) faz a inicialização ou o build falhar quando o processador não rodou.
- Uma [terceira revisão](#third-review) da RC2 (2026-11-05): três revisores dizem para lançar a 1.0.0; suas correções mudam dois comportamentos, então uma 1.0.0-RC3 vem antes.
- A 1.0.0-RC3 (2026-11-05) tem as correções da terceira revisão. Os trechos de instalação agora a mostram (decisão do mantenedor).
- `main` só aceita mudanças por pull requests com verificações aprovadas (administradores contornam isso nos commits de release), e cada release candidate tem um GitHub release; a 1.0.0-RC3 é a *Latest*, como mostram os trechos de instalação (2026-11-06).
- 1.0.0-RC4 (2026-11-14): o piloto encontrou o circuit breaker abrindo por causa do bulkhead cheio do próprio método em uma inicialização a frio; o breaker agora nunca registra a rejeição de um limite local (#100). Mesma API da RC3. Os trechos de instalação a mostram, e ela é a *Latest*.
- Próximo: 1.0.0.

## A revisão :id=the-review

Quatro revisores leram o repositório em 2026-11-02, cada um de um ponto de vista. Nenhum rodou o build. Seus achados são afirmações a verificar, não fatos: cada tarefa abaixo começa confirmando o achado.

| Revisor | Veredito |
|---|---|
| Um usuário do Resilience4j decidindo se adota | Ainda não para produção em geral; sim para um piloto em um serviço. Engenharia sólida; o risco é a idade do projeto e um único mantenedor. |
| Um designer de API | Perto de pronto para o congelamento; cinco correções baratas agora que seriam permanentes depois. |
| Um cético de desempenho e concorrência | Engenharia cuidadosa, nenhum bug confirmado; os destaques são melhores casos; x86 nunca medido; quatro lacunas suspeitas. |
| Um iniciante em bibliotecas de resiliência | Uma chamada protegida em cerca de 15 minutos; algumas afirmações desatualizadas ou contraditórias; algumas páginas internas demais. |

O que eles valorizaram, para manter enquanto o resto muda:

- Erros em tempo de compilação com códigos `R4C-…` estáveis, cada um documentado com um exemplo errado e sua correção, onde o Resilience4j resolve `fallbackMethod` por reflexão em tempo de execução.
- Um custo por chamada quase nulo, medido abertamente: empates e regressões são mostrados, os ajustes de equidade estão escritos no Javadoc de cada benchmark, o Resilience4j recebe exceções sem stack trace e nenhum código morto, e o portão de alocação roda no CI.
- Código gerado que é Java simples: os stack traces passam por `X$Resilient`, não por cadeias de proxies, e a autoinvocação é interceptada no estilo de subclasse.
- Um guia de migração sério (tabelas de configuração, padrões que diferem, quando ficar no Resilience4j) e uma promessa de compatibilidade explícita, apoiada pelo japicmp e pela regra do código gerado.
- Uma API que pode crescer: as configs são classes finais com builders, `ResilienceSnapshot` é uma classe, handles e `FallbackContext` são implementados pela biblioteca, as rejeições compartilham uma base selada sem stack trace, os enums documentam que crescem, `@Internal`/`@Experimental` são aplicados, `PipelineCall` mantém uma exceção checada precisa.
- Código lock-free correto, até onde o revisor leu: o handshake `quiet` do breaker, o CAS acquire `RUNNING → IDLE` do timeout, a liberação e a nova verificação do `BulkheadSemaphore`, a ordem `count++ → offer → drain` dos que esperam, a marca de cancelamento do `LinkedFuture`, e o retry budget errando para o lado de menos retries.
- O quickstart (um programa testado, sua saída, "O que acabou de acontecer") e a página de instalação (BOM em `annotationProcessor`, Maven antigo, descoberta no JDK 23, IDEs, ordem com o Lombok). As árvores en/es coincidem e o espanhol soa natural.

## Fase 1: API, antes do congelamento :id=phase-1-api-before-the-freeze

Um pull request. Tudo aqui muda a API pública, o que é livre antes da 1.0 e impossível depois. Cada mudança ganha uma linha no changelog (en/es) e, onde toca o código gerado, uma verificação de que `examples` continua passando.

- [x] **1.1 Parâmetros de tipo anuláveis.** Sob `@NullMarked`, `Pipeline<T>`, `PipelineCall<T, X>`, `FallbackHandler<T>` e `Classifier<T>` limitam `T` a não nulo, embora `PipelineRunnable extends PipelineCall<@Nullable Void, X>` já quebre esse limite e os fallbacks de métodos `void` retornem null. Declare `<T extends @Nullable Object>` nos quatro, e em tudo o mais que receba o tipo de resultado de um usuário (`AsyncCall`, `Recovery`, `PipelineRunnable`: verificar). Verificação: `./gradlew build`; os testes do processador compilam o código gerado com `-Werror`.
  - **Resultado:** (#62) Feito: os quatro tipos, mais `AsyncCall`, `Recovery` e o `executeAsync` das políticas; os tipos `Mono`/`Flux` do reactor continuam não nulos (o Reactor proíbe elementos null).
- [x] **1.2 Records que crescem.** `reference/compatibility.md` diz que records de eventos e snapshots podem ganhar componentes, mas adicionar um remove o construtor canônico (incompatível em binário, o japicmp falha). Decidir: ou construir eventos e snapshots não é suportado (dizer isso em `compatibility.md` e no Javadoc dos records), ou cada novo componente mantém o construtor antigo (dizer isso e acrescentar a regra ao CLAUDE.md). A segunda é mais amigável aos testes de listeners dos usuários; preferi-la.
  - **Resultado:** (#62) Feito: o construtor antigo é mantido (`compatibility.md`, `ResilienceEvent`, CLAUDE.md).
- [x] **1.3 Tipos selados que crescem.** Adicionar `PolicyConfig` e `CallRejectedException` (seus `permits` crescem a cada nova política) à lista do que "pode crescer" em `compatibility.md` e ao Javadoc deles, como para os eventos: um `switch` exaustivo sobre eles precisa de um ramo default.
  - **Resultado:** (#62) Feito (`compatibility.md`, Javadoc das duas classes).
- [x] **1.4 Um nome para o evento de rejeição.** `CircuitBreakerEvent.CallNotPermitted` → `Rejected`, como os eventos do bulkhead, do rate limiter e do adaptive limit. A exceção mantém seu nome. Alinhar também o enum de anotação `LimitAlgorithm` com `AdaptiveLimitConfig.Algorithm` (os outros enums, `Backoff`, `Jitter`, `WindowType`, coincidem com o enum da sua config). Atualizar os módulos de métricas, a tabela de eventos em `guides/observability.md` (en/es) e os trechos em `examples/src/test/.../docs/`.
  - **Resultado:** (#62) Feito para o evento. `LimitAlgorithm` mantido: veja [design](design.md#api-decisions).
- [x] **1.5 Constantes que são embutidas.** `HedgeConfig.MIN_ATTEMPTS`/`MAX_ATTEMPTS` são constantes de tempo de compilação: são copiadas nos class files de quem as chama, então aumentar o limite de 10 não alcançaria código compilado antes. Torná-las privadas (os limites estão documentados), ou não constantes.
  - **Resultado:** (#62) Feito: privadas.
- [x] **1.6 Pequenas renomeações.** `ResilienceRegistry.Builder.listen(String instance, …)` → `id`. `Resilient.create(type, registry, Object... args)`: os argumentos podem ser null, então `@Nullable Object...`. O formato do id derivado é `<interface>#…` em `ResilienceRegistry` e `ResilienceEvent`, mas `<type>#…` nos handles: usar `<type>` (classes anotadas).
  - **Resultado:** (#62) Feito.
- [x] **1.7 Decidir, não necessariamente mudar.** Escrever cada decisão em `design.md`:
  - `@Timeout` não tem `name` (as outras seis têm). Adicioná-lo depois é aditivo.
  - Os parâmetros de lista dos builders (`retryOn(List<Class<? extends Throwable>>)`) rejeitam `List<Class<? extends Exception>>`; `Collection<? extends Class<? extends Throwable>>` aceitaria, mas só antes da 1.0.
  - Ordem dos argumentos: `Resilient.of(type, delegate, registry)`, `create(type, registry, args…)`, `Pipeline.builder(registry, id)`.
  - `ResilienceHealthIndicator` e `ResilienceEndpoint` do Micronaut têm construtores públicos: marcá-los com `@Internal`.
  - Os setters de ajuste do `AdaptiveLimitConfig.Builder` têm Javadoc de uma linha sem descrição principal: escrevê-la.
  - Os formatos dos snapshots diferem: os snapshots de retry, timeout e hedge incluem `config`; bulkhead, rate limiter e adaptive limit copiam alguns campos (`maxConcurrent`, `permits`, `minLimit`/`maxLimit`); o do circuit breaker não tem nenhum dos dois. Escolher um formato, ou escrever por que diferem.
  - `CircuitBreakerSnapshot` usa `-1` para "ainda sem taxa": manter o sentinela (documentado) ou planejar um acessor `hasRates()`.
  - `Pipeline.Builder.retry(String)` ao lado de `retry(Consumer)` (e os mesmos pares para cada política): `retry(null)` é ambíguo. Aceitar ou renomear um do par.
  - `LockedOverride.policy` é uma `String` com um conjunto fechado de valores: um enum seria mais seguro; a string coincide com as chaves de configuração.
  - Dois enums chamados `ExhaustedResult` (`Pipeline.ExhaustedResult` e o da anotação, que também tem `INHERIT`): importar os dois é desajeitado. Aceitar ou renomear.
  - **Resultado:** (#62) Feito: cada ponto decidido em [design](design.md#api-decisions); os construtores do Micronaut são `@Internal` e todos os 51 setters de builders têm uma descrição principal.

Verificação da fase inteira: `./gradlew build :stress:compileJava`, `./gradlew :quarkus:it:check`, `./gradlew :core:apiCompatibility` (o relatório lista as mudanças pretendidas e nada mais).

## Fase 2: documentação :id=phase-2-documentation

Um pull request, en e es juntos. A maioria são números desatualizados ou frases que contradizem o código.

- [x] **2.1 Números de desempenho desatualizados.**
  - As páginas iniciais (`docs/{en,es}/README.md`) rotulam seu gráfico como "resilience4cata 0.1.0" e mostram o bulkhead em 20.5 ns; o `README.md` da raiz também. Atual: 17.3 ns (`benchmarks.md#release-counters`).
  - `performance.md`: a linha de destaque do bulkhead (20.5 / 264 ns, "empate") é anterior a 17.3 / 195.5 ns; "acquire/release … 23.2 → 9.3 ns" contradiz a liberação com `getAndAdd` volátil; a tabela principal foi medida com a 0.1.0.
  - `faq.md` "Quão rápido é?" coloca 1.9 ns (4 threads) ao lado dos 89.2 ns (1 thread) do Resilience4j. Usar uma só linha.
  - O `README.md` da raiz cita o breaker com quatro threads (832 ns para o Resilience4j) onde o FAQ usa uma thread (89 ns): o README, as páginas iniciais, o FAQ e `performance.md` devem citar as mesmas linhas.
  - Decidir se a tabela de destaques deve ser rodada de novo na AWS com o código da 1.0 (`infra/bench.sh`), para que cada número citado tenha uma única fonte.
  - **Resultado:** (#63) Feito: linhas do bulkhead de [release counters](benchmarks.md#release-counters) (17.3 / 195.5 ns) em `performance.md`, nas páginas iniciais (legenda e data também) e no README, que agora também cita o breaker com uma thread. A frase do FAQ estava certa: o breaker custa 1.9 ns com uma e com quatro threads. Rodar de novo a tabela de destaques com o código da 1.0 fica para a execução na AWS da 2.5.
- [x] **2.2 Destaques honestos.**
  - Os 1.9 ns do breaker com quatro threads só valem enquanto a janela não contém nenhuma falha (o caminho `quiet`). A partir de cerca de 1 % de falhas, cada chamada escreve o cursor compartilhado: "mixed", 274 vs 910 ns. Dizer isso ao lado do destaque.
  - Dizer que o Resilience4j atualiza suas métricas embutidas a cada chamada e o resilience4cata não mantém contadores por chamada, o que é parte da diferença.
  - A linha do timeout (22 vs 8842 ns) compara um watchdog com uma passagem para um executor: tirá-la do destaque em negrito ou qualificá-la.
  - "Um par de leituras de relógio por chamada, seja qual for o número de políticas" é falso para pilhas (rate limiter, adaptive limit, breaker com tempo, retry com `maxDuration`, cada um lê o relógio). Corrigir.
  - Os benchmarks medem tempo médio em laço fechado, o que esconde as caudas: dizer isso onde o rate limiter e o bulkhead sob contenção são citados (os perdedores estacionam), até existirem as execuções com percentis.
  - x86 nunca foi medido: dizer isso em `performance.md` ("x86-64 pendente" está lá; mantê-lo visível ao lado do destaque).
  - A janela por contagem do circuit breaker é aproximada sob contenção (uma leitura opaca desatualizada de `quiet` pode deixar de registrar um sucesso logo depois de uma falha; o salto de slot com o mesmo resultado pode reordenar escritas depois que a janela dá a volta). Escrever isso em `policies/circuit-breaker.md` e `architecture.md`.
  - **Resultado:** (#63) Feito: o caminho quiet do breaker, os contadores de retry por chamada do Resilience4j (verificado no seu jar 2.4.0: campos `LongAdder` em `RetryImpl`; não afirmado para o seu breaker), médias em laço fechado, só ARM, a linha das leituras de relógio, e a janela por contagem aproximada (as duas corridas verificadas no código). A linha do timeout já estava qualificada.
- [x] **2.5 Benchmarks por trás dos destaques honestos.** Adicionar um caso de breaker com uma taxa de falhas realista (0.1 % e 1 %) ao lado de "success" e "mixed", com uma e quatro threads, com o Resilience4j na mesma taxa. Rodá-lo na AWS e citá-lo na 2.2.
  - **Resultado:** (#64) Feito: `CircuitBreakerFailureRateBenchmark` (0 B, no portão de alocação), rodado em 8 máquinas Graviton3 com `-f 6` junto com os casos de sucesso e de uma em dez ([taxas de falha realistas](benchmarks.md#failure-rates)). Com quatro threads: 11.2 ns a 0.1 %, 65.4 ns a 1 %, contra cerca de 1 000 ns para o Resilience4j. Citado em `performance.md`. O resto da tabela de destaques (medida com a 0.1.0) não foi rodado de novo: mantém sua fonte e data, e as linhas medidas desde então (bulkhead, breaker) citam sua própria execução.
- [x] **2.3 Contradições.**
  - `start/how-it-works.md`, "Ordem das políticas": "A ordem ainda não é configurável" (errado desde `@PolicyOrder`).
  - As faixas de fatos das páginas das políticas dizem "x de 7"; há 8 políticas.
  - `README.md` da raiz "os nomes ainda podem mudar antes da 1.0" vs o FAQ "nenhuma mudança de API planejada". Ficar com a do FAQ.
  - `policies/retry.md` "Letras miúdas" cita R4C-ATR-006 para `enabled`: aposentado.
  - A tabela de prefixos de `errors.md` não tem linha `R4C-ORD`.
  - `guides/troubleshooting.md` [R4C-RT-004]: reescrever com a regra atual (o core pode ser mais novo que o processador; um core mais antigo é RT-021).
  - Tabela de artefatos de `start/installation.md`: a linha das anotações não tem `@Hedge`, `@AdaptiveLimit`, `@PolicyOrder`.
  - O exemplo gerado de `policies/circuit-breaker.md` mostra `50.0f`; o código gerado emite `50.0`. Atualizá-lo a partir de `examples/build/generated/...`.
  - Os chips da página inicial dizem "Sync e CompletionStage": acrescentar `Mono`/`Flux`.
  - O roadmap do FAQ diz "Configuração YAML: v1", embora Spring, Micronaut e Quarkus já leiam YAML: dizer o que se quer dizer (arquivos YAML em Java puro) ou remover.
  - **Resultado:** (#63) Feito. "x de 7" estava certo (Retry e Hedge compartilham uma camada): agora diz "camada x de 7", e os tipos de retorno listam `Mono` e `Flux`.
- [x] **2.4 Caminho do iniciante.**
  - Quickstart passo 6, "Veja o breaker abrir": baixar `minimumCalls`, mostrar `OPEN`, a `CallNotPermittedException` e o fallback respondendo (trecho testado em `examples`).
  - Um guia "Retry e circuit breaker para uma chamada HTTP": quais exceções repetir ou em quais abortar, um `@Timeout` dentro, um `name` de breaker, números sensatos, idempotência. Página nova nas duas barras laterais.
  - Um glossário curto, como definições em `policies/README.md` ou em sua própria página: jitter e "full jitter", half-open e sondas, id de instância e id derivado, registry, bulkhead, hedge, exceção sem stack trace, código em linha reta, e a verificação de ABI (`Abi.require` nos exemplos gerados).
  - Páginas das políticas: mover o bloco de código gerado para baixo de "Como se comporta" (ou recolhê-lo).
  - R4C-RT-003: a mensagem, `errors.md` e o "Verifique que funciona" da instalação mostram só Gradle; acrescentar Maven.
  - `guides/spring.md`: a aba Maven adiciona `spring-boot-configuration-processor` sem versão nem motivo; explicar ou remover. Mover detalhes de mantenedor (versões testadas 4.1.0 e 4.1.2, internos do AOT, a verificação de transações que lê bytecode) para uma seção final "Por dentro". A seção de transações (R4C-SPR-004) vem antes de "Configuração": colocar "Configuração" primeiro.
  - Barra lateral: rotular o grupo "Projeto" como sendo para contribuidores.
  - `errors.md` tem cerca de 3.200 linhas em uma página: adicionar uma dica no topo (buscar pelo código, a tabela de prefixos liga a cada área), ou dividi-la por área se o gerador do site e o `ErrorCodeTest` permitirem.
  - Maven: `annotationProcessorPaths` substitui a descoberta de todos os outros processadores (Lombok, MapStruct), então adicionar o nosso pode descartar os deles silenciosamente. Verificar que `start/installation.md` diz isso claramente ao lado do trecho Maven, com a correção (listar todos).
  - Padrões que surpreendem quem vem do Resilience4j: uma lista curta em `migrate/from-resilience4j.md` (e linkada a partir do FAQ). Candidatos que o revisor citou: um retry recua exponencialmente com jitter a partir de 200 ms por padrão; o rate limiter rejeita na hora (`maxWait = 0`); com classes anotadas, `@Transactional` fica fora das políticas, e a verificação na inicialização o recusa, então o código precisa ser reestruturado. Também a afirmação do revisor de que um `Error` conta como sucesso para o breaker: verificá-la primeiro em `CircuitBreakerPolicy` e nas camadas geradas; se for verdade, documentar (e decidir na fase 1 se esse é o comportamento certo); se for falsa, nada a fazer.
  - Nomes de métricas: usuários migrando do Resilience4j precisam refazer dashboards e alertas (`r4c.*`). Adicionar na página de migração uma tabela dos nomes de métricas do Resilience4j para os nossos.
  - O que o Resilience4j tem e nós não, em um só lugar na página de migração (parte já está lá em "quando ficar"): bulkhead com pool de threads, cache, RxJava, Kotlin, reconfiguração a quente (por decisão), uma transição automática open → half-open, endpoints de eventos, métricas de sucesso e de latência, e que os hedges não levam o contexto de quem chama às tentativas com hedge. Verificar cada um no código antes de escrevê-lo.
  - **Resultado:** (#63) Feito, com três coisas mantidas de propósito: os blocos de código gerado ficam ao lado dos trechos (a vista lado a lado é o objetivo do componente `compare`, e seu painel já rola); os detalhes do guia de Spring ficam onde estão (a ordem dos post-processors, o AOT e o limite da native image são voltados ao usuário; só a linha das versões foi simplificada); o parágrafo de transações fica em "Classes anotadas", onde se aplica. A afirmação sobre `Error` é verdadeira (`recordOn` tem padrão `Exception`), já documentada, e agora linkada a partir do FAQ.

Verificação: `./gradlew :examples:test --tests '*DocsTest*' :site:test`, e `./gradlew :site:serve` para ver as páginas alteradas. A 2.5 é uma mudança de benchmark (seu próprio pull request, com uma execução na AWS).

## Fase 3: bugs suspeitos :id=phase-3-suspected-bugs

Um pull request por bug confirmado. Para cada um: primeiro escrever um teste que o reproduza (seam determinístico ou jcstress, nunca timing), vê-lo falhar, depois corrigir, depois fazer a verificação por mutação do teste. Se não reproduzir, anotar o porquê nesta página e fechar a tarefa.

- [x] **3.1 Bulkhead: um slot livre com um assíncrono esperando na fila.** `BulkheadPolicy.exit` (por volta da linha 323): quando `hasQueuedThreads()` e `yieldToSync()`, ele libera e retorna sem drenar os assíncronos que esperam. Se a thread síncrona a quem cedeu então expira, é interrompida ou perde para alguém que entra na frente, o slot fica livre enquanto um assíncrono espera, até a próxima chamada; sem tráfego, esse que espera é rejeitado em `maxWait`. Testar por `enqueueForTest`/`drainForTest` e o semáforo. Correção possível: depois de ceder, drenar se o semáforo ainda tiver uma permissão e nenhuma thread na fila a tiver tomado. O jcstress hoje não cobre a passagem com cessão ao síncrono: adicionar um caso a `BulkheadWaitersStress` (ou a uma classe nova) e verificá-lo por mutação.
  - **Resultado:** (#65) Confirmado e corrigido. O novo caso de jcstress `BulkheadWaitersStress.YieldToSyncThatGivesUp` viu o slot livre com alguém esperando na fila uma vez em 1,45 milhão de execuções. Uma espera síncrona que termina sem slot agora drena os assíncronos que esperam, só no caminho de rejeição. Depois da correção ele passa em modo default (cerca de 2,3 milhões de execuções); a verificação por mutação é a execução antes da correção.
- [x] **3.2 Adaptive limit: amostras inconsistentes.** `AdaptiveLimitPolicy` (por volta da linha 281): `SUM` e `COUNT` de uma faixa são escritas com perda separadas, então uma corrida pode manter uma e perder a outra; uma faixa que volta atrás (uma thread preemptada entre leitura e escrita) pode tornar `sum - usedSum` negativo, limitado a um RTT de 1 ns, o que empurra o Gradient2 para cima. Correção mínima: um delta negativo ou zero pula a janela em vez de virar 1 ns; melhor, escrever soma e contagem de forma consistente (uma escrita empacotada, ou um número de sequência). Testar com o construtor `(id, config, env, stripes)` e seams; adicionar a corrida da faixa ao `AdaptiveLimitStress`, que hoje não a cobre.
  - **Resultado:** (#65) Confirmado e corrigido. `aStripeThatWentBackIsNotReadAsAOneNanosecondWindow` (seam `loseLatencyForTest`) lia a janela como 1 ns antes da correção. Uma contagem ou soma total abaixo da já usada agora descarta a janela e ressincroniza. Não adicionado ao jcstress: ele não consegue pausar uma thread entre a leitura e a escrita com perda.
- [x] **3.3 Rate limiter: um `now` desatualizado depois do park.** `RateLimiterPolicy` (por volta da linha 134): `now` é lido uma vez antes do laço; depois de um CAS perdido e do park de 1 ns (dezenas de µs no Linux), a espera é calculada a partir de um tempo antigo: quem chama dorme demais, ou com `maxWait = 0` pode ser rejeitado quando uma permissão já está disponível. Verificar se ler o relógio de novo depois de um park muda o benchmark (medir com uma e com quatro threads). O mesmo park dá aos perdedores uma cauda longa (cerca de 50 µs de folga de timer no Linux) que benchmarks de tempo médio não mostram: medi-la com as execuções de percentis (depois da 1.0, ou aqui se a 3.3 mudar o laço).
  - **Resultado:** (#65) Confirmado pela leitura; não reproduzível por um teste, porque nada pode rodar entre a leitura de `tat` e o compare-and-set. Corrigido lendo o relógio de novo depois do park, só no caminho de contenção. Medido na AWS: quatro threads, 183.6 contra 187.0 ns, então não ficou mais lento ([correções da revisão](benchmarks.md#review-fixes)). A cauda fica para as execuções de percentis depois da 1.0.
- [x] **3.4 Timeout: a interrupção do próprio usuário é limpa.** `TimeoutPolicy` (por volta da linha 218): depois que o watchdog interrompe, `Thread.interrupted()` também limpa uma interrupção que quem chama tinha definido antes. Decidir a semântica (manter uma interrupção anterior: definir a flag de novo se estava definida antes da chamada?) e testá-la com `Watchdog(0)`.
  - **Resultado:** (#65) Confirmado e corrigido. `anInterruptTheCallerAlreadyHadSurvivesTheTimeout` falhava antes da correção. O watchdog registra `callerInterrupted` antes da sua liberação de `TIMED_OUT`, e quem chama então mantém essa interrupção. O jcstress do timeout e do watchdog passa.
- [x] **3.5 Retry budget: atualização perdida sem limite.** `RetryBudget` (por volta da linha 125): uma thread preemptada entre sua leitura e escrita opacas pode apagar as chamadas de outras threads na sua faixa; as faixas são compartilhadas quando as threads passam de 2 × CPUs. A direção é segura (menos retries). Provavelmente documentar, não corrigir: escrever o limite (ou a falta dele) no Javadoc da classe e em `architecture.md`.
  - **Resultado:** (#65) Documentado, não corrigido: o Javadoc do RetryBudget e `architecture.md` agora dizem que a perda não tem limite fixo, só baixa o budget e só acontece quando as faixas são compartilhadas.
- [x] **3.6 Timeout com virtual threads.** A primeira chamada com tempo de cada thread nova registra um `ThreadScopes` em uma fila compartilhada e o watchdog percorre todas as threads registradas a cada tick. Com uma virtual thread por requisição, isso é uma alocação por requisição e uma varredura proporcional às threads vivas. Fazer um benchmark (um caso JMH que inicia uma virtual thread por chamada); depois decidir se corrige ou documenta.
  - **Resultado:** (#65) Medido com o novo `VirtualThreadTimeoutBenchmark`: cerca de 270 B por thread nova; nenhum custo de tempo com uma thread; +0.8 µs (+8.6 %) sobre um início de thread de 9.3 µs com quatro threads iniciando ao mesmo tempo. Documentado na página do timeout e em [benchmarks](benchmarks.md#review-fixes), não alterado: só a primeira chamada com tempo de uma thread paga.

## Fase 4: release :id=phase-4-release

- [x] (#66) As correções de documentação da segunda revisão: a primeira chamada com tempo de uma thread nova aloca (`performance.md`), o rate limiter (não o bulkhead) estaciona, as funcionalidades que leem o relógio, o texto do passo 6 do quickstart e um link para o guia HTTP, um teste para a linha do timeout do guia, o link do FAQ para os padrões, a média aproximada do adaptive limit, este status.
- [x] (#66) `SECURITY.md`: versões suportadas e como relatar uma vulnerabilidade de forma privada.
- [x] Uma execução completa do jcstress em ARM (AWS Graviton), já que o CI só o roda em x86.
  - **Resultado:** 2026-11-02, uma `c7g.4xlarge` (16 núcleos Graviton3), `-m default`, cerca de 40 minutos: 65 testes, 2598 configurações, nenhuma falhou, nenhum erro. Um teste é "interessante", como esperado: `RetryBudgetStress.CallsAreNeverOvercounted` perdeu chamadas em 10.9 % das suas amostras, nunca contou uma a mais. É a perda documentada da tarefa 3.5. Escrevemos então que ela aparece com muito mais frequência em ARM do que em x86; as execuções posteriores do CI em ambos (runners de 4 núcleos, modo quick) viram taxas parecidas, 5.5–8.6 % em ARM e 6.3–18.6 % em x86, já que perder uma chamada não precisa de reordenação ([blog](blog/jcstress-on-arm-and-x86.md)). A execução usou uma configuração Terraform descartável fora do repositório; rodá-la de forma agendada está em "Depois da 1.0".
- [x] Decidir se um processador ausente deve fazer a inicialização falhar no Spring, Micronaut e Quarkus antes da 1.0 (decisão do mantenedor; o adotante argumenta que adicionar isso depois muda o comportamento dentro da major).
  - **Resultado:** sim, antes da 1.0.0, em uma 1.0.0-RC2. Um bean cuja classe (ou uma superclasse), ou uma interface que ele implementa, declara políticas mas não tem classe gerada agora faz falhar:
    - a inicialização do Spring com R4C-SPR-006;
    - a criação de um bean do Micronaut de uma classe anotada com R4C-MN-007 (uma interface anotada só é envolvida por `@ResilientBean`, que já falha com R4C-RT-003);
    - o build do Quarkus com R4C-QK-008.

    Cada um tem um interruptor para desligá-lo (`allow-missing-processor`), e beans excluídos não são verificados. Spring e Micronaut leem os class files (`PolicyAnnotations`, uma cópia em cada, mantidas em sincronia), então a verificação não roda em uma native image; o Quarkus usa o índice Jandex. Os testes carregam as fixtures anotadas sem o seu `$Resilient`: um class loader que o esconde (Spring, Micronaut), ou uma raiz de aplicação sem ele (Quarkus). Cada verificação foi checada por mutação.
- [x] Decidir sobre um release candidate: `1.0.0-RC1` no Central, uma ou duas semanas de uso, depois a 1.0.0. (Decisão do mantenedor.)
  - **Resultado:** sim. Os trechos de instalação ficaram na 0.13.0 até a RC3; desde então mostram o release candidate mais recente (decisões do mantenedor).
- [x] Lançar a 1.0.0-RC1 seguindo [releasing](releasing.md).
  - **Resultado:** lançada em 2026-11-02 (commit `release 1.0.0-RC1`, tag `v1.0.0-RC1`, execução 41553226849 do `release.yml`, publicada no portal do Central pelo mantenedor).
    - `apiCompatibility` em relação à 0.13.0 mostrou só as mudanças pretendidas: o evento renomeado, os dois construtores do Micronaut, e `FluxCall$Attempt` (só de código-fonte).
    - A verificação do código gerado (`-Pr4c.generatedBy=0.13.0`) não se aplica: de 0.x para 1.0 cruza uma versão major, e os testes já usam `CircuitBreakerEvent.Rejected`.
    - As quatro comparações da página inicial foram medidas de novo com o código do release candidate ([benchmarks](benchmarks.md#release-candidate), #67).
- [x] Depois do release candidate: `version=1.0.0-SNAPSHOT`, `r4c.apiBaseline=1.0.0-RC1`, javadoc.io sincronizado e gerado para a 1.0.0-RC1 nos 11 módulos.
- [ ] Lançar a 1.0.0 depois de uma ou duas semanas de uso, a menos que algo precise mudar:
  - versão, tag `v1.0.0`, `release.yml`, Publish no portal;
  - depois os trechos de instalação para 1.0.0 (não o blog), `r4c.apiBaseline=1.0.0`, javadoc.io;
  - daí em diante, a verificação do código gerado (`-Pr4c.generatedBy=<release>`) é o passo 1 de todo release.

## Terceira revisão :id=third-review

Os mesmos quatro revisores leram a 1.0.0-RC2 em 2026-11-05. Nenhum rodou o build inteiro; o iniciante rodou o quickstart com os jars da RC2 do Maven Central.

| Revisor | Veredito |
|---|---|
| Um designer de API | Lançar a 1.0.0 com esta API; nada seria um erro permanente. |
| Um cético de desempenho e concorrência | Lançar a 1.0.0: nenhum bug de ordenação, de wake-up perdido, de ABA ou de exatamente-uma-vez encontrado no core; as correções da fase 3 se sustentam. Um falso positivo na verificação da RC2. |
| Um iniciante | Uma chamada protegida em cerca de 10 minutos; a saída do quickstart coincidiu exatamente na RC2. Afirmações desatualizadas sobre o release candidate. |
| Um usuário do Resilience4j decidindo se adota | Um piloto em dois ou três serviços, ainda não produção: nenhum usuário externo até agora, um único mantenedor, e só Spring Boot 4.1. |

- [x] Um tipo cujas anotações não alcançam nenhum método (uma interface anotada vazia, uma classe só com métodos final) não gerava nada e não reportava nada, e o Spring e o Quarkus então reportavam o processador como ausente. Agora é [R4C-APT-007](errors.md#r4c-apt-007); um bundle só com `@PolicyOrder` não conta mais como política nas verificações das integrações.
- [x] O breaker contava um `Error` como sucesso (`recordOn = Exception`), diferente do Resilience4j. Decisão do mantenedor: `recordOn` tem padrão `Throwable`.
- [x] Os jars do Quarkus não tinham `Automatic-Module-Name`; as mensagens de processador ausente citavam o processador sem versão.
- [x] Documentação: a página inicial, o FAQ, as páginas de instalação, solução de problemas, classes anotadas, timeout, compatibilidade e desempenho, e o post do blog da RC2 (veja a entrada "Não lançado" do changelog).
- [x] Lançar a 1.0.0-RC3 com essas mudanças (o padrão do breaker e o novo erro de compilação mudam comportamento), depois a 1.0.0.
  - **Resultado:** lançada em 2026-11-05 (commit `release 1.0.0-RC3`, tag `v1.0.0-RC3`, execução 42052831462 do `release.yml`, publicada no portal do Central pelo mantenedor). Os trechos de instalação mostram a 1.0.0-RC3.

Deixado para depois da 1.0 (abaixo): as verificações de processador ausente do Spring e do Micronaut em uma native image (o Spring poderia rodá-la no seu passo AOT), `Ticker.onAdvance` sem forma de cancelar o registro, `onInstanceCreated` sem a nova instância, nenhuma forma de parar as threads daemon compartilhadas (um vazamento em uma aplicação web recarregável), o tick do watchdog que só diminui, e os nomes diferentes dos interruptores das três integrações (deliberados; a escrever em [design](design.md)). Fora do código: nenhum feedback externo ainda. A proteção do branch `main` e os GitHub releases dos release candidates foram adicionados em 2026-11-06 (passo 8 de [releasing](releasing.md#release-steps)).

## Depois da 1.0 :id=after-10

Aditivo, então não bloqueia o congelamento. Aproximadamente em ordem de valor. Os itens marcados com *(pedido antes da 1.0)* são os que um revisor queria antes do release: o mantenedor decide se os antecipa.

- Rodar a comparação com o Resilience4j em x86 (`c7i`) e publicá-la ao lado da Graviton; em x86 todo atômico é um fence completo e `nanoTime` é barato, então as escolhas ajustadas para ARM podem se classificar de outro jeito. *(pedido antes da 1.0)*
- ~~jcstress em modo default em um runner ARM (`ubuntu-24.04-arm` ou Graviton) de forma agendada.~~ Feito antes da 1.0: `stress` roda o modo quick em x86 e ARM depois de cada push para main e todas as noites, e `stress-default` roda o modo default em x86 e ARM toda semana. Os mutantes foram então rodados de novo nas duas máquinas (`stress-mutants`, à mão, modos quick e default): o ARM não pega nada que o x86 deixe passar. O controle e os dois mutantes de store-buffering são pegos em ambos; os cinco que só enfraquecem uma ordenação sobrevivem em ambos, e no Apple M1. Dois desses cinco (a nova verificação do semáforo do bulkhead, a contagem dos que esperam) são ordenados de qualquer forma por um read-modify-write atômico logo antes deles; os três em `TimeoutPolicy` estão além do que essas máquinas reordenam, mesmo com o teste adicionado para eles (`TimeoutStress.TickSeesWholeCall`). *(pedido antes da 1.0)*
- ~~Benchmarks de percentis (`SampleTime`) para o rate limiter e o bulkhead sob contenção.~~ Feito antes da 1.0 ([percentis](benchmarks.md#percentiles)): com quatro threads saturando um limitador, 0.1 a 1 % das chamadas estacionam e esperam 110 µs ou mais (p99.9; Resilience4j 910 µs); o bulkhead não tem cauda de park. Falta: uma execução em laço aberto (chamadas a uma taxa fixa, latência a partir do início planejado). *(pedido antes da 1.0)*
- ~~Uma falha explícita no Spring, Micronaut e Quarkus quando classes anotadas não têm classe gerada.~~ Feito antes da 1.0: veja a fase 4.
- Métricas opcionais de sucesso e de latência, medidas antes de adicionar (escritas compartilhadas por chamada), para paridade com os dashboards do Resilience4j. *(pedido antes da 1.0)*
- Suporte ao Spring Boot 3, ou um motivo documentado e um caminho de migração. *(pedido antes da 1.0)*
- Saúde do projeto: um segundo mantenedor com direitos de release, uma política de segurança (`SECURITY.md`) e uma política de suporte. *(pedido antes da 1.0)*
- Lacunas aditivas da API que o revisor de API listou:
  - `Ticker.onAdvance` não tem forma de cancelar o registro.
  - Os eventos carregam só `nanoTime`, sem hora de relógio de parede.
  - `ConfigException.problems()` retorna `List<String>`, não problemas estruturados (código, chave, origem).
  - Só os circuit breakers têm um método de listagem no registry; retry, hedge e timeout não têm handles.
- Funcionalidades que o Resilience4j tem, cada uma a decidir (construir, ou documentar por que não): bulkhead com pool de threads, cache, RxJava, suporte a Kotlin, uma transição automática open → half-open, endpoints de eventos, levar o contexto de quem chama às tentativas com hedge.
- Reavaliar os sinais de adoção em cerca de seis meses, como faria o revisor adotante: atividade, mantenedores, issues fechadas. Um piloto em um serviço Java puro de alto tráfego é o que ele recomendaria primeiro. Até existir um, `pilot/` roda serviços Spring Boot, Micronaut e Quarkus na última versão do Central sob 10 minutos de carga k6 com o backend fora do ar e lento, em uma máquina da AWS: todas as verificações passaram em 2026-11-13 (veja `pilot/README.md`). Não é um piloto com tráfego real.
- Da terceira revisão: a verificação de processador ausente em uma native image do Spring ou do Micronaut (o Spring poderia rodá-la no seu passo AOT, na JVM); `Ticker.onAdvance` retornando uma forma de cancelar o registro; `onInstanceCreated` passando a nova instância; uma forma de parar as threads daemon compartilhadas, ou documentação de que o `core` pertence ao class path do servidor em uma aplicação web recarregável; o tick do watchdog voltando a crescer quando não resta nenhum timeout curto; os nomes dos interruptores das três integrações escritos como deliberados em [design](design.md#api-decisions).
