/**
 * Project Reactor support for resilience4cata: annotated methods that return a {@code Mono}, and
 * {@link dev.resilience4cata.reactor.PipelineOperator}. Depends on {@code core} and {@code reactor-core} only.
 */
// reactor-core 3.6 and reactive-streams are automatic modules (newer reactor-core versions have a module-info).
@SuppressWarnings({"requires-automatic", "requires-transitive-automatic"})
@org.jspecify.annotations.NullMarked
module dev.resilience4cata.reactor {
    requires transitive dev.resilience4cata.core;
    requires transitive reactor.core;
    requires transitive org.reactivestreams;

    exports dev.resilience4cata.reactor;
    exports dev.resilience4cata.reactor.generated;
}
