package dev.resilience4cata.examples.docs.fallback;

import dev.resilience4cata.annotation.Fallback;
import java.util.List;

/** A fallback on a generic method. */
public interface RepositoryApi {

    // tag::fallback-generic[]
    @Fallback(method = "nothingFound")
    <T> List<T> findAll(Class<T> type, String query);

    default <E> List<E> nothingFound(Class<E> type, String query, Exception failure) {
        return List.of();                               // same type parameters, by position
    }
    // end::fallback-generic[]
}
