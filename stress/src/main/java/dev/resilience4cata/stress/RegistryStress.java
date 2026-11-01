package dev.resilience4cata.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * {@link ResilienceRegistry}: two pipelines with one id built at the same time share one instance, and
 * {@link ResilienceRegistry#onInstanceCreated} actions run once for it. Public API only.
 */
public final class RegistryStress {

    private RegistryStress() {}

    /**
     * Results: creation actions run, circuit breakers in the snapshot, and whether the two pipelines
     * share one breaker (1: a failure through one is seen by the other's breaker, which opens).
     */
    @JCStressTest
    @Outcome(id = "1, 1, 1", expect = ACCEPTABLE, desc = "One instance, announced once, shared.")
    @Outcome(id = "2, .*", expect = FORBIDDEN, desc = "The creation was announced twice.")
    @Outcome(expect = FORBIDDEN, desc = "Two instances for one id, or none.")
    @State
    public static class ConcurrentFirstCreation {
        final ResilienceRegistry registry = ResilienceRegistry.builder().build();
        final AtomicInteger created = new AtomicInteger();
        volatile Pipeline<String> first;
        volatile Pipeline<String> second;

        public ConcurrentFirstCreation() {
            registry.onInstanceCreated(created::incrementAndGet);
        }

        static Pipeline<String> build(ResilienceRegistry registry) {
            return Pipeline.<String>builder(registry, "shared")
                    .circuitBreaker(b -> b.windowSize(1).minimumCalls(1).failureRateThreshold(100)).build();
        }

        @Actor
        public void build1() {
            first = build(registry);
        }

        @Actor
        public void build2() {
            second = build(registry);
        }

        @Arbiter
        public void after(III_Result r) {
            r.r1 = created.get();
            r.r2 = registry.snapshot().circuitBreakers().size();
            try {
                first.execute(() -> {
                    throw new IllegalStateException("stress");
                });
            } catch (IllegalStateException expected) {
                // the failure opens the shared breaker
            }
            boolean rejected;
            try {
                second.execute(() -> "ok");
                rejected = false;
            } catch (dev.resilience4cata.CallNotPermittedException e) {
                rejected = true;
            }
            r.r3 = rejected ? 1 : 0;
        }
    }
}
