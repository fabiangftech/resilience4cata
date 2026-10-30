package dev.resilience4cata.quarkus.deployment.proxies;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Calls a protected bean at startup, before the extension creates the registry (its observer has priority 1000). */
@Singleton
public class EarlyStartup {

    public volatile String result;
    public volatile int callsAtStartup;

    @Inject
    EarlyClient client;

    @Inject
    Backend backend;

    void onStart(@Observes @Priority(1) StartupEvent event) {
        backend.failures.set(2);
        result = client.fetch("startup");
        callsAtStartup = backend.calls.get();
        backend.calls.set(0);
    }
}
