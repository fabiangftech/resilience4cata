package dev.resilience4cata.generated;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WildcardAsyncCallTest {

    @Test
    void widenReturnsTheSameStage() {
        CompletableFuture<Integer> stage = new CompletableFuture<>();

        CompletionStage<Number> widened = AsyncCall.widen(stage);

        assertThat(widened).isSameAs(stage);
    }

    @Test
    void aWidenedStageGoesThroughAPolicy() throws Exception {
        CompletionStage<? extends Number> delegate = CompletableFuture.completedFuture(7L);
        FallbackPolicy fallback = new FallbackPolicy("demo#call()", java.util.List.of(Throwable.class), java.util.List.of());

        CompletableFuture<Number> result = fallback.executeAsync(() -> AsyncCall.<Number>widen(delegate),
                (failure, attempts) -> CompletableFuture.completedFuture(0));

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(7L);
    }
}
