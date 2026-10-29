# Project Reactor

Un método que devuelve el `Mono<T>` de Reactor recibe la forma asíncrona de cada política, una vez por suscripción, con el contexto del suscriptor, y se cancela a través de las políticas cuando el suscriptor cancela.

<div class="r4c-facts">

| Módulo | Reactor | Tipos | Desde |
|---|---|---|---|
| `resilience4cata-reactor` | 3.6 o posterior | `Mono<T>` (no `Flux`) | 0.11.0 |

</div>

## Instalación

Agrega el módulo junto a los demás; el procesador no necesita nada nuevo.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:0.11.0'
    implementation 'dev.resilience4cata:resilience4cata-reactor:0.11.0'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:0.11.0'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:0.11.0")
    implementation("dev.resilience4cata:resilience4cata-reactor:0.11.0")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:0.11.0")
}
```

#### **Maven**

```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-reactor</artifactId>
  <version>0.11.0</version>
</dependency>
```

<!-- tabs:end -->

El módulo depende de `resilience4cata-core` y de `reactor-core`; gana el Reactor más nuevo de tu aplicación. Un método `Mono` compilado sin él es el error [`R4C-ASY-003`](../errors.md#r4c-asy-003).

## Una interfaz con Mono

Las anotaciones son las mismas que en cualquier otro método; decide el tipo de retorno.

<!-- snippet: reactor-interface -->
```java
public interface PriceClient {

    @Retry(maxAttempts = 3, delay = "50ms")
    @CircuitBreaker(name = "prices")
    @Timeout("2s")
    @Fallback(method = "unknown")
    Mono<BigDecimal> price(String sku);

    default Mono<BigDecimal> unknown(String sku, Throwable failure) {
        return Mono.just(BigDecimal.ZERO);
    }
}
```

<!-- snippet: reactor-call -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder().build();
PriceClient prices = Resilient.of(PriceClient.class, http, registry);

Mono<BigDecimal> price = prices.price("sku-1");   // nothing runs yet
BigDecimal value = price.block();                 // subscribes: two failed attempts, then 42
```

Las clases anotadas funcionan igual ([clases anotadas](annotated-classes.md)), y también los beans de Spring, Micronaut y Quarkus: en un controller de WebFlux, devuelve el `Mono` del bean protegido tal cual.

## Qué hace un método Mono

- **Nada corre antes de una suscripción.** Llamar a `price("sku-1")` construye un `Mono`; cada suscripción es una llamada a través de las políticas, así que suscribirse dos veces (o `repeat()`) llama dos veces.
- **Cada intento vuelve a llamar al método.** Un retry o un hedge llama a tu implementación por un `Mono` nuevo y se suscribe a él, como en un método `CompletionStage`. Un método que lanza una excepción, o devuelve `null`, es un intento fallido.
- **El contexto del suscriptor llega a cada intento,** y también al `Mono` del fallback, así que el tracing, el `ReactiveSecurityContextHolder` de Spring Security y tus propias claves funcionan dentro de las políticas:

<!-- snippet: reactor-context -->
```java
Mono<BigDecimal> price = prices.price("sku-1")
        .contextWrite(Context.of("tenant", "acme"));   // the delegate's Mono, and every retry, see it
```

- **Un `Mono` vacío es un éxito sin valor** (`null` para un classifier), y el resultado también es vacío.
- **Cancelar la suscripción cancela la llamada a través de cada política:** se cancela el `Mono` del intento en curso, un retry que espera su delay se detiene, el breaker no registra nada, el bulkhead y el adaptive limit liberan su slot. Un cliente que se desconecta de un endpoint de WebFlux hace esto.
- **Los errores llegan sin demanda, los valores con demanda,** como exige Reactive Streams; el `Mono` generado pasa el [TCK de Reactive Streams](https://github.com/reactive-streams/reactive-streams-jvm/tree/master/tck).
- **Ninguna política bloquea el hilo,** lo que BlockHound verifica en el build: un rate limiter o un bulkhead con `maxWait` hacen esperar a la llamada sin estacionar un hilo, como en cualquier [método async](async.md).

Qué hilo emite: el primer intento se suscribe en el hilo que se suscribe. Los delays de retry, los hedges, los timeouts y las esperas del rate limiter se disparan en el timer de la librería, que entrega el trabajo a un virtual thread nuevo, así que el valor de un intento posterior llega ahí. Agrega `publishOn(...)` después del método si los operadores siguientes necesitan un scheduler en particular.

> [!MEASURED]
> En AWS Graviton3, un método `Mono` con circuit breaker cuesta 130 ns por llamada contra 246 ns del operador de resilience4j-reactor 2.4.0 (119 contra 1379 ns con cuatro hilos), y un stack de retry, breaker, timeout y bulkhead 227 ns contra 4338 ns, incluidos los 52 ns de suscribirse al `Mono`. Consulta [Rendimiento](../performance.md#reactor).

## Fallbacks

Un fallback de un método `Mono` devuelve el valor (`BigDecimal`) o un `Mono` de él (`Mono<BigDecimal>`), como método hermano o como [`FallbackHandler`](../policies/fallback.md); un `CompletionStage` no se acepta. Su `Mono` se suscribe con el contexto del llamador.

## Código que no puedes anotar

`PipelineOperator` aplica un [pipeline](pipeline.md) a un `Mono`, para una llamada de `WebClient` o un cliente de terceros:

<!-- snippet: reactor-operator -->
```java
PipelineOperator<String> resilient = PipelineOperator.of(Pipeline.builder(registry, "rates", String.class)
        .retry(r -> r.maxAttempts(3).delay(Duration.ofMillis(50)))
        .timeout(t -> t.timeout(Duration.ofSeconds(2)))
        .build());

Mono<String> rates = webClientCall.transform(resilient);   // each attempt subscribes to webClientCall again
```

Cada intento se vuelve a suscribir a la fuente, lo que vuelve a ejecutar una petición de `WebClient`. Una fuente que da el mismo resultado a cada suscriptor (`cache()`, una fuente hot) no se reintenta de forma útil; constrúyela por intento con `resilient.defer(() -> ...)`.

## Testing

Con un [`ManualClock`](testing.md), los delays, timeouts y hedges ocurren cuando la prueba avanza el reloj, y `StepVerifier` verifica el resultado:

<!-- snippet: reactor-testing -->
```java
ManualClock clock = new ManualClock();
ResilienceRegistry registry = ResilienceTesting.registry(clock).build();
PriceClient prices = Resilient.of(PriceClient.class, slow, registry);

StepVerifier.create(prices.price("sku-1"))
        .then(() -> clock.advance(Duration.ofSeconds(7)))   // three 2 s timeouts and the delays between them
        .expectNext(BigDecimal.ZERO)                        // the fallback
        .verifyComplete();
```

El reloj manual es el de la librería; `StepVerifier.withVirtualTime` mueve los schedulers de Reactor, no los timers de las políticas.

## Límites

- **`Flux` todavía no está soportado:** un método que lo devuelve es [`R4C-ASY-002`](../errors.md#r4c-asy-002). Devuelve `Mono<List<T>>` (`collectList()`) cuando los elementos caben en memoria.
- **Exactamente `Mono`:** una subclase de él, `Mono<? super T>` o un `Mono` sin tipo no es un método `Mono` ([`R4C-ASY-001`](../errors.md#r4c-asy-001), o `R4C-ASY-002` para una subclase).
- **Transacciones:** un `@Transactional` reactivo en un método `Mono` con retry o hedge de una clase anotada correría todos los intentos en una sola transacción, lo que Spring, Micronaut y Quarkus reportan al arrancar, como en los demás métodos ([`R4C-SPR-004`](../errors.md#r4c-spr-004)).
- **Mutiny y RxJava** todavía no están soportados; convierte a un `CompletionStage` (`uni.subscribeAsCompletionStage()`).
