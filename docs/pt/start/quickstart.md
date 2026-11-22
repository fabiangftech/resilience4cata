# Início rápido

Em uns cinco minutos, proteja uma chamada instável com um retry, um circuit breaker, um timeout e um fallback, e veja-os funcionar.

Você precisa de Java 21 ou mais recente e de um projeto Gradle ou Maven. Tudo abaixo é um único programa executável; seu código é compilado e executado pelos testes do projeto, então funciona como mostrado.

## 1. Adicione as dependências :id=_1-add-the-dependencies

`resilience4cata-all` traz a biblioteca (core, as anotações e a configuração externa); o processador gera o código em tempo de compilação e vai apenas no annotation processor path.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:1.0.0-RC4'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:1.0.0-RC4'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:1.0.0-RC4")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:1.0.0-RC4")
}
```

#### **Maven**

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

A [página de instalação](installation.md) explica cada linha, o BOM e os módulos individuais, IDEs e JPMS.

## 2. Anote uma interface :id=_2-annotate-an-interface

Anote a interface da dependência que você chama. Esta tem até três tentativas, é protegida por um circuit breaker chamado `prices`, é interrompida depois de dois segundos e respondida por `unknownPrice` quando todo o resto falha:

<!-- snippet: quickstart-interface -->
```java
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.math.BigDecimal;

public interface PriceClient {

    @Retry(maxAttempts = 3, delay = "100ms")
    @CircuitBreaker(name = "prices")
    @Timeout("2s")
    @Fallback(method = "unknownPrice")
    BigDecimal price(String sku) throws IOException;

    default BigDecimal unknownPrice(String sku, Exception failure) {
        return BigDecimal.ZERO;
    }
}
```

## 3. Implemente-a :id=_3-implement-it

Qualquer implementação serve: um cliente HTTP escrito à mão, um gerado, uma lambda. Este substituto falha na primeira chamada:

<!-- snippet: quickstart-implementation -->
```java
import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for a real HTTP client: the first call fails, the next ones answer. */
public class FlakyPriceClient implements PriceClient {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public BigDecimal price(String sku) throws IOException {
        if (calls.incrementAndGet() == 1) {
            throw new IOException("connection reset");
        }
        return new BigDecimal("9.99");
    }
}
```

## 4. Envolva-a e chame-a :id=_4-wrap-it-and-call-it

`Resilient.of` coloca as políticas em volta da sua implementação. Construa o `ResilienceRegistry` uma vez e compartilhe-o: ele guarda as configurações e o estado de cada breaker.

<!-- snippet: quickstart-main -->
```java
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();

        PriceClient prices = Resilient.of(PriceClient.class, new FlakyPriceClient(), registry);
        System.out.println(prices.price("sku-1"));   // 9.99: the first attempt failed, the retry succeeded

        PriceClient down = Resilient.of(PriceClient.class, sku -> {
            throw new IOException("service down");
        }, registry);
        System.out.println(down.price("sku-1"));     // 0: all three attempts failed, the fallback answered

        System.out.println(registry.circuitBreaker("prices").state());   // CLOSED: too few calls to judge yet
    }
}
```

## 5. Execute :id=_5-run-it

Compile e execute `Main` (pela IDE, ou com a task de execução do seu build). Ele imprime:

```text
9.99
0
CLOSED
```

A segunda chamada leva até algumas centenas de milissegundos: entre as tentativas o retry espera um tempo aleatório abaixo de 100 ms, depois abaixo de 200 ms (backoff exponencial com full jitter).

## O que aconteceu :id=what-just-happened

- **Em tempo de compilação**, o processador de anotações leu `PriceClient` e escreveu `PriceClient$Resilient.java` ao lado dela (em `build/generated/sources/annotationProcessor/java/main/` com Gradle, `target/generated-sources/annotations/` com Maven). É Java simples: abra e leia. Um erro de digitação em um atributo, uma duração inválida ou um fallback com a assinatura errada teria sido um erro de compilação.
- **`Resilient.of`** carregou essa classe uma vez e criou as políticas a partir do registry. As chamadas passam direto pelo código gerado, sem proxies e sem reflection.
- **A primeira chamada** falhou com `IOException`, então o retry esperou e tentou de novo; a segunda tentativa devolveu `9.99`.
- **O segundo wrapper** sempre falha, então depois de três tentativas o fallback `unknownPrice` respondeu `0`.
- **O breaker** registrou cada tentativa, cinco chamadas com quatro falhas, mas ficou `CLOSED`: por padrão ele só julga quando sua janela tem 100 chamadas (`minimumCalls`).
- As políticas se aninham nesta ordem por padrão, da mais externa para dentro ([`@PolicyOrder`](../policies/README.md#changing-the-order) a muda): `Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method`. É por isso que o fallback vê o resultado final e cada tentativa do retry tem seu próprio timeout de dois segundos.

## 6. Veja o breaker abrir :id=_6-see-the-breaker-open

Com os padrões, o breaker espera 100 chamadas antes de julgar. Reduza isso só para a instância `prices`, sem tocar na anotação, e execute de novo o serviço que falha:

<!-- snippet: quickstart-breaker-opens -->
```java
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class BreakerOpens {

    public static void main(String[] args) throws IOException {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .circuitBreakerInstance("prices", b -> b.windowSize(4).minimumCalls(4))   // judge after 4 calls
                .build();

        AtomicInteger calls = new AtomicInteger();
        PriceClient down = Resilient.of(PriceClient.class, sku -> {
            calls.incrementAndGet();
            throw new IOException("service down");
        }, registry);

        down.price("sku-1");   // three attempts, three failures
        down.price("sku-2");   // the fourth failure opens the breaker, which stops the retry
        System.out.println(registry.circuitBreaker("prices").state());   // OPEN

        System.out.println(down.price("sku-3"));   // 0: rejected at once, answered by the fallback
        System.out.println(calls.get());           // 4: the service was not called again
    }
}
```

Ele imprime:

```text
OPEN
0
4
```

- A primeira chamada falhou três vezes. Na segunda chamada, a quarta falha encheu a janela de quatro com uma taxa de falhas de 100%, acima dos 50% padrão, então o breaker abriu. O retry esperou seu backoff, a tentativa seguinte foi rejeitada e, como o retry nunca repete uma rejeição, ele desistiu e o fallback respondeu.
- A terceira chamada foi rejeitada na hora com uma `CallNotPermittedException`, sem chegar ao serviço, e o fallback respondeu `0` de novo. Depois de `waitInOpen` (60 s por padrão) o breaker deixa passar algumas chamadas de teste.
- `circuitBreakerInstance` é uma forma de mudar uma instância sem editar sua anotação; um arquivo de propriedades ou variáveis de ambiente fazem o mesmo ([Configuração](../guides/configuration.md)).

## Próximos passos :id=next-steps

- [Retry e circuit breaker para uma chamada HTTP](../guides/http-calls.md): as mesmas políticas ajustadas para uma dependência HTTP real.
- [Como funciona](how-it-works.md): o que o processador gera, ids de instância, de onde vem a configuração.
- [Políticas](../policies/README.md): todos os atributos de todas as políticas, com seus padrões.
- [Configuração](../guides/configuration.md): reajuste o mesmo código a partir de um arquivo de propriedades, variáveis de ambiente ou propriedades de sistema.
- [Testes](../guides/testing.md): execute este exemplo em um `ManualClock`, sem espera real.
- [Métodos async](../guides/async.md): devolva um `CompletableFuture` e nada bloqueia.
