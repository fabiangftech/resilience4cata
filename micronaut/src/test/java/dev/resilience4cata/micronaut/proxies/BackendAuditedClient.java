package dev.resilience4cata.micronaut.proxies;

import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** The audited implementation: its advice runs once per attempt, inside the wrapper. */
@Singleton
@ResilientBean(AuditedClient.class)
@Requires(env = "proxies")
public class BackendAuditedClient implements AuditedClient {

    private final Backend backend;

    public BackendAuditedClient(Backend backend) {
        this.backend = backend;
    }

    @Audit
    @Override
    public String fetch(String id) {
        return backend.call(id);
    }
}
