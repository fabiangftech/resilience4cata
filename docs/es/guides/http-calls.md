# Retry y circuit breaker para una llamada HTTP

Una receta completa para el caso más común: una dependencia HTTP que se reintenta cuando tiene problemas, se corta cuando está caída y nunca se reintenta cuando la propia petición está mal.

Las piezas vienen de [Retry](../policies/retry.md), [Circuit breaker](../policies/circuit-breaker.md) y [Timeout](../policies/timeout.md); esta página las junta y explica cada decisión. Cada snippet se compila y se ejecuta contra un servidor HTTP real en los tests del proyecto.

## La interfaz :id=interface

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

- **Dos tipos de excepción para dos tipos de falla.** Un 5xx o un 429 significa que el servicio tiene problemas: vale la pena otro intento, y es una falla para el breaker. Un 4xx significa que la petición está mal: otro intento recibe la misma respuesta, y el servicio está bien. Convertir los códigos de estado en excepciones en el cliente es lo que permite a las anotaciones distinguirlos.
- **`@Retry`**: tres intentos, con una espera aleatoria de menos de 200 ms y luego de menos de 400 ms entre ellos (backoff exponencial con full jitter, los valores por defecto), y nunca más de 3 s en total (`maxDuration`). `retryOn` conserva su valor por defecto, `Exception`, que cubre los errores de conexión, `ServerError` y los timeouts; `abortOn = ClientError.class` gana sobre él.
- **`@CircuitBreaker`** con un `name`, para que todos los métodos y todos los wrappers que llaman al mismo servicio compartan un breaker. `ignoreOn = ClientError.class`: un 4xx no cuenta ni como falla ni como éxito, así que quien manda peticiones malas no puede abrirlo.
- **`@Timeout("1s")`** se aplica a cada intento, porque el timeout está dentro del retry. Un intento lento se interrumpe, el breaker lo cuenta como falla y se reintenta.

## La implementación :id=implementation

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

El cliente solo traduce estados a excepciones; no sabe nada de reintentos. Cualquier cliente funciona igual: el `RestClient` de Spring, un cliente OpenAPI generado, OkHttp.

## Conectarlo :id=wiring

<!-- snippet: http-calls-wiring -->
```java
static InventoryClient connect(URI base, ResilienceRegistry registry) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    return Resilient.of(InventoryClient.class, new HttpInventoryClient(http, base), registry);
}
```

Crea el `ResilienceRegistry` una vez por aplicación y compártelo: guarda el estado del breaker. Con [Spring Boot](spring.md), [Micronaut](micronaut.md) o [Quarkus](quarkus.md) la integración lo hace por ti. Dale al cliente HTTP su propio timeout de conexión; `@Timeout` acota el intento entero, incluido el tiempo de leer la respuesta.

## Qué pasa :id=what-happens

| El servicio responde | Lo que ve quien llama |
|---|---|
| 503, 503 y luego 200 | el valor, tras dos reintentos |
| 404 | `ClientError` al instante: una petición, y el breaker no registra nada |
| 500 hasta que el breaker se abre | `CallNotPermittedException` cuando el breaker está abierto; el retry se detiene en vez de volver a llamar al servicio |
| nada durante un segundo | el intento se interrumpe y se reintenta; cuando se acaban los intentos o el presupuesto de 3 s, `CallTimeoutException` |

Añade `@Fallback` si quien llama debe recibir un valor por defecto en vez de una excepción ([Fallback](../policies/fallback.md)).

## Reintenta solo lo que es seguro repetir :id=idempotency

Un retry vuelve a mandar la misma petición. Eso es seguro con `GET`, `PUT` y `DELETE`, que dan el mismo resultado al repetirse, y no con un `POST` que crea un pedido o cobra una tarjeta: un timeout no te dice si el primer intento llegó. Para esos, o no pongas `@Retry`, o manda una clave de idempotencia que el servicio use para descartar duplicados. El breaker y el timeout son seguros en cualquier petición.

## Elegir los números :id=numbers

- **Intentos y esperas**: dos o tres intentos suelen bastar; más solo añaden carga a un servicio que ya tiene problemas. Deja `maxDuration` por debajo del timeout de quien llama.
- **Timeout**: un poco por encima de la respuesta más lenta normal del servicio (su percentil 99), no de su media.
- **Breaker**: los valores por defecto juzgan una ventana de 100 llamadas y abren con un 50 % de fallas. Para un servicio al que llamas unas pocas veces por minuto, baja `windowSize` y `minimumCalls`, o usa una ventana por tiempo, o el breaker nunca se abrirá.
- Cámbialos por entorno sin tocar el código: `instances.inventory.circuitBreaker.minimumCalls=20` en un archivo properties, o la variable de entorno equivalente ([Configuración](configuration.md)).
