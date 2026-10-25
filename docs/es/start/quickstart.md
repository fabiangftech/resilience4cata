# Inicio rápido

En unos cinco minutos, protege una llamada inestable con un retry, un circuit breaker, un timeout y un fallback, y míralos funcionar.

Necesitas Java 21 o posterior y un proyecto Gradle o Maven. Todo lo que sigue es un programa ejecutable; su código se compila y se ejecuta en los tests del proyecto, así que funciona tal como se muestra.

## 1. Agrega las dependencias

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:0.3.0')
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:0.3.0')

    implementation 'dev.resilience4cata:resilience4cata-core'
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    val r4cBom = platform("dev.resilience4cata:resilience4cata-bom:0.3.0")
    implementation(r4cBom)
    annotationProcessor(r4cBom)

    implementation("dev.resilience4cata:resilience4cata-core")
    compileOnly("dev.resilience4cata:resilience4cata-annotations")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor")
}
```

#### **Maven**

```xml
<dependencies>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-core</artifactId>
    <version>0.3.0</version>
  </dependency>
  <dependency>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-annotations</artifactId>
    <version>0.3.0</version>
    <scope>provided</scope>
  </dependency>
</dependencies>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>0.3.0</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

La [página de instalación](installation.md) explica cada línea, el BOM con Maven, los IDE y JPMS.

## 2. Anota una interfaz

Anota la interfaz de la dependencia que llamas. Esta se reintenta hasta tres veces, está protegida por un circuit breaker llamado `prices`, se interrumpe a los dos segundos, y responde con `unknownPrice` cuando todo lo demás falla:

<!-- snippet: quickstart-interface -->
```java
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.math.BigDecimal;

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

## 3. Impleméntala

Cualquier implementación sirve: un cliente HTTP escrito a mano, uno generado, una lambda. Este sustituto falla en su primera llamada:

<!-- snippet: quickstart-implementation -->
```java
import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for a real HTTP client: the first call fails, the next ones answer. */
public class FlakyPriceClient implements PriceClient {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public BigDecimal price(String sku) throws IOException {
        if (calls.incrementAndGet() == 1) {
            throw new IOException("connection reset");
        }
        return new BigDecimal("9.99");
    }
}
```

## 4. Envuélvela y llámala

`Resilient.of` pone las políticas alrededor de tu implementación. Construye el `ResilienceRegistry` una vez y compártelo: guarda las configuraciones y el estado de cada breaker.

<!-- snippet: quickstart-main -->
```java
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();

        PriceClient prices = Resilient.of(PriceClient.class, new FlakyPriceClient(), registry);
        System.out.println(prices.price("sku-1"));   // 9.99: the first attempt failed, the retry succeeded

        PriceClient down = Resilient.of(PriceClient.class, sku -> {
            throw new IOException("service down");
        }, registry);
        System.out.println(down.price("sku-1"));     // 0: all three attempts failed, the fallback answered

        System.out.println(registry.circuitBreaker("prices").state());   // CLOSED: too few calls to judge yet
    }
}
```

## 5. Ejecútalo

Compila y ejecuta `Main` (desde el IDE, o con la tarea de ejecución de tu build). Imprime:

```text
9.99
0
CLOSED
```

La segunda llamada tarda hasta unos cientos de milisegundos: entre intentos, el retry espera un tiempo aleatorio menor que 100 ms y después menor que 200 ms (backoff exponencial con jitter completo).

## Qué acaba de pasar

- **En compilación**, el procesador de anotaciones leyó `PriceClient` y escribió `PriceClient$Resilient.java` a su lado (en `build/generated/sources/annotationProcessor/java/main/` con Gradle, `target/generated-sources/annotations/` con Maven). Es Java común: ábrelo y léelo. Un error de tipeo en un atributo, una duración inválida o un fallback con la firma incorrecta habrían sido errores de compilación.
- **`Resilient.of`** cargó esa clase una vez y creó las políticas a partir del registry. Las llamadas pasan directamente por código generado, sin proxies y sin reflexión.
- **La primera llamada** falló con `IOException`, así que el retry esperó y volvió a intentar; el segundo intento devolvió `9.99`.
- **El segundo wrapper** siempre falla, así que después de tres intentos respondió el fallback `unknownPrice` con `0`.
- **El breaker** registró cada intento, cinco llamadas con cuatro fallas, pero siguió `CLOSED`: por defecto solo evalúa cuando su ventana tiene 100 llamadas (`minimumCalls`).
- Las políticas se anidan en un orden fijo, de afuera hacia adentro: `Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead → method`. Por eso el fallback ve el resultado final y cada intento del retry tiene su propio timeout de dos segundos.

## Próximos pasos

- [Cómo funciona](how-it-works.md): qué genera el procesador, los ids de instancia, de dónde viene la configuración.
- [Políticas](../policies/README.md): cada atributo de las seis políticas, con sus valores por defecto.
- [Configuración](../guides/configuration.md): ajusta el mismo código desde un archivo properties, variables de entorno o propiedades del sistema.
- [Tests](../guides/testing.md): ejecuta este ejemplo sobre un `ManualClock`, sin esperas reales.
- [Métodos async](../guides/async.md): devuelve un `CompletableFuture` y nada se bloquea.
