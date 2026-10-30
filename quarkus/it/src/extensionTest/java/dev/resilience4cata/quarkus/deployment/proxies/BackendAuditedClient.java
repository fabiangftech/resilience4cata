package dev.resilience4cata.quarkus.deployment.proxies;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** The intercepted implementation: its interceptor runs once per attempt, inside the wrapper. */
@Singleton
public class BackendAuditedClient implements AuditedClient {

    @Inject
    Backend backend;

    @Audit
    @Override
    public String fetch(String id) {
        return backend.call(id);
    }
}
