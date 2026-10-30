package dev.resilience4cata.quarkus.reactive;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * {@link ReactiveScenarios} against the packaged application: the jar with {@code quarkusIntTest}, the native image
 * with {@code testNative}. Neither has the test's {@code ManualClock}.
 */
@QuarkusIntegrationTest
class ReactiveSmokeIT extends ReactiveScenarios {
}
