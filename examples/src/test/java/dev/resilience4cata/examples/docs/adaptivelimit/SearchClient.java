package dev.resilience4cata.examples.docs.adaptivelimit;

// tag::adaptive-limit-minimal[]
import dev.resilience4cata.annotation.AdaptiveLimit;

public interface SearchClient {

    @AdaptiveLimit
    String search(String query);
}
// end::adaptive-limit-minimal[]
