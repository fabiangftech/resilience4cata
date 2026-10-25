package dev.resilience4cata.examples.docs.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The claims of docs/{en,es}/guides/async.md. */
class AsyncTest {

    /** Fails twice (once by throwing, once with a failed stage), then answers on another thread. */
    static final class HttpTrackingClient implements TrackingClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<Boolean> virtual = new CopyOnWriteArrayList<>();
        final CompletableFuture<String> neverCompletes = new CompletableFuture<>();

        @Override
        public CompletableFuture<String> track(String parcel) {
            virtual.add(Thread.currentThread().isVirtual());
            int call = calls.incrementAndGet();
            if (parcel.equals("lost")) {
                return CompletableFuture.failedFuture(new IOException("no such parcel"));
            }
            if (call == 1) {
                throw new IllegalStateException("thrown before any stage exists");
            }
            if (call == 2) {
                return CompletableFuture.failedFuture(new IOException("busy"));
            }
            return CompletableFuture.supplyAsync(() -> "in transit");
        }

        @Override
        public CompletionStage<String> eta(String parcel) {
            return neverCompletes;
        }
    }

    @Test
    void retriesWithoutBlockingTheCaller() throws Exception {
        HttpTrackingClient http = new HttpTrackingClient();
        // tag::async-call[]
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        TrackingClient tracking = Resilient.of(TrackingClient.class, http, registry);

        CompletableFuture<String> status = tracking.track("p-1");   // returns at once: no thread sleeps between attempts
        // status completes with "in transit" after two failed attempts
        // end::async-call[]

        assertThat(status.get(5, TimeUnit.SECONDS)).isEqualTo("in transit");
        assertThat(http.calls).hasValue(3);
        // the first attempt runs on the caller's thread, the retries on new virtual threads
        assertThat(http.virtual).containsExactly(Thread.currentThread().isVirtual(), true, true);
    }

    @Test
    void theFallbackGetsTheUnwrappedFailure() throws Exception {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        HttpTrackingClient http = new HttpTrackingClient();
        TrackingClient tracking = Resilient.of(TrackingClient.class, http, registry);

        assertThat(tracking.track("lost").get(5, TimeUnit.SECONDS)).isEqualTo("unknown (no such parcel)");
        assertThat(http.calls).hasValue(3);
    }

    @Test
    void aTimeoutFailsTheReturnedFutureAndCancelsTheLateStage() {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        HttpTrackingClient http = new HttpTrackingClient();
        TrackingClient tracking = Resilient.of(TrackingClient.class, http, registry);
        // tag::async-timeout[]
        CompletableFuture<String> eta = tracking.eta("p-1").toCompletableFuture();

        assertThatThrownBy(() -> eta.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CallTimeoutException.class);   // after 100 ms; no thread was blocked
        // end::async-timeout[]
        assertThatThrownBy(() -> http.neverCompletes.get(5, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
    }

    // java:S5838: the value is a Number, which has no isZero(); equality also checks that it is an Integer
    @SuppressWarnings("java:S5838")
    @Test
    void aWildcardStageWorksWithItsUpperBound() throws Exception {
        ResilienceRegistry registry = ResilienceRegistry.builder().build();
        ParcelClient parcels = Resilient.of(ParcelClient.class,
                parcel -> CompletableFuture.failedFuture(new IOException("scale offline")), registry);

        Number weight = parcels.weight("p-1").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(weight).isEqualTo(0);

        ParcelClient working = Resilient.of(ParcelClient.class, parcel -> CompletableFuture.completedFuture(2.5), registry);
        assertThat(working.weight("p-1").toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo(2.5);
    }
}
