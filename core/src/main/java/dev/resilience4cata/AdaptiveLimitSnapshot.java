package dev.resilience4cata;

import java.time.Duration;
import java.util.Objects;

/**
 * State of one adaptive limit at one moment. Obtained from {@link AdaptiveLimitHandle#snapshot()} or
 * {@link ResilienceRegistry#snapshot()}.
 *
 * @param id the adaptive limit's id
 * @param algorithm how the limit is recomputed
 * @param limit the current limit, between {@code minLimit} and {@code maxLimit}
 * @param concurrentCalls calls running inside the adaptive limit, between 0 and {@code maxLimit}
 * @param minLimit the configured lowest limit
 * @param maxLimit the configured highest limit
 * @param windows windows ended so far: how many times the limit was recomputed
 * @param windowLatency average latency of the calls of the last window that ended; {@link Duration#ZERO}
 *     before the first one
 * @param longTermLatency {@link AdaptiveLimitConfig.Algorithm#GRADIENT2}: the long-term average latency the
 *     windows are compared with; {@link Duration#ZERO} before the first window, and always with
 *     {@link AdaptiveLimitConfig.Algorithm#AIMD}
 */
public record AdaptiveLimitSnapshot(String id, AdaptiveLimitConfig.Algorithm algorithm, int limit, int concurrentCalls,
        int minLimit, int maxLimit, long windows, Duration windowLatency, Duration longTermLatency) {

    /** Checks that nothing is null. */
    public AdaptiveLimitSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(windowLatency, "windowLatency");
        Objects.requireNonNull(longTermLatency, "longTermLatency");
    }

    /** Free slots: {@code limit - concurrentCalls}, or 0 when the limit just went below the calls in flight. */
    public int availableSlots() {
        return Math.max(0, limit - concurrentCalls);
    }
}
