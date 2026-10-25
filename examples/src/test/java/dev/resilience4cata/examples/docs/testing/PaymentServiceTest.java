package dev.resilience4cata.examples.docs.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.junit.ConfigureRegistry;
import dev.resilience4cata.junit.ResilienceTest;
import dev.resilience4cata.testkit.ManualClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** {@code docs/en/guides/testing.md}: configuring the registry with {@code @ConfigureRegistry}. */
// tag::testing-junit-configure[]
@ResilienceTest
class PaymentServiceTest {

    private final List<RetryEvent.Retrying> retries = new CopyOnWriteArrayList<>();

    @ConfigureRegistry
    void configure(ResilienceRegistry.Builder builder) { // runs before the registry is built, once per test
        builder.retry("payments", r -> r.maxAttempts(3).delay(Duration.ofSeconds(2))
                        .backoff(RetryConfig.Backoff.FIXED).jitter(RetryConfig.Jitter.NONE))
                .listen(RetryEvent.Retrying.class, retries::add);
    }

    @Test
    void usesTheNamedConfig(ManualClock clock, ResilienceRegistry registry) {
        PaymentService payments = Resilient.of(PaymentService.class, order -> {
            throw new IllegalStateException("card declined");
        }, registry);

        assertThatThrownBy(() -> payments.charge("o-1")).isInstanceOf(IllegalStateException.class);

        assertThat(retries).hasSize(2);
        assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(4)); // two 2 s delays
    }
}
// end::testing-junit-configure[]
