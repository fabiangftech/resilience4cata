package dev.resilience4cata.quarkus.deployment.fixtures;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/** Creates {@link DevUiService} at startup, so that its instances exist before a test reads the Dev UI. */
@Singleton
public class DevUiWarmup {

    void onStart(@Observes StartupEvent event, DevUiService service) {
        service.call();
    }
}
