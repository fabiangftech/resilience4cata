package dev.resilience4cata.micronaut;

import java.io.Serial;

/**
 * A bean that resilience4cata cannot protect as declared: thrown while the bean is created, so the
 * application context fails to start (or the bean lookup fails, for a lazily created bean). The message starts
 * with an {@code R4C-MN} code, says which bean and why, and how to fix it.
 */
public final class ResilienceBeanException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    ResilienceBeanException(String message) {
        super(message);
    }
}
