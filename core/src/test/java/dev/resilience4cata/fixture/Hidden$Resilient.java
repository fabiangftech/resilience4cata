package dev.resilience4cata.fixture;

import dev.resilience4cata.ResilienceRegistry;

/** Package-private on purpose: core cannot access it. */
final class Hidden$Resilient implements Hidden {

    /**
     * The constructor {@code Resilient.of} looks for.
     *
     * @param delegate unused
     * @param registry unused
     */
    // java:S1172: Resilient.of looks this constructor up by its (interface, registry) signature
    @SuppressWarnings("java:S1172")
    public Hidden$Resilient(Hidden delegate, ResilienceRegistry registry) {
        // empty: only the signature and the class's access matter to the test
    }
}
