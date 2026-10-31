package dev.resilience4cata.event;

/**
 * Events of a hedge. A call whose first attempt succeeds before the delay publishes nothing.
 */
public sealed interface HedgeEvent extends ResilienceEvent {

    /**
     * A hedge started: attempt {@code attempt} (2 or more) of a call is now in flight next to the others.
     *
     * @param id the hedge's id
     * @param nanoTime when the attempt started
     * @param attempt the number of the attempt that started, from 2
     * @param reason what started it
     */
    record Hedged(String id, long nanoTime, int attempt, Reason reason) implements HedgeEvent {
    }

    /**
     * A hedge won: attempt {@code attempt} (2 or more) succeeded first, and the other attempts were
     * cancelled. Not published when the first attempt wins.
     *
     * @param id the hedge's id
     * @param nanoTime when the winning attempt completed
     * @param attempt the number of the attempt that won, from 2
     */
    record Won(String id, long nanoTime, int attempt) implements HedgeEvent {
    }

    /**
     * A hedge that would have started did not: from now on, this call waits for the attempts already in flight.
     *
     * @param id the hedge's id
     * @param nanoTime when the hedge was skipped
     * @param attempt the number of the attempt that was not started, from 2
     * @param reason why it was not started
     */
    record Skipped(String id, long nanoTime, int attempt, SkipReason reason) implements HedgeEvent {
    }

    /**
     * The call failed: every attempt started failed, or one failed with a failure that ends the call. The
     * failure reaches the caller (or the fallback). Not published when the hedge is disabled.
     *
     * @param id the hedge's id
     * @param nanoTime when the call failed
     * @param attempts number of attempts started, including the first one
     * @param failure the failure the call fails with: the last one
     */
    record Failed(String id, long nanoTime, int attempts, Throwable failure) implements HedgeEvent {
    }

    /**
     * What started a hedge. Constants may be added in minor versions: keep a {@code default} branch in a {@code
     * switch} over it.
     */
    enum Reason {
        /** The attempts in flight produced no result within {@code delay}. */
        DELAY,
        /** An attempt failed with a failure listed in {@code hedgeOn}. */
        FAILURE
    }

    /**
     * Why a hedge was not started. Constants may be added in minor versions: keep a {@code default} branch in a
     * {@code switch} over it.
     */
    enum SkipReason {
        /**
         * The instance's hedge budget is exhausted: hedges in the last {@code budgetWindow} already reached
         * {@code budgetMinHedgesPerSecond} per second plus {@code budgetPercent} of the calls.
         */
        BUDGET_EXHAUSTED,
        /**
         * An attempt was rejected by a policy inside the hedge (an open circuit breaker, a full bulkhead, a
         * rate limit, a full adaptive limit): the dependency has no capacity for more attempts.
         */
        REJECTED,
        /** An attempt failed with a failure in {@code abortOn}, or not in {@code hedgeOn}: the call ends. */
        ABORTED
    }
}
