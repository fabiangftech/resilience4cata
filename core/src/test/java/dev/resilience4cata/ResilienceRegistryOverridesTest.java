package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.resilience4cata.generated.RetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Named-config overlays ({@code retryOverlay}, ...) and per-instance overrides ({@code retryInstance}, ...). */
class ResilienceRegistryOverridesTest {

    @Test
    void overlayOverridesTheProgrammaticConfigAttributeByAttribute() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retry("external", r -> r.maxAttempts(5).delay(Duration.ofSeconds(1)))
                .retryOverlay("external", r -> r.maxAttempts(2))
                .build();

        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(2);
        assertThat(registry.retryConfig("external").delay()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void overlayWinsWhateverTheCallOrder() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .circuitBreakerOverlay("external", cb -> cb.minimumCalls(7))
                .circuitBreaker("external", cb -> cb.minimumCalls(30).windowSize(40))
                .build();

        assertThat(registry.circuitBreakerConfig("external").minimumCalls()).isEqualTo(7);
        assertThat(registry.circuitBreakerConfig("external").windowSize()).isEqualTo(40);
    }

    @Test
    void overlayAloneDefinesTheConfigFromTheDefaults() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .timeoutDefaults(t -> t.timeout(Duration.ofSeconds(3)))
                .timeoutOverlay("slow", t -> t.enabled(false))
                .rateLimiterOverlay("api", rl -> rl.permits(9))
                .bulkheadOverlay("pool", b -> b.maxConcurrent(4))
                .build();

        assertThat(registry.timeoutConfig("slow").timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(registry.timeoutConfig("slow").enabled()).isFalse();
        assertThat(registry.rateLimiterConfig("api").permits()).isEqualTo(9);
        assertThat(registry.bulkheadConfig("pool").maxConcurrent()).isEqualTo(4);
    }

    @Test
    void overlaysOfOneNameApplyInCallOrder() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryOverlay("external", r -> r.maxAttempts(2).delay(Duration.ofMillis(5)))
                .retryOverlay("external", r -> r.maxAttempts(3))
                .build();

        assertThat(registry.retryConfig("external").maxAttempts()).isEqualTo(3);
        assertThat(registry.retryConfig("external").delay()).isEqualTo(Duration.ofMillis(5));
    }

    @Test
    void invalidOverlayFailsAtBuild() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder()
                        .retry("bad", r -> r.maxAttempts(2))
                        .retryOverlay("bad", r -> r.maxAttempts(0)).build())
                .withMessageContaining("Invalid retry config 'bad'");
    }

    @Test
    void overlayOfTheDefaultsIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder().retryOverlay("default", r -> { }))
                .withMessageContaining("retryDefaults");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder().bulkheadOverlay("", b -> { }));
    }

    @Test
    void programmaticConfigsRegisteredTwiceStillFail() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder()
                        .retry("external", r -> { })
                        .retry("external", r -> { }))
                .withMessageContaining("registered twice");
    }

    @Test
    void instanceOverridesApplyAfterTheAnnotationValues() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retry("external", r -> r.delay(Duration.ofSeconds(1)).maxAttempts(4))
                .retryInstance("A#m()", r -> r.maxAttempts(9))
                .build();

        RetryPolicy policy = registry.retryPolicy("A#m()", "", "external", r -> r.maxAttempts(6).jitter(
                RetryConfig.Jitter.NONE));

        assertThat(policy.config().maxAttempts()).isEqualTo(9);
        assertThat(policy.config().jitter()).isEqualTo(RetryConfig.Jitter.NONE);
        assertThat(policy.config().delay()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void instanceOverridesApplyWithoutAnnotationValues() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("A#m()", r -> r.maxAttempts(9))
                .build();

        assertThat(registry.retryPolicy("A#m()", "", "", null).config().maxAttempts()).isEqualTo(9);
        assertThat(registry.retryPolicy("B#m()", "", "", null).config()).isEqualTo(RetryConfig.DEFAULTS);
    }

    @Test
    void aNamedInstanceIsAddressedByItsName() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .circuitBreakerInstance("payments", cb -> cb.minimumCalls(3))
                .circuitBreakerInstance("A#m()", cb -> cb.minimumCalls(99))
                .build();

        CircuitBreakerConfig first = registry.circuitBreakerPolicy("A#m()", "payments", "", null).config();
        CircuitBreakerConfig second = registry.circuitBreakerPolicy("B#n()", "payments", "", null).config();

        assertThat(first.minimumCalls()).isEqualTo(3);
        assertThat(second).isEqualTo(first);
        assertThat(registry.circuitBreaker("payments").config().minimumCalls()).isEqualTo(3);
    }

    @Test
    void everyPolicyHasInstanceOverrides() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .rateLimiterInstance("A#m()", rl -> rl.permits(3))
                .timeoutInstance("A#m()", t -> t.timeout(Duration.ofMillis(70)))
                .bulkheadInstance("A#m()", b -> b.maxConcurrent(2))
                .build();

        assertThat(registry.rateLimiterPolicy("A#m()", "", "", rl -> rl.permits(8)).config().permits()).isEqualTo(3);
        assertThat(registry.timeoutPolicy("A#m()", "", "", null).config().timeout()).isEqualTo(Duration.ofMillis(70));
        assertThat(registry.bulkheadPolicy("A#m()", "", "", null).config().maxConcurrent()).isEqualTo(2);
    }

    @Test
    void instanceOverridesOfOneIdApplyInCallOrder() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("A#m()", r -> r.maxAttempts(9).delay(Duration.ZERO))
                .retryInstance("A#m()", r -> r.maxAttempts(2))
                .build();

        RetryConfig config = registry.retryPolicy("A#m()", "", "", null).config();
        assertThat(config.maxAttempts()).isEqualTo(2);
        assertThat(config.delay()).isEqualTo(Duration.ZERO);
    }

    @Test
    void invalidInstanceOverridesNameTheInstance() {
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retryInstance("A#m()", r -> r.delay(Duration.ofSeconds(30)))
                .build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.retryPolicy("A#m()", "", "", r -> r.maxDelay(Duration.ofSeconds(20))))
                .withMessageContaining("Invalid retry config for instance 'A#m()'")
                .withMessageContaining("retryInstance(\"A#m()\", ...)")
                .withMessageContaining("maxDelay");
    }

    @Test
    void emptyInstanceIdIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResilienceRegistry.builder().retryInstance("", r -> { }));
    }
}
