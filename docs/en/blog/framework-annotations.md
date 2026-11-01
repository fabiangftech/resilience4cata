# What a resilience annotation costs in Spring, Micronaut and Quarkus

In each of the three frameworks, a protected bean costs what its policy costs, a few nanoseconds; the same annotations through each framework's usual interceptors cost from tens of nanoseconds to several microseconds per call.

*1 November 2026 · by [fabiangftech](https://github.com/fabiangftech), author of resilience4cata*

## The question

The [first article](compile-time-resilience.md) compared policies called directly: a generated class against Resilience4j's decorators. Few applications call a policy directly. They put an annotation on a bean method and let the framework do the rest: Spring through an aspect, Micronaut through an interceptor, Quarkus through a CDI interceptor such as SmallRye Fault Tolerance's. That machinery runs on every call, the successful ones included, so I wanted to measure what an application actually gets.

## How it was measured

The benchmarks start a real application context and call the bean the framework injects, from one and from four threads:

- **Spring Boot 4.1.2**: resilience4j-spring-boot4 2.4.0's aspects against resilience4cata's generated subclass, which the Spring integration registers as the bean.
- **Micronaut 5.2.2** on JDK 25: resilience4j-micronaut 2.4.0's interceptors and Micronaut's own `@Retryable` and `@CircuitBreaker`, against the generated subclass.
- **Quarkus 3.40.1**: SmallRye Fault Tolerance's annotations, and Resilience4j decorators built once in a bean and called by hand, with no interceptor at all, which is Resilience4j's best case there.

The settings are the same as in the core benchmarks: a retry of three attempts without delay, a circuit breaker with a window of 100 calls, a bulkhead of 1000 calls that never fills, and a rate limiter that never runs out. A fast benchmark can also mean an interceptor that never ran, so each setup first checks that every Resilience4j instance takes part in its call. The breaker counted the call, a full bulkhead rejects it, a drained limiter rejects it, and an open breaker rejects it.

On the resilience4cata side nothing is framework-specific. The class you annotate is compiled with a generated subclass next to it, and the integration makes that subclass the bean:

<!-- not-compiled: a short illustration; the Spring, Micronaut and Quarkus guides have the tested beans -->
```java
@Service
public class Inventory {

    @Retry(maxAttempts = 3)
    @CircuitBreaker
    public int stock(String sku) {
        return warehouse.stock(sku);
    }
}
```

The bean is `Inventory$Resilient`, whose `stock` override is plain code calling `super.stock(sku)`. There is no proxy and no interceptor chain in between.

## The numbers

> [!MEASURED]
> AWS `c7g.xlarge` (Graviton3), 3 forks, ns per call on one thread; resilience4cata allocates 0 B in every row. The fastest rival in each framework is shown: Resilience4j's interceptors in Spring and Micronaut, hand-called Resilience4j decorators or Fault Tolerance in Quarkus. From [Framework beans and a full comparison, 0.13.0](../benchmarks.md#frameworks).

| Call | Spring: resilience4cata | Spring: Resilience4j | Micronaut: resilience4cata | Micronaut: Resilience4j | Quarkus: resilience4cata | Quarkus: fastest rival |
|---|---:|---:|---:|---:|---:|---:|
| retry, first attempt succeeds | **1.1** | 1346.0 | **1.1** | 104.8 | **1.1** | 22.9 |
| circuit breaker | **1.5** | 1737.0 | **1.5** | 304.5 | **1.5** | 101.6 |
| open circuit breaker | **2.3** | 4174.2 | **2.3** | 1127.4 | **2.3** | 355.9 |
| rate limiter | **42.0** | 1481.8 | **42.0** | 141.9 | **42.0** | 66.7 |
| bulkhead | **20.4** | 1687.7 | **20.7** | 490.2 | 20.4 | **20.3** |
| retry + breaker + rate limiter + bulkhead | **62.1** | 6104.4 | **62.1** | 1117.9 | **62.0** | 217.2 |

With four threads the gaps grow, because the shared state behind the interceptors starts to contend. The Spring circuit breaker goes from 1.4 ns against 2142 ns, and the Micronaut one from 1.5 ns against 1501 ns.

## Where the time goes

The resilience4cata column is the same in the three frameworks, and the same as in the core benchmarks. The framework is not on the call path at all. The rival columns differ by framework because each one pays for a different kind of interception:

- **Spring:** resilience4j-spring-boot4's aspects cost 1.3 to 6 µs and 1.6 to 6.3 KB per call. Their pointcut is dynamic, and evaluating it loads a class by name (`Class.forName`) on every call.
- **Micronaut:** its compile-time AOP is much cheaper, 100 ns to 1.1 µs for resilience4j-micronaut's interceptors. Micronaut's own `@Retryable` and `@CircuitBreaker` cost 1.0 to 1.8 µs and 2.3 to 3.6 KB.
- **Quarkus:** Fault Tolerance's interceptors cost 0.3 to 1.9 µs per call. Resilience4j's decorators, called by hand with no interceptor, are the fastest rival there. It is also the only case in the table that resilience4cata does not win: a bulkhead with free slots, 20.4 ns against 20.3 ns. Both take and give back a permit with two atomic operations, and that is as low as the hardware goes.

## The cases that were not wins

The same run repeated every core benchmark against the fastest Resilience4j variant. Out of 156 comparable cases, framework ones included, resilience4cata won 150. I said I would show the ties and the losses, so here are the other six:

- **A hedge with a 1 ms delay lost by 2×**, on one thread and on four: 2.1 ms against 1.07 ms. The shared timer works in ticks of a sixteenth of the delay, at least 1 ms, and a delay could start up to about two ticks late. Hedges now count their delay from the call's start, at the cost of one clock read, and the timer wakes up at the deadline. The hedge fires in 1060 µs against Resilience4j's 1067 µs ([Hedge delays on time](../benchmarks.md#hedge-precision)). Neither library can go much lower, because both wait for the operating system to wake a thread.
- **Two bulkhead cases on one thread are ties at the hardware's floor**: the Quarkus one above, and the same bulkhead called directly (20.5 ns against 20.4 ns).
- **Two `Flux` cases are within the error**: the bulkhead with four threads and the rate limiter with one, where resilience4cata allocates more per stream than resilience4j-reactor's operators. That one is being worked on.

Losing a benchmark was useful. The hedge's lateness had been in the documentation since 0.4.0 ("up to about two ticks late"), but only a comparison with a precise timer made it look like what it was.

## Caveats

- **Microseconds matter only next to cheap calls.** A remote call that takes 20 ms will not notice 2 µs of interception. A service that protects in-memory caches, local calls or millions of calls per second will.
- **These are microbenchmarks** on one machine type, with every policy configured never to reject. The [benchmarks page](../benchmarks.md) has the method, the fairness notes and every number, ties and losses included.
- **The bean is a subclass.** With annotated classes, a class-based proxy such as Spring's `@Transactional` sits outside the policies, so one transaction spans every retry. Each integration refuses to start when a retried method joins a transaction, and the guides explain how to choose.

## Try it

Add the integration for your framework and the annotation processor. The [Spring Boot](../guides/spring.md), [Micronaut](../guides/micronaut.md) and [Quarkus](../guides/quarkus.md) guides have the dependencies for Gradle and Maven and a tested example. Coming from Resilience4j, the [migration guide](../migrate/from-resilience4j.md) maps every annotation and property. The code is Apache-2.0 licensed on [GitHub](https://github.com/fabiangftech/resilience4cata).
