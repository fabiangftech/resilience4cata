package dev.resilience4cata;

import org.jspecify.annotations.Nullable;

/**
 * One hedge instance. Hedges keep no per-call counters, so only the configuration and the hedge budget's
 * current window are shown; how often hedges start and win is observable through
 * {@link dev.resilience4cata.event.HedgeEvent events}.
 *
 * @param id the hedge's id
 * @param config its resolved configuration
 * @param budget the hedge budget's current window; {@code null} without a budget ({@link HedgeConfig#hasBudget()})
 */
public record HedgeSnapshot(String id, HedgeConfig config, @Nullable Budget budget) {

    /**
     * The window of a hedge budget, read when the snapshot was taken. The three counts are read one after
     * the other while calls go on, so they are approximate.
     *
     * @param calls calls counted in the window
     * @param hedges hedges started in the window
     * @param allowedHedges hedges the budget allows in the window with these calls; once {@code hedges}
     *     reaches it, no further hedge starts and calls wait for the attempts in flight
     */
    public record Budget(long calls, long hedges, long allowedHedges) {
    }
}
