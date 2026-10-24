package dev.resilience4cata.benchmark;

import dev.resilience4cata.Classifier;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import org.jspecify.annotations.Nullable;

/** Services whose results are classified, for {@link ClassifierBenchmark}. */
public interface ClassifiedService {

    /** The same breaker configuration without a classifier: the reference. */
    @CircuitBreaker(config = "bench")
    Integer plain();

    @CircuitBreaker(config = "bench", classifier = Status.class)
    Integer classified();

    @Retry(config = "bench", classifier = Status.class)
    @CircuitBreaker(config = "bench", classifier = Status.class)
    Integer retriedAndClassified();

    /** Status codes of 500 and above are failures. */
    final class Status implements Classifier<Integer> {
        @Override
        public Outcome classify(@Nullable Integer status, @Nullable Throwable failure) {
            if (failure != null) {
                return Outcome.FAILURE;
            }
            return status != null && status >= 500 ? Outcome.FAILURE : Outcome.SUCCESS;
        }
    }

    /** Always answers 200; the boxed value is cached, so the call itself allocates nothing. */
    final class Impl implements ClassifiedService {
        private final Integer ok = 200;

        @Override
        public Integer plain() {
            return ok;
        }

        @Override
        public Integer classified() {
            return ok;
        }

        @Override
        public Integer retriedAndClassified() {
            return ok;
        }
    }
}
