package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** {@code locked = true}: the annotation's (or pipeline steps') values win over per-instance overrides. */
class LockedOverridesTest {

    private static final Consumer<RetryConfig.Builder> FIVE_ATTEMPTS = r -> r.maxAttempts(5);

    @Test
    void lockedValuesBeatInstanceOverridesAndOtherAttributesStayOverridable() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("payments", r -> r.maxAttempts(9).delay(Duration.ofMillis(7)))
                .build();

        RetryConfig config = registry.retryPolicy("A#m()", "payments", "", FIVE_ATTEMPTS, true).config();

        assertThat(config.maxAttempts()).isEqualTo(5);
        assertThat(config.delay()).isEqualTo(Duration.ofMillis(7));
        assertThat(registry.lockedOverrides()).containsExactly(new LockedOverride("retry", "payments", config));
    }

    @Test
    void withoutTheLockInstanceOverridesWinAsBefore() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("payments", r -> r.maxAttempts(9))
                .build();

        assertThat(registry.retryPolicy("A#m()", "payments", "", FIVE_ATTEMPTS, false).config().maxAttempts())
                .isEqualTo(9);
        assertThat(registry.retryPolicy("B#m()", "", "", FIVE_ATTEMPTS).config().maxAttempts()).isEqualTo(5);
        assertThat(registry.lockedOverrides()).isEmpty();
    }

    @Test
    void overridesThatChangeNoLockedValueAreNotListed() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("other", r -> r.delay(Duration.ofMillis(3)))
                .retryInstance("same", r -> r.maxAttempts(5))
                .build();

        assertThat(registry.retryPolicy("A#m()", "other", "", FIVE_ATTEMPTS, true).config().delay())
                .isEqualTo(Duration.ofMillis(3));
        assertThat(registry.retryPolicy("A#n()", "same", "", FIVE_ATTEMPTS, true).config().maxAttempts()).isEqualTo(5);
        assertThat(registry.lockedOverrides()).isEmpty();
    }

    @Test
    void aLockWithoutAnnotationValuesLocksNothing() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("payments", r -> r.maxAttempts(9))
                .build();

        assertThat(registry.retryPolicy("A#m()", "payments", "", null, true).config().maxAttempts()).isEqualTo(9);
        assertThat(registry.lockedOverrides()).isEmpty();
    }

    @Test
    void everyPolicyCanBeLocked() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("r", r -> r.maxAttempts(9))
                .hedgeInstance("h", h -> h.maxAttempts(9))
                .circuitBreakerInstance("cb", cb -> cb.minimumCalls(9))
                .rateLimiterInstance("rl", rl -> rl.permits(9))
                .adaptiveLimitInstance("al", al -> al.maxLimit(90))
                .timeoutInstance("A#t()", t -> t.timeout(Duration.ofSeconds(9)))
                .bulkheadInstance("b", b -> b.maxConcurrent(9))
                .build();

        assertThat(registry.retryPolicy("A#r()", "r", "", r -> r.maxAttempts(2), true).config().maxAttempts())
                .isEqualTo(2);
        assertThat(registry.hedgePolicy("A#h()", "h", "", h -> h.maxAttempts(2), true).config().maxAttempts())
                .isEqualTo(2);
        assertThat(registry.circuitBreakerPolicy("A#cb()", "cb", "", cb -> cb.minimumCalls(2), true).config()
                .minimumCalls()).isEqualTo(2);
        assertThat(registry.rateLimiterPolicy("A#rl()", "rl", "", rl -> rl.permits(2), true).config().permits())
                .isEqualTo(2);
        assertThat(registry.adaptiveLimitPolicy("A#al()", "al", "", al -> al.maxLimit(20), true).config().maxLimit())
                .isEqualTo(20);
        assertThat(registry.timeoutPolicy("A#t()", "", "", t -> t.timeout(Duration.ofSeconds(2)), true).config()
                .timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(registry.bulkheadPolicy("A#b()", "b", "", b -> b.maxConcurrent(2), true).config().maxConcurrent())
                .isEqualTo(2);
        assertThat(registry.lockedOverrides()).extracting(LockedOverride::policy).containsExactly(
                "adaptiveLimit", "bulkhead", "circuitBreaker", "hedge", "rateLimiter", "retry", "timeout");
    }

    @Test
    void aLockCanTurnInvalidOverridesIntoAValidConfiguration() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("payments", r -> r.maxDelay(Duration.ofSeconds(1)))
                .build();

        RetryConfig config = registry.retryPolicy("A#m()", "payments", "",
                r -> r.delay(Duration.ofSeconds(5)).maxDelay(Duration.ofSeconds(10)), true).config();

        assertThat(config.maxDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(registry.lockedOverrides()).hasSize(1);
    }

    @Test
    void anInvalidLockedCombinationNamesTheLock() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("payments", r -> r.maxDelay(Duration.ofSeconds(1)).delay(Duration.ofMillis(1)))
                .build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.retryPolicy("A#m()", "payments", "", r -> r.delay(Duration.ofSeconds(2)), true))
                .withMessageStartingWith("[R4C-REG-")
                .withMessageContaining("maxDelay (PT1S) must not be shorter than delay (PT2S)")
                .withMessageContaining("then the values set in its annotation or pipeline step again (locked)");
    }

    @Test
    void anInstanceSharedByTwoCallSitesIsListedOnce() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("payments", r -> r.maxAttempts(9))
                .build();

        registry.retryPolicy("A#m()", "payments", "", FIVE_ATTEMPTS, true);
        registry.retryPolicy("A#n()", "payments", "", FIVE_ATTEMPTS, true);

        assertThat(registry.lockedOverrides()).hasSize(1);
    }

    @Test
    void aLockedPipelineKeepsItsStepValues() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("payments", r -> r.maxAttempts(9).delay(Duration.ofMillis(7)))
                .circuitBreakerInstance("payments", cb -> cb.minimumCalls(9))
                .build();

        Pipeline.<String>builder(registry, "payments")
                .retry(FIVE_ATTEMPTS)
                .circuitBreaker()
                .locked()
                .build();

        RetryConfig retry = (RetryConfig) registry.lockedOverrides().get(0).config();
        assertThat(retry.maxAttempts()).isEqualTo(5);
        assertThat(retry.delay()).isEqualTo(Duration.ofMillis(7));
        // The circuit breaker step sets nothing, so nothing is locked and the override applies.
        assertThat(registry.lockedOverrides()).extracting(LockedOverride::policy).containsExactly("retry");
        assertThat(registry.circuitBreakerPolicy("payments", "", "", null).config().minimumCalls()).isEqualTo(9);
    }
}
