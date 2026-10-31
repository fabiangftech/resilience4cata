# Quarkus

Usa resilience4cata en una aplicación Quarkus: el código generado protege los beans anotados, el registro se
configura desde `application.properties`, y SmallRye Health y Micrometer lo ven.

La extensión `resilience4cata-quarkus` (disponible desde 0.5.0) es una extensión de Quarkus normal, con un módulo de
ejecución y uno de despliegue. No añade nada a la ruta de la llamada: no hay interceptores de CDI. Sus pasos de
construcción hacen que las clases generadas por el procesador de anotaciones sean los beans que recibe tu
aplicación, así que una llamada cuesta lo mismo que sin Quarkus, más el proxy de cliente de ArC cuando el bean es de
ámbito normal.

**Requisitos:** Quarkus 3.40 o posterior (la extensión se compila y se prueba con 3.40.1, la línea LTS actual) y
Java 21.

## Instalación :id=install

Añade la extensión y, como para cualquier uso de las anotaciones, el procesador de anotaciones
([instalación](../start/installation.md)). La extensión trae `resilience4cata-core` y `resilience4cata-config`;
Quarkus encuentra su módulo de despliegue por sí solo.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation enforcedPlatform("io.quarkus.platform:quarkus-bom:${quarkusPlatformVersion}")
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.11.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.11.0')

    implementation 'dev.resilience4cata:resilience4cata-quarkus'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-micrometer'   // opcional: métricas, con quarkus-micrometer
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'  // opcional: ManualClock
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:${quarkusPlatformVersion}"))
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.11.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-quarkus")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-micrometer")   // opcional: métricas, con quarkus-micrometer
    testImplementation("dev.resilience4cata:resilience4cata-testkit")  // opcional: ManualClock
}
```

#### **Maven**

```xml
<!-- con resilience4cata-bom importado en <dependencyManagement>, junto al BOM de Quarkus -->
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-quarkus</artifactId>
</dependency>
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-annotations</artifactId>
  <scope>provided</scope>
</dependency>

<!-- en la configuración de maven-compiler-plugin -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>0.11.0</version>
  </path>
  <!-- annotationProcessorPaths sustituye al descubrimiento: lista también los demás procesadores que uses -->
</annotationProcessorPaths>
```

<!-- tabs:end -->

> Sin el procesador de anotaciones no se genera nada y los beans quedan sin protección: las anotaciones tienen
> retención `CLASS`, así que nada en ejecución puede notar que están ahí. Con el registro de depuración de la
> extensión (`quarkus.log.category."dev.resilience4cata".level=DEBUG`) la construcción lista cada bean que conecta.

Las clases generadas se buscan en el índice de la aplicación, como los beans: un jar de biblioteca con clases
anotadas necesita un índice de Jandex (o `quarkus.index-dependency`), igual que para sus beans.

## Clases anotadas :id=classes

Anota la propia clase del bean:

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

El procesador genera `OrderService$Resilient extends OrderService`. La extensión veta `OrderService` y convierte
`OrderService$Resilient` en el bean en su lugar, con el ámbito, los calificadores y los estereotipos de la clase.
Así:

- `@Inject OrderService` recibe la clase generada (detrás del proxy de cliente de ArC, como cualquier bean
  `@ApplicationScoped`); los calificadores, `@Named`, `@Alternative`/`@Priority` y `@Startup` siguen funcionando;
- las llamadas sobre `this` (`findAgain` llamando a `find`) pasan por las políticas, a diferencia de los
  interceptores de CDI como los de SmallRye Fault Tolerance;
- la inyección por campo, los métodos inicializadores, `@PostConstruct` y los métodos observadores se heredan; la
  inyección por constructor también funciona: los constructores generados reciben el `ResilienceRegistry` seguido
  de los parámetros originales, con sus anotaciones
  ([clases anotadas](annotated-classes.md#constructor-injection)).

**Las clases de ámbito normal necesitan un constructor sin parámetros.** A los beans `@ApplicationScoped`,
`@RequestScoped` y `@SessionScoped` se llega a través de un proxy de cliente, que necesita un constructor no privado
sin parámetros. Quarkus añade uno a una clase que no lo tiene, pero no a la subclase generada de una clase que no lo
tiene, así que la construcción falla con [`R4C-QK-005`](../errors.md#r4c-qk-005). Usa inyección por campo como
arriba, añade un constructor package-private sin parámetros (solo lo usa el proxy), o haz el bean `@Singleton`.

Una clase con métodos o campos `@Produces` no puede sustituirse (los productores no se heredan):
[`R4C-QK-004`](../errors.md#r4c-qk-004).

**Interceptores sobre la clase.** Los interceptores de CDI (`@Transactional`, `@Logged`, …) los aplica una subclase
de ArC de la clase generada, así que se ejecutan fuera de las políticas. Para las transacciones eso significaría una
sola transacción para todos los intentos, y después de que un intento fallido la marque rollback-only, un intento
posterior exitoso no puede hacer commit. Por eso un método que es a la vez reintentado o con hedge y
`@Transactional` (con un tipo que se une a una transacción o la inicia) **detiene la construcción** con
[`R4C-QK-002`](../errors.md#r4c-qk-002). Declara la política en una interfaz (una transacción por intento, ver
abajo), mueve `@Transactional` a otro bean al que llame el método, o pon
`quarkus.resilience4cata.allow-retry-inside-transaction=true` si lo que quieres es una sola transacción compartida.

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

Para cada bean que implementa una interfaz anotada, la extensión registra un bean sintético con los tipos de la
interfaz y los calificadores del bean, creado por `Resilient.of(PaymentsGateway.class, bean, registry)`. Es una
alternativa habilitada con prioridad alta, así que gana dondequiera que se inyecte la interfaz:

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

- `@Inject CardPayments` (la clase) sigue recibiendo el propio bean, sin protección.
- El envoltorio de un bean `@ApplicationScoped` también es `@ApplicationScoped`, detrás de su propio proxy de
  cliente, así que la interfaz sigue siendo un bean de ámbito normal (un `@InjectMock` sobre la interfaz en un test
  funciona, y reemplaza al envoltorio, políticas incluidas). El envoltorio de un bean de ámbito de petición o de
  sesión es un `@Singleton` que guarda el proxy de cliente del bean (esos ámbitos siguen funcionando). Ambos se
  crean al arrancar; el envoltorio de un bean `@Dependent` también es `@Dependent`.
- Los interceptores de CDI del bean se ejecutan **dentro** de las políticas: un método `@Transactional` reintentado
  obtiene una transacción por intento.
- Las llamadas sobre `this` dentro de `CardPayments` no están protegidas (el envoltorio está fuera del objeto).
- Un bean que implementa dos interfaces anotadas, sin que una extienda a la otra, detiene la construcción con
  [`R4C-QK-003`](../errors.md#r4c-qk-003). Los métodos productores no se envuelven: devuelve tú mismo
  `Resilient.of(...)` desde ellos.

## Clientes REST :id=rest-client

Anota una interfaz de cliente REST como cualquier otra:

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

El bean del cliente REST tiene el calificador `@RestClient`. La extensión añade un bean `@Default` a su alrededor,
así que inyectar la interfaz sin calificador da el cliente protegido, y `@RestClient` sigue dando el original:

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

## Beans reactivos :id=reactive

Los métodos de beans que devuelven el `Mono` o el `Flux` de Reactor se protegen igual, con `resilience4cata-reactor` en el classpath (no forma parte de `resilience4cata-all`: [instalación](../start/installation.md)). Cada suscripción es una llamada a través de las políticas; en un `Flux`, el retry y el fallback solo actúan antes del primer elemento, y `@Timeout` es el tiempo sin un elemento ([Project Reactor](reactor.md)). `Uni` y `Multi` de Mutiny no están soportados ([R4C-ASY-002](../errors.md#r4c-asy-002)): convierte en la implementación (`uni.subscribeAsCompletionStage()`).

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

Quarkus REST no se suscribe a los tipos de Reactor: un endpoint que devuelve un `Mono` escribe el objeto como entidad y la llamada nunca se ejecuta. Convierte a Mutiny en el recurso; Mutiny recibe un `Flow.Publisher` del JDK, que da el `JdkFlowAdapter` de Reactor:

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

- **Un fallo de un `Uni`** pasa por tus `@ServerExceptionMapper` como cualquier otro (un `CallTimeoutException` asociado a 504, por ejemplo). Un stream de server-sent events ya envió sus cabeceras antes de su primer elemento, así que un fallo, incluso antes del primer evento, termina el stream cerrando la conexión.
- **Un cliente que se desconecta** cancela la llamada a través de todas las políticas, también antes del primer evento de un stream: el `Mono` o `Flux` del delegado se cancela y el bulkhead libera su lugar.

Un `jakarta.transaction.Transactional` alrededor de un método `Mono` o `Flux` con retry o hedge de una clase anotada hace fallar el build, como en los demás métodos ([R4C-QK-002](../errors.md#r4c-qk-002)).

## Configuración :id=configuration

Las políticas leen la configuración de la aplicación bajo `resilience4cata.` (o `r4c.`), con las claves de la
[referencia de configuración](../reference/configuration.md) en camelCase o kebab-case:

<!-- snippet: quarkus-properties -->
```properties
resilience4cata.defaults.retry.delay=50ms
resilience4cata.defaults.circuit-breaker.minimum-calls=20
resilience4cata.configs.slow.retry.delay=500ms
resilience4cata.instances.orders.circuit-breaker.failure-rate-threshold=25
resilience4cata.instances."dev.resilience4cata.quarkus.guide.PaymentsGateway#charge(java.lang.String,long)".timeout.value=3s
%dev.resilience4cata.defaults.retry.max-attempts=2
```

- Los valores vienen de SmallRye Config, así que funciona cualquier fuente de Quarkus: `application.properties`,
  YAML (`quarkus-config-yaml`), `.env`, propiedades del sistema, `-D` y expresiones (`${…}`).
- **Los perfiles son los de Quarkus**: `%dev.`, `%test.`, `%prod.` y los tuyos. Las claves propias de
  resilience4cata `profiles.<nombre>.*` siguen funcionando también.
- **La precedencia es la de Quarkus**: propiedades del sistema sobre variables de entorno sobre
  `application.properties`. Las variables de entorno usan los
  [nombres `R4C_` o `RESILIENCE4CATA_`](configuration.md) de `resilience4cata-config` (SmallRye no puede
  convertirlos de vuelta en claves camelCase). Un fichero `resilience4cata.properties` en el classpath se sigue
  leyendo, por debajo de todos ellos.
- Los **ids de instancia** que no sean solo letras, dígitos, `_` y `-` (el derivado
  `<tipo>#<método>(<tipos de parámetros>)`) van entre comillas dobles, como arriba.
- La configuración se lee una vez, al arrancar, como en todo resilience4cata: no hay recarga.
- Las claves bajo `quarkus.resilience4cata.` son los [interruptores](#switches) propios de la extensión.

**Validación al arrancar.** El registro se crea cuando arranca la aplicación, así que una clave desconocida o un
valor incorrecto la detiene con todos los problemas a la vez, y el fichero y la línea donde se puso cada clave:

```text
ERROR [io.quarkus.runtime.Application] Failed to start application
dev.resilience4cata.config.ConfigException: Invalid resilience4cata configuration (1 problem):
  [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.maxAtempts' (application.properties:2). Did you mean 'resilience4cata.defaults.retry.maxAttempts'? ...
```

Al arrancar también se comprueban las claves `resilience4cata.instances.<id>.*` frente a las instancias de
políticas creadas hasta ese momento ([`R4C-CFG-013`](../errors.md#r4c-cfg-013)). Quarkus crea los beans de forma
perezosa, así que por defecto es una advertencia (`quarkus.resilience4cata.verify-instances=warn`): los envoltorios
de las interfaces se crean al arrancar, pero las clases anotadas solo cuando se usan por primera vez, salvo que sean
beans `@Startup`. Pon `fail` cuando todas las clases protegidas se crean al arrancar.

## Customizers y listeners :id=beans

Un bean `ResilienceRegistryCustomizer` ajusta el builder del registro, por ejemplo una configuración con nombre en
código:

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

Los customizers se ejecutan en orden de `@Priority`, antes de las propiedades, así que las propiedades ganan:
`resilience4cata.configs.slow.retry.delay` de arriba sustituye el retraso del customizer y mantiene su
`maxAttempts`.

Cada bean `EventListener` se registra para el tipo de evento de sus tipos de bean (un listener sin tipo recibe
todos los eventos):

<!-- snippet: quarkus-listener -->
```java
@Produces
@Singleton
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.id() + " is now " + transition.to());
}
```

Un bean `Ticker` (el `ManualClock` del testkit) pasa a ser el reloj del registro. Una aplicación que produce su
propio bean `ResilienceRegistry` sustituye al de la extensión (entonces no se aplican customizers, listeners ni
métricas); el bean `ResilienceConfig` sigue ahí para construirlo con `config.applyTo(builder)`.

## Métricas y salud :id=observability

- **Métricas**: con `quarkus-micrometer` (y un registro como `quarkus-micrometer-registry-prometheus`) y
  `resilience4cata-micrometer`, los [medidores de Micrometer](micrometer.md) se registran solos y aparecen en
  `/q/metrics`. Sin Micrometer pero con `quarkus-opentelemetry` y `resilience4cata-opentelemetry`, se registran en
  su lugar las [métricas de OpenTelemetry](opentelemetry.md) (se prefiere Micrometer cuando están los dos, como hace
  SmallRye Fault Tolerance). Las llamadas exitosas no publican eventos, así que no cuestan nada.
- **Salud**: con `quarkus-smallrye-health`, la comprobación de preparación `resilience4cata` (`/q/health/ready`)
  informa del estado y la tasa de fallos de cada circuit breaker. Se mantiene **UP** con breakers abiertos: un
  breaker abierto es la aplicación protegiéndose de una dependencia que falla, no un motivo para sacarla del
  balanceo de carga. Lista los breakers que deben ponerla en DOWN en
  `quarkus.resilience4cata.health.down-when-open` (`*` para todos).
- Todavía no hay tarjeta en la Dev UI ni endpoint: `registry.snapshot()` lo tiene todo, si quieres exponerlo.

## Dev UI :id=dev-ui

En modo dev (`quarkus dev`, o `./gradlew quarkusDev`), la Dev UI en `/q/dev-ui` tiene una tarjeta de
resilience4cata. Su página *Policies* lista cada instancia de política que el registry creó hasta el momento, y se
actualiza cada dos segundos mientras la página está visible:

| Política | Qué muestra la página |
|---|---|
| Circuit breakers | estado, tasas de fallas y de llamadas lentas, llamadas en la ventana (fallidas, lentas) |
| Bulkheads | llamadas concurrentes contra el máximo, llamadas en espera |
| Rate limiters | permisos disponibles contra los permisos por período |
| Adaptive limits | algoritmo, límite actual y sus cotas, llamadas en curso, latencia de la última ventana y latencia de largo plazo |
| Retries, hedges | intentos máximos (y espera), las llamadas del presupuesto y los reintentos o hedges permitidos, cuando hay presupuesto |
| Timeouts | el timeout |

La mayoría de las instancias se crean en la primera llamada a su método (las de una clase anotada, al crearse el
bean), así que una política aparece una vez que se usó. Un valor que todavía no tiene sentido, como una tasa de fallas
antes de `minimumCalls`, se muestra como un guion.

La página también puede forzar la apertura de un circuit breaker, deshabilitarlo o reiniciarlo, como hace
`registry.circuitBreaker(id)`: útil para ver cómo se comporta la aplicación con una dependencia caída. El enlace
*Guide* de la tarjeta abre esta sección.

Nada de esto existe fuera del modo dev: los build steps que agregan la tarjeta y su servicio JsonRPC corren solo en
desarrollo local, así que un build de tests o de producción no tiene ninguno de los dos.

## SmallRye Fault Tolerance frente a resilience4cata :id=smallrye

Quarkus trae su propia tolerancia a fallos, [SmallRye Fault Tolerance](https://quarkus.io/guides/smallrye-fault-tolerance)
(`quarkus-smallrye-fault-tolerance`, MicroProfile Fault Tolerance). Las dos funcionan en la misma aplicación, pero
no en el mismo método.

**Elige SmallRye Fault Tolerance** cuando quieras la opción integrada y estándar: portabilidad de MicroProfile,
`@Asynchronous` y tipos de retorno de Mutiny, configuración que se puede cambiar en ejecución, y nada que añadir a
la construcción.

**Elige resilience4cata** cuando quieras que el mismo código funcione fuera de CDI (Java simple, pruebas sin
contenedor, Spring Boot), anotaciones y configuración comprobadas al compilar y al arrancar con errores con código,
configuraciones con nombre, bundles, ajustes por instancia, clasificadores y hedging, llamadas sobre `this`
protegidas en clases anotadas, o su [sobrecoste](../performance.md).

**Mecanismo.** SmallRye Fault Tolerance es un interceptor de CDI; resilience4cata es código generado que la
extensión convierte en el bean. Ninguno protege un `new CardPayments()` que no haya creado CDI.

**Imports.** Las dos bibliotecas llaman a sus anotaciones `@Retry`, `@CircuitBreaker`, `@Timeout`, `@Bulkhead` y
`@Fallback`, y los IDE importan la primera coincidencia. Un método (o clase) que lleva anotaciones de las dos detiene
la construcción con [`R4C-QK-001`](../errors.md#r4c-qk-001) cuando SmallRye Fault Tolerance está instalado (una
advertencia si no). Prefiere una biblioteca por módulo, o un [bundle](bundles.md) como `@ExternalCall` para que los
nombres simples no aparezcan.

**Equivalencias.** Los valores por defecto también difieren: consulta la
[referencia de configuración](../reference/configuration.md).

| SmallRye / MicroProfile Fault Tolerance | resilience4cata |
|---|---|
| `@Retry(maxRetries = 3)` (reintentos tras la primera llamada) | `@Retry(maxAttempts = 4)` (intentos, el primero incluido) |
| `@Retry(delay = 200, delayUnit = ChronoUnit.MILLIS)` | `@Retry(delay = "200ms", backoff = Backoff.FIXED)` (el backoff por defecto es exponencial) |
| `@Retry(jitter = 200)` (± una duración) | `@Retry(jitter = Jitter.FULL)` (`NONE`, `FULL` o `EQUAL`, una parte de cada retraso) |
| `@Retry(maxDuration = 180000)` | `@Retry(maxDuration = "3m")` |
| `@Retry(retryOn = …, abortOn = …)` | `@Retry(retryOn = …, abortOn = …)` |
| `@ExponentialBackoff` | `@Retry(backoff = Backoff.EXPONENTIAL, multiplier = 2, maxDelay = "10s")` |
| `@CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5)` | `@CircuitBreaker(windowSize = 20, minimumCalls = 20, failureRateThreshold = 50)` (un porcentaje) |
| `@CircuitBreaker(delay = 5000)` | `@CircuitBreaker(waitInOpen = "5s")` |
| `@CircuitBreaker(successThreshold = 1)` | `@CircuitBreaker(halfOpenCalls = 1)` |
| `@CircuitBreaker(failOn = …, skipOn = …)` | `@CircuitBreaker(recordOn = …, ignoreOn = …)` |
| `@Timeout(value = 2, unit = ChronoUnit.SECONDS)` | `@Timeout("2s")` |
| `@Bulkhead(10)` | `@Bulkhead(maxConcurrent = 10)` |
| `@Bulkhead(value = 10, waitingTaskQueue = 10)` con `@Asynchronous` | `@Bulkhead(maxConcurrent = 10, maxWait = "…")` (espera un tiempo, síncrono o asíncrono) |
| `@RateLimit(value = 100, window = 1, windowUnit = ChronoUnit.SECONDS)` | `@RateLimiter(permits = 100, period = "1s")` |
| `@Fallback(fallbackMethod = "cached")` | `@Fallback(method = "cached")` |
| `@Fallback(MyHandler.class)` | `@Fallback(MyHandler.class)` (un `FallbackHandler`) |
| `@Asynchronous` con `CompletionStage` | basta con un tipo de retorno `CompletionStage`/`CompletableFuture` |
| `quarkus.fault-tolerance."<id>".retry.max-retries=5` | `resilience4cata.instances.<id>.retry.maxAttempts=6` |
| `quarkus.fault-tolerance.global.retry.max-retries=5` | `resilience4cata.defaults.retry.maxAttempts=6` |

Cambiar un valor en ejecución, que SmallRye permite, no tiene equivalente: resilience4cata resuelve la
configuración una sola vez, por diseño.

## Interruptores :id=switches

| Propiedad | Fase | Por defecto | Efecto |
|---|---|---|---|
| `quarkus.resilience4cata.beans.enabled` | construcción | `true` | Convertir las clases generadas en los beans (las subclases sustituyen a las clases anotadas, los envoltorios representan a los beans de interfaces) |
| `quarkus.resilience4cata.beans.exclude` | construcción | vacío | Clases de beans (con nombre completo) que se dejan como están |
| `quarkus.resilience4cata.allow-retry-inside-transaction` | construcción | `false` | Permitir `@Retry`/`@Hedge` en métodos `@Transactional` de clases anotadas en lugar de fallar con R4C-QK-002 |
| `quarkus.resilience4cata.health.enabled` | construcción | `true` | La comprobación de preparación, cuando SmallRye Health está presente |
| `quarkus.resilience4cata.metrics.enabled` | construcción | `true` | Vincular el registro a Micrometer u OpenTelemetry |
| `quarkus.resilience4cata.verify-instances` | ejecución | `warn` | `fail`, `warn` u `off` para claves de instancia que no coinciden con nada al arrancar |
| `quarkus.resilience4cata.health.down-when-open` | ejecución | vacío | Ids de breakers (o `*`) que ponen la comprobación en DOWN mientras están abiertos |

## Pruebas :id=testing

Produce un bean `ManualClock` en tus fuentes de prueba: las pausas de los reintentos, las esperas del bulkhead y del
rate limiter y los timeouts lo siguen, así que una prueba nunca duerme ([guía de pruebas](testing.md)).

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

Un bean de ámbito normal se inyecta como proxy de cliente: lee y cambia su estado con métodos, nunca a través de
sus campos.

## Imágenes nativas :id=native

El procesador escribe metadatos de alcanzabilidad de GraalVM para cada clase generada, y `core` inicializa sus hilos
en segundo plano en ejecución, así que una construcción nativa (`-Dquarkus.native.enabled=true`) no necesita
configuración adicional: el código generado por ArC instancia las clases anotadas, y `Resilient.of` los envoltorios
de interfaces, que encuentra gracias a esos metadatos. La configuración se lee en ejecución. La CI del proyecto
construye la aplicación de esta guía como imagen nativa (GraalVM para JDK 25) y la prueba por HTTP: una clase
anotada, envoltorios de interfaces y de clientes REST, la comprobación de disponibilidad, las métricas, y métodos
`Mono` y `Flux` servidos con `Uni` y `Multi` ([beans reactivos](#reactive)).

## Límites :id=limits

- Modo dev: todavía no se ha comprobado si una recarga en caliente vuelve a ejecutar el procesador de anotaciones. Si
  un cambio en las anotaciones parece ignorarse, reinicia el modo dev.
- Las clases anotadas de ámbito normal necesitan un constructor sin parámetros (o inyección por campo); las clases
  anotadas no pueden declarar productores.
- Envoltorios de interfaces: las llamadas sobre `this` dentro de la implementación no están protegidas; una interfaz
  anotada por bean; los métodos productores no se envuelven.
- Con clases anotadas, los interceptores de CDI como `@Transactional` se ejecutan fuera de las políticas; un método
  transaccional reintentado o con hedge detiene la construcción ([`R4C-QK-002`](../errors.md#r4c-qk-002)) salvo
  que se permita.
- Sin tarjeta en la Dev UI, sin endpoint, sin recarga en caliente de la configuración, sin métricas por llamada.
