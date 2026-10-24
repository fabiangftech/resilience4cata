package dev.resilience4cata.generated;

/** Outcomes recorded by a closed or half-open circuit breaker. */
interface SlidingWindow {

    /** Outcome bit: the call failed. */
    int FAILURE = 2;
    /** Outcome bit: the call was slow. */
    int SLOW = 4;

    /** {@link #record} result bit: the rates may have risen and must be evaluated. */
    int EVALUATE = 1;
    /** {@link #record} result bit: a success changed the counters, so the window may now hold only fast successes. */
    int MAYBE_QUIET = 2;

    /**
     * Records one outcome.
     *
     * @param outcome {@link #FAILURE} and/or {@link #SLOW} bits, or 0 for a fast success
     * @param now the current time in nanoseconds; only read by time-based windows
     * @return {@link #EVALUATE} and/or {@link #MAYBE_QUIET} bits, or 0
     */
    int record(int outcome, long now);

    /** True if at least {@code minimumCalls} are recorded and a rate reaches its threshold. */
    boolean exceeds(int minimumCalls, float failureRateThreshold, float slowCallRateThreshold, boolean timed, long now);

    /**
     * True if one more fast success cannot change anything, so it does not need to be recorded.
     * Volatile reads: used to publish and check the circuit breaker's quiet state.
     */
    boolean onlyFastSuccesses();
}
