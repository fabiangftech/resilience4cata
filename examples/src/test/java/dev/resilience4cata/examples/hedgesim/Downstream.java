package dev.resilience4cata.examples.hedgesim;

import dev.resilience4cata.testkit.ManualClock;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * A simulated dependency with a fixed capacity, on a {@link ManualClock}: {@code workers} requests are served at a
 * time, the rest wait in one FIFO queue. Every attempt of every caller goes into that same queue, so a hedge competes
 * with the first attempts of other calls, as it would against a real service.
 *
 * <p>Cancellation (the hedge cancels the losers) reaches the service only with {@link CancelMode#REACHES_SERVER}: a
 * queued request is then dropped and one in service stops at once. With {@link CancelMode#IGNORED}, the usual case for
 * a remote call (the client stops waiting, the server does not know), the service does the whole work anyway, and that
 * work is counted as wasted.
 *
 * <p>Single-threaded: every request and completion runs on the thread that advances the clock.
 */
final class Downstream {

    /** Whether a cancelled attempt stops its work on the server. */
    enum CancelMode {
        /** The server never hears of the cancellation: queued requests are still served. */
        IGNORED,
        /** A cancelled request leaves the queue, or stops in service and frees its worker. */
        REACHES_SERVER
    }

    private final ManualClock clock;
    private final ServiceTime serviceTime;
    private final int workers;
    private final CancelMode cancelMode;
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private int busy;
    private long requests;
    private long busyNanos;
    private long wastedNanos;

    Downstream(ManualClock clock, ServiceTime serviceTime, int workers, CancelMode cancelMode) {
        this.clock = clock;
        this.serviceTime = serviceTime;
        this.workers = workers;
        this.cancelMode = cancelMode;
    }

    /** Sends one request (one attempt); the stage completes when a worker has served it. */
    CompletableFuture<Long> call() {
        requests++;
        Request request = new Request();
        if (cancelMode == CancelMode.REACHES_SERVER) {
            request.future.whenComplete((value, failure) -> {
                if (request.future.isCancelled()) {
                    request.cancel();
                }
            });
        }
        queue.add(request);
        dispatch();
        return request.future;
    }

    private void dispatch() {
        while (busy < workers && !queue.isEmpty()) {
            Request next = queue.poll();
            if (next.future.isCancelled() && cancelMode == CancelMode.REACHES_SERVER) {
                continue;
            }
            busy++;
            next.serve(serviceTime.next());
        }
    }

    /** Requests sent so far: first attempts and hedges. */
    long requests() {
        return requests;
    }

    /** Worker time spent, in nanoseconds, useful or not. */
    long busyNanos() {
        return busyNanos;
    }

    /** Worker time spent on requests whose caller had already stopped waiting. */
    long wastedNanos() {
        return wastedNanos;
    }

    int queued() {
        return queue.size();
    }

    int busy() {
        return busy;
    }

    private final class Request {
        final CompletableFuture<Long> future = new CompletableFuture<>();
        long startedAt;
        long duration;
        Future<?> completion;

        void serve(long nanos) {
            startedAt = clock.read();
            duration = nanos;
            completion = clock.schedule(nanos, this::finish);
        }

        void finish() {
            busy--;
            busyNanos += duration;
            if (!future.complete(duration)) {
                wastedNanos += duration;
            }
            dispatch();
        }

        /** Only with {@link CancelMode#REACHES_SERVER}. */
        void cancel() {
            if (completion == null) {
                return; // still queued: dispatch skips it
            }
            if (completion.cancel(false)) {
                long spent = clock.read() - startedAt;
                busy--;
                busyNanos += spent;
                wastedNanos += spent;
                dispatch();
            }
        }
    }
}
