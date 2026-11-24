# Spring Boot

Use o resilience4cata em uma aplicação Spring Boot 4: beans anotados são protegidos pelo código gerado, o
registry é configurado a partir do `application.yml`, e o Actuator ganha um health indicator, um endpoint e métricas.

O módulo `resilience4cata-spring` (disponível desde 0.4.0) é uma auto-configuração do Spring Boot. Ele não adiciona
nada ao caminho da chamada: não há proxies, aspectos nem interceptors. Um bean protegido *é* a classe que o
annotation processor gerou, então uma chamada custa o que custaria sem o Spring.

**Requisitos:** Spring Boot 4.1 ou mais recente (testado na 4.1.0, a mais antiga suportada, e na versão 4.1
mais recente) e Java 21. O Spring Boot 3 não é suportado.

## Instalação :id=install

Adicione o módulo e, como em qualquer uso das anotações, o annotation processor
([instalação](../start/installation.md)). O módulo traz `resilience4cata-core` e
`resilience4cata-config`.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:1.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:1.1.0')

    implementation 'dev.resilience4cata:resilience4cata-spring'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-micrometer'   // optional: metrics
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'  // optional: ManualClock
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:1.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-spring")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-micrometer")   // optional: metrics
    testImplementation("dev.resilience4cata:resilience4cata-testkit")  // optional: ManualClock
}
```

#### **Maven**

```xml
<!-- with resilience4cata-bom imported in <dependencyManagement> -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-spring</artifactId>
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
    <version>1.1.0</version>
  </path>
  <!-- annotationProcessorPaths replaces discovery: list the other processors you use too,
       for example Spring Boot's configuration processor if you have @ConfigurationProperties.
       Its version comes from Spring Boot's dependency management (maven-compiler-plugin 3.12+;
       older versions of the plugin need a <version>). -->
  <path>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

> Sem o annotation processor nada é gerado, então a inicialização falha com
> [`R4C-SPR-006`](../errors.md#r4c-spr-006): um bean cuja classe ou interface tem anotações do resilience4cata mas nenhuma
> classe gerada rodaria sem proteção. A verificação lê os class files dos tipos dos beans (as anotações têm
> retenção `CLASS`), então ela não roda em uma native image, onde o build já compilou tudo.
> `resilience4cata.spring.allow-missing-processor=true` a desliga.

## Classes anotadas :id=classes

Anote a própria classe. Este é o estilo recomendado no Spring:

<!-- snippet: spring-class -->
```java
@Service
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

O processor gera `OrderService$Resilient extends OrderService`, e a integração faz o Spring
instanciá-la sempre que uma bean definition nomeia `OrderService` (component scanning, `@Import`,
`registerBean`). Assim:

- o bean continua sendo um `OrderService`: ele é injetado pela sua classe, suas interfaces, `@Qualifier`, `List<…>`, e
  mantém seu nome, escopo e `@Primary` (no escopo de request ou de sessão, cada request ou sessão recebe sua própria
  instância da classe gerada, e todas compartilham as policies nomeadas);
- chamadas em `this` (`findAgain` chamando `find`) passam pelas policies, ao contrário do Spring AOP e do próprio
  `@Retryable` do Spring;
- a injeção por construtor funciona como antes: os construtores gerados recebem o `ResilienceRegistry` (injetado por
  tipo) seguido dos parâmetros originais, com seus nomes e anotações (`@Autowired` em um de vários
  construtores, `@Qualifier`, `@Value`; veja [classes anotadas](annotated-classes.md#constructor-injection)).
  A injeção por campo e por setter e o `@PostConstruct` são herdados.

Um método `@Bean` que devolve `new OrderService(...)` produziria um bean sem proteção, então a inicialização falha
com [`R4C-SPR-001`](../errors.md#r4c-spr-001): registre a classe em vez disso, ou devolva
`new OrderService$Resilient(registry, ...)` a partir do método.

**Proxies sobre a classe.** A classe gerada não é `final`, então o Spring ainda pode colocar um proxy baseado em classe
em volta dela (`@Transactional`, `@Cacheable`, `@Async`, beans com escopo). O proxy fica fora das policies. Para
transações, isso significaria uma única transação para todas as tentativas, e depois que uma tentativa falha a marca
como rollback-only, uma tentativa posterior bem-sucedida falha ao fazer commit com `UnexpectedRollbackException`. Por isso, um método que
tem retry ou hedge e é transacional ao mesmo tempo (`@Transactional` do Spring ou do Jakarta, com uma propagação que
se junta a uma transação ou inicia uma) **interrompe a inicialização** com [`R4C-SPR-004`](../errors.md#r4c-spr-004). Corrija
declarando a policy em uma interface que o bean implementa (o wrapper da interface fica fora do
proxy de transação: uma transação por tentativa), ou movendo o `@Transactional` para outro bean que o
método com retry chama. Se uma única transação compartilhada é o que você quer, defina
`resilience4cata.spring.allow-retry-inside-transaction=true`. `@CircuitBreaker`, `@Timeout`, `@RateLimiter`
e `@Bulkhead` executam a chamada uma vez e não são verificados. O override gerado repete as anotações do
método que ele sobrescreve, então um `@Transactional` do Jakarta no nível do método (que o Spring lê apenas no método invocado)
se aplica e é verificado como o do Spring.

## Interfaces anotadas :id=interfaces

<!-- snippet: spring-interface -->
```java
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface StockClient {

    int available(String sku);
}
```

<!-- snippet: spring-interface-bean -->
```java
@Component
public class HttpStockClient implements StockClient {

    @Override
    public int available(String sku) {
        return 42; // an HTTP call in a real application
    }
}
```

Um bean que implementa uma interface anotada é substituído, uma vez inicializado, por
`Resilient.of(StockClient.class, bean, registry)`. Injete a interface:

<!-- snippet: spring-inject-interface -->
```java
@Component
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

- O tipo do bean passa a ser o wrapper, que implementa apenas `StockClient`: injetar `HttpStockClient` falha,
  como com proxies JDK, e o relatório de inicialização diz para injetar a interface.
- O wrapper é aplicado depois de todos os outros post-processors, então ele envolve os proxies deles: uma chamada com retry através de um
  proxy `@Transactional` recebe uma nova transação por tentativa.
- Chamadas em `this` dentro de `HttpStockClient` não são protegidas (o wrapper fica fora do objeto).
- Um bean com escopo de request ou de sessão é envolvido uma vez, em volta do seu scoped proxy: cada chamada chega à
  instância do request ou da sessão atual.
- Um bean que implementa duas interfaces anotadas, sem que nenhuma estenda a outra, faz a inicialização falhar com
  [`R4C-SPR-003`](../errors.md#r4c-spr-003). Um bean que já é uma classe gerada (um método `@Bean` que
  devolve `Resilient.of(...)` ele mesmo) é deixado como está.
- Um bean do qual um `BeanPostProcessor` da aplicação depende é criado antes que o post-processor do wrapper
  exista, então ficaria sem envolver: a inicialização falha com [`R4C-SPR-005`](../errors.md#r4c-spr-005). Injete-o
  nesse post-processor através de `ObjectProvider<StockClient>` ou `@Lazy`. Classes anotadas não são afetadas.

## Beans reativos :id=reactive

Beans cujos métodos devolvem `Mono` ou `Flux` do Reactor são protegidos da mesma forma, tanto classes anotadas quanto interfaces, com `resilience4cata-reactor` no class path (ele não faz parte de `resilience4cata-all`: [instalação](../start/installation.md)). Cada assinatura é uma chamada através das policies; para um `Flux`, o retry e o fallback atuam apenas antes do primeiro elemento, e `@Timeout` é o tempo sem elemento ([Project Reactor](reactor.md)).

<!-- snippet: spring-reactive-bean -->
```java
@Retry(name = "ticks", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
@Timeout("300ms")
@Bulkhead(name = "ticks", maxConcurrent = 1)
public Flux<String> ticks(String sku) {
    return backend.ticks(sku);
}
```

No WebFlux, devolva o `Mono` ou o `Flux` do bean a partir do controller como ele é; um `Flux` pode ser transmitido como server-sent events:

<!-- snippet: spring-reactive-endpoint -->
```java
@GetMapping(value = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
Flux<String> ticks(@PathVariable String sku) {
    return quotes.ticks(sku);   // the bean's Flux as it is: server-sent events
}
```

- **O contexto do Reactor** que um `WebFilter` escreve (tracing, o `ReactiveSecurityContextHolder` do Spring Security, um id de request) chega a cada tentativa e ao fallback.
- **Uma falha antes do primeiro elemento** passa pelos seus `@ExceptionHandler`s como qualquer outra (uma `CallTimeoutException` mapeada para 504, por exemplo). Depois do primeiro elemento de um stream a resposta já começou, então uma falha encerra o stream.
- **Um cliente que se desconecta** cancela a chamada através de cada policy: o `Mono` ou `Flux` do delegate é cancelado e o bulkhead libera sua vaga.
- **Um `@Transactional` reativo** em volta de um método `Mono` ou `Flux` com retry ou hedge de uma classe anotada faz a inicialização falhar, como para outros métodos ([R4C-SPR-004](../errors.md#r4c-spr-004)).

## Configuração :id=configuration

As policies leem as propriedades da aplicação sob `resilience4cata`, com as chaves da
[referência de configuração](../reference/configuration.md) em kebab-case ou camelCase:

<!-- snippet: spring-yaml -->
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
    "[dev.resilience4cata.spring.guide.StockClient#available(java.lang.String)]":
      timeout.value: 3s
```

- **Ids de instância** com algo além de letras, dígitos, `_` e `-` (o derivado `<type>#<method>(<parameter types>)`)
  vão entre colchetes, como o Spring escreve chaves de mapa: `"[com.acme.StockClient#available(java.lang.String)]"` em YAML,
  `resilience4cata.instances[com.acme.StockClient#available(java.lang.String)].timeout.value=3s` em
  `.properties`.
- **Listas** (`retry-on`, `abort-on`, `record-on`, `ignore-on`) podem ser listas YAML ou valores separados por vírgula.
- **Placeholders** (`${…}`) são resolvidos.
- **A precedência é a do Spring**: argumentos de linha de comando e propriedades de teste acima de propriedades de sistema, acima de variáveis
  de ambiente, acima de `application-<profile>.yml`, acima de `application.yml`. As variáveis de ambiente usam os
  [nomes `R4C_` ou `RESILIENCE4CATA_`](configuration.md) do `resilience4cata-config`. Um arquivo
  `resilience4cata.properties` no class path continua sendo lido, abaixo de todos eles.
- **Profiles**: documentos de profile do Spring (`application-prod.yml`, `spring.config.activate.on-profile`) são
  o caminho natural; as próprias chaves `profiles.<name>.*` do resilience4cata também continuam funcionando
  (`resilience4cata.profiles.active: ${spring.profiles.active:}` as une).
- A configuração é lida uma vez, na inicialização, como em todo o resto do resilience4cata: não há refresh.

**Validação na inicialização.** Uma chave desconhecida ou um valor inválido interrompe a inicialização com todos os problemas de uma vez, com
o arquivo e a linha onde foi definido:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Invalid resilience4cata configuration (1 problem):

    [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts' (class path resource [application.yml] - 4:20). Did you mean 'resilience4cata.defaults.retry.max-attempts'? ...
```

Uma vez que os singletons existem, toda chave `resilience4cata.instances.<id>.*` deve corresponder a uma instância de policy, ou a
inicialização falha com [`R4C-CFG-013`](../errors.md#r4c-cfg-013). Com beans lazy ou pipelines construídos depois, defina
`resilience4cata.spring.verify-instances=warn` (o padrão com `spring.main.lazy-initialization=true`).

O módulo traz metadados de configuração do Spring Boot para as chaves `defaults` e os switches abaixo, que as IDEs
que os leem usam para completá-los e documentá-los.

## Customizers e listeners :id=beans

Um bean `ResilienceRegistryCustomizer` ajusta o builder do registry, por exemplo uma config nomeada em código:

<!-- snippet: spring-customizer -->
```java
@Bean
ResilienceRegistryCustomizer slowBackends() {
    return builder -> builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
}
```

Os customizers rodam em `@Order`, antes das propriedades, então as propriedades prevalecem: `configs.slow.retry.delay` acima
sobrescreve o delay do customizer e mantém seu `maxAttempts`. Uma chave `defaults.<policy>.*` substitui o
`<policy>Defaults(...)` de um customizer para essa policy.

Todo bean `EventListener` é registrado para o tipo de evento da sua assinatura genérica (um listener raw recebe todos os
eventos):

<!-- snippet: spring-listener -->
```java
@Bean
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.id() + " is now " + transition.to());
}
```

Os listeners rodam na thread que causou o evento ([eventos](observability.md#events)). Um bean listener não deve
depender de um bean protegido: o registry precisa do listener, e o bean protegido precisa do registry.

Um bean `Ticker` (o `ManualClock` do testkit) se torna o relógio do registry. Uma aplicação que define seu próprio
bean `ResilienceRegistry` substitui o auto-configurado (customizers e beans listener então não são
aplicados); o bean `ResilienceConfig` continua lá para construí-lo com `config.applyTo(builder)`.

## Métricas, health e o endpoint :id=actuator

- **Métricas**: com `resilience4cata-micrometer` no class path e um bean `MeterRegistry` (o do Spring Boot
  Actuator), os [meters do Micrometer](micrometer.md) são registrados automaticamente. Chamadas bem-sucedidas não publicam
  eventos, então não custam nada. Não há observações por chamada (`ObservationRegistry`): uma por chamada
  alocaria e leria o relógio em cada sucesso.
- **Health**: o health indicator `resilience4cata` reporta cada circuit breaker (estado, taxas de falha e de chamadas
  lentas, chamadas na janela). Ele fica **UP** com breakers abertos: um breaker aberto é a aplicação se protegendo
  de uma dependência com falhas, não um motivo para reiniciá-la ou tirá-la do balanceamento de carga. Liste os
  breakers que devem deixá-lo DOWN (UNKNOWN enquanto half-open) em
  `resilience4cata.spring.health.down-when-open` (`*` para todos). Ele não faz parte dos grupos de liveness ou readiness
  a menos que você o adicione.
- **Endpoint**: `GET /actuator/resilience4cata` devolve `registry.snapshot()` como JSON ([seu formato](observability.md#snapshots)), e
  `GET /actuator/resilience4cata/{instance}` as policies de um id de instância. Ele é somente leitura e não mantém histórico de
  eventos. Exponha-o como qualquer endpoint: `management.endpoints.web.exposure.include=health,resilience4cata`.

### OpenTelemetry :id=opentelemetry

A auto-configuração vincula apenas o Micrometer. Para exportar as [métricas do OpenTelemetry](opentelemetry.md) em vez disso,
adicione `dev.resilience4cata:resilience4cata-opentelemetry` e três beans ao lado do seu bean `OpenTelemetry`:

<!-- snippet: spring-opentelemetry -->
```java
@Bean
OpenTelemetryMetrics resilienceMetrics(OpenTelemetry openTelemetry) {
    return OpenTelemetryMetrics.create(openTelemetry); // closed with the context
}

@Bean
ResilienceRegistryCustomizer resilienceCounters(OpenTelemetryMetrics metrics) {
    return metrics::register; // the counters' listener, before the registry is built
}

@Bean
SmartInitializingSingleton resilienceGauges(OpenTelemetryMetrics metrics, ResilienceRegistry registry) {
    return () -> metrics.bindTo(registry); // the gauges, read at each collection
}
```

O customizer registra o listener dos contadores antes de o registry ser construído, os gauges são vinculados quando todo
singleton existe, e o bean de métricas é fechado junto com o contexto, o que remove os gauges. Com o Micrometer também no
class path, defina `resilience4cata.spring.metrics.enabled=false` para que os eventos não sejam contados duas vezes.

## Switches :id=switches

| Propriedade | Padrão | Efeito |
|---|---|---|
| `resilience4cata.spring.wrap` | `true` | Substituir beans de interfaces anotadas pelo seu wrapper |
| `resilience4cata.spring.exclude` | vazio | Nomes de beans a deixar em paz: sem subclasse, sem wrapper, sem verificações |
| `resilience4cata.spring.allow-retry-inside-transaction` | `false` | Permitir `@Retry`/`@Hedge` em métodos transacionais de classes anotadas (uma transação para todas as tentativas) em vez de falhar com R4C-SPR-004 |
| `resilience4cata.spring.allow-missing-processor` | `false` | Iniciar mesmo quando um bean tem anotações do resilience4cata mas nenhuma classe gerada (suas policies não rodam) em vez de falhar com R4C-SPR-006 |
| `resilience4cata.spring.verify-instances` | `fail` (`warn` com inicialização lazy) | `fail`, `warn` ou `off` para chaves de instância que não correspondem a nada |
| `resilience4cata.spring.health.down-when-open` | vazio | Ids de breakers (ou `*`) que deixam o health DOWN enquanto abertos |
| `resilience4cata.spring.metrics.enabled` | `true` | Registrar os meters do Micrometer |
| `management.health.resilience4cata.enabled` | `true` | O health indicator |

## Testes :id=testing

Declare um bean `ManualClock`: pausas de retry, esperas do bulkhead e do rate limiter e timeouts o seguem, então um teste
nunca dorme ([guia de testes](testing.md)).

<!-- snippet: spring-test -->
```java
@TestConfiguration
static class TestClock {

    @Bean
    ManualClock clock() {
        return new ManualClock(); // retry pauses, waits and timeouts follow it instead of the system clock
    }
}

@Autowired
OrderService orders;

@Autowired
ManualClock clock;

@Autowired
OrderRepository repository; // fails the first loads it is told to

@Test
void retriesWithoutSleeping() {
    repository.failures.set(2);

    assertThat(orders.find("42")).isEqualTo(new Order("42"));
    assertThat(clock.elapsed()).isPositive(); // the two pauses, taken on the manual clock
}
```

Dublês de teste de beans protegidos:

- `@MockitoBean` substitui o bean pelo mock puro, sem as policies, para uma interface ou classe anotada.
- `@MockitoSpyBean` em uma interface anotada também injeta o spy puro, então `verify(spy)` funciona; em uma classe
  anotada o spy mantém as policies.
- Um contexto de teste compartilhado por várias classes de teste compartilha um registry, então um breaker aberto continua aberto entre elas:
  reinicie-o com `registry.circuitBreaker(id).reset()`, ou use `@DirtiesContext`.

## AOT e native images :id=aot

O Spring AOT (a etapa de processamento de um build de native image, ou de uma aplicação JVM otimizada por AOT) funciona:

- a troca de classes anotadas acontece enquanto as bean definitions são processadas, então o código gerado chama
  `new OrderService$Resilient(...)` diretamente, e o post-processor que as trocou fica de fora do
  contexto otimizado;
- beans de interface continuam sendo envolvidos em tempo de execução com `Resilient.of`, que encontra `StockClient$Resilient` pelo
  nome. O annotation processor escreve metadados de alcançabilidade do GraalVM para cada classe gerada
  (`META-INF/native-image/dev.resilience4cata.generated/…`), então nenhum runtime hint do Spring é necessário;
- as propriedades são lidas em tempo de execução, mas as condições são fixadas em tempo de build, como em todo o Spring AOT:
  `resilience4cata.spring.metrics.enabled`, `management.health.resilience4cata.enabled`, a exposição do
  endpoint, e `resilience4cata.spring.exclude` para classes anotadas.

O Spring Boot 4 precisa do GraalVM 25 ou mais recente para native images. A CI do projeto constrói uma native image de uma pequena
aplicação (uma classe anotada, uma interface envolvida com um fallback, configuração YAML, o health do Actuator, métricas
e o endpoint do resilience4cata sobre Tomcat, e métodos `Mono` e `Flux` dos dois estilos servidos pelo Spring MVC) com
os plugins Gradle do Spring Boot e do GraalVM, e roda seus testes dentro da imagem.

> [!MEASURED]
> Na native image dessa aplicação, o resilience4cata adiciona 1.9 MB de imagem, 11 ms de inicialização (78 ms contra 67 ms
> até a primeira requisição respondida) e 9–11 MB de RSS ([Footprint da native image](../benchmarks.md#native-footprint)).

## Limites :id=limits

- Interfaces anotadas mudam o tipo do bean para o wrapper: injete a interface. Chamadas em `this` dentro da
  implementação não são protegidas. Uma interface anotada por bean.
- Métodos `@Bean` devem devolver a própria classe gerada; uma classe `@Configuration` completa com métodos `@Bean`
  não pode ser anotada ([`R4C-SPR-002`](../errors.md#r4c-spr-002)).
- Com classes anotadas, proxies baseados em classe como `@Transactional` rodam fora das policies; um método transacional com retry ou
  hedge faz a inicialização falhar ([`R4C-SPR-004`](../errors.md#r4c-spr-004)) a menos que permitido. A
  verificação lê os class files, então uma native image a pula: ela roda no build e nos testes na JVM.
- Métodos que o construtor chama em `this` rodam antes de as policies serem configuradas
  ([classes anotadas](annotated-classes.md)).
- Sem observações por chamada, sem histórico de eventos no endpoint, sem hot reload da configuração.
- Os restarts do Spring Boot DevTools funcionam: cada restart usa as classes geradas do novo class loader de restart, e
  o trabalho que a biblioteca executa depois (a próxima tentativa de um retry, um timeout assíncrono) roda com esse loader. Um restart constrói um
  novo registry, então as policies recomeçam (breakers fechados, janelas vazias). Uma mudança nas anotações passa a valer
  quando o build que o DevTools observa roda o annotation processor de novo (o build do Gradle ou do Maven faz isso).
