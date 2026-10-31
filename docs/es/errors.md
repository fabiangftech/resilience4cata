# Referencia de errores

Todo error que reporta resilience4cata empieza con un código estable entre corchetes, por ejemplo:

```text
error: [R4C-FB-002] @Fallback(method = "findFallbak") on find: no method named findFallbak in UserClient. Did you mean findFallback? Expected a default or static method java.lang.String findFallbak(java.lang.String, <a Throwable type> e), where the exception parameter is optional.
```

El código indica qué sección de esta página explica el error. El resto del mensaje dice dónde (el
compilador señala el elemento anotado; la clase, el método y el atributo aparecen en el texto), qué está mal
y cómo corregirlo. Los mensajes están en inglés.

| Prefijo | Lo reporta | Cuándo |
|---|---|---|
| `R4C-APT` | procesador de anotaciones | ubicación de las anotaciones, problemas generales |
| `R4C-ATR` | procesador de anotaciones | valores de atributos |
| `R4C-CMP` | procesador de anotaciones | anotaciones compuestas (bundles `@ResiliencePolicy`) |
| `R4C-ASY` | procesador de anotaciones | métodos asíncronos |
| `R4C-HDG` | procesador de anotaciones | `@Hedge` |
| `R4C-CLS` | procesador de anotaciones | clases `classifier` y `onExhaustedResult` |
| `R4C-FB` | procesador de anotaciones | `@Fallback` |
| `R4C-SUB` | procesador de anotaciones | clases anotadas (subclases generadas) |
| `R4C-RT` | `resilience4cata-core` en ejecución | `Resilient.of`, `Resilient.create`, `Pipeline.Builder` |
| `R4C-REG` | `resilience4cata-core` en ejecución | builders `*Config`, `ResilienceRegistry` y su builder: configuraciones con nombre, instancias |
| `R4C-CFG` | `resilience4cata-config` en ejecución | configuración externa (`ResilienceConfig`) |
| `R4C-SPR` | `resilience4cata-spring` al arrancar | la integración con Spring Boot: beans que no pueden protegerse como se declararon |
| `R4C-MN` | `resilience4cata-micronaut` al arrancar o al crear un bean | la integración con Micronaut: beans que no pueden protegerse como se declararon |
| `R4C-QK` | `resilience4cata-quarkus` mientras Quarkus construye la aplicación | la extensión de Quarkus: beans que no pueden protegerse como se declararon, o quedarían protegidos dos veces |
| `R4C-TK` | `resilience4cata-testkit` en las pruebas | `ManualClock`, `ResilienceTesting` |
| `R4C-JU` | `resilience4cata-junit` en las pruebas | la extensión de JUnit (`@ResilienceTest`) |

Los códigos son estables: nunca se renumeran ni cambian de significado. Un error nuevo recibe el siguiente
número libre de su área; un error que ya no se reporta conserva su código, marcado abajo como retirado.

El build compila los ejemplos Java, erróneo y corregido, de los códigos del procesador de anotaciones
(`ErrorCodeTest` en el módulo `processor`): cada ejemplo erróneo reporta su código y cada corrección compila
sin advertencias.

## Procesador de anotaciones: ubicación (R4C-APT)

### R4C-APT-001

**Causa:** una anotación de resiliencia en un enum o un record, o en uno de sus métodos. El estilo de
envoltorio necesita una interfaz, y el estilo de subclase debe extender el tipo, pero los enums y los records
son finales.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public record PriceClient(String baseUrl) {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Corrección:** declara los métodos en una interfaz, impleméntala con el record y envuelve la implementación
con `Resilient.of(PriceClient.class, implementation, registry)`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    String price(String sku);
}
```

### R4C-APT-002

**Causa:** una anotación de resiliencia en un método estático o privado. Las llamadas a esos métodos no se
pueden interceptar con un envoltorio ni con una subclase.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    static String price(String sku) {
        return "1";
    }
}
```

**Corrección:** anota un método de instancia no privado (abstracto o default en una interfaz; sobrescribible
en una clase).

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    String price(String sku);
}
```

### R4C-APT-003

**Causa:** la interfaz anotada es privada (un miembro privado de una clase). La clase generada
`<Interface>$Resilient` es de primer nivel y debe implementarla.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Clients {
    private interface PriceClient {
        @Retry
        String price(String sku);
    }
}
```

**Corrección:** haz la interfaz package-private o pública.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry
        String price(String sku);
    }
}
```

### R4C-APT-004

**Causa:** una anotación tiene un atributo que el procesador no conoce, por ejemplo
`Unknown attribute @Retry.hedges`. Pasa cuando `resilience4cata-annotations` es más nuevo que
`resilience4cata-processor`.

**Corrección:** usa la misma versión para todos los artefactos de resilience4cata, idealmente mediante el BOM:

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:<version>')
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    implementation 'dev.resilience4cata:resilience4cata-core'
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:<version>')
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

### R4C-APT-005

**Causa:** el procesador no pudo escribir un archivo fuente generado, o el archivo de metadatos de imagen
nativa que escribe junto a él (`META-INF/native-image/dev.resilience4cata.generated/<Interface>$Resilient/reachability-metadata.json`
en la salida de clases): falló el `Filer` del compilador, por ejemplo porque el directorio de salida no
admite escritura, o porque otro procesador o un archivo fuente ya define `<Interface>$Resilient`.

**Corrección:** revisa el directorio de fuentes generadas del build (con Gradle,
`build/generated/sources/annotationProcessor`), asegúrate de que ninguna clase escrita a mano se llame
`<Interface>$Resilient` y recompila tras `./gradlew clean`.

### R4C-APT-006

**Causa:** la opción del compilador `-Ar4c.inject` tiene un valor distinto de `micronaut`; o es
`-Ar4c.inject=micronaut` pero `io.micronaut.context.annotation.Replaces` de Micronaut no está en el class path de
compilación, así que la subclase generada de una clase anotada no puede reemplazar a la clase como bean de
Micronaut. La opción se lee una vez por compilación; el error no tiene posición en el código.

**Incorrecto** (compilado con `-Ar4c.inject=spring`):

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class InventoryService {
    @Retry
    public int stock(String sku) {
        return 1;
    }
}
```

**Corrección:** usa `-Ar4c.inject=micronaut` en una aplicación Micronaut, con `io.micronaut:micronaut-inject` en el
class path de compilación (toda aplicación Micronaut lo tiene); quita la opción con cualquier otro contenedor
(Spring Boot no necesita ninguna: consulta la [guía de Spring Boot](guides/spring.md)). Consulta la
[guía de Micronaut](guides/micronaut.md#classes).

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.add('-Ar4c.inject=micronaut')
}
```

## Procesador de anotaciones: valores de atributos (R4C-ATR)

### R4C-ATR-001

**Causa:** un atributo de duración (`delay`, `maxDelay`, `maxDuration`, `slowCallDuration`, `waitInOpen`,
`maxWaitInHalfOpen`, `maxWait`, `period`, `budgetWindow`, `window`, o el valor de `@Timeout`) no es una
duración válida.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(delay = "2 seconds")
    String price(String sku);
}
```

**Corrección:** usa un número seguido de `ns`, `us`, `ms`, `s`, `m`, `h` o `d` (`"250ms"`, `"2s"`), una
duración ISO-8601 (`"PT2S"`) o `"0"`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(delay = "2s")
    String price(String sku);
}
```

### R4C-ATR-002

**Causa:** una duración que debe ser mayor que cero vale `"0"`: el valor de `@Timeout` o
`@RateLimiter.period`.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Timeout;

public interface PriceClient {
    @Timeout("0")
    String price(String sku);
}
```

**Corrección:** pon una duración positiva. Para desactivar la política, usa `enabled = false` (o la
configuración).

```java
package com.acme;

import dev.resilience4cata.annotation.Timeout;

public interface PriceClient {
    @Timeout("2s")
    String price(String sku);
}
```

### R4C-ATR-003

**Causa:** un conteo es menor que 1: `maxAttempts`, `windowSize`, `minimumCalls`, `halfOpenCalls`,
`maxConcurrent`, `permits`, `initialLimit`, `minLimit` o `maxLimit`.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(maxAttempts = 0)
    String price(String sku);
}
```

**Corrección:** ponlo en 1 o más (`maxAttempts` cuenta la primera llamada), u omítelo para usar el valor
configurado.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(maxAttempts = 3)
    String price(String sku);
}
```

### R4C-ATR-004

**Causa:** `@Retry.multiplier` es menor que 1, infinito o NaN.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(multiplier = 0.5)
    String price(String sku);
}
```

**Corrección:** usa un número finito de al menos 1 (1 mantiene la espera constante).

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(multiplier = 2.0)
    String price(String sku);
}
```

### R4C-ATR-005

**Causa:** `@CircuitBreaker.failureRateThreshold` o `slowCallRateThreshold` no es un porcentaje mayor que 0
y como mucho 100.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;

public interface PriceClient {
    @CircuitBreaker(failureRateThreshold = 150)
    String price(String sku);
}
```

**Corrección:** usa un valor en (0, 100].

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;

public interface PriceClient {
    @CircuitBreaker(failureRateThreshold = 50)
    String price(String sku);
}
```

### R4C-ATR-006

**Causa:** retirado; ya no se reporta. `enabled` era un string (`""`, `"true"` o `"false"`); desde 0.12.0 es un
`boolean` (`enabled = false`), que revisa el compilador.

### R4C-ATR-007

**Causa:** un atributo del presupuesto de reintentos o de hedge está fuera de rango: `budgetPercent` es
negativo, infinito o NaN, `budgetMinRetriesPerSecond` (`@Retry`) o `budgetMinHedgesPerSecond` (`@Hedge`) es
negativo, o `budgetWindow` es menor que 1 s o mayor que 1 h. (Una `budgetWindow` que ni siquiera es una duración es [R4C-ATR-001](#r4c-atr-001).)

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(budgetPercent = 20, budgetWindow = "500ms")
    String price(String sku);
}
```

**Corrección:** usa un porcentaje de 0 o más, una reserva de 0 o más reintentos por segundo y una ventana
entre `"1s"` y `"1h"`; u omite el atributo para heredarlo (`budgetWindow` vale `10s` por defecto).

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(budgetPercent = 20, budgetMinRetriesPerSecond = 1, budgetWindow = "10s")
    String price(String sku);
}
```

### R4C-ATR-008

**Causa:** `@Hedge(maxAttempts = …)` es menor que 2 o mayor que 10. `maxAttempts` cuenta todos los intentos,
incluido el primero, así que 1 nunca lanzaría un hedge; más de 10 intentos en vuelo para una llamada no se
admiten.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(maxAttempts = 1, delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

**Corrección:** usa un valor de 2 (un hedge, el valor por defecto) a 10, o quita `@Hedge` si la llamada no debe
cubrirse.

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(maxAttempts = 2, delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

### R4C-ATR-009

**Causa:** los límites de `@AdaptiveLimit` están desordenados (`minLimit` por encima de `maxLimit`, o
`initialLimit` fuera de ellos), o su `window` dura menos de 10 ms o más de 1 h. Solo se comparan los límites
puestos en la anotación; uno que viene de una configuración con nombre o externa se revisa cuando el registry la
resuelve ([R4C-REG-003](#r4c-reg-003)).

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.AdaptiveLimit;

public interface OrderClient {
    @AdaptiveLimit(minLimit = 50, maxLimit = 20)
    String place(String order);
}
```

**Corrección:** mantén `minLimit <= initialLimit <= maxLimit`, y una `window` de `"10ms"` a `"1h"`.

```java
package com.acme;

import dev.resilience4cata.annotation.AdaptiveLimit;

public interface OrderClient {
    @AdaptiveLimit(minLimit = 20, maxLimit = 50)
    String place(String order);
}
```

## Procesador de anotaciones: anotaciones compuestas (R4C-CMP)

### R4C-CMP-001

**Causa:** un tipo de anotación lleva una anotación de resiliencia pero no está marcado con
`@ResiliencePolicy`, así que el procesador ignoraría sus políticas allí donde se use.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Patient {
}
```

**Corrección:** añade `@ResiliencePolicy`.

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Patient {
}
```

### R4C-CMP-002

**Causa:** dos anotaciones compuestas en el mismo elemento dan valores distintos al mismo atributo, y
ninguna puede ganar.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public interface PriceClient {
    @Fast
    @Patient
    String price(String sku);
}

@ResiliencePolicy
@Retry(maxAttempts = 2)
@Retention(RetentionPolicy.CLASS)
@interface Fast {
}

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@interface Patient {
}
```

**Corrección:** deja solo uno de los bundles, haz que coincidan, o define el atributo directamente en el
elemento, que gana sobre todos los bundles.

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public interface PriceClient {
    @Patient
    String price(String sku);
}

@ResiliencePolicy
@Retry(maxAttempts = 2)
@Retention(RetentionPolicy.CLASS)
@interface Fast {
}

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@interface Patient {
}
```

## Procesador de anotaciones: métodos asíncronos (R4C-ASY)

### R4C-ASY-001

**Causa:** un método anotado devuelve un tipo `CompletionStage` no soportado: un tipo raw, un argumento
`? super X`, o un subtipo de `CompletionStage` distinto de `CompletableFuture`.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<? super Integer> price(String sku);
}
```

**Corrección:** devuelve `CompletionStage<T>` o `CompletableFuture<T>`, donde `T` también puede ser `?` o
`? extends X`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<Integer> price(String sku);
}
```

### R4C-ASY-002

**Causa:** un método anotado devuelve un tipo reactivo perezoso: un `Flow.Publisher` o un `Publisher` de Reactive
Streams (`Flowable` de RxJava, una subclase del `Flux` de Reactor), `Uni` o `Multi` de Mutiny, o `Single`, `Maybe`,
`Completable` u `Observable` de RxJava. Un método así solo construye un pipeline que se ejecuta después, al
suscribirse, así que las políticas protegerían su construcción, no el trabajo: un fallo nunca se reintentaría ni
tendría timeout. El `Mono` y el `Flux` de Reactor están soportados con `resilience4cata-reactor` (ver
[R4C-ASY-003](#r4c-asy-003)).

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.Flow;

public interface PriceClient {
    @Retry
    Flow.Publisher<Integer> price(String sku);
}
```

**Corrección:** devuelve `CompletionStage<T>` o `CompletableFuture<T>`, convirtiendo en la implementación
(`uni.subscribeAsCompletionStage()`), o el `Mono<T>` o el `Flux<T>` de Reactor con `resilience4cata-reactor`, o
mantén el tipo reactivo y usa los operadores de retry y timeout de la propia librería reactiva.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<Integer> price(String sku);
}
```

### R4C-ASY-003

**Causa:** un método anotado devuelve el `Mono` o el `Flux` de Reactor y `resilience4cata-reactor` no está en el
classpath de compilación (para un `Flux`, la versión 0.11.0 o posterior). El código generado de estos métodos llama a
ese módulo.

**Incorrecto:** con `reactor-core` pero sin `resilience4cata-reactor`:

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Mono;

public interface PriceClient {
    @Retry
    Mono<Integer> price(String sku);
}
```

**Corrección:** agrega `dev.resilience4cata:resilience4cata-reactor` (la misma versión que los demás módulos) a las
dependencias, junto a `resilience4cata-core`. El código queda igual.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Mono;

public interface PriceClient {
    @Retry
    Mono<Integer> price(String sku);
}
```

### R4C-ASY-004

**Causa:** un método anotado que devuelve el `Flux` de Reactor tiene un `@Hedge`, un classifier en `@Retry` o en
`@CircuitBreaker`, o un `@PolicyOrder` que cambia su orden. Un hedge mezclaría los elementos de dos streams; un
classifier juzga un resultado, y un stream tiene muchos; y la regla de que el retry y el fallback solo actúan antes
del primer elemento del stream está definida para el orden por defecto.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import reactor.core.publisher.Flux;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    Flux<Integer> quotes(String sku);
}
```

**Corrección:** usa `@Retry` (con `retryOn`/`abortOn`, o `recordOn`/`ignoreOn` en el breaker, en vez de un
classifier), mantén el orden por defecto, o devuelve un `Mono` o un `CompletionStage` donde necesites el hedge o el
classifier.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Flux;

public interface QuoteClient {
    @Retry
    Flux<Integer> quotes(String sku);
}
```

## Procesador de anotaciones: hedges (R4C-HDG)

### R4C-HDG-001

**Causa:** `@Hedge` se aplica a un método sincrónico: uno que no devuelve `CompletionStage<T>` ni
`CompletableFuture<T>`. Un hedge vuelve a lanzar la llamada mientras el primer intento sigue en curso, y eso
necesita una llamada asíncrona. Un `@Hedge` en el tipo, o en un bundle `@ResiliencePolicy`, se aplica a todos
los métodos, así que se informa cada método sincrónico del tipo.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    String quote(String sku);
}
```

**Corrección:** devuelve un `CompletableFuture` (o un `CompletionStage`), o quita `@Hedge` del método. Para un `@Hedge` en el tipo, anota los métodos async en lugar del tipo.

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

### R4C-HDG-002

**Causa:** `@Hedge` y `@Retry` se aplican al mismo método, directamente, en el tipo o a través de bundles
`@ResiliencePolicy`. Ocupan el mismo lugar en el orden de las políticas, y un retry alrededor de un hedge
multiplicaría los intentos.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    @Retry(maxAttempts = 3)
    CompletableFuture<String> quote(String sku);
}
```

**Corrección:** quédate con uno. Un hedge ya lanza un intento nuevo enseguida cuando uno falla con una falla de
`hedgeOn`, así que `maxAttempts` en el hedge cubre el reintento simple.

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms", maxAttempts = 3)
    CompletableFuture<String> quote(String sku);
}
```

## Procesador de anotaciones: orden de las políticas (R4C-ORD)

### R4C-ORD-001

**Causa:** `@PolicyOrder` nombra una capa más de una vez (en el método, en su tipo o en un bundle). Cada capa tiene
un solo lugar en el orden.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportClient {
    @Retry
    @Timeout("2s")
    @PolicyOrder({Layer.TIMEOUT, Layer.RETRY, Layer.TIMEOUT})
    String report(String id);
}
```

**Corrección:** nombra cada capa una sola vez, de la más exterior a la más interior.

```java
package com.acme;

import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportClient {
    @Retry
    @Timeout("2s")
    @PolicyOrder({Layer.TIMEOUT, Layer.RETRY})
    String report(String id);
}
```

## Procesador de anotaciones: clasificadores (R4C-CLS)

La clase generada crea una instancia de cada clase `classifier` por método, así que la clase debe ser
concreta, no genérica, con un constructor sin argumentos accesible, e implementar `Classifier<T>` para el
tipo de resultado del método.

### R4C-CLS-001

**Causa:** `classifier` está definido, pero `dev.resilience4cata.Classifier` no está en el classpath de
compilación.

**Incorrecto:** compilar esto sin `resilience4cata-core` en el classpath:

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Corrección:** añade la dependencia de core:

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

### R4C-CLS-002

**Causa:** `classifier` nombra una interfaz, una anotación o un enum en lugar de una clase.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Classifier.class)
    String price(String sku);
}
```

**Corrección:** nombra una clase que implemente `Classifier<T>`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-003

**Causa:** la clase del clasificador es abstracta, así que no se puede instanciar.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    abstract class Retryable implements Classifier<String> {
    }
}
```

**Corrección:** usa una clase concreta.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-004

**Causa:** la clase del clasificador declara parámetros de tipo; el código generado solo podría crearla con
un tipo raw no comprobado.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable<T> implements Classifier<T> {
        @Override
        public Outcome classify(T result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Corrección:** implementa `Classifier<T>` con un `T` concreto (o un supertipo, como `Classifier<Object>`).

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<Object> {
        @Override
        public Outcome classify(Object result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-005

**Causa:** la clase del clasificador (o una clase que la contiene) es privada, o package-private en otro
paquete, así que la clase generada, en el paquete de la interfaz, no puede crearla.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    private static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Corrección:** hazla pública, o package-private en el paquete de la interfaz.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-006

**Causa:** el clasificador es una clase interna (anidada no estática), que necesita una instancia de su
clase contenedora.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Corrección:** hazla `static`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-007

**Causa:** el clasificador no tiene un constructor sin argumentos que la clase generada pueda llamar.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        private Retryable() {
        }

        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Corrección:** añade un constructor sin argumentos público (o package-private, en el paquete de la
interfaz), o quita el privado.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-008

**Causa:** la clase del clasificador no implementa `dev.resilience4cata.Classifier`.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = String.class)
    String price(String sku);
}
```

**Corrección:** nombra una clase que implemente `Classifier<T>`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-009

**Causa:** el clasificador implementa el tipo raw `Classifier`, así que no se puede comprobar su tipo de
resultado.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier {
        @Override
        public Outcome classify(Object result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Corrección:** implementa `Classifier<T>` con el tipo de resultado del método, como sugiere el mensaje.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-010

**Causa:** el clasificador implementa `Classifier<X>`, pero el resultado del método (el valor del stage, en
un método asíncrono) no es asignable a `X`.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<Integer> {
        @Override
        public Outcome classify(Integer result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Corrección:** implementa `Classifier` para el tipo de resultado del método o un supertipo suyo.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-011

**Causa:** `@Retry` define a la vez `classifier` y `retryOn`, o `@CircuitBreaker` define a la vez
`classifier` y `recordOn`. El clasificador decide qué excepciones son fallos, así que la lista se ignoraría.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

public interface PriceClient {
    @Retry(classifier = Retryable.class, retryOn = IOException.class)
    String price(String sku) throws IOException;

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Corrección:** quita `retryOn` (o `recordOn`) y devuelve `Outcome.FAILURE` para esas excepciones desde el
clasificador. `abortOn` e `ignoreOn` siguen aplicándose antes del clasificador.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku) throws IOException;

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
```

### R4C-CLS-012

**Causa:** `@Retry` define `onExhaustedResult` sin `classifier`. Solo un clasificador puede rechazar un
resultado, así que el ajuste nunca se aplicaría.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(onExhaustedResult = ExhaustedResult.THROW)
    String price(String sku);
}
```

**Corrección:** añade un clasificador que rechace los resultados a reintentar, o quita `onExhaustedResult`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = NotEmpty.class, onExhaustedResult = ExhaustedResult.THROW)
    String price(String sku);

    final class NotEmpty implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure != null || result == null || result.isEmpty() ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
```

## Procesador de anotaciones: fallbacks (R4C-FB)

Un `@Fallback` nombra una clase handler, `@Fallback(MyHandler.class)`, o métodos hermanos,
`@Fallback(method = "name")`. Una clase handler debe cumplir las mismas reglas que un clasificador: concreta,
no genérica, accesible, con constructor sin argumentos, e implementar `FallbackHandler<T>` para el tipo de
resultado del método.

### R4C-FB-001

**Causa:** `@Fallback` no define ni una clase handler ni un nombre de método, o define ambos.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback
    String find(String id);
}
```

**Corrección:** define exactamente uno de los dos.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-002

**Causa:** `@Fallback(method = "...")` nombra un método que no existe en la interfaz (o clase). El mensaje
sugiere el nombre existente más parecido.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallbak")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

**Corrección:** corrige el nombre o añade el método. Recibe los mismos parámetros que el método anotado,
opcionalmente seguidos de la excepción, y devuelve el mismo tipo.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-003

**Causa:** existen métodos con el nombre del fallback, pero ninguno sirve. El mensaje lista cada candidato y
por qué se descartó: privado (la clase generada no puede llamarlo), tipo de retorno incorrecto, una excepción
checked que el método anotado no declara, parámetros de tipo distintos, o un parámetro de excepción que no es
una clase. No se listan los candidatos con otros parámetros.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    private String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

**Corrección:** en una interfaz, usa un método default o estático; en una clase, uno no privado. Ajusta la
firma a la que muestra el mensaje.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-004

**Causa:** `@Fallback` nombra una clase handler, pero `dev.resilience4cata.FallbackHandler` no está en el
classpath de compilación.

**Incorrecto:** compilar esto sin `resilience4cata-core` en el classpath:

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Corrección:** añade la dependencia de core:

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

### R4C-FB-005

**Causa:** el handler que nombra `@Fallback` es una interfaz, una anotación o un enum en lugar de una clase.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(FallbackHandler.class)
    String find(String id);
}
```

**Corrección:** nombra una clase que implemente `FallbackHandler<T>`.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-006

**Causa:** la clase handler es abstracta.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    abstract class Unknown implements FallbackHandler<String> {
    }
}
```

**Corrección:** usa una clase concreta.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-007

**Causa:** la clase handler declara parámetros de tipo.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown<T> implements FallbackHandler<T> {
        @Override
        public T apply(FallbackContext context) {
            return null;
        }
    }
}
```

**Corrección:** implementa `FallbackHandler<T>` con un `T` concreto. Para un método genérico, usa un método
hermano (ver [R4C-FB-014](#r4c-fb-014)).

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-008

**Causa:** la clase handler (o una clase que la contiene) es privada, o package-private en otro paquete, así
que la clase generada no puede crearla.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    private static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Corrección:** hazla pública, o package-private en el paquete de la interfaz.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-009

**Causa:** el handler es una clase interna (anidada no estática).

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Corrección:** hazla `static`.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-010

**Causa:** el handler no tiene un constructor sin argumentos que la clase generada pueda llamar.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        private Unknown() {
        }

        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Corrección:** añade un constructor sin argumentos público (o package-private, en el paquete de la
interfaz), o quita el privado.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-011

**Causa:** la clase handler no implementa `dev.resilience4cata.FallbackHandler`.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(String.class)
    String find(String id);
}
```

**Corrección:** nombra una clase que implemente `FallbackHandler<T>`.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-012

**Causa:** el handler implementa el tipo raw `FallbackHandler`.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler {
        @Override
        public Object apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Corrección:** implementa `FallbackHandler<T>` con el tipo de resultado del método, como sugiere el mensaje.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-013

**Causa:** el handler implementa `FallbackHandler<X>`, pero `X` no es asignable al tipo de retorno del
método. En un método asíncrono que devuelve un stage de `T`, `X` puede ser `T` o `CompletionStage<T>`.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<Integer> {
        @Override
        public Integer apply(FallbackContext context) {
            return 0;
        }
    }
}
```

**Corrección:** implementa `FallbackHandler` para el tipo de retorno del método (con boxing, si es
primitivo).

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-014

**Causa:** una clase handler en un método genérico cuyo tipo de retorno depende de los parámetros de tipo del
método. Un handler implementa `FallbackHandler<X>` para un único `X`, que no encaja con todas las
instanciaciones sin un cast no comprobado.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface Repository {
    @Fallback(Nothing.class)
    <T> T load(Class<T> type, String id);

    final class Nothing implements FallbackHandler<Object> {
        @Override
        public Object apply(FallbackContext context) {
            return null;
        }
    }
}
```

**Corrección:** usa un método de fallback hermano que declare los mismos parámetros de tipo.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface Repository {
    @Fallback(method = "loadFallback")
    <T> T load(Class<T> type, String id);

    default <T> T loadFallback(Class<T> type, String id, Exception e) {
        return null;
    }
}
```

## Procesador de anotaciones: clases anotadas (R4C-SUB)

Una clase anotada recibe una subclase generada, `<Class>$Resilient`, que sobrescribe los métodos anotados y
se crea con `Resilient.create(...)`. Por eso la clase debe poder extenderse y los métodos anotados deben poder
sobrescribirse.

### R4C-SUB-001

**Causa:** la clase anotada es final.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Corrección:** quita `final`, o mueve las anotaciones a una interfaz y envuelve la implementación con
`Resilient.of(...)`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-002

**Causa:** la clase anotada es sealed, así que la subclase generada no es una subclase permitida.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public sealed class PriceService permits CachedPriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}

final class CachedPriceService extends PriceService {
}
```

**Corrección:** hazla non-sealed (o una clase normal), o usa una interfaz y `Resilient.of(...)`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}

final class CachedPriceService extends PriceService {
}
```

### R4C-SUB-003

**Causa:** la clase anotada es abstracta. La subclase generada debe poder instanciarse y llama a `super` en
cada método.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public abstract class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Corrección:** anota una subclase concreta, o una interfaz.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-004

**Causa:** la clase anotada, o una clase que la contiene, es privada.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Services {
    private static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

**Corrección:** hazla package-private, protected o pública.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Services {
    static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

### R4C-SUB-005

**Causa:** la clase anotada es una clase interna (anidada no estática): su subclase generada necesitaría una
instancia de la clase contenedora.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class Services {
    class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

**Corrección:** hazla una clase anidada estática.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class Services {
    static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

### R4C-SUB-006

**Causa:** todos los constructores de la clase anotada son privados, así que la subclase generada no puede
llamar a ninguno.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    private PriceService() {
    }

    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Corrección:** haz al menos un constructor package-private, protected o público.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    protected PriceService() {
    }

    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-007

**Causa:** un método anotado (en la clase, una superclase o una interfaz que implementa) es final, así que la
subclase no puede sobrescribirlo y sus anotaciones se ignorarían.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public final String price(String sku) {
        return "1";
    }
}
```

**Corrección:** quita `final`, o quita las anotaciones.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-008

**Causa:** un método anotado es package-private en una superclase de otro paquete. La subclase generada, en
el paquete de la clase anotada, no puede sobrescribirlo, así que sus anotaciones se ignorarían.

**Incorrecto:**

```java
// com/acme/base/BaseService.java
package com.acme.base;

import dev.resilience4cata.annotation.Retry;

public class BaseService {
    @Retry
    String price(String sku) {
        return "1";
    }
}

// com/acme/PriceService.java
package com.acme;

import com.acme.base.BaseService;
import dev.resilience4cata.annotation.Retry;

public class PriceService extends BaseService {
    @Retry
    public String name() {
        return "prices";
    }
}
```

**Corrección:** haz el método protected o público.

```java
// com/acme/base/BaseService.java
package com.acme.base;

import dev.resilience4cata.annotation.Retry;

public class BaseService {
    @Retry
    protected String price(String sku) {
        return "1";
    }
}

// com/acme/PriceService.java
package com.acme;

import com.acme.base.BaseService;
import dev.resilience4cata.annotation.Retry;

public class PriceService extends BaseService {
    @Retry
    public String name() {
        return "prices";
    }
}
```

## Ejecución: Resilient y Pipeline (R4C-RT)

Los lanza `resilience4cata-core` al envolver un servicio o construir un pipeline. Los tipos de excepción están
documentados en cada método; el código va al principio del mensaje.

### R4C-RT-001

**Causa:** `Resilient.of(type, ...)` con un `type` que es una clase, no una interfaz
(`IllegalArgumentException`).

**Incorrecto:** `Resilient.of(PriceService.class, new PriceService(), registry)` donde `PriceService` es una
clase anotada.

**Corrección:** para una clase anotada, usa `Resilient.create(PriceService.class, registry)`; `Resilient.of`
recibe la interfaz anotada.

### R4C-RT-002

**Causa:** el objeto pasado a `Resilient.of` no implementa la interfaz (`IllegalArgumentException`). Solo
puede pasar mediante tipos raw o casts no comprobados.

**Corrección:** pasa una implementación de la interfaz.

### R4C-RT-003

**Causa:** no se encontró la clase generada `<type>$Resilient` (`IllegalStateException`): el tipo no tiene
anotaciones de resiliencia, o el procesador de anotaciones no se ejecutó al compilarlo.

**Incorrecto:** un build que solo añade las anotaciones:

```groovy
dependencies {
    compileOnly 'dev.resilience4cata:resilience4cata-annotations:<version>'
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

**Corrección:** añade el procesador, que es obligatorio (no hay alternativa por reflexión):

```groovy
dependencies {
    compileOnly 'dev.resilience4cata:resilience4cata-annotations:<version>'
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
}
```

En una imagen nativa de GraalVM, el mismo error significa que el procesador se ejecutó pero la imagen no
alcanza la clase por reflexión: los metadatos de alcanzabilidad que escribe junto a los archivos de clase,
`META-INF/native-image/dev.resilience4cata.generated/<type>$Resilient/reachability-metadata.json`, no estaban
en el class path del build de la imagen, o un paso del build (shading, un filtro de recursos) los eliminó. El
mensaje termina entonces con una frase que nombra ese archivo. Conserva la salida del procesador, metadatos
incluidos, en el jar o directorio que lee `native-image`; ver [Imagen nativa](start/installation.md#imagen-nativa).

### R4C-RT-004

**Causa:** la clase generada no tiene el constructor que espera esta versión de `resilience4cata-core`, o una
subclase generada no extiende la clase anotada (`IllegalStateException`). La generó una versión incompatible
del procesador.

**Corrección:** usa la misma versión para `resilience4cata-core` y `resilience4cata-processor` (el BOM las
mantiene alineadas) y recompila desde cero.

### R4C-RT-005

**Causa:** `resilience4cata-core` no puede acceder a la clase generada (`IllegalStateException`), normalmente
porque la aplicación es un módulo con nombre que no exporta el paquete a core.

**Corrección:** añade al `module-info.java` de la aplicación:

```java
exports com.acme to dev.resilience4cata.core;
```

### R4C-RT-006

**Causa:** el constructor de la clase generada falló con una excepción checked, o no se pudo instanciar
(`IllegalStateException`, con el fallo como causa). Las excepciones unchecked del constructor, como una
configuración inválida, se relanzan tal cual.

**Corrección:** mira la causa; suele venir del constructor de un clasificador o de un handler de fallback.

### R4C-RT-007

**Causa:** `Resilient.create(type, ...)` con una interfaz (`IllegalArgumentException`).

**Corrección:** envuelve una implementación: `Resilient.of(PriceClient.class, implementation, registry)`.

### R4C-RT-008

**Causa:** `Resilient.create(type, ...)` con un tipo que no puede tener una subclase generada: final,
abstracto, local, anónimo, un enum, un record, un array o un primitivo (`IllegalArgumentException`). El
procesador reporta casi todos estos casos al compilar ([R4C-SUB-001](#r4c-sub-001) y siguientes).

**Corrección:** anota una clase de primer nivel o anidada estática que no sea final ni abstracta, o una
interfaz y usa `Resilient.of(...)`.

### R4C-RT-009

**Causa:** ningún constructor de la clase anotada acepta los argumentos pasados a `Resilient.create`
(`IllegalArgumentException`). El mensaje lista los constructores.

**Corrección:** pasa argumentos que encajen con un constructor, o llama directamente al constructor generado,
que comprueba el compilador: `new PriceService$Resilient(registry, ...)`.

### R4C-RT-010

**Causa:** más de un constructor acepta los argumentos pasados a `Resilient.create`, por ejemplo un único
`null` (`IllegalArgumentException`).

**Corrección:** llama directamente al constructor generado, con un cast para elegir la sobrecarga:
`new PriceService$Resilient(registry, (String) null)`.

### R4C-RT-011

**Causa:** `Pipeline.builder(registry, id)` con un id en blanco (`IllegalArgumentException`).

**Corrección:** pasa el id que nombra las instancias de política del pipeline, por ejemplo `"payments"`.

### R4C-RT-012

**Causa:** una parte de `Pipeline.Builder` se define dos veces: una política, un clasificador,
`onExhaustedResult` o un fallback (`IllegalStateException`).

**Incorrecto:** `Pipeline.builder(registry, "payments").retry().retry("five")`

**Corrección:** define cada parte una vez por builder; pasa los ajustes en la única llamada:
`retry("five", b -> b.maxAttempts(3))`.

### R4C-RT-013

**Causa:** se definió `fallbackApplyOn` o `fallbackSkipOn`, pero el pipeline no tiene fallback
(`IllegalStateException` desde `build()`).

**Corrección:** añade un fallback (`fallback`, `fallbackHandler`, `fallbackAsync`) o quita la selección.

### R4C-RT-014

**Causa:** se definió `classifier(...)`, pero el pipeline no tiene ni retry ni circuit breaker
(`IllegalStateException` desde `build()`).

**Corrección:** añade la política a la que va destinado el clasificador, o quítalo.

### R4C-RT-015

**Causa:** se definió `retryClassifier(...)`, pero el pipeline no tiene retry (`IllegalStateException` desde
`build()`).

**Corrección:** añade un retry, o quita el clasificador del retry.

### R4C-RT-016

**Causa:** se definió `onExhaustedResult(...)`, pero el pipeline no tiene un retry con clasificador
(`IllegalStateException` desde `build()`). Solo un clasificador puede rechazar un resultado. El equivalente
en anotaciones es [R4C-CLS-012](#r4c-cls-012).

**Corrección:** añade un retry y un clasificador (`classifier` o `retryClassifier`), o quita
`onExhaustedResult`.

### R4C-RT-017

**Causa:** se definió `circuitBreakerClassifier(...)`, pero el pipeline no tiene circuit breaker
(`IllegalStateException` desde `build()`).

**Corrección:** añade un circuit breaker, o quita el clasificador del circuit breaker.

### R4C-RT-018

**Causa:** se llama a `execute(...)` o `run(...)` sobre un pipeline que tiene un hedge (`IllegalStateException`).
Un hedge vuelve a lanzar la llamada mientras el primer intento sigue en curso, y eso necesita una llamada que
devuelva un `CompletionStage`.

**Corrección:** llama a `executeAsync(() -> client.callAsync(...))`, o quita `.hedge(...)` del pipeline.

### R4C-RT-019

**Causa:** el pipeline tiene a la vez `.retry(...)` y `.hedge(...)` (`IllegalStateException` desde `build()`).

**Corrección:** quédate con uno: un hedge ya lanza un intento nuevo cuando uno falla (`hedgeOn`), y un retry
alrededor multiplicaría los intentos. Es el equivalente en ejecución de [R4C-HDG-002](#r4c-hdg-002).

### R4C-RT-020

**Causa:** un `Flux` se protege con un pipeline (`FluxPipelineOperator`) que tiene un hedge o un classifier
(`IllegalStateException`, entregada al suscriptor del `Flux`). Un hedge mezclaría los elementos de dos streams, y
un classifier juzga un resultado mientras que un stream tiene muchos.

**Corrección:** quita `.hedge(...)` o el classifier, o construye un pipeline aparte para el `Flux`.

### R4C-RT-021

**Causa:** se construye una clase generada por el procesador de anotaciones (`X$Resilient`) sobre un
`resilience4cata-core` (o, para métodos `Mono` y `Flux`, un `resilience4cata-reactor`) más viejo que el
`resilience4cata-processor` que la generó (`IllegalStateException`, desde el constructor generado). El código
generado puede llamar métodos que ese runtime no tiene. El código generado por el procesador `1.a` funciona sobre
el runtime `1.b` para todo `b >= a`, dentro de una misma versión mayor. Suele pasar cuando una librería compilada
con un resilience4cata más nuevo se usa en una aplicación cuyo árbol de dependencias resuelve un core más viejo.

**Corrección:** usa un runtime al menos tan nuevo como el procesador: importa la misma versión de
`resilience4cata-bom` para todos los artefactos de resilience4cata, o revisa qué versión resuelve tu build
(`./gradlew dependencyInsight --dependency resilience4cata-core`, `mvn dependency:tree`).

## Ejecución: registro y configuración de políticas (R4C-REG)

Los lanza `resilience4cata-core` cuando se construye la configuración de una política (`RetryConfig.builder()`
y los demás builders `*Config`), cuando se construye un `ResilienceRegistry`, y cuando el registro crea o
busca una instancia de política: al envolver un servicio (`Resilient.of`, `Resilient.create`) o al construir
un pipeline (`Pipeline.Builder.build()`, que los deja pasar sin cambios). Los tipos de excepción están
documentados en cada método; el código va al inicio del mensaje. La configuración externa
(`ResilienceConfig`) valida sus valores antes de que lleguen al registro y los reporta como
[R4C-CFG-006](#r4c-cfg-006), con las claves que los definieron.

### R4C-REG-001

**Causa:** un builder `*Config` rechazó un valor (`IllegalArgumentException` desde `build()`), por ejemplo un
`maxAttempts` menor que 1, una duración negativa, un `maxDelay` más corto que el `delay`, o un umbral de tasa
fuera de `(0, 100]`. El mensaje nombra el atributo y el valor.

**Incorrecto:** `RetryConfig.builder().maxAttempts(0).build()`

**Corrección:** usa un valor dentro del rango que indica el mensaje:
`RetryConfig.builder().maxAttempts(1).build()`.

### R4C-REG-002

**Causa:** `ResilienceRegistry.Builder.build()` encontró una configuración por defecto o con nombre inválida
(`IllegalArgumentException`, con el error [R4C-REG-001](#r4c-reg-001) como causa). El mensaje nombra la
configuración (o los valores por defecto) y dónde se definió.

**Incorrecto:**

```java
ResilienceRegistry.builder()
        .retry("slow", r -> r.delay(Duration.ofSeconds(2)).maxDelay(Duration.ofSeconds(1)))
        .build();
```

**Corrección:** haz que los valores de esa configuración sean válidos en conjunto. Una configuración con
nombre parte de los valores por defecto (`retryDefaults(...)`), luego aplica `retry("slow", ...)` y después
`retryOverlay("slow", ...)` (`configs.slow.retry.*` en la configuración externa):

```java
ResilienceRegistry.builder()
        .retry("slow", r -> r.delay(Duration.ofSeconds(1)).maxDelay(Duration.ofSeconds(2)))
        .build();
```

### R4C-REG-003

**Causa:** la configuración de una instancia de política es inválida una vez que se combina todo lo que se
le aplica (`IllegalArgumentException`, con el error [R4C-REG-001](#r4c-reg-001) como causa): su
configuración con nombre (o los valores por defecto), luego los valores de su anotación o paso de pipeline, y
después sus ajustes por instancia (`retryInstance(id, ...)`, `instances.<id>.*` en la configuración
externa). Se lanza cuando se crea la instancia: desde `Resilient.of`/`Resilient.create` o desde
`Pipeline.Builder.build()`.

**Incorrecto:** un ajuste por instancia que no encaja con los valores propios de la instancia:

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryInstance("payments", r -> r.delay(Duration.ofSeconds(30)))
        .build();
Pipeline.builder(registry, "payments").retry(r -> r.maxDelay(Duration.ofSeconds(20))).build();
```

**Corrección:** cambia el ajuste, o el valor con el que choca, para que la combinación sea válida, por
ejemplo `retryInstance("payments", r -> r.delay(Duration.ofSeconds(10)))`.

### R4C-REG-004

**Causa:** `config = "name"` (en una anotación o en un paso de pipeline), o `retryConfig("name")` y las
demás búsquedas `*Config(String)`, nombra una configuración que el registro no tiene
(`IllegalArgumentException`). El mensaje lista las configuraciones conocidas y sugiere la más parecida
("Did you mean …?").

**Incorrecto:** `@Retry(config = "fiev")` con un registro que solo registra `retry("five", ...)`.

**Corrección:** corrige el nombre, o registra la configuración:
`ResilienceRegistry.builder().retry("fiev", r -> ...)`, o `configs.fiev.retry.*` en la configuración externa.

### R4C-REG-005

**Causa:** la misma configuración con nombre se registra dos veces en un builder, por ejemplo con dos
llamadas a `retry("five", ...)` (`IllegalArgumentException`).

**Corrección:** regístrala una sola vez. Para cambiar algunos de sus atributos en otro lugar, usa el overlay,
que se aplica encima: `retryOverlay("five", r -> r.maxAttempts(3))`.

### R4C-REG-006

**Causa:** una configuración con nombre u overlay se registra con el nombre `""` o `"default"`, por ejemplo
`retry("default", ...)` (`IllegalArgumentException`). Ambos nombres están reservados: significan los valores
por defecto.

**Corrección:** cambia los valores por defecto con `retryDefaults(...)` (`defaults.retry.*` en la
configuración externa), o dale otro nombre a la configuración.

### R4C-REG-007

**Causa:** `retryInstance("", ...)` u otra llamada `*Instance(...)` con un id de instancia vacío
(`IllegalArgumentException`).

**Corrección:** pasa el id de la instancia: el `name` de su anotación, el id del pipeline, o el id derivado
`<interface>#<method>(<parameter types>)`, por ejemplo `com.acme.PriceClient#price(java.lang.String)`.

### R4C-REG-008

**Causa:** dos puntos de llamada comparten una instancia de retry, circuit breaker, rate limiter o bulkhead,
por el mismo `name` en sus anotaciones o el mismo id de pipeline, pero resuelven configuraciones distintas
(`IllegalStateException`). El mensaje muestra ambas configuraciones. Las instancias con el mismo nombre se
comparten, así que deben coincidir.

**Incorrecto:**

```java
interface Inventory {
    @Retry(name = "backend", maxAttempts = 3)
    String stock(String sku);

    @Retry(name = "backend", maxAttempts = 5)
    String reserve(String sku);
}
```

**Corrección:** da nombres distintos a los puntos de llamada si deben ser independientes, o haz idéntica su
configuración (por ejemplo, el mismo `config = "..."` y ningún atributo distinto) si deben compartir una
instancia.

### R4C-REG-009

**Causa:** `registry.circuitBreaker(id)`, `registry.rateLimiter(id)` o `registry.bulkhead(id)` con un id que
ninguna instancia de este registro tiene (`IllegalArgumentException`). El mensaje lista los ids conocidos y
sugiere el más parecido. Las instancias se crean cuando se envuelve su interfaz (`Resilient.of`), se crea su
clase (`Resilient.create`) o se construye su pipeline, así que buscarlas antes también falla.

**Corrección:** usa el `name` de la anotación (o el id del pipeline), o el id derivado
`<interface>#<method>(<parameter types>)`, y búscala después de crear el servicio o el pipeline.

## Ejecución: configuración externa (R4C-CFG)

`ResilienceConfig.build()` reporta todos los problemas a la vez en una única `ConfigException`; cada línea
empieza con su código y nombra la clave y su origen (archivo y línea, variable de entorno o propiedad del
sistema).

### R4C-CFG-001

**Causa:** una clave desconocida, mal formada o incompleta: política o atributo desconocido, segmento vacío,
una clave que se corta antes de tiempo. El mensaje sugiere la clave válida más parecida. Una clave con el
prefijo corto (`r4c.`, `R4C_`) cuyo primer segmento no se parece a `defaults`, `configs`, `instances` ni
`profiles` se ignora, porque es de otra herramienta ([Formas de clave](reference/configuration.md#key-forms)).

**Incorrecto:**

```properties
configs.external.circuit-breaker.wait-in-opne=10s
```

**Corrección:** usa una de las formas de clave que lista el mensaje: `defaults.<policy>.<attribute>`,
`configs.<name>.<policy>.<attribute>`, `configs.<name>.extends`, `instances.<id>.<policy>.<attribute>`,
`profiles.<profile>.…` o `profiles.active`.

```properties
configs.external.circuit-breaker.wait-in-open=10s
```

### R4C-CFG-002

**Causa:** un valor que no se puede interpretar para su atributo: un número, booleano, constante de enum o
duración.

**Incorrecto:**

```properties
defaults.retry.delay=fast
```

**Corrección:** usa un valor del tipo del atributo; las duraciones usan la misma gramática que las
anotaciones (`"250ms"`, `"2s"`, `"PT2S"`, `"0"`).

```properties
defaults.retry.delay=250ms
```

### R4C-CFG-003

**Causa:** una lista de excepciones (`retry-on`, `abort-on`, `record-on`, `ignore-on`) nombra una clase que el
class loader no encuentra, o una clase que no es `Throwable`.

**Incorrecto:**

```properties
defaults.retry.retry-on=java.io.IOExeption
```

**Corrección:** usa nombres completos de clases de excepción visibles para el class loader de la aplicación.

```properties
defaults.retry.retry-on=java.io.IOException
```

### R4C-CFG-004

**Causa:** un nombre de config o de perfil inválido, o reservado (`default` para un config, `active` para un
perfil). Los nombres usan minúsculas y dígitos separados por guiones simples, para que correspondan a
variables de entorno.

**Incorrecto:**

```properties
profiles.active=Test
```

**Corrección:** cámbialo, por ejemplo a `test`.

```properties
profiles.active=test
```

### R4C-CFG-005

**Causa:** la misma clave se define dos veces en un mismo origen (por ejemplo dos veces en un archivo, o con
dos grafías como `max-attempts` y `maxAttempts`).

**Corrección:** deja una de las dos. Orígenes distintos sí pueden definir la misma clave; decide el orden de
precedencia.

### R4C-CFG-006

**Causa:** los valores son válidos uno a uno pero no juntos, por ejemplo un `max-delay` menor que el `delay`,
o `max-attempts=0` (el builder del config los rechazó). El mensaje nombra las claves implicadas.

**Incorrecto:**

```properties
configs.slow.retry.delay=2s
configs.slow.retry.max-delay=1s
```

**Corrección:** haz que los valores sean coherentes, como describe el mensaje.

```properties
configs.slow.retry.delay=1s
configs.slow.retry.max-delay=2s
```

### R4C-CFG-007

**Causa:** `configs.<name>.extends` nombra un config que no está definido en la configuración externa, o
varios configs se extienden entre sí en un ciclo.

**Incorrecto:**

```properties
configs.batch.extends=slwo
```

**Corrección:** extiende un config existente (el mensaje sugiere el nombre más parecido y lista los conocidos)
y rompe cualquier ciclo.

### R4C-CFG-008

**Causa:** reservado; la versión actual no lo reporta.

### R4C-CFG-009

**Causa:** no se puede leer un origen de configuración: un recurso del classpath o un archivo que no existe,
o un error de E/S.

**Corrección:** revisa la ruta, o no añadas el origen si es opcional.

### R4C-CFG-010

**Causa:** una variable de entorno corresponde a más de una clave, porque `_` representa tanto `.` como `-`.

**Corrección:** define esa clave en un archivo de propiedades o como propiedad del sistema.

### R4C-CFG-011

**Causa:** retirado; ya no se reporta. Un config con nombre definido a la vez en código y externamente ahora
se fusiona, y ganan los valores externos.

### R4C-CFG-012

**Causa:** un id de instancia mal formado en una clave `instances.`: un id que contiene `.` y no va entre
comillas, un id vacío, una `"` sin cerrar o mal colocada, comillas alrededor de algo que no es el id, o un id
que empieza o termina con espacios.

**Incorrecto:**

```properties
instances.com.acme.Api#get(java.lang.String).retry.max-attempts=5
```

**Corrección:** pon el id de instancia entre comillas.

```properties
instances."com.acme.Api#get(java.lang.String)".retry.max-attempts=5
```

### R4C-CFG-013

**Causa:** `config.verifyInstances(registry)` encontró un ajuste `instances.` que no corresponde a ninguna
instancia de esa política en el registry (lo reporta `verifyInstances`, no `build()`, porque las instancias
solo existen una vez envueltos los servicios). El mensaje sugiere el id más parecido.

**Corrección:** usa un id de instancia existente: el `name` de la política, o
`<interface>#<method>(<parameter types>)` si no tiene.

### R4C-CFG-014

**Causa:** `config.verifyInstances(registry)` encontró un ajuste `instances.` de un atributo que la
anotación de la instancia fija con `locked = true` (o que un pipeline construido con `.locked()` fija en su
paso). El registry conservó el valor de la anotación e ignoró el ajuste; el mensaje muestra la configuración
que usa la instancia.

**Corrección:** quita la clave, o quita `locked = true` de la anotación si los operadores deben poder
cambiar ese valor. Ver [Bloquear una instancia](guides/configuration.md#bloquear-una-instancia).

## Ejecución: integración con Spring Boot (R4C-SPR)

`resilience4cata-spring` revisa los beans al arrancar y detiene el arranque con estos códigos cuando un bean
perdería su protección sin aviso. Spring Boot muestra el mensaje como *Description* y la corrección como
*Action* de su informe "APPLICATION FAILED TO START". Consulta la [guía de Spring Boot](guides/spring.md).

### R4C-SPR-001

**Causa:** un bean es una instancia de una clase anotada `C` pero no de la `C$Resilient` generada, así que
sus políticas nunca se ejecutarían: un método `@Bean` devolvió `new C(...)`, el bean se registró con un
supplier, o es una subclase de `C` sin anotaciones propias (`BeanCreationException`).

```text
[R4C-SPR-001] Bean 'pricing' is an instance of com.acme.PricingService, which has resilience4cata annotations, but not of the generated com.acme.PricingService$Resilient, so its policies would not run. ...
```

**Corrección:** registra la clase misma (escaneo de componentes, `@Import`), para que Spring instancie
`C$Resilient`; o devuelve `new C$Resilient(registry, ...)` desde el método `@Bean`, con el
`ResilienceRegistry` inyectado como parámetro; o anota la subclase. Para dejar el bean sin protección a
propósito, pon su nombre en `resilience4cata.spring.exclude`.

### R4C-SPR-002

**Causa:** una clase `@Configuration` con métodos `@Bean` tiene además anotaciones de resilience4cata. Spring
instancia su propia subclase CGLIB de una clase así, de modo que la subclase generada no puede usarse.

**Corrección:** mueve los métodos anotados a una clase `@Component` o `@Service`, o declara la configuración
con `@Configuration(proxyBeanMethods = false)`, que Spring no extiende.

### R4C-SPR-003

**Causa:** un bean implementa dos o más interfaces anotadas y ninguna extiende a todas las demás
(`BeanCreationException`). El envoltorio generado de una interfaz implementa solo esa interfaz, así que el bean
ya no podría inyectarse por las otras.

**Corrección:** anota la clase en su lugar (su subclase generada conserva todas las interfaces y sigue siendo
el tipo del bean), o declara una interfaz que extienda a las demás e implementa esa. Para envolver el bean tú
mismo, ponlo en `resilience4cata.spring.exclude` y define un `@Bean` que devuelva `Resilient.of(...)`.

### R4C-SPR-004

**Causa:** una clase anotada tiene un método con `@Retry` o `@Hedge` en vigor (en el método, la clase, una
superclase, una interfaz o un bundle) que además es transaccional: `@Transactional` de Spring o de Jakarta con una
propagación que se une a una transacción o la inicia (`REQUIRED`, `SUPPORTS`, `MANDATORY`, `REQUIRES_NEW`,
`NESTED`) (`BeanCreationException`). El proxy de transacciones envuelve la subclase generada, así que todos los
intentos se ejecutarían en la misma transacción, y un intento que falla y la marca rollback-only hace que el commit
de un intento posterior exitoso falle con `UnexpectedRollbackException`.

```text
[R4C-SPR-004] Bean 'ledger' (com.acme.Ledger) has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**Corrección:** declara la política en una interfaz que implemente el bean (su envoltorio queda fuera del proxy de
transacciones: una transacción por intento); o mueve `@Transactional` a otro bean al que llame el método
reintentado; o, si se quiere a propósito una sola transacción compartida por todos los intentos, pon
`resilience4cata.spring.allow-retry-inside-transaction=true`. `NOT_SUPPORTED` y `NEVER` no se reportan.

### R4C-SPR-005

**Causa:** un bean implementa una interfaz anotada, pero Spring lo creó antes de registrar el post-procesador de
resilience4cata, así que nunca se envolvió y sus políticas no se aplicarían. Spring crea un bean tan pronto cuando
un `BeanPostProcessor` o un `BeanFactoryPostProcessor` depende de él (un parámetro del constructor o un campo de
ese post-procesador), y avisa en el log de que el bean "is not eligible for getting processed by all
BeanPostProcessors" (`ResilienceBeanException`, una vez creados todos los singletons).

```text
[R4C-SPR-005] Bean 'stockClient' (com.acme.HttpStockClient) implements com.acme.StockClient, which has resilience4cata annotations, but it was created before resilience4cata's post-processor was registered, ... It was needed by 'auditPostProcessor'. ...
```

**Corrección:** inyecta el bean de forma diferida en ese post-procesador (`ObjectProvider<StockClient>` o `@Lazy`),
para que se cree más tarde, pasando por todos los post-procesadores; o anota la clase en vez de la interfaz (su
subclase generada se instancia en lugar de la clase, pronto o no). Para dejar el bean sin protección a propósito,
añade su nombre a `resilience4cata.spring.exclude`.

## Ejecución: integración con Micronaut (R4C-MN)

`resilience4cata-micronaut` revisa los beans y falla con estos códigos cuando un bean perdería su protección sin
aviso. Los errores de `@ResilientBean` (001 a 003) detienen el arranque: cada definición de bean anotada con
`@ResilientBean` se revisa al arrancar el contexto, antes de crear ninguna. Los demás se reportan al crear el bean,
que es al arrancar para los beans `@Context` y en el primer uso para los demás (Micronaut crea los beans de forma
perezosa), como una `ResilienceBeanException` dentro de la `BeanInstantiationException` de Micronaut. Consulta la
[guía de Micronaut](guides/micronaut.md).

### R4C-MN-001

**Causa:** un bean anotado con `@ResilientBean(I.class)` se expone con otros tipos además de `I`, por ejemplo
porque también declara `@Bean(typed = {I.class, Impl.class})`. `@ResilientBean` limita los tipos del bean a `I`,
porque el envoltorio generado que reemplaza al bean solo implementa `I`: inyectarlo con otro tipo fallaría o se
saltaría las políticas.

```text
[R4C-MN-001] Bean com.acme.HttpPayments is annotated @ResilientBean(Payments.class) but is exposed as [com.acme.HttpPayments, com.acme.Payments]. ...
```

**Corrección:** quita `@Bean(typed = ...)` (o `@Type`) del bean e inyecta la interfaz.

### R4C-MN-002

**Causa:** `@ResilientBean(X.class)` nombra un tipo que no es una interfaz, o una interfaz que el bean no
implementa, así que ningún envoltorio generado puede protegerlo.

**Corrección:** nombra la interfaz anotada que implementa el bean. Para una clase anotada, quita `@ResilientBean`:
compila con `-Ar4c.inject=micronaut` para que Micronaut cree su subclase generada, o devuelve
`new C$Resilient(registry, ...)` desde un método de un `@Factory` ([clases anotadas](guides/micronaut.md#classes)).

### R4C-MN-003

**Causa:** un bean anotado con `@ResilientBean` tiene un ámbito que Micronaut envuelve en un proxy
(`@Refreshable`, `@RequestScope`, `@ThreadLocal`, cualquier `@ScopedProxy`). Ese proxy busca su destino por la
clase del bean, y el envoltorio generado no lo es.

**Corrección:** haz el bean `@Singleton`, `@Prototype` o `@Context`, o deja el bean con ámbito sin protección e
inyéctalo en un singleton que implemente la interfaz anotada.

### R4C-MN-004

**Causa:** un bean es una instancia de una clase anotada `C` pero no de la `C$Resilient` generada, así que sus
políticas nunca se ejecutarían: Micronaut creó `C` por su cuenta (tiene una anotación de ámbito o un constructor
`@Inject`, y el código se compiló sin `-Ar4c.inject=micronaut`), o un método de un `@Factory` devolvió
`new C(...)`. También se reporta cuando Micronaut crea `C$Resilient` desde su propia definición aunque se compiló
sin la opción: su constructor repite el `@Inject` de `C`, así que es un bean, pero sin el ámbito ni los
calificadores de `C` (un `@Singleton` pasaría a ser una instancia nueva en cada inyección).

```text
[R4C-MN-004] Bean com.acme.InventoryService is an instance of com.acme.InventoryService, which has resilience4cata annotations, but not of the generated InventoryService$Resilient, so its policies would not run. ...
```

**Corrección:** compila con `-Ar4c.inject=micronaut`, para que Micronaut cree `C$Resilient` en lugar de `C`; o quita
la anotación de ámbito (y `@Inject`) de `C` y devuelve `new C$Resilient(registry, ...)` desde un método de un
`@Factory` con tipo `C`. Para dejar el bean sin protección a propósito, pon el nombre de la clase en
`resilience4cata.micronaut.exclude`.

### R4C-MN-005

**Causa:** una clase anotada compilada con `-Ar4c.inject=micronaut` tiene un método con `@Retry` o `@Hedge` en vigor
que además es `@Transactional` (el de Micronaut, o el de Jakarta traducido a él) con una propagación que se une a
una transacción o la inicia (`REQUIRED`, `SUPPORTS`, `MANDATORY`, `REQUIRES_NEW`, `NESTED`). La subclase generada
repite `@Transactional`, así que el proxy de transacciones de Micronaut la envuelve: todos los intentos se
ejecutarían en la misma transacción, y un intento que falla y la marca rollback-only hace que el commit de un
intento posterior exitoso falle.

```text
[R4C-MN-005] Bean com.acme.Ledger has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**Corrección:** declara la política en una interfaz que implemente el bean y anota el bean con `@ResilientBean` (el
envoltorio queda fuera del proxy de transacciones: una transacción por intento); o mueve `@Transactional` a otro
bean al que llame el método reintentado; o, si se quiere a propósito una sola transacción compartida por todos los
intentos, pon `resilience4cata.micronaut.allow-retry-inside-transaction=true`. `NOT_SUPPORTED` y `NEVER` no se
reportan. La revisión lee el archivo de clase generado, así que una imagen nativa la omite.

### R4C-MN-006

**Causa:** una opción de la integración con Micronaut tiene un valor inválido, por ejemplo
`resilience4cata.micronaut.verify-instances: strict`. El contexto no arranca.

```text
[R4C-MN-006] Invalid value 'strict' for resilience4cata.micronaut.verify-instances. Use fail, warn or off.
```

**Corrección:** usa uno de los valores que indica el mensaje.

## Compilación: extensión de Quarkus (R4C-QK)

`resilience4cata-quarkus` revisa la aplicación mientras Quarkus la construye (`quarkusBuild`, el modo dev, cada
`@QuarkusTest`) y detiene la construcción con estos códigos cuando un bean no quedaría protegido como se declaró,
o quedaría protegido dos veces. Quarkus los muestra entre sus "deployment problems". Consulta la
[guía de Quarkus](guides/quarkus.md).

### R4C-QK-001

**Causa:** un método protegido por resilience4cata lleva además anotaciones de MicroProfile o SmallRye Fault
Tolerance (`org.eclipse.microprofile.faulttolerance.*`, `io.smallrye.faulttolerance.api.*`), en el método o en
su clase; para una interfaz anotada, también en el método de la clase que la implementa. Los nombres simples son
los mismos (`@Retry`, `@CircuitBreaker`, `@Timeout`, `@Bulkhead`, `@Fallback`), así que suele ser un import
equivocado. Con `quarkus-smallrye-fault-tolerance` instalado se aplicarían las dos bibliotecas, así que la
construcción falla; sin él, las anotaciones de Fault Tolerance no hacen nada y el mismo mensaje se registra
como advertencia.

```text
[R4C-QK-001] Methods protected by resilience4cata also carry MicroProfile Fault Tolerance annotations: com.acme.CatalogClient.fetch(). SmallRye Fault Tolerance would apply its interceptor as well, so both libraries would retry, time out and count the same calls. ...
```

**Corrección:** usa una sola biblioteca por método: quita la otra anotación o corrige el import. Consulta
[SmallRye Fault Tolerance frente a resilience4cata](guides/quarkus.md#smallrye).

### R4C-QK-002

**Causa:** una clase anotada tiene un método con `@Retry` o `@Hedge` en vigor que además es `@Transactional`
(Jakarta Transactions, con Narayana) con un tipo que se une a una transacción o la inicia (`REQUIRED`,
`REQUIRES_NEW`, `MANDATORY`, `SUPPORTS`). El interceptor de transacciones envuelve la subclase generada, así
que todos los intentos se ejecutarían en la misma transacción, y un intento que falla y la marca rollback-only
hace que el commit de un intento posterior exitoso falle.

```text
[R4C-QK-002] Bean com.acme.Ledger has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**Corrección:** declara la política en una interfaz que implemente el bean (su envoltorio queda fuera del
interceptor de transacciones: una transacción por intento); o mueve `@Transactional` a otro bean al que llame
el método reintentado; o, si se quiere a propósito una sola transacción compartida por todos los intentos, pon
`quarkus.resilience4cata.allow-retry-inside-transaction=true`. `NOT_SUPPORTED` y `NEVER` no se reportan.

### R4C-QK-003

**Causa:** un bean implementa dos o más interfaces anotadas y ninguna extiende a todas las demás. El envoltorio
generado de una interfaz implementa solo esa interfaz, así que el bean solo puede inyectarse a través de un
envoltorio.

**Corrección:** declara una interfaz que extienda a las demás e implementa esa, o anota la clase en su lugar.
Para envolver el bean tú mismo, pon su clase en `quarkus.resilience4cata.beans.exclude` y devuelve
`Resilient.of(...)` desde un método productor.

### R4C-QK-004

**Causa:** una clase anotada declara métodos o campos productores (`@Produces`). La subclase generada sustituye
a la clase como bean, y los productores de CDI no se heredan, así que desaparecerían.

**Corrección:** mueve los productores a otro bean, o pon la clase en `quarkus.resilience4cata.beans.exclude`.

### R4C-QK-005

**Causa:** una clase anotada es un bean de ámbito normal (`@ApplicationScoped`, `@RequestScoped`,
`@SessionScoped`) y no tiene un constructor no privado sin parámetros. El proxy de cliente que Quarkus pone
delante de la subclase generada necesita uno, y Quarkus solo puede añadirlo a la subclase si la clase lo tiene.

```text
[R4C-QK-005] com.acme.OrderService is a normal-scoped bean (@ApplicationScoped) with resilience4cata annotations, but has no non-private constructor without parameters. ...
```

**Corrección:** añade un constructor sin parámetros (basta con que sea package-private: solo lo usa el proxy),
o usa inyección por campo en lugar de por constructor, o haz el bean `@Singleton`, que no tiene proxy de
cliente.

### R4C-QK-006

**Causa:** un bean protegido por resilience4cata tiene una condición de búsqueda (`@LookupIfProperty`,
`@LookupUnlessProperty`): una clase anotada, o un bean que implementa una interfaz anotada. El bean que recibe la
aplicación es la subclase generada o el wrapper de la interfaz, y Quarkus solo lee las condiciones de búsqueda en la
clase original, así que el bean seguiría disponible diga lo que diga la propiedad.

```text
[R4C-QK-006] Beans protected by resilience4cata have lookup conditions (@LookupIfProperty, @LookupUnlessProperty): com.acme.BetaFeature. ...
```

**Corrección:** usa una condición de build en su lugar (`@IfBuildProperty`, `@IfBuildProfile` y sus formas
`Unless`), que funciona en clases anotadas, o comprueba la propiedad en el código que busca el bean.

### R4C-QK-007

**Causa:** un recurso de Quarkus REST (una clase con `@Path`, o que implementa una interfaz con `@Path`) es una clase
anotada. Quarkus REST crea las clases de recursos por su cuenta, así que la subclase generada nunca atendería los
endpoints y las políticas no se ejecutarían.

```text
[R4C-QK-007] REST resource classes (@Path) have resilience4cata annotations: com.acme.OrdersResource. ...
```

**Corrección:** mueve las llamadas protegidas a un bean que el recurso inyecte (una clase anotada, o un bean que
implemente una interfaz anotada), o protege la interfaz del cliente REST que el recurso llama.

## Pruebas: testkit (R4C-TK)

`ManualClock` y `ResilienceTesting` reportan un mal uso en las pruebas con estos códigos.

### R4C-TK-001

**Causa:** `ManualClock.advance(…)` con una duración negativa (`IllegalArgumentException`). Un reloj solo
avanza.

**Corrección:** pasa una duración de cero o más. Para empezar una prueba en otro momento, crea el reloj con
ese tiempo: `new ManualClock(startNanos)`, o `@ResilienceTest(startNanos = …)` con la extensión de JUnit.

### R4C-TK-002

**Causa:** `ManualClock.advanceUntilIdle()` se rindió después de 100.000 tareas seguidas
(`IllegalStateException`): cada tarea que ejecutó programó otra, así que el reloj nunca quedaría libre.
Normalmente es un retry que sigue fallando sin límite de tiempo, o un helper de prueba que se reprograma solo.

**Corrección:** avanza a un tiempo fijo con `clock.advance(…)`, o dale un límite al retry (`maxAttempts`,
`maxDuration`) para que las tareas terminen.

### R4C-TK-003

**Causa:** `ResilienceTesting.elapseWaitInOpen(breaker, clock)` sobre un circuit breaker que no está
`OPEN` (`IllegalStateException`); el mensaje dice en qué estado está.

**Corrección:** llámalo cuando el breaker ya se abrió: comprueba que los fallos registrados alcancen
`minimumCalls` y `failureRateThreshold`. Para probar el estado abierto sin fallos, usa `breaker.forceOpen()`.

## Pruebas: extensión de JUnit (R4C-JU)

`@ResilienceTest` (`ResilienceExtension`) reporta un mal uso con estos códigos; JUnit los muestra como el fallo
de la prueba o de la clase.

### R4C-JU-001

**Causa:** un parámetro `ManualClock` o `ResilienceRegistry` en un constructor o en un método `@BeforeAll`
(`ParameterResolutionException`). La extensión crea un reloj y un registry nuevos para cada prueba, así que no
hay ninguno antes de que empiece la primera.

**Corrección:** recíbelos como parámetros del método de prueba o de un método `@BeforeEach`.

### R4C-JU-002

**Causa:** un método `@ConfigureRegistry` con otra firma (`ExtensionConfigurationException`; JUnit 5 lo
envuelve en una `ParameterResolutionException`).

**Corrección:** que sea un método `void` no privado con un parámetro `ResilienceRegistry.Builder`, estático o
no: `void configure(ResilienceRegistry.Builder builder)`.

### R4C-JU-003

**Causa:** con `@ResilienceTest(failOnPendingTasks = true)`, una prueba pasó pero dejó tareas en su reloj
(`AssertionError`): un retry, una espera o un timeout async que nunca se ejecutó. El mensaje lista sus plazos.

**Corrección:** avanza el reloj más allá de ellas (`clock.advanceUntilIdle()`, o `clock.advance(…)` lo
suficiente), o pon `failOnPendingTasks = false` si dejarlas es intencional.
