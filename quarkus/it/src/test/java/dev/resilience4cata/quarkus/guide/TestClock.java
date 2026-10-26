package dev.resilience4cata.quarkus.guide;

import dev.resilience4cata.testkit.ManualClock;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

// tag::quarkus-test-clock[]
@Singleton
public class TestClock {

    @Produces
    @Singleton
    ManualClock clock() {
        return new ManualClock(); // retry pauses, waits and timeouts follow it instead of the system clock
    }
}
// end::quarkus-test-clock[]
