package dev.resilience4cata;

/**
 * Stable codes of the misconfiguration errors thrown by {@link Resilient} and {@link Pipeline.Builder}.
 * Only the codes are public, in the exception messages ({@code [R4C-RT-003] ...}); {@code docs/en/errors.md}
 * has one section per code. A code is never renumbered or reused: a new error takes the next free number,
 * and a retired one stays here, marked as no longer reported.
 */
enum ErrorCode {

    NOT_AN_INTERFACE("R4C-RT-001"),
    NOT_AN_IMPLEMENTATION("R4C-RT-002"),
    NOT_GENERATED("R4C-RT-003"),
    INCOMPATIBLE_GENERATED("R4C-RT-004"),
    INACCESSIBLE_GENERATED("R4C-RT-005"),
    CONSTRUCTION_FAILED("R4C-RT-006"),
    CREATE_ON_INTERFACE("R4C-RT-007"),
    NOT_EXTENSIBLE("R4C-RT-008"),
    NO_MATCHING_CONSTRUCTOR("R4C-RT-009"),
    AMBIGUOUS_CONSTRUCTOR("R4C-RT-010"),
    BLANK_PIPELINE_ID("R4C-RT-011"),
    SET_TWICE("R4C-RT-012"),
    FALLBACK_FILTER_WITHOUT_FALLBACK("R4C-RT-013"),
    CLASSIFIER_WITHOUT_POLICY("R4C-RT-014"),
    RETRY_CLASSIFIER_WITHOUT_RETRY("R4C-RT-015"),
    EXHAUSTED_RESULT_WITHOUT_CLASSIFIER("R4C-RT-016"),
    CIRCUIT_BREAKER_CLASSIFIER_WITHOUT_CIRCUIT_BREAKER("R4C-RT-017");

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
