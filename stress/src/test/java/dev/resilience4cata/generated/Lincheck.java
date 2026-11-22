package dev.resilience4cata.generated;

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;

/** The model-checking options every Lincheck test uses, sized for {@code check}; {@code -Plincheck.iterations=<n>} for more. */
final class Lincheck {

    private Lincheck() {
    }

    static ModelCheckingOptions modelChecking() {
        return new ModelCheckingOptions()
                .iterations(Integer.getInteger("lincheck.iterations", 30))
                .invocationsPerIteration(500)
                .threads(2)
                .actorsPerThread(3)
                .actorsBefore(2)
                .actorsAfter(1);
    }
}
