package dev.resilience4cata.examples.docs.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.FallbackContext;
import dev.resilience4cata.FallbackHandler;
import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.event.FallbackEvent;
import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Every claim and snippet of {@code docs/en/policies/fallback.md}. */
class FallbackPageTest {

    private final ManualClock clock = new ManualClock();
    private final List<FallbackEvent.Applied> applied = new CopyOnWriteArrayList<>();
    private final ResilienceRegistry registry = ResilienceTesting.registry(clock)
            .listen(FallbackEvent.Applied.class, applied::add)
            .build();

    @Test
    void aSiblingMethodAnswersForAnyException() {
        PriceApi prices = Resilient.of(PriceApi.class, sku -> {
            throw new IllegalStateException("down");
        }, registry);
        assertThat(prices.price("sku-1")).isEqualTo(-1);
        assertThat(applied).hasSize(1);
        assertThat(applied.getFirst().instance())
                .isEqualTo("dev.resilience4cata.examples.docs.fallback.PriceApi#price(java.lang.String)");
    }

    @Test
    void anErrorDoesNotMatchAnExceptionParameter() {
        PriceApi prices = Resilient.of(PriceApi.class, sku -> {
            throw new AssertionError("an Error");
        }, registry);
        assertThatThrownBy(() -> prices.price("sku-1")).isInstanceOf(AssertionError.class);
    }

    @Test
    void overloadsArePickedByExceptionType() throws IOException {
        AtomicReference<IOException> failure = new AtomicReference<>();
        CatalogApi catalog = Resilient.of(CatalogApi.class, new Catalog(failure), registry);

        failure.set(new FileNotFoundException("gone"));
        assertThat(catalog.describe("sku-1")).isEqualTo("no such product: sku-1");
        failure.set(new IOException("reset"));
        assertThat(catalog.describe("sku-1")).isEqualTo("description unavailable");

        registry.circuitBreaker("catalog").forceOpen();
        assertThat(catalog.describe("sku-1")).isEqualTo("catalog is resting");
    }

    @Test
    void aFailureNoOverloadAcceptsIsRethrown() {
        CatalogApi catalog = Resilient.of(CatalogApi.class, new Catalog(new AtomicReference<>()) {
            @Override
            public String describe(String sku) {
                throw new IllegalStateException("bug");
            }
        }, registry);
        assertThatThrownBy(() -> catalog.describe("sku-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyOnAndSkipOn() throws IOException {
        AtomicReference<IOException> failure = new AtomicReference<>();
        CatalogApi catalog = Resilient.of(CatalogApi.class, new Catalog(failure), registry);

        failure.set(new IOException("reset"));
        assertThat(catalog.search("q")).isEqualTo("[]");
        failure.set(new FileNotFoundException("index missing"));
        assertThatThrownBy(() -> catalog.search("q")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void aThrowableParameterTakesErrorsToo() {
        CatalogApi catalog = Resilient.of(CatalogApi.class, new Catalog(new AtomicReference<>()) {
            @Override
            public CompletableFuture<String> describeAsync(String sku) {
                throw new AssertionError("an Error, thrown before any stage exists");
            }
        }, registry);
        assertThat(catalog.describeAsync("sku-1")).isCompletedWithValue("cached description of sku-1");
    }

    @Test
    void aVoidMethodCanHaveAVoidSibling() {
        RefreshApi refresh = Resilient.of(RefreshApi.class, id -> {
            throw new IllegalStateException("down");
        }, registry);
        int before = RefreshApi.SKIPPED.get();
        refresh.refresh("a");
        assertThat(RefreshApi.SKIPPED.get()).isEqualTo(before + 1);
    }

    @Test
    void aCheckedExceptionTheMethodDoesNotDeclareIsWrapped() {
        Pipeline<Long> quote = Pipeline.builder(registry, "quotes-checked", Long.class)
                .fallbackHandler(context -> {
                    throw new IOException("fallback failed");
                })
                .build();
        assertThatThrownBy(() -> quote.execute(() -> {
            throw new IllegalStateException("x");
        })).isInstanceOf(java.lang.reflect.UndeclaredThrowableException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void aGenericMethodWithAGenericSibling() {
        RepositoryApi repository = Resilient.of(RepositoryApi.class, new RepositoryApi() {
            @Override
            public <T> List<T> findAll(Class<T> type, String query) {
                throw new IllegalStateException("database down");
            }
        }, registry);
        List<String> found = repository.findAll(String.class, "q");
        assertThat(found).isEmpty();
    }

    @Test
    void asyncFallback() {
        CatalogApi catalog = Resilient.of(CatalogApi.class, new Catalog(new AtomicReference<>()), registry);
        assertThat(catalog.describeAsync("sku-1")).isCompletedWithValue("cached description of sku-1");
    }

    @Test
    void testingOnAManualClock() {
        // tag::fallback-testing[]
        AtomicInteger calls = new AtomicInteger();
        QuoteApi backend = (sku, quantity) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("pricing down");
        };
        QuoteApi quotes = Resilient.of(QuoteApi.class, backend, registry);

        assertThat(quotes.quote("sku-1", 3)).isEqualTo(300L);   // ListPrice, once the retries gave up
        assertThat(calls).hasValue(3);
        // end::fallback-testing[]
    }

    @Test
    void theHandlerSeesTheAttemptsAndTheFailure() {
        AtomicReference<FallbackContext> seen = new AtomicReference<>();
        Recording.seen = seen;
        RecordingQuoteApi quotes = Resilient.of(RecordingQuoteApi.class, (sku, quantity) -> {
            throw new IllegalStateException("pricing down");
        }, registry);
        assertThat(quotes.quote("sku-1", 3)).isZero();
        FallbackContext context = seen.get();
        assertThat(context.attempts()).isEqualTo(3);
        assertThat(context.failure()).isInstanceOf(IllegalStateException.class);
        assertThat(context.argumentCount()).isEqualTo(2);
        assertThat(context.argument(0, String.class)).isEqualTo("sku-1");
        assertThat(context.instanceId())
                .isEqualTo("dev.resilience4cata.examples.docs.fallback.RecordingQuoteApi#quote(java.lang.String,int)");
    }

    @Test
    void pipeline() {
        QuoteApi backend = (sku, quantity) -> {
            throw new IllegalStateException("pricing down");
        };
        // tag::fallback-pipeline[]
        Pipeline<Long> quote = Pipeline.builder(registry, "quotes", Long.class)
                .retry(r -> r.maxAttempts(3))
                .fallbackHandler(context -> 100L * context.attempts())
                .build();

        long price = quote.execute(() -> backend.quote("sku-1", 3));
        // end::fallback-pipeline[]
        assertThat(price).isEqualTo(300L);
    }

    @Test
    void pipelineFunctionFallback() {
        Pipeline<Long> quote = Pipeline.builder(registry, "quotes-fn", Long.class)
                .fallback(failure -> -1L)
                .build();
        assertThat(quote.execute(() -> {
            throw new IllegalStateException("x");
        })).isEqualTo(-1L);
    }

    /** Records the context its handler gets. */
    static final class Recording implements FallbackHandler<Long> {
        static volatile AtomicReference<FallbackContext> seen;

        @Override
        public Long apply(FallbackContext context) {
            seen.set(context);
            return 0L;
        }
    }

    private static class Catalog implements CatalogApi {
        private final AtomicReference<IOException> failure;

        Catalog(AtomicReference<IOException> failure) {
            this.failure = failure;
        }

        @Override
        public String describe(String sku) throws IOException {
            throw failure.get();
        }

        @Override
        public String search(String query) throws IOException {
            throw failure.get();
        }

        @Override
        public CompletableFuture<String> describeAsync(String sku) {
            return CompletableFuture.failedFuture(new IllegalStateException("down"));
        }
    }
}
