package dev.resilience4cata.micronaut.fixtures;

import dev.resilience4cata.testkit.ManualClock;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * The registry's clock in every test context of this module but the load tests', which run on the system clock (also
 * a snippet of the Micronaut guide).
 */
@Requires(notEnv = "load")
// tag::micronaut-test-clock[]
@Factory
public class Clocks {

    @Singleton
    ManualClock manualClock() {
        return new ManualClock(); // retry pauses, waits and timeouts follow it instead of the system clock
    }
}
// end::micronaut-test-clock[]
