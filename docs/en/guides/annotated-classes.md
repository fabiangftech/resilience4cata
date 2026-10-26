# Annotated classes

Annotate a non-final class instead of an interface, and the processor generates a subclass whose overrides apply the policies, so calls the object makes to itself are protected too.

## Annotate the class

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

The processor writes `StockService$Resilient extends StockService`. It overrides only the methods that have policies (here `stock`), each ending with `super.stock(...)`. `total` is not overridden, but its call to `stock` goes through the override, because that is what `this.stock(...)` dispatches to.

## Create it

`Resilient.create` takes the class, the registry, and the arguments of one of the class's constructors:

<!-- snippet: annotated-classes-create -->
```java
StockService stock = Resilient.create(StockService.class, registry, warehouse);
int total = stock.total(List.of("a", "b"));   // each stock(sku) call is retried
```

For every non-private constructor of the class, the generated subclass has one with a `ResilienceRegistry` first and the same parameters after it. Calling it directly does the same as `Resilient.create`, checked by the compiler:

<!-- snippet: annotated-classes-constructor -->
```java
StockService stock = new StockService$Resilient(registry, warehouse);   // checked by the compiler
```

`Resilient.create` picks the constructor the way the compiler would, exact matches before varargs. With no fitting constructor it fails with [`R4C-RT-009`](../errors.md#r4c-rt-009), with more than one (for example `null` passed to overloaded constructors) with [`R4C-RT-010`](../errors.md#r4c-rt-010); both messages suggest the `new …$Resilient(registry, …)` call to use instead.

## Constructors and dependency injection :id=constructor-injection

Each mirrored constructor keeps the parameter names of the original and copies its annotations and the annotations of each parameter, so a dependency injection container that builds `StockService$Resilient` sees the same metadata as on `StockService`: `@Autowired` or `@Inject` on one of several constructors, `@Qualifier("…")`, `@Named("…")` or `@Value("${…}")` on a parameter.

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

- Only annotations kept in class files (retention `CLASS` or `RUNTIME`) are copied; `SOURCE` annotations, such as Lombok's, are not. Type-use annotations (`@Nullable` from JSpecify) are not repeated either.
- The `ResilienceRegistry` parameter has no annotation: containers inject it by type, so the application needs exactly one `ResilienceRegistry` bean.
- If a parameter of the class's constructor is itself named `registry`, the generated one is named `r4c$registry`. Parameters named `java` or `dev` are renamed `r4c$p<index>`, because those names would hide the packages that the generated constructor uses.
- `@ConstructorProperties` lists one name per parameter, so the generated copy starts with the registry's.
- The parameter names are those of the source, so a container that matches beans by parameter name (Spring, compiled with `-parameters`) sees the same names on both classes.

The overrides do the same for methods: each one repeats the annotations of the method it overrides and of its parameters, with the same rules, so annotations that frameworks read only on the invoked method (Jakarta's `@Transactional` under Spring, CDI or Micronaut interceptor bindings, `@Deprecated`) are not lost. resilience4cata's own annotations and the bundles that carry them are not repeated, and neither are the class's type-level annotations (`@Inherited` ones reach the subclass anyway).

## Interface or class?

| | Annotated interface | Annotated class |
|---|---|---|
| Created with | `Resilient.of(Api.class, impl, registry)` | `Resilient.create(Impl.class, registry, args...)` |
| The implementation | Anything: hand-written, generated, a lambda | Must be a non-final class you own |
| `this.other()` inside the implementation | Not intercepted | Intercepted |
| Annotations that count | The interface's only | The class's, its superclasses' and its interfaces' |
| Cost | Generated code, one field read to reach the delegate | Generated code that calls `super` directly |

> [!MEASURED]
> On AWS Graviton3 (`c7g.xlarge`), a retry whose first attempt succeeds costs 1.0 ns in the class style against 1.2 ns in the interface style; with one retry, 5.6 ns against 6.5 ns. [Benchmarks](../benchmarks.md#annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3)

A class is processed only if it declares a resilience annotation itself: on the class, on one of its methods, or through a [bundle](bundles.md). A class that only implements an annotated interface gets no subclass; wrap it with `Resilient.of(Api.class, impl, registry)`, which applies the interface's policies and does not intercept self-invocation.

## Annotations on the class, its superclasses and its interfaces

The class style merges every annotation that applies to a method, attribute by attribute. Lowest precedence first:

1. the interface's type-level annotations,
2. the interface method's annotations,
3. the type-level annotations of the declaring superclasses, top-down,
4. the class's own type-level annotations,
5. the method's annotations in the class (an override in a subclass beats the superclass's method).

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

`Resilient.create(OrderService.class, registry)` gives `status` 4 attempts, `quote` 3, both with the interface's 10 ms delay, and `price` its fallback. The same object wrapped with `Resilient.of(OrderApi.class, ...)` would see only `OrderApi`'s annotations: 2 attempts for `status`.

A type-level annotation on the class applies to every method the subclass can override, inherited ones included, except:

- final methods, which are skipped;
- `toString`, `equals`, `hashCode` and the other `Object` methods, which take only annotations written on the method itself.

Instance ids use the annotated class: `com.acme.OrderService#status(java.lang.String)`.

## Restrictions

All of these are compile errors, with a code and a fix in the error reference:

| What | Code |
|---|---|
| The class is `final` | [`R4C-SUB-001`](../errors.md#r4c-sub-001) |
| The class is `sealed` | [`R4C-SUB-002`](../errors.md#r4c-sub-002) |
| The class is `abstract` | [`R4C-SUB-003`](../errors.md#r4c-sub-003) |
| The class is `private`, or nested in a private class | [`R4C-SUB-004`](../errors.md#r4c-sub-004) |
| The class is an inner (non-static nested) class | [`R4C-SUB-005`](../errors.md#r4c-sub-005) |
| Every constructor is `private` | [`R4C-SUB-006`](../errors.md#r4c-sub-006) |
| A `final` method is annotated at method level (in the class, a superclass or an interface) | [`R4C-SUB-007`](../errors.md#r4c-sub-007) |
| An annotated method is package-private in a superclass from another package | [`R4C-SUB-008`](../errors.md#r4c-sub-008) |
| A `static` or `private` method is annotated | [`R4C-APT-002`](../errors.md#r4c-apt-002) |
| The type is an enum or a record | [`R4C-APT-001`](../errors.md#r4c-apt-001) |

Also:

- **Fallback siblings are called on `this`**, so they must not be `private` (and not package-private in a superclass from another package). `protected` works.
- **Methods the constructor calls on `this` run before the policies are set up**, because the subclass's fields are assigned after `super(...)` returns. Don't annotate methods that constructors call.
- **The generated subclass is not `final`**, so frameworks can proxy it (CGLIB for `@Transactional`, scoped proxies). Its overrides call the policies; a subclass of your own that overrides them again bypasses them. Extend your own class, not the generated one.
- `Resilient.create` rejects an interface ([`R4C-RT-007`](../errors.md#r4c-rt-007)) and a type that cannot have a generated subclass ([`R4C-RT-008`](../errors.md#r4c-rt-008)).

## With Lombok

If Lombok generates the constructor or the methods of an annotated class, Lombok must run before resilience4cata: declare it first on the processor path ([installation](../start/installation.md#with-other-annotation-processors)).

## Async methods

Methods of an annotated class may return `CompletionStage<T>` or `CompletableFuture<T>`, with the same rules as in interfaces ([async methods](async.md)).
