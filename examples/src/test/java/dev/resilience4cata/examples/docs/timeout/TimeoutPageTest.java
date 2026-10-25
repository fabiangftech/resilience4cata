package dev.resilience4cata.examples.docs.timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallTimeoutException;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.TimeoutSnapshot;
import dev.resilience4cata.event.TimeoutEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/policies/timeout.md}. */
class TimeoutPageTest {

    private final ManualClock clock = new ManualClock();
    private final List<TimeoutEvent.TimedOut> timedOut = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(TimeoutEvent.TimedOut.class, timedOut::add)
            .build();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void testingOnAManualClock() {
        // tag::timeout-testing[]
        ReportApi slow = id -> {
            try {
                clock.sleep(Duration.ofSeconds(5));          // a slow dependency, on the test's clock
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted", e);   // how the timeout reaches the call
            }
            return "report " + id;
        };
        ReportApi reports = Resilient.of(ReportApi.class, slow, registry);

        assertThatThrownBy(() -> reports.build("r-1"))
                .isInstanceOf(CallTimeoutException.class)
                .hasCauseInstanceOf(IllegalStateException.class);   // what the call threw once interrupted
        assertThat(Thread.currentThread().isInterrupted()).isFalse(); // the interrupt is cleared
        // end::timeout-testing[]
        assertThat(timedOut).hasSize(1);
        assertThat(timedOut.getFirst().timeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void codeThatIgnoresTheInterruptRunsToTheEnd() {
        AtomicBoolean finished = new AtomicBoolean();
        ReportApi stubborn = id -> {
            clock.advance(Duration.ofSeconds(5)); // busy for 5 s, never checks the interrupt
            finished.set(true);
            return "late report";
        };
        ReportApi reports = Resilient.of(ReportApi.class, stubborn, registry);

        assertThatThrownBy(() -> reports.build("r-1"))
                .isInstanceOf(CallTimeoutException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(((CallTimeoutException) e).timeout()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(finished).isTrue(); // it ran to completion; its result was discarded
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aFastCallIsUntouched() {
        ReportApi reports = Resilient.of(ReportApi.class, id -> {
            try {
                clock.sleep(Duration.ofMillis(1_999));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "report " + id;
        }, registry);
        assertThat(reports.build("r")).isEqualTo("report r");
        assertThat(timedOut).isEmpty();
    }

    @Test
    void eachCallSiteHasItsOwnPolicyWithTheDerivedId() {
        Resilient.of(ReportApi.class, id -> "r", registry);
        TimeoutSnapshot snapshot = registry.snapshot().timeouts().getFirst();
        assertThat(snapshot.instance())
                .isEqualTo("dev.resilience4cata.examples.docs.timeout.ReportApi#build(java.lang.String)");
        assertThat(snapshot.config().timeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void asyncTimeoutsFailTheFutureAndCancelTheStage() {
        CompletableFuture<String> never = new CompletableFuture<>();
        AsyncReportApi reports = Resilient.of(AsyncReportApi.class, id -> never, registry);

        CompletableFuture<String> report = reports.build("r-1");
        clock.advance(Duration.ofMillis(1_999));
        assertThat(report).isNotDone();
        clock.advance(Duration.ofMillis(1));

        assertThatThrownBy(report::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CallTimeoutException.class);
        assertThat(never).isCancelled();
    }

    @Test
    void asyncTimeoutsDoNotCoverTheTimeTakenToReturnTheStage() {
        AsyncReportApi reports = Resilient.of(AsyncReportApi.class, id -> {
            clock.advance(Duration.ofSeconds(5)); // blocks before returning its stage
            return CompletableFuture.completedFuture("report " + id);
        }, registry);
        assertThat(reports.build("r")).isCompletedWithValue("report r");
    }

    @Test
    void pipeline() {
        AtomicInteger calls = new AtomicInteger();
        ReportApi backend = id -> "report " + id + " #" + calls.incrementAndGet();
        // tag::timeout-pipeline[]
        Pipeline<String> build = Pipeline.builder(registry, "reports", String.class)
                .timeout(t -> t.timeout(Duration.ofSeconds(2)))
                .build();

        String report = build.execute(() -> backend.build("r-1"));
        // end::timeout-pipeline[]
        assertThat(report).isEqualTo("report r-1 #1");
        assertThat(registry.snapshot().timeouts()).extracting(TimeoutSnapshot::instance).containsExactly("reports");
    }
}
