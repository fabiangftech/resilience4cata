package dev.resilience4cata.quarkus.deployment.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@link LoadScenarios} with the endpoints on {@code @RunOnVirtualThread}: every request runs on its own virtual
 * thread, so the sync timeouts interrupt virtual threads, and the bulkhead's waits and the retries' pauses park them.
 */
class VirtualThreadsLoadTest extends LoadScenarios {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = application(VirtualLoadResource.class);

    @Override
    String resource() {
        return "vload";
    }

    @AfterEach
    void everyCallRanOnAVirtualThread() {
        assertThat(warehouse.virtualCalls()).as("calls on virtual threads").isEqualTo(warehouse.calls());
    }
}
