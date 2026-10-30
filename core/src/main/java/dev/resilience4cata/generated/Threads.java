package dev.resilience4cata.generated;

import java.util.concurrent.ThreadFactory;
import org.jspecify.annotations.Nullable;

/**
 * The threads the library starts. Its own threads (the timers, the watchdog) live as long as the JVM: they inherit no
 * {@code InheritableThreadLocal} values and hold the library's class loader, not the application's of the moment
 * (after a reload in dev mode, Spring DevTools' or Quarkus's, they would keep the old application alive and hand it
 * to the work they start). Work the library runs later on a caller's behalf (a delay's task, an async timeout that
 * fires, a queued bulkhead call that gets its slot) runs on a new virtual thread with the context class loader of
 * the thread that started the call, captured when the work was scheduled, and inherits no thread-local values: the
 * thread that starts it belongs to some other call (a timer, the call that freed a slot).
 */
final class Threads {

    private static final ClassLoader LIBRARY = Threads.class.getClassLoader();

    private Threads() {
    }

    /** An unstarted daemon platform thread of the library. */
    static Thread daemon(String name, Runnable body) {
        Thread thread = Thread.ofPlatform().daemon().name(name).inheritInheritableThreadLocals(false).unstarted(body);
        thread.setContextClassLoader(LIBRARY);
        return thread;
    }

    /** A factory of {@link #daemon} threads, for an executor. */
    static ThreadFactory daemons(String name) {
        return body -> daemon(name, body);
    }

    /** The context class loader to run work later with: the current thread's. */
    static @Nullable ClassLoader callers() {
        return Thread.currentThread().getContextClassLoader();
    }

    /** Starts {@code task} on a new virtual thread with {@code loader} as its context class loader. */
    static void startVirtual(String name, @Nullable ClassLoader loader, Runnable task) {
        Thread thread = Thread.ofVirtual().name(name).inheritInheritableThreadLocals(false).unstarted(task);
        thread.setContextClassLoader(loader);
        thread.start();
    }
}
