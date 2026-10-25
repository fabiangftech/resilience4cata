package dev.resilience4cata.processor;

import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * Claims the library's own annotations ({@code dev.resilience4cata.annotation.*}) after
 * {@link ResilienceProcessor} has used them, so that {@code -Xlint:processing} does not report them as
 * "claimed by no processor" when other annotation processors are on the processor path.
 *
 * <p>{@link ResilienceProcessor} supports {@code "*"} (composed annotations are user-defined) and claims
 * nothing when it shares the path with other processors, so that they still see every annotation. That
 * leaves only this library's annotations unclaimed, and no other processor needs them. javac calls the
 * processors of one service file in the order they are listed: this one is listed right after
 * {@code ResilienceProcessor}, which has therefore already run in the round.
 */
public final class AnnotationClaimer extends AbstractProcessor {

    /** Creates the processor. Called by the compiler through {@link java.util.ServiceLoader}. */
    public AnnotationClaimer() {
        // Nothing to set up: the processor keeps no state.
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("dev.resilience4cata.annotation.*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return true;
    }
}
