/**
 * Micrometer metrics for resilience4cata: {@link dev.resilience4cata.micrometer.MicrometerMetrics}
 * counts policy events and reports circuit breakers, bulkheads and rate limiters as gauges. Depends
 * on {@code core} and {@code micrometer-core} only.
 */
@SuppressWarnings({"requires-automatic", "requires-transitive-automatic"}) // micrometer-core is an automatic module
@org.jspecify.annotations.NullMarked
module dev.resilience4cata.micrometer {
    requires transitive dev.resilience4cata.core;
    requires transitive micrometer.core;

    exports dev.resilience4cata.micrometer;
}
