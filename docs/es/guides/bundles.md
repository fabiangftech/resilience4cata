# Bundles

Un bundle es una anotación propia que lleva anotaciones de resiliencia, para que un equipo escriba `@RemoteCall` una vez en lugar de repetir cuatro anotaciones en cada método.

## Define un bundle

Marca la anotación con `@ResiliencePolicy`, dale retención `CLASS` y los targets `METHOD` y `TYPE`, y pon las anotaciones de las políticas sobre ella:

<!-- snippet: bundles-annotation -->
```java
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
public @interface RemoteCall {
}
```

Sin `@ResiliencePolicy`, el procesador ignoraría las políticas de la anotación, así que en su lugar reporta [`R4C-CMP-001`](../errors.md#r4c-cmp-001).

## Úsalo

En un tipo, un bundle (como cualquier anotación de resiliencia) se aplica a todos los métodos; en un método, solo a ese método. Las anotaciones escritas directamente en el elemento ganan sobre el bundle, atributo por atributo:

<!-- snippet: bundles-usage -->
```java
@RemoteCall
public interface ShippingClient {

    String track(String parcel) throws IOException;

    @Retry(maxAttempts = 1)   // overrides one attribute of the bundle: never retried
    String cancel(String parcel) throws IOException;
}
```

`track` recibe el bundle completo. `cancel` también recibe el bundle, salvo `maxAttempts`, que su propio `@Retry` fija en 1; su `config = "external"` sigue viniendo del bundle.

## Bundles y configuraciones con nombre

Un bundle que fija `config = "…"` en lugar de valores literales deja los números fuera del código: el registry, o la [configuración externa](configuration.md), define qué significa `external`, y operaciones puede cambiarlo sin recompilar.

<!-- snippet: bundles-registry -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryDefaults(r -> r.delay(Duration.ofMillis(100)))
        .retry("external", r -> r.maxAttempts(5))
        .circuitBreaker("external", cb -> cb.failureRateThreshold(25).waitInOpen(Duration.ofSeconds(30)))
        .build();
```

Con este registry, `track` hace hasta 5 intentos separados por 100 ms (antes del jitter) y su breaker se abre con una tasa de fallas del 25%. Un nombre de config que el registry no conoce falla al envolver la interfaz ([`R4C-REG-004`](../errors.md#r4c-reg-004)), no en la primera llamada.

## Bundles de bundles

Un bundle puede llevar otros bundles. Sus propias anotaciones de política ganan sobre las que hereda, atributo por atributo:

<!-- snippet: bundles-nested -->
```java
@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@RemoteCall
@Retry(maxAttempts = 5)   // beats RemoteCall's retry attributes, keeps its config = "external"
public @interface Idempotent {
}
```

Un método anotado con `@Idempotent` recibe el breaker y el timeout de dos segundos de `RemoteCall`, y un retry con `config = "external"` y 5 intentos.

## Precedencia

Para cada atributo, de menor a mayor precedencia:

1. Los valores por defecto incorporados y los del registry, después la configuración con nombre elegida con `config = "…"` ([de dónde viene la configuración](../start/how-it-works.md#de-dónde-viene-la-configuración)).
2. Las anotaciones a nivel de tipo: las de la interfaz que declara el método (cuando es una superinterfaz), después las de la interfaz que envuelves.
3. Las anotaciones a nivel de método.

En un mismo elemento, una anotación directa gana sobre todos los bundles, y un bundle gana sobre los bundles que lleva. Un bundle a nivel de método gana sobre una anotación a nivel de tipo, incluso directa, porque el método es el elemento más específico. En las [clases anotadas](annotated-classes.md#anotaciones-en-la-clase-sus-superclases-y-sus-interfaces), la clase y sus superclases agregan sus niveles por encima de los de la interfaz.

Los overrides por instancia (`instances.<id>.*` y `retryInstance(...)`) se aplican después de todo esto.

## Dos bundles que no coinciden

Si dos bundles en el mismo elemento fijan el mismo atributo con valores distintos, ninguno puede ganar, así que el build falla con [`R4C-CMP-002`](../errors.md#r4c-cmp-002). No hay un "gana el último" silencioso:

<!-- not-compiled: a deliberate compile error (R4C-CMP-002) -->
```java
public interface PriceClient {
    @Fast       // @Retry(maxAttempts = 2)
    @Patient    // @Retry(maxAttempts = 5)
    String price(String sku);
}
```

Quédate con uno de los dos, haz que coincidan, o escribe el atributo directamente en el método, que gana sobre ambos.
