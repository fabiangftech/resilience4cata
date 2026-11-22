# Bundles

Um bundle é uma anotação sua que carrega anotações de resiliência, de modo que uma equipe escreve `@RemoteCall` uma vez em vez de repetir quatro anotações em cada método.

## Defina um bundle :id=define-a-bundle

Marque a anotação com `@ResiliencePolicy`, dê a ela retenção `CLASS` e os targets `METHOD` e `TYPE`, e coloque as anotações de política nela:

<!-- snippet: bundles-annotation -->
```java
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@Retry(config = "external")
@CircuitBreaker(config = "external")
@Timeout("2s")
public @interface RemoteCall {
}
```

Sem `@ResiliencePolicy`, o processador ignoraria as políticas da anotação, então ele reporta [`R4C-CMP-001`](../errors.md#r4c-cmp-001) em vez disso.

## Use-o :id=use-it

Em um tipo, um bundle (como qualquer anotação de resiliência) se aplica a todos os métodos; em um método, apenas a esse método. As anotações escritas diretamente no elemento vencem o bundle, atributo por atributo:

<!-- snippet: bundles-usage -->
```java
@RemoteCall
public interface ShippingClient {

    String track(String parcel) throws IOException;

    @Retry(maxAttempts = 1)   // overrides one attribute of the bundle: never retried
    String cancel(String parcel) throws IOException;
}
```

`track` recebe o bundle inteiro. `cancel` também recebe o bundle, exceto `maxAttempts`, que seu próprio `@Retry` define como 1; seu `config = "external"` continua vindo do bundle.

## Bundles e configs nomeadas :id=bundles-and-named-configs

Um bundle que define `config = "…"` em vez de valores literais mantém os números fora do código: o registry, ou a [configuração externa](configuration.md), define o que `external` significa, e os operadores podem mudá-lo sem recompilar.

<!-- snippet: bundles-registry -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryDefaults(r -> r.delay(Duration.ofMillis(100)))
        .retry("external", r -> r.maxAttempts(5))
        .circuitBreaker("external", cb -> cb.failureRateThreshold(25).waitInOpen(Duration.ofSeconds(30)))
        .build();
```

Com esse registry, `track` faz até 5 tentativas com 100 ms de intervalo (antes do jitter) e seu breaker abre com uma taxa de falhas de 25%. Um nome de config que o registry não conhece falha quando a interface é envolvida ([`R4C-REG-004`](../errors.md#r4c-reg-004)), não na primeira chamada.

## Bundles de bundles :id=bundles-of-bundles

Um bundle pode carregar outros bundles. Suas próprias anotações de política vencem as que ele herda, atributo por atributo:

<!-- snippet: bundles-nested -->
```java
@ResiliencePolicy
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@RemoteCall
@Retry(maxAttempts = 5)   // beats RemoteCall's retry attributes, keeps its config = "external"
public @interface Idempotent {
}
```

Um método anotado com `@Idempotent` recebe o breaker de `RemoteCall` e o timeout de dois segundos, e um retry com `config = "external"` e 5 tentativas.

## Precedência :id=precedence

Para cada atributo, da menor precedência para a maior:

1. Os padrões embutidos e do registry, depois a config nomeada escolhida com `config = "…"` ([de onde vem a configuração](../start/how-it-works.md#where-configuration-comes-from)).
2. As anotações de nível de tipo: as da interface que declara o método (quando ela é uma superinterface), depois as da interface que você envolve.
3. As anotações de nível de método.

Em um mesmo elemento, uma anotação direta vence todos os bundles, e um bundle vence os bundles que carrega. Um bundle de nível de método vence uma anotação de nível de tipo, mesmo uma direta, porque o método é o elemento mais específico. Para [classes anotadas](annotated-classes.md#annotations-on-the-class-its-superclasses-and-its-interfaces), a classe e suas superclasses adicionam seus níveis acima dos da interface.

As sobrescritas por instância (`instances.<id>.*` e `retryInstance(...)`) se aplicam depois de tudo isso.

## Dois bundles que discordam :id=two-bundles-that-disagree

Se dois bundles no mesmo elemento definem o mesmo atributo com valores diferentes, nenhum pode vencer, então o build falha com [`R4C-CMP-002`](../errors.md#r4c-cmp-002). Não existe um "o último vence" silencioso:

<!-- not-compiled: um erro de compilação proposital (R4C-CMP-002) -->
```java
public interface PriceClient {
    @Fast       // @Retry(maxAttempts = 2)
    @Patient    // @Retry(maxAttempts = 5)
    String price(String sku);
}
```

Mantenha um deles, faça-os concordar, ou escreva o atributo diretamente no método, o que vence os dois.
