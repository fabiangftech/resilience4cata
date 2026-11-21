# Hedging quando os hedges competem pelo mesmo serviço

Um hedge corta a cauda quando a chamada lenta foi azar, mas quando todas as tentativas esperam na mesma fila ele pode deixar a cauda muito pior: uma simulação de `@Hedge` contra um único serviço compartilhado, com cargas de 30 % a 95 %.

*5 de novembro de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor do resilience4cata*

*Atualizado no mesmo dia: as tabelas agora trazem a mediana de dez seeds em vez de só a seed 1, e o budget padrão com 95 % de carga acabou sendo um colapso lento.*

## A pergunta :id=the-question

Depois que a release candidate foi anunciada, um leitor perguntou como o hedging se comporta sob latência de cauda quando a segunda chamada disputa o mesmo serviço downstream. É a pergunta certa. Um hedge envia uma segunda requisição quando a primeira está lenta. Se a primeira está lenta por azar (uma pausa de GC, um cache frio, um disco lento), a segunda costuma vencer e a cauda encolhe. Se a primeira está lenta porque o serviço está ocupado, a segunda requisição o deixa mais ocupado, para todos os chamadores.

A [página do hedge](../policies/hedge.md) já diz para não fazer hedge contra uma dependência saturada, e que o budget de hedge "limita o dano, não o desfaz". Este artigo coloca números nessa frase.

## Como foi simulado :id=how-it-was-simulated

A pergunta é sobre filas, não sobre o custo da policy, então isto não é um benchmark JMH. O JMH mede quanto o código custa em um laço fechado, em que um serviço lento também deixa os chamadores mais lentos e esconde o colapso de que trata este artigo. Em vez disso, uma simulação roda o próprio `HedgePolicy` e o `AdaptiveLimitPolicy` da biblioteca, por meio de `Pipeline`, no `ManualClock` do testkit, contra um serviço simulado:

- **O serviço:** 16 workers e uma fila FIFO compartilhada por todas as tentativas de todas as chamadas, então um hedge espera atrás das primeiras tentativas de outros chamadores.
- **Tempo de serviço:** log-normal com mediana de 10 ms; 2 % das requisições levam de 100 a 300 ms em vez disso. Cada requisição é sorteada por conta própria, então um hedge normalmente não pega o mesmo soluço.
- **Chegadas:** laço aberto (Poisson), a uma taxa que manteria os workers ocupados de 30 % a 95 % do tempo sem hedges. Um serviço lento não desacelera as chegadas, como acontece com usuários reais.
- **O hedge:** uma segunda tentativa depois de 20 ms, um pouco acima do p95 do serviço.
- **Cada ponto:** 200,000 chamadas, os primeiros 10 % não medidos, com dez seeds (1 a 10). As tabelas dão a mediana sobre as seeds e, entre parênteses, o menor e o maior p99 quando diferem.

Estes são os setups comparados:

<!-- snippet: hedge-setups -->
```java
NO_HEDGE("no hedge", null),
HEDGE_NO_BUDGET("hedge, no budget", b -> b.hedge(h -> h.delay(DELAY).budgetPercent(0))),
HEDGE_DEFAULT_BUDGET("hedge, default budget", b -> b.hedge(h -> h.delay(DELAY))),
HEDGE_BUDGET_5("hedge, budget 5 %", b -> b.hedge(h -> h.delay(DELAY).budgetPercent(5))),
HEDGE_BUDGET_ADAPTIVE("hedge, default budget, adaptive limit", b -> b.hedge(h -> h.delay(DELAY)).adaptiveLimit()),
THREE_ATTEMPTS_NO_BUDGET("3 attempts, no budget", b -> b.hedge(h -> h.delay(DELAY).maxAttempts(3).budgetPercent(0)));
```

O budget padrão permite hedges para 10 % das chamadas mais 1 por segundo ao longo de 10 segundos. O adaptive limit tem os seus padrões (Gradient2). A única coisa que a simulação varia é se uma tentativa cancelada interrompe o seu trabalho no servidor.

> [!NOTE]
> Estes são os resultados de um modelo, determinístico para uma seed, não medições de tempo real. Eles mostram como as policies reagem à carga; uma rede real, timers reais e um servidor real acrescentam ruído e custos que o modelo deixa de fora. O código está em `examples/src/test/java/dev/resilience4cata/examples/hedgesim`, e `./gradlew :examples:hedgeSimulation` escreve o relatório completo.

## Quando o servidor ignora o cancelamento :id=when-the-server-ignores-the-cancellation

Este é o caso comum de uma chamada HTTP: quando uma tentativa vence, o hedge cancela a outra, mas cancelar só faz o cliente parar de esperar. O servidor termina o trabalho mesmo assim.

Latência p99 / p99.9 em ms, por carga sem hedges (mediana de dez seeds; menor e maior p99 entre parênteses):

| Setup | 50 % | 70 % | 85 % | 95 % |
|---|---:|---:|---:|---:|
| Sem hedge | 200 / 290 | 201 / 290 | 205 / 294 | 237 / 349 |
| Hedge, sem budget | **30 / 36** (30–76) | colapso | colapso | colapso |
| Hedge, budget padrão (10 %) | **30 / 36** (30–33) | 373 / 460 (322–407) | 530 / 616 (421–610) | colapso lento |
| Hedge, budget 5 % | **30 / 36** (30–31) | 154 / 275 (130–168) | 216 / 336 (191–262) | 962 / 1,049 (423–1,652) |
| Hedge, budget padrão, adaptive limit | **30 / 36** (30–31) | **58 / 268** (56–63), 1.5 % rejeitadas | **122 / 293** (102–157), 3.8 % rejeitadas | **167 / 305** (149–182), 7.4 % rejeitadas |
| 3 tentativas, sem budget | colapso em 4 seeds de 10 | colapso | colapso | colapso |

"Colapso" significa que a fila nunca esvazia: toda chamada espera o bastante para enviar os seus hedges, então a carga dobra (triplica com três tentativas) e fica assim. A latência então cresce enquanto durar a execução, aqui até minutos. "Colapso lento" é o mesmo com menos carga extra: os workers ficam ocupados 99.9 % do tempo e a fila cresce devagar. Com a seed 1, o p99 é 2.6 s depois de 100,000 chamadas, 6.7 s depois de 200,000 e 14.2 s depois de 400,000, então nenhum número isolado o descreve.

O que os números dizem:

- **Com folga, o hedging funciona como prometido.** Até 50 % de carga, o p99.9 cai de 290 ms para 36 ms com 2.3 % a mais de requisições, com ou sem budget. Sem budget, uma rajada de chegadas em uma seed de dez levou o p99.9 a 199 ms.
- **Sem budget, ele colapsa.** Quando o enfileiramento empurra chamadas comuns para além dos 20 ms de espera, elas também fazem hedge, e a carga extra mantém a fila longa. Ele não se recupera sozinho: é um estado estável. Três tentativas já colapsaram a 50 % em quatro seeds de dez, todas as vezes depois de uma rajada de chegadas.
- **O budget evita o colapso, mas não o dano.** As requisições nunca passam de 1.10 por chamada. Mas hedges que disparam por causa da fila, não de um soluço, gastam o budget e somam carga onde há pouco espaço. A partir de 70 % de carga, a cauda com o budget padrão é pior do que sem nenhum hedge. A 95 %, 10 % a mais de carga não cabem em 5 % de folga, e o serviço colapsa lentamente.
- **Um budget menor ajuda.** Aqui 2.2 % das chamadas precisaram de um hedge, e 5 % bastaram para elas. Ele se sai melhor do que 10 % em todas as cargas e vence o sem hedge a 70 %, mas a 85 % e 95 % ainda é pior do que sem hedge. A 95 % ele deixa os workers a 99.5 %, e o seu p99 é o que mais varia entre seeds: de 423 a 1,652 ms.
- **O adaptive limit mantém a cauda curta.** Conforme a latência sobe, ele reduz o número de tentativas permitidas em andamento e recusa as outras, primeiras tentativas e hedges da mesma forma; um hedge rejeitado também impede os seguintes. É também o setup mais estável entre seeds. A 95 % de carga o p99 é 167 ms em vez de 237 ms sem hedges, mas 7.4 % das chamadas são rejeitadas com `AdaptiveLimitFullException`, que o chamador precisa tratar (com um fallback, por exemplo).

## Quando o cancelamento chega ao servidor :id=when-the-cancellation-reaches-the-server

Com um cancelamento que chega ao servidor (o gRPC o propaga, por exemplo), uma perdedora na fila sai da fila e uma em atendimento libera o seu worker. O quadro muda completamente:

| Setup | 50 % | 70 % | 85 % | 95 % |
|---|---:|---:|---:|---:|
| Sem hedge | 200 / 290 | 201 / 290 | 205 / 294 | 237 / 349 |
| Qualquer setup com hedge acima | 30 / 36 | 30 / 36 | 30 / 36 | 31 / 38 |

Todo setup com hedge dá o mesmo resultado, em todas as cargas e em todas as seeds. Cancelar a tentativa lenta economiza o resto do trabalho dela: a 95 % de carga, os workers ficam ocupados 74 % do tempo em vez de 95 %. Aqui o hedging tira carga do serviço, porque a maioria dos hedges interrompe uma requisição que teria segurado o seu worker por 100 a 300 ms.

## O que tirar disso :id=what-to-take-from-it

- **Saiba se o seu cancelamento chega ao servidor.** Se chegar, o hedging é seguro em uma faixa ampla de cargas. Se não chegar, o hedging precisa de folga.
- **Mantenha o budget ligado.** Sem ele, uma rajada pode jogar o serviço em um estado do qual ele não sai. O resilience4cata o liga por padrão por esse motivo.
- **Dimensione o budget para os hedges que você espera.** Se 2 % das chamadas são lentas por azar, um budget de 10 % deixa 8 % para hedges causados por enfileiramento, que só somam carga. `budgetPercent` é o ajuste.
- **Coloque um adaptive limit dentro do hedge** quando o serviço puder ficar sobrecarregado. Ele transforma a sobrecarga em rejeições rápidas em vez de uma fila longa.
- **Defina a espera acima da latência que só o enfileiramento causa.** Uma espera próxima da mediana faz hedge de chamadas comuns assim que o serviço fica ocupado.

## Ressalvas :id=caveats

- Um serviço com uma fila. Serviços reais têm várias réplicas; um hedge enviado a uma réplica menos ocupada pode ajudar mais do que aqui, e um load balancer que o envia à mesma réplica ocupada, menos.
- Os soluços são independentes. Quando todas as réplicas compartilham o mesmo caminho lento (um banco de dados lento por trás de todas), um hedge espera duas vezes e nunca ajuda; a simulação não cobre esse caso.
- Sem rede, sem timers reais, sem escalonamento de threads. Uma confirmação em relógios reais, com um gerador de carga e um servidor em máquinas separadas, é um trabalho futuro possível.
- Dez seeds, um modelo de tempo de serviço, uma espera. A forma se manteve em todas as seeds, mas os números a 85 % e 95 % variam entre elas, como mostram os parênteses (até quatro vezes, para o budget de 5 % a 95 %).
- O cancelamento do modelo é instantâneo e gratuito. Um cancelamento real leva uma viagem pela rede, e muitos servidores só o verificam entre etapas do seu trabalho, então a segunda tabela é um teto, não uma previsão.

## Experimente :id=try-it

Uma seed roda em cerca de um minuto, as dez seeds das tabelas em cerca de dez:

```bash
./gradlew :examples:hedgeSimulation -Phedge.seed=1
./gradlew :examples:hedgeSimulation -Phedge.seeds=1-10
```

Para experimentar hedging no seu próprio serviço, comece pela [página do hedge](../policies/hedge.md) e pelo [adaptive limit](../policies/adaptive-limit.md). Perguntas e resultados dos seus próprios serviços são bem-vindos nas [discussões](https://github.com/fabiangftech/resilience4cata/discussions).
