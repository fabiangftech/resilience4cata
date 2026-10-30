package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.BulkheadConfig;
import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.TimeoutConfig;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the threads that run work later on a caller's behalf carry: the work of a delay (a retry's next attempt, a
 * hedge, a rate limiter's wait), an async timeout that fires, and a queued bulkhead call that gets its slot run on
 * new virtual threads with the context class loader of the thread that started the call, and inherit no
 * {@code InheritableThreadLocal} values: not those of the request that happened to create the library's timer
 * threads, nor those of the call that freed the slot. And the library's own threads hold the library's class loader,
 * so that they do not keep an application's (a reloaded one in dev mode) alive.
 */
class ThreadContextTest {

    private static final InheritableThreadLocal<String> REQUEST = new InheritableThreadLocal<>();

    /** What a task saw: its context class loader and the inheritable value. */
    record Seen(ClassLoader loader, String request) {
        static Seen now() {
            return new Seen(Thread.currentThread().getContextClassLoader(), REQUEST.get());
        }
    }

    private final ClassLoader original = Thread.currentThread().getContextClassLoader();

    @AfterEach
    void restore() {
        Thread.currentThread().setContextClassLoader(original);
        REQUEST.remove();
    }

    private static ClassLoader loader(String name) {
        return new URLClassLoader(name, new URL[0], ThreadContextTest.class.getClassLoader());
    }

    @Test
    void aDelayedTaskRunsWithTheCallersClassLoaderAndNoInheritedValues() throws Exception {
        ClassLoader caller = loader("caller");
        Thread.currentThread().setContextClassLoader(caller);
        REQUEST.set("this request");
        CompletableFuture<Seen> later = new CompletableFuture<>();
        AsyncSupport.later(TimeUnit.MILLISECONDS.toNanos(5), () -> later.complete(Seen.now()));
        CompletableFuture<Seen> now = new CompletableFuture<>();
        AsyncSupport.later(0, () -> now.complete(Seen.now()));

        for (CompletableFuture<Seen> seen : java.util.List.of(later, now)) {
            assertThat(seen.get(5, TimeUnit.SECONDS).loader()).as("the caller's context class loader").isSameAs(caller);
            assertThat(seen.get().request()).as("no inherited value").isNull();
        }
    }

    @Test
    void anAsyncTimeoutCompletesOnAThreadWithTheCallersClassLoader() throws Exception {
        TimeoutPolicy timeout = new TimeoutPolicy("t", TimeoutConfig.builder().timeout(Duration.ofMillis(20)).build(),
                PolicyEnvironment.DEFAULT);
        ClassLoader caller = loader("caller");
        Thread.currentThread().setContextClassLoader(caller);
        REQUEST.set("this request");
        CompletableFuture<Seen> seen = new CompletableFuture<>();
        timeout.executeAsync(CompletableFuture::new).whenComplete((value, failure) -> {
            assertThat(failure).isInstanceOf(CallTimeoutException.class);
            seen.complete(Seen.now());
        });

        assertThat(seen.get(5, TimeUnit.SECONDS).loader()).isSameAs(caller);
        assertThat(seen.get().request()).isNull();
    }

    @Test
    void aQueuedBulkheadCallRunsWithItsOwnCallersClassLoaderNotTheReleasers() throws Exception {
        BulkheadPolicy bulkhead = new BulkheadPolicy("b", BulkheadConfig.builder().maxConcurrent(1)
                .maxWait(Duration.ofSeconds(5)).build(), PolicyEnvironment.DEFAULT);
        CompletableFuture<String> holding = new CompletableFuture<>();
        ClassLoader holder = loader("holder");
        ClassLoader waiter = loader("waiter");
        CompletableFuture<Seen> seen = new CompletableFuture<>();
        CountDownLatch queued = new CountDownLatch(1);

        Thread first = Thread.ofPlatform().unstarted(() -> {
            REQUEST.set("the holder's request");
            bulkhead.executeAsync(() -> holding);
        });
        first.setContextClassLoader(holder);
        first.start();
        first.join();
        Thread second = Thread.ofPlatform().unstarted(() -> {
            REQUEST.set("the waiter's request");
            bulkhead.executeAsync(() -> {
                seen.complete(Seen.now());
                return CompletableFuture.completedFuture("in");
            });
            queued.countDown();
        });
        second.setContextClassLoader(waiter);
        second.start();
        queued.await();
        assertThat(bulkhead.waitingCalls()).isOne();

        Thread releaser = Thread.ofPlatform().unstarted(() -> {
            REQUEST.set("the holder's request");
            holding.complete("done"); // frees the slot: the hand-off starts the queued call from this thread
        });
        releaser.setContextClassLoader(holder);
        releaser.start();
        releaser.join();

        assertThat(seen.get(5, TimeUnit.SECONDS).loader()).as("the queued call's own caller").isSameAs(waiter);
        assertThat(seen.get().request()).as("not the releasing call's value").isNull();
    }

    @Test
    void theLibrarysThreadsHoldTheLibrarysClassLoaderAndNoInheritedValues() throws Exception {
        Thread.currentThread().setContextClassLoader(loader("application"));
        REQUEST.set("the request that started the thread");
        CompletableFuture<Seen> seen = new CompletableFuture<>();
        Thread thread = Threads.daemon("test", () -> seen.complete(Seen.now()));
        thread.start();

        assertThat(thread.isDaemon()).isTrue();
        assertThat(seen.get(5, TimeUnit.SECONDS).loader()).isSameAs(Threads.class.getClassLoader());
        assertThat(seen.get().request()).isNull();
    }
}
