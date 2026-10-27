package dev.resilience4cata.quarkus.usability.fixtures;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;

/** A bean implementing an annotated interface, switched on by a run-time property. */
@ApplicationScoped
@LookupIfProperty(name = "beta.enabled", stringValue = "true")
public class BetaNotifier implements Notifier {

    @Override
    public String send(String message) {
        return "beta: " + message;
    }
}
