# resilience4cata 1.0.0-RC2: experimente antes da 1.0

A API do resilience4cata está congelada: a 1.0.0-RC2 está no Maven Central e, a menos que o seu feedback mostre que algo precisa mudar, a 1.0.0 vai sair com a mesma API.

*4 de novembro de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor do resilience4cata*

## O que uma release candidate significa aqui :id=what-a-release-candidate-means-here

Até agora, cada versão minor podia mudar a API, e o [changelog](../changelog.md) listava o que quebrou. A partir da 1.0.0 isso acaba: as versões minor e patch de uma major mantêm funcionando tudo o que a [página de compatibilidade](../reference/compatibility.md) lista, que é a API Java, o código gerado por um processor mais antigo, as chaves de configuração, os nomes de métricas, o JSON dos endpoints e os códigos de erro.

As release candidates são a última chance de mudar qualquer uma dessas coisas a baixo custo. O build já trata a API como congelada: todo módulo publicado é comparado com a 1.0.0-RC2 no Maven Central, e uma mudança incompatível quebra o build. Se você experimentar a candidata e algo estiver estranho, um nome, um padrão, uma mensagem de erro ou um ponto de extensão que falta, agora é quando ainda dá para corrigir.

## O que é o resilience4cata :id=what-resilience4cata-is

O resilience4cata é uma biblioteca de resiliência para Java 21+: retry, circuit breaker, rate limiter, timeout, bulkhead, fallback, além de hedging e um limite de concorrência adaptativo. Você coloca anotações em uma interface ou classe, e um annotation processor escreve uma classe Java comum ao lado dela em tempo de compilação. Não há proxy, aspect nem agent. O método gerado chama cada policy diretamente, e uma chamada síncrona bem-sucedida não aloca nada.

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), uma thread, ns por chamada, no código da 1.0.0-RC1 (a RC2 só muda as integrações com frameworks), contra o Resilience4j 2.4.0 com as mesmas configurações. De [as linhas da página inicial sobre a release candidate](../benchmarks.md#release-candidate).

| Chamada | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| Retry, a primeira tentativa dá certo | **1.4 ns**, 0 B | 22.9 ns, 0 B |
| Circuit breaker, toda chamada dá certo | **1.9 ns**, 0 B | 89.9 ns, 0 B |
| Timeout síncrono | **23.6 ns**, 0 B | 8907.6 ns, 183 B |
| Bulkhead, vagas livres | **17.3 ns**, 0 B | 20.1 ns, 0 B |

Com quatro threads, quando toda chamada dá certo, o circuit breaker fica em 1.9 ns, enquanto o do Resilience4j vai a 849.5 ns. O [primeiro artigo](compile-time-resilience.md) explica de onde vem a diferença.

## O que mudou no caminho para a 1.0 :id=what-changed-on-the-way-to-10

Antes de a API ser congelada, quatro revisores passaram pela biblioteca: um designer de API, um revisor de desempenho, um recém-chegado e alguém decidindo se a adotaria. As conclusões deles, e o que foi feito com cada uma, estão no [plano até a 1.0](../plan-1.0.md). Em resumo:

- **As últimas mudanças de API.** Duas são incompatíveis e pequenas: `CircuitBreakerEvent.CallNotPermitted` agora é `Rejected`, como os eventos de rejeição das outras policies, e duas constantes do hedge deixaram de ser públicas. Os tipos que carregam o resultado de uma chamada aceitam tipos anuláveis, o que ajuda usuários de Kotlin e NullAway.
- **Promessas por escrito.** A [página de compatibilidade](../reference/compatibility.md) diz o que continua compatível dentro de uma versão major e o que pode crescer em uma minor, como novas constantes de enum, novos componentes em records de eventos ou novas policies.
- **Quatro condições de corrida corrigidas.** Estavam no bulkhead com `maxWait`, no adaptive limit, no rate limiter e no timeout síncrono. Três foram reproduzidas antes, por um teste que repete o entrelaçamento ou pelo jcstress, e esses testes foram verificados por mutação; a do rate limiter só pôde ser confirmada lendo o código. O CI roda o jcstress só em x86, então ele também rodou uma vez no Graviton3: os 65 testes passaram, em 2598 configurações.
- **Documentação.** Há uma [receita para uma chamada HTTP](../guides/http-calls.md) com um retry e um circuit breaker, testada contra um servidor real, e um passo no quickstart que mostra o breaker aberto. O [guia de migração](../migrate/from-resilience4j.md) agora mapeia os nomes de métricas do Resilience4j para os nossos.
- **RC2: um annotation processor ausente é um erro.** As anotações têm retenção `CLASS`, então, se o processor não rodasse, nada percebia e os beans rodavam sem proteção. Agora o Spring Boot falha na inicialização com [R4C-SPR-006](../errors.md#r4c-spr-006), o Micronaut falha o bean com [R4C-MN-007](../errors.md#r4c-mn-007) e o Quarkus falha o build com [R4C-QK-008](../errors.md#r4c-qk-008). Cada mensagem informa o tipo e a dependência a adicionar.

O changelog tem a lista completa: [RC2](../changelog.md#1.0.0-rc2) e [RC1](../changelog.md#1.0.0-rc1).

## Experimente :id=try-it

<!-- tabs:start -->

#### **Gradle**

<!-- not-compiled: configuração de build -->
```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC2'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC2'
}
```

#### **Gradle (Kotlin)**

<!-- not-compiled: configuração de build -->
```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.0.0-RC2")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.0.0-RC2")
}
```

#### **Maven**

<!-- not-compiled: configuração de build -->
```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>1.0.0-RC2</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0-RC2</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

As páginas deste site ainda mostram 0.13.0 nos seus trechos de build; escreva `1.0.0-RC2` no lugar. Depois, o [quickstart](../start/quickstart.md) protege uma chamada instável em uns cinco minutos. Para uma aplicação, comece pelo guia de [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) ou [Quarkus](../guides/quarkus.md). Se você usa o Resilience4j hoje, o [guia de migração](../migrate/from-resilience4j.md) mapeia os registries, configs, decorators e anotações Spring dele para o resilience4cata.

## O que mais ajuda :id=what-helps-most

Qualquer coisa que você gostaria de mudar depois da 1.0 mas não poderia, porque quebraria a compatibilidade:

- nomes, padrões ou atributos de anotações que surpreenderam você;
- uma mensagem de erro que não disse o que fazer;
- um setup de framework que não funcionou como o guia diz;
- algo de que você precisou e teve que contornar.

Abra uma [issue](https://github.com/fabiangftech/resilience4cata/issues) ou inicie uma [discussão](https://github.com/fabiangftech/resilience4cata/discussions) no GitHub. "Experimentei e simplesmente funcionou" também é útil. Problemas de segurança passam pelo relato privado, como explica o [SECURITY.md](https://github.com/fabiangftech/resilience4cata/blob/main/SECURITY.md).

## O que vem a seguir :id=what-comes-next

Se nada precisar mudar, a 1.0.0 vem a seguir com a mesma API, e a promessa de compatibilidade começa. Se algo precisar mudar, haverá antes outra candidata.
