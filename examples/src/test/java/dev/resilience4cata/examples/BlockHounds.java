package dev.resilience4cata.examples;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.blockhound.BlockHound;

/**
 * BlockHound, installed once for the JVM of {@code blockHoundTest} ({@code BlockHound.install} only works the
 * first time), recording every blocking call made on a non-blocking thread into {@link #BLOCKING}.
 */
final class BlockHounds {

    /** The blocking calls seen since the last {@code clear()}, each with the thread and the stack. */
    static final List<String> BLOCKING = new CopyOnWriteArrayList<>();

    private static boolean installed;

    private BlockHounds() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        // install(...), not builder().install(): only install loads the integrations on the class path, Reactor's
        // among them, which marks the Reactor schedulers' threads as non-blocking.
        BlockHound.install(builder -> builder
                // Cancelling a timer task (a bulkhead waiter's, a retry's backoff, a stream timeout's re-check) removes
                // it from the shared ScheduledThreadPoolExecutor's queue under its lock: the same short lock as
                // scheduling (offer) and the timer thread's take, which Reactor's own BlockHound integration allows for
                // its schedulers.
                .allowBlockingCallsInside("java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue", "remove")
                .blockingMethodCallback(method -> BLOCKING.add(method + " on " + Thread.currentThread().getName()
                        + "\n" + stack())));
    }

    private static String stack() {
        StringBuilder text = new StringBuilder();
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            text.append("    at ").append(frame).append('\n');
        }
        return text.toString();
    }
}
