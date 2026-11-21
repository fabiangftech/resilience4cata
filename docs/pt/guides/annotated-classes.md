# Classes anotadas

Anote uma classe não final em vez de uma interface, e o processador gera uma subclasse cujas sobrescritas aplicam as políticas, de modo que as chamadas que o objeto faz a si mesmo também ficam protegidas.

## Anote a classe :id=annotate-the-class

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

O processador escreve `StockService$Resilient extends StockService`. Ele sobrescreve apenas os métodos que têm políticas (aqui `stock`), cada um terminando com `super.stock(...)`. `total` não é sobrescrito, mas sua chamada a `stock` passa pela sobrescrita, porque é para ela que `this.stock(...)` despacha.

## Crie-a :id=create-it

`Resilient.create` recebe a classe, o registry e os argumentos de um dos construtores da classe:

<!-- snippet: annotated-classes-create -->
```java
StockService stock = Resilient.create(StockService.class, registry, warehouse);
int total = stock.total(List.of("a", "b"));   // each stock(sku) call is retried
```

Para cada construtor não privado da classe, a subclasse gerada tem um com um `ResilienceRegistry` primeiro e os mesmos parâmetros depois dele. Chamá-lo diretamente faz o mesmo que `Resilient.create`, verificado pelo compilador:

<!-- snippet: annotated-classes-constructor -->
```java
StockService stock = new StockService$Resilient(registry, warehouse);   // checked by the compiler
```

`Resilient.create` escolhe o construtor como o compilador faria, correspondências exatas antes de varargs. Sem nenhum construtor adequado ele falha com [`R4C-RT-009`](../errors.md#r4c-rt-009), com mais de um (por exemplo `null` passado a construtores sobrecarregados) com [`R4C-RT-010`](../errors.md#r4c-rt-010); as duas mensagens sugerem a chamada `new …$Resilient(registry, …)` a usar em seu lugar.

## Construtores e injeção de dependências :id=constructor-injection

Cada construtor espelhado mantém os nomes de parâmetros do original e copia suas anotações e as anotações de cada parâmetro, de modo que um contêiner de injeção de dependências que constrói `StockService$Resilient` vê os mesmos metadados que em `StockService`: `@Autowired` ou `@Inject` em um de vários construtores, `@Qualifier("…")`, `@Named("…")` ou `@Value("${…}")` em um parâmetro.

<!-- not-compiled: precisa do Spring no class path -->
```java
public class ReportService {
    @Autowired
    public ReportService(@Qualifier("reportsDb") DataSource dataSource, @Value("${reports.limit}") int limit) { … }
    …
}
```

<!-- not-compiled: código gerado -->
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

- Apenas as anotações mantidas nos arquivos de classe (retenção `CLASS` ou `RUNTIME`) são copiadas; as anotações `SOURCE`, como as do Lombok, não. Anotações de uso de tipo (`@Nullable` do JSpecify) também não são repetidas.
- O parâmetro `ResilienceRegistry` não tem anotação: os contêineres o injetam por tipo, então a aplicação precisa de exatamente um bean `ResilienceRegistry`.
- Se um parâmetro do construtor da classe já se chama `registry`, o gerado se chama `r4c$registry`. Parâmetros chamados `java` ou `dev` são renomeados para `r4c$p<index>`, porque esses nomes esconderiam os pacotes que o construtor gerado usa.
- `@ConstructorProperties` lista um nome por parâmetro, então a cópia gerada começa com o do registry.
- Os nomes de parâmetros são os do código-fonte, então um contêiner que associa beans pelo nome do parâmetro (Spring, compilado com `-parameters`) vê os mesmos nomes nas duas classes.

As sobrescritas fazem o mesmo para os métodos: cada uma repete as anotações do método que sobrescreve e de seus parâmetros, com as mesmas regras, de modo que anotações que os frameworks leem apenas no método invocado (o `@Transactional` do Jakarta no Spring, os interceptor bindings do CDI ou do Micronaut, `@Deprecated`) não se perdem. As próprias anotações do resilience4cata e os bundles que as carregam não são repetidos, nem as anotações de nível de tipo da classe (as `@Inherited` chegam à subclasse de qualquer forma), exceto com a opção de compilador `-Ar4c.inject=micronaut`, que as repete e marca a subclasse como o bean do contêiner; a integração com o Micronaut então descarta as definições de bean do próprio `C`, para que o Micronaut crie `C$Resilient` no lugar de `C` ([guia do Micronaut](micronaut.md#classes)). Ela não adiciona `@Replaces(C.class)`, que também substituiria subclasses de `C` e `@MockBean`s dele.

Um contêiner que constrói beans a partir de construtores `@Inject` por conta própria (o Micronaut faz isso) também vê o `@Inject` espelhado, então pode criar tanto `C` quanto `C$Resilient`. A integração com o Micronaut reporta isso ([`R4C-MN-004`](../errors.md#r4c-mn-004)); com outros contêineres, garanta que apenas `C$Resilient` esteja registrado.

## Interface ou classe? :id=interface-or-class

| | Interface anotada | Classe anotada |
|---|---|---|
| Criada com | `Resilient.of(Api.class, impl, registry)` | `Resilient.create(Impl.class, registry, args...)` |
| A implementação | Qualquer uma: escrita à mão, gerada, uma lambda | Deve ser uma classe não final sua |
| `this.other()` dentro da implementação | Não interceptado | Interceptado |
| Anotações que contam | Apenas as da interface | As da classe, de suas superclasses e de suas interfaces |
| Custo | Código gerado, uma leitura de campo para chegar ao delegate | Código gerado que chama `super` diretamente |

> [!MEASURED]
> No AWS Graviton3 (`c7g.xlarge`), um retry cuja primeira tentativa tem sucesso custa 1.0 ns no estilo de classe contra 1.2 ns no estilo de interface; com um retry, 5.6 ns contra 6.5 ns. [Benchmarks](../benchmarks.md#annotated-classes-generics-config-overrides-ticker-bulkhead-2026-10-24-aws-graviton3)

Uma classe só é processada se ela mesma declara uma anotação de resiliência: na classe, em um de seus métodos ou por meio de um [bundle](bundles.md). Uma classe que apenas implementa uma interface anotada não recebe subclasse; envolva-a com `Resilient.of(Api.class, impl, registry)`, que aplica as políticas da interface e não intercepta a autoinvocação.

## Anotações na classe, em suas superclasses e em suas interfaces :id=annotations-on-the-class-its-superclasses-and-its-interfaces

O estilo de classe mescla todas as anotações que se aplicam a um método, atributo por atributo. Da menor precedência para a maior:

1. as anotações de nível de tipo da interface,
2. as anotações do método da interface,
3. as anotações de nível de tipo das superclasses declarantes, de cima para baixo,
4. as anotações de nível de tipo da própria classe,
5. as anotações do método na classe (uma sobrescrita em uma subclasse vence o método da superclasse).

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

`Resilient.create(OrderService.class, registry)` dá a `status` 4 tentativas, a `quote` 3, ambos com o atraso de 10 ms da interface, e a `price` seu fallback. O mesmo objeto envolvido com `Resilient.of(OrderApi.class, ...)` veria apenas as anotações de `OrderApi`: 2 tentativas para `status`.

Uma anotação de nível de tipo na classe se aplica a todo método que a subclasse pode sobrescrever, incluindo os herdados, exceto:

- métodos final, que são ignorados;
- `toString`, `equals`, `hashCode` e os outros métodos de `Object`, que só recebem anotações escritas no próprio método.

Os ids de instância usam a classe anotada: `com.acme.OrderService#status(java.lang.String)`.

## Restrições :id=restrictions

Todos estes são erros de compilação, com um código e uma correção na referência de erros:

| O quê | Código |
|---|---|
| A classe é `final` | [`R4C-SUB-001`](../errors.md#r4c-sub-001) |
| A classe é `sealed` | [`R4C-SUB-002`](../errors.md#r4c-sub-002) |
| A classe é `abstract` | [`R4C-SUB-003`](../errors.md#r4c-sub-003) |
| A classe é `private`, ou aninhada em uma classe privada | [`R4C-SUB-004`](../errors.md#r4c-sub-004) |
| A classe é uma classe interna (aninhada não estática) | [`R4C-SUB-005`](../errors.md#r4c-sub-005) |
| Todos os construtores são `private` | [`R4C-SUB-006`](../errors.md#r4c-sub-006) |
| Um método `final` é anotado em nível de método (na classe, em uma superclasse ou em uma interface) | [`R4C-SUB-007`](../errors.md#r4c-sub-007) |
| Um método anotado é package-private em uma superclasse de outro pacote | [`R4C-SUB-008`](../errors.md#r4c-sub-008) |
| Um método `static` ou `private` é anotado | [`R4C-APT-002`](../errors.md#r4c-apt-002) |
| O tipo é um enum ou um record | [`R4C-APT-001`](../errors.md#r4c-apt-001) |

Além disso:

- **Os métodos irmãos de fallback são chamados em `this`**, então não podem ser `private` (nem package-private em uma superclasse de outro pacote). `protected` funciona.
- **Os métodos que o construtor chama em `this` rodam antes de as políticas serem configuradas**, porque os campos da subclasse são atribuídos depois que `super(...)` retorna. Não anote métodos que os construtores chamam.
- **A subclasse gerada não é `final`**, então os frameworks podem criar proxies dela (CGLIB para `@Transactional`, scoped proxies). Suas sobrescritas chamam as políticas; uma subclasse sua que as sobrescreva de novo as contorna. Estenda sua própria classe, não a gerada.
- `Resilient.create` rejeita uma interface ([`R4C-RT-007`](../errors.md#r4c-rt-007)) e um tipo que não pode ter uma subclasse gerada ([`R4C-RT-008`](../errors.md#r4c-rt-008)).

## Com Lombok :id=with-lombok

Uma classe anotada pode usar Lombok, em qualquer ordem no processor path ([instalação](../start/installation.md#with-other-annotation-processors)): o processador lê uma classe que usa anotações do Lombok somente depois que o Lombok a alterou.

- A subclasse gerada espelha os construtores que o Lombok escreve, com seus nomes de parâmetros: `@RequiredArgsConstructor` em uma classe com dois campos final dá `new StockService$Resilient(registry, warehouse, clock)`.
- As políticas de nível de tipo protegem todo método que a subclasse pode sobrescrever, portanto também os getters, setters e `canEqual` que `@Data`, `@Getter` ou `@Setter` escrevem; suas sobrescritas mantêm `@lombok.Generated`. Como em qualquer classe, `equals`, `hashCode` e `toString` só recebem anotações de nível de método. Para proteger apenas seus próprios métodos, anote-os em vez da classe.
- `@Value` torna a classe final: ela não pode ter uma subclasse gerada ([R4C-SUB-001](../errors.md#r4c-sub-001)).
- O `@NonNull` de um parâmetro é repetido na sobrescrita, onde o Lombok adiciona sua verificação de null: um `null` falha com o `NullPointerException` do Lombok antes de qualquer política rodar.

## Métodos assíncronos :id=async-methods

Os métodos de uma classe anotada podem retornar `CompletionStage<T>` ou `CompletableFuture<T>`, com as mesmas regras que nas interfaces ([métodos assíncronos](async.md)).
