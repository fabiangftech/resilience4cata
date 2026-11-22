# 03 — Internos de desempenho: algoritmos e concorrência por política

> **Histórico.** Esta pesquisa é anterior à implementação e é mantida como foi escrita; para saber o que a biblioteca faz hoje, veja [funcionalidades](../policies/README.md), [arquitetura](../architecture.md) e [benchmarks](../benchmarks.md).

> Pesquisa da fase 1. Sem código de produção. Os trechos curtos são apenas ilustrativos. Última atualização: 2026-10-23.
> Escopo: qual algoritmo usar para cada política de resiliência e como construí-lo em Java moderno (21+) para que o caminho quente seja barato, sem alocação e escalável sob contenção. Inclui como o Resilience4j faz isso hoje e quanto custa, além de uma metodologia de benchmark.
> Fora do escopo (coberto pelos documentos irmãos): panorama de concorrentes (01), mecanismo de processamento de anotações, superfície de API/configuração (04), organização dos módulos (05).

## Legenda de evidências :id=evidence-legend

| Marca | Significado |
|---|---|
| **[SRC]** | Verificado lendo o código-fonte. Resilience4j no commit [`b7c0069`](https://github.com/resilience4j/resilience4j/tree/b7c0069802e373ed72dff0b817c270fbb6e966ba) de `master` (2026-10-07, após a 2.4.0). OpenJDK nas tags `jdk-21+35` e `jdk-25+36`. Netty `4.1`. |
| **[DOC]** | Verificado na documentação oficial, em uma página de JEP, em uma especificação ou em um artigo. |
| **[INF]** | Inferido por nós a partir do código, do comportamento da JVM ou de conhecimento geral, **não medido**. Deve ser validado com JMH e/ou jcstress em uma fase posterior. |
| **[PROP]** | Nossa proposta de design. É uma hipótese a medir em benchmark, não um fato. |

Os números dados sem **[DOC]** ou **[SRC]** são ordens de grandeza, apenas para raciocinar.

---

## 0. Resumo :id=_0-tldr

| Política | Resilience4j hoje (custos principais) | Recomendado para o resilience4cata |
|---|---|---|
| Circuit breaker | `ReentrantLock` em volta de cada resultado registrado (estratégia padrão `SYNCHRONIZED`); um novo `Snapshot` por chamada; alocações de `Instant` nas janelas por tempo e no estado OPEN; uma opção `LOCK_FREE` que aloca um nó e uma cópia das estatísticas por chamada. [SRC] | Uma palavra de estado em um int/long via `VarHandle`. Janela por contagem como um array de slots atualizado com `getAndSet`, mais contadores por resultado. Janela por tempo como um anel de buckets marcados por época. Avaliar os limiares só quando uma taxa pode subir. Permissões marcadas por geração para o half-open. Zero alocação em todos os caminhos. [PROP] |
| Rate limiter | `AtomicRateLimiter`: um novo `State` imutável por tentativa de CAS, relógio `Supplier<Long>` com boxing, back-off com `parkNanos(1)`, rajadas de janela fixa nas bordas dos ciclos. `SemaphoreBasedRateLimiter`: um semáforo **justo** e uma thread de scheduler por limiter. [SRC] | **GCRA** sobre um único `long` (tempo teórico de chegada) com um laço de CAS. A configuração de token bucket se mapeia nele. Sem alocação, sem thread em segundo plano. [PROP] |
| Bulkhead | `Semaphore.tryAcquire(timeout)`, uma lambda com captura por chamada para os eventos e um segundo atômico disputado (`grantsInProgress`) em cada liberação. [SRC] | Um contador que falha rápido (`getAndAdd`), com um caminho lento que espera em um semáforo só quando `maxWait > 0`. Com o Loom, um bulkhead por thread pool é uma funcionalidade de nicho. Limites adaptativos são uma política separada. [PROP] |
| Retry | Quatro alocações por chamada (`ContextImpl` + 3 atômicos) mesmo quando a primeira tentativa tem sucesso; boxing na função de intervalo. [SRC] | Estado da tentativa em variáveis locais (código gerado ou um laço). Alocar só em caso de falha. Jitter descorrelacionado com `ThreadLocalRandom`. Um **orçamento** de retries como token bucket sobre um `long`. [PROP] |
| Timeout | O caminho síncrono precisa de um `Future` (um salto de thread). O caminho assíncrono usa um `ScheduledExecutorService` por chamada. [SRC] | Um watchdog com roda de timers (timer wheel) que interrompe o chamador (virtual threads tornam o I/O interrompível). Roda hashed ou hierárquica com cancelamento O(1). Sem salto de thread. [PROP] |
| Transversal | Alguns eventos são alocados antes da verificação "tem consumidores"; `String.format` por exceção de rejeição; o aninhamento de lambdas cria call sites megamórficos. [SRC][INF] | Callbacks de listener com argumentos primitivos, protegidos por uma verificação "sem listeners" que pode ser constant-folded. Exceções de rejeição pré-alocadas e sem stack trace, ou um resultado que não lança. **Código acquire/release gerado por call site, em linha reta, em vez de lambdas aninhadas.** Um único par de `nanoTime` compartilhado por pipeline. [PROP] |

A maior alavanca isolada provavelmente não é nenhum algoritmo em particular, mas a **composição** (§7.4). As lambdas de decoradores do Resilience4j compartilham um único call site de bytecode por tipo de política em toda a aplicação, então o JIT vê chamadas `supplier.get()` megamórficas e não consegue fazer inline nem escape analysis entre as camadas. Uma biblioteca orientada a anotações pode gerar código monomórfico e em linha reta para cada método anotado. [INF]

---

## 1. Caixa de ferramentas de baixo nível (Java moderno) :id=_1-low-level-toolbox-modern-java

### 1.1 Primitivas atômicas :id=_11-atomic-primitives

- **`VarHandle` vs `Atomic*`.** Ambos compilam para as mesmas instruções de CPU (`LOCK CMPXCHG` / `LOCK XADD` em x86; LSE `CAS`/`LDADD` ou LL/SC em AArch64). Um `VarHandle` sobre um campo do objeto da política evita o objeto extra e o salto de ponteiro de um `AtomicLong` separado. Também expõe modos mais fracos (`getOpaque`, `getAcquire`/`setRelease`, `weakCompareAndSetPlain`), que são mais baratos em ARM. Use campos `static final VarHandle` para que o JIT os trate como constantes. [DOC] ([javadoc de VarHandle](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/VarHandle.html))
- **`getAndAdd` vs laços de CAS.** `getAndAdd` (x86 `LOCK XADD`, ARM `LDADD`) sempre tem sucesso em uma instrução. Um laço de CAS pode tentar de novo muitas vezes sob contenção. Prefira aritmética que caiba em `getAndAdd` e reserve o CAS para transições que precisam da semântica de "comparar" (máquinas de estado, GCRA). [INF]
- **`LongAdder` / `Striped64`.** As escritas vão para células escolhidas pelo probe de cada thread, então os incrementos disputados escalam. As leituras (`sum()`) são O(células) e não são snapshots atômicos. É ideal para contadores de métricas, mas errado para decisões que precisam de um valor consistente e exato (por ex. "admitir se count < max"). [DOC] ([LongAdder](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/LongAdder.html))
- **Locks.** Um `ReentrantLock` sem disputa custa cerca de um CAS para travar e um store de release para destravar. Sob contenção ele estaciona (park) as threads, e um dono do lock que perde a CPU trava todo mundo (o próprio Javadoc de configuração do Resilience4j diz isso, veja §2.1). `synchronized` fixa (pinning) virtual threads no JDK 21–23 (corrigido no JDK 24 pelo [JEP 491](https://openjdk.org/jeps/491)). A biblioteca nunca deve bloquear enquanto segura um monitor. [DOC]

### 1.2 False sharing e padding :id=_12-false-sharing-and-padding

- Dois campos quentes, escritos de forma independente, na mesma linha de cache de 64 bytes (128 bytes por causa do prefetch de linha adjacente em muitas CPUs Intel, e o tamanho da linha nos Apple série M) fazem os núcleos brigarem por essa linha. [INF]
- `@jdk.internal.vm.annotation.Contended` (originalmente [JEP 142](https://openjdk.org/jeps/142)) só tem efeito em classes de usuário quando a JVM roda com `-XX:-RestrictContended`, e fica em um pacote interno. Uma biblioteca não pode depender dela. [DOC]
- **Padding manual por hierarquia de classes** (como fazem o JCTools e o Agrona: `class Pad0 { long p01..p16; } class Value extends Pad0 { volatile long value; } class Pad1 extends Value { long p17..p32; }`) funciona porque o HotSpot dispõe primeiro os campos da superclasse. É a técnica portável. [INF]
- **Regra de layout para todo objeto de política:** manter juntas a configuração de leitura predominante (limiares, intervalos) e as referências imutáveis. Isolar cada palavra quente **escrita de forma independente** (estado, contadores, TAT) na sua própria linha com padding. Não aplicar padding em tudo: padding custa memória, o que importa com mais de 10 mil instâncias nomeadas (multi-tenant). Fazer do padding uma escolha em tempo de build para instâncias "quentes", ou aplicá-lo só às palavras muito escritas. [PROP]

### 1.3 Tempo :id=_13-time

- Custo de `System.nanoTime()`: em Linux com a fonte de relógio TSC, cerca de **25 ns de latência e ~26 ns de granularidade**, escalando bem entre threads. No Windows (medições mais antigas) eram ~14 ns de latência, mas **~370 ns de granularidade**, e **não** escalava com 32 threads (~15 µs). [DOC] ([Shipilëv, *Nanotrusting the Nanotime*](https://shipilev.net/blog/2014/nanotrusting-nanotime/), números da época do JDK 7; medir de novo no JDK 21/25 e em macOS/AArch64.)
- `Clock.instant()` / `Instant.now()` aloca um `Instant` e lê o relógio de parede, que não é monotônico. É errado para medir intervalos e um desperdício nos caminhos quentes. [INF]
- **Relógio em cache:** uma thread daemon "ticker" que escreve `nanoTime()` em um `volatile long` a cada 1 ms. Os leitores pagam um load simples. Custos: uma thread permanente, resolução de 1 ms e o risco de o ticker travar por falta de CPU (o tempo congela, os timeouts nunca disparam). Bom para **decisões grosseiras** (a espera em OPEN acabou? qual bucket de tempo?), mas ruim para limiares de chamada lenta abaixo de ~10 ms. [INF]
- **Proposta:** uma abstração `Ticker` com três implementações: `nano` (padrão), `cached(1ms)` (opcional) e `manual` (testes). Um par de `nanoTime()` por **pipeline**, não por política (§7.5). Pular a medição de tempo por completo quando nenhuma política do pipeline precisa de durações (sem limiar de chamada lenta, sem métricas de latência, sem adaptive limit). [PROP]

### 1.4 Alocação e o JIT :id=_14-allocation-and-the-jit

- A escape analysis do C2 pode eliminar alocações de vida curta, mas só quando tudo sofre inline. Call sites megamórficos (mais de 2 tipos de receptor), inlining profundo (o `MaxInlineLevel` padrão foi elevado de 9 no JDK 14 via [JDK-8234863](https://bugs.openjdk.org/browse/JDK-8234863)) e métodos grandes a derrotam. O código da biblioteca deve ser **sem alocação por construção**, não "sem alocação se o JIT for bonzinho". [DOC][INF]
- Armadilhas de boxing vistas no Resilience4j: timestamp `Function<Clock, Long>`, nano-time `Supplier<Long>`, sleep `CheckedConsumer<Long>`, backoff `Function<Long, Long>`. Cada `Long` acima de 127 é uma possível alocação. [SRC]
- Portão de CI: o JMH `-prof gc` deve reportar `gc.alloc.rate.norm ≈ 0 B/op` em todo caminho feliz e em todo caminho de rejeição (§9). [PROP]

---

## 2. Circuit breaker

### 2.1 Como o Resilience4j faz [SRC] :id=_21-how-resilience4j-does-it-src

Arquivos: [`CircuitBreakerStateMachine`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java), [`CircuitBreakerMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerMetrics.java), [`FixedSizeSlidingWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/FixedSizeSlidingWindowMetrics.java), [`SlidingTimeWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/SlidingTimeWindowMetrics.java), [`LockFreeFixedSizeSlidingWindowMetrics`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/LockFreeFixedSizeSlidingWindowMetrics.java), [`CASBackoffUtil`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-core/src/main/java/io/github/resilience4j/core/CASBackoffUtil.java), [`CircuitBreakerConfig`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/CircuitBreakerConfig.java).

- **Estado:** `AtomicReference<CircuitBreakerState>`. Cada estado é um objeto (`ClosedState`, `OpenState`, `HalfOpenState`...) com seu próprio `CircuitBreakerMetrics` e `AtomicBoolean`. As transições usam `getAndUpdate(lambda)`, que aloca novos objetos de estado (raro, então tudo bem).
- **Permissão em CLOSED:** `isClosed.get()`, então o caminho rápido é barato.
- **Registro (estratégia padrão `SYNCHRONIZED`):** cada `onSuccess`/`onError` pega um `ReentrantLock`, atualiza uma `TotalAggregation` mais um bucket do anel e **aloca um `SnapshotImpl`** que retorna. Depois, `CircuitBreakerMetrics.checkIfThresholdsExceeded` calcula as taxas de falhas e de chamadas lentas em **toda** chamada, inclusive nos sucessos.
  - Por contagem: um array de objetos `Measurement`, índice da cabeça sob o lock.
  - Por tempo: uma `PartialAggregation` por **segundo** (`timeWindowSizeInSeconds`), e `clock.instant().getEpochSecond()` em **todo registro** (uma alocação de `Instant`, a menos que a escape analysis a elimine).
- **Estratégia `LOCK_FREE`** (adicionada no PR [#2241](https://github.com/resilience4j/resilience4j/pull/2241), dez. de 2024, opcional; o padrão continua `SYNCHRONIZED`): uma lista encadeada de nós imutáveis. Cada `record` **copia** a `PackedAggregation` da cauda, **aloca um novo `Node`**, o insere por CAS e depois avança cabeça/cauda. Back-off sob contenção: `Thread.onSpinWait()` por algumas voltas, depois `LockSupport.parkNanos(...)` com jitter pelo id da thread. O próprio Javadoc de configuração declara o compromisso: lock-free "allocat[es] more objects", enquanto synchronized significa "one thread that acquired the lock and is superseded from the CPU will block all other threads".
- **Medição de tempo:** `getCurrentTimestamp()` chama `Function<Clock, Long>` (padrão `clock -> System.nanoTime()`), então há **boxing**, duas vezes por chamada.
- **Estado OPEN:** `tryAcquirePermission` chama `clock.instant().isAfter(retryAfterWaitDuration)` em **toda chamada rejeitada** (outro `Instant`). A rejeição então monta `CallNotPermittedException` com **`String.format`** a cada chamada (o stack trace pode ser desligado via `writableStackTraceEnabled`, mas a mensagem é sempre formatada).
- **Eventos:** os eventos de sucesso/erro são protegidos por `eventProcessor.hasConsumers()` **antes** da alocação. Mas `publishEventIfHasConsumer(new CircuitBreakerOnCallNotPermittedEvent(name))`, `...OnIgnoredErrorEvent`, `...OnResetEvent` e os eventos de limiar **alocam o evento primeiro** e só depois verificam. Então toda chamada rejeitada aloca um evento mesmo sem listener (a menos que a escape analysis o elimine).
- **Tratamento do resultado:** `onResult` com um resultado não nulo chama `handlePossibleTransition(Either.left(result))`, que aloca um `Either` por chamada bem-sucedida com resultado não nulo e invoca a função `transitionOnResult`.
- **Half-open:** `AtomicInteger.getAndUpdate(current -> current == 0 ? current : --current)`, um laço de CAS através de uma lambda (sem captura, então sem alocação).
- **Resultados obsoletos:** os resultados são aplicados a `stateReference.get()`, o estado **atual**. Então uma chamada lenta admitida em CLOSED que termina durante HALF_OPEN é contada como resultado de sondagem. [SRC: `onSuccess` → `stateReference.get().onSuccess(...)`] Se isso é um bug é discutível, mas torna ruidosas as decisões do half-open. [INF]
- Logging: `LOG.debug(...)` em todo caminho de sucesso/erro (barato quando desligado, mas ainda é uma chamada e uma verificação de nível). [SRC]

### 2.2 Algoritmos de janela :id=_22-window-algorithms

| Janela | Memória | Custo de atualização | Custo de leitura | Notas |
|---|---|---|---|---|
| Anel por contagem (últimas N chamadas) | O(N) slots | O(1) | O(1) com contadores correntes | "Últimas N" exatas. A ordem precisa de um contador de sequência global, portanto uma linha quente. |
| Por tempo, em buckets (B buckets × resolução) | O(B) | O(1) amortizado | O(B) para somar | Aproximada nas bordas dos buckets. Pode ser distribuída em faixas. |
| Por tempo, decaimento exponencial (EWMA da proporção de falhas) | O(1) | O(1) com `exp` ou uma tabela de consulta | O(1) | Sem bordas de janela. Difícil de explicar "minimumNumberOfCalls". Candidata a um modo *adaptativo*, não ao padrão. [INF] |

#### Por contagem, lock-free, sem alocação [PROP] :id=count-based-lock-free-allocation-free-prop

```java
// Illustrative only.
final class CountWindow {
  static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(int[].class);
  final int[] slots;          // outcome codes: 0 = EMPTY, 1 = OK, 2 = FAIL, 3 = SLOW_OK, 4 = SLOW_FAIL
  final int mask;             // N rounded up to a power of two; or use % N
  volatile long seq;          // padded; the only globally hot word
  // per-outcome counters, each on its own padded line (int fields + VarHandle getAndAdd)

  void record(int outcome) {
    long s = (long) SEQ.getAndAdd(this, 1L);
    int old = (int) SLOTS.getAndSet(slots, (int) (s & mask), outcome);
    if (old != outcome) { dec(old); inc(outcome); }   // the counters mirror the slot contents
  }
}
```

- Por chamada: um `getAndAdd` em `seq`, um `getAndSet` em um slot e 0–2 `getAndAdd`s de contador. Sem lock, sem alocação, sem laço de nova tentativa, então é wait-free. [PROP]
- Os valores dos contadores acabam iguais ao histograma do array de slots. Transitoriamente, um contador pode estar errado pelo número de chamadas `record` em andamento, e pode até chegar a −1 se um slot for sobrescrito antes de quem o escreveu ter incrementado. **Limitar a ≥ 0 na leitura.** Validar os invariantes com [jcstress](https://github.com/openjdk/jcstress). [INF]
- Empacotar todos os contadores em um único `long` (um único `getAndAdd` de um delta com sinal empacotado) economiza atômicos. Mas um campo negativo transitório toma emprestado do vizinho e poderia disparar um OPEN espúrio. Só fazer isso com bits de guarda; medir antes. [INF]
- Um N que não é potência de dois pode usar `Math.floorMod(s, N)` (uma divisão, alguns ns) ou arredondar a janela para cima. [INF]

#### Por tempo, anel de buckets [PROP] :id=time-based-bucketed-ring-prop

- B buckets de resolução configurável (por ex. 10 × 1 s, ou 20 × 500 ms). Cada bucket tem um `long epoch` mais contadores. Ao registrar: `tick = now / resolution` (de um relógio em cache ou do `nanoTime` compartilhado do pipeline). Se `bucket.epoch != tick`, fazer CAS na época e zerar os contadores. Incrementos concorrentes na borda podem se perder ou cair no bucket zerado, um erro limitado a cerca de um bucket de chamadas em andamento. Isso é aceitável para uma política estatística. [INF]
- Sem `Instant`, sem segundos de época: usar ticks monotônicos. [PROP]
- **Distribuição em faixas para contenção extrema:** S sub-anéis independentes escolhidos pelo probe da thread (como `Striped64`), somados só na avaliação. As janelas por tempo toleram isso porque a ordem não importa. As janelas por contagem não (a ordem das "últimas N" é global). [INF]

#### Avaliar os limiares só quando uma taxa pode subir [PROP] :id=evaluate-thresholds-only-when-a-rate-can-rise-prop

- Em um **sucesso rápido** em CLOSED, nem a taxa de falhas nem a de chamadas lentas podem aumentar. Pular a avaliação, o que tira a divisão e as comparações de >99% das chamadas em um sistema saudável.
- Avaliar em caso de falha, de sucesso lento e **enquanto a janela ainda não está "aquecida"** (total < `minimumNumberOfCalls`). Caso contrário, o sucesso que completa o mínimo (por ex. 6 falhas nas primeiras 10 chamadas) não abriria o circuito até a próxima falha.

### 2.3 Máquina de estados [PROP] :id=_23-state-machine-prop

- Um `volatile int state` (ou um `long` empacotando `state:2 | generation:30 | halfOpenPermits:16`) em um `VarHandle`, mais `long openUntilNanos`.
- **Caminho rápido em CLOSED:** um load acquire/simples de `state` e um desvio. Retornar um **token de permissão primitivo** (`long` = geração) em vez de um objeto, para que o código gerado possa passá-lo a `onSuccess(token, nanos)`.
- **Caminho rápido em OPEN:** ler `state`, depois ler `openUntilNanos` e comparar com `now`. Um ticker em cache serve aqui. Depois da expiração, a primeira thread a fazer o CAS OPEN→HALF_OPEN vence. As outras releem. Rejeitar com uma exceção **pré-alocada e sem stack trace** ou com um código de resultado (§7.3). Nada é alocado.
- **HALF_OPEN:** permissões como um campo contador. `getAndAdd(-1)`, e desfazer se ficar abaixo de 0. **Marcação por geração:** resultados cuja geração do token ≠ a geração atual são descartados (ou contados só nas métricas), para que chamadas obsoletas da época de CLOSED não decidam as sondagens. Opcionalmente, limitar as sondagens concorrentes separadamente do total de sondagens.
- **Transições:** CAS na palavra de estado. O vencedor zera a janela (caminho raro, pode alocar ou travar) e publica os eventos.
- **Estados forçados** (DISABLED, FORCED_OPEN, METRICS_ONLY) são apenas mais códigos de estado.

### 2.4 Detecção de chamadas lentas :id=_24-slow-call-detection

- Isso precisa de uma duração de início/fim, portanto duas chamadas a `nanoTime` (~50 ns no total em Linux [DOC, veja §1.3]). Compartilhá-las com todas as outras políticas temporizadas do pipeline (§7.5). [PROP]
- Se o limiar de lentidão for ≥ 100 ms, um ticker em cache de 1 ms é preciso o bastante e transforma cada leitura em um load simples. Fazer disso uma escolha por política, automática com base no limiar. [PROP]
- Quando nenhum limiar de chamada lenta está configurado e nenhuma métrica de duração está ligada, **não medir o tempo da chamada**. O Resilience4j sempre mede. [SRC][PROP]

---

## 3. Rate limiter

### 3.1 Algoritmos :id=_31-algorithms

| Algoritmo | Estado | Comportamento de rajada | Lock-free em uma palavra? | Notas |
|---|---|---|---|---|
| Contador de janela fixa | contagem + id da janela | Até **2× o limite** através de uma borda de janela | Sim (empacotar id + contagem em um `long`) | O mais simples; rajadas nas bordas. |
| Log de janela deslizante | timestamps das últimas L permissões | Exato | Não (memória O(L), anel de timestamps) | Preciso, mas memória ∝ limite. |
| Contador de janela deslizante | contagens de duas janelas adjacentes, ponderadas | Aproximado, suave | Quase (duas palavras) | Aproximação no estilo Cloudflare/nginx. |
| Token bucket | tokens + hora da última recarga | Até a capacidade | Sim, via a equivalência com o GCRA | O clássico. |
| Leaky bucket (como medidor) | nível + última hora | Até a capacidade | Sim, igual ao GCRA | Matematicamente equivalente ao GCRA. |
| Leaky bucket (como fila) | uma fila de quem espera | Nenhuma (suaviza a saída) | Não | Isto é um escalonador, não um limitador. |
| **GCRA** | um `long` TAT (tempo teórico de chegada) | Até a rajada configurada | **Sim, um CAS** | O que melhor se encaixa em um limitador dentro do processo. |

Referências: [GCRA (Wikipedia)](https://en.wikipedia.org/wiki/Generic_cell_rate_algorithm), [Brandur — Rate limiting, cells, and GCRA](https://brandur.org/rate-limiting). [DOC]

### 3.2 GCRA em uma palavra [PROP] :id=_32-gcra-on-one-word-prop

```java
// Illustrative only. T = emission interval (period / limit), tau = T * burst.
long tryAcquire(int permits, long nowNanos) {
  long inc = T * permits;
  for (;;) {
    long tat = (long) TAT.getVolatile(this);
    long newTat = Math.max(tat, nowNanos) + inc;
    long wait = newTat - nowNanos - tau;          // > 0 means "not yet"
    if (wait > 0) return wait;                    // the caller rejects, or waits ≤ timeout and then calls reserve()
    if (TAT.compareAndSet(this, tat, newTat)) return 0;
    Thread.onSpinWait();
  }
}
```

- Uma leitura volatile, um CAS, **sem alocação, sem thread em segundo plano**, e correto para qualquer número de permissões. Uma variante de "reserva" (o `reservePermission` do Resilience4j) faz o CAS de `newTat` mesmo quando `wait > 0` (limitado pelo timeout) e retorna `wait`. [PROP]
- Uma rejeição sem reserva é **somente leitura**: sem CAS, então não faz a linha de cache pular entre núcleos durante a sobrecarga. Isso importa, porque a sobrecarga é justamente quando o limitador está mais quente. [INF]
- Uma configuração de token bucket (`capacity`, `refillRate`) se mapeia em `T = 1/rate`, `tau = T × (capacity − 1)`. Uma configuração de "limite por período" se mapeia em `T = period/limit`, `burst = limit`. Arredondamento: `period/limit` em nanos inteiros deriva < 1 ns por permissão. Acompanhar o resto se a exatidão importar. [INF]
- **Contenção:** toda chamada *admitida* faz CAS em uma palavra. Com taxas muito altas em muitos núcleos, dividir em K sublimitadores de rate/K cada, escolhidos pelo probe da thread, com empréstimo opcional. Isso troca exatidão por escalabilidade, então deve ser opcional. [PROP]

### 3.3 Como o Resilience4j faz [SRC] :id=_33-how-resilience4j-does-it-src

[`AtomicRateLimiter`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/AtomicRateLimiter.java) (o padrão):

- Um algoritmo de **ciclo fixo**: `currentCycle = nanos / limitRefreshPeriod`. As permissões são repostas até `limitForPeriod` (com teto) a cada borda de ciclo, então um cliente pode fazer uma rajada de ~2× o limite em volta de uma borda. [SRC + INF]
- O estado é um **objeto `State` imutável (config, activeCycle, activePermissions, nanosToWait)** em uma `AtomicReference`. **Cada tentativa de CAS aloca um novo `State`**, com sucesso ou falha, inclusive para chamadas rejeitadas.
- O relógio é `Supplier<Long> nanoTimeSupplier` (**com boxing**). `config.getTimeoutDuration().toNanos()` e `getLimitRefreshPeriod().toNanos()` são recalculados em toda chamada.
- O back-off em caso de falha do CAS é `LockSupport.parkNanos(1)`. Para uma thread de plataforma em Linux, o sono mínimo efetivo é limitado pelo timer slack (~50 µs por padrão), muito mais do que custaria uma nova tentativa. [SRC + INF]
- `waitingThreads` é um `AtomicInteger` compartilhado, incrementado e decrementado em volta das esperas.

[`SemaphoreBasedRateLimiter`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/internal/SemaphoreBasedRateLimiter.java):

- `new Semaphore(limit, /* fair */ true)`. Um semáforo AQS justo enfileira em FIFO todo adquirente disputado, o que é muito mais lento que a versão não justa sob contenção. [SRC + INF]
- Uma tarefa `scheduleAtFixedRate` repõe as permissões, usando o **seu próprio scheduler de uma thread por limiter**, a menos que um seja fornecido. Isso é uma thread por limiter nomeado. A reposição (`limit - availablePermits()` e depois `release`) não é atômica em relação a aquisições concorrentes. [SRC + INF]
- `reservePermission` não é suportado. [SRC]

### 3.4 Esperando por uma permissão :id=_34-waiting-for-a-permit

- **Falhar rápido por padrão.** Se uma espera estiver configurada: em virtual threads, `LockSupport.parkNanos(wait)` desmonta a thread e é barato. Em threads de plataforma, bloqueia uma thread do SO, o que é escolha do usuário. [INF]
- Com reserva (reserva GCRA), quem espera conhece seu slot exato, então não há manadas de despertar (thundering herds) nem nova disputa. [PROP]

---

## 4. Bulkhead

### 4.1 Bulkhead por semáforo :id=_41-semaphore-bulkhead

- **Falhar rápido (maxWait = 0), o caso comum** [PROP]:

  ```java
  if ((int) INFLIGHT.getAndAdd(this, 1) >= max) { INFLIGHT.getAndAdd(this, -1); return REJECTED; }
  // ... call ...
  INFLIGHT.getAndAdd(this, -1);   // + signal a waiter only if waiting is enabled and waiters > 0
  ```

  Um `LOCK XADD` para entrar e outro para sair, wait-free. A concorrência nunca passa de `max`. A contagem a mais transitória pode causar uma rejeição espúria bem no limite, o que é aceitável para um bulkhead. [INF]
- **Com espera (`maxWait > 0`):** recorrer ao `java.util.concurrent.Semaphore` (não justo por padrão; justo como opção), ou reutilizar o contador mais uma fila de quem espera. Só esse caminho lento paga os custos do AQS. [PROP]

**`SemaphoreBulkhead` do Resilience4j** ([source](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/internal/SemaphoreBulkhead.java)) [SRC]:
- `tryAcquirePermission()` chama `semaphore.tryAcquire(maxWait.toMillis(), MILLISECONDS)` mesmo quando a espera é 0, e cria uma **lambda com captura** `() -> callPermitted ? new ...PermittedEvent : new ...RejectedEvent` por chamada (uma alocação, a menos que a escape analysis a elimine).
- Cada `releasePermission()`/`onComplete()` chama `grantPendingPermissions()`, que faz `grantsInProgress.getAndIncrement()` (e depois `addAndGet`) em um **segundo atômico compartilhado**, mesmo quando não existem esperas assíncronas. Então são dois a três atômicos disputados por chamada em vez de dois.
- O caminho assíncrono usa uma `ConcurrentLinkedQueue<PendingPermission>` e agenda uma tarefa de timeout por aquisição em espera.
- Rejeição: `BulkheadFullException` com stack trace opcional (configurável). [SRC]

### 4.2 Bulkhead por thread pool vs virtual threads :id=_42-thread-pool-bulkhead-vs-virtual-threads

- O [`FixedThreadPoolBulkhead`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/internal/FixedThreadPoolBulkhead.java) do Resilience4j isola as chamadas em um `ThreadPoolExecutor` limitado e retorna `CompletionStage`. Seus custos são um salto de thread, uma fila e alocações de futures por chamada. [SRC + INF]
- [JEP 444](https://openjdk.org/jeps/444) (virtual threads, final no JDK 21): "virtual threads are not expensive so there is never a need to pool them" e "do not be tempted to pool virtual threads in order to limit concurrency"; em vez disso, use "constructs specifically designed for that purpose, such as semaphores." [DOC]
- Então, em um mundo com Loom, o bulkhead por semáforo é **o** bulkhead. O isolamento por thread pool fica com dois usos: (a) aplicações que ainda rodam código bloqueante em threads de plataforma e precisam manter a thread chamadora livre, e (b) a semântica de "abandonar no timeout", em que o chamador precisa retornar enquanto o trabalho continua em outro lugar. Ambos são de nicho. Entregar mais tarde, como uma implementação separada e opcional. [INF]

### 4.3 Pinning

- JDK 21–23: bloquear dentro de `synchronized` fixa a thread portadora (carrier). **JDK 24+ ([JEP 491](https://openjdk.org/jeps/491))**: os monitores deixam de fixar, e `Object.wait()` desmonta. O pinning continua para frames nativos: código nativo que chama de volta Java que bloqueia, bloquear durante o carregamento de classes ou em **inicializadores de classe**, e esperar pela inicialização de classe de outra thread. [DOC]
- Regras da biblioteca: nada de `synchronized` em volta de algo que possa bloquear. Nada de bloquear em inicializadores estáticos (por ex. não iniciar uma thread ticker e esperar por ela em `<clinit>`). Usar `j.u.c.locks` ou código lock-free. Documentar que o código `synchronized` do próprio usuário fixa no JDK 21–23. [PROP]
- Evitar caches `ThreadLocal` por chamada: com virtual threads, cada chamada costuma rodar em uma thread nova, então o cache vira alocação. O JEP 444 adverte explicitamente contra guardar recursos caros em pools dentro de thread locals. Preferir variáveis locais da pilha, ou `ScopedValue` para propagar contexto (final no JDK 25, [JEP 506](https://openjdk.org/jeps/506); não utilizável em uma base Java 21 sem preview). [DOC][INF]

### 4.4 Concorrência estruturada (situação verificada em 2026-10-23) :id=_44-structured-concurrency-status-checked-2026-10-23

| JDK | JEP | Situação |
|---|---|---|
| 21 | [453](https://openjdk.org/jeps/453) | Preview |
| 22–24 | [462](https://openjdk.org/jeps/462), [480](https://openjdk.org/jeps/480), [499](https://openjdk.org/jeps/499) | 2ª–4ª preview |
| 25 | [505](https://openjdk.org/jeps/505) | 5ª preview: `StructuredTaskScope.open(Joiner, cfg -> cfg.withTimeout(...))` |
| 26 | [525](https://openjdk.org/jeps/525) | 6ª preview (callback de timeout do Joiner) |
| 27 | [533](https://openjdk.org/jeps/533) | 7ª preview (os joiners lançam `ExecutionException`) ([InfoQ](https://www.infoq.com/news/2026/05/jep-533-jdk-27/)) |
| 28 (GA prevista para março de 2027) | [543](https://openjdk.org/jeps/543) | **Final**, "without further change"; situação *Integrated* |

[DOC] Implicação: um jar com base Java 21 **não pode** usar `StructuredTaskScope` (class files de preview ficam presos a uma versão exata do JDK). Implementar timeouts e hedging sobre virtual threads simples agora. Acrescentar mais tarde um adaptador em JAR multi-release ou em módulo separado para o JDK 28+. [INF]

---

## 5. Retry

### 5.1 Estratégias de backoff :id=_51-backoff-strategies

| Estratégia | Fórmula | Comentário |
|---|---|---|
| Fixo | `base` | Tempestades de retries sincronizadas. |
| Exponencial (com teto) | `min(cap, base · m^n)` | Ainda sincronizado. |
| Full jitter | `rand(0, min(cap, base · 2^n))` | O menor trabalho total na simulação da AWS. |
| Equal jitter | `t/2 + rand(0, t/2)` | Mantém uma espera mínima. |
| **Jitter descorrelacionado** | `sleep = min(cap, rand(base, prev · 3))` | Boa dispersão, com estado (precisa de `prev`). |

Fonte: [AWS Architecture Blog — Exponential Backoff And Jitter (Brooker)](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/). [DOC]

Implementação: `ThreadLocalRandom.current().nextLong(lo, hi)` (sem contenção, sem alocação). Calcular as exponenciais por multiplicação repetida ou por um shift com saturação em caso de overflow, não com `Math.pow` a cada tentativa. Manter tudo em nanos `long`, sem `Duration` no caminho quente. [PROP]

### 5.2 Como o Resilience4j faz [SRC] :id=_52-how-resilience4j-does-it-src

[`RetryImpl`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-retry/src/main/java/io/github/resilience4j/retry/internal/RetryImpl.java), [`Retry.decorateCheckedSupplier`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-retry/src/main/java/io/github/resilience4j/retry/Retry.java):
- `retry.context()` → `new ContextImpl()` com `new AtomicInteger` e dois `new AtomicReference`: **quatro alocações por chamada, mesmo quando a primeira tentativa tem sucesso**.
- `onComplete` incrementa `LongAdder`s (tudo bem) e envolve valores em `Optional` nos caminhos de retry.
- `IntervalBiFunction`/`IntervalFunction` recebem um `Long` com boxing; `sleepFunction` é `CheckedConsumer<Long>` (`Thread::sleep`).
- `onResult` avalia o predicado de resultado em toda chamada (esperado), e `Either.right(result)` aloca no retry por resultado.

### 5.3 Proposta [PROP] :id=_53-proposal-prop

- O **caminho sem falhas custa uma verificação de predicado**: contagem de tentativas, última exceção e estado do backoff vivem em variáveis locais do método gerado (ou em um único laço dentro de um auxiliar estático). Não alocar nada até a primeira falha.
- **Dormir:** usar `Thread.sleep(Duration)` ou `LockSupport.parkNanos` na thread chamadora. Em uma virtual thread isso desmonta. O JDK agenda o despertar em um `ScheduledThreadPoolExecutor` interno. O JDK 21 tem um **único** `UNPARKER`; o JDK 25 tem um **array** de schedulers de tarefas atrasadas (`jdk.virtualThreadScheduler.timerQueues`) para reduzir a contenção. [SRC: `VirtualThread.java` em `jdk-21+35` / `jdk-25+36`] Para APIs assíncronas, usar `CompletableFuture.delayedExecutor` ou a nossa própria roda de timers (§6.3).
- **Orçamentos de retry** (evitam tempestades de retries; um diferencial, já que o Resilience4j não tem nenhum [INF]):
  - Google SRE: um limite por requisição (≤ 3 tentativas) mais uma **proporção de retries por cliente < 10%**. [DOC] ([livro de SRE — Handling Overload](https://sre.google/sre-book/handling-overload/))
  - Throttling de retries do gRPC: contagem de tokens por destino começando em `maxTokens`. Cada falha −1, cada sucesso +`tokenRatio`. Os retries só são permitidos enquanto tokens > `maxTokens/2`. [DOC] ([gRPC A6](https://github.com/grpc/proposal/blob/master/A6-client-retries.md))
  - Implementação: um `long` em mili-tokens de ponto fixo, atualizado com `getAndAdd` (sucesso/falha) e uma leitura (decisão de retry). Sem alocação, um atômico por chamada. Compartilhar o orçamento entre todas as políticas de retry que miram a mesma dependência. [PROP]
- Os retries interagem com circuit breakers e rate limiters. A ordem e os orçamentos devem ser explícitos no pipeline (veja o documento irmão 04). [INF]

---

## 6. Timeout / time limiter

### 6.1 O problema fundamental :id=_61-the-fundamental-problem

Java não consegue interromper preemptivamente uma computação em execução. Um timeout só pode (a) parar de *esperar* por um resultado que roda em outro lugar, ou (b) *interromper* a thread que o executa, o que só ajuda se o código chegar a um ponto interrompível. [INF]

### 6.2 Opções :id=_62-options

| Opção | Salto de thread | Custo por chamada | Cancela o trabalho? |
|---|---|---|---|
| Executar em um executor, `future.get(timeout)` (`TimeLimiter` síncrono do Resilience4j) | Sim | Tarefa + future + fila; troca de contexto | Só via `cancel(true)` → interrupção |
| Executar em uma **nova virtual thread**, join com timeout | Sim (barato) | Criação + início + join da VT (~µs) [INF] | Interrupção |
| `StructuredTaskScope` + `withTimeout` | Sim | Scope + subtarefa | Interrupção (cancela o scope) — preview até o JDK 28 |
| `CompletableFuture.orTimeout` (assíncrono) | Não (para trabalho que já é assíncrono) | `Timeout` + `Canceller` + tarefa agendada | Não (completa excepcionalmente) |
| **Watchdog na thread chamadora** [PROP] | **Não** | Uma inserção + um cancelamento no timer | Interrupção do chamador |

- **Resilience4j** [SRC] ([`TimeLimiterImpl`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-timelimiter/src/main/java/io/github/resilience4j/timelimiter/internal/TimeLimiterImpl.java)): o caminho síncrono é `decorateFutureSupplier(Supplier<Future>)`, então **o usuário precisa fornecer uma thread** e a chamada faz um `future.get(timeout.toMillis(), MILLISECONDS)`. O caminho assíncrono usa um timeout em `ScheduledExecutorService` por chamada e `whenComplete` (alocações: a tarefa agendada, a lambda de conclusão, o future dependente). No timeout, cria uma `TimeoutException` com nome.
- **`CompletableFuture.orTimeout`** [SRC]: o JDK 21 agenda em uma única thread daemon estática (`CompletableFutureDelayScheduler`, um `ScheduledThreadPoolExecutor`). No JDK 25 ele é reescrito sobre o novo `java.util.concurrent.DelayScheduler` usado pelo `ForkJoinPool`. Uma inserção/cancelamento em um `ScheduledThreadPoolExecutor` é O(log n) em um heap protegido por lock, o que vira um ponto de contenção com taxas de chamada altas. [INF]
- **Interrompibilidade com virtual threads:** no JDK 21, interromper uma virtual thread bloqueada em connect/read/write de um `Socket` "will cause it to wakeup and close the socket", e o método lança `SocketException`. [SRC: Javadoc de `java/net/Socket.java`, `jdk-21+35`] Então timeouts baseados em interrupção são eficazes para I/O bloqueante em virtual threads, o que beneficia os alvos de timeout mais comuns (HTTP/JDBC sobre sockets). Em threads de plataforma, o I/O clássico de `Socket` não é interrompível. [INF]

### 6.3 Design do watchdog [PROP] :id=_63-watchdog-design-prop

- A chamada fica na thread do chamador (sem salto, preservando thread-locals, MDC e transações). Antes da chamada, registrar `(thread, deadline)` em um timer. Depois da chamada, cancelar.
- **Protocolo da corrida:** um estado por chamada `RUNNING → DONE` (CAS do chamador) ou `RUNNING → INTERRUPTING → INTERRUPTED` (CAS do timer, depois `thread.interrupt()`, depois um store de release). Se o chamador perde o CAS, ele espera (girando brevemente) até `INTERRUPTED` e então limpa a flag de interrupção com `Thread.interrupted()` antes de lançar `TimeoutException`. Isso garante que nenhuma interrupção perdida vaze para trabalho posterior sem relação. Verificar o modelo com jcstress.
- **Estrutura do timer:** a maioria dos timeouts é **cancelada** (a chamada termina primeiro), então o cancelamento deve ser O(1) e barato.
  - Um hashed wheel timer (`HashedWheelTimer` do Netty: tick padrão de 100 ms, 512 buckets, filas MPSC para timeouts novos e cancelados, uma thread trabalhadora). [SRC] ([source](https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/HashedWheelTimer.java))
  - Uma roda de timers hierárquica (Varghese & Lauck 1987; também usada no Kafka e no `TimerWheel` do Caffeine) lida com uma ampla faixa de timeouts com buckets limitados. [DOC] ([Varghese & Lauck, *Hashed and Hierarchical Timing Wheels*](https://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf))
  - **Variante "varredura de slots", ainda mais barata:** cada chamada temporizada em andamento reivindica um slot em um array pré-alocado (índice de lista livre), escreve `deadline` e `thread` e o limpa após a chamada. Uma thread watchdog varre os slots ocupados a cada tick (por ex. 1 ms). Inserir e cancelar são stores simples mais um pop/push na lista livre; a varredura é O(em andamento). Melhor quando a concorrência é limitada (e um bulkhead normalmente a limita). Comparar com a roda em benchmark.
- A thread watchdog deve ser uma thread daemon de plataforma (não pode depender de o scheduler de virtual threads estar livre), iniciada de forma preguiçosa, mas **não** dentro de `<clinit>` (pinning, §4.3).
- **APIs assíncronas:** para métodos `CompletionStage`, usar uma semântica parecida com `orTimeout` sobre a nossa própria roda em vez do agendador de atrasos do JDK, para compartilhar um único timer em toda a biblioteca.

---

## 7. Questões transversais :id=_7-cross-cutting-concerns

### 7.1 Eventos e listeners :id=_71-events-and-listeners

- **Regra:** zero alocação e nenhum dispatch virtual quando ninguém escuta. Um sentinela `static final Listener[] NONE`, mais `if (listeners != NONE)`, onde `listeners` é um campo `final` definido em tempo de build. Se os listeners forem conhecidos em tempo de build, o código gerado pode omitir a chamada por completo. [PROP]
- **Callbacks primitivos em vez de objetos de evento:** `interface CircuitBreakerListener { default void onSuccess(long nanos) {} default void onRejected() {} default void onStateChange(int from, int to) {} }`. Nenhuma alocação, mesmo *com* listeners. Adaptadores para objetos de evento (para logging ou Micrometer) alocam apenas dentro do adaptador. [PROP]
- **Ordem dos eventos e exceções:** um listener que lança não deve quebrar a chamada. Capturar e contar em um `LongAdder` de "falhas de listener". [PROP]
- **Resilience4j** [SRC]: o `EventProcessor` usa um `CopyOnWriteArraySet` por nome de classe de evento, buscado com `event.getClass().getName()` (uma busca em `ConcurrentHashMap` por evento publicado). A guarda existe, mas vários call sites alocam antes de verificar (§2.1). Bulkhead/retry passam lambdas `Supplier` com captura.

### 7.2 Métricas :id=_72-metrics

- Contadores: `LongAdder` (ou longs distribuídos em faixas com padding) incrementados no caminho quente; exposição **sob demanda (pull)** (um gauge que lê `sum()` no momento da coleta). Nenhuma chamada ao Micrometer ou a um registry no caminho quente. [PROP]
- Histogramas de latência (só se habilitados): buckets log-lineares fixos (no estilo HdrHistogram) em um `AtomicLongArray` ou em arrays distribuídos em faixas. Registrar = `numberOfLeadingZeros` + índice no array + `getAndAdd`. Necessários para os atrasos do hedging (p95) e para os limites adaptativos. [PROP] ([HdrHistogram](https://github.com/HdrHistogram/HdrHistogram))

### 7.3 Exceções :id=_73-exceptions

- O custo de construir um `Throwable` é dominado por `fillInStackTrace`, que é proporcional à profundidade da pilha e costuma ficar na casa dos microssegundos. O construtor `(message, cause, enableSuppression=false, writableStackTrace=false)` o evita. [DOC][INF] ([construtor de Throwable](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html#%3Cinit%3E(java.lang.String,java.lang.Throwable,boolean,boolean)))
- O Resilience4j usa esse construtor para `CallNotPermittedException`, `BulkheadFullException` e `RequestNotPermitted` (stack trace configurável, gravável por padrão). Mas monta a mensagem com `String.format` (circuit breaker) ou construção de strings **em toda rejeição**. [SRC]
- **Proposta:** as exceções de rejeição são **pré-alocadas por instância de política**, sem stack trace e com supressão desativada. A mensagem é calculada uma vez ("CircuitBreaker 'x' is OPEN"). O estado é lido de um campo no momento de `getMessage()`, se necessário. Um modo de depuração (flag de configuração) cria exceções novas com stack traces. Além disso, oferecer uma API que não lança (`Result`/resultado sealed ou um `tryCall` que retorna um status) para chamadores que tratam a rejeição como fluxo normal. [PROP]
- **Nunca** envolver exceções do usuário no caminho quente (nada de envolver em `CompletionException`/`ExecutionException` no caminho síncrono). Relançar exceções checadas com um "sneaky throw" no código gerado, preservando a assinatura declarada. [PROP]

### 7.4 Composição sem lambdas aninhadas nem sites megamórficos :id=_74-composition-without-nested-lambdas-or-megamorphic-sites

**Problema.** `Decorators.ofSupplier(s).withRetry(r).withCircuitBreaker(cb).withBulkhead(b).decorate()` monta uma cadeia de lambdas. O corpo de cada lambda de `CircuitBreaker.decorateCheckedSupplier` é **um único local de bytecode para toda a aplicação** [SRC: `CircuitBreaker.java`]. Seu call site `supplier.get()` vê todas as classes de supplier decoradas da aplicação, então fica megamórfico a partir do terceiro tipo distinto. Sites megamórficos são compilados como chamadas por itable/vtable, o que bloqueia o inlining, então a escape analysis não consegue eliminar as alocações de `Long`, `Either`, `Snapshot` e eventos. Aplicações reais têm muitos métodos decorados, então microbenchmarks com um único supplier decorado são *irrealisticamente* rápidos para o Resilience4j. [INF; validar com `-XX:+PrintInlining` / JITWatch]

**Proposta** [PROP]:
1. **Protocolo acquire/release, não envolvimento.** Cada política expõe operações primitivas, sem lambdas: `long cb.acquire(now)`, `cb.onSuccess(token, nanos)`, `cb.onFailure(token, nanos, Throwable)`, `boolean bh.tryEnter()` e `bh.exit()`, `long rl.tryAcquire(now)`, e assim por diante.
2. **Código gerado em linha reta por método anotado** (qualquer que seja o mecanismo escolhido pela pesquisa irmã: um processador de anotações que gera subclasses ou wrappers). Cada método recebe seu próprio bytecode, então cada call site é **monomórfico** (classes de política `final`, invocadas por campos constantes `static final` ou por campos de instância final). Forma:

   ```java
   // Generated, illustrative.
   long t0 = TICKER.read();
   if (!BH.tryEnter()) throw BH.rejected();
   try {
     long tok = CB.acquire(t0);          // throws a preallocated rejection if OPEN
     try {
       R r = target.call(args);          // direct invokevirtual on the real class
       long t1 = TICKER.read();
       CB.onSuccess(tok, t1 - t0);
       return r;
     } catch (Throwable e) { CB.onFailure(tok, TICKER.read() - t0, e); throw e; }
   } finally { BH.exit(); }
   ```

3. **Retry como um laço** em volta do bloco interno, no mesmo método gerado. Sem lambda, sem objeto de contexto.
4. **Alternativa para uso programático (sem anotações):** uma única classe `Pipeline` com ordem fixa e campos `final` por tipo de política (null = ausente), executando a mesma sequência acquire/release através de desvios `if (cb != null)`. É uma única classe cujos call sites veem, cada um, uma única classe concreta de política (as políticas são classes `final`), então continuam monomórficos. O `Callable.call()` do usuário continua sendo um site megamórfico, o que é inevitável sem geração. [INF]

### 7.5 Leituras de relógio compartilhadas :id=_75-shared-clock-reads

Em uma pilha de N políticas temporizadas, o Resilience4j lê o relógio por política (o circuit breaker lê duas vezes, mais o rate limiter, o time limiter, e assim por diante). Um pipeline gerado lê `t0` uma vez antes e `t1` uma vez depois, e passa as durações para todos. [PROP]

---

## 8. Funcionalidades diferenciais :id=_8-differentiating-features

### 8.1 Hedging

- Enviar uma segunda requisição se a primeira não tiver terminado após um atraso (tipicamente ~a latência p95), ficar com o primeiro sucesso e cancelar o resto. Em *The Tail at Scale*, adiar o hedge até a latência esperada do percentil 95 limita a carga extra a cerca de 5% e reduz substancialmente a cauda. [DOC] ([Dean & Barroso, CACM 2013](https://research.google/pubs/the-tail-at-scale/))
- Hedging no gRPC: `maxAttempts` (≤ 5), `hedgingDelay`, `nonFatalStatusCodes`; os hedges obedecem ao throttling de retries. [DOC] ([gRPC A6](https://github.com/grpc/proposal/blob/master/A6-client-retries.md))
- Implementação com Loom: a primeira tentativa roda na thread chamadora; o hedge inicia uma virtual thread quando o timer (§6.3) dispara; a primeira conclusão vence por um CAS em um slot de resultado; as perdedoras são interrompidas. O hedging exige idempotência (um atributo explícito da anotação) e deve consumir o orçamento de retries. O atraso vem de um histograma de latência ao vivo (§7.2) ou é fixo. [PROP]
- O Resilience4j tem um módulo `hedge` mais recente (veja o documento 01). Comparar a semântica ali. [INF]

### 8.2 Limites de concorrência adaptativos :id=_82-adaptive-concurrency-limits

- [Netflix concurrency-limits](https://github.com/Netflix/concurrency-limits): limite ≈ RPS × latência (lei de Little). Algoritmos: **Vegas** (estimativa de fila `L·(1 − minRTT/sampleRTT)`, ±1 contra os limiares α/β), **Gradient2** (divergência entre as EWMA de latência de longo e de curto prazo), **AIMD** (baseado em perdas). Limitadores: `SimpleLimiter`, particionado (fatia por prioridade), bloqueante. [DOC]
- O filtro de concorrência adaptativa do Envoy usa um controlador de gradiente com nova medição periódica do minRTT. [DOC] ([documentação do Envoy](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/adaptive_concurrency_filter))
- **Design do caminho quente** [PROP]: contador de chamadas em andamento (como no bulkhead, §4.1) contra um `volatile int limit`; ao concluir, somar o RTT a um acumulador por janela (soma + contagem em `LongAdder`; min-RTT via CAS-min em um `long`). A **thread que cruza a borda da janela** (CAS na época da janela) recalcula `limit`. Todas as outras pagam só `getAndAdd` ×2 mais um `LongAdder.add`. Sem thread em segundo plano.
- Este é um diferencial claro: um "bulkhead adaptativo" que não precisa de ajuste. [INF]

### 8.3 Descarte de carga (load shedding) :id=_83-load-shedding

- Throttling adaptativo do lado do cliente (Google SRE): rejeitar localmente com probabilidade `max(0, (requests − K·accepts) / (requests + 1))`, com K ≈ 2. [DOC (o K e as definições são citados na página; a fórmula em si aparece como imagem na [página do livro de SRE](https://sre.google/sre-book/handling-overload/))]. Implementação: dois contadores de janela deslizante (anel por tempo do §2.2) mais `ThreadLocalRandom`. [PROP]
- Disciplina de fila do lado do servidor para os chamadores em espera: no estilo CoDel ("se a fila não esteve vazia por X ms, descartar") e **LIFO adaptativo** sob sobrecarga, como descrito pelo Facebook. [DOC] ([Maurer, *Fail at Scale*, ACM Queue 2015](https://doi.org/10.1145/2814330); um precedente em Java é o [`AdaptiveLifoCoDelCallQueue`](https://hbase.apache.org/2.5/devapidocs/src-html/org/apache/hadoop/hbase/ipc/AdaptiveLifoCoDelCallQueue.html) do HBase)
- Partições por prioridade (fatias críticas vs de lote de um limite), como no limitador particionado da Netflix. [DOC]

---

## 9. Metodologia de benchmark (JMH vs Resilience4j) :id=_9-benchmark-methodology-jmh-vs-resilience4j

### 9.1 Regras básicas :id=_91-ground-rules

- Apenas [JMH](https://github.com/openjdk/jmh). Forks ≥ 3, aquecimento ≥ 5×1 s, medição ≥ 5×1 s. `-Xms=-Xmx` fixos, mais `-XX:+AlwaysPreTouch`. Rodar no **JDK 21 e no JDK 25** (o timer das virtual threads e o agendador de atrasos do `CompletableFuture` mudaram, §5.3/§6.2), em **x86-64 e AArch64** (os atômicos se comportam de forma diferente). Fixar a frequência da CPU e desligar o turbo quando possível. Publicar o JSON do JMH e o hardware exato. [PROP]
- Comparar com a **última versão do Resilience4j (2.4.0)** com configuração *equivalente*: mesmo tipo e tamanho de janela, as duas estratégias `SYNCHRONIZED` e `LOCK_FREE`, `writableStackTraceEnabled=false` nos benchmarks de rejeição (justo com eles) e eventos com e sem consumidores. Reutilizar os próprios cenários JMH deles (por ex. [`CircuitBreakerBenchmark`](https://github.com/resilience4j/resilience4j/tree/master/resilience4j-circuitbreaker/src/jmh/java/io/github/resilience4j/circuitbreaker)) para que ninguém possa alegar escolha a dedo. [PROP]
- **Referência**: a chamada não decorada, para podermos reportar o *overhead* = política − referência. [PROP]
- **Poluição de perfil (justiça para as duas bibliotecas):** antes de medir, decorar e exercitar ≥ 3–4 classes diferentes de supplier/alvo na mesma JVM, para que os call sites compartilhados fiquem megamórficos como em aplicações reais. Reportar os números "limpos" e os "poluídos". [PROP]
- Profilers: `-prof gc` (`gc.alloc.rate.norm` B/op), `-prof perfnorm` (instruções e cache misses por op), `-prof perfasm` ou async-profiler para os pontos quentes, JFR `jdk.VirtualThreadPinned` nas execuções com virtual threads. Usar `Blackhole.consumeCPU(n)` para simular trabalho. [DOC][PROP]
- Correção: [jcstress](https://github.com/openjdk/jcstress) para toda estrutura lock-free (contadores da janela, CAS de estado, GCRA, protocolo do watchdog). Um circuit breaker rápido, mas errado, é um bug. [PROP]

### 9.2 Cenários :id=_92-scenarios

| # | Cenário | Modos | Threads | O que mostra |
|---|---|---|---|---|
| 1 | Caminho feliz por política (CB fechado, RL abaixo do limite, BH abaixo do limite, retry com sucesso na primeira, TL rápido) | `AverageTime`, ns/op | 1 | Overhead puro, B/op |
| 2 | O mesmo, instância compartilhada | `Throughput` | 1, 2, 4, …, 2× núcleos | Escalabilidade sob contenção (false sharing, tempestades de CAS, comboios de lock) |
| 3 | Caminho de rejeição (CB aberto, RL esgotado, BH cheio) | `Throughput` + `-prof gc` | 1 e máx. | Custos de exceções e eventos sob sobrecarga (quando mais importa) |
| 4 | Resultados mistos (1%, 10%, 50% de falhas, chamadas lentas) | `Throughput` | máx. | Avaliação de limiares e atualizações da janela |
| 5 | CB oscilando (open/half-open/close frequentes) | `Throughput` | máx. | Correção/custo das transições e sondagens |
| 6 | Retry com falhas, backoff zero | `AverageTime` | 1 | Custo da maquinaria de retry por tentativa |
| 7 | Pilha completa Retry→CB→RL→BH→TL vs `Decorators` | `AverageTime`, `Throughput` | 1, máx. | Overhead de composição (§7.4) |
| 8 | Timeout: agendar+cancelar por chamada vs `orTimeout` vs `TimeLimiter` da R4j | `Throughput` | 1, máx. | Custo da estrutura de dados do timer |
| 9 | 10 mil virtual threads passando por BH/RL com espera | `SampleTime` (p50/p99/p999) | VTs | Justiça, latência de cauda, pinning |
| 10 | Pegada de memória: 10 mil instâncias nomeadas | JOL / histograma do heap | — | Custo de memória do padding e do estado |
| 11 | Perfil do JIT poluído vs limpo (§9.1) | como #1/#7 | 1 | Números realistas vs melhor caso |

### 9.3 Resultado esperado (hipóteses a confirmar) :id=_93-expected-outcome-hypotheses-to-confirm

- Caminho feliz em uma thread: a vantagem vem da eliminação de alocações e de menos leituras de relógio. Esperar um ganho modesto (dezenas de ns/op) em perfis "limpos" e um maior nos "poluídos". [INF]
- Throughput sob contenção: os maiores ganhos são esperados no circuit breaker (lock vs wait-free) e no rate limiter (alocação por tentativa de CAS e back-off com `parkNanos` vs um CAS simples). [INF]
- Caminho de rejeição: exceções pré-alocadas sem stack trace mais nenhum evento ansioso devem ser ordens de grandeza mais baratos que um `String.format` por rejeição. [INF]
- Não devemos afirmar nenhum número antes de medir. Tudo o que está acima deve ser confirmado na fase 2. [PROP]

---

## 10. Recomendações por política (resumo) :id=_10-recommendations-by-policy-summary

1. **Circuit breaker:** uma palavra de estado em `VarHandle` mais tokens de geração. Janela por contagem wait-free (`getAndSet` no slot + contadores) e buckets de tempo marcados por época. Pular a avaliação nos sucessos rápidos depois do aquecimento. Nenhuma medição de tempo, a menos que necessária. Rejeições pré-alocadas.
2. **Rate limiter:** GCRA (um `long`, CAS). Um caminho de rejeição somente leitura. As configurações de token bucket e por período se mapeiam nele. Divisão (sharding) opcional.
3. **Bulkhead:** um contador `getAndAdd` que falha rápido. Semáforo só para espera. Isolamento por thread pool mais tarde e opcional. Um limite adaptativo como política irmã.
4. **Retry:** só variáveis locais, alocar em caso de falha. Jitter descorrelacionado ou full jitter com `ThreadLocalRandom`. Um orçamento de retries baseado em tokens, compartilhado por dependência.
5. **Timeout:** um watchdog na thread chamadora com um protocolo de interrupção estrito. Um timer de roda hashed ou de varredura de slots com uma única thread daemon de plataforma. O assíncrono usa o mesmo timer.
6. **Diferenciais:** hedging sobre virtual threads com um atraso guiado por histograma; limites adaptativos Vegas/Gradient; throttling adaptativo do SRE; orçamentos de retry.
7. **Transversal:** callbacks de listener primitivos; métricas sob demanda (pull); **código acquire/release monomórfico gerado**; um par de leituras de relógio por pipeline; padding das palavras muito escritas; `0 B/op` como portão de CI.

---

## Fontes :id=sources

- Código-fonte do Resilience4j, `master` @ `b7c0069`: <https://github.com/resilience4j/resilience4j> (arquivos com links no texto). PR da janela lock-free: <https://github.com/resilience4j/resilience4j/pull/2241>. Versões: <https://github.com/resilience4j/resilience4j/releases>
- OpenJDK `VirtualThread.java`: <https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/VirtualThread.java>, <https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/lang/VirtualThread.java>
- OpenJDK `CompletableFuture.java` (21 vs 25): <https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java>, <https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java>
- OpenJDK `Socket.java` (semântica de interrupção com virtual threads): <https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/net/Socket.java>
- JEPs: [444](https://openjdk.org/jeps/444), [491](https://openjdk.org/jeps/491), [505](https://openjdk.org/jeps/505), [506](https://openjdk.org/jeps/506), [525](https://openjdk.org/jeps/525), [533](https://openjdk.org/jeps/533), [543](https://openjdk.org/jeps/543), [142](https://openjdk.org/jeps/142); [JDK-8234863](https://bugs.openjdk.org/browse/JDK-8234863)
- InfoQ sobre o JEP 533 (JDK 27): <https://www.infoq.com/news/2026/05/jep-533-jdk-27/>; sobre o JEP 543 (JDK 28): <https://www.infoq.com/news/2026/10/jep-543-jdk-28/>
- A. Shipilëv, *Nanotrusting the Nanotime*: <https://shipilev.net/blog/2014/nanotrusting-nanotime/>
- M. Brooker, *Exponential Backoff And Jitter*: <https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/>
- Livro de SRE do Google, *Handling Overload*: <https://sre.google/sre-book/handling-overload/>
- gRPC A6, retries e hedging no cliente: <https://github.com/grpc/proposal/blob/master/A6-client-retries.md>
- Dean & Barroso, *The Tail at Scale*: <https://research.google/pubs/the-tail-at-scale/>
- Netflix concurrency-limits: <https://github.com/Netflix/concurrency-limits>
- Concorrência adaptativa do Envoy: <https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/adaptive_concurrency_filter>
- B. Maurer, *Fail at Scale*, ACM Queue (2015): <https://doi.org/10.1145/2814330>; `AdaptiveLifoCoDelCallQueue` do HBase: <https://hbase.apache.org/2.5/devapidocs/src-html/org/apache/hadoop/hbase/ipc/AdaptiveLifoCoDelCallQueue.html>
- GCRA: <https://en.wikipedia.org/wiki/Generic_cell_rate_algorithm>, <https://brandur.org/rate-limiting>
- Varghese & Lauck, rodas de timers: <https://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf>
- `HashedWheelTimer` do Netty: <https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/HashedWheelTimer.java>
- JMH: <https://github.com/openjdk/jmh>; jcstress: <https://github.com/openjdk/jcstress>; HdrHistogram: <https://github.com/HdrHistogram/HdrHistogram>
