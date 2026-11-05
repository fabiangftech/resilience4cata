# resilience4cata 1.0.0-RC2: pruébalo antes de la 1.0

La API de resilience4cata está congelada: 1.0.0-RC2 está en Maven Central y, salvo que tu feedback muestre que algo tiene que cambiar, 1.0.0 saldrá con la misma API.

*4 de noviembre de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor de resilience4cata*

## Qué significa aquí un release candidate

Hasta ahora, cada versión menor podía cambiar la API, y el [changelog](../changelog.md) listaba lo que se rompía. Desde 1.0.0 eso se acaba: las versiones menores y de parche de una misma versión mayor mantienen funcionando lo que lista la [página de compatibilidad](../reference/compatibility.md), que es la API Java, el código generado por un procesador más antiguo, las claves de configuración, los nombres de métricas, el JSON de los endpoints y los códigos de error.

Los release candidates son la última oportunidad de cambiar algo de eso a bajo costo. El build ya trata la API como congelada: cada módulo publicado se compara con 1.0.0-RC2 en Maven Central, y un cambio incompatible hace fallar el build. Si pruebas el candidato y algo resulta incómodo, un nombre, un valor por defecto, un mensaje de error o un punto de extensión que falta, ahora es cuando todavía se puede arreglar.

## Qué es resilience4cata

resilience4cata es una librería de resiliencia para Java 21+: retry, circuit breaker, rate limiter, timeout, bulkhead, fallback, además de hedging y un límite de concurrencia adaptativo. Pones anotaciones en una interfaz o una clase, y un procesador de anotaciones escribe una clase Java normal junto a ella al compilar. No hay proxy, aspecto ni agente. El método generado llama a cada política directamente, y una llamada exitosa no asigna memoria.

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), un hilo, ns por llamada, sobre el código de 1.0.0-RC1 (RC2 solo cambia las integraciones con frameworks), contra Resilience4j 2.4.0 con la misma configuración. De [las filas de la portada sobre el release candidate](../benchmarks.md#release-candidate).

| Llamada | resilience4cata | Resilience4j 2.4.0 |
|---|---:|---:|
| Retry, el primer intento tiene éxito | **1.4 ns**, 0 B | 22.9 ns, 0 B |
| Circuit breaker, todas las llamadas con éxito | **1.9 ns**, 0 B | 89.9 ns, 0 B |
| Timeout síncrono | **23.6 ns**, 0 B | 8907.6 ns, 183 B |
| Bulkhead, con espacios libres | **17.3 ns**, 0 B | 20.1 ns, 0 B |

Con cuatro hilos el circuit breaker se mantiene en 1.9 ns, mientras que el de Resilience4j sube a 849.5 ns. El [primer artículo](compile-time-resilience.md) explica de dónde sale la diferencia.

## Qué cambió en el camino a la 1.0

Antes de congelar la API, cuatro revisores recorrieron la librería: alguien que diseña APIs, alguien que revisa rendimiento, alguien que llega nuevo y alguien que decide si adoptarla. Sus hallazgos, y lo que se hizo con cada uno, están en el [plan hacia la 1.0](../plan-1.0.md). En resumen:

- **Los últimos cambios de API.** Dos son incompatibles y pequeños: `CircuitBreakerEvent.CallNotPermitted` ahora es `Rejected`, como los eventos de rechazo de las demás políticas, y dos constantes del hedge ya no son públicas. Los tipos que llevan el resultado de una llamada aceptan tipos anulables, lo que ayuda a quienes usan Kotlin y NullAway.
- **Promesas por escrito.** La [página de compatibilidad](../reference/compatibility.md) dice qué se mantiene compatible dentro de una versión mayor y qué puede crecer en una menor, como nuevas constantes de enums, nuevos componentes en los records de eventos o nuevas políticas.
- **Cuatro carreras corregidas.** Estaban en el bulkhead con `maxWait`, el límite adaptativo, el rate limiter y el timeout síncrono. Tres se reprodujeron primero, con un test que repite el entrelazado o con jcstress, y esos tests se comprobaron por mutación; la del rate limiter solo se pudo confirmar leyendo el código. La CI corre jcstress solo en x86, así que también se corrió una vez en Graviton3: pasaron los 65 tests, en 2598 configuraciones.
- **Documentación.** Hay una [receta para una llamada HTTP](../guides/http-calls.md) con retry y circuit breaker, probada contra un servidor real, y un paso en el quickstart que muestra el breaker abierto. La [guía de migración](../migrate/from-resilience4j.md) ahora traduce los nombres de métricas de Resilience4j a los nuestros.
- **RC2: la falta del procesador de anotaciones es un error.** Las anotaciones tienen retención `CLASS`, así que si el procesador no corría, nada lo notaba y los beans corrían sin protección. Ahora Spring Boot hace fallar el arranque con [R4C-SPR-006](../errors.md#r4c-spr-006), Micronaut hace fallar el bean con [R4C-MN-007](../errors.md#r4c-mn-007) y Quarkus hace fallar el build con [R4C-QK-008](../errors.md#r4c-qk-008). Cada mensaje nombra el tipo y la dependencia que hay que añadir.

El [changelog](../changelog.md#1.0.0-rc1) tiene la lista completa.

## Pruébalo

<!-- tabs:start -->

#### **Gradle**

<!-- not-compiled: build configuration -->
```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC2'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC2'
}
```

#### **Gradle (Kotlin)**

<!-- not-compiled: build configuration -->
```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.0.0-RC2")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.0.0-RC2")
}
```

#### **Maven**

<!-- not-compiled: build configuration -->
```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>1.0.0-RC2</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>1.0.0-RC2</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

Las páginas de este sitio todavía muestran 0.13.0 en sus snippets de build; escribe `1.0.0-RC2` en su lugar. Después, el [quickstart](../start/quickstart.md) protege una llamada inestable en unos cinco minutos. Para una aplicación, empieza por la guía de [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) o [Quarkus](../guides/quarkus.md). Si hoy usas Resilience4j, la [guía de migración](../migrate/from-resilience4j.md) traduce sus registros, configuraciones, decoradores y anotaciones de Spring a resilience4cata.

## Qué ayuda más

Cualquier cosa que querrías cambiar después de la 1.0 pero no podrías, porque rompería la compatibilidad:

- nombres, valores por defecto o atributos de anotaciones que te sorprendieron;
- un mensaje de error que no te dijo qué hacer;
- una configuración de framework que no funcionó como dice la guía;
- algo que necesitabas y tuviste que rodear.

Abre un [issue](https://github.com/fabiangftech/resilience4cata/issues) o inicia una [discusión](https://github.com/fabiangftech/resilience4cata/discussions) en GitHub. "Lo probé y funcionó sin más" también sirve. Los problemas de seguridad van por el reporte privado, como explica [SECURITY.md](https://github.com/fabiangftech/resilience4cata/blob/main/SECURITY.md).

## Qué viene después

Si nada tiene que cambiar, 1.0.0 sale con la misma API y empieza la promesa de compatibilidad. Si algo sí tiene que cambiar, antes habrá otro candidato.
