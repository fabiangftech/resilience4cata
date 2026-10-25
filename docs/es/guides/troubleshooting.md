# Solución de problemas

Problemas con los que se encuentra la gente al usar resilience4cata, por síntoma, con la causa y la solución.

Cada error que reporta la biblioteca empieza con un código como `[R4C-RT-003]`; la [referencia de errores](../errors.md) tiene una sección para cada uno, con un ejemplo incorrecto y la solución.

## Build y generación de código :id=build

### `Resilient.of` falla: "No generated class … found" :id=no-generated-class

```text
[R4C-RT-003] No generated class com.acme.shop.clients.PriceClient$Resilient found. Make sure that com.acme.shop.clients.PriceClient has at least one resilience annotation, and that the annotation processor runs when it is compiled. With Gradle:
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
```

El procesador no se ejecutó cuando se compiló la interfaz, o la interfaz no tiene ninguna anotación de resiliencia. El procesador es obligatorio: no hay alternativa por reflexión.

- **Gradle:** agrega `annotationProcessor 'dev.resilience4cata:resilience4cata-processor'` (y `annotationProcessor platform(…bom…)` si usas el BOM, porque la ruta de procesadores se resuelve por separado). Para tipos anotados bajo `src/test`, usa también `testAnnotationProcessor` y `testCompileOnly`.
- **Maven:** incluye el procesador en `<annotationProcessorPaths>` de `maven-compiler-plugin`. Con maven-compiler-plugin 3.12.0 o posterior, un `<path>` sin `<version>` toma su versión de `dependencyManagement` (el BOM importado); las versiones anteriores del plugin necesitan una `<version>` explícita.
- **Procesador solo en el class path:** desde JDK 23, `javac` ya no ejecuta los procesadores que encuentra en el class path a menos que se le pida. Ponlo en la ruta de procesadores.
- **Verificación:** después de un build, existe `build/generated/sources/annotationProcessor/java/main/<package>/<Type>$Resilient.java` (Gradle) o `target/generated-sources/annotations/…` (Maven).

Consulta [R4C-RT-003](../errors.md#r4c-rt-003) e [Instalación](../start/installation.md).

### `[R4C-RT-004]`: la clase generada no coincide :id=version-mismatch

`resilience4cata-core` y `resilience4cata-processor` tienen versiones distintas. Toma ambos del BOM, o dales la misma versión. Luego haz un build limpio para que desaparezcan las clases generadas antiguas. Consulta [R4C-RT-004](../errors.md#r4c-rt-004).

### `[R4C-RT-005]` en una aplicación modular :id=jpms

`core` carga `<Type>$Resilient` por reflexión, así que tu módulo debe exportarle (o abrirle) el paquete: `exports com.acme.shop.clients to dev.resilience4cata.core;`. Consulta [R4C-RT-005](../errors.md#r4c-rt-005).

### El IDE no ejecuta el procesador :id=ide

El build funciona en la línea de comandos, pero en el IDE las clases `$Resilient` faltan o están desactualizadas.

- **IntelliJ IDEA:** en proyectos Gradle y Maven, IDEA lee la ruta de procesadores desde el build. Si compilas con el compilador propio de IDEA, habilita *Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing*, o deja que Gradle compile el proyecto (*Build and run using: Gradle*).
- **Eclipse:** el procesamiento de anotaciones debe estar habilitado para el proyecto (Buildship para Gradle, el soporte de procesamiento de anotaciones de m2e para Maven).
- Después de cambiar anotaciones, vuelve a compilar el módulo: el procesador escribe la fuente generada cuando se compila la interfaz anotada.

Consulta [Instalación](../start/installation.md) para cada IDE.

### Lombok, MapStruct u otros procesadores en el mismo build :id=other-processors

El procesador de resilience4cata puede ejecutarse junto a otros procesadores de anotaciones. Ponlos todos en la misma ruta de procesadores:

- **Gradle:** una línea `annotationProcessor` por procesador (`org.projectlombok:lombok`, `org.mapstruct:mapstruct-processor`, `dev.resilience4cata:resilience4cata-processor`).
- **Maven:** una vez que se define `<annotationProcessorPaths>`, Maven ejecuta solo lo que esa lista incluye, así que incluye ahí también Lombok y MapStruct.
- **Pon Lombok antes que resilience4cata**, en las líneas `annotationProcessor` de Gradle y en `<annotationProcessorPaths>` de Maven. La subclase generada de una [clase anotada](annotated-classes.md) replica los constructores de la clase, así que debe ver los constructores que agrega Lombok. Con resilience4cata primero, una clase anotada que usa `@RequiredArgsConstructor` (u otro constructor de Lombok) no compila, con un error como `constructor … in class … cannot be applied to given types`.

El procesador solo lee las interfaces y clases que llevan anotaciones de resiliencia y, cuando hay otros procesadores presentes, reclama solo sus propias anotaciones, así que no se las oculta.

### `-Xlint:all -Werror` falla con "No processor claimed any of these annotations" :id=xlint-processing

resilience4cata en sí no lo causa: solo en la ruta de procesadores reclama las anotaciones que ve, y junto a otros procesadores reclama solo `dev.resilience4cata.annotation.*`. Si la advertencia lista otras anotaciones, otro procesador las dejó sin reclamar; agrega `-Xlint:-processing` a los argumentos del compilador, o corrígelo en ese procesador.

### `-Werror` falla con una advertencia `auxiliaryclass` en el código generado :id=auxiliary-class

Una interfaz anotada declarada como segundo tipo de nivel superior, no público, en el archivo fuente de otro tipo (`Clients.java` con `public class Clients` e `interface PriceClient`) es una *clase auxiliar*. El `PriceClient$Resilient` generado vive en su propio archivo y la referencia, así que `javac -Xlint:all` advierte `auxiliary class PriceClient in Clients.java should not be accessed from outside its own source file`, y `-Werror` lo convierte en error. Mueve la interfaz a su propio archivo (`PriceClient.java`), o anídala como tipo miembro de la clase.

### Errores de compilación del procesador :id=compile-errors

El procesador verifica las anotaciones en tiempo de compilación y reporta errores `R4C-APT`, `R4C-ATR`, `R4C-CMP`, `R4C-ASY`, `R4C-CLS`, `R4C-FB` y `R4C-SUB` sobre el elemento anotado. Los más comunes:

| Síntoma | Código |
|---|---|
| anotación en un método `static` o `private` | [R4C-APT-002](../errors.md#r4c-apt-002) |
| anotación en una interfaz privada | [R4C-APT-003](../errors.md#r4c-apt-003) |
| una duración como `"2 s"` o `"1.5s"` | [R4C-ATR-001](../errors.md#r4c-atr-001) |
| `@Timeout("0")` | [R4C-ATR-002](../errors.md#r4c-atr-002) |
| un método async que devuelve algo distinto de `CompletionStage<T>` / `CompletableFuture<T>` (por ejemplo `? super T`) | [R4C-ASY-001](../errors.md#r4c-asy-001) |
| la firma del método de fallback no encaja | [R4C-FB-003](../errors.md#r4c-fb-003) |
| una clase anotada que es `final` | [R4C-SUB-001](../errors.md#r4c-sub-001) |

## Comportamiento :id=behaviour

### Una llamada dentro de mi implementación no se reintenta :id=self-invocation

Con `Resilient.of` (el estilo de interfaz), las políticas están en el wrapper generado, que llama a tu implementación. Cuando la implementación llama a uno de sus propios métodos, la llamada no pasa por el wrapper:

<!-- snippet: troubleshooting-self-invocation-api -->
```java
public interface CatalogApi {

    @Retry(maxAttempts = 3, delay = "0")
    int price(String sku);

    int total(List<String> skus); // not annotated
}
```

<!-- snippet: troubleshooting-self-invocation -->
```java
final class HttpCatalog implements CatalogApi {

    @Override
    public int price(String sku) {
        return fetchPrice(sku); // may fail
    }

    @Override
    public int total(List<String> skus) {
        // this.price(...) calls the implementation directly, not the generated wrapper: no retry here
        return skus.stream().mapToInt(this::price).sum();
    }

    private int failuresLeft = 1; // a flaky remote call: fails once

    private int fetchPrice(String sku) {
        if (failuresLeft-- > 0) {
            throw new IllegalStateException("flaky");
        }
        return 10;
    }
}
```

`catalog.price("a")` se reintenta; `catalog.total(List.of("a"))` falla en el primer error, porque `total` llama a `this.price` en la implementación. Es una propiedad del estilo de interfaz, no un bug, y no se intenta sortear. Para interceptar las autollamadas, anota una clase en su lugar: la subclase generada sobrescribe los métodos, así que `this.price(…)` pasa por las políticas. Consulta [Clases anotadas](annotated-classes.md).

### Las anotaciones en mi clase de implementación no tienen efecto :id=implementation-annotations

`Resilient.of(Interface.class, impl, registry)` usa solo las anotaciones de la interfaz (y de sus superinterfaces). El wrapper no lee las anotaciones de la clase de implementación. Anotar la clase la convierte en una [clase anotada](annotated-classes.md): el procesador genera una subclase para ella, que creas con `Resilient.create(Type.class, registry, args…)` o `new Type$Resilient(registry, args…)`, y la clase debe entonces cumplir las reglas de las clases anotadas (no `final`, no `sealed`, no abstracta… consulta de [R4C-SUB-001](../errors.md#r4c-sub-001) a [R4C-SUB-008](../errors.md#r4c-sub-008)). Elige un estilo por tipo.

### Los retries se detienen en cuanto el breaker se abre :id=open-breaker-stops-retries

Es el comportamiento por defecto, a propósito. El retry está fuera del circuit breaker (orden: Fallback → Retry → CircuitBreaker → RateLimiter → Timeout → Bulkhead), y `RetryConfig.DEFAULTS.abortOn` es `[CallNotPermittedException]`: una vez que el breaker rechaza un intento, reintentar solo sería rechazado otra vez. El retry se rinde con `GaveUp(reason = NOT_RETRYABLE)` y vuelve a lanzar la `CallNotPermittedException` (o se ejecuta el fallback).

Si defines `abortOn` tú mismo, mantén `CallNotPermittedException` en la lista a menos que de verdad quieras reintentar contra un breaker abierto. Consulta [Retry](../policies/retry.md) y [Circuit breaker](../policies/circuit-breaker.md).

### Un timeout no detiene mi código :id=timeout-not-interrupting

Un `@Timeout` sync ejecuta la llamada en el thread de quien llama y lo **interrumpe** cuando se acaba el tiempo. El código que ignora las interrupciones sigue ejecutándose hasta que retorna por sí solo, y solo entonces quien llama recibe `CallTimeoutException`:

- lecturas bloqueantes clásicas de `java.net.Socket` / `InputStream`, y las bibliotecas construidas sobre ellas;
- bucles activos que nunca verifican `Thread.interrupted()`;
- código que captura `InterruptedException` y sigue adelante.

Soluciones: configura los timeouts de E/S del propio cliente (timeouts de conexión y de lectura; `java.net.http.HttpClient`, los canales NIO y la mayoría de los clientes modernos responden a las interrupciones o tienen sus propios timeouts), verifica `Thread.interrupted()` en los bucles largos y restablece el flag de interrupción cuando captures `InterruptedException`. En los métodos async, el stage devuelto falla con `CallTimeoutException` a tiempo, y el stage de la llamada se cancela si es un `Future`; que el trabajo detrás de él se detenga depende de ese código.

Los timeouts además son poco precisos por diseño: nunca antes de tiempo, a lo sumo unos dos ticks del watchdog tarde (un tick es el timeout más corto en uso / 16, entre 1 y 50 ms). Consulta [Timeout](../policies/timeout.md).

### Mi configuración externa no se aplica :id=config-not-applied

Verifica, en orden:

1. **El valor lo sobrescribe algo con mayor precedencia.** De menor a mayor: valores por defecto integrados, `defaults.*`, `configs.<name>.*`, atributos de la anotación, `instances.<id>.*`. Una clave `defaults.*` no cambia un atributo que la anotación define explícitamente; usa `instances.<id>.*` para eso. Consulta [precedencia](../reference/configuration.md#precedence).
2. **La fuente no se lee.** `ResilienceConfig.load()` lee solo el recurso del classpath `resilience4cata.properties`, las variables de entorno (`R4C_…`) y las propiedades del sistema **con el prefijo `r4c.` o `resilience4cata.`**; otros archivos necesitan `ResilienceConfig.builder().file(…)` o `.classpath(…)`.
3. **La configuración no se aplica al registry.** Envuelve tus servicios con el registry construido por `config.applyTo(ResilienceRegistry.builder()).build()` (o `config.toRegistry()`).
4. **El id de instancia no coincide.** Una clave `instances.<id>` cuyo id no coincide con nada queda sin usar en silencio hasta que lo preguntes. `config.describe()` lista cada valor efectivo con su fuente; `config.verifyInstances(registry)`, llamado después de envolver tus servicios, falla con los ids que no coinciden con ninguna instancia y sugiere el más cercano:

<!-- snippet: troubleshooting-verify-instances -->
```java
ResilienceConfig config = ResilienceConfig.builder().properties("app.properties", properties).build();
System.out.println(config.describe()); // every effective value and where it comes from
ResilienceRegistry registry = config.applyTo(ResilienceRegistry.builder()).build();
Payments payments = Resilient.of(Payments.class, order -> "ok", registry);
config.verifyInstances(registry); // after every service is wrapped: fails on ids that match nothing
```

Los ids derivados se ven como `com.acme.Api#get(java.lang.String)` y deben ir entre comillas en las claves: `instances."com.acme.Api#get(java.lang.String)".timeout.value=2s`. Consulta [R4C-CFG-013](../errors.md#r4c-cfg-013) y [Claves de configuración](../reference/configuration.md).

5. **Esperabas una recarga.** La configuración se lee una sola vez, cuando se construyen el registry y sus instancias; no hay recarga en caliente.

### `IllegalArgumentException [R4C-REG-009]` desde `registry.circuitBreaker(id)` :id=handle-not-found

El id es incorrecto, o la instancia todavía no existe: las instancias se crean cuando se envuelve la interfaz (`Resilient.of`, `Resilient.create`) o se construye el `Pipeline`. El mensaje sugiere el id más cercano. Consulta [R4C-REG-009](../errors.md#r4c-reg-009).

### `IllegalStateException [R4C-REG-008]` al envolver :id=shared-name

Dos métodos comparten un `name` (o un id de pipeline) pero se resuelven a configuraciones distintas. Los métodos que comparten una instancia deben coincidir en todos los atributos; dales nombres distintos, o los mismos atributos. Consulta [R4C-REG-008](../errors.md#r4c-reg-008).
