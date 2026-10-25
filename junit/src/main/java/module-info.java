/**
 * JUnit Jupiter support for resilience4cata: {@link dev.resilience4cata.junit.ResilienceTest} gives every
 * test a fresh {@link dev.resilience4cata.testkit.ManualClock} and a
 * {@link dev.resilience4cata.ResilienceRegistry} that follows it, as parameters. Built on {@code testkit};
 * compiled against JUnit Jupiter 5.10 and tested on 5.10 and 6.
 */
@org.jspecify.annotations.NullMarked
module dev.resilience4cata.junit {
    requires transitive dev.resilience4cata.testkit;
    requires transitive org.junit.jupiter.api;

    exports dev.resilience4cata.junit;
}
