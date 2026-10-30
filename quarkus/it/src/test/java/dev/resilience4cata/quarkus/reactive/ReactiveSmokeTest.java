package dev.resilience4cata.quarkus.reactive;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;

/** {@link ReactiveScenarios} on the JVM, without the guide test's {@code ManualClock}: real pauses and timeouts. */
@QuarkusTest
@TestProfile(ReactiveSmokeTest.SystemClock.class)
class ReactiveSmokeTest extends ReactiveScenarios {

    public static class SystemClock implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.arc.exclude-types", "dev.resilience4cata.quarkus.guide.TestClock");
        }
    }
}
