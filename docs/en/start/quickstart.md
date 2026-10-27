# Quickstart

In about five minutes, protect a flaky call with a retry, a circuit breaker, a timeout and a fallback, and watch them work.

You need Java 21 or newer and a Gradle or Maven project. Everything below is one runnable program; its code is compiled and run by the project's tests, so it works as shown.

## 1. Add the dependencies

`resilience4cata-all` brings the library (core, the annotations and external configuration); the processor generates the code at compile time and goes only on the annotation processor path.

<!-- tabs:start -->

#### **Gradle**

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-all:0.8.0'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:0.8.0'
}
```

#### **Gradle (Kotlin)**

```kotlin
dependencies {
    implementation("dev.resilience4cata:resilience4cata-all:0.8.0")
    annotationProcessor("dev.resilience4cata:resilience4cata-processor:0.8.0")
}
```

#### **Maven**

```xml
<dependency>
  <groupId>dev.resilience4cata</groupId>
  <artifactId>resilience4cata-all</artifactId>
  <version>0.8.0</version>
</dependency>

<!-- in the maven-compiler-plugin <configuration> -->
<annotationProcessorPaths>
  <path>
    <groupId>dev.resilience4cata</groupId>
    <artifactId>resilience4cata-processor</artifactId>
    <version>0.8.0</version>
  </path>
</annotationProcessorPaths>
```

<!-- tabs:end -->

The [installation page](installation.md) explains each line, the BOM and the individual modules, IDEs and JPMS.

## 2. Annotate an interface

Annotate the interface of the dependency you call. This one is retried up to three times, guarded by a circuit breaker named `prices`, interrupted after two seconds, and answered by `unknownPrice` when everything else fails:

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

## 3. Implement it

Any implementation works: a hand-written HTTP client, a generated one, a lambda. This stand-in fails on its first call:

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

## 4. Wrap it and call it

`Resilient.of` puts the policies around your implementation. Build the `ResilienceRegistry` once and share it: it holds the configurations and the state of every breaker.

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

## 5. Run it

Compile and run `Main` (from the IDE, or with your build's run task). It prints:

```text
9.99
0
CLOSED
```

The second call takes up to a few hundred milliseconds: between attempts the retry waits a random time below 100 ms, then below 200 ms (exponential backoff with full jitter).

## What just happened

- **At compile time**, the annotation processor read `PriceClient` and wrote `PriceClient$Resilient.java` next to it (under `build/generated/sources/annotationProcessor/java/main/` with Gradle, `target/generated-sources/annotations/` with Maven). It is plain Java: open it and read it. A typo in an attribute, a bad duration or a fallback with the wrong signature would have been a compile error.
- **`Resilient.of`** loaded that class once and created the policies from the registry. Calls go straight through generated code, with no proxies and no reflection.
- **The first call** failed with `IOException`, so the retry waited and tried again; the second attempt returned `9.99`.
- **The second wrapper** always fails, so after three attempts the fallback `unknownPrice` answered `0`.
- **The breaker** recorded every attempt, five calls with four failures, but stayed `CLOSED`: by default it judges only once its window holds 100 calls (`minimumCalls`).
- The policies nest in a fixed order, outermost first: `Fallback → Retry → CircuitBreaker → RateLimiter → AdaptiveLimit → Timeout → Bulkhead → method`. That is why the fallback sees the final outcome and every retry attempt has its own two-second timeout.

## Next steps

- [How it works](how-it-works.md): what the processor generates, instance ids, where configuration comes from.
- [Policies](../policies/README.md): every attribute of every policy, with their defaults.
- [Configuration](../guides/configuration.md): retune the same code from a properties file, environment variables or system properties.
- [Testing](../guides/testing.md): run this example on a `ManualClock`, with no real waiting.
- [Async methods](../guides/async.md): return a `CompletableFuture` and nothing blocks.
