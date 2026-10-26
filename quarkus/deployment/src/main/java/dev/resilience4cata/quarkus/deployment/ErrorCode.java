package dev.resilience4cata.quarkus.deployment;

/**
 * Stable codes of the errors reported by the Quarkus extension at build time (area {@code R4C-QK}), in the
 * messages ({@code [R4C-QK-001] ...}); {@code docs/en/errors.md} has one section per code. A code is never
 * renumbered or reused: a new error takes the next free number.
 */
enum ErrorCode {

    FAULT_TOLERANCE_CLASH("R4C-QK-001"),
    RETRY_INSIDE_TRANSACTION("R4C-QK-002"),
    SEVERAL_INTERFACES("R4C-QK-003"),
    PRODUCER_ON_ANNOTATED_CLASS("R4C-QK-004"),
    UNPROXYABLE_ANNOTATED_CLASS("R4C-QK-005");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }

    /** The message: {@code [code] } followed by {@code detail}. */
    String message(String detail) {
        return "[" + code + "] " + detail;
    }
}
