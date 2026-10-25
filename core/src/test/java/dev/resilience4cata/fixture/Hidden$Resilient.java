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
    public Hidden$Resilient(Hidden delegate, ResilienceRegistry registry) {
    }
}
