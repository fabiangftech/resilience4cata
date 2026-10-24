package dev.resilience4cata.testkit;

import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.ResilienceRegistry;
import java.util.Random;

/**
 * Shortcuts for tests of code protected by resilience4cata.
 *
 * <pre>{@code
 * ManualClock clock = new ManualClock();
 * ResilienceRegistry registry = ResilienceTesting.registry(clock)
 *         .retry("payments", r -> r.maxAttempts(3).delay(Duration.ofSeconds(1)))
 *         .build();
 * Payments payments = Resilient.of(Payments.class, stub, registry);
 *
 * payments.charge(order);                        // two failures, then success: returns at once
 * assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(2));
 * }</pre>
 */
public final class ResilienceTesting {

    /** The seed {@link #registry(ManualClock)} uses for jitter. */
    public static final long DEFAULT_SEED = 42L;

    private ResilienceTesting() {
    }

    /**
     * Returns a registry builder whose policies follow {@code clock} and draw jitter from a
     * {@link Random} seeded with {@link #DEFAULT_SEED}, so that delays are the same on every run.
     * Configure it further like any builder.
     *
     * @param clock the clock the policies follow
     * @return the builder
     */
    public static ResilienceRegistry.Builder registry(ManualClock clock) {
        return registry(clock, DEFAULT_SEED);
    }

    /**
     * Same as {@link #registry(ManualClock)}, with another seed for jitter.
     *
     * @param clock the clock the policies follow
     * @param seed the seed of the jitter's {@link Random}
     * @return the builder
     */
    public static ResilienceRegistry.Builder registry(ManualClock clock, long seed) {
        return ResilienceRegistry.builder().ticker(clock).random(new Random(seed));
    }

    /**
     * Moves {@code clock} past an open breaker's {@code waitInOpen}, so that the next call is let through
     * as a half-open probe. The breaker reports {@link CircuitBreakerState#OPEN} until that call arrives:
     * it moves to half-open when asked for a permit, not on its own.
     *
     * @param breaker an open breaker whose registry follows {@code clock}
     * @param clock the clock
     * @throws IllegalStateException if the breaker is not {@link CircuitBreakerState#OPEN}
     */
    public static void elapseWaitInOpen(CircuitBreakerHandle breaker, ManualClock clock) {
        CircuitBreakerState state = breaker.state();
        if (state != CircuitBreakerState.OPEN) {
            throw new IllegalStateException("Breaker '" + breaker.id() + "' is " + state + ", not OPEN");
        }
        clock.advance(breaker.config().waitInOpen());
    }
}
