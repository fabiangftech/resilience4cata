# Bundles

A bundle is an annotation of your own that carries resilience annotations, so a team writes `@RemoteCall` once instead of repeating four annotations on every method.

## Define a bundle

Mark the annotation with `@ResiliencePolicy`, give it `CLASS` retention and the `METHOD` and `TYPE` targets, and put the policy annotations on it:

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

Without `@ResiliencePolicy`, the processor would ignore the policies on the annotation, so it reports [`R4C-CMP-001`](../errors.md#r4c-cmp-001) instead.

## Use it

On a type, a bundle (like any resilience annotation) applies to every method; on a method, to that method only. Annotations written directly on the element beat the bundle, attribute by attribute:

<!-- snippet: bundles-usage -->
```java
@RemoteCall
public interface ShippingClient {

    String track(String parcel) throws IOException;

    @Retry(maxAttempts = 1)   // overrides one attribute of the bundle: never retried
    String cancel(String parcel) throws IOException;
}
```

`track` gets the whole bundle. `cancel` gets the bundle too, except `maxAttempts`, which its own `@Retry` sets to 1; its `config = "external"` still comes from the bundle.

## Bundles and named configs

A bundle that sets `config = "…"` instead of literal values keeps the numbers out of the code: the registry, or [external configuration](configuration.md), defines what `external` means, and operators can change it without recompiling.

<!-- snippet: bundles-registry -->
```java
ResilienceRegistry registry = ResilienceRegistry.builder()
        .retryDefaults(r -> r.delay(Duration.ofMillis(100)))
        .retry("external", r -> r.maxAttempts(5))
        .circuitBreaker("external", cb -> cb.failureRateThreshold(25).waitInOpen(Duration.ofSeconds(30)))
        .build();
```

With this registry, `track` makes up to 5 attempts 100 ms apart (before jitter) and its breaker opens at a 25% failure rate. A config name the registry does not know fails when the interface is wrapped ([`R4C-REG-004`](../errors.md#r4c-reg-004)), not on the first call.

## Bundles of bundles

A bundle may carry other bundles. Its own policy annotations beat the ones it inherits, attribute by attribute:

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

A method annotated `@Idempotent` gets `RemoteCall`'s breaker and two-second timeout, and a retry with `config = "external"` and 5 attempts.

## Precedence

For each attribute, lowest precedence first:

1. The built-in and registry defaults, then the named config picked with `config = "…"` ([where configuration comes from](../start/how-it-works.md#where-configuration-comes-from)).
2. Type-level annotations: those of the interface that declares the method (when it is a superinterface), then those of the interface you wrap.
3. Method-level annotations.

On any one element, a direct annotation beats every bundle, and a bundle beats the bundles it carries. A method-level bundle beats a type-level annotation, even a direct one, because the method is the more specific element. For [annotated classes](annotated-classes.md#annotations-on-the-class-its-superclasses-and-its-interfaces), the class and its superclasses add their levels above the interface's.

Per-instance overrides (`instances.<id>.*` and `retryInstance(...)`) apply after all of these.

## Two bundles that disagree

If two bundles on the same element set the same attribute to different values, neither can win, so the build fails with [`R4C-CMP-002`](../errors.md#r4c-cmp-002). There is no silent "last one wins":

<!-- not-compiled: a deliberate compile error (R4C-CMP-002) -->
```java
public interface PriceClient {
    @Fast       // @Retry(maxAttempts = 2)
    @Patient    // @Retry(maxAttempts = 5)
    String price(String sku);
}
```

Keep one of them, make them agree, or write the attribute directly on the method, which beats both.
