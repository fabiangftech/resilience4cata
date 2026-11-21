# Perguntas frequentes

Respostas curtas às perguntas que as pessoas fazem primeiro, com links para os detalhes.

## Geral :id=general

### O que é o resilience4cata? :id=what

Uma biblioteca de resiliência baseada em anotações para Java 21+ puro: retry, hedging, circuit breaker, rate limiter, limite de concorrência adaptativo, timeout, bulkhead e fallback. Um processador de anotações transforma as anotações em código-fonte Java comum em tempo de compilação, então não há proxies, nem geração de bytecode, nem agents em tempo de execução. Veja [Como funciona](start/how-it-works.md).

### Quais versões de Java são suportadas? :id=java

Java 21 ou posterior, em tempo de compilação e em tempo de execução. A biblioteca é compilada com `--release 21` e testada em Java 21 e 25.

### Funciona com Spring, Quarkus, Micronaut ou Jakarta CDI? :id=frameworks

| Framework | Integração |
|---|---|
| Spring Boot 4.1+ | `resilience4cata-spring` (desde a 0.4.0): classes anotadas são instanciadas como sua subclasse gerada, beans de interfaces anotadas são envolvidos, o registry é configurado a partir de `application.yml`, mais health, um endpoint do actuator e métricas. Sem AOP. [Guia do Spring Boot](guides/spring.md) |
| Spring Boot 3 | não suportado |
| Micronaut 5.2+ (Java 25) | `resilience4cata-micronaut` (desde a 0.5.0): beans de interfaces anotadas marcados com `@ResilientBean` são envolvidos, classes anotadas compiladas com `-Ar4c.inject=micronaut` são criadas como sua subclasse gerada, o registry é configurado a partir de `application.yml`, mais health, um endpoint de gerenciamento e métricas. Sem AOP. [Guia do Micronaut](guides/micronaut.md) |
| Micronaut 4 | não suportado |
| Quarkus 3.40+ | `resilience4cata-quarkus` (desde a 0.5.0), uma extensão Quarkus: classes anotadas viram sua subclasse gerada como bean, beans de interfaces anotadas e REST clients são injetados através do seu wrapper, o registry é configurado a partir de `application.properties`, mais um readiness check, métricas e verificações em tempo de build (incluindo um conflito com SmallRye Fault Tolerance). Sem interceptors. [Guia do Quarkus](guides/quarkus.md), com [SmallRye Fault Tolerance vs resilience4cata](guides/quarkus.md#smallrye) |
| Jakarta CDI | nenhuma ainda, e nenhuma é necessária: as classes geradas são Java simples, então crie o objeto você mesmo (`Resilient.of(…)` ou `new C$Resilient(registry, …)`) e registre-o como bean do jeito que o seu framework faz. Os construtores gerados das classes anotadas copiam `@Inject` e os qualifiers, então um container também pode instanciar `C$Resilient` diretamente |

Em nenhum caso há proxy em tempo de execução, AOP ou varredura de anotações em tempo de execução.

### É um fork do Resilience4j? :id=fork

Não. Cobre políticas parecidas com um design diferente: código gerado em tempo de compilação em vez de decorators, um registry para todas as políticas, configuração resolvida uma vez. Seus benchmarks comparam com o Resilience4j 2.4.0. Alguns padrões são diferentes e pegam as pessoas de surpresa: um retry faz backoff exponencial com jitter a partir de 200 ms, um rate limiter rejeita na hora, e um `Error` não é retentado ([os padrões](migrate/from-resilience4j.md#config-mapping), [as diferenças](migrate/from-resilience4j.md#differences)). Veja [Migrar do Resilience4j](migrate/from-resilience4j.md).

### Do que ele depende? :id=dependencies

`core` e `annotations` dependem apenas do JDK, mais as anotações [JSpecify](https://jspecify.dev/) (`org.jspecify:jspecify`) para o `core`. O processador fica só no processor path, e as anotações são `compileOnly`: nada mais acaba no seu class path de execução.

## Uso :id=using

### Tenho que usar o processador de anotações? :id=processor

Sim, com anotações: o processador é obrigatório e não há alternativa com reflection. Se ele não rodou, `Resilient.of` falha com [R4C-RT-003](errors.md#r4c-rt-003), cuja mensagem mostra a dependência a adicionar. Nas integrações com frameworks, o mesmo erro é reportado na inicialização: [R4C-SPR-006](errors.md#r4c-spr-006) no Spring Boot, [R4C-MN-007](errors.md#r4c-mn-007) para uma classe anotada no Micronaut, e [R4C-QK-008](errors.md#r4c-qk-008) no Quarkus, em tempo de build. Sem anotações, a API [`Pipeline`](guides/pipeline.md) não precisa de processador.

### Interface ou classe anotada? :id=style

Anote uma **interface** quando ela é sua e a implementação pode ser qualquer coisa (um cliente HTTP, uma lambda, um stub de teste). Anote uma **classe** quando não há interface, ou quando as chamadas que a classe faz a si mesma também precisam passar pelas políticas. Veja [Classes anotadas](guides/annotated-classes.md).

### Posso mudar a ordem das políticas? :id=order

Sim, com `@PolicyOrder` em um método, um tipo ou um bundle. Por padrão é Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method, seja qual for a ordem das anotações, então cada tentativa de um retry é registrada pelo breaker e tem seu próprio timeout, e um breaker aberto interrompe os retries. `@PolicyOrder({TIMEOUT, RETRY})`, por exemplo, coloca um único timeout em volta de todo o loop do retry. O fallback é sempre o mais externo, e a ordem é fixada em tempo de compilação. Veja [Mudar a ordem](policies/README.md#changing-the-order).

### Por que meu método não tem retry quando a implementação o chama? :id=self-invocation

No estilo de interface, as políticas vivem no wrapper gerado; uma chamada da implementação a si mesma não passa por ele. Veja [Solução de problemas](guides/troubleshooting.md#self-invocation).

### Suporta tipos reativos (`Mono`, `Flux`, `Single`)? :id=reactive

`Mono` e `Flux` do Reactor, sim, com `resilience4cata-reactor`: cada política se aplica uma vez por assinatura, com o contexto do subscriber, e é cancelada junto com a assinatura; em um `Flux`, o retry e o fallback agem apenas antes do primeiro elemento, e o timeout recomeça a cada elemento. Veja [Project Reactor](guides/reactor.md). Outros tipos reativos (`Uni` e `Multi` do Mutiny, os tipos do RxJava) ainda não são suportados: com anotações de resiliência eles são um erro de compilação, [R4C-ASY-002](errors.md#r4c-asy-002), porque as políticas protegeriam apenas a construção do pipeline, não o trabalho. Converta para um `CompletionStage`, um `Mono` ou um `Flux` na implementação. Veja [Métodos async](guides/async.md).

### Funciona com virtual threads? :id=virtual-threads

Sim. Os caminhos de chamada não pegam locks nem usam `synchronized`, então não prendem (pin) uma virtual thread à sua carrier. Os callbacks atrasados da própria biblioteca (retries async, timeouts async) rodam em virtual threads novas.

As políticas síncronas são feitas para uma thread por requisição: um timeout interrompe uma virtual thread bloqueada e limpa essa interrupção antes de lançar a exceção (uma interrupção que a thread já tinha é mantida), a espera de um bulkhead ou de um rate limiter e a pausa de um retry estacionam a virtual thread e liberam sua carrier, e o controle interno do timeout para uma thread desaparece com a thread. A suíte de testes executa milhares de virtual threads através de cada política bloqueante enquanto uma gravação JFR verifica que nenhuma fica presa, e executa os testes de carga dos frameworks com requisições em virtual threads: Spring Boot com `spring.threads.virtual.enabled=true`, o executor bloqueante do Micronaut (virtual threads em Java 21 e mais recentes) e endpoints Quarkus com `@RunOnVirtualThread`.

### Posso mudar uma configuração em tempo de execução? :id=reload

Não, por design. Anotações, configs nomeadas e configuração externa são resolvidas uma vez, quando cada instância é criada. Para reajustar em produção, mude a configuração externa (por exemplo `instances.<id>.*`) e reinicie. Para forçar o estado de um breaker, use seu handle: `registry.circuitBreaker(id).forceOpen()`, `disable()`, `reset()`.

### Como configuro a partir de um arquivo ou de variáveis de ambiente? :id=external-config

Adicione `resilience4cata-config` e construa o registry a partir de `ResilienceConfig.load()`. As chaves são como `defaults.retry.maxAttempts=5` ou `R4C_DEFAULTS_RETRY_MAXATTEMPTS=5`. Veja [Configuração](guides/configuration.md) e a [referência de chaves](reference/configuration.md).

### O que é um id de instância? :id=instance-id

Cada método anotado recebe suas próprias instâncias de política, com o id `<type>#<method>(<parameter types>)`, por exemplo `com.acme.CatalogClient#price(java.lang.String,int)`. Um atributo `name` o substitui, e métodos com o mesmo `name` compartilham uma instância. Os ids são usados por handles, eventos, snapshots e chaves `instances.<id>.*`.

## Operação :id=operations

### Como obtenho métricas? :id=metrics

Com o [módulo Micrometer](guides/micrometer.md) (`resilience4cata-micrometer`): três linhas de configuração dão a você contadores a partir de eventos e gauges para cada circuit breaker, bulkhead e rate limiter. Para outra biblioteca de métricas, construa o mesmo sobre [eventos e handles](guides/observability.md#diy).

### Ele conta chamadas bem-sucedidas ou mede latência? :id=success-metrics

Não. Chamadas bem-sucedidas não publicam evento nem atualizam contador: um contador em cada chamada seria uma escrita em memória compartilhada no hot path. Meça a contagem de chamadas e a latência no seu próprio código ou com as métricas do seu framework.

### Como testo código que espera segundos entre retries? :id=testing

Com `resilience4cata-testkit`: um `ManualClock` faz sleeps, esperas e timeouts seguirem um relógio que o teste avança, então atrasos de segundos não levam tempo real. Veja [Testes](guides/testing.md).

### Por que meu timeout não interrompe a chamada? :id=timeout

Um timeout síncrono interrompe a thread de quem chama; código que ignora interrupções (I/O de socket bloqueante clássico, loops ocupados) roda até retornar. Defina também os timeouts de I/O do próprio cliente. Veja [Solução de problemas](guides/troubleshooting.md#timeout-not-interrupting).

### Quão rápido ele é? :id=performance

No AWS Graviton3, um retry cuja primeira tentativa tem sucesso custa 1.4 ns e um circuit breaker 1.9 ns por chamada, com 0 bytes alocados, contra 22.9 ns e 89.9 ns do Resilience4j 2.4.0 ([medido na release candidate](benchmarks.md#release-candidate)). Os 1.9 ns do breaker valem enquanto sua janela não tem falhas; com uma chamada em dez falhando e quatro threads são 278 contra 940 ns ([taxas de falha realistas](benchmarks.md#failure-rates)). Caminhos com contenção custam centenas de nanossegundos nas duas bibliotecas, e estas são médias medidas apenas em ARM. Veja [Desempenho](performance.md).

## Projeto :id=project

### Está pronto para produção? :id=production

Toda política tem testes unitários e de ponta a ponta, as lock-free também testes de concorrência (jcstress, em x86 e ARM), a alocação é controlada no CI, e um soak test semanal de duas horas executa todas as políticas sob carga. A API está congelada desde as release candidates da 1.0.0: a 1.0.0 a entrega como está, a menos que o feedback sobre uma release candidate force uma mudança, e o [Changelog](changelog.md) a listaria. O que a 1.0.0 manterá compatível está em [Compatibilidade](reference/compatibility.md).

### O que ainda não é suportado? :id=not-supported

Caching e bulkheads com pool de threads; o algoritmo Vegas e a espera (`maxWait`) para adaptive limits; hedging síncrono (`@Hedge` funciona em métodos async); tipos do Mutiny e do RxJava (`Mono` e `Flux` do Reactor são suportados); tipos async `? super`; reconfiguração em tempo de execução; módulos de integração para frameworks além de Spring Boot, Micronaut e Quarkus. Veja [Decisões de design](design.md) para o que está planejado e o que está excluído por decisão.

### O que está no roadmap? :id=roadmap

Planejado, não prometido; nomes e escopo podem mudar:

| Funcionalidade | Nível | Notas |
|---|---|---|
| Hedging (`@Hedge`) | feito na 0.4.0 | métodos async e `Pipeline.executeAsync` ([Hedge](policies/hedge.md)); hedging síncrono depois. O Resilience4j tem `resilience4j-hedge` (async, só programático) |
| Limite de concorrência adaptativo (`@AdaptiveLimit`) | feito na 0.6.0 | Gradient2 e AIMD, sync e async ([Adaptive limit](policies/adaptive-limit.md)); Vegas e espera depois. O Resilience4j não tem nenhum |
| Tracing com OpenTelemetry | depois | as métricas estão em `resilience4cata-opentelemetry` desde a 0.3.0 ([guia](guides/opentelemetry.md)) |
| Arquivos YAML para Java puro | depois | o módulo `config` lê arquivos de propriedades; Spring Boot, Micronaut e Quarkus já leem seu próprio YAML. Um módulo separado, para que o `core` continue sem dependências |
| Ordem de execução configurável (`@PolicyOrder`) | feito na 0.10.0 | só anotações; um `Pipeline` mantém a ordem padrão ([Mudar a ordem](policies/README.md#changing-the-order)) |
| Atributo `locked` (valores da anotação que a configuração externa não pode sobrescrever) | feito na 0.7.0 | `locked = true` na anotação, `Pipeline.Builder.locked()` ([Travar uma instância](guides/configuration.md#locking-an-instance)) |
| `@Cached`, Mutiny e RxJava, coroutines do Kotlin, injeção de falhas, propagação de deadline | depois | |

Até que exista injeção de falhas, provoque falhas nos testes com um stub da interface ou classe protegida, e controle atrasos e timeouts com o `ManualClock` do [testkit](guides/testing.md).

### Onde reporto um bug? :id=bugs

No [issue tracker do GitHub](https://github.com/fabiangftech/resilience4cata/issues). Inclua o código `R4C-…` se houver um, a anotação e as versões do Java e do resilience4cata.
