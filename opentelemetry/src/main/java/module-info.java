/**
 * OpenTelemetry metrics for resilience4cata: {@link dev.resilience4cata.opentelemetry.OpenTelemetryMetrics}
 * turns the registry's events into counters and reads its snapshots into asynchronous gauges. Depends on
 * {@code core} and the OpenTelemetry API only; bring your own SDK.
 */
@SuppressWarnings({"requires-automatic", "requires-transitive-automatic"}) // the OpenTelemetry API has no module-info
@org.jspecify.annotations.NullMarked
module dev.resilience4cata.opentelemetry {
    requires transitive dev.resilience4cata.core;
    requires transitive io.opentelemetry.api;

    exports dev.resilience4cata.opentelemetry;
}
