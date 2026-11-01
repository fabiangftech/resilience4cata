package dev.resilience4cata;

/**
 * State of one circuit breaker at one moment. Obtained from {@link CircuitBreakerHandle#snapshot()} or
 * {@link ResilienceRegistry#snapshot()}.
 *
 * <p>Calls are those in the current window: the sliding window while closed, the probes while half
 * open, none while open, forced open or disabled. The rates are percentages from 0 to 100 over those
 * calls, or {@code -1} until the window holds {@code minimumCalls} calls ({@code halfOpenCalls} while
 * half open), the point from which the breaker itself evaluates them. The slow-call rate is also
 * {@code -1} when slow-call detection is off.
 *
 * @param id the breaker's id
 * @param state the state
 * @param failureRate percentage of failed calls, or {@code -1}
 * @param slowCallRate percentage of slow calls, or {@code -1}
 * @param bufferedCalls calls in the window
 * @param failedCalls failed calls in the window, at most {@code bufferedCalls}
 * @param slowCalls slow calls in the window, at most {@code bufferedCalls}
 */
public record CircuitBreakerSnapshot(String id, CircuitBreakerState state, double failureRate,
        double slowCallRate, int bufferedCalls, int failedCalls, int slowCalls) {
}
