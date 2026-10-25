package dev.resilience4cata.examples.docs.classifiers;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Retry;

/** Methods whose classifier throws: the classifier's exception becomes the call's failure. */
public interface FragileApi {

    @Retry(maxAttempts = 3, delay = "0", classifier = ThrowingClassifier.class)
    Response get(String id);

    @CircuitBreaker(name = "fragile", windowSize = 1, minimumCalls = 1, classifier = ThrowingClassifier.class)
    Response recorded(String id);

    @Retry(maxAttempts = 3, delay = "0", classifier = ThrowingClassifier.class)
    @Fallback(method = "recover")
    Response withFallback(String id);

    default Response recover(String id, IllegalStateException e) {
        return new Response(200, "recovered from " + e.getMessage());
    }
}
