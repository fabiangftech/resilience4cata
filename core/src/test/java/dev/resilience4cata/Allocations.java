package dev.resilience4cata;

import java.lang.management.ManagementFactory;

/**
 * Measures what a warmed-up code path allocates on the current thread. One measurement right after a
 * warm-up is not enough on a slow machine (a CI runner with two cores): until the JIT has compiled the path,
 * escape analysis has not removed its temporary objects. So the batch is measured in rounds until one stays
 * under {@link #LIMIT}, at most {@link #MAX_ROUNDS} times; a path that really allocates exceeds the limit in
 * every round.
 */
final class Allocations {

    /** Bytes a round may allocate: the measurement itself can allocate a little. */
    static final long LIMIT = 1024;
    static final int MAX_ROUNDS = 20;

    /**
     * @param bytes the fewest bytes a round allocated
     * @param rounds how many rounds ran
     */
    record Measurement(long bytes, int rounds) {
    }

    private Allocations() {
    }

    static Measurement steadyState(Runnable batch) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long best = Long.MAX_VALUE;
        int rounds = 0;
        while (rounds < MAX_ROUNDS && best >= LIMIT) {
            rounds++;
            long before = threads.getThreadAllocatedBytes(thread);
            batch.run();
            best = Math.min(best, threads.getThreadAllocatedBytes(thread) - before);
        }
        return new Measurement(best, rounds);
    }
}
