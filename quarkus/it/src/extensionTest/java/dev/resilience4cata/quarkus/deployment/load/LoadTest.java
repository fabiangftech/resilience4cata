package dev.resilience4cata.quarkus.deployment.load;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.extension.RegisterExtension;

/** {@link LoadScenarios} with blocking endpoints on Quarkus's worker threads. */
class LoadTest extends LoadScenarios {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = application();

    @Override
    String resource() {
        return "load";
    }
}
