package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The programmatic API on a class that cannot be annotated: every policy and a fallback, sync and async. */
class PipelineExampleTest {

    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .retry("external", r -> r.maxAttempts(3).delay(Duration.ofMillis(1)))
            .circuitBreaker("external", cb -> cb.windowSize(20).minimumCalls(10).waitInOpen(Duration.ofSeconds(30)))
            .build();

    private final Pipeline<String> charge = Pipeline.builder(registry, "payments", String.class)
            .retry("external")
            .circuitBreaker("external")
            .rateLimiter(rl -> rl.permits(100).period(Duration.ofSeconds(1)))
            .timeout(t -> t.timeout(Duration.ofSeconds(2)))
            .bulkhead(b -> b.maxConcurrent(20))
            .fallbackHandler(context -> "pending (" + context.attempts() + " attempts)")
            .build();

    @Test
    void protectsSyncCalls() throws IOException {
        PipelineLegacyGateway gateway = new PipelineLegacyGateway(call -> call == 1);

        assertThat(charge.execute(() -> gateway.charge("A-1"))).isEqualTo("receipt A-1 (call 2)");

        PipelineLegacyGateway down = new PipelineLegacyGateway(call -> true);
        assertThat(charge.execute(() -> down.charge("A-2"))).isEqualTo("pending (3 attempts)");
        assertThat(registry.circuitBreaker("payments").state()).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    void protectsAsyncCalls() throws Exception {
        PipelineLegacyGateway gateway = new PipelineLegacyGateway(call -> call <= 2);

        assertThat(charge.executeAsync(() -> gateway.chargeAsync("B-1")).get(5, TimeUnit.SECONDS))
                .isEqualTo("receipt B-1 (call 3)");

        PipelineLegacyGateway down = new PipelineLegacyGateway(call -> true);
        assertThat(charge.executeAsync(() -> down.chargeAsync("B-2")).get(5, TimeUnit.SECONDS))
                .isEqualTo("pending (3 attempts)");
        assertThat(registry.bulkhead("payments").concurrentCalls()).isZero();
    }
}
