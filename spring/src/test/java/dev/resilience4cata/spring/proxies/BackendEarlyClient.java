package dev.resilience4cata.spring.proxies;

/** The implementation of {@link EarlyClient}. */
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
