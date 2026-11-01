# Lo que cuesta una anotación de resiliencia en Spring, Micronaut y Quarkus

En los tres frameworks, un bean protegido cuesta lo que cuesta su política, unos pocos nanosegundos; las mismas anotaciones a través de los interceptores habituales de cada framework cuestan desde decenas de nanosegundos hasta varios microsegundos por llamada.

*1 de noviembre de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor de resilience4cata*

## La pregunta

El [primer artículo](compile-time-resilience.md) comparaba políticas llamadas directamente: una clase generada contra los decoradores de Resilience4j. Pocas aplicaciones llaman a una política directamente. Ponen una anotación en un método de un bean y dejan que el framework haga el resto: Spring con un aspecto, Micronaut con un interceptor, Quarkus con un interceptor de CDI como el de SmallRye Fault Tolerance. Esa maquinaria corre en cada llamada, también en las que salen bien, así que quise medir lo que de verdad recibe una aplicación.

## Cómo se midió

Los benchmarks arrancan un contexto de aplicación real y llaman al bean que inyecta el framework, desde uno y desde cuatro hilos:

- **Spring Boot 4.1.2**: los aspectos de resilience4j-spring-boot4 2.4.0 contra la subclase generada de resilience4cata, que la integración de Spring registra como el bean.
- **Micronaut 5.2.2** sobre JDK 25: los interceptores de resilience4j-micronaut 2.4.0 y los `@Retryable` y `@CircuitBreaker` propios de Micronaut, contra la subclase generada.
- **Quarkus 3.40.1**: las anotaciones de SmallRye Fault Tolerance, y decoradores de Resilience4j construidos una vez en un bean y llamados a mano, sin ningún interceptor, que es el mejor caso de Resilience4j ahí.

La configuración es la misma que en los benchmarks de core: un retry de tres intentos sin espera, un circuit breaker con una ventana de 100 llamadas, un bulkhead de 1000 llamadas que nunca se llena y un rate limiter que nunca se agota. Un benchmark rápido también puede ser un interceptor que nunca corrió, así que cada setup comprueba primero que cada instancia de Resilience4j participa en su llamada. El breaker contó la llamada, un bulkhead lleno la rechaza, un limitador vaciado la rechaza y un breaker abierto la rechaza.

Del lado de resilience4cata no hay nada específico de cada framework. La clase que anotas se compila con una subclase generada al lado, y la integración hace que esa subclase sea el bean:

<!-- not-compiled: una ilustración corta; las guías de Spring, Micronaut y Quarkus tienen los beans probados -->
```java
@Service
public class Inventory {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    public int stock(String sku) {
        return warehouse.stock(sku);
    }
}
```

El bean es `Inventory$Resilient`, cuyo override de `stock` es código normal que llama a `super.stock(sku)`. No hay proxy ni cadena de interceptores en medio.

## Los números

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), 3 forks, ns por llamada con un hilo; resilience4cata asigna 0 B en todas las filas. Se muestra el rival más rápido de cada framework: los interceptores de Resilience4j en Spring y Micronaut, los decoradores de Resilience4j llamados a mano o Fault Tolerance en Quarkus. De [Beans de framework y una comparación completa, 0.13.0](../benchmarks.md#frameworks).

| Llamada | Spring: resilience4cata | Spring: Resilience4j | Micronaut: resilience4cata | Micronaut: Resilience4j | Quarkus: resilience4cata | Quarkus: rival más rápido |
|---|---:|---:|---:|---:|---:|---:|
| retry, el primer intento funciona | **1.1** | 1346.0 | **1.1** | 104.8 | **1.1** | 22.9 |
| circuit breaker | **1.5** | 1737.0 | **1.5** | 304.5 | **1.5** | 101.6 |
| circuit breaker abierto | **2.3** | 4174.2 | **2.3** | 1127.4 | **2.3** | 355.9 |
| rate limiter | **42.0** | 1481.8 | **42.0** | 141.9 | **42.0** | 66.7 |
| bulkhead | **20.4** | 1687.7 | **20.7** | 490.2 | 20.4 | **20.3** |
| retry + breaker + rate limiter + bulkhead | **62.1** | 6104.4 | **62.1** | 1117.9 | **62.0** | 217.2 |

Con cuatro hilos las diferencias crecen, porque el estado compartido detrás de los interceptores empieza a competir. El circuit breaker de Spring pasa a 1.4 ns contra 2142 ns, y el de Micronaut a 1.5 ns contra 1501 ns.

## Adónde se va el tiempo

La columna de resilience4cata es igual en los tres frameworks, e igual que en los benchmarks de core. El framework no está en el camino de la llamada. Las columnas rivales cambian según el framework porque cada uno paga por un tipo distinto de intercepción:

- **Spring:** los aspectos de resilience4j-spring-boot4 cuestan de 1.3 a 6 µs y de 1.6 a 6.3 KB por llamada. Su pointcut es dinámico, y evaluarlo carga una clase por nombre (`Class.forName`) en cada llamada.
- **Micronaut:** su AOP en tiempo de compilación es mucho más barato, de 100 ns a 1.1 µs para los interceptores de resilience4j-micronaut. Los `@Retryable` y `@CircuitBreaker` propios de Micronaut cuestan de 1.0 a 1.8 µs y de 2.3 a 3.6 KB.
- **Quarkus:** los interceptores de Fault Tolerance cuestan de 0.3 a 1.9 µs por llamada. Los decoradores de Resilience4j, llamados a mano sin interceptor, son ahí el rival más rápido. Ese es además el único caso de la tabla que resilience4cata no gana: un bulkhead con huecos libres, 20.4 ns contra 20.3 ns. Los dos toman y devuelven un permiso con dos operaciones atómicas, y por debajo de eso no llega el hardware.

## Los casos que no fueron victorias

La misma ejecución repitió cada benchmark de core contra la variante más rápida de Resilience4j. De 156 casos comparables, incluidos los de framework, resilience4cata ganó 150. Dije que mostraría los empates y las derrotas, así que aquí están los otros seis:

- **Un hedge con una espera de 1 ms perdía por 2×**, con un hilo y con cuatro: 2.1 ms contra 1.07 ms. El timer compartido trabaja en ticks de un dieciseisavo de la espera, al menos 1 ms, y una espera podía empezar hasta unos dos ticks tarde. Ahora los hedges cuentan su espera desde el inicio de la llamada, a cambio de una lectura del reloj, y el timer despierta en el plazo. El hedge sale en 1060 µs contra 1067 µs de Resilience4j ([Esperas del hedge a su hora](../benchmarks.md#hedge-precision)). Ninguna de las dos librerías puede bajar mucho más, porque las dos esperan a que el sistema operativo despierte un thread.
- **Dos casos de bulkhead con un hilo empatan en el suelo del hardware**: el de Quarkus de arriba, y el mismo bulkhead llamado directamente (20.5 ns contra 20.4 ns).
- **Dos casos de `Flux` quedan dentro del error**: el bulkhead con cuatro hilos y el rate limiter con uno, donde resilience4cata asigna más por stream que los operadores de resilience4j-reactor. En eso se está trabajando.

Perder un benchmark fue útil. El retraso del hedge estaba en la documentación desde 0.4.0 ("hasta unos dos ticks tarde"), pero solo una comparación con un timer preciso lo mostró como lo que era.

## Advertencias

- **Los microsegundos solo importan junto a llamadas baratas.** Una llamada remota de 20 ms no notará 2 µs de intercepción. Un servicio que protege cachés en memoria, llamadas locales o millones de llamadas por segundo sí.
- **Son microbenchmarks** en un tipo de máquina, con cada política configurada para no rechazar nunca. La [página de benchmarks](../benchmarks.md) tiene el método, las notas de equidad y todos los números, empates y derrotas incluidos.
- **El bean es una subclase.** Con clases anotadas, un proxy basado en clases como el `@Transactional` de Spring queda fuera de las políticas, así que una transacción abarca todos los reintentos. Cada integración se niega a arrancar cuando un método reintentado se une a una transacción, y las guías explican cómo elegir.

## Pruébalo

Añade la integración de tu framework y el procesador de anotaciones. Las guías de [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) y [Quarkus](../guides/quarkus.md) tienen las dependencias para Gradle y Maven y un ejemplo probado. Si vienes de Resilience4j, la [guía de migración](../migrate/from-resilience4j.md) traduce cada anotación y propiedad. El código tiene licencia Apache-2.0 en [GitHub](https://github.com/fabiangftech/resilience4cata).
