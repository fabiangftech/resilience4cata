# Primeros pasos

> resilience4cata 0.1.0 está en preparación y **todavía no está publicada** en Maven Central. Hasta entonces, compílala desde el código fuente e instálala en tu repositorio local, o usa las coordenadas de abajo cuando salga la versión.

Esta página va de un build vacío a un servicio protegido, su configuración, sus eventos y sus tests. Cada snippet de Java de la página se compila y se ejecuta en `examples/src/test/java/dev/resilience4cata/examples/GuideTest.java`.

## Dependencias

Coordenadas: groupId `dev.resilience4cata`, versión `0.1.0`. Java 21 o más nuevo.

| Artefacto | Alcance | Qué es |
|---|---|---|
| `resilience4cata-core` | implementation | Políticas, registry, `Resilient`, `Pipeline`, eventos. Solo depende de JSpecify |
| `resilience4cata-annotations` | solo compilación | Las anotaciones. Retención `CLASS`, así que no hacen falta en runtime |
| `resilience4cata-processor` | annotation processor | Genera el código. **Obligatorio** para las anotaciones; nunca va en el class path de runtime |
| `resilience4cata-config` | implementation, opcional | Configuración externa: archivos properties, entorno, propiedades del sistema |
| `resilience4cata-testkit` | test, opcional | `ManualClock` y jitter con semilla para los tests |
| `resilience4cata-bom` | platform | Alinea las versiones de todos los anteriores |

**Gradle (Groovy DSL).** El path del annotation processor se resuelve aparte del class path de compilación, así que también necesita el BOM:

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.1.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.1.0')

    implementation 'dev.resilience4cata:resilience4cata-core'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'

    implementation 'dev.resilience4cata:resilience4cata-config'        // optional
    testImplementation 'dev.resilience4cata:resilience4cata-testkit'   // optional
}
```

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.1.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-core")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")

    implementation("dev.resilience4cata:resilience4cata-config")        // optional
    testImplementation("dev.resilience4cata:resilience4cata-testkit")   // optional
}
```

Los tipos anotados en las fuentes de test también necesitan las configuraciones de test: `testCompileOnly` para las anotaciones y `testAnnotationProcessor` para el processor (más el BOM en `testAnnotationProcessor`).

El processor está registrado en Gradle como processor incremental *isolating*, así que editar un archivo recompila solo lo que depende de él.

**Maven.** Importa el BOM, agrega las anotaciones como `provided` y pon el processor en `annotationProcessorPaths` con una versión explícita:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.resilience4cata</groupId>
      <artifactId>resilience4cata-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-core</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-annotations</artifactId>
    <scope>provided</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.13.0</version>
      <configuration>
        <release>21</release>
        <annotationProcessorPaths>
          <path>
            <groupId>dev.resilience4cata</groupId>
            <artifactId>resilience4cata-processor</artifactId>
            <version>0.1.0</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Agrega `resilience4cata-config` (alcance por defecto) y `resilience4cata-testkit` (alcance `test`) de la misma forma si los usas.

### JPMS

En un módulo con nombre, lee `core` (y `config` o `testkit` si los usas), lee las anotaciones solo en compilación, y exporta a `core` los paquetes que tienen tipos anotados, porque `core` es quien instancia las clases generadas:

```java
module com.acme.shop {
    requires dev.resilience4cata.core;
    requires static dev.resilience4cata.annotations;
    requires dev.resilience4cata.config;              // optional

    exports com.acme.shop.clients to dev.resilience4cata.core;
}
```

Sin el `exports … to dev.resilience4cata.core`, `Resilient.of` falla con [`R4C-RT-005`](errors.md#r4c-rt-005), cuyo mensaje indica la línea que hay que agregar.

## Tu primera interfaz anotada

Anota la interfaz de la dependencia que llamas. Aquí la consulta de un precio se reintenta hasta tres veces, la protege un circuit breaker llamado `prices`, se interrumpe a los dos segundos y responde con un fallback cuando todo falla:

```java
public interface PriceClient {

    @Retry(maxAttempts = 3, delay = "100ms")
    @CircuitBreaker(name = "prices")
    @Timeout("2s")
    @Fallback(method = "unknownPrice")
    BigDecimal price(String sku) throws IOException;

    default BigDecimal unknownPrice(String sku, Exception failure) {
        return BigDecimal.ZERO;
    }
}
```

En compilación el processor genera `PriceClient$Resilient`, una clase Java común que implementa `PriceClient` y llama a tu implementación a través de las políticas. Envuelve tu implementación con ella:

```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);

BigDecimal price = prices.price("sku-1");   // retried, timed out after 2 s, guarded by the "prices" breaker
```

- Las políticas se anidan en un orden fijo, de afuera hacia adentro: `Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → método`. Así el breaker registra cada intento, cada intento tiene su propio timeout, y el fallback también ve los rechazos, como un breaker abierto ([orden de ejecución](features.md#orden-de-ejecución)).
- Las duraciones son strings (`"250ms"`, `"2s"`, `"PT2S"`) que se verifican en compilación. Un atributo incorrecto, una duración inválida o un fallback con la firma equivocada es un error de compilación con un código de la [referencia de errores](errors.md).
- Los atributos que no escribes salen de los valores por defecto del registry (para `@Retry`: 3 intentos, backoff exponencial de 200 ms con full jitter).
- Un método que devuelve `CompletionStage<T>` o `CompletableFuture<T>` recibe la versión async de cada política, sin bloquear threads ([features](features.md)).
- Solo cuentan las anotaciones de la interfaz; las llamadas que tu implementación se hace a sí misma no pasan por las políticas. Si necesitas eso, anota una clase.

## Una clase anotada

Una clase no final puede llevar las anotaciones ella misma. El processor genera entonces una subclase, `StockService$Resilient`, que sobrescribe cada método protegido, así que las llamadas que el objeto se hace a sí mismo también quedan protegidas:

```java
public class StockService {
    private final Warehouse warehouse;

    public StockService(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    @Retry(maxAttempts = 3, delay = "50ms")
    public int stock(String sku) {
        return warehouse.count(sku);
    }

    public int total(List<String> skus) {
        int total = 0;
        for (String sku : skus) {
            total += stock(sku);   // self-invocation: goes through stock()'s retry
        }
        return total;
    }
}
```

`Warehouse` es cualquier interfaz con `int count(String sku)`. Crea el servicio con los argumentos del constructor después del registry:

```java
StockService stock = Resilient.create(StockService.class, registry, warehouse);
int total = stock.total(List.of("a", "b"));   // each stock(sku) call is retried
```

`new StockService$Resilient(registry, warehouse)` hace lo mismo con verificación en compilación. Las clases final, abstractas, sealed e internas, los records y los enums son errores de compilación ([clases anotadas](features.md#clases-anotadas)).

## La API programática

Para código que no puedes anotar (una clase de terceros, una lambda), construye un `Pipeline` una vez y reutilízalo:

```java
Pipeline<String> geocode = Pipeline.builder(registry, "geocoder", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(1)))
        .fallback(failure -> "unknown")
        .build();

String city = geocode.execute(() -> legacyGeocoder.city("10115"));
```

Las políticas se anidan en el mismo orden que con anotaciones, sin importar en qué orden llames a los métodos del builder ([API programática](features.md#api-programática)).

### Qué estilo usar

| Estilo | Úsalo cuando | Auto-invocación | Costo |
|---|---|---|---|
| Interfaz anotada + `Resilient.of` | La interfaz es tuya; la implementación puede ser cualquiera (un cliente HTTP generado, una lambda) | No se intercepta | Código generado lineal, la opción más rápida |
| Clase anotada + `Resilient.create` | Una clase no final es tuya y sus métodos se llaman entre sí | Se intercepta | Igual que el estilo de interfaz |
| `Pipeline` | No puedes anotar el código, o las políticas se eligen en runtime | n/a | Algo más por llamada; su único call site se vuelve megamórfico cuando muchos pipelines comparten una JVM ([benchmarks](benchmarks.md)) |

## Configuraciones con nombre y anotaciones compuestas

Los servicios reales comparten valores. Ponlos en el registry bajo un nombre, refiérete al nombre desde las anotaciones y agrupa las anotaciones en una propia:

```java
@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
public @interface RemoteCall {
}

@RemoteCall
public interface ShippingClient {

    String track(String parcel) throws IOException;

    @Retry(maxAttempts = 1)   // overrides one attribute of the bundle: never retried
    String cancel(String parcel) throws IOException;
}
```

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryDefaults(r -> r.delay(Duration.ofMillis(100)))
        .retry("external", r -> r.maxAttempts(5))
        .circuitBreaker("external", cb -> cb.failureRateThreshold(25).waitInOpen(Duration.ofSeconds(30)))
        .build();
```

- Los valores se resuelven de menor a mayor prioridad: valores por defecto de la librería, `*Defaults(...)`, la config con nombre, la anotación compuesta, la anotación a nivel de tipo, la anotación a nivel de método. Una anotación solo sobrescribe los atributos que escribe.
- `config` elige una plantilla; `name` elige la instancia con estado (la ventana de un breaker, los permisos de un rate limiter). Sin `name`, cada método tiene su propia instancia. Dos métodos con el mismo `name` la comparten.
- Un nombre de config desconocido falla al envolver el servicio, no en la primera llamada. La configuración se resuelve una vez y después es inmutable: no hay hot reload.

## Configuración externa

Con `resilience4cata-config`, operaciones puede ajustar los mismos servicios sin recompilar. Pon un `resilience4cata.properties` en el class path:

```properties
configs.external.retry.maxAttempts=4
configs.external.circuitBreaker.waitInOpen=10s
instances.prices.circuitBreaker.failureRateThreshold=30
```

y aplícalo al builder del registry:

```java
ResilienceConfig config = ResilienceConfig.load();   // resilience4cata.properties, R4C_* variables, r4c.* properties
ResilienceRegistry registry = config
        .applyTo(ResilienceRegistry.builder()
                .retry("external", r -> r.maxAttempts(5)))   // the file's maxAttempts=4 wins
        .build();
ShippingClient shipping = Resilient.of(ShippingClient.class, new HttpShippingClient(), registry);
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);
config.verifyInstances(registry);   // fails if an instances.<id> key matches no instance
```

- `defaults.<política>.*`, `configs.<nombre>.*` y `configs.<nombre>.extends` definen los valores por defecto y las configs con nombre; `instances.<id>.*` ajusta una instancia y les gana a sus anotaciones; `profiles.<perfil>.…` con `profiles.active` cambian valores por entorno.
- Variables de entorno: `R4C_CONFIGS_EXTERNAL_RETRY_MAXATTEMPTS=6`. Propiedades del sistema: `-Dr4c.configs.external.retry.maxAttempts=6`. Las fuentes se ordenan classpath < archivos < entorno < propiedades del sistema.
- Todos los problemas (clave desconocida, valor inválido, ciclo en `extends`) se reportan juntos en una `ConfigException`, con el origen y la línea de la clave y un "¿quisiste decir…?". Las claves y los códigos están en [features](features.md#cargar-configuración-externa-módulo-config) y en la [referencia de errores](errors.md).

## Eventos y snapshots

Los listeners se registran en el builder del registry, para un tipo de evento, la interfaz de eventos de una política, o `ResilienceEvent` para todos. Los tipos de evento que nadie escucha no cuestan nada:

```java
private static final System.Logger LOG = System.getLogger("shop.resilience");
```

```java
LongAdder retries = new LongAdder();
ResilienceRegistry registry = ResilienceRegistry.builder()
        .listen(CircuitBreakerEvent.StateTransition.class,
                t -> LOG.log(Level.WARNING, "{0}: {1} -> {2}", t.instance(), t.from(), t.to()))
        .listen(RetryEvent.Retrying.class, e -> retries.increment())
        .build();
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);
prices.price("sku-1");   // the first attempt fails, the second succeeds

CircuitBreakerSnapshot breaker = registry.circuitBreaker("prices").snapshot();
ResilienceSnapshot all = registry.snapshot();   // every instance created so far
```

- Los listeners corren de forma sincrónica en el thread que causó el evento; tienen que ser rápidos. Un listener que lanza una excepción no afecta la llamada.
- `breaker.state()`, `failureRate()`, `bufferedCalls()` y los demás son una copia de solo lectura, para health checks y métricas. `registry.circuitBreaker("prices")` también tiene `forceOpen()`, `disable()` y `reset()`.
- La lista de eventos está en [eventos e inspección del estado](features.md#eventos-e-inspección-del-estado).

## Tests con `testkit`

Un `ManualClock` reemplaza el reloj, los sleeps y los timers de un registry, así que un test con backoffs de segundos termina al instante:

```java
ManualClock clock = new ManualClock();
ResilienceRegistry registry = ResilienceTesting.registry(clock).build();   // also seeds the jitter
PriceClient prices = Resilient.of(PriceClient.class, new HttpPriceClient(), registry);

assertThat(prices.price("sku-1")).isEqualByComparingTo("9.99");   // one retry, no real sleep
assertThat(clock.elapsed()).isBetween(Duration.ZERO, Duration.ofMillis(100));
```

`clock.advance(Duration.ofSeconds(30))` adelanta el tiempo (por ejemplo, más allá del `waitInOpen` de un breaker), y `ResilienceTesting.elapseWaitInOpen(breaker, clock)` hace exactamente eso para un breaker. Los detalles y límites están en [tests](features.md#tests).

## Solución de problemas

**`[R4C-RT-003] No generated class … found.`** El processor no corrió sobre el tipo (o el tipo no tiene ninguna anotación de resiliencia). El mensaje dice:

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
```

Agrega el processor a `annotationProcessor` (Gradle) o a `annotationProcessorPaths` (Maven) como se muestra arriba. Ponerlo en el class path de compilación no alcanza: desde JDK 23, `javac` ya no ejecuta los processors que encuentra en el class path salvo que se le pida. No hay fallback por reflexión, por diseño.

**`warning: No processor claimed any of these annotations`** con `-Xlint:all -Werror`. Los builds con `-Xlint:all -Werror` funcionan: cuando el processor de resilience4cata es el único en el processor path reclama las anotaciones que ve, y junto a otros processors reclama solo las suyas (`dev.resilience4cata.annotation.*`), así los demás siguen viendo todo lo demás. Si la advertencia lista otras anotaciones, las dejó sin reclamar otro processor; agrega `-Xlint:-processing` a los argumentos del compilador.

**`[R4C-RT-004]`**: las versiones de `core` y `processor` no coinciden. Usa el BOM.

**`[R4C-RT-005]`**: un módulo con nombre no exporta el paquete a `dev.resilience4cata.core`; ver [JPMS](#jpms).

Cada error de compilación (`R4C-APT`, `R4C-ATR`, …), de runtime (`R4C-RT`) y de configuración (`R4C-CFG`) tiene una sección con un ejemplo incorrecto y la corrección en la [referencia de errores](errors.md).
