# Retry e circuit breaker para uma chamada HTTP

Uma receita completa para o caso mais comum: uma dependência HTTP, retentada quando está com dificuldades, cortada quando está fora do ar, e nunca retentada quando a própria requisição está errada.

As peças vêm de [Retry](../policies/retry.md), [Circuit breaker](../policies/circuit-breaker.md) e [Timeout](../policies/timeout.md); esta página as junta e explica cada escolha. Cada snippet é compilado e executado contra um servidor HTTP real nos testes do projeto.

## A interface :id=interface

<!-- snippet: http-calls-interface -->
```java
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;

public interface InventoryClient {

    @Retry(maxAttempts = 3, delay = "200ms", maxDuration = "3s", abortOn = ClientError.class)
    @CircuitBreaker(name = "inventory", ignoreOn = ClientError.class)
    @Timeout("1s")
    int stock(String sku) throws IOException, InterruptedException;

    /** A 4xx answer: the request is wrong, so a retry cannot help, and the service is not failing. */
    final class ClientError extends IOException {
        private static final long serialVersionUID = 1L;

        public ClientError(int status) {
            super("HTTP " + status);
        }
    }

    /** A 5xx or 429 answer: the service is struggling. Worth a retry, and a failure for the breaker. */
    final class ServerError extends IOException {
        private static final long serialVersionUID = 1L;

        public ServerError(int status) {
            super("HTTP " + status);
        }
    }
}
```

- **Dois tipos de exceção para dois tipos de falha.** Um 5xx ou um 429 significa que o serviço está com dificuldades: vale outra tentativa, e é uma falha para o breaker. Um 4xx significa que a requisição está errada: outra tentativa recebe a mesma resposta, e o serviço está bem. Transformar códigos de status em exceções no cliente é o que permite que as anotações os diferenciem.
- **`@Retry`**: três tentativas, com uma espera aleatória abaixo de 200 ms e depois abaixo de 400 ms entre elas (backoff exponencial com full jitter, os padrões), e nunca mais de 3 s no total (`maxDuration`). `retryOn` mantém seu padrão, `Exception`, que cobre erros de conexão, `ServerError` e timeouts; `abortOn = ClientError.class` vence sobre ele.
- **`@CircuitBreaker`** com um `name`, de modo que todo método e todo wrapper que chama o mesmo serviço compartilham um breaker. `ignoreOn = ClientError.class`: um 4xx não conta nem como falha nem como sucesso, então quem envia requisições ruins não consegue abri-lo.
- **`@Timeout("1s")`** se aplica a cada tentativa, porque o timeout fica dentro do retry. Uma tentativa lenta é interrompida, contada como falha pelo breaker, e retentada.

## A implementação :id=implementation

<!-- snippet: http-calls-implementation -->
```java
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class HttpInventoryClient implements InventoryClient {
    private final HttpClient http;
    private final URI base;

    public HttpInventoryClient(HttpClient http, URI base) {
        this.http = http;
        this.base = base;
    }

    @Override
    public int stock(String sku) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/stock/" + sku)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 500 || status == 429) {
            throw new ServerError(status);
        }
        if (status >= 400) {
            throw new ClientError(status);
        }
        return Integer.parseInt(response.body().trim());
    }
}
```

O cliente apenas mapeia status para exceções; ele não sabe nada sobre retries. Qualquer cliente funciona da mesma forma: o `RestClient` do Spring, um cliente OpenAPI gerado, OkHttp.

## Conectando tudo :id=wiring

<!-- snippet: http-calls-wiring -->
```java
static InventoryClient connect(URI base, ResilienceRegistry registry) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    return Resilient.of(InventoryClient.class, new HttpInventoryClient(http, base), registry);
}
```

Construa o `ResilienceRegistry` uma vez por aplicação e compartilhe-o: ele guarda o estado do breaker. Com [Spring Boot](spring.md), [Micronaut](micronaut.md) ou [Quarkus](quarkus.md) a integração faz isso por você. Dê ao cliente HTTP seu próprio connect timeout; `@Timeout` limita a tentativa inteira, incluindo o tempo para ler a resposta.

## O que acontece :id=what-happens

| O serviço responde | O que quem chama vê |
|---|---|
| 503, 503, depois 200 | o valor, após dois retries |
| 404 | `ClientError` imediatamente: uma requisição, nada registrado pelo breaker |
| 500 até o breaker abrir | `CallNotPermittedException` quando o breaker estiver aberto; o retry para em vez de chamar o serviço de novo |
| nada durante um segundo | a tentativa é interrompida e retentada; quando as tentativas ou o orçamento de 3 s acabam, `CallTimeoutException` |

Adicione `@Fallback` se quem chama deve receber um valor padrão em vez de uma exceção ([Fallback](../policies/fallback.md)).

## Retente apenas o que é seguro repetir :id=idempotency

Um retry envia a mesma requisição de novo. Isso é seguro para `GET`, `PUT` e `DELETE`, que dão o mesmo resultado quando repetidos, e não para um `POST` que cria um pedido ou cobra um cartão: um timeout não diz se a primeira tentativa foi concluída. Para esses, não adicione `@Retry`, ou envie uma chave de idempotência que o serviço use para descartar duplicatas. O breaker e o timeout são seguros em qualquer requisição.

## Escolhendo os números :id=numbers

- **Tentativas e atrasos**: duas ou três tentativas costumam bastar; mais só adicionam carga a um serviço que já está com dificuldades. Mantenha `maxDuration` abaixo do próprio timeout de quem chama.
- **Timeout**: um pouco acima da resposta normal mais lenta do serviço (seu percentil 99), não da média.
- **Breaker**: os padrões avaliam uma janela de 100 chamadas e abrem com 50% de falhas. Para um serviço que você chama algumas vezes por minuto, reduza `windowSize` e `minimumCalls`, ou use uma janela de tempo, ou o breaker nunca vai abrir.
- Mude-os por ambiente sem tocar no código: `instances.inventory.circuitBreaker.minimumCalls=20` em um arquivo de properties, ou a variável de ambiente correspondente ([Configuração](configuration.md)).
