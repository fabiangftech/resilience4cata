# 为 HTTP 调用添加重试和熔断器

针对最常见情况的完整方案：一个 HTTP 依赖，在它吃力时重试，在它宕机时切断，而在请求本身有错时绝不重试。

各部分来自[重试](../policies/retry.md)、[熔断器](../policies/circuit-breaker.md)和[超时](../policies/timeout.md)；本页把它们组合在一起，并解释每个选择。每个代码片段都在项目的测试中针对真实的 HTTP 服务器编译并运行。

## 接口 :id=interface

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

- **两种异常类型对应两类失败。** 5xx 或 429 表示服务正在吃力：值得再尝试一次，对熔断器来说是失败。4xx 表示请求有错：再次尝试得到的是同样的回答，而服务本身没有问题。在客户端中把状态码转换为异常，正是注解能够区分它们的原因。
- **`@Retry`**：三次尝试，两次尝试之间分别随机等待少于 200 ms 和少于 400 ms（带完全抖动的指数退避，即默认值），总时长绝不超过 3 s（`maxDuration`）。`retryOn` 保持默认值 `Exception`，它覆盖连接错误、`ServerError` 和超时；`abortOn = ClientError.class` 优先于它。
- **`@CircuitBreaker`** 带有 `name`，因此调用同一服务的每个方法和每个包装器共享一个熔断器。`ignoreOn = ClientError.class`：4xx 既不算失败也不算成功，因此发送错误请求的调用方无法使熔断器打开。
- **`@Timeout("1s")`** 作用于每一次尝试，因为超时位于重试之内。缓慢的尝试会被中断，被熔断器计为失败，并被重试。

## 实现 :id=implementation

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

客户端只负责把状态映射为异常；它对重试一无所知。任何客户端都以相同方式工作：Spring 的 `RestClient`、生成的 OpenAPI 客户端、OkHttp。

## 装配 :id=wiring

<!-- snippet: http-calls-wiring -->
```java
static InventoryClient connect(URI base, ResilienceRegistry registry) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    return Resilient.of(InventoryClient.class, new HttpInventoryClient(http, base), registry);
}
```

每个应用只构建一次 `ResilienceRegistry` 并共享它：它保存熔断器的状态。使用 [Spring Boot](spring.md)、[Micronaut](micronaut.md) 或 [Quarkus](quarkus.md) 时，集成会替你完成这件事。为 HTTP 客户端设置它自己的连接超时；`@Timeout` 限制的是整次尝试，包括读取回答的时间。

## 会发生什么 :id=what-happens

| 服务的回答 | 调用方看到的结果 |
|---|---|
| 503、503，然后 200 | 两次重试后得到值 |
| 404 | 立即得到 `ClientError`：只有一个请求，熔断器不记录任何内容 |
| 一直 500，直到熔断器打开 | 熔断器打开后得到 `CallNotPermittedException`；重试停止，而不是再次调用服务 |
| 一秒内没有回答 | 尝试被中断并重试；当尝试次数或 3 s 预算用尽时，得到 `CallTimeoutException` |

如果调用方应该得到默认值而不是异常，请添加 `@Fallback`（[降级](../policies/fallback.md)）。

## 只重试可以安全重复的操作 :id=idempotency

重试会再次发送同一个请求。对于 `GET`、`PUT` 和 `DELETE` 这是安全的，它们重复执行会得到相同的结果；但对于创建订单或扣款的 `POST` 则不安全：超时并不能告诉你第一次尝试是否已经成功。对于这类请求，要么不添加 `@Retry`，要么发送一个幂等键，供服务丢弃重复请求。熔断器和超时对任何请求都是安全的。

## 选择数值 :id=numbers

- **尝试次数和延迟**：通常两到三次尝试就足够了；更多的尝试只会给已经吃力的服务增加负载。让 `maxDuration` 小于调用方自己的超时。
- **超时**：略高于服务正常情况下最慢的回答（其第 99 百分位），而不是其平均值。
- **熔断器**：默认值根据 100 次调用的窗口进行判断，在失败率达到 50% 时打开。对于每分钟只调用几次的服务，请降低 `windowSize` 和 `minimumCalls`，或使用时间窗口，否则熔断器永远不会打开。
- 无需修改代码即可按环境更改它们：在 properties 文件中设置 `instances.inventory.circuitBreaker.minimumCalls=20`，或设置对应的环境变量（[配置](configuration.md)）。
