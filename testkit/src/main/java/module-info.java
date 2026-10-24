/**
 * Test support for resilience4cata: a {@link dev.resilience4cata.testkit.ManualClock} that replaces the
 * system clock and the library's timers in a registry, so that tests move time by hand instead of
 * waiting. No dependencies besides {@code core}; works with any test framework.
 */
@org.jspecify.annotations.NullMarked
module dev.resilience4cata.testkit {
    requires transitive dev.resilience4cata.core;

    exports dev.resilience4cata.testkit;
}
