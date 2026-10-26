# Clases anotadas

Anota una clase no final en lugar de una interfaz, y el procesador genera una subclase cuyos overrides aplican las políticas, así que las llamadas que el objeto se hace a sí mismo también quedan protegidas.

## Anota la clase

<!-- snippet: annotated-classes-service -->
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

El procesador escribe `StockService$Resilient extends StockService`. Solo sobrescribe los métodos que tienen políticas (aquí `stock`), y cada uno termina con `super.stock(...)`. `total` no se sobrescribe, pero su llamada a `stock` pasa por el override, porque `this.stock(...)` despacha a él.

## Créala

`Resilient.create` recibe la clase, el registry y los argumentos de uno de los constructores de la clase:

<!-- snippet: annotated-classes-create -->
```java
StockService stock = Resilient.create(StockService.class, registry, warehouse);
int total = stock.total(List.of("a", "b"));   // each stock(sku) call is retried
```

Por cada constructor no privado de la clase, la subclase generada tiene uno que recibe primero un `ResilienceRegistry` y después los mismos parámetros. Llamarlo directamente hace lo mismo que `Resilient.create`, con verificación del compilador:

<!-- snippet: annotated-classes-constructor -->
```java
StockService stock = new StockService$Resilient(registry, warehouse);   // checked by the compiler
```

`Resilient.create` elige el constructor como lo haría el compilador, las coincidencias exactas antes que los varargs. Si ningún constructor encaja, falla con [`R4C-RT-009`](../errors.md#r4c-rt-009); si encaja más de uno (por ejemplo, al pasar `null` a constructores sobrecargados), con [`R4C-RT-010`](../errors.md#r4c-rt-010). Los dos mensajes sugieren la llamada `new …$Resilient(registry, …)` que conviene usar en su lugar.

## Constructores e inyección de dependencias :id=constructor-injection

Cada constructor replicado conserva los nombres de parámetro del original y copia sus anotaciones y las de cada parámetro, así que un contenedor de inyección de dependencias que construye `StockService$Resilient` ve los mismos metadatos que en `StockService`: `@Autowired` o `@Inject` en uno de varios constructores, `@Qualifier("…")`, `@Named("…")` o `@Value("${…}")` en un parámetro.

<!-- not-compiled: needs Spring on the class path -->
```java
public class ReportService {
    @Autowired
    public ReportService(@Qualifier("reportsDb") DataSource dataSource, @Value("${reports.limit}") int limit) { … }
    …
}
```

<!-- not-compiled: generated code -->
```java
public class ReportService$Resilient extends ReportService {
    @org.springframework.beans.factory.annotation.Autowired
    public ReportService$Resilient(dev.resilience4cata.ResilienceRegistry registry,
            @org.springframework.beans.factory.annotation.Qualifier("reportsDb") javax.sql.DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value("${reports.limit}") int limit) {
        super(dataSource, limit);
        …
    }
}
```

- Solo se copian las anotaciones que quedan en los class files (retención `CLASS` o `RUNTIME`); las de retención `SOURCE`, como las de Lombok, no. Las anotaciones de uso de tipo (`@Nullable` de JSpecify) tampoco se repiten.
- El parámetro `ResilienceRegistry` no lleva anotación: los contenedores lo inyectan por tipo, así que la aplicación necesita exactamente un bean `ResilienceRegistry`.
- Si un parámetro del constructor de la clase ya se llama `registry`, el generado se llama `r4c$registry`. Los parámetros llamados `java` o `dev` se renombran a `r4c$p<índice>`, porque esos nombres ocultarían los paquetes que usa el constructor generado.
- `@ConstructorProperties` lista un nombre por parámetro, así que la copia generada empieza por el del registry.
- Los nombres de parámetro son los del código fuente, así que un contenedor que empareja beans por nombre de parámetro (Spring, compilando con `-parameters`) ve los mismos nombres en las dos clases.

Los overrides hacen lo mismo con los métodos: cada uno repite las anotaciones del método que sobrescribe y de sus parámetros, con las mismas reglas, así que las anotaciones que los frameworks leen solo en el método invocado (el `@Transactional` de Jakarta en Spring, los interceptor bindings de CDI o Micronaut, `@Deprecated`) no se pierden. No se repiten las anotaciones propias de resilience4cata ni los bundles que las llevan, ni las anotaciones a nivel de tipo de la clase (las `@Inherited` llegan a la subclase de todos modos).

## ¿Interfaz o clase?

| | Interfaz anotada | Clase anotada |
|---|---|---|
| Se crea con | `Resilient.of(Api.class, impl, registry)` | `Resilient.create(Impl.class, registry, args...)` |
| La implementación | Cualquiera: escrita a mano, generada, una lambda | Debe ser una clase no final tuya |
| `this.other()` dentro de la implementación | No se intercepta | Se intercepta |
| Anotaciones que cuentan | Solo las de la interfaz | Las de la clase, sus superclases y sus interfaces |
| Costo | Código generado, una lectura de campo para llegar al delegate | Código generado que llama a `super` directamente |

> [!MEASURED]
> En AWS Graviton3 (`c7g.xlarge`), un retry cuyo primer intento tiene éxito cuesta 1.0 ns en el estilo de clase contra 1.2 ns en el estilo de interfaz; con un reintento, 5.6 ns contra 6.5 ns. [Benchmarks](../benchmarks.md#clases-anotadas-genéricos-overrides-de-config-bulkhead-con-ticker-2026-10-24-aws-graviton3)

Una clase se procesa solo si ella misma declara una anotación de resiliencia: en la clase, en uno de sus métodos o a través de un [bundle](bundles.md). Una clase que solo implementa una interfaz anotada no recibe subclase; envuélvela con `Resilient.of(Api.class, impl, registry)`, que aplica las políticas de la interfaz y no intercepta la auto-invocación.

## Anotaciones en la clase, sus superclases y sus interfaces

El estilo de clase combina, atributo por atributo, todas las anotaciones que se aplican a un método. De menor a mayor precedencia:

1. las anotaciones a nivel de tipo de la interfaz,
2. las anotaciones del método de la interfaz,
3. las anotaciones a nivel de tipo de las superclases que declaran el método, de arriba hacia abajo,
4. las anotaciones a nivel de tipo de la propia clase,
5. las anotaciones del método en la clase (un override en una subclase gana sobre el método de la superclase).

<!-- snippet: annotated-classes-api -->
```java
@Retry(delay = "10ms", jitter = Jitter.NONE)
public interface OrderApi {

    @Retry(maxAttempts = 2)
    String status(String id);

    @Retry(maxAttempts = 3)
    String quote(String id);
}
```

<!-- snippet: annotated-classes-order-service -->
```java
public class OrderService implements OrderApi {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    @Retry(maxAttempts = 4)   // wins over the interface method's 2; the delay still comes from OrderApi
    public String status(String id) {
        calls.incrementAndGet();
        throw new IllegalStateException("orders down");
    }

    @Override                 // not annotated here: OrderApi's three attempts apply
    public String quote(String id) {
        calls.incrementAndGet();
        throw new IllegalStateException("quotes down");
    }

    @Fallback(method = "cachedPrice")
    public String price(String id) {
        throw new IllegalStateException("pricing down");
    }

    protected String cachedPrice(String id, IllegalStateException e) {   // not private: the subclass calls it
        return "cached price of " + id;
    }
}
```

`Resilient.create(OrderService.class, registry)` le da a `status` 4 intentos y a `quote` 3, los dos con el delay de 10 ms de la interfaz, y a `price` su fallback. El mismo objeto envuelto con `Resilient.of(OrderApi.class, ...)` vería solo las anotaciones de `OrderApi`: 2 intentos para `status`.

Una anotación a nivel de tipo en la clase se aplica a todos los métodos que la subclase puede sobrescribir, incluidos los heredados, salvo:

- los métodos final, que se omiten;
- `toString`, `equals`, `hashCode` y los demás métodos de `Object`, que solo toman las anotaciones escritas en el propio método.

Los ids de instancia usan la clase anotada: `com.acme.OrderService#status(java.lang.String)`.

## Restricciones

Todos estos casos son errores de compilación, con un código y la corrección en la referencia de errores:

| Qué | Código |
|---|---|
| La clase es `final` | [`R4C-SUB-001`](../errors.md#r4c-sub-001) |
| La clase es `sealed` | [`R4C-SUB-002`](../errors.md#r4c-sub-002) |
| La clase es `abstract` | [`R4C-SUB-003`](../errors.md#r4c-sub-003) |
| La clase es `private`, o está anidada en una clase privada | [`R4C-SUB-004`](../errors.md#r4c-sub-004) |
| La clase es interna (anidada no estática) | [`R4C-SUB-005`](../errors.md#r4c-sub-005) |
| Todos los constructores son `private` | [`R4C-SUB-006`](../errors.md#r4c-sub-006) |
| Un método `final` está anotado a nivel de método (en la clase, una superclase o una interfaz) | [`R4C-SUB-007`](../errors.md#r4c-sub-007) |
| Un método anotado es package-private en una superclase de otro paquete | [`R4C-SUB-008`](../errors.md#r4c-sub-008) |
| Un método `static` o `private` está anotado | [`R4C-APT-002`](../errors.md#r4c-apt-002) |
| El tipo es un enum o un record | [`R4C-APT-001`](../errors.md#r4c-apt-001) |

Además:

- **Los métodos fallback hermanos se llaman sobre `this`**, así que no pueden ser `private` (ni package-private en una superclase de otro paquete). `protected` funciona.
- **Los métodos que el constructor llama sobre `this` se ejecutan antes de que las políticas estén configuradas**, porque los campos de la subclase se asignan después de que `super(...)` retorna. No anotes métodos que los constructores llaman.
- **La subclase generada no es `final`**, así que los frameworks pueden crear proxies de ella (CGLIB para `@Transactional`, proxies con scope). Sus overrides llaman a las políticas; una subclase tuya que los vuelva a sobrescribir se las salta. Extiende tu propia clase, no la generada.
- `Resilient.create` rechaza una interfaz ([`R4C-RT-007`](../errors.md#r4c-rt-007)) y un tipo que no puede tener una subclase generada ([`R4C-RT-008`](../errors.md#r4c-rt-008)).

## Con Lombok

Si Lombok genera el constructor o los métodos de una clase anotada, Lombok debe ejecutarse antes que resilience4cata: decláralo primero en el processor path ([instalación](../start/installation.md#con-otros-procesadores-de-anotaciones)).

## Métodos async

Los métodos de una clase anotada pueden devolver `CompletionStage<T>` o `CompletableFuture<T>`, con las mismas reglas que en las interfaces ([métodos async](async.md)).
