# Quarkus

Use o resilience4cata em uma aplicação Quarkus: beans anotados são protegidos pelo código gerado, o registry
é configurado a partir do `application.properties`, e o SmallRye Health e o Micrometer o enxergam.

A extensão `resilience4cata-quarkus` (disponível desde 0.5.0) é uma extensão Quarkus comum, com um módulo de runtime e
um de deployment. Ela não adiciona nada ao caminho da chamada: não há interceptors CDI. Suas build steps fazem das
classes que o annotation processor gerou os beans que a sua aplicação recebe, então uma chamada custa o que custaria
sem o Quarkus, mais o client proxy do ArC quando o bean tem escopo normal.

**Requisitos:** Quarkus 3.40 ou mais recente (a extensão é compilada e testada contra a 3.40.1, a linha LTS
atual) e Java 21.

## Instalação :id=install

Adicione a extensão e, como em qualquer uso das anotações, o annotation processor
([instalação](../start/installation.md)). A extensão traz `resilience4cata-core` e
`resilience4cata-config`; o Quarkus encontra o módulo de deployment sozinho.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation enforcedPlatform("io.quarkus.platform:quarkus-bom:${quarkusPlatformVersion}")
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.0.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.0.0')

    implementation 'dev.resilience4cata:resilience4cata-quarkus'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-micrometer'   // optional: metrics, with quarkus-micrometer
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'  // optional: ManualClock
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:${quarkusPlatformVersion}"))
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.0.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-quarkus")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-micrometer")   // optional: metrics, with quarkus-micrometer
    testImplementation("dev.resilience4cata:resilience4cata-testkit")  // optional: ManualClock
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement>, next to the Quarkus BOM -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-quarkus</artifactId>
</dependency>
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-annotations</artifactId>
  <scope>provided</scope>
</dependency>

<!-- in the maven-compiler-plugin configuration -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0</version>
  </path>
  <!-- annotationProcessorPaths replaces discovery: list the other processors you use too -->
</annotationProcessorPaths>
```

<!-- tabs:end -->

> Sem o annotation processor nada é gerado, então o build falha com
> [`R4C-QK-008`](../errors.md#r4c-qk-008) para cada bean cuja classe ou interface tem anotações do resilience4cata mas
> nenhuma classe gerada, em vez de executá-lo sem proteção. `quarkus.resilience4cata.allow-missing-processor=true` a
> desliga. Com o log de debug da extensão (`quarkus.log.category."dev.resilience4cata".level=DEBUG`), o build
> lista cada bean que ela conecta.

As classes geradas são encontradas no índice da aplicação, como os beans: um jar de biblioteca com classes anotadas precisa de um
índice Jandex (ou `quarkus.index-dependency`), como para os seus beans.

## Classes anotadas :id=classes

Anote a própria classe do bean:

<!-- snippet: quarkus-class -->
```java
@ApplicationScoped
public class OrderService {

    @Inject
    OrderRepository orders;

    @Retry(maxAttempts = 3, delay = "100ms")
    @CircuitBreaker(name = "orders")
    public Order find(String id) {
        return orders.load(id);
    }

    public Order findAgain(String id) {
        return find(id); // a call on this: still retried
    }
}
```

O processor gera `OrderService$Resilient extends OrderService`. A extensão veta `OrderService` e
faz de `OrderService$Resilient` o bean no lugar dela, com o escopo, os qualifiers e os stereotypes da classe. Assim:

- `@Inject OrderService` recebe a classe gerada (atrás do client proxy do ArC, como qualquer bean `@ApplicationScoped`);
  qualifiers, `@Named`, `@Alternative`/`@Priority` e `@Startup` continuam funcionando;
- chamadas em `this` (`findAgain` chamando `find`) passam pelas policies, ao contrário de interceptors CDI como os do SmallRye
  Fault Tolerance;
- injeção por campo, métodos inicializadores, `@PostConstruct` e métodos observer são herdados; a injeção por construtor
  também funciona: os construtores gerados recebem o `ResilienceRegistry` seguido dos parâmetros originais,
  com suas anotações ([classes anotadas](annotated-classes.md#constructor-injection)).

**Classes com escopo normal precisam de um construtor sem parâmetros.** Beans `@ApplicationScoped`, `@RequestScoped` e
`@SessionScoped` são acessados através de um client proxy, que precisa de um construtor não privado sem
parâmetros. O Quarkus adiciona um a uma classe que não tem nenhum, mas não à subclasse gerada de uma classe que não tem nenhum,
então o build falha com [`R4C-QK-005`](../errors.md#r4c-qk-005). Use injeção por campo como acima, adicione um
construtor package-private sem parâmetros (só o proxy o usa), ou torne o bean `@Singleton`.

Uma classe com métodos ou campos `@Produces` não pode ser substituída (producers não são herdados):
[`R4C-QK-004`](../errors.md#r4c-qk-004).

**Interceptors sobre a classe.** Interceptors CDI (`@Transactional`, `@Logged`, …) são aplicados por uma subclasse do ArC
da classe gerada, então rodam fora das policies. Para transações, isso significaria uma única transação para
todas as tentativas, e depois que uma tentativa falha a marca como rollback-only, uma tentativa posterior bem-sucedida não consegue fazer commit. Por isso, um
método que tem retry ou hedge e é `@Transactional` ao mesmo tempo (com um tipo que se junta a uma transação ou inicia uma) **faz o build
falhar** com [`R4C-QK-002`](../errors.md#r4c-qk-002). Declare a policy em uma interface em vez disso (uma
transação por tentativa, veja abaixo), mova o `@Transactional` para outro bean que o método chama, ou defina
`quarkus.resilience4cata.allow-retry-inside-transaction=true` se uma única transação compartilhada é o que você quer.

## Interfaces anotadas :id=interfaces

<!-- snippet: quarkus-interface -->
```java
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface PaymentsGateway {

    String charge(String order, long cents);
}
```

<!-- snippet: quarkus-interface-bean -->
```java
@ApplicationScoped
public class CardPayments implements PaymentsGateway {

    @Override
    public String charge(String order, long cents) {
        return "receipt-" + order; // a call to the payment provider in a real application
    }
}
```

Para cada bean que implementa uma interface anotada, a extensão registra um bean sintético com os tipos da
interface, com os qualifiers do bean, criado por `Resilient.of(PaymentsGateway.class, bean, registry)`. Ele é uma
alternative habilitada com prioridade alta, então prevalece onde quer que a interface seja injetada:

<!-- snippet: quarkus-inject-interface -->
```java
@ApplicationScoped
public class Checkout {

    @Inject
    PaymentsGateway payments; // the interface: the wrapper PaymentsGateway$Resilient around CardPayments

    public String pay(String order) {
        return payments.charge(order, 1999);
    }
}
```

- `@Inject CardPayments` (a classe) continua recebendo o próprio bean, sem proteção.
- O wrapper de um bean `@ApplicationScoped` também é `@ApplicationScoped`, atrás do seu próprio client proxy, então a
  interface continua sendo um bean de escopo normal (o `@InjectMock` de um teste na interface funciona e substitui o wrapper,
  policies incluídas). O wrapper de um bean com escopo de request ou de sessão é um `@Singleton` que contém o client
  proxy do bean (esses escopos continuam funcionando). Ambos são criados na inicialização; o wrapper de um bean `@Dependent` também é
  `@Dependent`.
- Os interceptors CDI do bean rodam **dentro** das policies: um método `@Transactional` com retry recebe uma
  transação por tentativa.
- Chamadas em `this` dentro de `CardPayments` não são protegidas (o wrapper fica fora do objeto).
- Um bean que implementa duas interfaces anotadas, sem que nenhuma estenda a outra, faz o build falhar com
  [`R4C-QK-003`](../errors.md#r4c-qk-003). Métodos producer não são envolvidos: devolva `Resilient.of(...)` a partir deles
  você mesmo.

## REST clients :id=rest-client

Anote uma interface de REST client como qualquer outra:

<!-- snippet: quarkus-rest-client -->
```java
@RegisterRestClient(configKey = "stock")
@Path("/stock")
@Retry(maxAttempts = 3, delay = "50ms")
public interface StockClient {

    @GET
    @Path("/{sku}")
    int available(@PathParam("sku") String sku);
}
```

O bean do REST client tem o qualifier `@RestClient`. A extensão adiciona um bean `@Default` em volta dele, então injetar
a interface sem qualifier recebe o client protegido, e `@RestClient` continua recebendo o puro:

<!-- snippet: quarkus-inject-rest-client -->
```java
@ApplicationScoped
public class StockReport {

    @Inject
    StockClient stock; // no @RestClient: the protected client

    public String line(String sku) {
        return sku + ": " + stock.available(sku);
    }
}
```

## Beans reativos :id=reactive

Métodos de beans que devolvem `Mono` ou `Flux` do Reactor são protegidos da mesma forma, com `resilience4cata-reactor` no class path (ele não faz parte de `resilience4cata-all`: [instalação](../start/installation.md)). Cada assinatura é uma chamada através das policies; para um `Flux`, o retry e o fallback atuam apenas antes do primeiro elemento, e `@Timeout` é o tempo sem elemento ([Project Reactor](reactor.md)). `Uni` e `Multi` do Mutiny não são suportados ([R4C-ASY-002](../errors.md#r4c-asy-002)): converta na implementação (`uni.subscribeAsCompletionStage()`).

<!-- snippet: quarkus-reactive -->
```java
@ApplicationScoped
public class PriceService {

    @Inject
    PriceBackend backend;

    @Retry(maxAttempts = 3, delay = "10ms")
    @Timeout("2s")
    public Mono<Integer> price(String sku) {
        return backend.price(sku);
    }

    @Retry(maxAttempts = 3, delay = "10ms")
    @Timeout("2s")                                  // to the first element, and between two
    @Fallback(method = "noTicks")
    public Flux<Integer> ticks(String sku) {
        return backend.ticks(sku);
    }

    protected Flux<Integer> noTicks(String sku) {
        return Flux.empty();
    }
}
```

O Quarkus REST não assina os tipos do Reactor: um endpoint que devolve um `Mono` escreve o objeto como uma entidade e a chamada nunca roda. Converta para Mutiny no resource; o Mutiny aceita um `Flow.Publisher` do JDK, que o `JdkFlowAdapter` do Reactor fornece:

<!-- snippet: quarkus-reactive-endpoint -->
```java
@GET
@Path("/quotes/{sku}")
@Produces(MediaType.TEXT_PLAIN)
public Uni<String> quote(@PathParam("sku") String sku) {
    return Uni.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(quotes.quote(sku)));
}

@GET
@Path("/ticks/{sku}")
@Produces(MediaType.SERVER_SENT_EVENTS)
public Multi<String> ticks(@PathParam("sku") String sku) {
    return Multi.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(quotes.ticks(sku)));
}
```

- **Uma falha de um `Uni`** passa pelos seus `@ServerExceptionMapper`s como qualquer outra (uma `CallTimeoutException` mapeada para 504, por exemplo). Um stream de server-sent events já enviou seus headers antes do primeiro elemento, então uma falha, mesmo antes do primeiro evento, encerra o stream fechando a conexão.
- **Um cliente que se desconecta** cancela a chamada através de cada policy, inclusive antes do primeiro evento de um stream: o `Mono` ou `Flux` do delegate é cancelado e o bulkhead libera sua vaga.

Um `jakarta.transaction.Transactional` em volta de um método `Mono` ou `Flux` com retry ou hedge de uma classe anotada faz o build falhar, como para outros métodos ([R4C-QK-002](../errors.md#r4c-qk-002)).

## Configuração :id=configuration

As policies leem a configuração da aplicação sob `resilience4cata.` (ou `r4c.`), com as chaves da
[referência de configuração](../reference/configuration.md) em camelCase ou kebab-case:

<!-- snippet: quarkus-properties -->
```properties
resilience4cata.defaults.retry.delay=50ms
resilience4cata.defaults.circuit-breaker.minimum-calls=20
resilience4cata.configs.slow.retry.delay=500ms
resilience4cata.instances.orders.circuit-breaker.failure-rate-threshold=25
resilience4cata.instances."dev.resilience4cata.quarkus.guide.PaymentsGateway#charge(java.lang.String,long)".timeout.value=3s
%dev.resilience4cata.defaults.retry.max-attempts=2
```

- Os valores vêm do SmallRye Config, então toda fonte do Quarkus funciona: `application.properties`, YAML
  (`quarkus-config-yaml`), `.env`, propriedades de sistema, `-D` e expressões (`${…}`).
- **Os profiles são os do Quarkus**: `%dev.`, `%test.`, `%prod.` e os seus. As próprias chaves `profiles.<name>.*`
  do resilience4cata também continuam funcionando.
- **A precedência é a do Quarkus**: propriedades de sistema acima de variáveis de ambiente, acima de `application.properties`.
  As variáveis de ambiente usam os [nomes `R4C_` ou `RESILIENCE4CATA_`](configuration.md) do
  `resilience4cata-config` (o SmallRye não consegue mapeá-las de volta para chaves em camelCase). Um arquivo `resilience4cata.properties` no
  class path continua sendo lido, abaixo de todos eles.
- **Ids de instância** com algo além de letras, dígitos, `_` e `-` (o derivado `<type>#<method>(<parameter types>)`) vão entre
  aspas duplas, como acima.
- A configuração é lida uma vez, na inicialização, como em todo o resto do resilience4cata: não há recarregamento.
- As chaves sob `quarkus.resilience4cata.` são os próprios [switches](#switches) da extensão.

**Validação na inicialização.** O registry é criado quando a aplicação inicia, então uma chave desconhecida ou um valor inválido
a interrompe com todos os problemas de uma vez, e o arquivo e a linha onde cada chave foi definida:

```text
ERROR [io.quarkus.runtime.Application] Failed to start application
dev.resilience4cata.config.ConfigException: Invalid resilience4cata configuration (1 problem):
  [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.maxAtempts' (application.properties:2). Did you mean 'resilience4cata.defaults.retry.maxAttempts'? ...
```

Na inicialização, as chaves `resilience4cata.instances.<id>.*` também são verificadas contra as instâncias de policies criadas até
então ([`R4C-CFG-013`](../errors.md#r4c-cfg-013)). O Quarkus cria beans sob demanda, então o padrão é um aviso
(`quarkus.resilience4cata.verify-instances=warn`): os wrappers de interfaces são criados na inicialização, mas as classes
anotadas só no primeiro uso, a menos que sejam beans `@Startup`. Defina `fail` quando toda classe protegida for criada na
inicialização.

## Customizers e listeners :id=beans

Um bean `ResilienceRegistryCustomizer` ajusta o builder do registry, por exemplo uma config nomeada em código:

<!-- snippet: quarkus-customizer -->
```java
@Singleton
public class ResilienceSetup implements ResilienceRegistryCustomizer {

    @Override
    public void customize(ResilienceRegistry.Builder builder) {
        builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
    }
}
```

Os customizers rodam na ordem de `@Priority`, antes das propriedades, então as propriedades prevalecem:
`resilience4cata.configs.slow.retry.delay` acima sobrescreve o delay do customizer e mantém seu `maxAttempts`.

Todo bean `EventListener` é registrado para o tipo de evento nos seus tipos de bean (um listener raw recebe todos os eventos):

<!-- snippet: quarkus-listener -->
```java
@Produces
@Singleton
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.id() + " is now " + transition.to());
}
```

Um bean `Ticker` (o `ManualClock` do testkit) se torna o relógio do registry. Uma aplicação que produz seu próprio
bean `ResilienceRegistry` substitui o da extensão (customizers, listeners e métricas então não são aplicados); o
bean `ResilienceConfig` continua lá para construí-lo com `config.applyTo(builder)`.

## Métricas e health :id=observability

- **Métricas**: com `quarkus-micrometer` (e um registry como `quarkus-micrometer-registry-prometheus`) e
  `resilience4cata-micrometer`, os [meters do Micrometer](micrometer.md) são registrados automaticamente e aparecem em
  `/q/metrics`. Sem Micrometer, mas com `quarkus-opentelemetry` e `resilience4cata-opentelemetry`, as
  [métricas do OpenTelemetry](opentelemetry.md) são registradas em vez disso (o Micrometer é preferido quando os dois estão presentes, como
  faz o SmallRye Fault Tolerance). Chamadas bem-sucedidas não publicam eventos, então não custam nada.
- **Health**: com `quarkus-smallrye-health`, o readiness check `resilience4cata` (`/q/health/ready`) reporta
  o estado e a taxa de falhas de cada circuit breaker. Ele fica **UP** com breakers abertos: um breaker aberto é a
  aplicação se protegendo de uma dependência com falhas, não um motivo para tirá-la do balanceamento de carga. Liste os
  breakers que devem deixá-lo DOWN em `quarkus.resilience4cata.health.down-when-open` (`*` para todos).
- Não há endpoint HTTP (no dev mode há o [card do Dev UI](#dev-ui)): `registry.snapshot().toMap()` tem tudo, se você quiser expô-lo.

## Dev UI :id=dev-ui

No dev mode (`quarkus dev`, ou `./gradlew quarkusDev`), o Dev UI em `/q/dev-ui` tem um card do resilience4cata. Sua
página *Policies* lista cada instância de policy que o registry criou até então, atualizada a cada dois segundos enquanto a
página está visível:

| Policy | O que a página mostra |
|---|---|
| Circuit breakers | estado, taxas de falhas e de chamadas lentas, chamadas na janela (com falha, lentas) |
| Bulkheads | chamadas concorrentes contra o máximo, chamadas em espera |
| Rate limiters | permissões disponíveis contra as permissões por período |
| Adaptive limits | algoritmo, limite atual e seus limites, chamadas em andamento, latência da última janela e latência de longo prazo |
| Retries, hedges | máximo de tentativas (e delay), as chamadas do orçamento e os retries ou hedges permitidos, quando há um orçamento definido |
| Timeouts | o timeout |

A maioria das instâncias é criada na primeira chamada ao seu método (as de uma classe anotada quando o bean é criado), então uma
policy aparece depois de ter sido usada. Um valor que ainda não tem significado, como uma taxa de falhas antes de `minimumCalls`, é
mostrado como um traço.

A página também pode forçar um circuit breaker a abrir, desabilitá-lo ou reiniciá-lo, como faz `registry.circuitBreaker(id)`: útil
para ver como a aplicação se comporta com uma dependência fora do ar. O link *Guide* do card abre esta seção.

Nada disso existe fora do dev mode: as build steps que adicionam o card e seu serviço JsonRPC rodam apenas em
desenvolvimento local, então um build de teste ou de produção não tem nenhum dos dois.

## SmallRye Fault Tolerance vs resilience4cata :id=smallrye

O Quarkus traz sua própria tolerância a falhas, o [SmallRye Fault Tolerance](https://quarkus.io/guides/smallrye-fault-tolerance)
(`quarkus-smallrye-fault-tolerance`, MicroProfile Fault Tolerance). Os dois funcionam na mesma aplicação, mas não no
mesmo método.

**Escolha o SmallRye Fault Tolerance** quando você quer a opção embutida e padrão: portabilidade MicroProfile,
`@Asynchronous` e tipos de retorno do Mutiny, configuração que pode ser alterada em tempo de execução, e nada a adicionar ao
build.

**Escolha o resilience4cata** quando você quer que o mesmo código funcione fora do CDI (Java puro, testes sem contêiner,
Spring Boot), anotações e configuração verificadas em tempo de compilação e na inicialização com erros codificados, configs nomeadas,
bundles, sobrescritas por instância, classifiers e hedging, chamadas em `this` protegidas em classes anotadas, ou o seu
[overhead](../performance.md).

**Mecanismo.** O SmallRye Fault Tolerance é um interceptor CDI; o resilience4cata é código gerado que a extensão
transforma no bean. Nenhum dos dois protege um `new CardPayments()` que o CDI não criou.

**Imports.** As duas bibliotecas chamam suas anotações de `@Retry`, `@CircuitBreaker`, `@Timeout`, `@Bulkhead` e
`@Fallback`, e as IDEs importam a primeira correspondência. Um método (ou classe) que leva anotações das duas faz o build falhar
com [`R4C-QK-001`](../errors.md#r4c-qk-001) quando o SmallRye Fault Tolerance está instalado (um aviso caso contrário).
Prefira uma biblioteca por módulo, ou um [bundle](bundles.md) como `@ExternalCall`, para que os nomes simples nem
apareçam.

**Correspondência.** Os padrões também diferem: consulte a [referência de configuração](../reference/configuration.md).

| SmallRye / MicroProfile Fault Tolerance | resilience4cata |
|---|---|
| `@Retry(maxRetries = 3)` (retries depois da primeira chamada) | `@Retry(maxAttempts = 4)` (tentativas, a primeira incluída) |
| `@Retry(delay = 200, delayUnit = ChronoUnit.MILLIS)` | `@Retry(delay = "200ms", backoff = Backoff.FIXED)` (o backoff padrão é exponencial) |
| `@Retry(jitter = 200)` (± uma duração) | `@Retry(jitter = Jitter.FULL)` (`NONE`, `FULL` ou `EQUAL`, uma fração de cada delay) |
| `@Retry(maxDuration = 180000)` | `@Retry(maxDuration = "3m")` |
| `@Retry(retryOn = …, abortOn = …)` | `@Retry(retryOn = …, abortOn = …)` |
| `@ExponentialBackoff` | `@Retry(backoff = Backoff.EXPONENTIAL, multiplier = 2, maxDelay = "10s")` |
| `@CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5)` | `@CircuitBreaker(windowSize = 20, minimumCalls = 20, failureRateThreshold = 50)` (um percentual) |
| `@CircuitBreaker(delay = 5000)` | `@CircuitBreaker(waitInOpen = "5s")` |
| `@CircuitBreaker(successThreshold = 1)` | `@CircuitBreaker(halfOpenCalls = 1)` |
| `@CircuitBreaker(failOn = …, skipOn = …)` | `@CircuitBreaker(recordOn = …, ignoreOn = …)` |
| `@Timeout(value = 2, unit = ChronoUnit.SECONDS)` | `@Timeout("2s")` |
| `@Bulkhead(10)` | `@Bulkhead(maxConcurrent = 10)` |
| `@Bulkhead(value = 10, waitingTaskQueue = 10)` com `@Asynchronous` | `@Bulkhead(maxConcurrent = 10, maxWait = "…")` (espera por um tempo, síncrono ou assíncrono) |
| `@RateLimit(value = 100, window = 1, windowUnit = ChronoUnit.SECONDS)` | `@RateLimiter(permits = 100, period = "1s")` |
| `@Fallback(fallbackMethod = "cached")` | `@Fallback(method = "cached")` |
| `@Fallback(MyHandler.class)` | `@Fallback(MyHandler.class)` (um `FallbackHandler`) |
| `@Asynchronous` com `CompletionStage` | um tipo de retorno `CompletionStage`/`CompletableFuture` basta |
| `quarkus.fault-tolerance."<id>".retry.max-retries=5` | `resilience4cata.instances.<id>.retry.maxAttempts=6` |
| `quarkus.fault-tolerance.global.retry.max-retries=5` | `resilience4cata.defaults.retry.maxAttempts=6` |

Mudar um valor em tempo de execução, o que o SmallRye permite, não tem equivalente: o resilience4cata resolve a configuração uma vez,
por decisão de projeto.

## Switches :id=switches

| Propriedade | Fase | Padrão | Efeito |
|---|---|---|---|
| `quarkus.resilience4cata.beans.enabled` | build | `true` | Fazer das classes geradas os beans (subclasses substituem classes anotadas, wrappers representam beans de interface) |
| `quarkus.resilience4cata.beans.exclude` | build | vazio | Classes de beans (totalmente qualificadas) a deixar em paz |
| `quarkus.resilience4cata.allow-retry-inside-transaction` | build | `false` | Permitir `@Retry`/`@Hedge` em métodos `@Transactional` de classes anotadas em vez de falhar com R4C-QK-002 |
| `quarkus.resilience4cata.allow-missing-processor` | build | `false` | Fazer o build mesmo quando um bean tem anotações do resilience4cata mas nenhuma classe gerada (suas policies não rodam) em vez de falhar com R4C-QK-008 |
| `quarkus.resilience4cata.health.enabled` | build | `true` | O readiness check, quando o SmallRye Health está presente |
| `quarkus.resilience4cata.metrics.enabled` | build | `true` | Vincular o registry ao Micrometer ou ao OpenTelemetry |
| `quarkus.resilience4cata.verify-instances` | runtime | `warn` | `fail`, `warn` ou `off` para chaves de instância que não correspondem a nada na inicialização |
| `quarkus.resilience4cata.health.down-when-open` | runtime | vazio | Ids de breakers (ou `*`) que deixam o readiness check DOWN enquanto abertos |

## Testes :id=testing

Produza um bean `ManualClock` nos seus fontes de teste: pausas de retry, esperas do bulkhead e do rate limiter e timeouts o
seguem, então um teste nunca dorme ([guia de testes](testing.md)).

<!-- snippet: quarkus-test-clock -->
```java
@Singleton
public class TestClock {

    @Produces
    @Singleton
    ManualClock clock() {
        return new ManualClock(); // retry pauses, waits and timeouts follow it instead of the system clock
    }
}
```

<!-- snippet: quarkus-test -->
```java
@Inject
OrderService orders;

@Inject
ManualClock clock;

@Inject
OrderRepository repository; // fails the first loads it is told to

@Test
void retriesWithoutSleeping() {
    repository.failNext(2);
    long before = clock.elapsed().toNanos();

    assertThat(orders.find("42")).isEqualTo(new Order("42"));
    assertThat(clock.elapsed().toNanos()).isGreaterThan(before); // the two pauses, on the manual clock
}
```

Um bean de escopo normal é injetado como client proxy: leia e altere seu estado através de métodos, nunca através dos seus
campos.

## Native images :id=native

O processor escreve metadados de alcançabilidade do GraalVM para cada classe gerada, e o `core` inicializa suas
threads de background em tempo de execução, então um build nativo (`-Dquarkus.native.enabled=true`) não precisa de configuração extra:
classes anotadas são instanciadas pelo código gerado do ArC, e os wrappers de interface por `Resilient.of`, que os encontra
através desses metadados. A configuração é lida em tempo de execução. A CI do projeto constrói a aplicação deste guia como
native image (GraalVM para JDK 25) e a testa via HTTP: uma classe anotada, wrappers de interface e de REST client,
o readiness check, as métricas, e métodos `Mono` e `Flux` servidos através de `Uni` e `Multi`
([beans reativos](#reactive)).

## Limites :id=limits

- Dev mode: ainda não foi verificado se um live reload executa de novo o annotation processor. Se uma mudança nas
  anotações parecer ignorada, reinicie o dev mode.
- Classes anotadas com escopo normal precisam de um construtor sem parâmetros (ou injeção por campo); classes anotadas
  não podem declarar producers.
- Wrappers de interface: chamadas em `this` dentro da implementação não são protegidas; uma interface anotada por bean;
  métodos producer não são envolvidos.
- Com classes anotadas, interceptors CDI como `@Transactional` rodam fora das policies; um método transacional com retry ou
  hedge faz o build falhar ([`R4C-QK-002`](../errors.md#r4c-qk-002)) a menos que permitido.
- Sem endpoint HTTP (apenas o [card do Dev UI](#dev-ui) no dev mode), sem hot reload da configuração, sem métricas por chamada.
