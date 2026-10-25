package dev.resilience4cata.junit;

/**
 * Stable codes of the errors reported by the JUnit extension (area {@code R4C-JU}), in the exception messages
 * ({@code [R4C-JU-001] ...}); {@code docs/en/errors.md} has one section per code. A code is never
 * renumbered or reused: a new error takes the next free number.
 */
enum ErrorCode {

    NOT_PER_TEST("R4C-JU-001"),
    BAD_CONFIGURE_METHOD("R4C-JU-002"),
    PENDING_TASKS("R4C-JU-003");

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
