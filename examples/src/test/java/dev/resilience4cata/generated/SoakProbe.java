package dev.resilience4cata.generated;

/**
 * Reads the library's internal, JVM-wide bookkeeping for {@code SoakTest}: what must not grow however long an
 * application runs. Lives in core's package (the examples are not modular, so core is on the class path) to reach
 * package-private counters that have no public API on purpose.
 */
public final class SoakProbe {

    private SoakProbe() {
    }

    /** Delayed async tasks plus async timeouts the timeout timer still holds. */
    public static int queuedTimers() {
        return AsyncSupport.queuedTimers();
    }

    /** Threads the shared sync-timeout watchdog keeps scopes for; dead threads are unlinked every tick. */
    public static int watchdogThreads() {
        return Watchdog.shared().registeredThreads();
    }
}
