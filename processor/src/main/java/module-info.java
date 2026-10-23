/** Annotation processor that validates resilience annotations and generates wrapper classes. */
module dev.resilience4cata.processor {
    requires java.compiler;
    requires dev.resilience4cata.annotations;

    provides javax.annotation.processing.Processor with dev.resilience4cata.processor.ResilienceProcessor;
}
