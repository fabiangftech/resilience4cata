package dev.resilience4cata.generated;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.CircuitBreakerConfig;
import dev.resilience4cata.CircuitBreakerState;
import dev.resilience4cata.event.CircuitBreakerEvent;
import dev.resilience4cata.event.EventListener;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Circuit breaker events under races: every transition is published exactly once, by the thread whose
 * compare-and-set made it, however many threads tried. In package {@code generated} for the fixed
 * clock of {@link PolicyEnvironment#forTest}.
 */
public final class EventStress {

    static final Exception FAILURE = new Exception("stress");

    private EventStress() {}

    /** Counts transitions; also remembers the number of transitions into OPEN. */
    static final class Counter implements EventListener<CircuitBreakerEvent.StateTransition> {
        final AtomicInteger transitions = new AtomicInteger();
        final AtomicInteger intoOpen = new AtomicInteger();

        @Override
        public void onEvent(CircuitBreakerEvent.StateTransition event) {
            transitions.incrementAndGet();
            if (event.to() == CircuitBreakerState.OPEN) {
                intoOpen.incrementAndGet();
            }
        }
    }

    static CircuitBreakerPolicy breaker(CircuitBreakerConfig.Builder config, Counter counter, long[] clock) {
        EventDispatcher events = EventDispatcher.of(List.of(
                new EventDispatcher.Subscription(CircuitBreakerEvent.StateTransition.class, null, counter)));
        PolicyEnvironment environment = PolicyEnvironment.forTest(() -> clock[0], nanos -> { }, events);
        return new CircuitBreakerPolicy("stress", config.build(), environment);
    }

    static void fail(CircuitBreakerPolicy breaker) {
        long token = breaker.acquire();
        if (token >= 0) {
            breaker.onFailure(token, breaker.startTimer(), FAILURE);
        }
    }

    /**
     * Two failures fill a window of two: both threads may evaluate the window and try to open the
     * breaker, but only one transition happens and only one event is published. Results: transitions
     * published, transitions into OPEN.
     */
    @JCStressTest
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Opened once, published once.")
    @Outcome(id = "2, 2", expect = FORBIDDEN, desc = "One transition published twice.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class ConcurrentFailuresPublishOneTransition {
        final Counter counter = new Counter();
        final CircuitBreakerPolicy breaker = breaker(CircuitBreakerConfig.builder()
                .windowSize(2).minimumCalls(2).failureRateThreshold(100f), counter, new long[] {0});

        @Actor
        public void failure1() {
            fail(breaker);
        }

        @Actor
        public void failure2() {
            fail(breaker);
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r1 = counter.transitions.get();
            r.r2 = counter.intoOpen.get();
        }
    }

    /**
     * The wait in the open state is over and two calls arrive: both see the open phase and try to move
     * it to half open. Results: transitions published after the setup's CLOSED→OPEN, and whether the
     * breaker is half open (1).
     */
    @JCStressTest
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "OPEN→HALF_OPEN published once.")
    @Outcome(id = "2, 1", expect = FORBIDDEN, desc = "One transition published twice.")
    @Outcome(expect = FORBIDDEN, desc = "Unexpected.")
    @State
    public static class ConcurrentProbesPublishOneTransition {
        final long[] clock = {0};
        final Counter counter = new Counter();
        final CircuitBreakerPolicy breaker;

        public ConcurrentProbesPublishOneTransition() {
            breaker = breaker(CircuitBreakerConfig.builder().windowSize(1).minimumCalls(1)
                    .waitInOpen(Duration.ofNanos(10)).halfOpenCalls(2), counter, clock);
            fail(breaker); // opens at time 0
            clock[0] = 10; // the wait is over; published to the actors by the jcstress state handoff
            counter.transitions.set(0);
        }

        @Actor
        public void probe1() {
            breaker.acquire();
        }

        @Actor
        public void probe2() {
            breaker.acquire();
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r1 = counter.transitions.get();
            r.r2 = breaker.state() == CircuitBreakerState.HALF_OPEN ? 1 : 0;
        }
    }
}
