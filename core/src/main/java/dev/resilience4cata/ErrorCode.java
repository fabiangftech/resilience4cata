package dev.resilience4cata;

/**
 * Stable codes of the misconfiguration errors thrown by {@link Resilient} and {@link Pipeline.Builder}
 * (area {@code R4C-RT}), and by {@link ResilienceRegistry}, its builder and the {@code *Config} builders
 * (area {@code R4C-REG}). Only the codes are public, in the exception messages ({@code [R4C-RT-003] ...});
 * {@code docs/en/errors.md} has one section per code. A code is never renumbered or reused: a new error takes the next free number,
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
    CIRCUIT_BREAKER_CLASSIFIER_WITHOUT_CIRCUIT_BREAKER("R4C-RT-017"),
    HEDGE_NOT_ASYNC("R4C-RT-018"),
    RETRY_WITH_HEDGE("R4C-RT-019"),
    NOT_FOR_STREAMS("R4C-RT-020"),

    INVALID_VALUE("R4C-REG-001"),
    INVALID_NAMED_CONFIG("R4C-REG-002"),
    INVALID_INSTANCE_CONFIG("R4C-REG-003"),
    UNKNOWN_CONFIG("R4C-REG-004"),
    DUPLICATE_CONFIG("R4C-REG-005"),
    RESERVED_CONFIG_NAME("R4C-REG-006"),
    EMPTY_INSTANCE_ID("R4C-REG-007"),
    CONFLICTING_INSTANCE("R4C-REG-008"),
    UNKNOWN_INSTANCE("R4C-REG-009");

    /** What {@link #invalidValue} puts before the detail, after the code. */
    private static final String INVALID_PREFIX = " config: ";

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

    /**
     * The exception of a {@code *Config} validation check: {@code [R4C-REG-001] Invalid <kind> config: <detail>}.
     * {@code ResilienceRegistry} and {@code resilience4cata-config} take the detail back with {@link #detail}.
     */
    static IllegalArgumentException invalidValue(String kind, String detail) {
        return new IllegalArgumentException(INVALID_VALUE.message("Invalid " + kind + INVALID_PREFIX + detail));
    }

    /** The detail of an {@link #invalidValue} exception, or the whole message of any other exception. */
    static String detail(Throwable e) {
        String message = String.valueOf(e.getMessage());
        String prefix = "[" + INVALID_VALUE.code + "] Invalid ";
        int end = message.indexOf(INVALID_PREFIX);
        return message.startsWith(prefix) && end > 0 ? message.substring(end + INVALID_PREFIX.length()) : message;
    }
}
