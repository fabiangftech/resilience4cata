package dev.resilience4cata.spring.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link LoadTest}'s scenarios with {@code spring.threads.virtual.enabled=true}: Tomcat runs every request on its own
 * virtual thread, so the sync timeouts interrupt virtual threads, and the bulkhead's waits and the retries' pauses
 * park them.
 */
@SpringBootTest(classes = LoadTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.web-application-type=servlet",
        "spring.threads.virtual.enabled=true",
        "management.endpoints.web.exposure.include=resilience4cata"})
class VirtualThreadsLoadTest extends LoadTest {

    @AfterEach
    void everyCallRanOnAVirtualThread() {
        assertThat(warehouse.virtualCalls).as("calls on virtual threads").hasValue(warehouse.calls.get());
    }
}
