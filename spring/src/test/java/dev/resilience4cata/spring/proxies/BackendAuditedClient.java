package dev.resilience4cata.spring.proxies;

/** The implementation, whose method the aspect counts. */
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
