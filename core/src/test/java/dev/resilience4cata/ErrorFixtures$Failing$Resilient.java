package dev.resilience4cata;

/** Stands in for a generated class whose constructor fails with a checked exception. */
public final class ErrorFixtures$Failing$Resilient implements ErrorFixtures.Failing {

    /**
     * Throws a checked exception without declaring it.
     *
     * @param delegate unused
     * @param registry unused
     */
    // java:S1172: Resilient.of looks this constructor up by its (interface, registry) signature
    @SuppressWarnings("java:S1172")
    public ErrorFixtures$Failing$Resilient(ErrorFixtures.Failing delegate, ResilienceRegistry registry) {
        ErrorFixtures$Failing$Resilient.<RuntimeException>sneakyThrow(new Exception("checked"));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}
