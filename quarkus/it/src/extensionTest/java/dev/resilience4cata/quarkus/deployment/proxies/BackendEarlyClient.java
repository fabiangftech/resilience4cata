package dev.resilience4cata.quarkus.deployment.proxies;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** The implementation of {@link EarlyClient}. */
@Singleton
public class BackendEarlyClient implements EarlyClient {

    @Inject
    Backend backend;

    @Override
    public String fetch(String id) {
        return backend.call(id);
    }
}
