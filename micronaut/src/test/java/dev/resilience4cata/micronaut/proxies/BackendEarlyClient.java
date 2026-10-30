package dev.resilience4cata.micronaut.proxies;

import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** The implementation of {@link EarlyClient}. */
@Singleton
@ResilientBean(EarlyClient.class)
@Requires(env = "proxies")
public class BackendEarlyClient implements EarlyClient {

    private final Backend backend;

    public BackendEarlyClient(Backend backend) {
        this.backend = backend;
    }

    @Override
    public String fetch(String id) {
        return backend.call(id);
    }
}
