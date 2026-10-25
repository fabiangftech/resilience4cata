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
| `R4C-CLS` | procesador de anotaciones | clases `classifier` y `onExhaustedResult` |
| `R4C-FB` | procesador de anotaciones | `@Fallback` |
| `R4C-SUB` | procesador de anotaciones | clases anotadas (subclases generadas) |
| `R4C-RT` | `resilience4cata-core` en ejecución | `Resilient.of`, `Resilient.create`, `Pipeline.Builder` |
| `R4C-CFG` | `resilience4cata-config` en ejecución | configuración externa (`ResilienceConfig`) |

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

**Causa:** el procesador no pudo escribir un archivo fuente generado: falló el `Filer` del compilador, por
ejemplo porque el directorio de fuentes generadas no admite escritura, o porque otro procesador o un archivo
fuente ya define `<Interface>$Resilient`.

**Corrección:** revisa el directorio de fuentes generadas del build (con Gradle,
`build/generated/sources/annotationProcessor`), asegúrate de que ninguna clase escrita a mano se llame
`<Interface>$Resilient` y recompila tras `./gradlew clean`.

## Procesador de anotaciones: valores de atributos (R4C-ATR)

### R4C-ATR-001

**Causa:** un atributo de duración (`delay`, `maxDelay`, `maxDuration`, `slowCallDuration`, `waitInOpen`,
`maxWaitInHalfOpen`, `maxWait`, `period`, o el valor de `@Timeout`) no es una duración válida.

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

**Corrección:** pon una duración positiva. Para desactivar la política, usa `enabled = "false"` (o la
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
`maxConcurrent` o `permits`.

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

**Causa:** `enabled` no es `""` (sin definir), `"true"` ni `"false"`.

**Incorrecto:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(enabled = "yes")
    String price(String sku);
}
```

**Corrección:** usa `"true"` o `"false"`, u omítelo.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(enabled = "false")
    String price(String sku);
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

## Ejecución: configuración externa (R4C-CFG)

`ResilienceConfig.build()` reporta todos los problemas a la vez en una única `ConfigException`; cada línea
empieza con su código y nombra la clave y su origen (archivo y línea, variable de entorno o propiedad del
sistema).

### R4C-CFG-001

**Causa:** una clave desconocida, mal formada o incompleta: política o atributo desconocido, segmento vacío,
una clave que se corta antes de tiempo. El mensaje sugiere la clave válida más parecida.

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
