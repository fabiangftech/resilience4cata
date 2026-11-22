# Micronaut

Use o resilience4cata em uma aplicação Micronaut 5: os beans anotados são protegidos pelo código gerado, o
registry é configurado a partir do `application.yml`, e o módulo de gerenciamento do Micronaut ganha um health
indicator, um endpoint e métricas.

O módulo `resilience4cata-micronaut` (disponível desde 0.5.0) não adiciona nada ao caminho da chamada: não há
advice `@Around` nem interceptor. Um bean protegido *é* a classe que o processador de anotações gerou, então
uma chamada custa o que custa sem o Micronaut.

**Requisitos:** Micronaut 5.2 ou mais recente (o módulo é compilado contra o Micronaut core 5.2.0, e seus testes rodam tanto
no core 5.2.0 quanto na plataforma 5.2.2) e **Java 25**, que o próprio Micronaut 5 exige. Este módulo é a única exceção à
baseline Java 21 do resilience4cata: ele é compilado com `--release 25`; todos os outros módulos continuam no Java 21.
O Micronaut 4 não é suportado.

## Instalação :id=install

Adicione o módulo e, como para qualquer uso das anotações, o processador de anotações
([instalação](../start/installation.md)). O módulo traz `resilience4cata-core` e
`resilience4cata-config`. A opção de compilador `-Ar4c.inject=micronaut` torna as subclasses geradas das
classes anotadas beans do Micronaut ([classes anotadas](#classes)); ela não é necessária se você só anota
interfaces.

<!-- tabs:start -->

#### **Gradle**

```groovy
plugins {
    id 'io.micronaut.application' version '5.0.2' // adds micronaut-inject-java to the processor path
}

dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.0.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.0.0')

    implementation 'dev.resilience4cata:resilience4cata-micronaut'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-micrometer'   // optional: metrics
    implementation 'io.micronaut:micronaut-management'                // optional: health and endpoint
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'  // optional: ManualClock
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.add('-Ar4c.inject=micronaut') // annotated classes become Micronaut beans
}
```

#### **Gradle (Kotlin)**

```kotlin
plugins {
    id("io.micronaut.application") version "5.0.2" // adds micronaut-inject-java to the processor path
}

dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.0.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-micronaut")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-micrometer")   // optional: metrics
    implementation("io.micronaut:micronaut-management")                // optional: health and endpoint
    testImplementation("dev.resilience4cata:resilience4cata-testkit")  // optional: ManualClock
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Ar4c.inject=micronaut") // annotated classes become Micronaut beans
}
```

#### **Maven**

```xml
<!-- with micronaut-parent as the parent, and resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-micronaut</artifactId>
</dependency>
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-annotations</artifactId>
  <scope>provided</scope>
</dependency>

<!-- in the maven-compiler-plugin configuration -->
<annotationProcessorPaths combine.children="append">
  <!-- micronaut-parent already lists micronaut-inject-java: append ours -->
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0</version>
  </path>
</annotationProcessorPaths>
<compilerArgs combine.children="append">
  <arg>-Ar4c.inject=micronaut</arg>
</compilerArgs>
```

<!-- tabs:end -->

Sem o plugin Gradle do Micronaut, coloque você mesmo `io.micronaut:micronaut-inject-java` em `annotationProcessor`,
com o BOM da plataforma Micronaut. **A ordem dos dois processadores não importa**: o do Micronaut nunca reivindica
anotações e o nosso reivindica apenas as suas, então cada um vê tudo (o build do módulo compila as mesmas fontes
nas duas ordens e compara a saída). O Lombok, se você o usa, vai primeiro, como diz a própria documentação do
Micronaut ([com Lombok](annotated-classes.md#with-lombok)).

> Sem o processador de anotações nada é gerado, então criar um bean de uma classe anotada falha com
> [`R4C-MN-007`](../errors.md#r4c-mn-007), e um `@ResilientBean` com [`R4C-RT-003`](../errors.md#r4c-rt-003), em vez
> de executá-los sem proteção. A verificação lê arquivos de classe (as anotações têm retenção `CLASS`), então ela não
> roda em uma native image. `resilience4cata.micronaut.allow-missing-processor=true` a desliga.

## Classes anotadas :id=classes

Anote a própria classe; este é o estilo recomendado, já que as chamadas em `this` também ficam protegidas:

<!-- snippet: micronaut-class -->
```java
@Singleton
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

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

O processador gera `OrderService$Resilient extends OrderService`. Com `-Ar4c.inject=micronaut` ele também
repete nela as anotações da própria classe (`@Singleton`, qualificadores como `@Named`, `@Requires`, `@Primary`,
`@Order`…). O processador do Micronaut então escreve uma definição de bean para a classe gerada, a integração deixa de fora
a que o Micronaut compilou para o próprio `OrderService`, e o Micronaut cria `OrderService$Resilient` onde quer que
teria criado `OrderService`:

- o bean continua sendo um `OrderService`, com seu escopo, qualificadores e requisitos (com `@RequestScope` ou
  `@Refreshable`, cada requisição ou refresh recebe sua própria instância da classe gerada, e todas compartilham as
  políticas nomeadas);
- as chamadas em `this` (`findAgain` chamando `find`) passam pelas políticas, ao contrário do `@Retryable` do próprio Micronaut;
- a injeção por construtor funciona como antes: os construtores gerados recebem o `ResilienceRegistry` (injetado por
  tipo) seguido dos parâmetros originais, com seus nomes e anotações (`@Inject` em um de vários
  construtores, `@Named`, `@Value`; veja [classes anotadas](annotated-classes.md#constructor-injection)). A injeção
  por campo e por método e `@PostConstruct` são herdados.

Sem a opção, o Micronaut criaria o `OrderService` sem proteção. O módulo percebe isso quando o bean é
criado e falha com [`R4C-MN-004`](../errors.md#r4c-mn-004). (Uma classe cujo construtor tem `@Inject` é um
caso especial: o construtor gerado repete `@Inject`, então sem a opção o Micronaut vê dois beans, a
classe e sua subclasse, e `R4C-MN-004` reporta o primeiro.)

**Sem a opção, com uma factory.** Deixe a classe sem anotação de escopo (e sem `@Inject`), para que o
Micronaut não a crie, e retorne a subclasse gerada a partir de um método `@Factory`:

<!-- snippet: micronaut-factory-class -->
```java
public class InvoiceClient { // no scope annotation: Micronaut does not create it itself

    private final String baseUrl;

    public InvoiceClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Retry(maxAttempts = 2)
    public String fetch(String number) {
        return baseUrl + "/invoices/" + number; // an HTTP call in a real application
    }
}
```

<!-- snippet: micronaut-factory -->
```java
@Factory
public class InvoiceFactory {

    @Singleton
    InvoiceClient invoiceClient(ResilienceRegistry registry, @Value("${invoices.url}") String url) {
        return new InvoiceClient$Resilient(registry, url); // typed InvoiceClient, the generated subclass inside
    }
}
```

Declare o tipo de retorno do método como a classe, não `InvoiceClient$Resilient`: a classe gerada ainda não existe
quando o processador do Micronaut lê a factory pela primeira vez. Um bean criado por factory não recebe injeção própria nem
AOP do Micronaut; com a opção, a classe mantém todos os recursos do Micronaut.

**AOP do Micronaut na classe.** As sobrescritas geradas repetem as anotações dos métodos que sobrescrevem, então
o around advice (`@Transactional`, `@Cacheable`, seus próprios bindings `@Around`) continua se aplicando, e o proxy do Micronaut
(uma subclasse `$Intercepted` de `OrderService$Resilient`) fica **em volta** das políticas: o advice roda uma vez para todas
as tentativas. Para transações isso significaria uma transação para todas as tentativas, e depois que uma tentativa que falhou
a marca como rollback-only, uma tentativa posterior bem-sucedida falha ao fazer commit. Por isso um método que tem retry ou hedge e é
`@Transactional` (o do Micronaut, ou o do Jakarta mapeado para ele, com uma propagação que entra em uma transação ou inicia uma)
falha com [`R4C-MN-005`](../errors.md#r4c-mn-005) quando o bean é criado. Corrija declarando a política em uma
interface que o bean implementa (o wrapper fica fora do proxy de transação: uma transação por tentativa), ou
movendo `@Transactional` para outro bean que o método com retry chama. Se uma transação compartilhada é o que você quer,
defina `resilience4cata.micronaut.allow-retry-inside-transaction=true`. `@CircuitBreaker`, `@Timeout`,
`@RateLimiter` e `@Bulkhead` executam a chamada uma vez e não são verificados.

## Interfaces anotadas :id=interfaces

<!-- snippet: micronaut-interface -->
```java
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface StockClient {

    int available(String sku);
}
```

Marque a implementação com `@ResilientBean`, nomeando a interface:

<!-- snippet: micronaut-interface-bean -->
```java
@Singleton
@ResilientBean(StockClient.class) // the bean is replaced by StockClient$Resilient
public class HttpStockClient implements StockClient {

    @Override
    public int available(String sku) {
        return 42; // an HTTP call in a real application
    }
}
```

Quando o Micronaut cria o bean, um `BeanCreatedEventListener` o substitui por
`Resilient.of(StockClient.class, bean, registry)`. Injete a interface:

<!-- snippet: micronaut-inject-interface -->
```java
@Singleton
public class StockReport {

    private final StockClient stock; // the interface: the bean is StockClient$Resilient

    public StockReport(StockClient stock) {
        this.stock = stock;
    }

    public String line(String sku) {
        return sku + ": " + stock.available(sku);
    }
}
```

- `@ResilientBean(StockClient.class)` também limita os tipos do bean a `StockClient` (ele define
  `@Bean(typed = StockClient.class)`), porque o wrapper implementa apenas a interface: injetar
  `HttpStockClient` não encontra nenhum bean. Expô-lo como outros tipos faz a inicialização falhar com
  [`R4C-MN-001`](../errors.md#r4c-mn-001), e nomear uma interface que o bean não implementa, com
  [`R4C-MN-002`](../errors.md#r4c-mn-002).
- O wrapper substitui o bean como o Micronaut o construiu, proxy de AOP incluído, então o advice do Micronaut em
  `HttpStockClient` roda **dentro** das políticas: um método `@Transactional` com retry recebe uma nova transação por
  tentativa.
- As chamadas em `this` dentro de `HttpStockClient` não são protegidas (o wrapper está fora do objeto).
- Use `@Singleton`, `@Prototype` ou `@Context`. Os escopos de que o Micronaut cria proxies (`@Refreshable`, `@RequestScope`,
  `@ThreadLocal`) fazem a inicialização falhar com [`R4C-MN-003`](../errors.md#r4c-mn-003).
- Toda definição `@ResilientBean` é verificada quando o contexto inicia, então esses erros param a inicialização mesmo
  para beans criados depois. Por que um marcador e não um envolvimento automático: é explícito, e não precisa procurar classes para
  cada bean.

## Beans reativos :id=reactive

Os métodos de beans que retornam `Mono` ou `Flux` do Reactor são protegidos da mesma forma, com `resilience4cata-reactor` no class path (ele não faz parte de `resilience4cata-all`: [instalação](../start/installation.md)). Cada inscrição é uma chamada através das políticas; para um `Flux`, o retry e o fallback atuam apenas antes do primeiro elemento, e `@Timeout` é o tempo sem nenhum elemento ([Project Reactor](reactor.md)).

<!-- snippet: micronaut-reactive -->
```java
@Singleton
public class PriceService {

    private final PriceBackend backend;

    public PriceService(PriceBackend backend) {
        this.backend = backend;
    }

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

Em um controller, retorne o `Mono` do bean como está. O Micronaut escreve server-sent events a partir de `Event<T>`, então mapeie os elementos de um `Flux` para eventos:

<!-- snippet: micronaut-reactive-endpoint -->
```java
@Get(uri = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM)
Flux<Event<String>> ticks(String sku) {
    return quotes.ticks(sku).map(Event::of);   // Micronaut frames server-sent events from Event<T>
}
```

- **Uma falha de um `Mono`** passa pelos handlers `@Error` do controller como qualquer outra (um `CallTimeoutException` mapeado para 504, por exemplo). Um stream de server-sent events que falha antes do seu primeiro evento é respondido com 500 pelo Micronaut, quaisquer que sejam os handlers; depois do primeiro evento a resposta já começou, então uma falha encerra o stream.
- **Um cliente que se desconecta** cancela uma chamada `Mono`, ou um stream que já enviou um evento, através de todas as políticas: o `Mono` ou o `Flux` do delegate é cancelado e o bulkhead libera sua vaga. Antes do primeiro evento de um stream o Micronaut não percebe a desconexão, então a chamada continua até o seu `@Timeout`: dê um aos métodos de streaming.

Um `@Transactional` do Micronaut em volta de um método `Mono` ou `Flux` com retry de uma classe anotada faz a inicialização falhar, como para os outros métodos ([R4C-MN-005](../errors.md#r4c-mn-005)).

## Configuração :id=configuration

As políticas leem a configuração da aplicação sob `resilience4cata`, com as chaves da
[referência de configuração](../reference/configuration.md) em kebab-case ou camelCase:

<!-- snippet: micronaut-yaml -->
```yaml
resilience4cata:
  defaults:
    retry:
      delay: 50ms
    circuit-breaker:
      minimum-calls: 20
  configs:
    slow:
      retry.delay: 500ms
  instances:
    orders:
      circuit-breaker.failure-rate-threshold: 25
    '"dev.resilience4cata.micronaut.guide.StockClient#available(java.lang.String)"':
      timeout.value: 3s
```

- **Ids de instância** com algo além de letras, dígitos, `_` e `-` (o derivado `<type>#<method>(<parameter types>)`)
  contêm pontos, que o Micronaut leria como aninhamento: escreva o id entre aspas duplas dentro da chave YAML, como
  acima (`'"com.acme.StockClient#available(java.lang.String)"'`), ou dê um `name` à política.
- **Listas** (`retry-on`, `abort-on`, `record-on`, `ignore-on`) podem ser listas YAML ou valores separados por vírgula.
- **Placeholders** (`${…}`) são resolvidos.
- **A precedência é a do Micronaut**: linha de comando acima de propriedades de sistema acima de variáveis de ambiente acima de
  `application-<environment>.yml` acima de `application.yml`. As variáveis de ambiente usam os
  [nomes `R4C_` ou `RESILIENCE4CATA_`](configuration.md) de `resilience4cata-config`, lidos como estão em vez de
  passar pelo mapeamento do Micronaut. Um arquivo `resilience4cata.properties` no class path continua sendo lido, abaixo de todos
  eles.
- **Ambientes**: os do Micronaut (`application-prod.yml`) são o caminho natural; as próprias chaves
  `profiles.<name>.*` do resilience4cata também continuam funcionando, separadamente.
- A configuração é lida uma vez, na inicialização, como em todo o resto do resilience4cata: não há refresh, e os
  beans não são `@Refreshable`.

**Validação na inicialização.** A configuração e o registry são beans `@Context`, então uma chave desconhecida ou um valor
inválido para a inicialização com todos os problemas de uma vez, nomeando o arquivo onde foi definido (o Micronaut não guarda números
de linha):

```text
Bean definition [dev.resilience4cata.config.ResilienceConfig] could not be loaded: Error instantiating bean of type [dev.resilience4cata.config.ResilienceConfig]

Message: Invalid resilience4cata configuration (2 problems):
  [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts' (application.yml): ... Did you mean 'resilience4cata.defaults.retry.max-attempts'?
  [R4C-CFG-002] invalid value 'soon' for 'resilience4cata.defaults.retry.delay' (application.yml): ...
```

Na inicialização, cada chave `resilience4cata.instances.<id>.*` deve corresponder a uma instância de política
([`R4C-CFG-013`](../errors.md#r4c-cfg-013)). O Micronaut cria singletons de forma preguiçosa por padrão, então as instâncias da
maioria dos beans ainda não existem nesse momento: uma chave sem correspondência é apenas registrada como aviso, que pode ser um alarme falso. Quando
o contexto cria seus singletons de forma antecipada (`eagerInitSingletons(true)`), as instâncias dos singletons existem e uma
chave sem correspondência para a inicialização, a menos que a aplicação tenha beans protegidos que não são singletons
(`@Prototype`, `@Refreshable`, escopo de requisição), cujas instâncias aparecem quando são criados: então continua sendo um
aviso. `resilience4cata.micronaut.verify-instances` (`fail`, `warn`, `off`) sobrescreve tudo isso.

## Personalizando o registry :id=beans

Personalize o builder do registry do jeito do Micronaut, com um `BeanCreatedEventListener`, por exemplo uma config nomeada
no código:

<!-- snippet: micronaut-customizer -->
```java
@Singleton
BeanCreatedEventListener<ResilienceRegistry.Builder> slowBackends() {
    return event -> event.getBean().retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
}
```

Os listeners rodam em `@Order`, antes da configuração, então a configuração vence: `configs.slow.retry.delay` acima
sobrescreve o delay do listener e mantém seu `maxAttempts`. Uma chave `defaults.<policy>.*` substitui o
`<policy>Defaults(...)` de um listener para essa política.

Todo bean `EventListener` é registrado para o tipo de evento da sua assinatura genérica (um listener raw recebe todos os
eventos):

<!-- snippet: micronaut-listener -->
```java
@Singleton
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.id() + " is now " + transition.to());
}
```

Os listeners rodam na thread que causou o evento ([eventos](observability.md#events)). Um bean listener não pode
depender de um bean protegido: o registry precisa do listener, e o bean protegido precisa do registry.

Um bean `Ticker` (o `ManualClock` do testkit) se torna o relógio do registry. Para construir o registry você mesmo,
declare um bean com `@Replaces(ResilienceRegistry.class)`; o bean `ResilienceConfig` continua lá para aplicar
a configuração com `config.applyTo(builder)`.

## Métricas, health e o endpoint :id=management

- **Métricas**: com `resilience4cata-micrometer` no class path e um bean `MeterRegistry` (de
  `micronaut-micrometer-core`), os [medidores do Micrometer](micrometer.md) são registrados automaticamente. As chamadas
  bem-sucedidas não publicam eventos, então não custam nada.
- **Health**: com `micronaut-management`, o health indicator `resilience4cata` reporta cada circuit breaker
  (estado, taxas de falhas e de chamadas lentas, chamadas na janela). Ele continua **UP** com breakers abertos: um breaker aberto
  é a aplicação se protegendo de uma dependência que falha, não um motivo para reiniciá-la ou tirá-la do
  balanceamento de carga. Liste os breakers que devem torná-lo DOWN (UNKNOWN enquanto half-open) em
  `resilience4cata.micronaut.health.down-when-open` (`*` para todos). Ele não é nem `@Liveness` nem `@Readiness`, então
  conta apenas em `/health`.
- **Endpoint**: `GET /resilience4cata` retorna `registry.snapshot()` como JSON ([seu formato](observability.md#snapshots)), e `GET /resilience4cata/{instance}`
  as políticas de um id de instância. Ele é somente leitura e não guarda histórico de eventos. Como os próprios endpoints do Micronaut, ele
  é sensível por padrão: configure-o com `endpoints.resilience4cata.enabled` e
  `endpoints.resilience4cata.sensitive`.

### OpenTelemetry :id=opentelemetry

O módulo faz o bind apenas do Micrometer. Para exportar as [métricas do OpenTelemetry](opentelemetry.md) em vez disso, adicione
`dev.resilience4cata:resilience4cata-opentelemetry` e estes beans a uma `@Factory`, ao lado do seu bean
`OpenTelemetry`:

<!-- snippet: micronaut-opentelemetry -->
```java
@Singleton
@Bean(preDestroy = "close")
OpenTelemetryMetrics resilienceMetrics(OpenTelemetry openTelemetry) {
    return OpenTelemetryMetrics.create(openTelemetry);
}

@Singleton
BeanCreatedEventListener<ResilienceRegistry.Builder> resilienceCounters(OpenTelemetryMetrics metrics) {
    return event -> metrics.register(event.getBean()); // the counters' listener, before the registry is built
}

@Singleton
BeanCreatedEventListener<ResilienceRegistry> resilienceGauges(OpenTelemetryMetrics metrics) {
    return event -> {
        metrics.bindTo(event.getBean()); // the gauges, read at each collection
        return event.getBean();
    };
}
```

O primeiro listener registra o listener dos contadores no builder antes de o registry ser construído, o segundo faz o bind
dos gauges quando o registry é criado, e `preDestroy = "close"` remove os gauges quando o contexto para.
Com o Micrometer também no class path, defina `resilience4cata.micronaut.metrics.enabled=false` para que os eventos não
sejam contados duas vezes.

## Chaves de ativação :id=switches

| Propriedade | Padrão | Efeito |
|---|---|---|
| `resilience4cata.micronaut.verify-instances` | `warn` (`fail` com singletons antecipados) | `fail`, `warn` ou `off` para chaves de instância que não correspondem a nenhuma instância na inicialização |
| `resilience4cata.micronaut.exclude` | vazio | Nomes de classe totalmente qualificados de beans a deixar de lado: não são envolvidos, e R4C-MN-004 e R4C-MN-005 não são verificados. Ao contrário do `exclude` do Spring e do Quarkus, ele não consegue impedir que uma classe anotada seja substituída por sua subclasse gerada quando é compilada com `-Ar4c.inject=micronaut` (o Micronaut escolhe as definições de bean antes de ler a configuração). Para executar uma classe sem suas políticas em qualquer framework, desligue-as: `resilience4cata.instances.<id>.<policy>.enabled=false` |
| `resilience4cata.micronaut.allow-retry-inside-transaction` | `false` | Permite `@Retry`/`@Hedge` em métodos transacionais de classes anotadas (uma transação para todas as tentativas) em vez de falhar com R4C-MN-005 |
| `resilience4cata.micronaut.allow-missing-processor` | `false` | Cria um bean de uma classe anotada que não tem subclasse gerada (suas políticas não rodam) em vez de falhar com R4C-MN-007 |
| `resilience4cata.micronaut.health.enabled` | `true` | O health indicator |
| `resilience4cata.micronaut.health.down-when-open` | vazio | Ids de breakers (ou `*`) que tornam o health DOWN enquanto abertos |
| `resilience4cata.micronaut.metrics.enabled` | `true` | Registra os medidores do Micrometer |

## Testes :id=testing

Dê ao contexto de teste um bean `ManualClock`: as pausas do retry, as esperas do bulkhead e do rate limiter e os timeouts o seguem,
então um teste nunca dorme ([guia de testes](testing.md)).

<!-- snippet: micronaut-test-clock -->
```java
@Factory
public class Clocks {

    @Singleton
    ManualClock manualClock() {
        return new ManualClock(); // retry pauses, waits and timeouts follow it instead of the system clock
    }
}
```

<!-- snippet: micronaut-test -->
```java
@Inject
OrderService orders;

@Inject
ManualClock clock; // the @Factory above, in src/test

@Inject
OrderRepository repository; // fails the first loads it is told to

@Test
void retriesWithoutSleeping() {
    repository.failures.set(2);

    assertThat(orders.find("42")).isEqualTo(new Order("42"));
    assertThat(clock.elapsed()).isPositive(); // the two pauses, taken on the manual clock
}
```

A classe de teste é anotada com `@MicronautTest` (de `micronaut-test-junit5`).

## Native images :id=native

Nada a adicionar para o resilience4cata:

- o processador de anotações escreve metadados de alcançabilidade do GraalVM para cada classe gerada
  (`META-INF/native-image/dev.resilience4cata.generated/…`), o que cobre `Resilient.of` para os beans
  `@ResilientBean`;
- com `-Ar4c.inject=micronaut`, as próprias definições de bean do Micronaut criam as subclasses geradas, sem
  reflexão;
- o endpoint serve `ResilienceSnapshot.toMap()`, que o core constrói sem reflexão.

O CI do projeto constrói uma native image GraalVM 25 de uma pequena aplicação (uma classe anotada, uma interface `@ResilientBean`
com fallback, configuração YAML, health, métricas do Micrometer e o endpoint sobre Netty, e métodos `Mono` e
`Flux` dos dois estilos) com o plugin Gradle do GraalVM e o processador de anotações `micronaut-graal` do Micronaut,
e roda seus testes dentro da imagem. Construa a sua com o plugin Gradle ou Maven do Micronaut, que também coloca
`application.yml` na imagem. As classes de exceção nomeadas na configuração externa (`retry-on: com.acme.Busy`) são
carregadas pelo nome: nomeie-as nas anotações, ou registre-as para reflexão. A
verificação de retry dentro de transação (`R4C-MN-005`) lê arquivos de classe, então uma native image a pula: ela roda no build e
nos testes na JVM. Server-sent events em uma native image precisam de uma entrada de reflexão que falta nos metadados do Netty, sem relação
com o resilience4cata: a primeira resposta chunked falhou com `Can't find '[touch]' in io.netty.util.ReferenceCountUtil`
até a aplicação registrar os métodos declarados dessa classe (`"allDeclaredMethods": true` no seu
`reachability-metadata.json`).

> [!MEASURED]
> Na native image dessa aplicação, o resilience4cata adiciona 1.9 MB de imagem, 5 ms de inicialização (32 ms contra 27 ms até
> a primeira requisição respondida) e 3–5 MB de RSS ([Footprint da native image](../benchmarks.md#native-footprint)).

## Limites :id=limits

- O Micronaut tem seus próprios `@Retryable`, `@CircuitBreaker` e `@Fallback` em `io.micronaut.retry.annotation`: verifique
  os imports, uma IDE pode escolher o errado. Não os combine com os do resilience4cata em um mesmo método.
- Interfaces anotadas: injete a interface; as chamadas em `this` dentro da implementação não são protegidas; uma
  interface anotada por `@ResilientBean`.
- As classes anotadas precisam de `-Ar4c.inject=micronaut` para serem criadas pelo Micronaut, ou de uma factory. Apenas a própria
  definição de bean da classe fica de fora: os beans de subclasses, os beans da classe vindos de métodos `@Factory` e o `@MockBean` de um teste
  continuam. Classes `@Factory` não podem ser anotadas.
- Mantenha as políticas fora das classes de configuração (`@ConfigurationProperties`, `@EachProperty`). O processador do Micronaut escreve
  seus metadados de configuração uma vez por rodada de compilação, e ele vê a subclasse gerada uma rodada depois, então
  avisa `Error occurred writing configuration metadata`, o que faz um build com `-Werror` falhar. Coloque a configuração em sua
  própria classe e as políticas em um bean que a recebe: `@EachBean(BackendConfig.class)` dá um bean protegido
  por entrada `@EachProperty`.
- Com classes anotadas, a AOP do Micronaut roda fora das políticas; um método transacional com retry ou hedge falha
  ([`R4C-MN-005`](../errors.md#r4c-mn-005)) a menos que seja permitido.
- Os métodos que o construtor chama em `this` rodam antes de as políticas serem configuradas
  ([classes anotadas](annotated-classes.md)).
- Nenhum histórico de eventos no endpoint, nenhuma operação de escrita (`forceOpen`, `reset`) nele, nenhum refresh da
  configuração. O Micronaut 4 e o Java 21 não são suportados por este módulo.

**Comparado com `micronaut-retry` e resilience4j-micronaut.** Ambos funcionam através da AOP `@Around` do Micronaut: toda
chamada com advice passa por uma cadeia de interceptadores de método em tempo de execução, e a configuração é buscada por método.
As políticas do resilience4cata são código gerado que o Micronaut apenas instancia. Nenhum benchmark dos três dentro do
Micronaut foi executado, então nenhum número é dado aqui; veja [desempenho](../performance.md) para o que foi
medido.
