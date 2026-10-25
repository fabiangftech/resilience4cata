package dev.resilience4cata.testkit;

/**
 * Stable codes of the errors thrown by the testkit (area {@code R4C-TK}), in the exception messages
 * ({@code [R4C-TK-001] ...}); {@code docs/en/errors.md} has one section per code. A code is never
 * renumbered or reused: a new error takes the next free number.
 */
enum ErrorCode {

    CLOCK_BACKWARDS("R4C-TK-001"),
    NEVER_IDLE("R4C-TK-002"),
    BREAKER_NOT_OPEN("R4C-TK-003");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }

    /** The message prefix: the code in brackets and a space. */
    String prefix() {
        return "[" + code + "] ";
    }
}
