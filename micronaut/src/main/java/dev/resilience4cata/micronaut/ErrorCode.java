package dev.resilience4cata.micronaut;

/**
 * Stable codes of the errors reported by the Micronaut integration (area {@code R4C-MN}), in the exception
 * messages ({@code [R4C-MN-001] ...}); {@code docs/en/errors.md} has one section per code. A code is never
 * renumbered or reused: a new error takes the next free number.
 */
enum ErrorCode {

    EXPOSED_TYPES("R4C-MN-001"),
    NOT_IMPLEMENTED("R4C-MN-002"),
    PROXIED_SCOPE("R4C-MN-003"),
    UNPROTECTED_INSTANCE("R4C-MN-004"),
    RETRY_INSIDE_TRANSACTION("R4C-MN-005"),
    INVALID_SETTING("R4C-MN-006");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }

    /** The exception message: {@code [code] } followed by {@code detail}. */
    String message(String detail) {
        return "[" + code + "] " + detail;
    }

    /** An exception that stops the startup: what is wrong, then how to fix it. */
    ResilienceBeanException exception(String problem, String fix) {
        return new ResilienceBeanException(message(problem + " " + fix));
    }
}
