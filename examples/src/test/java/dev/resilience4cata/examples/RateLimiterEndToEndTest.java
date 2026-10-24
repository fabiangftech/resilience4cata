package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.CircuitBreakerHandle;
import dev.resilience4cata.RateLimitExceededException;
import dev.resilience4cata.RateLimiterHandle;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RateLimiterEndToEndTest {

    private static final String PREFIX = "dev.resilience4cata.examples.QuoteClient#";

    private final ResilienceRegistry registry = ResilienceRegistry.builder().build();
    private final QuoteClient client = Resilient.of(QuoteClient.class, new QuoteClient() {
        @Override
        public String quote(String symbol) {
            return "quote " + symbol;
        }

        @Override
        public String history(String symbol) {
            return "history " + symbol;
        }

        @Override
        public String news(String symbol) {
            return "news " + symbol;
        }

        @Override
        public String paced(String symbol) {
            return "paced " + symbol;
        }

        @Override
        public String guarded(String symbol) {
            return "guarded " + symbol;
        }
    }, registry);

    @Test
    void rejectsCallsBeyondThePermits() {
        for (int i = 0; i < 3; i++) {
            assertThat(client.quote("ACME")).isEqualTo("quote ACME");
        }
        assertThatThrownBy(() -> client.quote("ACME"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("QuoteClient#quote(java.lang.String)");
        RateLimiterHandle limiter = registry.rateLimiter(PREFIX + "quote(java.lang.String)");
        assertThat(limiter.availablePermits()).isZero();
        assertThat(limiter.config().permits()).isEqualTo(3);
    }

    @Test
    void methodsWithTheSameNameShareOneRateLimiter() {
        assertThat(client.history("ACME")).isEqualTo("history ACME");
        assertThat(client.news("ACME")).isEqualTo("news ACME");
        assertThatThrownBy(() -> client.history("ACME")).isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("'market-api'");
        assertThatThrownBy(() -> client.news("ACME")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void waitsForTheNextPermitWithinMaxWait() {
        assertThat(client.paced("A")).isEqualTo("paced A");
        long start = System.nanoTime();
        assertThat(client.paced("B")).isEqualTo("paced B");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(50L, 2_000L);
    }

    @Test
    void callsRejectedByTheBreakerUseNoPermit() {
        CircuitBreakerHandle breaker = registry.circuitBreaker(PREFIX + "guarded(java.lang.String)");
        breaker.forceOpen();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.guarded("ACME")).isInstanceOf(CallNotPermittedException.class);
        }
        assertThat(registry.rateLimiter(PREFIX + "guarded(java.lang.String)").availablePermits()).isEqualTo(1);

        breaker.reset();
        assertThat(client.guarded("ACME")).isEqualTo("guarded ACME");
        assertThatThrownBy(() -> client.guarded("ACME")).isInstanceOf(RateLimitExceededException.class);
    }
}
