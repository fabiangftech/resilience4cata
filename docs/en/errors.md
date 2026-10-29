# Error reference

Every error that resilience4cata reports starts with a stable code in brackets, for example:

```text
error: [R4C-FB-002] @Fallback(method = "findFallbak") on find: no method named findFallbak in UserClient. Did you mean findFallback? Expected a default or static method java.lang.String findFallbak(java.lang.String, <a Throwable type> e), where the exception parameter is optional.
```

The code tells you which section of this page explains the error. The rest of the message says where (the
compiler points at the annotated element; class, method and attribute are named in the text), what is wrong
and how to fix it.

| Prefix | Reported by | When |
|---|---|---|
| `R4C-APT` | annotation processor | placement of the annotations, general problems |
| `R4C-ATR` | annotation processor | attribute values |
| `R4C-CMP` | annotation processor | composed annotations (`@ResiliencePolicy` bundles) |
| `R4C-ASY` | annotation processor | asynchronous methods |
| `R4C-HDG` | annotation processor | `@Hedge` |
| `R4C-CLS` | annotation processor | `classifier` classes and `onExhaustedResult` |
| `R4C-FB` | annotation processor | `@Fallback` |
| `R4C-SUB` | annotation processor | annotated classes (generated subclasses) |
| `R4C-RT` | `resilience4cata-core` at run time | `Resilient.of`, `Resilient.create`, `Pipeline.Builder` |
| `R4C-REG` | `resilience4cata-core` at run time | `*Config` builders, `ResilienceRegistry` and its builder: named configs, instances |
| `R4C-CFG` | `resilience4cata-config` at run time | external configuration (`ResilienceConfig`) |
| `R4C-SPR` | `resilience4cata-spring` at startup | the Spring Boot integration: beans that cannot be protected as declared |
| `R4C-MN` | `resilience4cata-micronaut` at startup or bean creation | the Micronaut integration: beans that cannot be protected as declared |
| `R4C-QK` | `resilience4cata-quarkus` while Quarkus builds the application | the Quarkus extension: beans that cannot be protected as declared, or would be protected twice |
| `R4C-TK` | `resilience4cata-testkit` in tests | `ManualClock`, `ResilienceTesting` |
| `R4C-JU` | `resilience4cata-junit` in tests | the JUnit extension (`@ResilienceTest`) |

Codes are stable: a code is never renumbered or given a new meaning. A new error gets the next free number of
its area; an error that is no longer reported keeps its code, marked as retired below.

The wrong and fixed Java examples of the annotation processor's codes are compiled by the build
(`ErrorCodeTest` in the `processor` module): each wrong example reports its code and each fix compiles
without warnings.

## Annotation processor: placement (R4C-APT)

### R4C-APT-001

**Trigger:** a resilience annotation on an enum or a record, or on one of its methods. The interface-wrapper
style needs an interface, and the subclass style must extend the type, but enums and records are final.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public record PriceClient(String baseUrl) {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Fix:** declare the methods in an interface, implement it with the record, and wrap the implementation with
`Resilient.of(PriceClient.class, implementation, registry)`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    String price(String sku);
}
```

### R4C-APT-002

**Trigger:** a resilience annotation on a static or private method. Calls to such methods cannot be
intercepted by a wrapper or a subclass.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    static String price(String sku) {
        return "1";
    }
}
```

**Fix:** annotate a non-private instance method (abstract or default in an interface; overridable in a
class).

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry
    String price(String sku);
}
```

### R4C-APT-003

**Trigger:** the annotated interface is private (a private member of a class). The generated class
`<Interface>$Resilient` is a top-level class and must implement it.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Clients {
    private interface PriceClient {
        @Retry
        String price(String sku);
    }
}
```

**Fix:** make the interface package-private or public.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry
        String price(String sku);
    }
}
```

### R4C-APT-004

**Trigger:** an annotation has an attribute that the processor does not know, for example
`Unknown attribute @Retry.hedges`. This happens when `resilience4cata-annotations` is newer than
`resilience4cata-processor`.

**Fix:** use the same version for every resilience4cata artifact, ideally through the BOM:

```groovy
dependencies {
    implementation platform('dev.resilience4cata:resilience4cata-bom:<version>')
    compileOnly 'dev.resilience4cata:resilience4cata-annotations'
    implementation 'dev.resilience4cata:resilience4cata-core'
    annotationProcessor platform('dev.resilience4cata:resilience4cata-bom:<version>')
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor'
}
```

### R4C-APT-005

**Trigger:** the processor could not write a generated source file, or the native-image metadata file it
writes next to it (`META-INF/native-image/dev.resilience4cata.generated/<Interface>$Resilient/reachability-metadata.json`
in the class output): the compiler's `Filer` failed, for example because the output directory is not
writable, or because another processor or a source file already defines `<Interface>$Resilient`.

**Fix:** check the build's generated-sources directory (with Gradle,
`build/generated/sources/annotationProcessor`), make sure no hand-written class is named
`<Interface>$Resilient`, and rebuild after `./gradlew clean`.

### R4C-APT-006

**Trigger:** the compiler option `-Ar4c.inject` has a value other than `micronaut`; or it is
`-Ar4c.inject=micronaut` but Micronaut's `io.micronaut.context.annotation.Replaces` is not on the compile class
path, so the generated subclass of an annotated class cannot replace the class as a Micronaut bean. The option is
read once per compilation; the error has no source position.

**Wrong** (compiled with `-Ar4c.inject=spring`):

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class InventoryService {
    @Retry
    public int stock(String sku) {
        return 1;
    }
}
```

**Fix:** use `-Ar4c.inject=micronaut` in a Micronaut application, with `io.micronaut:micronaut-inject` on the
compile class path (every Micronaut application has it); remove the option for any other container (Spring Boot
needs none: see the [Spring Boot guide](guides/spring.md)). See the [Micronaut guide](guides/micronaut.md#classes).

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs.add('-Ar4c.inject=micronaut')
}
```

## Annotation processor: attribute values (R4C-ATR)

### R4C-ATR-001

**Trigger:** a duration attribute (`delay`, `maxDelay`, `maxDuration`, `slowCallDuration`, `waitInOpen`,
`maxWaitInHalfOpen`, `maxWait`, `period`, `budgetWindow`, `window`, or the value of `@Timeout`) is not a valid
duration.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(delay = "2 seconds")
    String price(String sku);
}
```

**Fix:** use a number followed by `ns`, `us`, `ms`, `s`, `m`, `h` or `d` (`"250ms"`, `"2s"`), an ISO-8601
duration (`"PT2S"`), or `"0"`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(delay = "2s")
    String price(String sku);
}
```

### R4C-ATR-002

**Trigger:** a duration that must be greater than zero is `"0"`: the value of `@Timeout`, or
`@RateLimiter.period`.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Timeout;

public interface PriceClient {
    @Timeout("0")
    String price(String sku);
}
```

**Fix:** set a positive duration. To switch the policy off, use `enabled = "false"` (or configuration)
instead.

```java
package com.acme;

import dev.resilience4cata.annotation.Timeout;

public interface PriceClient {
    @Timeout("2s")
    String price(String sku);
}
```

### R4C-ATR-003

**Trigger:** a count is less than 1: `maxAttempts`, `windowSize`, `minimumCalls`, `halfOpenCalls`,
`maxConcurrent`, `permits`, `initialLimit`, `minLimit` or `maxLimit`.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(maxAttempts = 0)
    String price(String sku);
}
```

**Fix:** set it to 1 or more (`maxAttempts` counts the first call), or leave it out to use the configured
value.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(maxAttempts = 3)
    String price(String sku);
}
```

### R4C-ATR-004

**Trigger:** `@Retry.multiplier` is less than 1, infinite or NaN.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(multiplier = 0.5)
    String price(String sku);
}
```

**Fix:** use a finite number of at least 1 (1 keeps the delay constant).

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(multiplier = 2.0)
    String price(String sku);
}
```

### R4C-ATR-005

**Trigger:** `@CircuitBreaker.failureRateThreshold` or `slowCallRateThreshold` is not a percentage greater
than 0 and at most 100.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;

public interface PriceClient {
    @CircuitBreaker(failureRateThreshold = 150)
    String price(String sku);
}
```

**Fix:** use a value in (0, 100].

```java
package com.acme;

import dev.resilience4cata.annotation.CircuitBreaker;

public interface PriceClient {
    @CircuitBreaker(failureRateThreshold = 50)
    String price(String sku);
}
```

### R4C-ATR-006

**Trigger:** `enabled` is neither `""` (not set), `"true"` nor `"false"`.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(enabled = "yes")
    String price(String sku);
}
```

**Fix:** use `"true"` or `"false"`, or leave it out.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(enabled = "false")
    String price(String sku);
}
```

### R4C-ATR-007

**Trigger:** a retry or hedge budget attribute is out of range: `budgetPercent` is negative, infinite or NaN,
`budgetMinRetriesPerSecond` (`@Retry`) or `budgetMinHedgesPerSecond` (`@Hedge`) is negative, or `budgetWindow`
is shorter than 1 s or longer than 1 h. (A `budgetWindow` that is not a duration at all is
[R4C-ATR-001](#r4c-atr-001).)

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(budgetPercent = 20, budgetWindow = "500ms")
    String price(String sku);
}
```

**Fix:** use a percentage of 0 or more, a reserve of 0 or more retries per second, and a window between
`"1s"` and `"1h"`; or leave the attribute out to inherit it (`budgetWindow` defaults to `10s`).

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(budgetPercent = 20, budgetMinRetriesPerSecond = 1, budgetWindow = "10s")
    String price(String sku);
}
```

### R4C-ATR-008

**Trigger:** `@Hedge(maxAttempts = …)` is below 2 or above 10. `maxAttempts` counts every attempt, the first
one included, so 1 would never hedge; more than 10 attempts in flight for one call is refused.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(maxAttempts = 1, delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

**Fix:** use a value from 2 (one hedge, the default) to 10, or remove `@Hedge` if the call should not be hedged.

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(maxAttempts = 2, delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

### R4C-ATR-009

**Trigger:** the bounds of `@AdaptiveLimit` are out of order (`minLimit` above `maxLimit`, or `initialLimit`
outside them), or its `window` is shorter than 10 ms or longer than 1 h. Only the bounds set on the annotation
are compared; a bound that comes from a named or external configuration is checked when the registry resolves
it ([R4C-REG-003](#r4c-reg-003)).

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.AdaptiveLimit;

public interface OrderClient {
    @AdaptiveLimit(minLimit = 50, maxLimit = 20)
    String place(String order);
}
```

**Fix:** keep `minLimit <= initialLimit <= maxLimit`, and a `window` from `"10ms"` to `"1h"`.

```java
package com.acme;

import dev.resilience4cata.annotation.AdaptiveLimit;

public interface OrderClient {
    @AdaptiveLimit(minLimit = 20, maxLimit = 50)
    String place(String order);
}
```

## Annotation processor: composed annotations (R4C-CMP)

### R4C-CMP-001

**Trigger:** an annotation type carries a resilience annotation but is not marked with `@ResiliencePolicy`,
so the processor would ignore its policies wherever it is used.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Patient {
}
```

**Fix:** add `@ResiliencePolicy`.

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Patient {
}
```

### R4C-CMP-002

**Trigger:** two composed annotations on the same element set the same attribute to different values, so
neither can win.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public interface PriceClient {
    @Fast
    @Patient
    String price(String sku);
}

@ResiliencePolicy
@Retry(maxAttempts = 2)
@Retention(RetentionPolicy.CLASS)
@interface Fast {
}

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@interface Patient {
}
```

**Fix:** keep only one of the bundles, or make them agree, or set the attribute directly on the element,
which wins over every bundle.

```java
package com.acme;

import dev.resilience4cata.annotation.ResiliencePolicy;
import dev.resilience4cata.annotation.Retry;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public interface PriceClient {
    @Patient
    String price(String sku);
}

@ResiliencePolicy
@Retry(maxAttempts = 2)
@Retention(RetentionPolicy.CLASS)
@interface Fast {
}

@ResiliencePolicy
@Retry(maxAttempts = 5)
@Retention(RetentionPolicy.CLASS)
@interface Patient {
}
```

## Annotation processor: asynchronous methods (R4C-ASY)

### R4C-ASY-001

**Trigger:** an annotated method returns a `CompletionStage` type that is not supported: a raw type, a
`? super X` argument, or a `CompletionStage` subtype other than `CompletableFuture`.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<? super Integer> price(String sku);
}
```

**Fix:** return `CompletionStage<T>` or `CompletableFuture<T>`, where `T` may also be `?` or `? extends X`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<Integer> price(String sku);
}
```

### R4C-ASY-002

**Trigger:** an annotated method returns a lazy reactive type: a `Flow.Publisher` or Reactive Streams `Publisher`
(Reactor's `Flux`, RxJava's `Flowable`), Mutiny's `Uni` or `Multi`, or RxJava's `Single`, `Maybe`,
`Completable` or `Observable`. Such a method only builds a pipeline that runs later, when it is subscribed, so the
policies would protect building it, not the work: a failure would never be retried or timed out. Reactor's `Mono` is
supported with `resilience4cata-reactor` (see [R4C-ASY-003](#r4c-asy-003)).

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.Flow;

public interface PriceClient {
    @Retry
    Flow.Publisher<Integer> price(String sku);
}
```

**Fix:** return `CompletionStage<T>` or `CompletableFuture<T>`, converting in the implementation
(`uni.subscribeAsCompletionStage()`), or Reactor's `Mono<T>` with `resilience4cata-reactor` (`flux.collectList()` for a
`Flux` whose items fit in memory), or keep the reactive type and use the reactive library's own retry and timeout
operators.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletionStage;

public interface PriceClient {
    @Retry
    CompletionStage<Integer> price(String sku);
}
```

### R4C-ASY-003

**Trigger:** an annotated method returns Reactor's `Mono`, and `resilience4cata-reactor` is not on the compile class
path. The generated code of a `Mono` method calls that module.

**Wrong:** with `reactor-core` but without `resilience4cata-reactor`:

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Mono;

public interface PriceClient {
    @Retry
    Mono<Integer> price(String sku);
}
```

**Fix:** add `dev.resilience4cata:resilience4cata-reactor` (same version as the other modules) to the dependencies,
next to `resilience4cata-core`. The code stays the same.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;
import reactor.core.publisher.Mono;

public interface PriceClient {
    @Retry
    Mono<Integer> price(String sku);
}
```

## Annotation processor: hedges (R4C-HDG)

### R4C-HDG-001

**Trigger:** `@Hedge` applies to a synchronous method: one that does not return `CompletionStage<T>` or
`CompletableFuture<T>`. A hedge starts the call again while the first attempt is still running, which needs an
asynchronous call. A `@Hedge` on the type, or in a `@ResiliencePolicy` bundle, applies to every method, so each
synchronous method of the type is reported.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    String quote(String sku);
}
```

**Fix:** return a `CompletableFuture` (or `CompletionStage`), or remove `@Hedge` from the method. For a `@Hedge` on the type, annotate the async methods instead of the type.

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    CompletableFuture<String> quote(String sku);
}
```

### R4C-HDG-002

**Trigger:** `@Hedge` and `@Retry` apply to the same method, directly, on the type, or through
`@ResiliencePolicy` bundles. They take the same place in the order of policies, and a retry around a hedge
would multiply the attempts.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.Retry;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms")
    @Retry(maxAttempts = 3)
    CompletableFuture<String> quote(String sku);
}
```

**Fix:** keep one. A hedge already starts a new attempt at once when one fails with a failure in `hedgeOn`, so
`maxAttempts` on the hedge covers the simple retry.

```java
package com.acme;

import dev.resilience4cata.annotation.Hedge;
import java.util.concurrent.CompletableFuture;

public interface QuoteClient {
    @Hedge(delay = "50ms", maxAttempts = 3)
    CompletableFuture<String> quote(String sku);
}
```

## Annotation processor: policy order (R4C-ORD)

### R4C-ORD-001

**Trigger:** `@PolicyOrder` lists a layer more than once (on the method, on its type or in a bundle). Each layer has
one place in the order.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportClient {
    @Retry
    @Timeout("2s")
    @PolicyOrder({Layer.TIMEOUT, Layer.RETRY, Layer.TIMEOUT})
    String report(String id);
}
```

**Fix:** list each layer once, outermost first.

```java
package com.acme;

import dev.resilience4cata.annotation.Layer;
import dev.resilience4cata.annotation.PolicyOrder;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;

public interface ReportClient {
    @Retry
    @Timeout("2s")
    @PolicyOrder({Layer.TIMEOUT, Layer.RETRY})
    String report(String id);
}
```

## Annotation processor: classifiers (R4C-CLS)

The generated class creates one instance of each `classifier` class per method, so the class must be a
concrete, non-generic class with an accessible no-argument constructor, implementing `Classifier<T>` for the
method's result type.

### R4C-CLS-001

**Trigger:** `classifier` is set, but `dev.resilience4cata.Classifier` is not on the compile classpath.

**Wrong:** compiling this without `resilience4cata-core` on the classpath:

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Fix:** add the core dependency:

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

### R4C-CLS-002

**Trigger:** `classifier` names an interface, an annotation or an enum instead of a class.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Classifier.class)
    String price(String sku);
}
```

**Fix:** name a class that implements `Classifier<T>`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-003

**Trigger:** the classifier class is abstract, so it cannot be instantiated.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    abstract class Retryable implements Classifier<String> {
    }
}
```

**Fix:** use a concrete class.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-004

**Trigger:** the classifier class declares type parameters; the generated code could only create it with an
unchecked raw type.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable<T> implements Classifier<T> {
        @Override
        public Outcome classify(T result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Fix:** implement `Classifier<T>` with a concrete `T` (or a supertype, such as `Classifier<Object>`).

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<Object> {
        @Override
        public Outcome classify(Object result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-005

**Trigger:** the classifier class (or a class it is nested in) is private, or package-private in another
package, so the generated class, in the interface's package, cannot create it.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    private static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Fix:** make it public, or package-private in the interface's package.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-006

**Trigger:** the classifier is an inner (non-static nested) class, which needs an instance of its enclosing
class.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Fix:** make it `static`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public final class Clients {
    interface PriceClient {
        @Retry(classifier = Retryable.class)
        String price(String sku);
    }

    static final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-007

**Trigger:** the classifier has no no-argument constructor that the generated class can call.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        private Retryable() {
        }

        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Fix:** add a public (or, in the interface's package, package-private) no-argument constructor, or remove
the private one.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-008

**Trigger:** the classifier class does not implement `dev.resilience4cata.Classifier`.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = String.class)
    String price(String sku);
}
```

**Fix:** name a class that implements `Classifier<T>`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-009

**Trigger:** the classifier implements the raw type `Classifier`, so its result type cannot be checked.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier {
        @Override
        public Outcome classify(Object result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Fix:** implement `Classifier<T>` with the method's result type, as the message suggests.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-010

**Trigger:** the classifier implements `Classifier<X>`, but the method's result (the value of the stage, for
an asynchronous method) is not assignable to `X`.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<Integer> {
        @Override
        public Outcome classify(Integer result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Fix:** implement `Classifier` for the method's result type or a supertype of it.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku);

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

### R4C-CLS-011

**Trigger:** `@Retry` sets both `classifier` and `retryOn`, or `@CircuitBreaker` sets both `classifier` and
`recordOn`. The classifier decides which exceptions are failures, so the list would be ignored.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

public interface PriceClient {
    @Retry(classifier = Retryable.class, retryOn = IOException.class)
    String price(String sku) throws IOException;

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure == null ? Outcome.SUCCESS : Outcome.FAILURE;
        }
    }
}
```

**Fix:** remove `retryOn` (or `recordOn`) and return `Outcome.FAILURE` for those exceptions from the
classifier. `abortOn` and `ignoreOn` still apply before the classifier.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.Retry;
import java.io.IOException;

public interface PriceClient {
    @Retry(classifier = Retryable.class)
    String price(String sku) throws IOException;

    final class Retryable implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
```

### R4C-CLS-012

**Trigger:** `@Retry` sets `onExhaustedResult` without a `classifier`. Only a classifier can reject a
result, so the setting would never apply.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(onExhaustedResult = ExhaustedResult.THROW)
    String price(String sku);
}
```

**Fix:** add a classifier that rejects the results to retry, or remove `onExhaustedResult`.

```java
package com.acme;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.ExhaustedResult;
import dev.resilience4cata.annotation.Retry;

public interface PriceClient {
    @Retry(classifier = NotEmpty.class, onExhaustedResult = ExhaustedResult.THROW)
    String price(String sku);

    final class NotEmpty implements Classifier<String> {
        @Override
        public Outcome classify(String result, Throwable failure) {
            return failure != null || result == null || result.isEmpty() ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }
}
```

## Annotation processor: fallbacks (R4C-FB)

A `@Fallback` names either a handler class, `@Fallback(MyHandler.class)`, or sibling methods,
`@Fallback(method = "name")`. A handler class must meet the same rules as a classifier: concrete,
non-generic, accessible, with a no-argument constructor, implementing `FallbackHandler<T>` for the method's
result type.

### R4C-FB-001

**Trigger:** `@Fallback` sets neither a handler class nor a method name, or sets both.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback
    String find(String id);
}
```

**Fix:** set exactly one of them.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-002

**Trigger:** `@Fallback(method = "...")` names a method that does not exist in the interface (or class).
The message suggests the closest existing name.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallbak")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

**Fix:** correct the name, or add the method. It takes the same parameters as the annotated method,
optionally followed by the exception, and returns the same type.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-003

**Trigger:** methods with the fallback's name exist, but none can be used. The message lists each candidate
and why it was rejected: private (the generated class cannot call it), wrong return type, a checked
exception the annotated method does not declare, different type parameters, or an exception parameter that
is not a class. Candidates with other parameters are not listed.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    private String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

**Fix:** in an interface, use a default or static method; in a class, a non-private one. Match the
signature that the message shows.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(method = "findFallback")
    String find(String id);

    default String findFallback(String id, Exception e) {
        return "unknown";
    }
}
```

### R4C-FB-004

**Trigger:** `@Fallback` names a handler class, but `dev.resilience4cata.FallbackHandler` is not on the
compile classpath.

**Wrong:** compiling this without `resilience4cata-core` on the classpath:

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Fix:** add the core dependency:

```groovy
dependencies {
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

### R4C-FB-005

**Trigger:** the handler named by `@Fallback` is an interface, an annotation or an enum instead of a class.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(FallbackHandler.class)
    String find(String id);
}
```

**Fix:** name a class that implements `FallbackHandler<T>`.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-006

**Trigger:** the handler class is abstract.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    abstract class Unknown implements FallbackHandler<String> {
    }
}
```

**Fix:** use a concrete class.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-007

**Trigger:** the handler class declares type parameters.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown<T> implements FallbackHandler<T> {
        @Override
        public T apply(FallbackContext context) {
            return null;
        }
    }
}
```

**Fix:** implement `FallbackHandler<T>` with a concrete `T`. For a generic method, use a sibling method
instead (see [R4C-FB-014](#r4c-fb-014)).

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-008

**Trigger:** the handler class (or a class it is nested in) is private, or package-private in another
package, so the generated class cannot create it.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    private static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Fix:** make it public, or package-private in the interface's package.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-009

**Trigger:** the handler is an inner (non-static nested) class.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Fix:** make it `static`.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public final class Clients {
    interface UserClient {
        @Fallback(Unknown.class)
        String find(String id);
    }

    static final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-010

**Trigger:** the handler has no no-argument constructor that the generated class can call.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        private Unknown() {
        }

        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Fix:** add a public (or, in the interface's package, package-private) no-argument constructor, or remove
the private one.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-011

**Trigger:** the handler class does not implement `dev.resilience4cata.FallbackHandler`.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(String.class)
    String find(String id);
}
```

**Fix:** name a class that implements `FallbackHandler<T>`.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-012

**Trigger:** the handler implements the raw type `FallbackHandler`.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler {
        @Override
        public Object apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

**Fix:** implement `FallbackHandler<T>` with the method's result type, as the message suggests.

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-013

**Trigger:** the handler implements `FallbackHandler<X>`, but `X` is not assignable to the method's return
type. For an asynchronous method returning a stage of `T`, `X` may be `T` or `CompletionStage<T>`.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<Integer> {
        @Override
        public Integer apply(FallbackContext context) {
            return 0;
        }
    }
}
```

**Fix:** implement `FallbackHandler` for the method's return type (boxed, for a primitive).

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface UserClient {
    @Fallback(Unknown.class)
    String find(String id);

    final class Unknown implements FallbackHandler<String> {
        @Override
        public String apply(FallbackContext context) {
            return "unknown";
        }
    }
}
```

### R4C-FB-014

**Trigger:** a handler class on a generic method whose return type depends on the method's type parameters.
A handler implements `FallbackHandler<X>` for one `X`, which cannot fit every instantiation without an
unchecked cast.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.annotation.Fallback;

public interface Repository {
    @Fallback(Nothing.class)
    <T> T load(Class<T> type, String id);

    final class Nothing implements FallbackHandler<Object> {
        @Override
        public Object apply(FallbackContext context) {
            return null;
        }
    }
}
```

**Fix:** use a sibling fallback method that declares the same type parameters.

```java
package com.acme;

import dev.resilience4cata.annotation.Fallback;

public interface Repository {
    @Fallback(method = "loadFallback")
    <T> T load(Class<T> type, String id);

    default <T> T loadFallback(Class<T> type, String id, Exception e) {
        return null;
    }
}
```

## Annotation processor: annotated classes (R4C-SUB)

An annotated class gets a generated subclass, `<Class>$Resilient`, which overrides the annotated methods
and is created with `Resilient.create(...)`. The class must therefore be extensible, and the annotated
methods overridable.

### R4C-SUB-001

**Trigger:** the annotated class is final.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Fix:** remove `final`, or move the annotations to an interface and wrap the implementation with
`Resilient.of(...)`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-002

**Trigger:** the annotated class is sealed, so the generated subclass is not a permitted subclass.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public sealed class PriceService permits CachedPriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}

final class CachedPriceService extends PriceService {
}
```

**Fix:** make it non-sealed (or a regular class), or use an interface and `Resilient.of(...)`.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}

final class CachedPriceService extends PriceService {
}
```

### R4C-SUB-003

**Trigger:** the annotated class is abstract. The generated subclass must be instantiable and calls
`super` for each method.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public abstract class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Fix:** annotate a concrete subclass, or an interface.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-004

**Trigger:** the annotated class, or a class it is nested in, is private.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Services {
    private static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

**Fix:** make it package-private, protected or public.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public final class Services {
    static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

### R4C-SUB-005

**Trigger:** the annotated class is an inner (non-static nested) class: its generated subclass would need an
instance of the enclosing class.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class Services {
    class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

**Fix:** make it a static nested class.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class Services {
    static class PriceService {
        @Retry
        public String price(String sku) {
            return "1";
        }
    }
}
```

### R4C-SUB-006

**Trigger:** every constructor of the annotated class is private, so the generated subclass cannot call any.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    private PriceService() {
    }

    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

**Fix:** make at least one constructor package-private, protected or public.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    protected PriceService() {
    }

    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-007

**Trigger:** an annotated method (in the class, a superclass or an interface it implements) is final, so the
subclass cannot override it and its annotations would be ignored.

**Wrong:**

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public final String price(String sku) {
        return "1";
    }
}
```

**Fix:** remove `final`, or remove the annotations.

```java
package com.acme;

import dev.resilience4cata.annotation.Retry;

public class PriceService {
    @Retry
    public String price(String sku) {
        return "1";
    }
}
```

### R4C-SUB-008

**Trigger:** an annotated method is package-private in a superclass from another package. The generated
subclass, in the annotated class's package, cannot override it, so its annotations would be ignored.

**Wrong:**

```java
// com/acme/base/BaseService.java
package com.acme.base;

import dev.resilience4cata.annotation.Retry;

public class BaseService {
    @Retry
    String price(String sku) {
        return "1";
    }
}

// com/acme/PriceService.java
package com.acme;

import com.acme.base.BaseService;
import dev.resilience4cata.annotation.Retry;

public class PriceService extends BaseService {
    @Retry
    public String name() {
        return "prices";
    }
}
```

**Fix:** make the method protected or public.

```java
// com/acme/base/BaseService.java
package com.acme.base;

import dev.resilience4cata.annotation.Retry;

public class BaseService {
    @Retry
    protected String price(String sku) {
        return "1";
    }
}

// com/acme/PriceService.java
package com.acme;

import com.acme.base.BaseService;
import dev.resilience4cata.annotation.Retry;

public class PriceService extends BaseService {
    @Retry
    public String name() {
        return "prices";
    }
}
```

## Runtime: Resilient and Pipeline (R4C-RT)

These are thrown by `resilience4cata-core` when a service is wrapped or a pipeline is built. The exception
types are documented on each method; the code is at the start of the message.

### R4C-RT-001

**Trigger:** `Resilient.of(type, ...)` with a `type` that is a class, not an interface
(`IllegalArgumentException`).

**Wrong:** `Resilient.of(PriceService.class, new PriceService(), registry)` where `PriceService` is an
annotated class.

**Fix:** for an annotated class, use `Resilient.create(PriceService.class, registry)`; `Resilient.of` takes
the annotated interface.

### R4C-RT-002

**Trigger:** the object passed to `Resilient.of` does not implement the interface
(`IllegalArgumentException`). It can only happen through raw types or unchecked casts.

**Fix:** pass an implementation of the interface.

### R4C-RT-003

**Trigger:** the generated class `<type>$Resilient` was not found (`IllegalStateException`): the type has no
resilience annotation, or the annotation processor did not run when it was compiled.

**Wrong:** a build that only adds the annotations:

```groovy
dependencies {
    compileOnly 'dev.resilience4cata:resilience4cata-annotations:<version>'
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
}
```

**Fix:** add the processor, which is mandatory (there is no reflection fallback):

```groovy
dependencies {
    compileOnly 'dev.resilience4cata:resilience4cata-annotations:<version>'
    implementation 'dev.resilience4cata:resilience4cata-core:<version>'
    annotationProcessor 'dev.resilience4cata:resilience4cata-processor:<version>'
}
```

In a GraalVM native image, the same error means the processor ran but the image cannot reach the class by
reflection: the reachability metadata it writes next to the class files,
`META-INF/native-image/dev.resilience4cata.generated/<type>$Resilient/reachability-metadata.json`, was not on
the image build's class path, or a build step (shading, a custom resource filter) stripped it. The message
then ends with a sentence that names that file. Keep the processor's output, metadata included, in the jar or
directory that `native-image` reads; see [Native image](start/installation.md#native-image).

### R4C-RT-004

**Trigger:** the generated class does not have the constructor that this version of `resilience4cata-core`
expects, or a generated subclass does not extend the annotated class (`IllegalStateException`). It was
generated by an incompatible version of the processor.

**Fix:** use the same version for `resilience4cata-core` and `resilience4cata-processor` (the BOM keeps them
aligned), then rebuild from clean.

### R4C-RT-005

**Trigger:** `resilience4cata-core` cannot access the generated class (`IllegalStateException`), usually
because the application is a named module that does not export the package to core.

**Fix:** add to the application's `module-info.java`:

```java
exports com.acme to dev.resilience4cata.core;
```

### R4C-RT-006

**Trigger:** the generated class's constructor failed with a checked exception, or could not be instantiated
(`IllegalStateException`, with the failure as its cause). Unchecked exceptions from the constructor, such as
an invalid configuration, are rethrown as they are.

**Fix:** look at the cause; it usually comes from a classifier or fallback handler constructor.

### R4C-RT-007

**Trigger:** `Resilient.create(type, ...)` with an interface (`IllegalArgumentException`).

**Fix:** wrap an implementation instead: `Resilient.of(PriceClient.class, implementation, registry)`.

### R4C-RT-008

**Trigger:** `Resilient.create(type, ...)` with a type that cannot have a generated subclass: final,
abstract, local, anonymous, an enum, a record, an array or a primitive (`IllegalArgumentException`). The
processor reports most of these at compile time ([R4C-SUB-001](#r4c-sub-001) and following).

**Fix:** annotate a top-level or static nested class that is neither final nor abstract, or an interface
and use `Resilient.of(...)`.

### R4C-RT-009

**Trigger:** no constructor of the annotated class accepts the arguments passed to `Resilient.create`
(`IllegalArgumentException`). The message lists the constructors.

**Fix:** pass arguments that match one constructor, or call the generated constructor directly, which the
compiler checks: `new PriceService$Resilient(registry, ...)`.

### R4C-RT-010

**Trigger:** more than one constructor accepts the arguments passed to `Resilient.create`, for example a
single `null` (`IllegalArgumentException`).

**Fix:** call the generated constructor directly, with a cast to pick the overload:
`new PriceService$Resilient(registry, (String) null)`.

### R4C-RT-011

**Trigger:** `Pipeline.builder(registry, id)` with a blank id (`IllegalArgumentException`).

**Fix:** pass the id that names the pipeline's policy instances, for example `"payments"`.

### R4C-RT-012

**Trigger:** a `Pipeline.Builder` part is set twice: a policy, a classifier, `onExhaustedResult` or a
fallback (`IllegalStateException`).

**Wrong:** `Pipeline.builder(registry, "payments").retry().retry("five")`

**Fix:** set each part once per builder; pass the overrides to the one call:
`retry("five", b -> b.maxAttempts(3))`.

### R4C-RT-013

**Trigger:** `fallbackApplyOn` or `fallbackSkipOn` is set, but the pipeline has no fallback
(`IllegalStateException` from `build()`).

**Fix:** add a fallback (`fallback`, `fallbackHandler`, `fallbackAsync`), or remove the selection.

### R4C-RT-014

**Trigger:** `classifier(...)` is set, but the pipeline has neither a retry nor a circuit breaker
(`IllegalStateException` from `build()`).

**Fix:** add the policy the classifier is meant for, or remove the classifier.

### R4C-RT-015

**Trigger:** `retryClassifier(...)` is set, but the pipeline has no retry (`IllegalStateException` from
`build()`).

**Fix:** add a retry, or remove the retry classifier.

### R4C-RT-016

**Trigger:** `onExhaustedResult(...)` is set, but the pipeline has no retry with a classifier
(`IllegalStateException` from `build()`). Only a classifier can reject a result. The annotation equivalent
is [R4C-CLS-012](#r4c-cls-012).

**Fix:** add a retry and a classifier (`classifier` or `retryClassifier`), or remove `onExhaustedResult`.

### R4C-RT-017

**Trigger:** `circuitBreakerClassifier(...)` is set, but the pipeline has no circuit breaker
(`IllegalStateException` from `build()`).

**Fix:** add a circuit breaker, or remove the circuit breaker classifier.

### R4C-RT-018

**Trigger:** `execute(...)` or `run(...)` is called on a pipeline that has a hedge (`IllegalStateException`). A
hedge starts the call again while the first attempt is still running, which needs a call that returns a
`CompletionStage`.

**Fix:** call `executeAsync(() -> client.callAsync(...))`, or remove `.hedge(...)` from the pipeline.

### R4C-RT-019

**Trigger:** the pipeline has both `.retry(...)` and `.hedge(...)` (`IllegalStateException` from `build()`).

**Fix:** keep one: a hedge already starts a new attempt when one fails (`hedgeOn`), and a retry around it would
multiply the attempts. This is the runtime twin of [R4C-HDG-002](#r4c-hdg-002).

## Runtime: registry and policy configuration (R4C-REG)

These are thrown by `resilience4cata-core` when a policy configuration is built (`RetryConfig.builder()` and
the other `*Config` builders), when a `ResilienceRegistry` is built, and when the registry creates or looks up
a policy instance: while a service is wrapped (`Resilient.of`, `Resilient.create`) or a pipeline is built
(`Pipeline.Builder.build()`, which passes them on unchanged). The exception types are documented on each
method; the code is at the start of the message. External configuration (`ResilienceConfig`) validates its
values before they reach the registry and reports them as [R4C-CFG-006](#r4c-cfg-006), with the keys that set
them.

### R4C-REG-001

**Trigger:** a `*Config` builder rejected a value (`IllegalArgumentException` from `build()`), for example a
`maxAttempts` below 1, a negative duration, a `maxDelay` shorter than the `delay`, or a rate threshold outside
`(0, 100]`. The message names the attribute and the value.

**Wrong:** `RetryConfig.builder().maxAttempts(0).build()`

**Fix:** use a value in the range the message gives: `RetryConfig.builder().maxAttempts(1).build()`.

### R4C-REG-002

**Trigger:** `ResilienceRegistry.Builder.build()` found an invalid default or named configuration
(`IllegalArgumentException`, with the [R4C-REG-001](#r4c-reg-001) error as its cause). The message names the
config (or the defaults) and where it was set.

**Wrong:**

```java
ResilienceRegistry.builder()
        .retry("slow", r -> r.delay(Duration.ofSeconds(2)).maxDelay(Duration.ofSeconds(1)))
        .build();
```

**Fix:** make the values of that config valid together. A named config starts from the defaults
(`retryDefaults(...)`), then applies `retry("slow", ...)`, then `retryOverlay("slow", ...)`
(`configs.slow.retry.*` in external configuration):

```java
ResilienceRegistry.builder()
        .retry("slow", r -> r.delay(Duration.ofSeconds(1)).maxDelay(Duration.ofSeconds(2)))
        .build();
```

### R4C-REG-003

**Trigger:** the configuration of one policy instance is invalid once everything that applies to it is
combined (`IllegalArgumentException`, with the [R4C-REG-001](#r4c-reg-001) error as its cause): its named
config (or the defaults), then the values set in its annotation or pipeline step, then its per-instance
overrides (`retryInstance(id, ...)`, `instances.<id>.*` in external configuration). It is thrown when the
instance is created: by `Resilient.of`/`Resilient.create` or by `Pipeline.Builder.build()`.

**Wrong:** a per-instance override that does not fit the instance's own values:

```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryInstance("payments", r -> r.delay(Duration.ofSeconds(30)))
        .build();
Pipeline.builder(registry, "payments").retry(r -> r.maxDelay(Duration.ofSeconds(20))).build();
```

**Fix:** change the override, or the value it collides with, so that the combination is valid, for example
`retryInstance("payments", r -> r.delay(Duration.ofSeconds(10)))`.

### R4C-REG-004

**Trigger:** `config = "name"` (in an annotation or a pipeline step), or `retryConfig("name")` and the other
`*Config(String)` lookups, names a config that the registry does not have (`IllegalArgumentException`). The
message lists the known configs and suggests the closest one ("Did you mean …?").

**Wrong:** `@Retry(config = "fiev")` with a registry that only registers `retry("five", ...)`.

**Fix:** correct the name, or register the config: `ResilienceRegistry.builder().retry("fiev", r -> ...)`, or
`configs.fiev.retry.*` in external configuration.

### R4C-REG-005

**Trigger:** the same named config is registered twice on one builder, for example two calls to
`retry("five", ...)` (`IllegalArgumentException`).

**Fix:** register it once. To change some of its attributes elsewhere, use the overlay, which applies on top
of it: `retryOverlay("five", r -> r.maxAttempts(3))`.

### R4C-REG-006

**Trigger:** a named config or overlay is registered with the name `""` or `"default"`, for example
`retry("default", ...)` (`IllegalArgumentException`). Both names are reserved: they mean the defaults.

**Fix:** change the defaults with `retryDefaults(...)` (`defaults.retry.*` in external configuration), or
give the config another name.

### R4C-REG-007

**Trigger:** `retryInstance("", ...)` or another `*Instance(...)` call with an empty instance id
(`IllegalArgumentException`).

**Fix:** pass the id of the instance: the `name` set in its annotation, the pipeline id, or the derived id
`<interface>#<method>(<parameter types>)`, for example `com.acme.PriceClient#price(java.lang.String)`.

### R4C-REG-008

**Trigger:** two call sites share one retry, circuit breaker, rate limiter or bulkhead instance, through the
same `name` in their annotations or the same pipeline id, but resolve to different configurations
(`IllegalStateException`). The message shows both configurations. Instances with the same name are shared,
so they must agree.

**Wrong:**

```java
interface Inventory {
    @Retry(name = "backend", maxAttempts = 3)
    String stock(String sku);

    @Retry(name = "backend", maxAttempts = 5)
    String reserve(String sku);
}
```

**Fix:** give the call sites different names if they should be independent, or make their configuration
identical (for example, the same `config = "..."` and no differing attributes) if they should share one
instance.

### R4C-REG-009

**Trigger:** `registry.circuitBreaker(id)`, `registry.rateLimiter(id)` or `registry.bulkhead(id)` with an id
that no instance of this registry has (`IllegalArgumentException`). The message lists the known ids and
suggests the closest one. Instances are created when their interface is wrapped (`Resilient.of`), their class
is created (`Resilient.create`) or their pipeline is built, so looking one up earlier also fails.

**Fix:** use the `name` set in the annotation (or the pipeline id), or the derived id
`<interface>#<method>(<parameter types>)`, and look it up after the service or pipeline has been created.

## Runtime: external configuration (R4C-CFG)

`ResilienceConfig.build()` reports every problem at once in one `ConfigException`; each line starts with its
code and names the key and its source (file and line, environment variable or system property).

### R4C-CFG-001

**Trigger:** an unknown, malformed or incomplete key: unknown policy or attribute, empty segment, a key that
stops early. The message suggests the closest valid key.

**Wrong:**

```properties
configs.external.circuit-breaker.wait-in-opne=10s
```

**Fix:** use one of the key forms the message lists: `defaults.<policy>.<attribute>`,
`configs.<name>.<policy>.<attribute>`, `configs.<name>.extends`, `instances.<id>.<policy>.<attribute>`,
`profiles.<profile>.…` or `profiles.active`.

```properties
configs.external.circuit-breaker.wait-in-open=10s
```

### R4C-CFG-002

**Trigger:** a value that cannot be parsed for its attribute: a number, boolean, enum constant or duration.

**Wrong:**

```properties
defaults.retry.delay=fast
```

**Fix:** use a value of the attribute's type; durations use the same grammar as annotations (`"250ms"`,
`"2s"`, `"PT2S"`, `"0"`).

```properties
defaults.retry.delay=250ms
```

### R4C-CFG-003

**Trigger:** an exception list (`retry-on`, `abort-on`, `record-on`, `ignore-on`) names a class the class
loader cannot find, or a class that is not a `Throwable`.

**Wrong:**

```properties
defaults.retry.retry-on=java.io.IOExeption
```

**Fix:** use fully qualified names of exception classes visible to the application's class loader.

```properties
defaults.retry.retry-on=java.io.IOException
```

### R4C-CFG-004

**Trigger:** an invalid config or profile name, or a reserved one (`default` for a config, `active` for a
profile). Names use lowercase letters and digits separated by single hyphens, so that they map to
environment variables.

**Wrong:**

```properties
profiles.active=Test
```

**Fix:** rename it, for example `test`.

```properties
profiles.active=test
```

### R4C-CFG-005

**Trigger:** the same key is set twice in one source (for example twice in one file, or under two spellings
such as `max-attempts` and `maxAttempts`).

**Fix:** keep one of them. Different sources may set the same key; the precedence order decides.

### R4C-CFG-006

**Trigger:** the values are valid one by one but not together, for example a `max-delay` shorter than the
`delay`, or `max-attempts=0` (the config builder rejected them). The message names the keys involved.

**Wrong:**

```properties
configs.slow.retry.delay=2s
configs.slow.retry.max-delay=1s
```

**Fix:** make the values consistent, as the message describes.

```properties
configs.slow.retry.delay=1s
configs.slow.retry.max-delay=2s
```

### R4C-CFG-007

**Trigger:** `configs.<name>.extends` names a config that is not defined in external configuration, or
configs extend each other in a cycle.

**Wrong:**

```properties
configs.batch.extends=slwo
```

**Fix:** extend an existing config (the message suggests the closest name and lists the known ones) and
break any cycle.

### R4C-CFG-008

**Trigger:** reserved; not reported by the current version.

### R4C-CFG-009

**Trigger:** a configuration source cannot be read: a classpath resource or file that does not exist, or an
I/O error.

**Fix:** check the path, or do not add the source when it is optional.

### R4C-CFG-010

**Trigger:** an environment variable matches more than one key, because `_` stands for both `.` and `-`.

**Fix:** set that key in a properties file or as a system property instead.

### R4C-CFG-011

**Trigger:** retired; no longer reported. A named config set both in code and externally is now merged, the
external values winning.

### R4C-CFG-012

**Trigger:** a malformed instance id in an `instances.` key: an id that contains `.` but is not quoted, an
empty id, an unclosed or misplaced `"`, quotes around anything but the id, or an id that starts or ends with
whitespace.

**Wrong:**

```properties
instances.com.acme.Api#get(java.lang.String).retry.max-attempts=5
```

**Fix:** quote the instance id.

```properties
instances."com.acme.Api#get(java.lang.String)".retry.max-attempts=5
```

### R4C-CFG-013

**Trigger:** `config.verifyInstances(registry)` found an `instances.` override that matches no instance of
that policy in the registry (reported by `verifyInstances`, not `build()`, because instances exist only once
services are wrapped). The message suggests the closest id.

**Fix:** use an existing instance id: the policy's `name`, or `<interface>#<method>(<parameter types>)` when
it has none.

### R4C-CFG-014

**Trigger:** `config.verifyInstances(registry)` found an `instances.` override of an attribute that the
instance's annotation sets with `locked = true` (or that a pipeline built with `.locked()` sets on its step).
The registry kept the annotation's value and ignored the override; the message shows the configuration the
instance uses.

**Fix:** remove the key, or remove `locked = true` from the annotation if operators should be able to
change that value. See [Locking an instance](guides/configuration.md#locking-an-instance).

## Runtime: Spring Boot integration (R4C-SPR)

`resilience4cata-spring` checks the beans at startup and fails it with these codes when a bean would silently
lose its protection. Spring Boot prints the message as the *Description* and the fix as the *Action* of its
"APPLICATION FAILED TO START" report. See the [Spring Boot guide](guides/spring.md).

### R4C-SPR-001

**Trigger:** a bean is an instance of an annotated class `C` but not of the generated `C$Resilient`, so its
policies would never run: a `@Bean` method returned `new C(...)`, the bean was registered with a supplier,
or it is a subclass of `C` that has no annotations of its own (`BeanCreationException`).

```text
[R4C-SPR-001] Bean 'pricing' is an instance of com.acme.PricingService, which has resilience4cata annotations, but not of the generated com.acme.PricingService$Resilient, so its policies would not run. ...
```

**Fix:** register the class itself (component scanning, `@Import`), so that Spring instantiates
`C$Resilient`; or return `new C$Resilient(registry, ...)` from the `@Bean` method, with the
`ResilienceRegistry` injected as a parameter; or annotate the subclass. To leave the bean unprotected on
purpose, list its name in `resilience4cata.spring.exclude`.

### R4C-SPR-002

**Trigger:** a `@Configuration` class with `@Bean` methods also has resilience4cata annotations. Spring
instantiates its own CGLIB subclass of such a class, so the generated subclass cannot be used.

**Fix:** move the annotated methods to a `@Component` or `@Service` class, or declare the configuration
with `@Configuration(proxyBeanMethods = false)`, which Spring does not subclass.

### R4C-SPR-003

**Trigger:** a bean implements two or more annotated interfaces, none of which extends all the others
(`BeanCreationException`). The generated wrapper of an interface implements that interface only, so the
bean could no longer be injected by the other ones.

**Fix:** annotate the class instead (its generated subclass keeps every interface and is still the bean's
type), or declare one interface that extends the others and implement that one. To wrap the bean yourself,
list it in `resilience4cata.spring.exclude` and define a `@Bean` that returns `Resilient.of(...)`.

### R4C-SPR-004

**Trigger:** an annotated class has a method with `@Retry` or `@Hedge` in effect (on the method, the class, a
superclass, an interface or a bundle) that is also transactional: Spring's or Jakarta's `@Transactional` with a
propagation that joins or starts a transaction (`REQUIRED`, `SUPPORTS`, `MANDATORY`, `REQUIRES_NEW`, `NESTED`)
(`BeanCreationException`). The transaction proxy goes around the generated subclass, so every attempt would run
in the same transaction, and an attempt that fails and marks it rollback-only makes the commit of a later
successful attempt fail with `UnexpectedRollbackException`.

```text
[R4C-SPR-004] Bean 'ledger' (com.acme.Ledger) has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**Fix:** declare the policy on an interface the bean implements instead (its wrapper goes outside the
transaction proxy: one transaction per attempt); or move `@Transactional` to another bean that the retried
method calls; or, if one shared transaction for every attempt is intended, set
`resilience4cata.spring.allow-retry-inside-transaction=true`. `NOT_SUPPORTED` and `NEVER` are not reported.

## Runtime: Micronaut integration (R4C-MN)

`resilience4cata-micronaut` checks the beans and fails with these codes when a bean would silently lose its
protection. The `@ResilientBean` mistakes (001 to 003) stop the startup: every bean definition annotated with
`@ResilientBean` is checked when the context starts, before any is created. The others are reported when the bean
is created, which is at startup for `@Context` beans and on first use for the others (Micronaut creates beans
lazily), as a `ResilienceBeanException` inside Micronaut's `BeanInstantiationException`. See the
[Micronaut guide](guides/micronaut.md).

### R4C-MN-001

**Trigger:** a bean annotated `@ResilientBean(I.class)` is exposed as other types than `I`, for example because it
also declares `@Bean(typed = {I.class, Impl.class})`. `@ResilientBean` limits the bean's types to `I`, because the
generated wrapper that replaces the bean implements `I` only: injecting it as another type would fail or bypass
the policies.

```text
[R4C-MN-001] Bean com.acme.HttpPayments is annotated @ResilientBean(Payments.class) but is exposed as [com.acme.HttpPayments, com.acme.Payments]. ...
```

**Fix:** remove `@Bean(typed = ...)` (or `@Type`) from the bean and inject the interface.

### R4C-MN-002

**Trigger:** `@ResilientBean(X.class)` names a type that is not an interface, or an interface that the bean does
not implement, so no generated wrapper can protect it.

**Fix:** name the annotated interface that the bean implements. For an annotated class, remove `@ResilientBean`:
compile with `-Ar4c.inject=micronaut` so that Micronaut creates its generated subclass, or return
`new C$Resilient(registry, ...)` from a `@Factory` method ([annotated classes](guides/micronaut.md#classes)).

### R4C-MN-003

**Trigger:** a bean annotated `@ResilientBean` has a scope that Micronaut proxies (`@Refreshable`,
`@RequestScope`, `@ThreadLocal`, any `@ScopedProxy`). Such a proxy looks up its target by the bean's class,
which the generated wrapper is not.

**Fix:** make the bean `@Singleton`, `@Prototype` or `@Context`, or keep the scoped bean unprotected and inject it
into a singleton that implements the annotated interface.

### R4C-MN-004

**Trigger:** a bean is an instance of an annotated class `C` but not of the generated `C$Resilient`, so its
policies would never run: Micronaut created `C` itself (it has a scope annotation or an `@Inject` constructor, and
the code was compiled without `-Ar4c.inject=micronaut`), or a `@Factory` method returned `new C(...)`. Also reported
when Micronaut creates `C$Resilient` from its own definition although it was compiled without the option: its
constructor repeats `C`'s `@Inject`, so it is a bean, but without `C`'s scope and qualifiers (a `@Singleton` would
become a new instance per injection).

```text
[R4C-MN-004] Bean com.acme.InventoryService is an instance of com.acme.InventoryService, which has resilience4cata annotations, but not of the generated InventoryService$Resilient, so its policies would not run. ...
```

**Fix:** compile with `-Ar4c.inject=micronaut`, so that Micronaut creates `C$Resilient` in place of `C`; or remove
the scope annotation (and `@Inject`) from `C` and return `new C$Resilient(registry, ...)` from a `@Factory` method
typed `C`. To leave the bean unprotected on purpose, list the class's name in `resilience4cata.micronaut.exclude`.

### R4C-MN-005

**Trigger:** an annotated class compiled with `-Ar4c.inject=micronaut` has a method with `@Retry` or `@Hedge` in
effect that is also `@Transactional` (Micronaut's, or Jakarta's mapped onto it) with a propagation that joins or
starts a transaction (`REQUIRED`, `SUPPORTS`, `MANDATORY`, `REQUIRES_NEW`, `NESTED`). The generated subclass
repeats `@Transactional`, so Micronaut's transaction proxy goes around it: every attempt would run in the same
transaction, and an attempt that fails and marks it rollback-only makes the commit of a later successful attempt
fail.

```text
[R4C-MN-005] Bean com.acme.Ledger has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**Fix:** declare the policy on an interface that the bean implements and annotate the bean `@ResilientBean` (the
wrapper goes outside the transaction proxy: one transaction per attempt); or move `@Transactional` to another
bean that the retried method calls; or, if one shared transaction for every attempt is intended, set
`resilience4cata.micronaut.allow-retry-inside-transaction=true`. `NOT_SUPPORTED` and `NEVER` are not reported.
The check reads the generated class file, so a native image skips it.

### R4C-MN-006

**Trigger:** a switch of the Micronaut integration has an invalid value, for example
`resilience4cata.micronaut.verify-instances: strict`. The context fails to start.

```text
[R4C-MN-006] Invalid value 'strict' for resilience4cata.micronaut.verify-instances. Use fail, warn or off.
```

**Fix:** use one of the values the message lists.

## Build: Quarkus extension (R4C-QK)

`resilience4cata-quarkus` checks the application while Quarkus builds it (`quarkusBuild`, dev mode, every
`@QuarkusTest`) and fails the build with these codes when a bean would not be protected as declared, or would be
protected twice. Quarkus prints them among its "deployment problems". See the [Quarkus guide](guides/quarkus.md).

### R4C-QK-001

**Trigger:** a method protected by resilience4cata also carries MicroProfile or SmallRye Fault Tolerance
annotations (`org.eclipse.microprofile.faulttolerance.*`, `io.smallrye.faulttolerance.api.*`), on the method or
on its class; for an annotated interface, also on the implementing class's method. The simple names are the
same (`@Retry`, `@CircuitBreaker`, `@Timeout`, `@Bulkhead`, `@Fallback`), so this is usually a wrong import. With
`quarkus-smallrye-fault-tolerance` installed both libraries would apply, so the build fails; without it the Fault
Tolerance annotations do nothing, and the same message is logged as a warning.

```text
[R4C-QK-001] Methods protected by resilience4cata also carry MicroProfile Fault Tolerance annotations: com.acme.CatalogClient.fetch(). SmallRye Fault Tolerance would apply its interceptor as well, so both libraries would retry, time out and count the same calls. ...
```

**Fix:** keep one library per method: remove the other annotation, or fix the import. See
[SmallRye Fault Tolerance vs resilience4cata](guides/quarkus.md#smallrye).

### R4C-QK-002

**Trigger:** an annotated class has a method with `@Retry` or `@Hedge` in effect that is also
`@Transactional` (Jakarta Transactions, with Narayana) with a type that joins or starts a transaction
(`REQUIRED`, `REQUIRES_NEW`, `MANDATORY`, `SUPPORTS`). The transaction interceptor goes around the generated
subclass, so every attempt would run in the same transaction, and an attempt that fails and marks it
rollback-only makes the commit of a later successful attempt fail.

```text
[R4C-QK-002] Bean com.acme.Ledger has transactional methods that are retried or hedged: @Retry on post (REQUIRED). ...
```

**Fix:** declare the policy on an interface the bean implements instead (its wrapper goes outside the
transaction interceptor: one transaction per attempt); or move `@Transactional` to another bean that the
retried method calls; or, if one shared transaction for every attempt is intended, set
`quarkus.resilience4cata.allow-retry-inside-transaction=true`. `NOT_SUPPORTED` and `NEVER` are not reported.

### R4C-QK-003

**Trigger:** a bean implements two or more annotated interfaces, none of which extends all the others. The
generated wrapper of an interface implements that interface only, so the bean can be injected through one
wrapper only.

**Fix:** declare one interface that extends the others and implement that one, or annotate the class instead.
To wrap the bean yourself, list its class in `quarkus.resilience4cata.beans.exclude` and return
`Resilient.of(...)` from a producer method.

### R4C-QK-004

**Trigger:** an annotated class declares producer methods or fields (`@Produces`). The generated subclass
replaces the class as the bean, and CDI producers are not inherited, so they would disappear.

**Fix:** move the producers to another bean, or list the class in `quarkus.resilience4cata.beans.exclude`.

### R4C-QK-005

**Trigger:** an annotated class is a normal-scoped bean (`@ApplicationScoped`, `@RequestScoped`,
`@SessionScoped`) and has no non-private constructor without parameters. The client proxy that Quarkus puts in
front of the generated subclass needs one, and Quarkus can add it to the subclass only if the class has one.

```text
[R4C-QK-005] com.acme.OrderService is a normal-scoped bean (@ApplicationScoped) with resilience4cata annotations, but has no non-private constructor without parameters. ...
```

**Fix:** add a constructor without parameters (package-private is enough: only the proxy uses it), or use
field injection instead of constructor injection, or make the bean `@Singleton`, which has no client proxy.

### R4C-QK-006

**Trigger:** a bean protected by resilience4cata has a lookup condition (`@LookupIfProperty`,
`@LookupUnlessProperty`): an annotated class, or a bean implementing an annotated interface. The bean the application
gets is the generated subclass or the interface wrapper, and Quarkus reads lookup conditions only on the original
class, so the bean would stay resolvable whatever the property says.

```text
[R4C-QK-006] Beans protected by resilience4cata have lookup conditions (@LookupIfProperty, @LookupUnlessProperty): com.acme.BetaFeature. ...
```

**Fix:** use a build-time condition instead (`@IfBuildProperty`, `@IfBuildProfile` and their `Unless` forms), which
works on annotated classes, or check the property in the code that looks the bean up.

### R4C-QK-007

**Trigger:** a Quarkus REST resource (a class with `@Path`, or one implementing an interface with `@Path`) is an
annotated class. Quarkus REST creates resource classes itself, so the generated subclass would never serve the
endpoints and the policies would not run.

```text
[R4C-QK-007] REST resource classes (@Path) have resilience4cata annotations: com.acme.OrdersResource. ...
```

**Fix:** move the protected calls to a bean that the resource injects (an annotated class, or a bean implementing an
annotated interface), or protect the REST client interface the resource calls.

## Tests: testkit (R4C-TK)

`ManualClock` and `ResilienceTesting` report misuse in tests with these codes.

### R4C-TK-001

**Trigger:** `ManualClock.advance(…)` with a negative duration (`IllegalArgumentException`). A clock only moves
forward.

**Fix:** pass a duration of zero or more. To start a test at another time, create the clock with that time:
`new ManualClock(startNanos)`, or `@ResilienceTest(startNanos = …)` with the JUnit extension.

### R4C-TK-002

**Trigger:** `ManualClock.advanceUntilIdle()` gave up after 100,000 tasks in a row
(`IllegalStateException`): every task it ran scheduled another one, so the clock would never be idle. Usually a
retry that keeps failing with no limit in time, or a test helper that reschedules itself.

**Fix:** advance to a fixed time with `clock.advance(…)` instead, or give the retry a limit (`maxAttempts`,
`maxDuration`) so that the tasks end.

### R4C-TK-003

**Trigger:** `ResilienceTesting.elapseWaitInOpen(breaker, clock)` on a circuit breaker that is not `OPEN`
(`IllegalStateException`); the message says which state it is in.

**Fix:** call it once the breaker opened: check that the failures recorded so far reach `minimumCalls` and
`failureRateThreshold`. To test the open state without failures, use `breaker.forceOpen()`.

## Tests: JUnit extension (R4C-JU)

`@ResilienceTest` (`ResilienceExtension`) reports misuse with these codes; JUnit shows them as the failure of
the test or class.

### R4C-JU-001

**Trigger:** a `ManualClock` or `ResilienceRegistry` parameter on a constructor or a `@BeforeAll` method
(`ParameterResolutionException`). The extension creates a new clock and registry for each test, so there is
none before the first test starts.

**Fix:** take them as parameters of the test method or of a `@BeforeEach` method.

### R4C-JU-002

**Trigger:** a `@ConfigureRegistry` method with another signature (`ExtensionConfigurationException`; JUnit 5
wraps it in a `ParameterResolutionException`).

**Fix:** make it a non-private `void` method with one `ResilienceRegistry.Builder` parameter, static or not:
`void configure(ResilienceRegistry.Builder builder)`.

### R4C-JU-003

**Trigger:** with `@ResilienceTest(failOnPendingTasks = true)`, a test passed but left tasks on its clock
(`AssertionError`): an async retry, wait or timeout that never ran. The message lists their delays.

**Fix:** advance the clock past them (`clock.advanceUntilIdle()`, or `clock.advance(…)` far enough), or set
`failOnPendingTasks = false` if leaving them is intended.
