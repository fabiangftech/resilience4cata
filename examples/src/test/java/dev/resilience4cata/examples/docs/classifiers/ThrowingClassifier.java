package dev.resilience4cata.examples.docs.classifiers;

import dev.resilience4cata.Classifier;
import org.jspecify.annotations.Nullable;

/** A classifier with a bug: it throws on a null body. */
public final class ThrowingClassifier implements Classifier<Response> {

    @Override
    public Outcome classify(@Nullable Response response, @Nullable Throwable failure) {
        if (response != null && response.body() == null) {
            throw new IllegalStateException("classifier bug");
        }
        return failure != null ? Outcome.FAILURE : Outcome.SUCCESS;
    }
}
