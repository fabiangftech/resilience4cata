# Spring Boot

Usa resilience4cata en una aplicación Spring Boot 4: el código generado protege los beans anotados, el
registro se configura desde `application.yml` y Actuator obtiene un indicador de salud, un endpoint y métricas.

El módulo `resilience4cata-spring` (disponible desde 0.4.0) es una autoconfiguración de Spring Boot. No añade
nada al camino de la llamada: no hay proxies, aspectos ni interceptores. Un bean protegido *es* la clase que
generó el procesador de anotaciones, así que una llamada cuesta lo mismo que sin Spring.

**Requisitos:** Spring Boot 4.1 o posterior (el módulo se compila contra 4.1.0, y sus tests se ejecutan tanto en 4.1.0
como en 4.1.2) y Java 21.
Spring Boot 3 no está soportado.

## Instalación :id=install

Añade el módulo y, como para cualquier uso de las anotaciones, el procesador de anotaciones
([instalación](../start/installation.md)). El módulo trae `resilience4cata-core` y `resilience4cata-config`.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.12.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.12.0')

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
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.12.0")
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
    <version>0.12.0</version>
  </path>
  <!-- annotationProcessorPaths replaces discovery: list the other processors you use too -->
  <path>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

> Sin el procesador de anotaciones no se genera nada y los beans quedan sin protección: las anotaciones tienen
> retención `CLASS`, así que nada en tiempo de ejecución puede notar que están. `GET /actuator/resilience4cata`
> lista las instancias de políticas; una lista vacía significa que el procesador no se ejecutó.

## Clases anotadas :id=classes

Anota la clase misma. Es el estilo recomendado en Spring:

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

El procesador genera `OrderService$Resilient extends OrderService`, y la integración hace que Spring la
instancie dondequiera que una definición de bean nombre `OrderService` (escaneo de componentes, `@Import`,
`registerBean`). Así:

- el bean sigue siendo un `OrderService`: se inyecta por su clase, sus interfaces, `@Qualifier`, `List<…>`, y
  conserva su nombre, su ámbito y `@Primary` (en el ámbito de petición o de sesión, cada petición o sesión tiene
  su propia instancia de la clase generada, y todas comparten las políticas con nombre);
- las llamadas sobre `this` (`findAgain` llamando a `find`) pasan por las políticas, a diferencia de Spring AOP y
  del propio `@Retryable` de Spring;
- la inyección por constructor funciona como antes: los constructores generados reciben el `ResilienceRegistry`
  (inyectado por tipo) seguido de los parámetros originales, con sus nombres y anotaciones (`@Autowired` en uno de
  varios constructores, `@Qualifier`, `@Value`; ver [clases anotadas](annotated-classes.md#constructor-injection)).
  La inyección por campo y por setter y `@PostConstruct` se heredan.

Un método `@Bean` que devuelve `new OrderService(...)` produciría un bean sin protección, así que el arranque
falla con [`R4C-SPR-001`](../errors.md#r4c-spr-001): registra la clase en su lugar, o devuelve
`new OrderService$Resilient(registry, ...)` desde el método.

**Proxies sobre la clase.** La clase generada no es `final`, así que Spring aún puede poner un proxy basado en
clases alrededor (`@Transactional`, `@Cacheable`, `@Async`, beans con ámbito). El proxy queda fuera de las
políticas. Con transacciones eso supondría una sola transacción para todos los intentos, y cuando un intento
fallido la marca rollback-only, un intento posterior exitoso no puede hacer commit (`UnexpectedRollbackException`).
Por eso un método reintentado o con hedge que además es transaccional (`@Transactional` de Spring o de Jakarta, con
una propagación que se une a una transacción o la inicia) **detiene el arranque** con
[`R4C-SPR-004`](../errors.md#r4c-spr-004). Corrígelo declarando la política en una interfaz que implemente el bean
(el envoltorio de la interfaz queda fuera del proxy de transacciones: una transacción por intento), o moviendo
`@Transactional` a otro bean al que llame el método reintentado. Si lo que quieres es una sola transacción
compartida, pon `resilience4cata.spring.allow-retry-inside-transaction=true`. `@CircuitBreaker`, `@Timeout`,
`@RateLimiter` y `@Bulkhead` ejecutan la llamada una vez y no se comprueban. El override generado repite las
anotaciones del método que sobrescribe, así que un `@Transactional` de Jakarta a nivel de método (que Spring solo
lee en el método invocado) se aplica y se comprueba como el de Spring.

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

Un bean que implementa una interfaz anotada se reemplaza, una vez inicializado, por
`Resilient.of(StockClient.class, bean, registry)`. Inyecta la interfaz:

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

- El tipo del bean pasa a ser el envoltorio, que implementa solo `StockClient`: inyectar `HttpStockClient`
  falla, como con los proxies JDK, y el informe de arranque dice que inyectes la interfaz.
- El envoltorio se aplica después de todos los demás post-procesadores, así que envuelve sus proxies: una llamada
  reintentada a través de un proxy `@Transactional` obtiene una transacción nueva por intento.
- Las llamadas sobre `this` dentro de `HttpStockClient` no están protegidas (el envoltorio está fuera del objeto).
- Un bean con ámbito de petición o de sesión se envuelve una sola vez, alrededor de su proxy de ámbito: cada
  llamada llega a la instancia de la petición o la sesión en curso.
- Un bean que implementa dos interfaces anotadas, sin que una extienda a la otra, detiene el arranque con
  [`R4C-SPR-003`](../errors.md#r4c-spr-003). Un bean que ya es una clase generada (un método `@Bean` que devuelve
  él mismo `Resilient.of(...)`) se deja como está.
- Un bean del que depende un `BeanPostProcessor` de la aplicación se crea antes de que exista el post-procesador
  del envoltorio, así que quedaría sin envolver: el arranque se detiene con
  [`R4C-SPR-005`](../errors.md#r4c-spr-005). Inyéctalo en ese post-procesador con `ObjectProvider<StockClient>` o
  `@Lazy`. Las clases anotadas no se ven afectadas.

## Beans reactivos :id=reactive

Los beans cuyos métodos devuelven el `Mono` o el `Flux` de Reactor se protegen igual, tanto clases como interfaces anotadas, con `resilience4cata-reactor` en el classpath (no forma parte de `resilience4cata-all`: [instalación](../start/installation.md)). Cada suscripción es una llamada a través de las políticas; en un `Flux`, el retry y el fallback solo actúan antes del primer elemento, y `@Timeout` es el tiempo sin un elemento ([Project Reactor](reactor.md)).

<!-- snippet: spring-reactive-bean -->
```java
@Retry(name = "ticks", maxAttempts = 3, delay = "5ms", backoff = Backoff.FIXED, jitter = Jitter.NONE)
@Timeout("300ms")
@Bulkhead(name = "ticks", maxConcurrent = 1)
public Flux<String> ticks(String sku) {
    return backend.ticks(sku);
}
```

En WebFlux, devuelve el `Mono` o el `Flux` del bean desde el controller tal cual; un `Flux` se puede transmitir como server-sent events:

<!-- snippet: spring-reactive-endpoint -->
```java
@GetMapping(value = "/ticks/{sku}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
Flux<String> ticks(@PathVariable String sku) {
    return quotes.ticks(sku);   // the bean's Flux as it is: server-sent events
}
```

- **El contexto de Reactor** que escribe un `WebFilter` (tracing, el `ReactiveSecurityContextHolder` de Spring Security, un request id) llega a cada intento y al fallback.
- **Un fallo antes del primer elemento** pasa por tus `@ExceptionHandler` como cualquier otro (un `CallTimeoutException` mapeado a 504, por ejemplo). Después del primer elemento de un stream la respuesta ya empezó, así que un fallo termina el stream.
- **Un cliente que se desconecta** cancela la llamada a través de cada política: se cancela el `Mono` o el `Flux` del delegado y el bulkhead libera su lugar.
- **Un `@Transactional` reactivo** alrededor de un método `Mono` o `Flux` con retry o hedge de una clase anotada hace fallar el arranque, como en los demás métodos ([R4C-SPR-004](../errors.md#r4c-spr-004)).

## Configuración :id=configuration

Las políticas leen las propiedades de la aplicación bajo `resilience4cata`, con las claves de la
[referencia de configuración](../reference/configuration.md) en kebab-case o camelCase:

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

- Los **ids de instancia** con algo más que letras, dígitos, `_` y `-` (el derivado
  `<tipo>#<método>(<tipos de parámetros>)`) van entre corchetes, como Spring escribe las claves de mapas:
  `"[com.acme.StockClient#available(java.lang.String)]"` en YAML,
  `resilience4cata.instances[com.acme.StockClient#available(java.lang.String)].timeout.value=3s` en
  `.properties`.
- Las **listas** (`retry-on`, `abort-on`, `record-on`, `ignore-on`) pueden ser listas YAML o valores separados
  por comas.
- Los **placeholders** (`${…}`) se resuelven.
- **La precedencia es la de Spring**: argumentos de línea de comandos y propiedades de prueba sobre propiedades
  del sistema sobre variables de entorno sobre `application-<perfil>.yml` sobre `application.yml`. Las variables
  de entorno usan los [nombres `R4C_` o `RESILIENCE4CATA_`](configuration.md) de `resilience4cata-config`. Un
  fichero `resilience4cata.properties` en el class path se sigue leyendo, por debajo de todos ellos.
- **Perfiles**: los documentos de perfil de Spring (`application-prod.yml`, `spring.config.activate.on-profile`)
  son la vía natural; las claves `profiles.<nombre>.*` propias de resilience4cata siguen funcionando
  (`resilience4cata.profiles.active: ${spring.profiles.active:}` las une).
- La configuración se lee una vez, al arrancar, como en todo resilience4cata: no hay recarga.

**Validación al arrancar.** Una clave desconocida o un valor incorrecto detienen el arranque con todos los
problemas a la vez, con el fichero y la línea donde se fijaron:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Invalid resilience4cata configuration (1 problem):

    [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts' (class path resource [application.yml] - 4:20). Did you mean 'resilience4cata.defaults.retry.max-attempts'? ...
```

Una vez creados los singletons, cada clave `resilience4cata.instances.<id>.*` debe corresponder a una instancia de
política, o el arranque falla con [`R4C-CFG-013`](../errors.md#r4c-cfg-013). Con beans perezosos o pipelines
construidos más tarde, pon `resilience4cata.spring.verify-instances=warn` (el valor por defecto con
`spring.main.lazy-initialization=true`).

El módulo incluye metadatos de configuración de Spring Boot para las claves `defaults` y los interruptores de
abajo, que los IDE que los leen usan para completarlas y documentarlas.

## Customizers y listeners :id=beans

Un bean `ResilienceRegistryCustomizer` ajusta el builder del registro, por ejemplo una config con nombre en código:

<!-- snippet: spring-customizer -->
```java
@Bean
ResilienceRegistryCustomizer slowBackends() {
    return builder -> builder.retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
}
```

Los customizers se ejecutan en `@Order`, antes que las propiedades, así que ganan las propiedades:
`configs.slow.retry.delay` de arriba reemplaza el delay del customizer y conserva su `maxAttempts`. Una clave
`defaults.<política>.*` reemplaza el `<política>Defaults(...)` de un customizer para esa política.

Cada bean `EventListener` se registra para el tipo de evento de su firma genérica (un listener sin tipo recibe
todos los eventos):

<!-- snippet: spring-listener -->
```java
@Bean
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.id() + " is now " + transition.to());
}
```

Los listeners se ejecutan en el hilo que causó el evento ([eventos](observability.md#events)). Un bean listener no
debe depender de un bean protegido: el registro necesita el listener, y el bean protegido necesita el registro.

Un bean `Ticker` (el `ManualClock` del testkit) pasa a ser el reloj del registro. Una aplicación que define su
propio bean `ResilienceRegistry` reemplaza al autoconfigurado (entonces no se aplican customizers ni beans
listener); el bean `ResilienceConfig` sigue disponible para construirlo con `config.applyTo(builder)`.

## Métricas, salud y el endpoint :id=actuator

- **Métricas**: con `resilience4cata-micrometer` en el class path y un bean `MeterRegistry` (el de Spring Boot
  Actuator), los [medidores de Micrometer](micrometer.md) se registran solos. Las llamadas exitosas no publican
  eventos, así que no cuestan nada. No hay observaciones por llamada (`ObservationRegistry`): una por llamada
  reservaría memoria y leería el reloj en cada éxito.
- **Salud**: el indicador de salud `resilience4cata` informa de cada circuit breaker (estado, tasas de fallos y
  de llamadas lentas, llamadas en la ventana). Sigue **UP** con breakers abiertos: un breaker abierto es la
  aplicación protegiéndose de una dependencia que falla, no un motivo para reiniciarla ni sacarla del balanceo.
  Lista los breakers que deben ponerlo DOWN (UNKNOWN mientras están semiabiertos) en
  `resilience4cata.spring.health.down-when-open` (`*` para todos). No forma parte de los grupos liveness ni
  readiness salvo que lo añadas.
- **Endpoint**: `GET /actuator/resilience4cata` devuelve `registry.snapshot()` como JSON ([su forma](observability.md#snapshots)), y
  `GET /actuator/resilience4cata/{instance}` las políticas de un id de instancia. Es de solo lectura y no guarda
  historial de eventos. Exponlo como cualquier endpoint: `management.endpoints.web.exposure.include=health,resilience4cata`.

## Interruptores :id=switches

| Propiedad | Por defecto | Efecto |
|---|---|---|
| `resilience4cata.spring.wrap` | `true` | Reemplaza los beans de interfaces anotadas por su envoltorio |
| `resilience4cata.spring.exclude` | vacío | Nombres de beans que dejar tal cual: sin subclase, sin envoltorio, sin comprobaciones |
| `resilience4cata.spring.allow-retry-inside-transaction` | `false` | Permite `@Retry`/`@Hedge` en métodos transaccionales de clases anotadas (una transacción para todos los intentos) en lugar de fallar con R4C-SPR-004 |
| `resilience4cata.spring.verify-instances` | `fail` (`warn` con inicialización perezosa) | `fail`, `warn` u `off` para claves de instancia que no corresponden a nada |
| `resilience4cata.spring.health.down-when-open` | vacío | Ids de breakers (o `*`) que ponen la salud en DOWN mientras están abiertos |
| `resilience4cata.spring.metrics.enabled` | `true` | Registra los medidores de Micrometer |
| `management.health.resilience4cata.enabled` | `true` | El indicador de salud |

## Pruebas :id=testing

Declara un bean `ManualClock`: las pausas de retry, las esperas de bulkhead y rate limiter y los timeouts lo
siguen, así que una prueba nunca duerme ([guía de pruebas](testing.md)).

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

Dobles de prueba de beans protegidos:

- `@MockitoBean` reemplaza el bean por el mock sin envolver, sin las políticas, para una interfaz o una clase
  anotada.
- `@MockitoSpyBean` sobre una interfaz anotada también inyecta el spy sin envolver, así que `verify(spy)` funciona;
  sobre una clase anotada el spy conserva las políticas.
- Un contexto de test compartido por varias clases de test comparte un registry, así que un breaker abierto sigue
  abierto entre ellas: reinícialo con `registry.circuitBreaker(id).reset()`, o usa `@DirtiesContext`.


## AOT e imágenes nativas :id=aot

Spring AOT (el paso de procesamiento de una imagen nativa, o de una aplicación JVM optimizada con AOT) funciona:

- el reemplazo de las clases anotadas ocurre mientras se procesan las definiciones de beans, así que el código
  generado llama directamente a `new OrderService$Resilient(...)`, y el post-procesador que las reemplazó queda
  fuera del contexto optimizado;
- los beans de interfaces se siguen envolviendo en tiempo de ejecución con `Resilient.of`, que encuentra
  `StockClient$Resilient` por nombre. El procesador de anotaciones escribe metadatos de alcanzabilidad de GraalVM
  para cada clase generada (`META-INF/native-image/dev.resilience4cata.generated/…`), así que no hacen falta
  hints de Spring en tiempo de ejecución;
- las propiedades se leen en tiempo de ejecución, pero las condiciones se fijan al compilar, como en todo Spring
  AOT: `resilience4cata.spring.metrics.enabled`, `management.health.resilience4cata.enabled`, la exposición del
  endpoint, y `resilience4cata.spring.exclude` para las clases anotadas.

Spring Boot 4 necesita GraalVM 25 o posterior para imágenes nativas. El build del proyecto comprueba el
procesamiento AOT en la JVM; su CI no construye imágenes nativas.

## Límites :id=limits

- Las interfaces anotadas cambian el tipo del bean al envoltorio: inyecta la interfaz. Las llamadas sobre `this`
  dentro de la implementación no están protegidas. Una interfaz anotada por bean.
- Los métodos `@Bean` deben devolver ellos mismos la clase generada; una clase `@Configuration` completa con
  métodos `@Bean` no puede anotarse ([`R4C-SPR-002`](../errors.md#r4c-spr-002)).
- Con clases anotadas, los proxies basados en clases como `@Transactional` quedan fuera de las políticas; un
  método transaccional reintentado o con hedge detiene el arranque ([`R4C-SPR-004`](../errors.md#r4c-spr-004))
  salvo que se permita. La comprobación lee los ficheros de clase, así que una imagen nativa la omite: se ejecuta en
  el build y las pruebas en la JVM.
- Los métodos que el constructor llama sobre `this` se ejecutan antes de que se preparen las políticas
  ([clases anotadas](annotated-classes.md)).
- Sin observaciones por llamada, sin historial de eventos en el endpoint, sin recarga en caliente de la
  configuración.
- Los reinicios de Spring Boot DevTools funcionan: cada reinicio usa las clases generadas del nuevo class loader de
  reinicio, y el trabajo que la librería ejecuta después (el siguiente intento de un retry, un timeout async) corre
  con ese loader. Un reinicio construye un registro nuevo, así que las políticas empiezan de cero (breakers cerrados,
  ventanas vacías). Un cambio en las anotaciones se aplica cuando el build que vigila DevTools vuelve a ejecutar el
  procesador de anotaciones (el build de Gradle o Maven lo hace).
