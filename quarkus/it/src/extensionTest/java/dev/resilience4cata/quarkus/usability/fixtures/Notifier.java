package dev.resilience4cata.quarkus.usability.fixtures;

import dev.resilience4cata.annotation.Retry;

/** A plug-in interface: every implementation is a bean, and the application calls them all. */
@Retry(maxAttempts = 2, delay = "0ms")
public interface Notifier {

    String send(String message);
}
