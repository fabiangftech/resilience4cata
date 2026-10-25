package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.FallbackEvent;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** {@link FallbackEvent.Applied} is published once when a fallback runs, and never when none does. */
class FallbackEventEndToEndTest {

    private static final String PREFIX = "dev.resilience4cata.examples.FallbackEventClient#";

    private final List<FallbackEvent.Applied> applied = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceRegistry.builder()
            .listen(FallbackEvent.Applied.class, applied::add)
            .build();

    private final FallbackEventClient client = Resilient.of(FallbackEventClient.class, new FallbackEventClient() {
        @Override
        public String load(String id, Throwable failure) throws IOException {
            return fail(failure);
        }

        @Override
        public String overloaded(String id, Throwable failure) throws IOException {
            return fail(failure);
        }

        @Override
        public String retried(String id, Throwable failure) throws IOException {
            return fail(failure);
        }

        @Override
        public String total(String id, Throwable failure) throws IOException {
            return fail(failure);
        }

        @Override
        public String handled(String id, Throwable failure) throws IOException {
            return fail(failure);
        }

        @Override
        public CompletableFuture<String> loadAsync(String id, Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public CompletableFuture<String> retriedAsync(String id, Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public CompletableFuture<String> handledAsync(String id, Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }, registry);

    private static String fail(Throwable failure) throws IOException {
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (Error) failure;
    }

    @Test
    void aFailureNoSiblingTakesIsRethrownWithoutAnEvent() {
        AssertionError error = new AssertionError("boom");
        assertThatThrownBy(() -> client.load("1", error)).isSameAs(error);
        IllegalStateException state = new IllegalStateException("state");
        assertThatThrownBy(() -> client.load("1", state)).isSameAs(state);

        assertThat(applied).isEmpty();
    }

    @Test
    void aFailureASiblingTakesPublishesOneEvent() throws IOException {
        IOException down = new IOException("down");
        assertThat(client.load("1", down)).isEqualTo("cached 1");

        assertThat(applied).containsExactly(new FallbackEvent.Applied(PREFIX + "load(java.lang.String,java.lang.Throwable)",
                applied.get(0).nanoTime(), down));
    }

    @Test
    void overloadsPublishOnlyForTheTypesTheyTake() throws IOException {
        IllegalArgumentException argument = new IllegalArgumentException();
        assertThatThrownBy(() -> client.overloaded("1", argument)).isSameAs(argument);
        IOException io = new IOException();
        assertThatThrownBy(() -> client.overloaded("1", io)).as("an IOException that is not a FileNotFoundException")
                .isSameAs(io);
        assertThat(applied).isEmpty();

        FileNotFoundException missing = new FileNotFoundException();
        assertThat(client.overloaded("1", missing)).isEqualTo("missing 1");
        IllegalStateException state = new IllegalStateException();
        assertThat(client.overloaded("2", state)).isEqualTo("state 2");
        assertThat(applied).extracting(FallbackEvent.Applied::failure).containsExactly(missing, state);
    }

    @Test
    void theFallbackFusedIntoTheRetryPublishesOnlyWhenItRuns() throws IOException {
        IllegalStateException state = new IllegalStateException();
        assertThatThrownBy(() -> client.retried("1", state)).isSameAs(state);
        assertThat(applied).isEmpty();

        assertThat(client.retried("1", new IOException())).isEqualTo("cached 1");
        assertThat(applied).hasSize(1);
    }

    @Test
    void aSiblingThatTakesEverythingAlwaysPublishes() throws IOException {
        assertThat(client.total("1", new AssertionError())).isEqualTo("anything 1");
        assertThat(applied).hasSize(1);
    }

    @Test
    void aHandlerPublishesUnlessTheFailureIsSkipped() throws IOException {
        IllegalArgumentException skipped = new IllegalArgumentException();
        assertThatThrownBy(() -> client.handled("1", skipped)).isSameAs(skipped);
        assertThat(applied).isEmpty();

        assertThat(client.handled("1", new AssertionError())).isEqualTo("fixed");
        assertThat(applied).hasSize(1);
    }

    @Test
    void asyncFallbacksPublishOnlyWhenTheyRun() {
        IllegalStateException state = new IllegalStateException();
        assertThatThrownBy(() -> client.loadAsync("1", state).join()).isInstanceOf(CompletionException.class)
                .hasCause(state);
        assertThatThrownBy(() -> client.retriedAsync("1", state).join()).isInstanceOf(CompletionException.class)
                .hasCause(state);
        IllegalArgumentException skipped = new IllegalArgumentException();
        assertThatThrownBy(() -> client.handledAsync("1", skipped).join()).isInstanceOf(CompletionException.class)
                .hasCause(skipped);
        assertThat(applied).isEmpty();

        assertThat(client.loadAsync("1", new IOException()).join()).isEqualTo("cached 1");
        assertThat(client.retriedAsync("2", new IOException()).join()).isEqualTo("cached 2");
        assertThat(client.handledAsync("3", state).join()).isEqualTo("fixed");
        assertThat(applied).extracting(FallbackEvent.Applied::instance).containsExactly(
                PREFIX + "loadAsync(java.lang.String,java.lang.Throwable)",
                PREFIX + "retriedAsync(java.lang.String,java.lang.Throwable)",
                PREFIX + "handledAsync(java.lang.String,java.lang.Throwable)");
    }

    @Test
    void pipelineFallbacksAgree() throws IOException {
        Pipeline<String> pipeline = Pipeline.builder(registry, "pipeline", String.class)
                .retry(retry -> retry.maxAttempts(2).delay(java.time.Duration.ZERO))
                .fallback(failure -> "fallback")
                .fallbackApplyOn(List.of(IOException.class))
                .build();
        IllegalStateException state = new IllegalStateException();
        assertThatThrownBy(() -> pipeline.execute(() -> {
            throw state;
        })).isSameAs(state);
        assertThat(applied).isEmpty();

        assertThat(pipeline.execute(() -> {
            throw new IOException();
        })).isEqualTo("fallback");
        assertThat(pipeline.executeAsync(() -> CompletableFuture.failedFuture(new IOException())).join())
                .isEqualTo("fallback");
        assertThat(pipeline.executeAsync(() -> CompletableFuture.<String>failedFuture(state)))
                .failsWithin(java.time.Duration.ofSeconds(5));
        assertThat(applied).extracting(FallbackEvent.Applied::instance).containsExactly("pipeline", "pipeline");
    }
}
