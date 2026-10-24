package dev.resilience4cata;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.event.ResilienceEvent;
import dev.resilience4cata.event.RetryEvent;
import dev.resilience4cata.generated.CircuitBreakerPolicy;
import dev.resilience4cata.generated.FallbackPolicy;
import dev.resilience4cata.generated.RetryPolicy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The registry's side of events and snapshots: listener registration and {@code snapshot()}. */
class ResilienceSnapshotTest {

    @Test
    void theSnapshotListsEveryInstanceSortedById() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        assertThat(registry.snapshot()).isEqualTo(new ResilienceSnapshot(List.of(), List.of(), List.of(), List.of(), List.of()));

        registry.circuitBreakerPolicy("B#m()", "", "", null);
        registry.circuitBreakerPolicy("A#m()", "", "", null);
        registry.retryPolicy("A#m()", "", "", r -> r.maxAttempts(5));
        registry.rateLimiterPolicy("A#m()", "limiter", "", null);
        registry.timeoutPolicy("A#m()", "", "", t -> t.timeout(Duration.ofSeconds(2)));
        registry.bulkheadPolicy("A#m()", "", "", b -> b.maxConcurrent(3));

        ResilienceSnapshot snapshot = registry.snapshot();
        assertThat(snapshot.circuitBreakers()).extracting(CircuitBreakerSnapshot::instance).containsExactly("A#m()", "B#m()");
        assertThat(snapshot.circuitBreakers().get(0).state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(snapshot.retries()).singleElement().satisfies(r -> {
            assertThat(r.instance()).isEqualTo("A#m()");
            assertThat(r.config().maxAttempts()).isEqualTo(5);
        });
        assertThat(snapshot.rateLimiters()).containsExactly(new RateLimiterSnapshot("limiter", 50, 50));
        assertThat(snapshot.timeouts()).singleElement().satisfies(t -> assertThat(t.config().timeout()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(snapshot.bulkheads()).containsExactly(new BulkheadSnapshot("A#m()", 0, 0, 3));
        assertThat(registry.circuitBreaker("A#m()").snapshot()).isEqualTo(snapshot.circuitBreakers().get(0));
    }

    @Test
    void listenersRegisteredOnTheBuilderReachThePolicies() {
        List<ResilienceEvent> all = new ArrayList<>();
        List<CircuitBreakerEvent.StateTransition> payments = new ArrayList<>();
        ResilienceRegistry registry = ResilienceRegistry.builder()
                .listen(ResilienceEvent.class, all::add)
                .listen("payments", CircuitBreakerEvent.StateTransition.class, payments::add)
                .build();

        CircuitBreakerPolicy other = registry.circuitBreakerPolicy("Other#m()", "", "", null);
        CircuitBreakerPolicy cb = registry.circuitBreakerPolicy("Payments#charge()", "payments", "", null);
        other.forceOpen();
        cb.forceOpen();
        RetryPolicy retry = registry.retryPolicy("A#m()", "", "", r -> r.maxAttempts(1));
        retry.onFailure(1, new IOException(), retry.start());
        FallbackPolicy fallback = registry.fallbackPolicy("A#m()", List.of(Exception.class), List.of());
        fallback.handles(new IOException());

        assertThat(payments).extracting(CircuitBreakerEvent.StateTransition::instance).containsExactly("payments");
        assertThat(all).extracting(e -> e.getClass().getSimpleName()).containsExactly(
                "StateTransition", "StateTransition", "GaveUp", "Applied");
        assertThat(all.get(2)).isInstanceOfSatisfying(RetryEvent.GaveUp.class,
                g -> assertThat(g.reason()).isEqualTo(RetryEvent.Reason.MAX_ATTEMPTS));
        assertThat(all.get(3)).isInstanceOf(FallbackEvent.Applied.class);
    }
}
