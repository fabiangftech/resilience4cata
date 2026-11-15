# resilience4cata 1.0.0-RC4: tu propio bulkhead ya no abre el breaker

El cuarto release candidate de 1.0.0 está en Maven Central. La API es la misma que en RC3 y cambia un comportamiento: el circuit breaker ya no cuenta como fallas los rechazos del propio bulkhead, rate limiter o adaptive limit del método.

*14 de noviembre de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor de resilience4cata*

## Lo que encontró el piloto

Nadie usa resilience4cata en producción todavía, así que antes de 1.0 hay un [piloto](https://github.com/fabiangftech/resilience4cata/tree/main/pilot), lo más parecido a uno sin un usuario. Corre tres servicios pequeños, uno con Spring Boot, otro con Micronaut y otro con Quarkus. Cada uno toma resilience4cata de Maven Central, como dice su guía, y llama por HTTP a un backend simulado. k6 les manda 10 minutos de carga: 1500 pedidos por segundo, con el backend caído durante un minuto y lento durante otro. El método que llama al backend usa la pila habitual:

<!-- not-compiled: the pilot's service, abridged -->
```java
@Fallback(method = "lastKnown")
@Retry(name = "inventory", maxAttempts = 3, delay = "20ms")
@CircuitBreaker(name = "inventory", windowSize = 100, minimumCalls = 20, failureRateThreshold = 50,
        waitInOpen = "5s", halfOpenCalls = 5)
@Timeout("200ms")
@Bulkhead(name = "inventory", maxConcurrent = 40, maxWait = "20ms")
public String stock(String id) throws IOException, InterruptedException { ... }
```

Con 1.0.0-RC3, el servicio de Spring abrió su breaker durante el arranque en frío, sin que el backend hubiera fallado ni una vez. Las llamadas son más lentas mientras una JVM se calienta, así que 40 de ellas llenaron el bulkhead y las siguientes fueron rechazadas con `BulkheadFullException`. En el orden por defecto el breaker está fuera del bulkhead, así que veía esos rechazos como fallas. Cuando su ventana tuvo 20 llamadas y la mitad eran rechazos, se abrió, y los pedidos fueron al fallback mientras siguió abierto.

La primera corrida del piloto lo evitó con `ignoreOn = BulkheadFullException.class` en el breaker. Un usuario tendría que saber que esa línea hacía falta, y nada en el error se lo diría.

## Por qué es una trampa

El breaker responde una pregunta: ¿está fallando la dependencia? Un bulkhead lleno, un rate limiter agotado o un adaptive limit lleno responden otra: ¿está ocupada esta aplicación? Mezclar las dos tiene tres efectos malos:

- Una ráfaga de tu propio tráfico abre el breaker mientras la dependencia está sana, y el fallback responde llamadas que habrían salido bien un momento después.
- Un breaker abierto rechaza después incluso las llamadas que el bulkhead habría dejado entrar, así que la sobrecarga se convierte en una caída que dura al menos `waitInOpen`.
- Una prueba half-open rechazada por el bulkhead cuenta como prueba fallida, así que el breaker se vuelve a abrir sin preguntarle nada a la dependencia.

Resilience4j tiene la misma trampa: su bulkhead y su rate limiter también están dentro del breaker, y su `BulkheadFullException` y su `RequestNotPermitted` cuentan como fallas salvo que los pongas en `ignoreExceptions`. La diferencia en 1.0.0-RC4 es que ya no tienes que acordarte.

## La regla en RC4

El circuit breaker nunca registra `BulkheadFullException`, `RateLimitExceededException` ni `AdaptiveLimitFullException`. No cuentan ni como fallas ni como éxitos, y una prueba half-open que recibe uno devuelve su permiso. Eso vale digan lo que digan `recordOn`, `ignoreOn` o un clasificador, en métodos síncronos, `CompletionStage`, `Mono` y `Flux`, y en el `Pipeline`. Los detalles están en la [página del circuit breaker](../policies/circuit-breaker.md#with-other-policies).

Dos excepciones siguen contando, porque sí dicen algo de la dependencia:

- `CallTimeoutException`: una dependencia demasiado lenta está fallando. Por eso una dependencia lenta sigue abriendo el breaker, por sus timeouts y llamadas lentas.
- `CallNotPermittedException` de otro breaker más adentro: la dependencia que está detrás está fallando.

La regla es fija y no tiene interruptor, a propósito: nadie debería tener que configurar nada para salir de una trampa. Un breaker que reaccione a la sobrecarga es otra herramienta, y para eso están el [bulkhead](../policies/bulkhead.md) y el [adaptive limit](../policies/adaptive-limit.md).

## Qué cambia para ti

- Si pusiste `ignoreOn = BulkheadFullException.class`, o el rechazo del rate limiter o del adaptive limit, en un breaker, puedes quitarlo. Dejarlo no hace daño.
- Si migras desde Resilience4j, quita esas entradas de `ignoreExceptions`: la [guía de migración](../migrate/from-resilience4j.md#local-rejections) tiene la nota.
- Nada más cambia. Cada módulo publicado se comparó con RC3: ningún cambio incompatible en la API. Los tests de `examples`, compilados con el procesador de RC3, pasan sobre los módulos de RC4, así que el código generado por RC3 sigue funcionando.

## El piloto otra vez

Después, el piloto corrió con el código nuevo y sin `ignoreOn`, en la misma máquina.

> [!MEASURED]
> AWS `c7g.4xlarge` (Graviton3), JDK 25, 2026-11-13, el código de 1.0.0-RC4. Cada servicio tuvo 4 núcleos, el backend 4 y k6 8. La fila de Spring Boot; Micronaut y Quarkus quedan a menos de un milisegundo de ella hasta p99. Corrida `20261113-202658` de `pilot/run.sh aws`, resumida en el [README del piloto](https://github.com/fabiangftech/resilience4cata/tree/main/pilot).

| Fase | Pedidos respondidos por el backend | p50 | p99 | p99.9 |
|---|---|---:|---:|---:|
| estable | 100 % (su 1 % de 503 se recupera con reintentos) | 6.8 ms | 28.7 ms | 42.3 ms |
| backend caído | 0 %: el breaker respondió todos los pedidos | 0.1 ms | 0.2 ms | 1.2 ms |
| backend lento (1 s) | 0 %: breaker, bulkhead o timeout; nadie esperó el segundo | 0.1 ms | 0.3 ms | 260.6 ms |
| después de las caídas | 100 % | 6.8 ms | 28.7 ms | 42.1 ms |

Los tres servicios pasaron todos los chequeos, con cerca de 1,04 millones de peticiones cada uno. En el servicio de Spring, un pedido de los 270.000 de la fase estable lo respondió un breaker abierto. Empezó justo antes de que el backend se cayera en el minuto 4, y sus reintentos encontraron el breaker ya abierto: el breaker haciendo su trabajo, no la trampa de antes.

## Pruébalo

<!-- tabs:start -->

#### **Gradle**

<!-- not-compiled: build configuration -->
```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC4'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC4'
}
```

#### **Gradle (Kotlin)**

<!-- not-compiled: build configuration -->
```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.0.0-RC4")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.0.0-RC4")
}
```

#### **Maven**

<!-- not-compiled: build configuration -->
```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>1.0.0-RC4</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0-RC4</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

El [inicio rápido](../start/quickstart.md) protege una llamada inestable en unos cinco minutos; para una aplicación, empieza por la guía de [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) o [Quarkus](../guides/quarkus.md). El [anuncio de RC2](release-candidate.md) explica qué significa aquí un release candidate y qué comentarios ayudan más.

## Lo que viene

Si nada más tiene que cambiar, 1.0.0 sale con la misma API y empieza la promesa de [compatibilidad](../reference/compatibility.md). Este cambio es justo para lo que sirve un release candidate: después de 1.0, cambiar lo que cuenta el breaker en una versión menor habría sorprendido a los usuarios. Si pruebas RC4 y algo te sorprende, abre un [issue](https://github.com/fabiangftech/resilience4cata/issues) o una [discusión](https://github.com/fabiangftech/resilience4cata/discussions) en GitHub.
