/** Annotation processor that validates resilience annotations and generates wrapper classes. */
module dev.resilience4cata.processor {
    requires java.compiler;
    requires dev.resilience4cata.annotations;

    // Order matters, as in META-INF/services: the claimer must run right after the processor (see AnnotationClaimer).
    provides javax.annotation.processing.Processor with dev.resilience4cata.processor.ResilienceProcessor,
            dev.resilience4cata.processor.AnnotationClaimer;
}
