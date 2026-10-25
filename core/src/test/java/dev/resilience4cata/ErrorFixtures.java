package dev.resilience4cata;

/** Interfaces whose hand-written "generated" classes are broken on purpose, for {@link ErrorCodeTest}. */
final class ErrorFixtures {

    private ErrorFixtures() {
    }

    /** Its generated class lacks the {@code (Mismatched, ResilienceRegistry)} constructor. */
    public interface Mismatched {
    }

    /** Its generated class's constructor throws a checked exception. */
    public interface Failing {
    }
}
