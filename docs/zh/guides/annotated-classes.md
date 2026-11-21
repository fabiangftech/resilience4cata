# 注解类

在非 final 类上（而不是接口上）添加注解，注解处理器会生成一个子类，由其中的覆写方法应用策略，因此对象对自身的调用同样受到保护。

## 为类添加注解 :id=annotate-the-class

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

处理器会写出 `StockService$Resilient extends StockService`。它只覆写带有策略的方法（这里是 `stock`），每个覆写方法都以 `super.stock(...)` 结尾。`total` 没有被覆写，但它对 `stock` 的调用会经过覆写方法，因为 `this.stock(...)` 分派到的正是覆写方法。

## 创建实例 :id=create-it

`Resilient.create` 接收类、注册表以及该类某个构造函数的参数：

<!-- snippet: annotated-classes-create -->
```java
StockService stock = Resilient.create(StockService.class, registry, warehouse);
int total = stock.total(List.of("a", "b"));   // each stock(sku) call is retried
```

对于类的每个非 private 构造函数，生成的子类都有一个对应的构造函数：第一个参数是 `ResilienceRegistry`，后面是相同的参数。直接调用它的效果与 `Resilient.create` 相同，并由编译器检查：

<!-- snippet: annotated-classes-constructor -->
```java
StockService stock = new StockService$Resilient(registry, warehouse);   // checked by the compiler
```

`Resilient.create` 按照编译器的方式选择构造函数，精确匹配优先于可变参数。没有合适的构造函数时，它以 [`R4C-RT-009`](../errors.md#r4c-rt-009) 失败；有多个时（例如向重载的构造函数传入 `null`）以 [`R4C-RT-010`](../errors.md#r4c-rt-010) 失败；两条消息都会建议改用 `new …$Resilient(registry, …)` 调用。

## 构造函数与依赖注入 :id=constructor-injection

每个镜像的构造函数都保留原构造函数的参数名，并复制其注解以及每个参数的注解，因此构建 `StockService$Resilient` 的依赖注入容器看到的元数据与 `StockService` 上的相同：多个构造函数之一上的 `@Autowired` 或 `@Inject`，参数上的 `@Qualifier("…")`、`@Named("…")` 或 `@Value("${…}")`。

<!-- not-compiled: 需要类路径上有 Spring -->
```java
public class ReportService {
    @Autowired
    public ReportService(@Qualifier("reportsDb") DataSource dataSource, @Value("${reports.limit}") int limit) { … }
    …
}
```

<!-- not-compiled: 生成的代码 -->
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

- 只复制保留在 class 文件中的注解（保留策略为 `CLASS` 或 `RUNTIME`）；`SOURCE` 注解（例如 Lombok 的）不会被复制。类型使用注解（JSpecify 的 `@Nullable`）也不会重复。
- `ResilienceRegistry` 参数不带注解：容器按类型注入它，因此应用中必须恰好有一个 `ResilienceRegistry` bean。
- 如果类的构造函数中某个参数本身就叫 `registry`，生成的参数会命名为 `r4c$registry`。名为 `java` 或 `dev` 的参数会被重命名为 `r4c$p<index>`，因为这些名字会遮蔽生成的构造函数所使用的包。
- `@ConstructorProperties` 为每个参数列出一个名字，因此生成的副本以注册表的名字开头。
- 参数名与源代码中的相同，因此按参数名匹配 bean 的容器（使用 `-parameters` 编译的 Spring）在两个类上看到的名字相同。

覆写方法对方法也做同样的处理：每个覆写方法都按相同规则重复被覆写方法及其参数的注解，因此框架只在被调用方法上读取的注解（Spring 下 Jakarta 的 `@Transactional`、CDI 或 Micronaut 的拦截器绑定、`@Deprecated`）不会丢失。resilience4cata 自己的注解以及携带它们的组合注解不会重复，类的类型级注解也不会（`@Inherited` 的注解反正会到达子类），除非使用编译器选项 `-Ar4c.inject=micronaut`：它会重复这些注解，并把子类标记为容器的 bean；随后 Micronaut 集成会丢弃 `C` 自己的 bean 定义，使 Micronaut 创建 `C$Resilient` 来代替 `C`（[Micronaut 指南](micronaut.md#classes)）。它不会添加 `@Replaces(C.class)`，因为那样也会替换 `C` 的子类以及它的 `@MockBean`。

自行根据 `@Inject` 构造函数构建 bean 的容器（Micronaut 就是这样）也会看到镜像过来的 `@Inject`，因此可能同时创建 `C` 和 `C$Resilient`。Micronaut 集成会报告这种情况（[`R4C-MN-004`](../errors.md#r4c-mn-004)）；使用其他容器时，请确保只注册 `C$Resilient`。

## 接口还是类？ :id=interface-or-class

| | 注解接口 | 注解类 |
|---|---|---|
| 创建方式 | `Resilient.of(Api.class, impl, registry)` | `Resilient.create(Impl.class, registry, args...)` |
| 实现 | 任意：手写的、生成的、lambda | 必须是你拥有的非 final 类 |
| 实现内部的 `this.other()` | 不被拦截 | 被拦截 |
| 生效的注解 | 仅接口的 | 类的、其父类的及其接口的 |
| 开销 | 生成的代码，读取一次字段以访问委托对象 | 直接调用 `super` 的生成代码 |

> [!MEASURED]
> 在 AWS Graviton3（`c7g.xlarge`）上，第一次尝试即成功的重试在类风格下耗时 1.0 ns，接口风格下为 1.2 ns；重试一次时分别为 5.6 ns 和 6.5 ns。[基准测试](../benchmarks.md#annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3)

只有自身声明了弹性注解的类才会被处理：在类上、在它的某个方法上，或通过[组合注解](bundles.md)。只实现了注解接口的类不会得到子类；请用 `Resilient.of(Api.class, impl, registry)` 包装它，这会应用接口的策略，但不拦截自调用。

## 类、父类及接口上的注解 :id=annotations-on-the-class-its-superclasses-and-its-interfaces

类风格会逐个属性地合并适用于某个方法的所有注解。优先级从低到高：

1. 接口的类型级注解，
2. 接口方法的注解，
3. 声明该方法的父类的类型级注解，自上而下，
4. 类自身的类型级注解，
5. 类中该方法的注解（子类中的覆写方法优先于父类的方法）。

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

`Resilient.create(OrderService.class, registry)` 让 `status` 尝试 4 次、`quote` 尝试 3 次，两者都使用接口的 10 ms 延迟，`price` 则获得其降级方法。同一个对象如果用 `Resilient.of(OrderApi.class, ...)` 包装，只会看到 `OrderApi` 的注解：`status` 尝试 2 次。

类上的类型级注解适用于子类可以覆写的每个方法（包括继承来的方法），但以下情况除外：

- final 方法，会被跳过；
- `toString`、`equals`、`hashCode` 及其他 `Object` 方法，只采用直接写在方法上的注解。

实例 id 使用注解类：`com.acme.OrderService#status(java.lang.String)`。

## 限制 :id=restrictions

以下情况都是编译错误，错误参考中给出了错误码和修复方法：

| 情况 | 错误码 |
|---|---|
| 类是 `final` | [`R4C-SUB-001`](../errors.md#r4c-sub-001) |
| 类是 `sealed` | [`R4C-SUB-002`](../errors.md#r4c-sub-002) |
| 类是 `abstract` | [`R4C-SUB-003`](../errors.md#r4c-sub-003) |
| 类是 `private`，或嵌套在 private 类中 | [`R4C-SUB-004`](../errors.md#r4c-sub-004) |
| 类是内部类（非 static 的嵌套类） | [`R4C-SUB-005`](../errors.md#r4c-sub-005) |
| 所有构造函数都是 `private` | [`R4C-SUB-006`](../errors.md#r4c-sub-006) |
| `final` 方法在方法级别上带有注解（在类、父类或接口中） | [`R4C-SUB-007`](../errors.md#r4c-sub-007) |
| 带注解的方法在另一个包的父类中是包私有的 | [`R4C-SUB-008`](../errors.md#r4c-sub-008) |
| `static` 或 `private` 方法带有注解 | [`R4C-APT-002`](../errors.md#r4c-apt-002) |
| 类型是枚举或 record | [`R4C-APT-001`](../errors.md#r4c-apt-001) |

此外：

- **降级兄弟方法在 `this` 上调用**，因此它们不能是 `private`（也不能是另一个包的父类中的包私有方法）。`protected` 可以。
- **构造函数在 `this` 上调用的方法会在策略设置好之前运行**，因为子类的字段在 `super(...)` 返回后才被赋值。不要为构造函数调用的方法添加注解。
- **生成的子类不是 `final`**，因此框架可以代理它（`@Transactional` 的 CGLIB、作用域代理）。它的覆写方法调用策略；你自己的子类如果再次覆写这些方法，就会绕过策略。请继承你自己的类，而不是生成的类。
- `Resilient.create` 会拒绝接口（[`R4C-RT-007`](../errors.md#r4c-rt-007)）以及无法拥有生成子类的类型（[`R4C-RT-008`](../errors.md#r4c-rt-008)）。

## 与 Lombok 一起使用 :id=with-lombok

注解类可以使用 Lombok，二者在处理器路径上的顺序任意（[安装](../start/installation.md#with-other-annotation-processors)）：对于使用 Lombok 注解的类，处理器只在 Lombok 修改过它之后才读取它。

- 生成的子类会镜像 Lombok 写出的构造函数及其参数名：在有两个 final 字段的类上使用 `@RequiredArgsConstructor`，得到 `new StockService$Resilient(registry, warehouse, clock)`。
- 类型级策略保护子类可以覆写的每个方法，因此 `@Data`、`@Getter` 或 `@Setter` 写出的 getter、setter 和 `canEqual` 也受保护；它们的覆写方法保留 `@lombok.Generated`。与任何类一样，`equals`、`hashCode` 和 `toString` 只采用方法级注解。若只想保护你自己的方法，请为这些方法添加注解，而不是为类添加。
- `@Value` 使类成为 final：它不能拥有生成的子类（[R4C-SUB-001](../errors.md#r4c-sub-001)）。
- 参数上的 `@NonNull` 会在覆写方法上重复，Lombok 在那里加入空值检查：`null` 会在任何策略运行之前以 Lombok 的 `NullPointerException` 失败。

## 异步方法 :id=async-methods

注解类的方法可以返回 `CompletionStage<T>` 或 `CompletableFuture<T>`，规则与接口中的相同（[异步方法](async.md)）。
