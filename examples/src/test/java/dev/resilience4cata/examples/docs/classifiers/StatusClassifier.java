package dev.resilience4cata.examples.docs.classifiers;

// tag::classifiers-classifier[]
import dev.resilience4cata.Classifier;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

public final class StatusClassifier implements Classifier<Response> {

    @Override
    public Outcome classify(@Nullable Response response, @Nullable Throwable failure) {
        if (failure != null) {
            return failure instanceof IOException ? Outcome.FAILURE : Outcome.IGNORE;
        }
        int status = response.status();
        if (status >= 500 || status == 429) {
            return Outcome.FAILURE;     // retried, and counted by the breaker
        }
        return status == 404 ? Outcome.IGNORE : Outcome.SUCCESS;
    }
}
// end::classifiers-classifier[]
