# Micronaut

Usa resilience4cata en una aplicación Micronaut 5: el código generado protege los beans anotados, el registro se
configura desde `application.yml` y el módulo de gestión de Micronaut obtiene un indicador de salud, un endpoint y
métricas.

El módulo `resilience4cata-micronaut` (disponible desde 0.5.0) no añade nada al camino de la llamada: no hay
advice `@Around` ni interceptores. Un bean protegido *es* la clase que generó el procesador de anotaciones, así que
una llamada cuesta lo mismo que sin Micronaut.

**Requisitos:** Micronaut 5.2 o posterior (el módulo se compila contra Micronaut core 5.2.0 y se prueba en la
plataforma 5.2.2) y **Java 25**, que el propio Micronaut 5 exige. Este módulo es la única excepción a la base Java
21 de resilience4cata: se compila con `--release 25`; todos los demás módulos siguen en Java 21. Micronaut 4 no
está soportado.

## Instalación :id=install

Añade el módulo y, como para cualquier uso de las anotaciones, el procesador de anotaciones
([instalación](../start/installation.md)). El módulo trae `resilience4cata-core` y `resilience4cata-config`. La
opción del compilador `-Ar4c.inject=micronaut` convierte las subclases generadas de las clases anotadas en beans de
Micronaut ([clases anotadas](#classes)); no hace falta si solo anotas interfaces.

<!-- tabs:start -->

#### **Gradle**

```groovy
plugins {
    id 'io.micronaut.application' version '5.0.2' // adds micronaut-inject-java to the processor path
}

dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.10.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.10.0')

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
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.10.0")
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
    <version>0.10.0</version>
  </path>
</annotationProcessorPaths>
<compilerArgs combine.children="append">
  <arg>-Ar4c.inject=micronaut</arg>
</compilerArgs>
```

<!-- tabs:end -->

Sin el plugin de Gradle de Micronaut, pon tú mismo `io.micronaut:micronaut-inject-java` en `annotationProcessor`,
con el BOM de la plataforma Micronaut. **El orden de los dos procesadores no importa**: el de Micronaut nunca
reclama anotaciones y el nuestro solo reclama las suyas, así que cada uno lo ve todo (el build del módulo compila
las mismas fuentes en los dos órdenes y compara el resultado). Lombok, si lo usas, va primero, como dice la propia
documentación de Micronaut ([con Lombok](annotated-classes.md#con-lombok)).

> Sin el procesador de anotaciones no se genera nada y los beans quedan sin protección: las anotaciones tienen
> retención `CLASS`, así que nada en tiempo de ejecución puede notar que están. `GET /resilience4cata` lista las
> instancias de políticas; una lista vacía significa que el procesador no se ejecutó.

## Clases anotadas :id=classes

Anota la clase misma; es el estilo recomendado, porque las llamadas sobre `this` también quedan protegidas:

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

El procesador genera `OrderService$Resilient extends OrderService`. Con `-Ar4c.inject=micronaut` además repite en
ella las anotaciones propias de la clase (`@Singleton`, calificadores como `@Named`, `@Requires`, `@Primary`,
`@Order`…). El procesador de Micronaut escribe entonces una definición de bean para la clase generada, la
integración deja fuera la que Micronaut compiló para el propio `OrderService`, y Micronaut crea
`OrderService$Resilient` dondequiera que habría creado `OrderService`:

- el bean sigue siendo un `OrderService`, con su ámbito, sus calificadores y sus requisitos;
- las llamadas sobre `this` (`findAgain` llamando a `find`) pasan por las políticas, a diferencia del `@Retryable`
  del propio Micronaut;
- la inyección por constructor funciona como antes: los constructores generados reciben el `ResilienceRegistry`
  (inyectado por tipo) seguido de los parámetros originales, con sus nombres y anotaciones (`@Inject` en uno de
  varios constructores, `@Named`, `@Value`; consulta [clases anotadas](annotated-classes.md#constructor-injection)).
  La inyección por campo y por método y `@PostConstruct` se heredan.

Sin la opción, Micronaut crearía el `OrderService` sin protección. El módulo lo detecta al crear el bean y falla
con [`R4C-MN-004`](../errors.md#r4c-mn-004). (Una clase cuyo constructor tiene `@Inject` es un caso especial: el
constructor generado repite `@Inject`, así que sin la opción Micronaut ve dos beans, la clase y su subclase, y
`R4C-MN-004` reporta el primero.)

**Sin la opción, con un factory.** Deja la clase sin anotación de ámbito (y sin `@Inject`), para que Micronaut no la
cree, y devuelve la subclase generada desde un método de un `@Factory`:

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

Declara como tipo de retorno del método la clase, no `InvoiceClient$Resilient`: la clase generada aún no existe
cuando el procesador de Micronaut lee el factory por primera vez. Un bean creado por un factory no recibe
inyección propia ni AOP de Micronaut; con la opción, la clase conserva todas las funciones de Micronaut.

**AOP de Micronaut en la clase.** Los métodos sobrescritos generados repiten las anotaciones de los métodos que
sobrescriben, así que el advice around (`@Transactional`, `@Cacheable`, tus propios bindings `@Around`) sigue
aplicándose, y el proxy de Micronaut (una subclase `$Intercepted` de `OrderService$Resilient`) queda **alrededor**
de las políticas: el advice se ejecuta una vez para todos los intentos. Con transacciones eso significaría una
transacción para todos los intentos, y después de que un intento fallido la marque rollback-only, un intento
posterior exitoso no podría hacer commit. Por eso un método que a la vez se reintenta o se cubre con hedge y es
`@Transactional` (el de Micronaut, o el de Jakarta traducido a él, con una propagación que se une a una transacción
o la inicia) falla con [`R4C-MN-005`](../errors.md#r4c-mn-005) al crear el bean. Corrígelo declarando la política
en una interfaz que implemente el bean (el envoltorio queda fuera del proxy de transacciones: una transacción por
intento), o moviendo `@Transactional` a otro bean al que llame el método reintentado. Si lo que quieres es una sola
transacción compartida, pon `resilience4cata.micronaut.allow-retry-inside-transaction=true`. `@CircuitBreaker`,
`@Timeout`, `@RateLimiter` y `@Bulkhead` ejecutan la llamada una sola vez y no se revisan.

## Interfaces anotadas :id=interfaces

<!-- snippet: micronaut-interface -->
```java
@Retry(maxAttempts = 3)
@Timeout("2s")
public interface StockClient {

    int available(String sku);
}
```

Marca la implementación con `@ResilientBean`, nombrando la interfaz:

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

Cuando Micronaut crea el bean, un `BeanCreatedEventListener` lo reemplaza por
`Resilient.of(StockClient.class, bean, registry)`. Inyecta la interfaz:

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

- `@ResilientBean(StockClient.class)` también limita los tipos del bean a `StockClient` (fija
  `@Bean(typed = StockClient.class)`), porque el envoltorio solo implementa la interfaz: inyectar `HttpStockClient`
  no encuentra ningún bean. Exponerlo con otros tipos detiene el arranque con
  [`R4C-MN-001`](../errors.md#r4c-mn-001), y nombrar una interfaz que el bean no implementa, con
  [`R4C-MN-002`](../errors.md#r4c-mn-002).
- El envoltorio reemplaza al bean tal como lo construyó Micronaut, proxy de AOP incluido, así que el advice de
  Micronaut sobre `HttpStockClient` se ejecuta **dentro** de las políticas: un método `@Transactional` reintentado
  obtiene una transacción nueva por intento.
- Las llamadas dentro de `HttpStockClient` sobre `this` no están protegidas (el envoltorio está fuera del objeto).
- Usa `@Singleton`, `@Prototype` o `@Context`. Los ámbitos que Micronaut envuelve en un proxy (`@Refreshable`,
  `@RequestScope`, `@ThreadLocal`) detienen el arranque con [`R4C-MN-003`](../errors.md#r4c-mn-003).
- Cada definición `@ResilientBean` se revisa al arrancar el contexto, así que estos errores detienen el arranque
  incluso para beans que se crean más tarde. Por qué una marca y no un envoltorio automático: es explícito y no
  necesita buscar una clase para cada bean.

## Configuración :id=configuration

Las políticas leen la configuración de la aplicación bajo `resilience4cata`, con las claves de la
[referencia de configuración](../reference/configuration.md) en kebab-case o camelCase:

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

- **Los ids de instancia** que no sean letras, dígitos, `_` y `-` (el derivado
  `<tipo>#<método>(<tipos de parámetros>)`) contienen puntos, que Micronaut leería como anidamiento: escribe el id
  entre comillas dobles dentro de la clave YAML, como arriba (`'"com.acme.StockClient#available(java.lang.String)"'`),
  o da a la política un `name`.
- **Las listas** (`retry-on`, `abort-on`, `record-on`, `ignore-on`) pueden ser listas YAML o valores separados por
  comas.
- **Los placeholders** (`${…}`) se resuelven.
- **La precedencia es la de Micronaut**: línea de comandos sobre propiedades del sistema sobre variables de entorno
  sobre `application-<entorno>.yml` sobre `application.yml`. Las variables de entorno usan los
  [nombres `R4C_` o `RESILIENCE4CATA_`](configuration.md) de `resilience4cata-config`, leídos tal cual y no a través
  de la traducción de Micronaut. Un archivo `resilience4cata.properties` en el class path se sigue leyendo, por
  debajo de todos ellos.
- **Entornos**: los de Micronaut (`application-prod.yml`) son la vía natural; las claves propias
  `profiles.<nombre>.*` de resilience4cata siguen funcionando también, por separado.
- La configuración se lee una vez, al arrancar, como en todo resilience4cata: no hay refresco, y los beans no son
  `@Refreshable`.

**Validación al arrancar.** La configuración y el registro son beans `@Context`, así que una clave desconocida o un
valor incorrecto detienen el arranque con todos los problemas a la vez, nombrando el archivo donde se fijaron
(Micronaut no conserva números de línea):

```text
Bean definition [dev.resilience4cata.config.ResilienceConfig] could not be loaded: Error instantiating bean of type [dev.resilience4cata.config.ResilienceConfig]

Message: Invalid resilience4cata configuration (2 problems):
  [R4C-CFG-001] unknown key 'resilience4cata.defaults.retry.max-atempts' (application.yml): ... Did you mean 'resilience4cata.defaults.retry.max-attempts'?
  [R4C-CFG-002] invalid value 'soon' for 'resilience4cata.defaults.retry.delay' (application.yml): ...
```

Al arrancar, cada clave `resilience4cata.instances.<id>.*` debe corresponder a una instancia de política
([`R4C-CFG-013`](../errors.md#r4c-cfg-013)). Micronaut crea los singletons de forma perezosa por defecto, así que
las instancias de la mayoría de los beans aún no existen en ese momento: una clave sin correspondencia solo se
registra como advertencia, que puede ser una falsa alarma. Cuando el contexto crea sus singletons de forma
anticipada (`eagerInitSingletons(true)`), las instancias de los singletons existen y una clave sin correspondencia
detiene el arranque, salvo que la aplicación tenga beans protegidos que no son singletons (`@Prototype`,
`@Refreshable`, ámbito de petición), cuyas instancias aparecen cuando se crean: entonces sigue siendo una
advertencia. `resilience4cata.micronaut.verify-instances` (`fail`, `warn`, `off`) se impone a todos estos casos.

## Personalizar el registro :id=beans

Personaliza el builder del registro al estilo de Micronaut, con un `BeanCreatedEventListener`, por ejemplo una
configuración con nombre en código:

<!-- snippet: micronaut-customizer -->
```java
@Singleton
BeanCreatedEventListener<ResilienceRegistry.Builder> slowBackends() {
    return event -> event.getBean().retry("slow", retry -> retry.maxAttempts(5).delay(Duration.ofSeconds(1)));
}
```

Los listeners se ejecutan en `@Order`, antes de la configuración, así que la configuración gana:
`configs.slow.retry.delay` de arriba sobrescribe el retraso del listener y mantiene su `maxAttempts`. Una clave
`defaults.<política>.*` reemplaza el `<política>Defaults(...)` de un listener para esa política.

Cada bean `EventListener` se registra para el tipo de evento de su firma genérica (un listener sin tipo recibe
todos los eventos):

<!-- snippet: micronaut-listener -->
```java
@Singleton
EventListener<CircuitBreakerEvent.StateTransition> breakerAlerts(Alerts alerts) {
    return transition -> alerts.send(transition.instance() + " is now " + transition.to());
}
```

Los listeners se ejecutan en el hilo que causó el evento ([eventos](observability.md#events)). Un bean listener no
debe depender de un bean protegido: el registro necesita el listener, y el bean protegido necesita el registro.

Un bean `Ticker` (el `ManualClock` del testkit) pasa a ser el reloj del registro. Para construir el registro tú
mismo, declara un bean con `@Replaces(ResilienceRegistry.class)`; el bean `ResilienceConfig` sigue ahí para
aplicar la configuración con `config.applyTo(builder)`.

## Métricas, salud y el endpoint :id=management

- **Métricas**: con `resilience4cata-micrometer` en el class path y un bean `MeterRegistry` (de
  `micronaut-micrometer-core`), los [medidores de Micrometer](micrometer.md) se registran automáticamente. Las
  llamadas exitosas no publican eventos, así que no cuestan nada.
- **Salud**: con `micronaut-management`, el indicador de salud `resilience4cata` informa de cada circuit breaker
  (estado, tasas de fallos y de llamadas lentas, llamadas en la ventana). Se mantiene **UP** con breakers abiertos:
  un breaker abierto es la aplicación protegiéndose de una dependencia que falla, no un motivo para reiniciarla ni
  sacarla del balanceo de carga. Lista los breakers que deben ponerlo en DOWN (UNKNOWN mientras están semiabiertos)
  en `resilience4cata.micronaut.health.down-when-open` (`*` para todos). No es `@Liveness` ni `@Readiness`, así
  que solo cuenta en `/health`.
- **Endpoint**: `GET /resilience4cata` devuelve `registry.snapshot()` como JSON, y
  `GET /resilience4cata/{instance}` las políticas de un id de instancia. Es de solo lectura y no guarda historial
  de eventos. Como los endpoints del propio Micronaut, es sensible por defecto: configúralo con
  `endpoints.resilience4cata.enabled` y `endpoints.resilience4cata.sensitive`.

## Interruptores :id=switches

| Propiedad | Por defecto | Efecto |
|---|---|---|
| `resilience4cata.micronaut.verify-instances` | `warn` (`fail` con singletons anticipados) | `fail`, `warn` u `off` para claves de instancia que no corresponden a ninguna instancia al arrancar |
| `resilience4cata.micronaut.exclude` | vacío | Nombres completos de clases de beans que se dejan en paz (no se revisan R4C-MN-004 ni R4C-MN-005) |
| `resilience4cata.micronaut.allow-retry-inside-transaction` | `false` | Permite `@Retry`/`@Hedge` en métodos transaccionales de clases anotadas (una transacción para todos los intentos) en lugar de fallar con R4C-MN-005 |
| `resilience4cata.micronaut.health.enabled` | `true` | El indicador de salud |
| `resilience4cata.micronaut.health.down-when-open` | vacío | Ids de breakers (o `*`) que ponen la salud en DOWN mientras están abiertos |
| `resilience4cata.micronaut.metrics.enabled` | `true` | Registrar los medidores de Micrometer |

## Pruebas :id=testing

Da al contexto de pruebas un bean `ManualClock`: las pausas de reintento, las esperas del bulkhead y del rate
limiter y los timeouts lo siguen, así que una prueba nunca duerme ([guía de pruebas](testing.md)).

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

La clase de prueba se anota con `@MicronautTest` (de `micronaut-test-junit5`).

## Imágenes nativas :id=native

No hay nada que añadir para resilience4cata:

- el procesador de anotaciones escribe metadatos de alcanzabilidad de GraalVM para cada clase generada
  (`META-INF/native-image/dev.resilience4cata.generated/…`), que cubren `Resilient.of` para los beans
  `@ResilientBean`;
- con `-Ar4c.inject=micronaut`, las propias definiciones de beans de Micronaut crean las subclases generadas, sin
  reflexión;
- el endpoint lee los records de los snapshots mediante introspecciones de beans generadas al compilar el módulo,
  no mediante reflexión.

Durante el desarrollo del módulo se construyó y ejecutó a mano una imagen nativa de GraalVM 25 de una aplicación
pequeña con una interfaz `@ResilientBean`, una clase anotada, configuración YAML, el endpoint y Micrometer; el CI
del proyecto no construye imágenes nativas. Construye la tuya con el plugin de Gradle o de Maven de Micronaut, que
además incluye `application.yml` en la imagen. Las clases de excepción nombradas en la configuración externa
(`retry-on: com.acme.Busy`) se cargan por nombre: nómbralas en las anotaciones, o regístralas para reflexión. La
revisión de reintentos dentro de transacciones (`R4C-MN-005`) lee archivos de clase, así que una imagen nativa la
omite: se ejecuta en el build y las pruebas en la JVM.

## Límites :id=limits

- Micronaut tiene sus propios `@Retryable`, `@CircuitBreaker` y `@Fallback` en `io.micronaut.retry.annotation`:
  revisa los imports, un IDE puede elegir el equivocado. No los combines con los de resilience4cata en un mismo
  método.
- Interfaces anotadas: inyecta la interfaz; las llamadas sobre `this` dentro de la implementación no están
  protegidas; una interfaz anotada por `@ResilientBean`.
- Las clases anotadas necesitan `-Ar4c.inject=micronaut` para que Micronaut las cree, o un factory. Solo se deja
  fuera la definición de bean de la propia clase: los beans de subclases, los beans de la clase que crean métodos
  `@Factory` y el `@MockBean` de un test se mantienen. Las clases `@Factory` no pueden anotarse.
- No pongas políticas en clases de configuración (`@ConfigurationProperties`, `@EachProperty`). El procesador de
  Micronaut escribe sus metadatos de configuración una vez por ronda de compilación, y ve la subclase generada una
  ronda después, así que avisa `Error occurred writing configuration metadata`, lo que hace fallar un build con
  `-Werror`. Pon la configuración en su propia clase y las políticas en un bean que la reciba:
  `@EachBean(BackendConfig.class)` da un bean protegido por cada entrada de `@EachProperty`.
- Con clases anotadas, la AOP de Micronaut se ejecuta fuera de las políticas; un método transaccional reintentado o
  con hedge falla ([`R4C-MN-005`](../errors.md#r4c-mn-005)) salvo que se permita.
- Los métodos que el constructor llama sobre `this` se ejecutan antes de que las políticas estén listas
  ([clases anotadas](annotated-classes.md)).
- Sin historial de eventos en el endpoint, sin operaciones de escritura (`forceOpen`, `reset`) en él, sin refresco
  de la configuración. Micronaut 4 y Java 21 no están soportados por este módulo.

**Comparado con `micronaut-retry` y resilience4j-micronaut.** Ambos funcionan mediante la AOP `@Around` de
Micronaut: cada llamada interceptada pasa en tiempo de ejecución por una cadena de interceptores de métodos, y la
configuración se busca por método. Las políticas de resilience4cata son código generado que Micronaut solo
instancia. No se ha ejecutado ningún benchmark de los tres dentro de Micronaut, así que aquí no se da ninguna
cifra; consulta [rendimiento](../performance.md) para lo que sí se ha medido.
