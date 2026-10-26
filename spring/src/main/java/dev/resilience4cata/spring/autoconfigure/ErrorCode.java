package dev.resilience4cata.spring.autoconfigure;

/**
 * Stable codes of the errors reported by the Spring integration (area {@code R4C-SPR}), in the exception
 * messages ({@code [R4C-SPR-001] ...}); {@code docs/en/errors.md} has one section per code. A code is
 * never renumbered or reused: a new error takes the next free number.
 */
enum ErrorCode {

    UNPROTECTED_INSTANCE("R4C-SPR-001"),
    ANNOTATED_CONFIGURATION("R4C-SPR-002"),
    SEVERAL_INTERFACES("R4C-SPR-003");

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
}
